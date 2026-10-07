package com.yzddmr6.prismspace.setup;

import static com.yzddmr6.prismspace.analytics.Analytics.Param.CONTENT;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.provider.Settings;

import com.yzddmr6.prismspace.prism.compose.nav.SystemAppPickerPrompt;
import com.yzddmr6.prismspace.util.Dialogs;
import com.yzddmr6.prismspace.analytics.Analytics;
import com.yzddmr6.prismspace.mobile.R;
import com.yzddmr6.prismspace.prism.compose.space.CreateSpaceResult;
import com.yzddmr6.prismspace.prism.compose.space.RootSetupPresentation;
import com.yzddmr6.prismspace.prism.compose.space.RootSetupResultMapping;
import com.yzddmr6.prismspace.prism.compose.space.RootSetupUiOutcome;
import com.yzddmr6.prismspace.prism.compose.space.SpaceProvisioningEngine;
import com.yzddmr6.prismspace.prism.compose.vm.ProvisioningFeedbackBridge;
import com.yzddmr6.prismspace.util.ProfileUser;
import com.yzddmr6.prismspace.util.SafeAsyncTask;

/**
 * Implementation of PrismSpace / MainSpace setup & shutdown.
 *
 * Created by Oasis on 2017/3/8.
 */
public class PrismSetup {

	/**
	 * Privileged space creation entry (setup wizard fallback). Exactly one transport is used:
	 * an authorized Shizuku wins; otherwise the engine probes su. When Shizuku is running but
	 * not yet authorized, permission is requested here — only on this explicit user action.
	 */
	public static void requestPrivilegedSetup(final Activity activity) {
		if (ShizukuSetupAuthorization.isAuthorized() || ! ShizukuSetupAuthorization.isRunning()) {
			startPrivilegedSetup(activity);
			return;
		}
		ShizukuSetupAuthorization.requestPermissionCompat(granted -> {
			if (granted) {
				startPrivilegedSetup(activity);
			} else {
				Analytics.$().event("setup_prism_shizuku_denied").send();
				Dialogs.buildAlert(activity, R.string.dialog_title_warning, R.string.dialog_shizuku_permission_denied)
						.withOkButton(null).show();
			}
		});
	}

	private static void startPrivilegedSetup(final Activity activity) {
		final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.setup_root_profile_progress), true);
		SafeAsyncTask.execute(activity,
				SpaceProvisioningEngine::createSpaceBlocking,
				(context, result) -> showRootSetupResult(context, progress, result));
	}

	private static void showProfileAlreadyExistsDialog(final Activity activity) {
		Dialogs.buildAlert(activity, R.string.dialog_title_warning, R.string.dialog_root_setup_profile_exists)
				.withOkButton(null).show();
			Analytics.$().event("setup_prism_root_skipped").withRaw("reason", "existing_profile").send();
	}

	private static void showRootSetupResult(final Activity activity, final ProgressDialog progress,
	                                        final CreateSpaceResult result) {
		final RootSetupPresentation presentation = RootSetupResultMapping.presentation(result);
		if (presentation.getOutcome() == RootSetupUiOutcome.Success) {
			dismissProgress(progress);
			Analytics.$().event("setup_prism_root_done").send();
			// Ask which system apps to bring in; the profile-side status decides if it is still due.
			SystemAppPickerPrompt.markExpected(activity,
					result instanceof CreateSpaceResult.Success ? ((CreateSpaceResult.Success) result).getUserId() : null);
			activity.finish();
		} else if (presentation.getOutcome() == RootSetupUiOutcome.ExistingProfile) {
			dismissProgress(progress);
			showProfileAlreadyExistsDialog(activity);
		} else {
			final int phase = presentation.getAnalyticsPhase() == null ? 2 : presentation.getAnalyticsPhase();
			Analytics.$().event("setup_prism_root_failed")
					.withRaw("phase", String.valueOf(phase))
					.with(CONTENT, result.toString()).send();
			dismissProgressAndShowError(activity, progress, phase, result);
		}
	}

	private static void dismissProgressAndShowError(final Activity activity, final ProgressDialog progress,
	                                                final int stage, final CreateSpaceResult result) {
		dismissProgress(progress);
		final String specificFailure = ProvisioningFeedbackBridge.specificRootSetupFailure(activity, result);
		final String message = specificFailure != null
				? specificFailure : activity.getString(R.string.dialog_space_setup_failed, stage);
		Dialogs.buildAlert(activity, null, message).withOkButton(null).show();
	}

	private static void dismissProgress(final ProgressDialog progress) {
		if (progress.isShowing()) progress.dismiss();
	}

	private static void showPromptForProfileManualRemoval(final Activity activity) {
		final AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setMessage(R.string.dialog_cannot_destroy_message)
				.setNegativeButton(android.R.string.ok, null);
		final Intent intent = new Intent(Settings.ACTION_SYNC_SETTINGS);
		if (intent.resolveActivity(activity.getPackageManager()) == null) intent.setAction(Settings.ACTION_SETTINGS);	// Fallback to entrance of Settings
		if (intent.resolveActivity(activity.getPackageManager()) != null)
			dialog.setPositiveButton(R.string.open_settings, (d, w) -> activity.startActivity(intent));
		dialog.show();
		Analytics.$().event("cannot_destroy").send();
	}

	/** Compose-path entry: surface the system manual-removal prompt (used when the
	 *  feedback mapper reports routeToSystemRemoval). Delegates to the private impl. */
	@ProfileUser public static void promptManualRemoval(final Activity activity) {
		showPromptForProfileManualRemoval(activity);
	}
}
