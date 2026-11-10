package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeBackendBase;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;

public final class MetalRenderBackend extends NativeBackendBase<MetalGL> {
    @Override protected MetalRuntime runtime() {
        return MetalRuntime.INSTANCE;
    }

    @Override protected int framesInFlight() {
        return SystemProperties.METAL_FRAMES_IN_FLIGHT;
    }

    @Override protected String apiName() {
        return "Metal";
    }
}
