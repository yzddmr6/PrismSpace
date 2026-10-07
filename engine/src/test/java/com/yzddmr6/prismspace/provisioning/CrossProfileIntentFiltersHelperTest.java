package com.yzddmr6.prismspace.provisioning;

import org.junit.Test;

import static android.app.admin.DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT;
import static android.app.admin.DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

import android.content.IntentFilter;
import java.util.ArrayList;
import java.util.List;

public class CrossProfileIntentFiltersHelperTest {

    @Test public void routeDirectionMapsBothWaysWithoutCollapsing() {
        // AOSP: FLAG_MANAGED_CAN_ACCESS_PARENT = parent -> managed; FLAG_PARENT_CAN_ACCESS_MANAGED = managed -> parent.
        assertEquals(FLAG_MANAGED_CAN_ACCESS_PARENT,
                CrossProfileIntentFiltersHelper.directionFlagFor(0, 10, 0, 10));
        assertEquals(FLAG_PARENT_CAN_ACCESS_MANAGED,
                CrossProfileIntentFiltersHelper.directionFlagFor(10, 0, 0, 10));
    }

    @Test public void dpmRegistrationUsesAospDirectionForEveryFilter() {
        final List<Integer> flags = new ArrayList<>();
        CrossProfileIntentFiltersHelper.setFilters((filter, directionFlag) -> flags.add(directionFlag));

        assertEquals(17, flags.size());
        for (int i = 0; i < flags.size(); i++) {
            // Index 9 is SEND / SEND_MULTIPLE, the only parent -> managed filter; every other AOSP filter
            // (telephony, SMS/MMS, network settings, HOME, GET_CONTENT, OPEN_DOCUMENT, PICK, speech,
            // capture, alarms) is managed -> parent.
            final int expected = i == 9 ? FLAG_MANAGED_CAN_ACCESS_PARENT : FLAG_PARENT_CAN_ACCESS_MANAGED;
            assertEquals("filter " + i + " direction flag", expected, (int) flags.get(i));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void siblingOrSelfRouteIsRejected() {
        CrossProfileIntentFiltersHelper.directionFlagFor(10, 10, 0, 10);
    }

    @Test public void skipCurrentProfileMatchesAospResolverFlag() {
        assertEquals(0x00000002, CrossProfileIntentFiltersHelper.PackageManager.SKIP_CURRENT_PROFILE);
    }

    @Test public void everyAospFilterKeepsItsDeclaredDirectionAndSkipSemantics() {
        final List<int[]> routes = new ArrayList<>();
        CrossProfileIntentFiltersHelper.setFilters(new CrossProfileIntentFiltersHelper.PackageManager() {
            @Override public void addCrossProfileIntentFilter(
                    final IntentFilter filter, final int source, final int target, final int flags) {
                routes.add(new int[] { source, target, flags });
            }
        }, 0, 10);

        assertEquals(17, routes.size());
        for (int i = 0; i < routes.size(); i++) {
            final int[] route = routes.get(i);
            if (i == 9) assertArrayEquals(new int[] { 0, 10, 0 }, route); // SEND / SEND_MULTIPLE
            else assertEquals("filter " + i + " source", 10, route[0]);
        }
        final int skip = CrossProfileIntentFiltersHelper.PackageManager.SKIP_CURRENT_PROFILE;
        for (final int index : new int[] { 0, 1, 2, 5, 6, 7, 8 }) assertEquals(skip, routes.get(index)[2]);
        for (final int index : new int[] { 3, 4, 9, 10, 11, 12, 13, 14, 15, 16 }) assertEquals(0, routes.get(index)[2]);
    }
}
