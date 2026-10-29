package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeBackendBase;
import com.gtnewhorizons.angelica.metal.device.MtlDrawable;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;

public abstract class MetalBackendBase extends NativeBackendBase<MetalGL> {
    @Override protected MetalGL createGl() {
        return MetalRuntime.gl();
    }

    @Override public boolean isMetal() {
        return true;
    }

    @Override public boolean supportsInPlaceMappedWrites() {
        return SystemProperties.METAL_FRAMES_IN_FLIGHT < MAX_FRAMES_IN_FLIGHT;
    }

    @Override protected String apiName() {
        return "Metal";
    }

    @Override protected boolean statsEnabled() {
        return SystemProperties.METAL_STATS;
    }

    @Override protected boolean gateEngaged() {
        return MetalGate.isRequested() && MetalGate.isEngaged();
    }

    @Override protected void destroyDevice() {
        if (MetalRuntime.gpu() != null) MetalRuntime.gpu().destroy();
    }

    @Override protected boolean ownsDrawable(Object drawable) {
        if (drawable == null) return false;
        if (drawable instanceof MtlDrawable) return true;
        return MetalDisplayBridge.isMtlSharedDrawable(drawable);
    }

    public MetalGL metal() {
        return gl;
    }
}
