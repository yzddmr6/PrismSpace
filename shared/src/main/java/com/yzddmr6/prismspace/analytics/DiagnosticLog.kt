package com.yzddmr6.prismspace.analytics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.FileProvider
import com.yzddmr6.prismspace.PrismApplication
import com.yzddmr6.prismspace.util.Users
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class DiagnosticSection(
    val title: String,
    val body: String,
)

internal data class DiagnosticSnapshotSession(val token: String, val totalLength: Long)
internal data class DiagnosticSnapshotChunk(val bytes: ByteArray, val eof: Boolean)

internal class DiagnosticSnapshotLeaseRegistry(
    private val maxActive: Int,
    private val ttlMs: Long,
) {
    private val lastAccessByToken = mutableMapOf<String, Long>()

    init {
        require(maxActive > 0)
        require(ttlMs > 0L)
    }

    fun expire(nowMs: Long): Set<String> {
        val expired = lastAccessByToken
            .filterValues { lastAccessMs -> nowMs - lastAccessMs >= ttlMs }
            .keys
            .toSet()
        expired.forEach(lastAccessByToken::remove)
        return expired
    }

    fun hasCapacity(): Boolean = lastAccessByToken.size < maxActive

    fun acquire(token: String, nowMs: Long): Boolean {
        if (token in lastAccessByToken || !hasCapacity()) return false
        lastAccessByToken[token] = nowMs
        return true
    }

    fun touch(token: String, nowMs: Long): Boolean {
        val lastAccessMs = lastAccessByToken[token] ?: return false
        if (nowMs - lastAccessMs >= ttlMs) {
            lastAccessByToken.remove(token)
            return false
        }
        lastAccessByToken[token] = nowMs
        return true
    }

    fun release(token: String) {
        lastAccessByToken.remove(token)
    }

    fun activeTokens(): Set<String> = lastAccessByToken.keys.toSet()
}

/**
 * Low-overhead local diagnostic log.
 *
 * Runtime logging is async and bounded to 2 MiB per user/process data dir. Export builds a text file
 * instead of using Intent.EXTRA_TEXT, because a useful diagnostic payload is larger than Binder's
 * practical extra-size limit.
 */
object DiagnosticLog {
    const val MAX_ROLLING_BYTES: Int = 2 * 1024 * 1024

    internal val TRIM_MARKER: ByteArray =
        "\n--- PrismSpace diagnostic log truncated; newest entries retained within 2 MiB budget ---\n".toByteArray(Charsets.UTF_8)

    private const val DIR = "diagnostics"
    private const val ROLLING_FILE = "prismspace-rolling.log"
    private const val EXPORT_PREFIX = "prismspace-diagnostics-"
    private const val SNAPSHOT_PREFIX = "prismspace-snapshot-"
    private const val SNAPSHOT_SUFFIX = ".tmp"
    private const val MAX_EXPORT_FILES = 2
    private const val MAX_SNAPSHOT_FILES = 2
    private const val SNAPSHOT_TTL_MS = 10 * 60 * 1_000L
    private const val TRIM_HEADROOM_BYTES = 128 * 1024
    private const val LOGCAT_MAX_BYTES = 1 * 1024 * 1024
    private const val LOGCAT_TIMEOUT_MS = 1_500L
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PrismDiagnosticLog").apply { isDaemon = true }
    }
    private val snapshotLeases = DiagnosticSnapshotLeaseRegistry(MAX_SNAPSHOT_FILES, SNAPSHOT_TTL_MS)

    /** Lines written before any context is obtainable (inside the Application constructor). */
    private const val EARLY_CAPACITY = 64

    private val gate = EarlyLogGate(EARLY_CAPACITY) { context, line -> append(context, line) }

    /**
     * Where a not-yet-initialised log gets its context on first write: the Application once attached,
     * which precedes every ContentProvider.onCreate (the incremental profile convergence runs from one).
     */
    internal var contextSource: () -> Context? = { PrismApplication.attachedOrNull() }

    /** Idempotent; the log may already have initialised itself lazily on an earlier write. */
    fun init(context: Context) {
        val attached = gate.attach(context.applicationContext ?: context) ?: return
        recordInitialized("explicit", attached)
    }

    private fun recordInitialized(source: String, attached: EarlyLogGate.Attached) {
        val context = gate.context ?: return
        record("I", "Prism.Diag", "initialized package=${context.packageName} user=${currentUserId()} " +
            "source=$source buffered=${attached.buffered} dropped=${attached.dropped}")
    }

    fun v(tag: String, message: String) { Log.v(tag, message); record("V", tag, message) }
    fun d(tag: String, message: String) { Log.d(tag, message); record("D", tag, message) }
    fun i(tag: String, message: String) { Log.i(tag, message); record("I", tag, message) }
    fun w(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, message, t) else Log.w(tag, message)
        record("W", tag, message, t)
    }
    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
        record("E", tag, message, t)
    }

    fun record(level: String, tag: String, message: String, t: Throwable? = null) {
        val line = buildString {
            append(timestamp())
            append(' ')
            append(level.take(1))
            append('/')
            append(tag)
            append(" u=")
            append(currentUserId())
            append(" pid=")
            append(Process.myPid())
            append(": ")
            append(message.replace('\n', ' '))
            if (t != null) {
                append('\n')
                append(Log.getStackTraceString(t))
            }
            append('\n')
        }
        gate.offer(line) { contextSource() }?.let { recordInitialized("lazy", it) }
    }

    /** Ordered by the single-thread executor; the 2 MiB rolling budget is enforced on every append. */
    private fun append(context: Context, line: String) {
        executor.execute {
            runCatching {
                val file = rollingFile(context)
                file.parentFile?.mkdirs()
                file.appendText(line, Charsets.UTF_8)
                if (file.length() > MAX_ROLLING_BYTES) {
                    file.writeBytes(trimToWindowBytes(file.readBytes(), MAX_ROLLING_BYTES - TRIM_HEADROOM_BYTES))
                }
            }.onFailure { Log.w("Prism.Diag", "failed to append diagnostic log", it) }
        }
    }

    fun createExportFile(
        context: Context,
        extraSections: List<DiagnosticSection> = emptyList(),
        includeLogcat: Boolean = true,
    ): File {
        val appContext = context.applicationContext
        flush()
        val dir = exportDir(appContext).apply { mkdirs() }
        cleanupOldExports(dir)
        val file = File(dir, "$EXPORT_PREFIX${fileTimestamp()}.txt")
        file.parentFile?.mkdirs()
        file.writeText(buildSnapshotText(appContext, extraSections, includeLogcat), Charsets.UTF_8)
        return file
    }

    /**
     * Materializes the complete profile report: rolling app log (2 MiB budget), filtered logcat
     * (1 MiB budget), and the report header. [totalLength] covers the resulting UTF-8 file exactly.
     */
    @Synchronized
    internal fun openChunkedSnapshot(context: Context): DiagnosticSnapshotSession {
        i("Prism.Diag", "profile snapshot open start")
        val dir = exportDir(context.applicationContext).apply { mkdirs() }
        cleanupOldSnapshots(dir)
        check(snapshotLeases.hasCapacity()) { "Too many active diagnostic snapshot sessions" }
        val token = UUID.randomUUID().toString()
        val file = snapshotFile(dir, token)
        return try {
            file.writeText(buildSnapshotText(context.applicationContext, includeLogcat = true), Charsets.UTF_8)
            check(snapshotLeases.acquire(token, System.currentTimeMillis())) {
                "Diagnostic snapshot session capacity changed unexpectedly"
            }
            DiagnosticSnapshotSession(token, file.length()).also {
                i("Prism.Diag", "profile snapshot open success bytes=${it.totalLength}")
            }
        } catch (error: Throwable) {
            snapshotLeases.release(token)
            runCatching { file.delete() }
            e("Prism.Diag", "profile snapshot open failed exception=${error.javaClass.name}", error)
            throw error
        }
    }

    @Synchronized
    internal fun readChunkedSnapshot(
        context: Context,
        token: String,
        offset: Long,
        maxBytes: Int,
    ): DiagnosticSnapshotChunk? {
        if (offset < 0L || maxBytes <= 0) return null
        if (!snapshotLeases.touch(token, System.currentTimeMillis())) {
            snapshotFileOrNull(exportDir(context.applicationContext), token)?.let { runCatching { it.delete() } }
            return null
        }
        val file = snapshotFileOrNull(exportDir(context.applicationContext), token)?.takeIf(File::isFile)
        if (file == null) {
            snapshotLeases.release(token)
            return null
        }
        return readSnapshotChunk(file, offset, maxBytes)
    }

    @Synchronized
    internal fun closeChunkedSnapshot(context: Context, token: String) {
        snapshotLeases.release(token)
        snapshotFileOrNull(exportDir(context.applicationContext), token)?.let { file ->
            if (file.exists() && !file.delete()) w("Prism.Diag", "profile snapshot close could not delete token=$token")
        }
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)

    fun buildSnapshotText(
        context: Context,
        extraSections: List<DiagnosticSection> = emptyList(),
        includeLogcat: Boolean = true,
    ): String {
        flush()
        return buildString {
            appendLine("PrismSpace diagnostic report")
            appendLine("Generated: ${timestamp()}")
            appendLine("Package: ${context.packageName}")
            appendLine("Version: ${versionName(context)}")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("User: ${currentUserId()}")
            appendLine("Process: pid=${Process.myPid()} uid=${Process.myUid()}")
            appendLine("Rolling log budget: ${MAX_ROLLING_BYTES} bytes per user")
            appendLine()

            extraSections.forEach { section ->
                appendSection(section.title, section.body)
            }

            appendSection("Rolling app log for current user", readRollingLog(context))
            if (includeLogcat) appendSection("Current logcat snapshot", collectLogcatSnapshot())
        }
    }

    internal fun trimToWindowBytes(input: ByteArray, maxBytes: Int): ByteArray {
        if (input.size <= maxBytes) return input
        if (maxBytes <= TRIM_MARKER.size) return input.copyOfRange(input.size - maxBytes, input.size)
        val tailSize = maxBytes - TRIM_MARKER.size
        val tail = input.copyOfRange(input.size - tailSize, input.size)
        return TRIM_MARKER + tail
    }

    internal fun readSnapshotChunk(file: File, offset: Long, maxBytes: Int): DiagnosticSnapshotChunk {
        val length = file.length()
        if (offset >= length) return DiagnosticSnapshotChunk(ByteArray(0), eof = true)
        val requested = minOf(maxBytes.toLong(), length - offset).toInt()
        val bytes = ByteArray(requested)
        val read = RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            var filled = 0
            while (filled < requested) {
                val count = input.read(bytes, filled, requested - filled)
                if (count < 0) break
                filled += count
            }
            filled
        }
        val actual = if (read == bytes.size) bytes else bytes.copyOf(read)
        return DiagnosticSnapshotChunk(actual, eof = offset + actual.size >= length)
    }

    private fun StringBuilder.appendSection(title: String, body: String) {
        appendLine("===== $title =====")
        if (body.isBlank()) appendLine("(empty)") else appendLine(body.trimEnd())
        appendLine()
    }

    private fun readRollingLog(context: Context): String =
        runCatching {
            val file = rollingFile(context)
            if (!file.exists()) "" else file.readText(Charsets.UTF_8)
        }.getOrElse { "Failed to read rolling log: ${it.message ?: it.javaClass.simpleName}" }

    private fun collectLogcatSnapshot(): String {
        val out = StringBuilder()
        var bytes = 0
        return runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "4000")
                .redirectErrorStream(true)
                .start()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (shouldKeepLogcatLine(line) && bytes < LOGCAT_MAX_BYTES) {
                            val next = "$line\n"
                            val nextBytes = next.toByteArray(Charsets.UTF_8).size
                            if (bytes + nextBytes <= LOGCAT_MAX_BYTES) {
                                out.append(next)
                                bytes += nextBytes
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(LOGCAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy()
                out.appendLine("logcat collection timed out after ${LOGCAT_TIMEOUT_MS}ms")
            }
            reader.join(300)
            out.toString()
        }.getOrElse { "Failed to collect logcat: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun shouldKeepLogcatLine(line: String): Boolean =
        line.contains("Prism", ignoreCase = true) ||
            line.contains("yzddmr6", ignoreCase = true) ||
            line.contains("Shizuku", ignoreCase = true) ||
            line.contains("PackageInstaller", ignoreCase = true) ||
            line.contains("IntentResolver", ignoreCase = true) ||
            line.contains("DocumentsUI", ignoreCase = true) ||
            line.contains("FileExplorer", ignoreCase = true) ||
            line.contains("AndroidRuntime", ignoreCase = true) ||
            line.contains("Analytics", ignoreCase = true)

    private fun rollingFile(context: Context): File = File(exportDir(context), ROLLING_FILE)
    private fun exportDir(context: Context): File = File(context.filesDir, DIR)

    private fun cleanupOldExports(dir: File) {
        val exports = dir.listFiles { file -> file.name.startsWith(EXPORT_PREFIX) && file.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        exports.drop(MAX_EXPORT_FILES - 1).forEach { runCatching { it.delete() } }
    }

    private fun cleanupOldSnapshots(dir: File, nowMs: Long = System.currentTimeMillis()) {
        snapshotLeases.expire(nowMs).forEach { token ->
            snapshotFileOrNull(dir, token)?.let { runCatching { it.delete() } }
        }
        snapshotLeases.activeTokens()
            .filterNot { token -> snapshotFile(dir, token).isFile }
            .forEach(snapshotLeases::release)
        val activeTokens = snapshotLeases.activeTokens()
        val snapshots = dir.listFiles { file ->
            file.name.startsWith(SNAPSHOT_PREFIX) && file.name.endsWith(SNAPSHOT_SUFFIX)
        }?.sortedByDescending(File::lastModified).orEmpty()
        snapshots.filter { file ->
            snapshotTokenOrNull(file) !in activeTokens && nowMs - file.lastModified() >= SNAPSHOT_TTL_MS
        }
            .forEach { runCatching { it.delete() } }
        val inactiveSlotsBeforeOpen = (MAX_SNAPSHOT_FILES - activeTokens.size - 1).coerceAtLeast(0)
        snapshots.filter(File::exists)
            .filter { snapshotTokenOrNull(it) !in activeTokens }
            .drop(inactiveSlotsBeforeOpen)
            .forEach { runCatching { it.delete() } }
    }

    private fun snapshotFile(dir: File, token: String) = File(dir, "$SNAPSHOT_PREFIX$token$SNAPSHOT_SUFFIX")

    private fun snapshotFileOrNull(dir: File, token: String): File? =
        runCatching { UUID.fromString(token) }.getOrNull()?.let { snapshotFile(dir, it.toString()) }

    private fun snapshotTokenOrNull(file: File): String? {
        if (!file.name.startsWith(SNAPSHOT_PREFIX) || !file.name.endsWith(SNAPSHOT_SUFFIX)) return null
        val token = file.name.removePrefix(SNAPSHOT_PREFIX).removeSuffix(SNAPSHOT_SUFFIX)
        return runCatching { UUID.fromString(token).toString() }.getOrNull()
    }

    private fun flush(timeoutMs: Long = 1_000L) {
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US)
        .apply { timeZone = TimeZone.getDefault() }
        .format(Date())

    private fun fileTimestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    private fun currentUserId(): String =
        runCatching { Users.currentId().toString() }.getOrElse { Process.myUserHandle().hashCode().toString() }

    private fun versionName(context: Context): String =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName ?: "unknown"} ($code)"
        }.getOrDefault("unknown")
}
