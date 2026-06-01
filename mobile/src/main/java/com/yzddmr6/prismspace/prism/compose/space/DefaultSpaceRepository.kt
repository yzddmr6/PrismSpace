package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.PrismNameManager
import com.yzddmr6.prismspace.common.app.AppListProvider
import com.yzddmr6.prismspace.data.PrismAppInfo
import com.yzddmr6.prismspace.data.PrismAppListProvider
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId

class DefaultSpaceRepository(private val appContext: Context) : SpaceRepository {
    private val provider get() = AppListProvider.getInstance<PrismAppListProvider>(appContext)
    private val bridgeHealth = BridgeHealthRepository(appContext)
    // Space display names must follow the per-app language (Main + the profile default name).
    private val localized get() = PrismLocale.wrap(appContext)

    override fun spaces(): List<PrismSpace> {
        refreshUsersSnapshot()
        return buildList {
        add(PrismSpace("main", Users.parentProfile.toId(), PrismSpaceKind.Main, localized.getString(R.string.lz_space_main_name)))
        addAll(dualSpaces())
        }
    }

    override fun dualSpaces(): List<PrismSpace> {
        refreshUsersSnapshot()
        return PrismNameManager.getAllNames(localized)
            .map { (handle, name) -> PrismSpace("space_${handle.toId()}", handle.toId(), PrismSpaceKind.Dual, name) }
            .sortedBy { it.userId }
    }

    override fun mainSpace() = spaces().first { it.kind == PrismSpaceKind.Main }
    override fun dualSpace() = dualSpaces().firstOrNull()
    override fun space(id: String) = spaces().firstOrNull { it.id == id }

    override fun usabilityOf(space: PrismSpace): SpaceUsability {
        refreshUsersSnapshot()
        if (space.kind == PrismSpaceKind.Main) return SpaceUsability.Usable
        val handle = Users.getProfilesManagedByPrism()
            .firstOrNull { it.toId() == space.userId }
            ?: return SpaceUsability.NotProvisioned
		return try {
			val provisioned = Users.isProfileManagedByPrism(appContext, handle)
			val running = Users.isProfileRunning(appContext, handle)
			val quietMode = Users.isProfileQuietModeEnabled(appContext, handle)
			val unlocked = appContext.getSystemService(android.os.UserManager::class.java)
				?.isUserUnlocked(handle) == true
			val base = spaceUsability(provisioned, running, unlocked, quietMode = quietMode)
			if (base != SpaceUsability.Usable) return base
			val bridgeReady = bridgeHealth.cachedHealth(handle)?.available
			spaceUsability(provisioned, running, unlocked, bridgeReady, quietMode)
		} catch (e: SecurityException) {
            DiagnosticLog.w(TAG, "dual-space usability unavailable user=${handle.toId()}", e)
            SpaceUsability.Unknown
        } catch (e: RuntimeException) {
            DiagnosticLog.w(TAG, "dual-space usability unavailable user=${handle.toId()}", e)
            SpaceUsability.Unknown
        }
    }

    override fun installedApps(space: PrismSpace): Collection<PrismAppInfo> {
        refreshUsersSnapshot()
        // Resolve the real UserHandle from space.userId:
        // Main -> parentProfile; Dual -> the managed profile whose toId() == userId.
        val handle = if (space.kind == PrismSpaceKind.Main) Users.parentProfile
                     else Users.getProfilesManagedByPrism().firstOrNull { it.toId() == space.userId }
                          ?: return emptyList()
        return provider.installedApps(handle)
    }

    override fun cloneTargetSpaceCount(): Int {
        refreshUsersSnapshot()
        return 1 + Users.getProfilesManagedByPrism().size
    }

    private fun refreshUsersSnapshot() {
        runCatching { Users.refreshUsers(appContext) }
            .onFailure { DiagnosticLog.w(TAG, "refresh users snapshot failed", it) }
    }

    private companion object {
        const val TAG = "Prism.SpaceRepo"
    }
}
