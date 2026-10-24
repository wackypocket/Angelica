package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendStartGuard;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import me.eigenraven.lwjgl3ify.api.DisplayEvents;
import me.eigenraven.lwjgl3ify.api.DisplayWindowContext;
import me.eigenraven.lwjgl3ify.client.MainThreadExec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjglx.Sys;
import org.lwjglx.input.Mouse;
import org.lwjglx.opengl.ContextAttribs;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.PixelFormat;

import java.io.File;
import java.nio.LongBuffer;

import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN;

final class VulkanRuntime {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private static Gpu gpu;
    private static VulkanGL gl;
    private static volatile Throwable initFailure;
    private static volatile boolean disarmed;
    private static boolean listenersRegistered;

    private VulkanRuntime() {}

    static Gpu gpu() {
        return gpu;
    }

    static VulkanGL gl() {
        return gl;
    }

    static boolean probe() {
        Sys.initialize();
        BackendStartGuard.arm();
        final Gpu g = new Gpu();
        final boolean ok = MainThreadExec.runOnMainThread(g::create);
        if (!ok) {
            g.destroy();
            BackendStartGuard.disarm();
            return false;
        }
        gpu = g;
        return true;
    }

    static boolean createDisplay(Object pixelFormat, Object attribs) {
        initFailure = null;
        disarmed = false;
        registerListeners();
        DisplayEvents.setCreateGLContext(false);
        try {
            Display.create((PixelFormat) pixelFormat, (ContextAttribs) attribs, 0L);
        } catch (Exception e) {
            initFailure = e;
        }
        final Throwable failure = initFailure;
        if (failure != null) {
            initFailure = null;
            LOG.error("Vulkan could not take the window", failure);
            return false;
        }
        return VulkanGate.isEngaged();
    }

    private static void registerListeners() {
        if (listenersRegistered) return;
        listenersRegistered = true;
        DisplayEvents.addPreWindowCreateListener((DisplayWindowContext ctx) -> {
            if (disarmed) return;
            SDLProperties.SDL_SetBooleanProperty(ctx.props(), SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN, true);
        });
        DisplayEvents.addPostWindowCreateListener((DisplayWindowContext ctx) -> {
            if (disarmed) return;
            try {
                attach(ctx.window());
                VulkanGate.setEngaged(true);
                VulkanLWJGLService.bind();
                BackendManager.RENDER_BACKEND.onPostWindowCreate(ctx.window());
            } catch (Throwable t) {
                initFailure = t;
            }
        });
    }

    private static void attach(long window) {
        if (gpu == null) throw new IllegalStateException("Vulkan device not created");
        final long surface;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final LongBuffer p = stack.mallocLong(1);
            if (!SDLVulkan.nSDL_Vulkan_CreateSurface(window, gpu.instance.address(), 0L, org.lwjgl.system.MemoryUtil.memAddress(p))) {
                throw new IllegalStateException("SDL_Vulkan_CreateSurface failed: " + SDLError.SDL_GetError());
            }
            surface = p.get(0);
        }
        if (!gpu.supportsPresent(surface)) {
            org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR(gpu.instance, surface, null);
            throw new IllegalStateException(gpu.deviceName + " cannot present to this window");
        }
        final File cacheDir = new File("angelica", "cache");
        gl = new VulkanGL(gpu, null, cacheDir);
        gl.attachWindow(window, surface);
        LOG.info("Vulkan backend owns the window: {} ({}), swapchain format {}", gpu.deviceName, gpu.driverName,
            com.gtnewhorizons.angelica.vulkan.device.Swapchain.formatName(gl.swapchainFormat()));
    }

    static void fallBackToGL() {
        VulkanLWJGLService.unbind();
        disarmed = true;
        if (gl != null) {
            try {
                gl.shutdown();
            } catch (Throwable t) {
                LOG.warn("Vulkan teardown during fallback failed", t);
            }
            gl = null;
        }
        if (gpu != null) {
            gpu.destroy();
            gpu = null;
        }
        Mouse.setGrabbed(false);
        DisplayEvents.setCreateGLContext(true);
        if (Display.isCreated()) Display.destroy();
        Display.isCloseRequested();
        if (VulkanGate.icons() != null) Display.setIcon(VulkanGate.icons());
    }

    static void ensureDrawableInstalled() {
        VulkanDisplayBridge.ensureDrawableInstalled();
    }
}
