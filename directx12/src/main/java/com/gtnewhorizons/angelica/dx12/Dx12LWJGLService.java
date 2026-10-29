package com.gtnewhorizons.angelica.dx12;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeLwjglService;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public final class Dx12LWJGLService extends NativeLwjglService {

    private static volatile boolean constructed;

    public static Dx12LWJGLService create() {
        if (!Dx12Gate.isEngaged()) {
            throw new IllegalStateException("The Direct3D 12 backend is not engaged; " + SystemProperties.KEY_CELERITAS_LWJGL_SERVICE + " must not name " + Dx12LWJGLService.class.getName());
        }
        constructed = true;
        return new Dx12LWJGLService();
    }

    public static boolean isConstructed() { return constructed; }

    static void bind() {
        System.setProperty(SystemProperties.KEY_CELERITAS_LWJGL_SERVICE, Dx12LWJGLService.class.getName());
    }

    static void unbind() {
        final String key = SystemProperties.KEY_CELERITAS_LWJGL_SERVICE;
        if (Dx12LWJGLService.class.getName().equals(System.getProperty(key))) {
            System.clearProperty(key);
        }
    }
}
