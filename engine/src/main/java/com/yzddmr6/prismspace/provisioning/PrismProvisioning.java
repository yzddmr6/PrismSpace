package com.yzddmr6.prismspace.provisioning;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.Notification.PRIORITY_HIGH;
import static android.app.admin.DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE;
import static android.app.admin.DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT;
import static android.app.admin.DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED;
import static android.content.Intent.ACTION_INSTALL_PACKAGE;
import static android.content.Intent.ACTION_MAIN;
import static android.content.Intent.ACTION_OPEN_DOCUMENT_TREE;
import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.ACTION_SEND_MULTIPLE;
import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.CATEGORY_BROWSABLE;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N_MR1;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Build.VERSION_CODES.Q;
import static androidx.core.app.NotificationCompat.BADGE_ICON_SMALL;
import static androidx.core.app.NotificationCompat.CATEGORY_STATUS;

import android.app.Activity;
import android.app.IntentService;
import android.app.Notification;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.yzddmr6.prismspace.PrismNameManager;
import com.yzddmr6.prismspace.util.Dialogs;
import com.yzddmr6.prismspace.analytics.Analytics;
import com.yzddmr6.prismspace.analytics.DiagnosticLog;
import com.yzddmr6.prismspace.api.Api;
import com.yzddmr6.prismspace.appops.AppOpsCompat;
import com.yzddmr6.prismspace.engine.CrossProfile;
import com.yzddmr6.prismspace.engine.PrismManager;
import com.yzddmr6.prismspace.engine.R;
import com.yzddmr6.prismspace.notification.NotificationIds;
import com.yzddmr6.prismspace.shuttle.ShuttleProvider;
import com.yzddmr6.prismspace.util.DevicePolicies;
import com.yzddmr6.prismspace.util.IntentCompat;
import com.yzddmr6.prismspace.util.IntentFilters;
import com.yzddmr6.prismspace.util.Modules;
import com.yzddmr6.prismspace.util.OwnerUser;
import com.yzddmr6.prismspace.util.ProfileUser;
import com.yzddmr6.prismspace.util.SafeAsyncTask;
import com.yzddmr6.prismspace.util.Suppliers;
import com.yzddmr6.prismspace.util.Toasts;
import com.yzddmr6.prismspace.util.Users;

import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The one-time provisioning for newly created managed profile of PrismSpace
 *
 * Created by Oasis on 2016/4/26.
 */
public class PrismProvisioning extends IntentService {

	/**
	 * Provision state:
	 *   1 - Managed profile provision (stock) is completed
	 *   2 - PrismSpace provision is started, POST_PROVISION_REV - PrismSpace provision is completed.
	 *   [3,POST_PROVISION_REV> - PrismSpace provision is completed in previous version, but needs re-performing in this version.
	 *   POST_PROVISION_REV - PrismSpace provision is up-to-date, nothing to do.
	 */
	private static final String PREF_KEY_PROVISION_STATE = "provision.state";
	/** Provision type: 0 (default) - Managed provisioning, 1 - Manual provisioning */
	private static final String PREF_KEY_PROFILE_PROVISION_TYPE = "profile.provision.type";
	/** The revision for post-provisioning. Increase this const value if post-provisioning needs to be re-performed after upgrade. */
	private static final int POST_PROVISION_REV = 12;
	/** States below this value describe a fresh or still-running initial provisioning transaction. */
	private static final int FIRST_COMPLETED_POST_PROVISION_REV = 3;
	private static final String AFFILIATION_ID = "com.yzddmr6.prismspace";
	private static final String SCHEME_PACKAGE = "package";

	@OwnerUser @ProfileUser public static void start(final Context context, final @Nullable String action) {
		final Intent intent = new Intent(action).setComponent(new ComponentName(context, PrismProvisioning.class));
		if (SDK_INT >= O) context.startForegroundService(intent);
		else context.startService(intent);
	}

	/** This is the normal procedure after ManagedProvision finished its provisioning, running in profile. */
	@ProfileUser public static void onProfileProvisioningComplete(final Context context, final Intent intent) {
		Log.d(TAG, "onProfileProvisioningComplete");
		if (Users.isParentProfile()) return;		// Nothing to do for managed device provisioning.
		start(context, intent.getAction());
	}

	@OwnerUser @ProfileUser @WorkerThread @Override protected void onHandleIntent(@Nullable final Intent intent) {
		if (intent == null) return;		// Should never happen since we already setIntentRedelivery(true).
		proceed(this, intent);
	}

	@WorkerThread private static void proceed(final Context context, final Intent intent) {
		if (DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE.equals(intent.getAction())) {
			Log.d(TAG, "Re-provisioning MainSpace.");
			if (! Users.isParentProfile()) throw new IllegalStateException("Not running in owner user");
			startDeviceOwnerPostProvisioning(context, new DevicePolicies(context));
			Toasts.show(context, R.string.toast_reprovision_done, Toast.LENGTH_SHORT);
			return;
		}
		if (DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE.equals(intent.getAction())) {		// Borrow this activity intent for re-provision.
			Log.d(TAG, "Re-provisioning PrismSpace.");
			// Repair normally starts this service inside the managed profile through Shuttle. Keep the
			// boundary defensive because vendor service routing can still fail at runtime.
			try {
				reprovisionManagedProfile(context);
				Toasts.show(context, R.string.toast_reprovision_done, Toast.LENGTH_SHORT);
			} catch (final RuntimeException e) {
				Analytics.$().event("profile_reprovision_error").with(Analytics.Param.ITEM_NAME, e.toString()).send();
				Analytics.$().report(e);
				Toasts.show(context, R.string.toast_reprovision_failed, Toast.LENGTH_LONG);
			}
			return;
		}
		if (Users.isParentProfile() && DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED.equals(intent.getAction())) {	// ACTION_DEVICE_OWNER_CHANGED is added in Android 6.
			Analytics.$().event("device_provision_manual_start").send();
			startDeviceOwnerPostProvisioning(context, new DevicePolicies(context));
			return;
		}

		proceedProfileProvisioning(context, intent);
	}

	@WorkerThread private static synchronized void proceedProfileProvisioning(final Context context, final Intent intent) {
		final boolean is_manual_setup = Intent.ACTION_USER_INITIALIZE.equals(intent.getAction()) || intent.getAction() == null/* recovery procedure triggered by MainActivity */;
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final int provisionState = prefs.getInt(PREF_KEY_PROVISION_STATE, 0);
		if (! shouldRunProfilePostProvisioning(intent.getAction(), provisionState, POST_PROVISION_REV)) {
			DiagnosticLog.INSTANCE.i(TAG, "Skipping duplicate profile completion state=" + provisionState
					+ " revision=" + POST_PROVISION_REV);
			return;
		}
		Analytics.$().setProperty(Analytics.Property.PrismSetup, is_manual_setup ? "manual" : "managed");
		Log.d(TAG, "Provisioning profile (" + Users.toId(android.os.Process.myUserHandle()) + (is_manual_setup ? ", manual) " : ")"));

		prefs.edit().putInt(PREF_KEY_PROVISION_STATE, 1).putInt(PREF_KEY_PROFILE_PROVISION_TYPE, is_manual_setup ? 1 : 0).apply();
		final DevicePolicies policies = new DevicePolicies(context);
		if (is_manual_setup) {		// Do the similar job of ManagedProvisioning here.
			Log.d(TAG, "Manual provisioning");
			Analytics.$().event("profile_post_provision_manual_start").send();
			ProfileOwnerManualProvisioning.start(context, policies);	// Mimic the stock managed profile provision
		} else Analytics.$().event("profile_post_provision_start").send();

		Log.d(TAG, "Start post-provisioning.");
		try {
			startProfileOwnerPostProvisioning(context, policies);
		} catch (final Exception e) {
			Analytics.$().event("profile_post_provision_error").with(Analytics.Param.ITEM_NAME, e.toString()).send();
			Analytics.$().report(e);
		}

		// One system app policy for every creation path: it does not depend on the ROM's ManagedProvisioning
		// lists and keeps critical packages available as the final invariant. It must not block enabling the profile.
		try {
			SystemAppPolicyRuntime.converge(context, policies, ConvergeReason.Provision, provisionState);
		} catch (final RuntimeException e) {
			DiagnosticLog.INSTANCE.e(TAG, "System app policy convergence failed during provisioning", e);
			Analytics.$().report(e);
		}

		setupLauncherActivityInPrism(context);     // Must before setProfileEnabled() is invoked.
		if (! Users.isParentProfile()) retireConvergeTrampoline(context);

		if (! is_manual_setup) {	// Enable the profile here, launcher will show all apps inside.
			Log.d(TAG, "Enable profile now.");
			policies.execute(DevicePolicyManager::setProfileEnabled);
		}
		if (! PrismNameManager.syncNameToParentProfileAfterProvisioning(context))
			Log.w(TAG, "Failed to synchronize the profile name after provisioning.");
		Analytics.$().event("profile_post_provision_done").send();

		prefs.edit().putInt(PREF_KEY_PROVISION_STATE, POST_PROVISION_REV).apply();

		// ManagedProvisioning owns the normal task hand-off. Starting the parent UI from this profile
		// activity races with the system restoring the original task, so only historical manual setup
		// keeps the compatibility launch attempt.
		if (is_manual_setup) finishByLaunchingOwnerUser(context);
	}

	private static void finishByLaunchingOwnerUser(final Context context) {
		if (! launchMainActivityInOwnerUser(context)) {
			Analytics.$().event("error_launch_main_ui").send();
			DiagnosticLog.INSTANCE.e(TAG, "Failed to launch main activity in owner user.", null);
			Toasts.show(context, R.string.toast_setup_complete, Toast.LENGTH_LONG);
		}
	}

	static boolean shouldRunProfilePostProvisioning(final @Nullable String action, final int state, final int revision) {
		return ! ACTION_PROFILE_PROVISIONING_COMPLETE.equals(action) || state < revision;
	}

	@WorkerThread public static void performIncrementalProfileOwnerProvisioningIfNeeded(final Context context) {
		try {
			final DevicePolicies policies = new DevicePolicies(context);
			final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
			final int state = prefs.getInt(PREF_KEY_PROVISION_STATE, 0);
			if (shouldRunOneTimePostProvisionMigration(state, POST_PROVISION_REV)) {
				DiagnosticLog.INSTANCE.i(TAG, "Running post-provision migration " + state + " -> " + POST_PROVISION_REV);
				// Older revisions registered cross-profile filters with swapped directions. DPM can only add
				// filters, so drop every filter owned by us before re-installing the corrected set.
				policies.execute(DevicePolicyManager::clearCrossProfileIntentFilters);
				startProfileOwnerPostProvisioning(context, policies);
				prefs.edit().putInt(PREF_KEY_PROVISION_STATE, POST_PROVISION_REV).commit();
			} else if (state < FIRST_COMPLETED_POST_PROVISION_REV) {
				Log.i(TAG, "Initial profile provisioning owns state " + state + "; skipping incremental migration.");
			} else Log.i(TAG, "Post-provision migration already current at " + state);
			// System app availability is a runtime invariant, not a migration: the policy keeps critical
			// packages available on every pass, seeds existing spaces once and only writes changed targets.
			SystemAppPolicyRuntime.converge(context, policies, ConvergeReason.Incremental, state);
		} catch (final RuntimeException e) {
			Analytics.$().logAndReport(TAG, "Error provisioning profile", e);
		}
	}

	static boolean shouldRunOneTimePostProvisionMigration(final int state, final int revision) {
		return state >= FIRST_COMPLETED_POST_PROVISION_REV && state < revision;
	}

	/** Manual-equivalent steps belong to convergence for any profile whose initial provisioning never
	 *  completed, not only for profiles explicitly marked as manually provisioned. */
	static boolean shouldRunManualProvisioningExtras(final int provisionType, final int provisionState) {
		return provisionType == 1 || provisionState < FIRST_COMPLETED_POST_PROVISION_REV;
	}

	static boolean shouldAdvanceRevisionAfterExplicitRepair(final boolean runningInParent,
			final int state, final int revision) {
		return ! runningInParent && state < revision;
	}

	@Override public void onCreate() {
		super.onCreate();
		NotificationIds.Provisioning.startForeground(this, mForegroundNotification.get());
	}

	@Override public void onDestroy() {
		stopForeground(true);
		super.onDestroy();
	}

	@ProfileUser private static boolean launchMainActivityInOwnerUser(final Context context) {
		final ComponentName activity = Modules.getMainLaunchActivity(context);
		final LauncherApps apps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
		if (apps == null) return false;
		if (apps.isActivityEnabled(activity, Users.getParentProfile())) {
			DiagnosticLog.INSTANCE.i(TAG, "Launching main activity through LauncherApps compatibility route.");
			apps.startMainActivity(activity, Users.getParentProfile(), null, null);
			return true;
		}
		DiagnosticLog.INSTANCE.i(TAG, "Launching main activity through intent-forwarder compatibility route.");
		try {   // Since Android O, activities in owner user is invisible to managed profile.
			final Intent intent = new Intent(ACTION_MAIN).addFlags(FLAG_ACTIVITY_NEW_TASK);
			CrossProfile.decorateIntentForActivityInParentProfile(context, intent);
			context.startActivity(intent);
			return true;
		} catch (final RuntimeException e) {
			DiagnosticLog.INSTANCE.w(TAG, "Intent-forwarder main launch failed.", e);
			return false;
		}
	}

	@ProfileUser private static void setupLauncherActivityInPrism(final Context context) {
		setLauncherActivitiesEnabledSetting(context, CrossProfile.CATEGORY_PARENT_PROFILE, false);
		setLauncherActivitiesEnabledSetting(context, CrossProfile.CATEGORY_MANAGED_PROFILE, true);
	}

	static void setLauncherActivitiesEnabledSetting(final Context context, final String category, final boolean enabled) {
		final PackageManager pm = context.getPackageManager();
		final Intent intent = new Intent(ACTION_MAIN).addCategory(category);
		for (final ResolveInfo resolve : pm.queryIntentActivities(intent, enabled ? MATCH_DISABLED_COMPONENTS : 0)) {
			final ActivityInfo activity = resolve.activityInfo;
			if (activity.applicationInfo.uid != Process.myUid()) continue;
			pm.setComponentEnabledSetting(new ComponentName(activity.packageName, activity.name),
					enabled ? COMPONENT_ENABLED_STATE_ENABLED : COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
		}
	}

	@OwnerUser @ProfileUser @WorkerThread public static void reprovisionManagedProfile(final Context context) {
		final DevicePolicies policies = new DevicePolicies(context);
		final boolean owner = Users.isParentProfile();
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final int stateBeforeRepair = prefs.getInt(PREF_KEY_PROVISION_STATE, 0);
		if (! owner) {
			// Always perform all the required provisioning steps covered by stock ManagedProvisioning, in case something is missing there.
			// This is also required for manual provision via ADB shell.
			policies.execute(DevicePolicyManager::clearCrossProfileIntentFilters);
			final int provision_type = prefs.getInt(PREF_KEY_PROFILE_PROVISION_TYPE, 0);
			// Convergence must cover profiles whose initial provisioning never completed at all
			// (e.g. privileged-created ones never touched by the system flow), not only manual ones.
			if (shouldRunManualProvisioningExtras(provision_type, prefs.getInt(PREF_KEY_PROVISION_STATE, 0)))
				ProfileOwnerManualProvisioning.start(context, policies);	// Simulate the stock managed profile provision
			}
			startProfileOwnerPostProvisioning(context, policies);
			SystemAppPolicyRuntime.converge(context, policies, ConvergeReason.Repair, stateBeforeRepair);
			if (! owner) {
				setupLauncherActivityInPrism(context);
				retireConvergeTrampoline(context);
				final int state = prefs.getInt(PREF_KEY_PROVISION_STATE, 0);
				if (shouldAdvanceRevisionAfterExplicitRepair(owner, state, POST_PROVISION_REV)
						&& ! prefs.edit().putInt(PREF_KEY_PROVISION_STATE, POST_PROVISION_REV).commit())
					throw new IllegalStateException("Failed to persist completed profile repair revision");
			}
	}

	/** The trampoline exists so the profile can be reached before convergence; once converged the
	 *  settings entry is the user-facing surface and the trampoline icon must not linger. */
	@ProfileUser private static void retireConvergeTrampoline(final Context context) {
		context.getPackageManager().setComponentEnabledSetting(
				new ComponentName(context, ConvergeActivity.class), COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
	}

	public static void startDeviceAndProfileOwnerSharedPostProvisioning(final Context context, final DevicePolicies policies) {
		final boolean isParent = Users.isParentProfile();
		if (SDK_INT >= O) {
			final Set<String> ids = Collections.singleton(AFFILIATION_ID);
			final Set<String> current_ids = policies.invoke(DevicePolicyManager::getAffiliationIds);
			if (! ids.equals(current_ids)) try {
				policies.execute(DevicePolicyManager::setAffiliationIds, ids);
			} catch (final SecurityException ignored) {}	// SecurityException will be thrown if profile is not managed by PrismSpace.

			policies.clearUserRestrictionsIfNeeded(UserManager.DISALLOW_BLUETOOTH_SHARING);    // Ref: UserRestrictionsUtils.DEFAULT_ENABLED_FOR_MANAGED_PROFILES
		}
		if (isParent) policies.clearUserRestrictionsIfNeeded(UserManager.DISALLOW_SHARE_LOCATION);		// May be restricted on some devices (e.g. LG V20)

		try {
			if (SDK_INT >= N_MR1 && ! policies.isBackupServiceEnabled())
				policies.setBackupServiceEnabled(true);     // Ref: DevicePolicyManagerService.toggleBackupServiceActive()
		} catch (final SecurityException e) {   // isBackupServiceEnabled()/setBackupServiceEnabled() require device owner before Android Q.
			if (SDK_INT >= Q || ! isParent && ! "There should only be one user, managed by Device Owner".equals(e.getMessage()))
				Analytics.$().report(e);
		} catch (final IllegalStateException e) {
			Analytics.$().report(e);
		}

		policies.execute(DevicePolicyManager::setShortSupportMessage,
				context.getText(com.yzddmr6.prismspace.shared.R.string.device_admin_support_message_short));
		policies.execute(DevicePolicyManager::setLongSupportMessage,
				context.getText(com.yzddmr6.prismspace.shared.R.string.device_admin_support_message_long));
		// As reported by user, some account types are strangely unable to remove. Just make sure all account types are allowed.
		final String[] restricted_account_types = policies.getManager().getAccountTypesWithManagementDisabled();
		if (restricted_account_types != null && restricted_account_types.length > 0) for (final String account_type : restricted_account_types)
			policies.execute(DevicePolicyManager::setAccountManagementDisabled, account_type, false);
	}

	/** MainSpace can be activated as either profile owner or device owner. */
	@OwnerUser public static void startOwnerUserPostProvisioningIfNeeded(final Context context) {
		try {   // Re-enable the accidentally disabled main UI activity. (due to a bug introduced in v4.5.3 when re-provisioning owner user in profile owner mode)
			context.getPackageManager().setComponentEnabledSetting(Modules.getMainLaunchActivity(context), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, DONT_KILL_APP);
		} catch (final SecurityException ignored) {}
		final DevicePolicies policies = new DevicePolicies(context);
		if (policies.isActiveDeviceOwner()) startDeviceOwnerPostProvisioning(context, policies);
		else if (policies.isProfileOwner()) startProfileOwnerPostProvisioning(context, policies);
	}

	/** All the initializations for mainland as device owner. */
	@OwnerUser public static void startDeviceOwnerPostProvisioning(final Context context, final DevicePolicies policies) {
		startDeviceAndProfileOwnerSharedPostProvisioning(context, policies);
		if (SDK_INT >= O) policies.clearUserRestrictionsIfNeeded(UserManager.DISALLOW_ADD_MANAGED_PROFILE);	// Ref: UserRestrictionsUtils.DEFAULT_ENABLED_FOR_DEVICE_OWNERS
	}

	/** All the preparations after the provisioning procedure of system ManagedProvisioning, also shared by manual and incremental provisioning. */
	@WorkerThread private static void startProfileOwnerPostProvisioning(final Context context, final DevicePolicies policies) {
		final boolean owner = Users.isParentProfile();

		// Acquire SYSTEM_ALERT_WINDOW permission to overcome "background activity start" blocking on Android Q+, required by ShuttleProvider.
		if (SDK_INT > P && ! Settings.canDrawOverlays(context))
			new AppOpsCompat(context).setMode(AppOpsCompat.OP_SYSTEM_ALERT_WINDOW, Process.myUid(), context.getPackageName(), MODE_ALLOWED);

		if (! owner) ShuttleProvider.Companion.initialize(context);  // Initialization of shuttle requires foreground activity to avoid its activity start being blocked.

		startDeviceAndProfileOwnerSharedPostProvisioning(context, policies);

		PrismManager.ensureLegacyInstallNonMarketAppAllowed(context, policies);

		if (SDK_INT >= Q) policies.execute(DevicePolicyManager::setCrossProfileCalendarPackages, null);

		// Some Samsung devices default to restrict all 3rd-party cross-profile services (IMEs, accessibility and etc).
		policies.execute(DevicePolicyManager::setPermittedInputMethods, null);
		policies.execute(DevicePolicyManager::setPermittedAccessibilityServices, null);
		if (SDK_INT >= O) policies.execute(DevicePolicyManager::setPermittedCrossProfileNotificationListeners, null);

		if (! owner) startProfileOwnerPostProvisioningForNonOwnerProfile(context, policies);
	}

	@ProfileUser private static void startProfileOwnerPostProvisioningForNonOwnerProfile(final Context context, final DevicePolicies policies) {
		policies.addUserRestrictionIfNeeded(UserManager.ALLOW_PARENT_PROFILE_APP_LINKING);
		CrossProfileIntentFiltersHelper.setFilters(policies);
		enableAdditionalForwarding(context, policies);

		// Prepare API
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(Api.latest.ACTION_FREEZE).withDataSchemes("package", "packages"), FLAG_MANAGED_CAN_ACCESS_PARENT);
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(Api.latest.ACTION_UNFREEZE).withDataSchemes("package", "packages"), FLAG_MANAGED_CAN_ACCESS_PARENT);
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(Api.latest.ACTION_LAUNCH).withDataSchemes("package", "intent"), FLAG_MANAGED_CAN_ACCESS_PARENT);

		// Keep parent-to-profile app-details forwarding for app management flows.
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).withDataScheme("package"), FLAG_MANAGED_CAN_ACCESS_PARENT);
	}

	@ProfileUser private static void enableAdditionalForwarding(final Context context, final DevicePolicies policies) {
		final int FLAGS_BIDIRECTIONAL = FLAG_MANAGED_CAN_ACCESS_PARENT | FLAG_PARENT_CAN_ACCESS_MANAGED;
		// For sharing across PrismSpace (bidirectional)
		policies.addCrossProfileIntentFilter(new IntentFilter(ACTION_SEND), FLAGS_BIDIRECTIONAL);		// Keep for historical compatibility reason
		try {
			policies.addCrossProfileIntentFilter(IntentFilters.forAction(ACTION_SEND).withDataType("*/*"), FLAGS_BIDIRECTIONAL);
			policies.addCrossProfileIntentFilter(IntentFilters.forAction(ACTION_VIEW).withDataType("*/*"), FLAGS_BIDIRECTIONAL);
			policies.addCrossProfileIntentFilter(IntentFilters.forAction(ACTION_SEND_MULTIPLE).withDataType("*/*"), FLAGS_BIDIRECTIONAL);
		} catch (final IntentFilter.MalformedMimeTypeException ignored) {}
		// For Storage Access Framework
		policies.addCrossProfileIntentFilter(new IntentFilter(ACTION_OPEN_DOCUMENT_TREE), FLAGS_BIDIRECTIONAL);
		// For web browser
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(ACTION_VIEW).withCategory(CATEGORY_BROWSABLE).withDataSchemes("http", "https", "ftp"),
				FLAG_PARENT_CAN_ACCESS_MANAGED);
		try {	// For Package Installer
			policies.addCrossProfileIntentFilter(IntentFilters.forActions(ACTION_INSTALL_PACKAGE)   // ACTION_VIEW is already covered above for */*.
					.withDataScheme(ContentResolver.SCHEME_CONTENT).withDataType("application/vnd.android.package-archive"), FLAGS_BIDIRECTIONAL);
		} catch (final IntentFilter.MalformedMimeTypeException ignored) {}
		policies.addCrossProfileIntentFilter(IntentFilters.forActions(IntentCompat.ACTION_SHOW_APP_INFO), FLAG_PARENT_CAN_ACCESS_MANAGED);
	}

	public PrismProvisioning() {
		super(TAG);
		setIntentRedelivery(true);
	}

	private final Supplier<Notification.Builder> mForegroundNotification = Suppliers.memoize(() -> {
		final Notification.Builder builder = new Notification.Builder(this)
				.setPriority(PRIORITY_HIGH).setCategory(CATEGORY_STATUS).setUsesChronometer(true)
				.setSmallIcon(android.R.drawable.stat_notify_sync)
				.setColor(getColor(com.yzddmr6.prismspace.shared.R.color.accent))
				.setContentTitle(getText(Users.isParentProfile() ? R.string.notification_provisioning_mainland_title : R.string.notification_provisioning_space_title))
				.setContentText(getText(R.string.notification_provisioning_text));
		return SDK_INT < O ? builder : builder.setBadgeIconType(BADGE_ICON_SMALL).setColorized(true);
	});

	/** Receives {@link DevicePolicyManager#ACTION_PROVISIONING_SUCCESSFUL} in managed profile */
	public static class CompletionActivity extends Activity {

		@Override protected void onCreate(@Nullable final Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			if (! DevicePolicyManager.ACTION_PROVISIONING_SUCCESSFUL.equals(getIntent().getAction())) {
				Log.w(TAG, getClass().getSimpleName() + " should not be started after provisioning.");
				finish();
				return;
			}
			final Dialogs.FluentProgressDialog progress = Dialogs.buildProgress(this,
					R.string.notification_provisioning_space_title).indeterminate().nonCancelable();
			progress.show();

			SafeAsyncTask.execute(this, a -> proceed(a, new Intent(ACTION_PROFILE_PROVISIONING_COMPLETE)),
					activity -> { progress.dismiss(); activity.finish(); });
		}
	}

	/**
	 * Always-enabled exported trampoline into profile-side convergence. The privileged creation
	 * path never runs the system provisioning flow, so no provisioning broadcast is guaranteed to
	 * arrive (HyperOS 1 evidence); and when the settings entry is still disabled, neither the
	 * Shuttle bridge nor LauncherApps entry-open can reach profile-side code. This trampoline is
	 * the one channel left: the parent app starts it via LauncherApps in its own profile. It only
	 * converges a profile we own; anywhere else it is a no-op.
	 */
	public static class ConvergeActivity extends Activity {

		@Override protected void onCreate(@Nullable final Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			if (! Users.isParentProfile() && new DevicePolicies(this).isProfileOwner()) {
				DiagnosticLog.INSTANCE.i(TAG, "converge trampoline entered; starting profile reprovision");
				PrismProvisioning.start(this, DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE);
			} else {
				Log.w(TAG, "ConvergeActivity started outside an owned profile; ignoring.");
			}
			finish();
		}
	}

	private static final String TAG = "Prism.Provision";
}
