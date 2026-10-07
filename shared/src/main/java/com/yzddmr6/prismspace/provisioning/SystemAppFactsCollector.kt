package com.yzddmr6.prismspace.provisioning

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE
import android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE
import android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
import android.content.pm.PackageManager.MATCH_SYSTEM_ONLY
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.view.inputmethod.InputMethodManager
import com.yzddmr6.prismspace.util.ProfileUser

/** Everything the policy needs to know about the platform, collected once per pass. */
data class SystemAppFacts(
    val facts: List<PackageFact>,
    val critical: Set<String>,
    val exempt: Set<String>,
    /** Packages with an *enabled* launcher entry (launchability scope, hidden packages included). */
    val enabledLauncherPackages: Set<String>,
)

/**
 * Profile-side fact collection (design §3). The policy scope of "has a launcher entry" includes
 * disabled components like AOSP provisioning; the launchability scope does not, so an app that
 * disables its own entry reads as not openable.
 */
@ProfileUser object SystemAppFactsCollector {

    /** @param extraPackages non-enumerated packages to classify too (defaults and overrides). */
    @SuppressLint("QueryPermissionsNeeded", "WrongConstant")
    fun collect(context: Context, extraPackages: Set<String>): SystemAppFacts {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // 1. Explicit system query: some HyperOS builds omit system packages from the general one.
        //    MATCH_UNINSTALLED_PACKAGES keeps hidden packages in the result.
        val system = pm.getInstalledApplications(MATCH_UNINSTALLED_PACKAGES or MATCH_DISABLED_COMPONENTS or MATCH_SYSTEM_ONLY)
            .associateBy { it.packageName }
        // 2. Policy scope launcher set: disabled entries count, OEMs may enable them at runtime.
        val policyLauncher = pm.queryIntentActivities(launcher, MATCH_UNINSTALLED_PACKAGES or MATCH_DISABLED_COMPONENTS
                or MATCH_DIRECT_BOOT_AWARE or MATCH_DIRECT_BOOT_UNAWARE)
            .mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
        // 3. Launchability scope: enabled entries only.
        val enabledLauncher = pm.queryIntentActivities(launcher, MATCH_UNINSTALLED_PACKAGES)
            .mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
        val facts = ArrayList<PackageFact>(system.size + extraPackages.size)
        system.values.forEach { facts += it.toFact(policyLauncher) }
        extraPackages.filterNot(system::containsKey).forEach { pkg ->
            val info = try {
                @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, MATCH_UNINSTALLED_PACKAGES)
            } catch (e: PackageManager.NameNotFoundException) { null }
            if (info != null) facts += info.toFact(policyLauncher)
        }
        // 4. System input methods are exempt, same scope as the retired AOSP-derived task.
        val exempt = runCatching {
            context.getSystemService(InputMethodManager::class.java)?.inputMethodList.orEmpty()
                .filter { it.serviceInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
                .mapTo(HashSet()) { it.packageName }
        }.getOrDefault(emptySet())
        return SystemAppFacts(
            facts = facts,
            critical = SystemAppsManager.detectCriticalSystemPackages(pm),
            exempt = exempt,
            enabledLauncherPackages = enabledLauncher,
        )
    }

    private fun ApplicationInfo.toFact(launcherPackages: Set<String>) = PackageFact(
        pkg = packageName,
        isSystem = flags and ApplicationInfo.FLAG_SYSTEM != 0,
        hasLauncherEntry = packageName in launcherPackages,
        installed = flags and ApplicationInfo.FLAG_INSTALLED != 0,
    )
}
