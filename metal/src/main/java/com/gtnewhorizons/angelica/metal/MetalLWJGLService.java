package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.glemu.NativeLwjglService;

public final class MetalLWJGLService extends NativeLwjglService {
    public static MetalLWJGLService create() {
        return create(MetalGate.GATE, MetalLWJGLService.class, MetalLWJGLService::new);
    }
}
