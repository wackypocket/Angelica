package com.gtnewhorizons.angelica.glemu.nativetest;

import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.glemu.NativeLwjglService;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.metal.MetalGate;
import com.gtnewhorizons.angelica.metal.MetalLWJGLService;
import com.gtnewhorizons.angelica.metal.MetalRenderBackend;
import com.gtnewhorizons.angelica.metal.MetalRuntime;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.lwjgl.sdl.SDLInit;

/** What the shared tests in glemu/src/nativeTest need to know about the backend they are compiled for. */
public final class TestBackend {
    public static final String NAME = "Metal";
    public static final String DESCRIPTOR_WRITE_COUNTER = "objectCreations";

    private TestBackend() {}

    public static boolean initPlatform() {
        return SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO);
    }

    public static GpuCaps newDevice() {
        final Gpu gpu = new Gpu();
        return gpu.create() ? gpu : null;
    }

    public static GlBase<?, ?, ?> newGl(GpuCaps gpu) {
        return new MetalGL((Gpu) gpu, Thread.currentThread());
    }

    public static void destroy(GpuCaps gpu) {
        ((Gpu) gpu).destroy();
    }

    public static void adopt(GpuCaps gpu, GlBase<?, ?, ?> gl) {
        MetalRuntime.INSTANCE.adopt((Gpu) gpu, (MetalGL) gl);
        MetalGate.GATE.setEngaged(true);
    }

    public static boolean isSelected(RenderBackend backend) {
        return backend instanceof MetalRenderBackend;
    }

    public static GlBase<?, ?, ?> activeGl() {
        return ((MetalRenderBackend) BackendManager.RENDER_BACKEND).glForDirectCalls();
    }

    public static boolean discardsDepthOnPresent(GpuCaps gpu) {
        return true;
    }

    public static boolean canRenderToA3DSlice(GpuCaps gpu) {
        return true;
    }

    public static NativeGate gate() {
        return MetalGate.GATE;
    }

    public static Class<? extends NativeLwjglService> serviceClass() {
        return MetalLWJGLService.class;
    }

    public static NativeLwjglService createService() {
        return MetalLWJGLService.create();
    }

    public static boolean serviceConstructed() {
        return NativeLwjglService.isConstructed(MetalLWJGLService.class);
    }
}
