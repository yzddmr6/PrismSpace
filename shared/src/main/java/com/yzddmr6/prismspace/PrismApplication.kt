package com.yzddmr6.prismspace

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import com.yzddmr6.prismspace.analytics.CrashReport
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.util.Hacks
import com.yzddmr6.prismspace.util.RomVariants
import com.yzddmr6.prismspace.util.Users

/**
 * For singleton instance purpose only.
 *
 * Created by Oasis on 2018/1/3.
 */
class PrismApplication : Application() {

	companion object {
		@JvmStatic fun get() = sInstance

		lateinit var sInstance: PrismApplication

		/** The Application once attached (before any ContentProvider.onCreate); null inside its constructor. */
		@JvmStatic fun attachedOrNull(): android.content.Context? =
			if (this::sInstance.isInitialized) sInstance.takeIf { it.baseContext != null } else null

		private const val PREFS_OWNER_HOUSEKEEPING = "prism_owner_housekeeping"
		private const val KEY_CONVERGE_TRAMPOLINE_RETIRED = "converge_trampoline_retired"
		private const val CONVERGE_ACTIVITY_CLASS =
			"com.yzddmr6.prismspace.provisioning.PrismProvisioning\$ConvergeActivity"
	}

	init {
		sInstance = this
		CrashReport.initCrashHandler()
	}

	override fun onCreate() {
		super.onCreate()
		DiagnosticLog.init(this)
		CrashReport.initCrashHandler(this)
		retireConvergeTrampolineInOwner()
		// ROM identity separates vendor-profile quirks (MIUI XSpace, OEM clone users) from AOSP behavior.
		val miuiVersion = Hacks.SystemProperties_get.invoke("ro.miui.ui.version.name").statically().orEmpty()
		DiagnosticLog.i("Prism.Diag", "rom miui=${RomVariants.isMiui()} miui_version=$miuiVersion incremental=${Build.VERSION.INCREMENTAL}")
	}

	/** The convergence trampoline carries MAIN+LAUNCHER so LauncherApps can start it inside a
	 *  broken profile; in the owner user it only pollutes the home launcher (observed in the wild:
	 *  users tap the leftover icon). Disable it here once; the profile side manages its own
	 *  retirement after convergence, and owner-user state never affects cross-profile launches. */
	private fun retireConvergeTrampolineInOwner() {
		if (!Users.isParentProfile()) return
		val prefs = getSharedPreferences(PREFS_OWNER_HOUSEKEEPING, MODE_PRIVATE)
		if (prefs.getBoolean(KEY_CONVERGE_TRAMPOLINE_RETIRED, false)) return
		try {
			packageManager.setComponentEnabledSetting(
				ComponentName(packageName, CONVERGE_ACTIVITY_CLASS),
				PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				PackageManager.DONT_KILL_APP,
			)
			prefs.edit().putBoolean(KEY_CONVERGE_TRAMPOLINE_RETIRED, true).apply()
			DiagnosticLog.i("Prism.Diag", "converge trampoline retired in owner user")
		} catch (e: RuntimeException) {
			DiagnosticLog.w("Prism.Diag", "converge trampoline retire failed; retry next start", e)
		}
	}
}
