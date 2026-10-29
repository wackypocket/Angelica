package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeLwjglService;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public final class VulkanLWJGLService extends NativeLwjglService {

    private static volatile boolean constructed;

    public static VulkanLWJGLService create() {
        if (!VulkanGate.isEngaged()) {
            throw new IllegalStateException("The Vulkan backend is not engaged; " + SystemProperties.KEY_CELERITAS_LWJGL_SERVICE + " must not name " + VulkanLWJGLService.class.getName());
        }
        constructed = true;
        return new VulkanLWJGLService();
    }

    public static boolean isConstructed() { return constructed; }

    static void bind() {
        System.setProperty(SystemProperties.KEY_CELERITAS_LWJGL_SERVICE, VulkanLWJGLService.class.getName());
    }

    static void unbind() {
        final String key = SystemProperties.KEY_CELERITAS_LWJGL_SERVICE;
        if (VulkanLWJGLService.class.getName().equals(System.getProperty(key))) {
            System.clearProperty(key);
        }
    }
}
