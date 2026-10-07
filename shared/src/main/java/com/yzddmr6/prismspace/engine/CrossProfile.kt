package com.yzddmr6.prismspace.engine

import android.app.admin.DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
import android.content.*
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.Users

object CrossProfile {

	const val CATEGORY_PARENT_PROFILE = "com.yzddmr6.prismspace.category.PARENT_PROFILE"
	const val CATEGORY_MANAGED_PROFILE = "com.yzddmr6.prismspace.category.MANAGED_PROFILE"

	/** PrismSpace's own cross-space "open / share a transferred file" request, carried by the system
	 *  intent forwarder (extras only, no data). Filters are registered by the dual space's profile owner. */
	const val ACTION_TRANSFER_OPEN = "com.yzddmr6.prismspace.action.TRANSFER_OPEN"
	/** Receives requests sent from the main space; lives (enabled) only in the dual space. */
	const val TRANSFER_OPEN_FROM_MAIN = "com.yzddmr6.prismspace.prism.transfer.TransferOpenFromMain"
	/** Receives requests sent from the dual space; enabled in the main space, disabled in the dual space. */
	const val TRANSFER_OPEN_FROM_DUAL = "com.yzddmr6.prismspace.prism.transfer.TransferOpenFromDual"

	/** Ensure a cross-profile intent forwarder is registered for this action+CATEGORY_PARENT_PROFILE, then add
	 *  the category to [intent] so the system routes it via the forwarder when startActivity is called.
	 *  The target activity in parent profile must declare [CATEGORY_PARENT_PROFILE] in its intent-filter.
	 *
	 *  We cannot find or set intent.component to the system IntentForwarderActivity ourselves because
	 *  PackageManager.queryIntentActivities from within a managed profile does not return the system-owned
	 *  forwarder (Android security boundary). We rely on the system to resolve at startActivity time. */
	@JvmStatic fun decorateIntentForActivityInParentProfile(context: Context, intent: Intent) {
		require(intent.data == null) { "Intent with data is not supported yet" }
		check(! Users.isParentProfile()) { "Must not be called in parent profile" }
		intent.addCategory(CATEGORY_PARENT_PROFILE)
		// addCrossProfileIntentFilter is idempotent: identical filters do not stack. Safe to call every time.
		addRequiredForwarding(context, intent)
	}

		// FLAG_PARENT_CAN_ACCESS_MANAGED forwards intents fired in the managed profile to parent activities
		// (managed -> parent). FLAG_MANAGED_CAN_ACCESS_PARENT is the opposite route and would never resolve here.
	private fun addRequiredForwarding(context: Context, intent: Intent) = DevicePolicies(context).addCrossProfileIntentFilter(
			IntentFilter(intent.action).apply { addCategory(CATEGORY_PARENT_PROFILE) }, FLAG_PARENT_CAN_ACCESS_MANAGED)
}
