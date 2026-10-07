package com.yzddmr6.prismspace.provisioning

import android.app.admin.DevicePolicyManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import com.yzddmr6.prismspace.util.DevicePolicies

/** Observed per-user state of one package inside the profile the applier runs in. */
data class PackageState(val installed: Boolean, val hidden: Boolean, val suspended: Boolean)

/** DPM/PackageManager boundary of the applier, so plans and the runtime stay JVM-testable. */
interface SystemAppStatePort {
    /** @return null for a package unknown to this device. */
    fun state(pkg: String): PackageState?
    /** @return false when the package is not present or not a system package of the parent user. */
    fun enable(pkg: String): Boolean
    fun setHidden(pkg: String, hidden: Boolean): Boolean
    fun setSuspended(pkg: String, suspended: Boolean): Boolean
}

/** Production port: profile-owner DPM calls, run inside the managed profile. */
class DpmSystemAppStatePort(
    private val policies: DevicePolicies,
    private val pm: PackageManager,
) : SystemAppStatePort {

    override fun state(pkg: String): PackageState? {
        val info = try {
            @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        } catch (e: NameNotFoundException) { return null }
        val hidden = try { policies.invoke(DevicePolicyManager::isApplicationHidden, pkg) }
        catch (e: IllegalArgumentException) { false }
        val suspended = try { policies.isPackageSuspended(pkg) }
        catch (e: NameNotFoundException) { false }      // Not installed for this user.
        catch (e: IllegalArgumentException) { false }
        return PackageState(installed = info.flags and ApplicationInfo.FLAG_INSTALLED != 0, hidden = hidden, suspended = suspended)
    }

    override fun enable(pkg: String): Boolean = policies.enableSystemApp(pkg)

    /** Goes through [DevicePolicies.setApplicationHidden] so app-ops are saved and restored like a freeze. */
    override fun setHidden(pkg: String, hidden: Boolean): Boolean =
        policies.setApplicationHidden(pkg, hidden) ||
            policies.invoke(DevicePolicyManager::isApplicationHidden, pkg) == hidden

    override fun setSuspended(pkg: String, suspended: Boolean): Boolean =
        policies.invoke(DevicePolicyManager::setPackagesSuspended, arrayOf(pkg), suspended).isEmpty()
}
