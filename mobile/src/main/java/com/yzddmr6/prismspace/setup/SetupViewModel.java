package com.yzddmr6.prismspace.setup;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.CheckResult;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.yzddmr6.prismspace.util.UserHandles;
import com.yzddmr6.prismspace.analytics.Analytics;
import com.yzddmr6.prismspace.engine.PrismManager;
import com.yzddmr6.prismspace.mobile.R;
import com.yzddmr6.prismspace.util.DeviceAdmins;
import com.yzddmr6.prismspace.util.DevicePolicies;
import com.yzddmr6.prismspace.util.Modules;
import com.yzddmr6.prismspace.util.Users;

import java.util.Optional;

import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.TIRAMISU;
import static com.yzddmr6.prismspace.analytics.Analytics.Param.ITEM_ID;
import static com.yzddmr6.prismspace.analytics.Analytics.Param.ITEM_NAME;

/**
 * View model for setup activity.
 *
 * <p>Owns the DPM prerequisite-check state machine ({@link #checkManagedProvisioningPrerequisites})
 * and exposes the resolved error state via public fields {@link #message}, {@link #message_params},
 * and {@link #action_extra}. The Compose UI layer
 * ({@link com.yzddmr6.prismspace.setup.compose.SetupController}) consumes these fields and
 * dispatches {@link #buildManagedProfileProvisioningIntentPublic} via an
 * {@code ActivityResultLauncher}.
 *
 * <p>The legacy Fragment-driven state machine and {@link android.os.Parcelable}
 * boilerplate were removed. Only the static DPM logic and the error-state DTO surface remain.
 *
 * Created by Oasis on 2016/4/19.
 */
public class SetupViewModel {

	public @StringRes int message;
	public @Nullable Object[] message_params;
	public int action_extra;
	/** Secondary action, kept alongside {@link #action_extra} so overriding the primary with a
	 *  fallback button never silently removes the only route to the setup help. */
	public int action_secondary;
	/** True when the error state should offer "try system setup anyway" — the resolveActivity
	 *  pre-check is a heuristic and must never hard-block the one truthful test: attempting. */
	public boolean try_provision_anyway;

	/** @return null if all prerequisites are met. */
	public static @CheckResult SetupViewModel checkManagedProvisioningPrerequisites(final Context context, final boolean ignore_incomplete_setup) {
		final PackageManager pm = context.getPackageManager();
		if (buildManagedProfileProvisioningIntent(context).resolveActivity(pm) == null)
			return buildMissingProvisioningError(context);

		// Check for incomplete provisioning, before DPM.isProvisioningAllowed() check which returns true in this case.
		if (! Users.hasProfile()) for (final int profile_id : PrismManager.getProfileIdsIncludingDisabled(context)) {
			if (Users.isParentProfile(profile_id)) continue;
			final Optional<ComponentName> owner = DevicePolicies.getProfileOwnerAsUser(context, UserHandles.of(profile_id));
			if (owner == null || ! owner.isPresent()) continue;
			final ComponentName profile_owner = owner.get();
			if (! Modules.MODULE_ENGINE.equals(profile_owner.getPackageName())) {
				final CharSequence label = readOwnerLabel(context, profile_owner);
				reason("existent_work_profile").with(ITEM_ID, profile_owner.getPackageName()).with(ITEM_NAME, label != null ? label.toString() : null).send();
				continue;
			}
			if (ignore_incomplete_setup) continue;
			return buildErrorVM(R.string.setup_error_provisioning_incomplete, reason("provisioning_incomplete")).withExtraAction(R.string.button_have_checked);
		}

		// DPM.isProvisioningAllowed() is the one-stop prerequisites checking.
		final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
		if (dpm != null && dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE))
			Analytics.$().event("device_provision_allowed").send();	// Special analytics
		if (dpm != null && dpm.isProvisioningAllowed(ACTION_PROVISION_MANAGED_PROFILE)) return null;

		final boolean has_managed_users = pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS);
		if (! has_managed_users)
			return buildErrorVM(R.string.setup_error_managed_profile_not_supported, reason("lack_managed_users"));

		final String device_owner = new DevicePolicies(context).getDeviceOwner();
		if (device_owner != null) {
			CharSequence owner_label = null;
			try {
				owner_label = pm.getApplicationInfo(device_owner, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm);
			} catch (final PackageManager.NameNotFoundException | RuntimeException ignored) {
				// Package visibility, stale DPC metadata, or vendor resources may make the label unreadable.
			}

			reason("managed_device").with(ITEM_ID, device_owner).send();
			return buildManagedDeviceError(owner_label, context.getString(R.string.setup_unknown_device_owner));
		}

		// The platform refused, and the refusal is the fact the user has to act on. Returning null
		// here used to hand control to system provisioning anyway, which then returned
		// RESULT_CANCELED within seconds and looked like a user cancellation.
		return buildDisallowedProvisioningError(context);
	}

	private static Analytics.Event reason(final String reason) {
		return Analytics.$().event("setup_prism_failure").with(Analytics.Param.ITEM_CATEGORY, reason);
	}

	/**
	 * The provisioning activity does not resolve. This app holds {@code QUERY_ALL_PACKAGES},
	 * so package visibility is ruled out; the entry is genuinely absent or disabled. Vendor
	 * dual-apps features (MIUI 应用双开/手机分身) are known to occupy the entry — a
	 * recoverable state, not a missing platform feature. The probe separates the realities
	 * for diagnostics, the copy only claims occupation when a handler exists to be occupied,
	 * and the user always keeps the option to attempt the system flow anyway.
	 */
	private static SetupViewModel buildMissingProvisioningError(final Context context) {
		final ProvisioningProbe probe = ProvisioningProbe.collect(context);
		final int profile_count = probe.foreign_profile_count;
		com.yzddmr6.prismspace.analytics.DiagnosticLog.INSTANCE.i(TAG,
				"provisioning entry missing: " + probe.toLogString() + " profiles=" + profile_count);
		reason("lack_managed_provisioning").withRaw("profiles", String.valueOf(profile_count))
				.withRaw("miui", String.valueOf(com.yzddmr6.prismspace.util.RomVariants.isMiui()))
				.withRaw("mp", probe.classify().name().toLowerCase(java.util.Locale.US)).send();
		final @StringRes int message = ProvisioningProbe.errorMessageFor(probe.classify(), profile_count, probe.managed_users_feature);
		final SetupViewModel error = buildErrorVM(message, null);
		if (ProvisioningProbe.shouldOfferPrivilegedFallback(probe.managed_users_feature))
			error.withExtraAction(R.string.button_setup_space_privileged);
		error.withTryProvisionAnyway();
		com.yzddmr6.prismspace.analytics.DiagnosticLog.INSTANCE.i(TAG,
				"setup error offered: message=" + context.getResources().getResourceEntryName(message)
						+ " extra=" + (error.action_extra != 0 ? context.getResources().getResourceEntryName(error.action_extra) : "none")
						+ " tryAnyway=" + error.try_provision_anyway);
		return error;
	}

	/** Public entry for the Compose controller's launch-failure fallback, so the post-attempt
	 *  error offers exactly the same actions as the pre-check error — a single source of truth. */
	public static SetupViewModel missingProvisioningErrorPublic(final Context context) {
		return buildMissingProvisioningError(context);
	}

	/**
	 * The system provisioning entry resolves, yet {@code DPM.isProvisioningAllowed()} says no and
	 * no device owner explains it. Android does not expose the cause, so the probe collects the two
	 * realities that do explain it in the field — the {@code no_add_managed_profile} restriction and
	 * a foreign profile already occupying the one-per-user managed profile slot (vendor "app clone"
	 * users on ColorOS/MIUI) — and the copy only claims what the evidence supports. Launching the
	 * system flow anyway stays available: the pre-check is not the truthful test, the attempt is.
	 */
	private static SetupViewModel buildDisallowedProvisioningError(final Context context) {
		final ProvisioningProbe probe = ProvisioningProbe.collect(context);
		com.yzddmr6.prismspace.analytics.DiagnosticLog.INSTANCE.i(TAG,
				"provisioning disallowed by platform: " + probe.toLogString());
		reason("disallowed").withRaw("restricted", String.valueOf(probe.add_managed_profile_restricted))
				.withRaw("foreign", String.valueOf(probe.foreign_profile_count))
				.withRaw("foreign_managed", String.valueOf(probe.foreign_managed_profile_count)).send();
		final @StringRes int message = ProvisioningProbe.disallowedMessageFor(probe.add_managed_profile_restricted,
				probe.foreign_profile_count, probe.foreign_managed_profile_count);
		final SetupViewModel error = buildErrorVM(message, null);
		if (ProvisioningProbe.shouldOfferPrivilegedFallback(probe.managed_users_feature))
			error.withExtraAction(R.string.button_setup_space_privileged);
		error.withTryProvisionAnyway();
		com.yzddmr6.prismspace.analytics.DiagnosticLog.INSTANCE.i(TAG,
				"setup error offered: message=" + context.getResources().getResourceEntryName(message)
						+ " extra=" + (error.action_extra != 0 ? context.getResources().getResourceEntryName(error.action_extra) : "none")
						+ " tryAnyway=" + error.try_provision_anyway);
		return error;
	}

	/** Public entry for the Compose controller's post-result refusal handling, so the refusal error
	 *  carries fresh probe evidence collected at the moment the system actually refused. */
	public static SetupViewModel disallowedProvisioningErrorPublic(final Context context) {
		return buildDisallowedProvisioningError(context);
	}

	private static SetupViewModel buildErrorVM(final @StringRes int message, final @Nullable Analytics.Event event) {
		if (event != null) event.send();
		final SetupViewModel next = new SetupViewModel();
		next.message = message;
		next.action_extra = R.string.button_setup_help;	// Default extra action, can be overridden by withExtraAction().
		return next;
	}

	/** Builds the honest, OK-only Device Owner boundary state. Package-visible for JVM tests. */
	static SetupViewModel buildManagedDeviceError(final @Nullable CharSequence owner_label, final String unknown_label) {
		final String resolved_label = owner_label == null ? "" : owner_label.toString().trim();
		final SetupViewModel error = buildErrorVM(R.string.setup_error_managed_device, null);
		error.message_params = new String[] { resolved_label.isEmpty() ? unknown_label : resolved_label };
		error.action_extra = 0;		// No reliable removal page exists, and Root cannot bypass this platform rule.
		return error;
	}

	private SetupViewModel withExtraAction(final @StringRes int text) {
		// The default primary action is the setup help. Overriding it with a fallback button must
		// not be the reason the help disappears — demote it to the secondary slot instead.
		if (action_extra == R.string.button_setup_help && text != R.string.button_setup_help)
			action_secondary = R.string.button_setup_help;
		action_extra = text;
		return this;
	}

	private SetupViewModel withTryProvisionAnyway() { try_provision_anyway = true; return this; }

	/** Public accessor for the Compose {@link com.yzddmr6.prismspace.setup.compose.SetupController}.
	 *  Same intent the Java state machine launched; do not modify. */
	public static Intent buildManagedProfileProvisioningIntentPublic(final Context context) {
		return buildManagedProfileProvisioningIntent(context);
	}

	private static Intent buildManagedProfileProvisioningIntent(final Context context) {
		final Intent intent = new Intent(ACTION_PROVISION_MANAGED_PROFILE);
		intent.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, DeviceAdmins.getComponentName(context));
		intent.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true);		// Actually works on Android 7+.
		if (SDK_INT >= O) intent.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_USER_CONSENT, true);
		if (SDK_INT >= TIRAMISU) intent.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE, true);
		return intent;
	}

	private static CharSequence readOwnerLabel(final Context context, final ComponentName owner) {
		final PackageManager pm = context.getPackageManager();
		try {
			return pm.getReceiverInfo(owner, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm);	// It should be a BroadcastReceiver
		} catch (final PackageManager.NameNotFoundException e) {
			try {
				return pm.getApplicationInfo(owner.getPackageName(), PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm);    // If not, use app label
			} catch (final PackageManager.NameNotFoundException ex) {
				return null;
			}
		}
	}

	SetupViewModel() {}

	private static final String TAG = "Prism.Setup";
}
