package com.gtnewhorizons.angelica.dx12;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendStartGuard;
import com.gtnewhorizons.angelica.dx12.device.Gpu;
import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import com.gtnewhorizons.angelica.dx12.shader.HlslCompiler;
import me.eigenraven.lwjgl3ify.api.DisplayEvents;
import me.eigenraven.lwjgl3ify.api.DisplayWindowContext;
import me.eigenraven.lwjgl3ify.client.MainThreadExec;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLProperties;
import org.lwjglx.Sys;
import org.lwjglx.input.Mouse;
import org.lwjglx.opengl.ContextAttribs;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.PixelFormat;

import java.io.File;

final class Dx12Runtime {
    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    private static Gpu gpu;
    private static Dx12GL gl;
    private static long hwnd;
    private static volatile Throwable initFailure;
    private static volatile boolean disarmed;
    private static boolean listenersRegistered;
    private static volatile boolean backendBound;
    private static volatile long attachedWindow;

    private Dx12Runtime() {}

    static Gpu gpu() {
        return gpu;
    }

    static Dx12GL gl() {
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
        HlslCompiler.configure(g.shaderModel);
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
            LOG.error("Direct3D 12 could not take the window", failure);
            return false;
        }
        if (attachedWindow == 0L) return false;
        Dx12Gate.setEngaged(true);
        Dx12LWJGLService.bind();
        backendBound = true;
        BackendManager.RENDER_BACKEND.onPostWindowCreate(attachedWindow);
        return true;
    }

    private static void registerListeners() {
        if (listenersRegistered) return;
        listenersRegistered = true;
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
        if (gpu == null) throw new IllegalStateException("Direct3D 12 device not created");
        hwnd = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(window), SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        if (hwnd == 0L) throw new IllegalStateException("SDL returned no HWND: " + SDLError.SDL_GetError());
        gl = new Dx12GL(gpu, null, new File(Launch.minecraftHome, "angelica"));
        gl.attachWindow(window, hwnd);
        LOG.info("Direct3D 12 backend owns the window: {} ({}, {})", gpu.deviceName, gpu.apiVersion, gpu.driverName);
    }
    static void checkCanFallBack() {
        if (backendBound || Dx12LWJGLService.isConstructed()) {
            throw new IllegalStateException("The Direct3D 12 backend is already bound; falling back to OpenGL would leave the game on a dead backend");
        }
    }

    static void fallBackToGL() {
        Dx12LWJGLService.unbind();
        disarmed = true;
        if (gl != null) {
            try {
                gl.shutdown();
            } catch (Throwable t) {
                LOG.warn("Direct3D 12 teardown during fallback failed", t);
            }
            gl = null;
        }
        hwnd = 0L;
        if (gpu != null) {
            gpu.destroy();
            gpu = null;
        }
        Mouse.setGrabbed(false);
        DisplayEvents.setCreateGLContext(true);
        if (Display.isCreated()) Display.destroy();
        Display.isCloseRequested();
        if (Dx12Gate.icons() != null) Display.setIcon(Dx12Gate.icons());
    }

    static void ensureDrawableInstalled() {
        Dx12DisplayBridge.ensureDrawableInstalled();
    }
}
