package com.yzddmr6.prismspace.provisioning;

import static android.app.admin.DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT;
import static android.app.admin.DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.yzddmr6.prismspace.engine.CrossProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TransferOpenForwardingTest {

    @Test public void registersExactlyOneFilterPerDirection() {
        final List<Integer> flags = new ArrayList<>();
        TransferOpenForwarding.register((filter, directionFlag) -> flags.add(directionFlag));
        // AOSP: FLAG_MANAGED_CAN_ACCESS_PARENT = parent -> managed; FLAG_PARENT_CAN_ACCESS_MANAGED = managed -> parent.
        assertEquals(2, flags.size());
        assertEquals(FLAG_MANAGED_CAN_ACCESS_PARENT, (int) flags.get(0));
        assertEquals(FLAG_PARENT_CAN_ACCESS_MANAGED, (int) flags.get(1));
    }

    @Test public void categoriesAreIndexAlignedWithDirections() {
        // Main -> dual carries MANAGED_PROFILE; dual -> main carries PARENT_PROFILE.
        assertArrayEquals(new String[] { CrossProfile.CATEGORY_MANAGED_PROFILE, CrossProfile.CATEGORY_PARENT_PROFILE },
                TransferOpenForwarding.CATEGORIES);
        assertArrayEquals(new int[] { FLAG_MANAGED_CAN_ACCESS_PARENT, FLAG_PARENT_CAN_ACCESS_MANAGED },
                TransferOpenForwarding.DIRECTIONS);
        assertEquals("com.yzddmr6.prismspace.action.TRANSFER_OPEN", CrossProfile.ACTION_TRANSFER_OPEN);
    }

    @Test public void dualSpacePostProvisioningRegistersFiltersAndAliases() throws Exception {
        final String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/yzddmr6/prismspace/provisioning/PrismProvisioning.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        final int start = source.indexOf("private static void startProfileOwnerPostProvisioningForNonOwnerProfile");
        assertTrue(start >= 0);
        final int end = source.indexOf("\n\t}", start);
        final String body = source.substring(start, end);
        assertTrue(body.contains("TransferOpenForwarding.register("));
        assertTrue(body.contains("TransferOpenForwarding.setReceiverAliases("));
    }
}
