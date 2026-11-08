package com.gtnewhorizons.angelica.metal.util;

import com.gtnewhorizons.angelica.metal.device.ObjC;

public final class DebugNames {
    private final boolean enabled;

    public DebugNames(boolean enabled) {
        this.enabled = enabled;
    }

    public void name(long object, String name) {
        if (!enabled || object == 0L || name == null) return;
        ObjC.setLabel(object, name);
    }
}
