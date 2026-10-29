package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.glemu.NativeDisplayBridge;
import com.gtnewhorizons.angelica.vulkan.device.VkDrawable;
import org.lwjglx.opengl.SharedDrawable;

public final class VulkanDisplayBridge {
    private VulkanDisplayBridge() {}

    private static final NativeDisplayBridge BRIDGE = new NativeDisplayBridge(VulkanGate::isActive, VkDrawable::new, VkDrawable.class);

    public static void ensureDrawableInstalled() {
        BRIDGE.ensureDrawableInstalled();
    }

    public static void present() {
        BRIDGE.present();
    }

    public static void releaseRenderThread() {
        BRIDGE.releaseRenderThread();
    }

    public static boolean isVkDrawable(SharedDrawable sd) {
        return BRIDGE.isDrawable(sd);
    }

    public static boolean isVkSharedDrawable(Object drawable) {
        return BRIDGE.isSharedDrawable(drawable);
    }
}
