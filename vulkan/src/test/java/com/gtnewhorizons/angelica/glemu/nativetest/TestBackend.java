package com.gtnewhorizons.angelica.glemu.nativetest;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.glemu.NativeLwjglService;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.vulkan.VulkanGate;
import com.gtnewhorizons.angelica.vulkan.VulkanLWJGLService;
import com.gtnewhorizons.angelica.vulkan.VulkanRenderBackend;
import com.gtnewhorizons.angelica.vulkan.VulkanRuntime;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import org.lwjgl.sdl.SDLInit;

/** What the shared tests in glemu/src/nativeTest need to know about the backend they are compiled for. */
public final class TestBackend {
    public static final String NAME = "Vulkan";
    public static final String DESCRIPTOR_WRITE_COUNTER = "setWrites";

    private TestBackend() {}

    public static boolean initPlatform() {
        return SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO);
    }

    public static GpuCaps newDevice() {
        final Gpu gpu = new Gpu();
        return gpu.create() ? gpu : null;
    }

    public static GlBase<?, ?, ?> newGl(GpuCaps gpu) {
        return new VulkanGL((Gpu) gpu, Thread.currentThread(), null);
    }

    public static void destroy(GpuCaps gpu) {
        ((Gpu) gpu).destroy();
    }

    public static void adopt(GpuCaps gpu, GlBase<?, ?, ?> gl) {
        VulkanRuntime.INSTANCE.adopt((Gpu) gpu, (VulkanGL) gl);
        VulkanGate.GATE.setEngaged(true);
    }

    public static boolean isSelected(RenderBackend backend) {
        return backend instanceof VulkanRenderBackend;
    }

    public static GlBase<?, ?, ?> activeGl() {
        return ((VulkanRenderBackend) BackendManager.RENDER_BACKEND).vulkan();
    }

    public static boolean discardsDepthOnPresent(GpuCaps gpu) {
        return ((Gpu) gpu).isMoltenVkOrApple() && !SystemProperties.DISABLE_VULKAN_DEFER_PASSES && !(SystemProperties.VULKAN_DEBUG && ((Gpu) gpu).debugUtils);
    }

    public static boolean canRenderToA3DSlice(GpuCaps gpu) {
        return ((Gpu) gpu).imageView2DOn3DImage;
    }

    public static NativeGate gate() {
        return VulkanGate.GATE;
    }

    public static Class<? extends NativeLwjglService> serviceClass() {
        return VulkanLWJGLService.class;
    }

    public static NativeLwjglService createService() {
        return VulkanLWJGLService.create();
    }

    public static boolean serviceConstructed() {
        return NativeLwjglService.isConstructed(VulkanLWJGLService.class);
    }
}
