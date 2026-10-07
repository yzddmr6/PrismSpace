package com.yzddmr6.prismspace.provisioning;

import android.app.admin.DeviceAdminReceiver;
import android.content.Intent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrismProvisioningRevisionTest {
    @Test public void freshAndIncompleteStatesWaitForInitialProvisioning() {
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(0, 10));
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(1, 10));
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(2, 10));
    }

    @Test public void completedOldStatesRunMigration() {
        assertTrue(PrismProvisioning.shouldRunOneTimePostProvisionMigration(3, 10));
        assertTrue(PrismProvisioning.shouldRunOneTimePostProvisionMigration(9, 10));
    }

    @Test public void revisionIsBumpedForSystemAppPolicy() throws Exception {
        final java.lang.reflect.Field field = PrismProvisioning.class.getDeclaredField("POST_PROVISION_REV");
        field.setAccessible(true);
        assertEquals(12, field.getInt(null));
    }

    @Test public void previousRevisionRunsSystemAppPolicyMigration() {
        assertTrue(PrismProvisioning.shouldRunOneTimePostProvisionMigration(10, 12));
        assertTrue(PrismProvisioning.shouldRunOneTimePostProvisionMigration(11, 12));
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(12, 12));
    }

    @Test public void migrationClearsOwnedCrossProfileFiltersBeforeReinstalling() throws Exception {
        final String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/yzddmr6/prismspace/provisioning/PrismProvisioning.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        final String migration = source.substring(source.indexOf("void performIncrementalProfileOwnerProvisioningIfNeeded"));
        final int clear = migration.indexOf("clearCrossProfileIntentFilters");
        final int reinstall = migration.indexOf("startProfileOwnerPostProvisioning(context, policies)");
        assertTrue(clear >= 0);
        assertTrue(reinstall > clear);
    }

    @Test public void currentOrFutureStateSkipsMigration() {
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(10, 10));
        assertFalse(PrismProvisioning.shouldRunOneTimePostProvisionMigration(11, 10));
    }

    @Test public void explicitProfileRepairAdvancesEveryOlderState() {
        assertTrue(PrismProvisioning.shouldAdvanceRevisionAfterExplicitRepair(false, 0, 10));
        assertTrue(PrismProvisioning.shouldAdvanceRevisionAfterExplicitRepair(false, 1, 10));
        assertTrue(PrismProvisioning.shouldAdvanceRevisionAfterExplicitRepair(false, 2, 10));
        assertTrue(PrismProvisioning.shouldAdvanceRevisionAfterExplicitRepair(false, 9, 10));
    }

    @Test public void explicitRepairNeverWritesParentState() {
        assertFalse(PrismProvisioning.shouldAdvanceRevisionAfterExplicitRepair(true, 0, 10));
        assertFalse(PrismProvisioning.shouldAdvanceRevisionAfterExplicitRepair(true, 9, 10));
    }

    @Test public void explicitRepairDoesNotRewriteCurrentOrFutureState() {
        assertFalse(PrismProvisioning.shouldAdvanceRevisionAfterExplicitRepair(false, 10, 10));
        assertFalse(PrismProvisioning.shouldAdvanceRevisionAfterExplicitRepair(false, 11, 10));
    }

    @Test public void firstManagedCompletionRunsPostProvisioning() {
        assertTrue(PrismProvisioning.shouldRunProfilePostProvisioning(
                DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE, 0, 10));
        assertTrue(PrismProvisioning.shouldRunProfilePostProvisioning(
                DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE, 9, 10));
    }

    @Test public void duplicateManagedCompletionSkipsPostProvisioning() {
        assertFalse(PrismProvisioning.shouldRunProfilePostProvisioning(
                DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE, 10, 10));
        assertFalse(PrismProvisioning.shouldRunProfilePostProvisioning(
                DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE, 11, 10));
    }

    @Test public void manualRecoveryAlwaysRunsPostProvisioning() {
        assertTrue(PrismProvisioning.shouldRunProfilePostProvisioning(Intent.ACTION_USER_INITIALIZE, 10, 10));
        assertTrue(PrismProvisioning.shouldRunProfilePostProvisioning(null, 10, 10));
    }

    @Test public void manualExtrasRunForManualTypeAtAnyState() {
        assertTrue(PrismProvisioning.shouldRunManualProvisioningExtras(1, 0));
        assertTrue(PrismProvisioning.shouldRunManualProvisioningExtras(1, 10));
    }

    @Test public void manualExtrasRunForNeverCompletedProfilesRegardlessOfType() {
        assertTrue(PrismProvisioning.shouldRunManualProvisioningExtras(0, 0));
        assertTrue(PrismProvisioning.shouldRunManualProvisioningExtras(0, 2));
    }

    @Test public void manualExtrasSkipCompletedSystemProvisionedProfiles() {
        assertFalse(PrismProvisioning.shouldRunManualProvisioningExtras(0, 3));
        assertFalse(PrismProvisioning.shouldRunManualProvisioningExtras(0, 10));
    }

    @Test public void convergeTrampolineIsRegisteredAndSelfProtecting() throws Exception {
        final String manifest = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/AndroidManifest.xml")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(manifest.contains("PrismProvisioning$ConvergeActivity"));
        // No launcher filter: the trampoline must never surface an icon.
        final int entry = manifest.indexOf("PrismProvisioning$ConvergeActivity");
        // LauncherApps.startMainActivity runtime-enforces CATEGORY_LAUNCHER, so the trampoline
        // must declare MAIN+LAUNCHER even though it is never meant to be seen (retired on
        // convergence by reprovisionManagedProfile / proceedProfileProvisioning).
        final int elementEnd = manifest.indexOf("</activity>", entry);
        final String element = manifest.substring(entry, elementEnd);
        assertTrue(element.contains("android:exported=\"true\""));
        assertTrue(element.contains("android.intent.action.MAIN"));
        assertTrue(element.contains("android.intent.category.LAUNCHER"));

        final String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/yzddmr6/prismspace/provisioning/PrismProvisioning.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        final int cls = source.indexOf("class ConvergeActivity");
        assertTrue(cls >= 0);
        // Self-protection: only an owned profile may be converged.
        assertTrue(source.indexOf("isParentProfile()", cls) > cls);
        assertTrue(source.indexOf("isProfileOwner()", cls) > cls);
        assertTrue(source.indexOf("ACTION_PROVISION_MANAGED_PROFILE", cls) > cls);
    }
}
