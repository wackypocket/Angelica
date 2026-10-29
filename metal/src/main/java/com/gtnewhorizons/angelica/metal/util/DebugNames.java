package com.gtnewhorizons.angelica.metal.util;

import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class DebugNames {
    private static final long SET_LABEL = sel("setLabel:");

    private final boolean enabled;

    public DebugNames(MetalGL gl, boolean enabled) {
        this.enabled = enabled;
    }

    public void name(long object, String name) {
        if (!enabled || object == 0L || name == null) return;
        final long s = ObjC.nsString(name);
        call(object, SET_LABEL, s);
        ObjC.release(s);
    }

    public boolean enabled() {
        return enabled;
    }

    public void push(String label) {
    }

    public void pop() {
    }

    public void closeAll() {
    }
}
