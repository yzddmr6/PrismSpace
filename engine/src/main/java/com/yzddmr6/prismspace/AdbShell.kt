package com.yzddmr6.prismspace

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.ACCOUNT_SERVICE
import android.content.Context.USER_SERVICE
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.os.UserManager
import androidx.annotation.Keep
import com.yzddmr6.prismspace.util.Hack
import com.yzddmr6.prismspace.util.Dump
import com.yzddmr6.prismspace.util.Hacks
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Helper running in ADB shell
 *
 * Created by Oasis on 2019-6-23.
 */
const val COUNT_DOWN_BEFORE_ACCOUNTS_REMOVAL = 5
const val EXCLUDED_ACCOUNT_TYPES_FOR_DEBUG = "com.google"

const val SUI_PACKAGE = "com.northsoft.sui"
@SuppressLint("MissingPermission", "StaticFieldLeak") @Keep // Must be kept explicitly
object AdbShell {

    @JvmStatic fun main(args: Array<String>) {
        if (Process.myUid() != 2000/* Process.SHELL_UID */) return System.err.println("Not running in ADB shell, exit now.")
        Thread.setDefaultUncaughtExceptionHandler { t, e -> System.err.println("\n$t"); e.printStackTrace() }

        // Sui authorization check
        if (isSuiAvailable()) {
            println("Sui detected and authorized.")
        } else {
            println("Sui not found.")
        }

        when(if (args.isEmpty()) "" else args[0]) {
            "remove-account" -> runBlocking { if (args.size > 1) runRemoveAccount(args[1]) else runRemoveAllAccounts() }
            "remove-user" -> runRemoveNonPrimaryUsers(if (args.size <= 1) null else (args[1].toIntOrNull() ?: return help()))
            else -> help()
        }
    }

    private fun help() {
        println("Usage: AdbShell -h | remove-account [<account type>:[<account name>]] | remove-user [user ID]")
    }

    private fun isSuiAvailable(): Boolean = try {
        shellContext.packageManager.getPackageInfo(SUI_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun runRemoveNonPrimaryUsers(userId: Int?) {
        val um = Hack.into(shellContext.getSystemService(USER_SERVICE) as UserManager).with(Hacks.UserManagerHack::class.java)
        for (id in if (userId != null) listOf(userId) else um.users.map { user -> user.userHandle.hashCode() }) {
            if (id == 0) continue
            print("Removing user $id... ")
            um.removeUser(id).also { successful -> println(if (successful) "Done" else "Failed") }
        }
    }

    private suspend fun runRemoveAccount(account: String) {
        account.split(':', limit = 2).also {
            if (it.size < 2 && it[0] == account) return help()
            if (it[1].isEmpty()) runRemoveAllAccounts(it[0])
            else removeAccountIfNotExcluded(shellContext.getSystemService(ACCOUNT_SERVICE) as AccountManager, Account(it[1], it[0]))
        }
    }

    private suspend fun runRemoveAllAccounts(type: String = "") {
        if (Account("Foo", "Bar").toString() != "Account {name=Foo, type=Bar}") return System.err.println("Incompatible ROM")
        val am = shellContext.getSystemService(ACCOUNT_SERVICE) as AccountManager
        val accounts: List<Account> = Dump.systemService(ACCOUNT_SERVICE) { lines ->
            // User UserInfo{0:Owner:13}:
            //   Accounts: 15
            //     Account {name=<account name>, type=<account type>}
            //     Account...
            //   ...
            // User UserInfo{10:PrismSpace:b0}:
            //   Accounts: 2
            //     Account...
            val prefix = "Account {name="
            lines.dropUpTo { contains("UserInfo{0:") }.map(String::trim).takeWhile { it.startsWith(prefix) }.map {
                it.substring(prefix.length, it.length - 1/* '}' */).split(", type=", limit = 2).let { pair -> Account(pair[0], pair[1]) }
            }.filter { type.isEmpty() || it.type == type }.toList()
        } ?: return System.err.println("Error querying accounts.")

        if (accounts.isEmpty()) return println("No matching accounts in primary user.")
        println("All matching accounts in primary user:")
        for (account in accounts)
            println("  [${account.type}] ${account.name}" + if (shouldExclude(account)) " (will not be removed)" else "")

        print("\nThese accounts and related local data (excluding app local data) will be removed in ")
        for (i in COUNT_DOWN_BEFORE_ACCOUNTS_REMOVAL downTo 1) print("${i}s... ").also { Thread.sleep(1_000) }
        println()

        for (account in accounts)
            removeAccountIfNotExcluded(am, account)
    }

    private inline fun <T> Sequence<T>.dropUpTo(crossinline predicate: T.() -> Boolean): Sequence<T> {
        var pass = false
        return filter{ pass || ! predicate(it).also { met -> pass = met } }.drop(1)
    }

    private fun shouldExclude(account: Account) = account.type in EXCLUDED_ACCOUNT_TYPES_FOR_DEBUG.split(",")

    private suspend fun removeAccountIfNotExcluded(am: AccountManager, account: Account) {
        print("Removing account [${account.type}] ${account.name}... ")
        removeAccountIfNotExcludedInternal(am, account).also { println(it) }
    }

    private suspend fun removeAccountIfNotExcludedInternal(am: AccountManager, account: Account): String {
        if (shouldExclude(account)) return "SKIPPED"
        val future = am.removeAccount(account, null, null, null)     // Avoid Dispatchers.MAIN due to lack of running main Looper.
        return withContext(Dispatchers.Default) {
            try {
                val result = future.result
                if (result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT)) "DONE"
                else result.getParcelable<Intent>(AccountManager.KEY_INTENT).let { intent ->
                    if (intent == null) "FAILED" else launchIntent(intent).let { "PENDING" }
                }
            } catch (e: Exception) {
                "ERROR ($e)"
            }
        }
    }

    private suspend fun launchIntent(intent: Intent) = withContext(Dispatchers.Main) { shellContext.startActivity(intent) }

    private val shellContext: Context by lazy {
        @SuppressLint("PrivateApi") val classActivityThread = Class.forName("android.app.ActivityThread")
        val activityThread = classActivityThread.getMethod("systemMain").invoke(null)
        val systemContext = classActivityThread.getMethod("getSystemContext").invoke(activityThread) as Context
        val shellContext = systemContext.createPackageContext("com.android.shell", 0)
        shellContext.also { it.javaClass.getDeclaredField("mOpPackageName").apply { isAccessible = true }.apply { set(it, it.packageName) } }
    }

    init {
        if (Looper.getMainLooper() == null) @Suppress("DEPRECATION") Looper.prepareMainLooper()     // A prepared Looper is required for the calls below to succeed
    }
}
