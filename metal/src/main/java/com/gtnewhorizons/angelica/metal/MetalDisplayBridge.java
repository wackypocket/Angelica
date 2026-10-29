package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.glemu.NativeDisplayBridge;
import com.gtnewhorizons.angelica.metal.device.MtlDrawable;
import org.lwjglx.opengl.SharedDrawable;

public final class MetalDisplayBridge {
    private MetalDisplayBridge() {}

    private static final NativeDisplayBridge BRIDGE = new NativeDisplayBridge(MetalGate::isActive, MtlDrawable::new, MtlDrawable.class);

    public static void ensureDrawableInstalled() {
        BRIDGE.ensureDrawableInstalled();
    }

    public static void present() {
        BRIDGE.present();
    }

    public static void releaseRenderThread() {
        BRIDGE.releaseRenderThread();
    }

    public static boolean isMtlDrawable(SharedDrawable sd) {
        return BRIDGE.isDrawable(sd);
    }

    public static boolean isMtlSharedDrawable(Object drawable) {
        return BRIDGE.isSharedDrawable(drawable);
    }
}
