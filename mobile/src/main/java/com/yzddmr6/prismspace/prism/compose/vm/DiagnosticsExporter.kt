package com.yzddmr6.prismspace.prism.compose.vm

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yzddmr6.prismspace.analytics.CrashMarker
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.analytics.DiagnosticSection
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.space.ProfileOwnership
import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.space.SpaceStateClassifier
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared diagnostics export used by Settings and by the setup wizard — a user stuck in setup
 * may never reach the Settings tab, so the entry must exist there too. Produces the export
 * file and the ACTION_SEND intent; callers own feedback and launching.
 */
object DiagnosticsExporter {
    private const val TAG = "Prism.DiagExport"

    /** Builds the export file and share intent. IO-bound; call from a coroutine. */
    suspend fun buildShare(
        context: Context,
        subject: String,
        attachedText: (String) -> String,
    ): Pair<String, Intent> = withContext(Dispatchers.IO) {
        DiagnosticLog.i(TAG, "diagnostic export start")
        val sections = buildList {
            // A pending crash marker rides along on every export until consumed, so a user who
            // exports manually from Settings also captures the crash scene.
            CrashMarker.pending(context.applicationContext)?.let { crash ->
                add(DiagnosticSection(title = "Last uncaught crash (pending report)", body = crash))
            }
            addAll(collectProfileDiagnostics(context.applicationContext))
        }
        val file = DiagnosticLog.createExportFile(
            context,
            extraSections = sections,
            includeLogcat = true,
        )
        val uri: Uri = DiagnosticLog.shareUri(context, file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, attachedText(file.name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newUri(context.contentResolver, file.name, uri) }
        file.name to intent
    }

    fun launchShare(context: Context, subject: String, intent: Intent) {
        context.startActivity(
            Intent.createChooser(intent, subject)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun collectProfileDiagnostics(context: Context): List<DiagnosticSection> {
        val stateRepo = SpaceStateRepository(context)
        val state = stateRepo.currentState()
        val targetId = selectDiagnosticsProfile(state, Users.profile?.toId())
        // The ownership partition is a parent-side fact; inside the managed profile there is none
        // to report, so no Profiles section is written there.
        val profilesSection = if (Users.isParentProfile()) {
            val ownership = runCatching { Users.ownership() }
                .onFailure { DiagnosticLog.w(TAG, "ownership snapshot unavailable", it) }
                .getOrDefault(ProfileOwnership.EMPTY)
            DiagnosticLog.i(TAG, "diagnostic export target user=${targetId ?: "-"} ${ownership.diagnosticLine()}")
            // Always first: which profiles exist and which one is ours, whatever happens below.
            DiagnosticSection(
                title = "Profiles",
                body = profilesSectionBody(ownership, state, stateRepo.lastFactsDiagnosticLine(), targetId),
            )
        } else null
        val leading = listOfNotNull(profilesSection)
        val profile = targetId?.let(UserHandles::of) ?: return leading + DiagnosticSection(
            title = "Dual-space diagnostic snapshot",
            body = "No managed profile is currently known to the main space.",
        )
        val healthSection = try {
            DiagnosticSection(
                title = "Dual-space shuttle health user=${profile.toId()}",
                body = ShuttleProvider.health(context, profile).diagnosticLine(),
            )
        } catch (error: Exception) {
            DiagnosticLog.w(TAG, "dual-space shuttle health collection failed user=${profile.toId()}", error)
            DiagnosticSection(
                title = "Dual-space shuttle health user=${profile.toId()}",
                body = "Health check failed: ${error.javaClass.name}: ${error.message.orEmpty()}",
            )
        }
        return try {
            DiagnosticLog.i(TAG, "dual-space diagnostic chunk collection start user=${profile.toId()}")
            val target = BridgeTargets.profile(profile.toId())
            val result = if (target == null) {
                DiagnosticsCollectionResult.Failure(
                    "Dual-space diagnostic snapshot unavailable: managed profile target is invalid.",
                    openAttempts = 0,
                )
            } else {
                collectDiagnosticsSnapshot(BridgeDiagnosticsSnapshotTransport(context, target))
            }
            val body = when (result) {
                is DiagnosticsCollectionResult.Success -> {
                    DiagnosticLog.i(
                        TAG,
                        "dual-space diagnostic chunk collection success user=${profile.toId()} " +
                            "bytes=${result.bytes.size} attempts=${result.openAttempts}",
                    )
                    result.bytes.toString(Charsets.UTF_8).ifBlank {
                        "Dual-space diagnostic snapshot was empty (profileBytes=${result.expectedLength})."
                    }
                }
                is DiagnosticsCollectionResult.Failure -> {
                    DiagnosticLog.w(
                        TAG,
                        "dual-space diagnostic chunk collection failed user=${profile.toId()} " +
                            "attempts=${result.openAttempts} reason=${result.reason}",
                    )
                    result.reason
                }
            }
            leading + listOf(healthSection, DiagnosticSection(
                title = "Dual-space diagnostic snapshot user=${profile.toId()}",
                body = body,
            ))
        } catch (e: Exception) {
            DiagnosticLog.e(TAG, "dual-space diagnostic collection failed", e)
            leading + listOf(healthSection, DiagnosticSection(
                title = "Dual-space diagnostic snapshot user=${profile.toId()}",
                body = "Failed to read dual-space diagnostic snapshot: ${e.javaClass.name}: ${e.message.orEmpty()}",
            ))
        }
    }
}

/**
 * The profile-side collection target is the classifier's primary: the classified state's userId.
 * A state with no own profile (none, or foreign only) has no primary. [fallback] (Users.profile,
 * identical by construction) is used only before the first classification snapshot exists.
 */
internal fun selectDiagnosticsProfile(state: SpaceState?, fallback: Int?): Int? = when {
    state == null -> fallback
    SpaceStateClassifier.ownProfileAbsent(state) -> null
    else -> state.userId
}

internal fun profilesSectionBody(
    ownership: ProfileOwnership,
    state: SpaceState?,
    lastFactsLine: String?,
    targetId: Int?,
): String = buildString {
    append(ownership.diagnosticLine())
    append("\nClassified space state: ").append(state ?: "unknown")
    append("\nDiagnostics target: ").append(targetId?.let { "user=$it" } ?: "-")
    if (lastFactsLine != null) append("\nLast classification: ").append(lastFactsLine)
}
