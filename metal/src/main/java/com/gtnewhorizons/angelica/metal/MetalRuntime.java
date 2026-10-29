package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendStartGuard;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import me.eigenraven.lwjgl3ify.api.DisplayEvents;
import me.eigenraven.lwjgl3ify.api.DisplayWindowContext;
import me.eigenraven.lwjgl3ify.client.MainThreadExec;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.sdl.SDLProperties;
import org.lwjglx.Sys;
import org.lwjglx.input.Mouse;
import org.lwjglx.opengl.ContextAttribs;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.PixelFormat;

import java.io.File;

import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_METAL_BOOLEAN;

final class MetalRuntime {
    private static final Logger LOG = LogManager.getLogger("Angelica/Metal");

    private static Gpu gpu;
    private static MetalGL gl;
    private static long view;
    private static volatile Throwable initFailure;
    private static volatile boolean disarmed;
    private static boolean listenersRegistered;
    private static volatile boolean backendBound;
    private static volatile long attachedWindow;

    private MetalRuntime() {}

    static Gpu gpu() {
        return gpu;
    }

    static MetalGL gl() {
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
        attachedWindow = 0L;
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
            LOG.error("Metal could not take the window", failure);
            return false;
        }
        if (attachedWindow == 0L) return false;
        MetalGate.setEngaged(true);
        MetalLWJGLService.bind();
        backendBound = true;
        BackendManager.RENDER_BACKEND.onPostWindowCreate(attachedWindow);
        return true;
    }

    private static void registerListeners() {
        if (listenersRegistered) return;
        listenersRegistered = true;
        DisplayEvents.addPreWindowCreateListener((DisplayWindowContext ctx) -> {
            if (disarmed) return;
            SDLProperties.SDL_SetBooleanProperty(ctx.props(), SDL_PROP_WINDOW_CREATE_METAL_BOOLEAN, true);
        });
        DisplayEvents.addPostWindowCreateListener((DisplayWindowContext ctx) -> {
            if (disarmed) return;
            try {
                attach(ctx.window());
                attachedWindow = ctx.window();
            } catch (Throwable t) {
                initFailure = t;
            }
        });
    }

    private static void attach(long window) {
        if (gpu == null) throw new IllegalStateException("Metal device not created");
        view = SDLMetal.SDL_Metal_CreateView(window);
        if (view == 0L) throw new IllegalStateException("SDL_Metal_CreateView failed: " + SDLError.SDL_GetError());
        final long layer = SDLMetal.SDL_Metal_GetLayer(view);
        if (layer == 0L) throw new IllegalStateException("SDL_Metal_GetLayer returned no CAMetalLayer: " + SDLError.SDL_GetError());
        gl = new MetalGL(gpu, null, new File(Launch.minecraftHome, "angelica"));
        gl.attachWindow(window, layer);
        LOG.info("Metal backend owns the window: {} (Metal {}, {})", gpu.deviceName, gpu.apiVersion, gpu.driverName);
    }

    static void checkCanFallBack() {
        if (backendBound || MetalLWJGLService.isConstructed()) {
            throw new IllegalStateException("The Metal backend is already bound; falling back to OpenGL would leave the game on a dead backend");
        }
    }

    static void fallBackToGL() {
        MetalLWJGLService.unbind();
        disarmed = true;
        if (gl != null) {
            try {
                gl.shutdown();
            } catch (Throwable t) {
                LOG.warn("Metal teardown during fallback failed", t);
            }
            gl = null;
        }
        if (view != 0L) {
            SDLMetal.SDL_Metal_DestroyView(view);
            view = 0L;
        }
        if (gpu != null) {
            gpu.destroy();
            gpu = null;
        }
        Mouse.setGrabbed(false);
        DisplayEvents.setCreateGLContext(true);
        if (Display.isCreated()) Display.destroy();
        Display.isCloseRequested();
        if (MetalGate.icons() != null) Display.setIcon(MetalGate.icons());
    }

    static void ensureDrawableInstalled() {
        MetalDisplayBridge.ensureDrawableInstalled();
    }
}
