package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/** A machine without a device skips these tests; CI declares it has one, so there a missing device fails them. */
public final class DeviceRequirement {
    private DeviceRequirement() {}

    public static void require(boolean available, String what) {
        if (Boolean.getBoolean("angelica.requireDevice")) Assertions.assertTrue(available, what);
        else Assumptions.assumeTrue(available, what);
    }
}
