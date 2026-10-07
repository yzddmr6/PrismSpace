package com.yzddmr6.prismspace;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;

import androidx.core.view.WindowCompat;
import androidx.fragment.app.FragmentActivity;

import com.yzddmr6.prismspace.analytics.Analytics;
import com.yzddmr6.prismspace.analytics.Analytics.Property;
import com.yzddmr6.prismspace.controller.LegacyClonePrefs;
import com.yzddmr6.prismspace.mobile.BuildConfig;
import com.yzddmr6.prismspace.mobile.R;
import com.yzddmr6.prismspace.prism.compose.host.PrismComposeHostFragment;
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository;
import com.yzddmr6.prismspace.setup.SetupActivity;
import com.yzddmr6.prismspace.space.SpaceState;
import com.yzddmr6.prismspace.util.DeviceAdmins;
import com.yzddmr6.prismspace.util.DevicePolicies;
import com.yzddmr6.prismspace.util.Loopers;
import com.yzddmr6.prismspace.util.Modules;
import com.yzddmr6.prismspace.util.PrismLocale;
import com.yzddmr6.prismspace.util.Scopes;
import com.yzddmr6.prismspace.util.Users;
import com.yzddmr6.prismspace.prism.transfer.TransferOpenRequests;

import java.util.List;
import java.util.Optional;

public class MainActivity extends FragmentActivity {

	@Override protected void attachBaseContext(final Context newBase) {
		super.attachBaseContext(PrismLocale.wrap(newBase));   // per-app 中/英 language override
	}

	@Override protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (! Users.isParentProfile()) {
			if (new DevicePolicies(this).isProfileOwner()) {    // Should generally not run in profile, unless the managed profile provision is interrupted or manually provision is not complete.
				onCreateInProfile();
				finish();
				} else startSetupWizard();
			return;
		}
		mIsDeviceOwner = new DevicePolicies(this).isProfileOrDeviceOwnerOnCallingUser();
		// Restored fragments attach during onStart. Their container must exist before asynchronous
		// profile discovery returns, otherwise a theme/language recreation leaves an unattached view.
		WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
		getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
		getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
		setContentView(R.layout.activity_main);
		continueParentStartup(savedInstanceState);
	}

	private void continueParentStartup(final Bundle savedInstanceState) {
		new Thread(() -> LegacyClonePrefs.INSTANCE.purge(getApplicationContext()), "Prism-housekeeping").start();
		if (mIsDeviceOwner) {
			startMainUi(savedInstanceState);	// As device owner, always show main UI.
			return;
		}
		resolveInitialRoute(savedInstanceState);
	}

	private void resolveInitialRoute(final Bundle savedInstanceState) {
		new Thread(() -> {
			SpaceState resolved;
			try {
				resolved = new SpaceStateRepository(getApplicationContext())
						.awaitInitialStateBlocking(INITIAL_STATE_TIMEOUT_MS);
			} catch (final RuntimeException error) {
				Log.e(TAG, "Initial space-state collection failed", error);
				resolved = null;
			}
			final SpaceState state = resolved;
			runOnUiThread(() -> {
				if (isFinishing() || isDestroyed()) return;
				// Only a confirmed absence enters setup. Unknown/failed states stay on the repair-capable UI.
				if (SpaceStateRepository.shouldOpenSetup(state)) {
					Log.i(TAG, "Profile not setup yet");
					startSetupWizard();
				} else {
					if (state == null) Log.w(TAG, "Initial space-state collection timed out; opening main UI");
					startMainUi(savedInstanceState);
				}
			});
		}, "Prism-initial-space-state").start();
	}

	@Override protected void onNewIntent(final Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		// Launcher taps on a running task land here. Do NOT signal reset-to-Home — that would
		// override the last visited tab. The signal only fires on fresh MainActivity creation
		// (see startMainUi below, gated on savedInstanceState == null).
	}

	/** A pending "open this transferred file" request parked by the dual space; drained in the foreground. */
	@Override protected void onResume() {
		super.onResume();
		if (Users.isParentProfile()) TransferOpenRequests.drain(this);
	}

	@Override protected void onPostResume() {
		super.onPostResume();
		if (! mMainUiPending) return;
		final Bundle savedInstanceState = mPendingMainUiState;
		mMainUiPending = false;
		mPendingMainUiState = null;
		startMainUi(savedInstanceState);
	}

	private void onCreateInProfile() {
		final DevicePolicies policies = new DevicePolicies(this);
		if (! policies.invoke(DevicePolicyManager::isAdminActive)) {
			Analytics.$().event("inactive_device_admin").send();
			startActivity(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
					.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceAdmins.getComponentName(this))
					.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.dialog_reactivate_message)));
			return;
		}
		final PackageManager pm = getPackageManager();
		final List<ResolveInfo> resolves = pm.queryBroadcastReceivers(new Intent(Intent.ACTION_USER_INITIALIZE).setPackage(Modules.MODULE_ENGINE), 0);
		final Optional<ResolveInfo> resolve = resolves.stream().filter(r ->
				r.activityInfo.name.startsWith("com.yzddmr6.prismspace.provision")).findFirst();
		if (resolve.isPresent()) {
			Log.w(TAG, "Manual provisioning is pending, resume it now.");
			Analytics.$().event("profile_post_provision_pending").send();
			final ActivityInfo receiver = resolve.get().activityInfo;
			sendBroadcast(new Intent().setClassName(receiver.packageName, receiver.name));
		} else {    // Receiver disabled but launcher entrance is left enabled. The best bet is just disabling the launcher entrance. No provisioning attempt any more.
			Log.w(TAG, "Manual provisioning is finished, but launcher activity is still left enabled. Disable it now.");
			Analytics.$().event("profile_post_provision_activity_leftover").send();
			pm.setComponentEnabledSetting(new ComponentName(this, getClass()), COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
		}
	}

	private void startMainUi(final Bundle savedInstanceState) {
		if (mMainUiStarted) return;
		// Initial routing runs off-main. A locked screen or a quick background transition can save
		// FragmentManager state before that result arrives; committing then crashes instead of merely
		// waiting for the Activity to become interactive again. onPostResume is the first lifecycle
		// callback where FragmentManager has cleared its saved-state guard.
		if (getSupportFragmentManager().isStateSaved()) {
			mMainUiPending = true;
			mPendingMainUiState = savedInstanceState;
			return;
		}
		mMainUiStarted = true;
		if (getSupportFragmentManager().findFragmentById(R.id.container) != null) return;
		final PrismComposeHostFragment fragment = new PrismComposeHostFragment();
		getSupportFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
		performOverallAnalyticsIfNeeded();
	}

	private void startSetupWizard() {
		startActivity(new Intent(this, SetupActivity.class));
		performOverallAnalyticsIfNeeded();
		finish();
	}

	private void performOverallAnalyticsIfNeeded() {
		if (! BuildConfig.DEBUG && ! Scopes.boot(this).mark("overall_analytics")) return;
		Loopers.addIdleTask(() -> {
			final Analytics analytics = Analytics.$();
			analytics.setProperty(Property.DeviceOwner, mIsDeviceOwner);
			analytics.setProperty(Property.RemoteConfigAvailable, Config.isRemote());
		});
	}

	private boolean mIsDeviceOwner;
	private boolean mMainUiStarted;
	private boolean mMainUiPending;
	private Bundle mPendingMainUiState;

	private static final long INITIAL_STATE_TIMEOUT_MS = 5_000L;
	private static final String TAG = "Prism.Main";
}
