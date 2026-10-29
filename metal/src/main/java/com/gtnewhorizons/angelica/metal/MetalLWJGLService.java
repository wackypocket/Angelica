package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeLwjglService;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public final class MetalLWJGLService extends NativeLwjglService {

    private static volatile boolean constructed;

    public static MetalLWJGLService create() {
        if (!MetalGate.isEngaged()) {
            throw new IllegalStateException("The Metal backend is not engaged; " + SystemProperties.KEY_CELERITAS_LWJGL_SERVICE + " must not name " + MetalLWJGLService.class.getName());
        }
        constructed = true;
        return new MetalLWJGLService();
    }

    public static boolean isConstructed() { return constructed; }

    static void bind() {
        System.setProperty(SystemProperties.KEY_CELERITAS_LWJGL_SERVICE, MetalLWJGLService.class.getName());
    }

    static void unbind() {
        final String key = SystemProperties.KEY_CELERITAS_LWJGL_SERVICE;
        if (MetalLWJGLService.class.getName().equals(System.getProperty(key))) {
            System.clearProperty(key);
        }
    }
}
