package com.gtnewhorizons.angelica.d3d12;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeLwjglService;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public final class D3d12LWJGLService extends NativeLwjglService {

    private static volatile boolean constructed;

    public static D3d12LWJGLService create() {
        if (!D3d12Gate.isEngaged()) {
            throw new IllegalStateException("The Direct3D 12 backend is not engaged; " + SystemProperties.KEY_CELERITAS_LWJGL_SERVICE + " must not name " + D3d12LWJGLService.class.getName());
        }
        constructed = true;
        return new D3d12LWJGLService();
    }

    public static boolean isConstructed() { return constructed; }

    static void bind() {
        System.setProperty(SystemProperties.KEY_CELERITAS_LWJGL_SERVICE, D3d12LWJGLService.class.getName());
    }

    static void unbind() {
        final String key = SystemProperties.KEY_CELERITAS_LWJGL_SERVICE;
        if (D3d12LWJGLService.class.getName().equals(System.getProperty(key))) {
            System.clearProperty(key);
        }
    }
}
