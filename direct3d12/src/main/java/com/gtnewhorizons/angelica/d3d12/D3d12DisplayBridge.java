package com.gtnewhorizons.angelica.d3d12;

import com.gtnewhorizons.angelica.glemu.NativeDisplayBridge;
import com.gtnewhorizons.angelica.d3d12.device.D3d12Drawable;

public final class D3d12DisplayBridge {
    private D3d12DisplayBridge() {}

    private static final NativeDisplayBridge BRIDGE = new NativeDisplayBridge(D3d12Gate::isActive, D3d12Drawable::new, D3d12Drawable.class);

    public static void ensureDrawableInstalled() {
        BRIDGE.ensureDrawableInstalled();
    }

    public static boolean isD3d12SharedDrawable(Object drawable) {
        return BRIDGE.isSharedDrawable(drawable);
    }
}
