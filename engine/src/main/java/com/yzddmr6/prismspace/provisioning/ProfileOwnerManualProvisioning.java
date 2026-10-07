package com.yzddmr6.prismspace.provisioning;

import android.content.Context;

import com.yzddmr6.prismspace.util.DevicePolicies;
import com.yzddmr6.prismspace.util.ProfileUser;

/**
 * Simulate the managed provisioning procedure for manually enabled managed profile.
 *
 * Created by Oasis on 2016/4/18.
 */
class ProfileOwnerManualProvisioning {

	@ProfileUser static void start(final Context context, final DevicePolicies policies) {
		/* System apps are converged by SystemAppPolicyRuntime for every creation path, not by a ManagedProvisioning-style task. */
		/* DisableBluetoothSharingTask & DisableInstallShortcutListenersTask cannot be done here, since they disable components. */
		/* Settings.Secure.MANAGED_PROFILE_CONTACT_REMOTE_SEARCH can be toggled in system Settings - Users & Profiles - Profile Settings */
		/* DISALLOW_WALLPAPER cannot be changed by profile / device owner. */

		// Set default cross-profile intent-filters
		CrossProfileIntentFiltersHelper.setFilters(policies);
	}
}
