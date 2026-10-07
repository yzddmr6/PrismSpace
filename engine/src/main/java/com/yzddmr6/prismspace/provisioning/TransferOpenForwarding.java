package com.yzddmr6.prismspace.provisioning;

import static android.app.admin.DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT;
import static android.app.admin.DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;

import android.content.ComponentName;
import android.content.Context;

import com.yzddmr6.prismspace.analytics.DiagnosticLog;
import com.yzddmr6.prismspace.engine.CrossProfile;
import com.yzddmr6.prismspace.util.IntentFilters;
import com.yzddmr6.prismspace.util.ProfileUser;

/**
 * The single cross-space delivery route for "open / share a transferred file": PrismSpace's own
 * {@link CrossProfile#ACTION_TRANSFER_OPEN} request, forwarded by the system intent forwarder to the
 * PrismSpace activity-alias of the other user. A profile owner never gets the interact-across-profiles
 * grant, so cross-profile intent filters are the platform's route for it.
 *
 * <p>Each user must hold exactly one local match: FromMain is disabled by the manifest (main space) and
 * enabled here (dual space); FromDual is enabled by the manifest (main space) and disabled here.
 */
final class TransferOpenForwarding {

	private static final String TAG = "Prism.Provision";

	/** Index-aligned: main -> dual, then dual -> main. The dual space's profile owner registers both. */
	static final String[] CATEGORIES = { CrossProfile.CATEGORY_MANAGED_PROFILE, CrossProfile.CATEGORY_PARENT_PROFILE };
	static final int[] DIRECTIONS = { FLAG_MANAGED_CAN_ACCESS_PARENT, FLAG_PARENT_CAN_ACCESS_MANAGED };

	/** No CATEGORY_DEFAULT: the request does not carry it; MATCH_DEFAULT_ONLY checks the target alias. */
	static void register(final CrossProfileIntentFiltersHelper.DirectionalFilterSink sink) {
		for (int i = 0; i < CATEGORIES.length; i++)
			sink.add(IntentFilters.forAction(CrossProfile.ACTION_TRANSFER_OPEN).withCategory(CATEGORIES[i]), DIRECTIONS[i]);
	}

	/** FromMain enabled, FromDual disabled in this (dual) user; DONT_KILL_APP. Logs transfer_forwarding. */
	@ProfileUser static void setReceiverAliases(final Context context) {
		final String fromMain = setAlias(context, CrossProfile.TRANSFER_OPEN_FROM_MAIN, true);
		final String fromDual = setAlias(context, CrossProfile.TRANSFER_OPEN_FROM_DUAL, false);
		DiagnosticLog.INSTANCE.i(TAG, "transfer_forwarding filters=" + CATEGORIES.length
				+ " fromMain=" + fromMain + " fromDual=" + fromDual);
	}

	private static String setAlias(final Context context, final String alias, final boolean enabled) {
		try {
			context.getPackageManager().setComponentEnabledSetting(new ComponentName(context.getPackageName(), alias),
					enabled ? COMPONENT_ENABLED_STATE_ENABLED : COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
			return enabled ? "enabled" : "disabled";
		} catch (final RuntimeException e) {     // IllegalArgumentException (unknown component) or SecurityException.
			DiagnosticLog.INSTANCE.w(TAG, "transfer_forwarding alias=" + alias + " failed", e);
			return "failed:" + e.getClass().getSimpleName();
		}
	}

	private TransferOpenForwarding() {}
}
