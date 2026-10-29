package com.gtnewhorizons.angelica.dx12;

import com.gtnewhorizons.angelica.glemu.NativeDisplayBridge;
import com.gtnewhorizons.angelica.dx12.device.Dx12Drawable;
import org.lwjglx.opengl.SharedDrawable;

public final class Dx12DisplayBridge {
    private Dx12DisplayBridge() {}

    private static final NativeDisplayBridge BRIDGE = new NativeDisplayBridge(Dx12Gate::isActive, Dx12Drawable::new, Dx12Drawable.class);

    public static void ensureDrawableInstalled() {
        BRIDGE.ensureDrawableInstalled();
    }

    public static void present() {
        BRIDGE.present();
    }

    public static void releaseRenderThread() {
        BRIDGE.releaseRenderThread();
    }

    public static boolean isDx12Drawable(SharedDrawable sd) {
        return BRIDGE.isDrawable(sd);
    }

    public static boolean isDx12SharedDrawable(Object drawable) {
        return BRIDGE.isSharedDrawable(drawable);
    }
}
