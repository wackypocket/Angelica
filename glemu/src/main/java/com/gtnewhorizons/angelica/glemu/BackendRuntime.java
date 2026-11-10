package com.gtnewhorizons.angelica.glemu;

import com.gtnewhorizons.angelica.glsm.backend.BackendStartGuard;
import java.util.function.BooleanSupplier;
import me.eigenraven.lwjgl3ify.api.DisplayEvents;
import me.eigenraven.lwjgl3ify.api.DisplayWindowContext;
import me.eigenraven.lwjgl3ify.client.MainThreadExec;
import org.lwjglx.Sys;
import org.lwjglx.opengl.PixelFormat;

/** Brings up a backend that owns the window, and takes it down again when startup falls back to OpenGL. */
public abstract class BackendRuntime implements NativeGate.Hooks {
    private volatile Throwable initFailure;
    private volatile boolean disarmed;
    private boolean listenersRegistered;
    private NativeDisplayBridge displayBridge;

    public abstract NativeGate gate();

    protected abstract Class<? extends NativeLwjglService> serviceClass();

    protected abstract NativeDisplayBridge newDisplayBridge();

    /** Creates the device, on the main thread. */
    protected abstract boolean startDevice();

    protected void startFailed() {
    }

    protected void configureWindow(int properties) {
    }

    /** The window exists but {@code Display.create} has not returned; whatever this throws fails the window. */
    protected abstract void windowCreated(long window);

    /** {@code Display.create} returned without a failure. Returns whether the backend now owns the window. */
    protected abstract boolean windowOpened();

    /** Returns whether the backend still owns the window; false falls back to OpenGL. */
    protected abstract boolean windowFailed(Throwable failure);

    /** Frees everything the backend holds, ahead of the window closing. */
    protected abstract void releaseBackend();

    /** Created on first use: it reaches into lwjgl3ify's Display, which test rigs without a window do not have. */
    protected final synchronized NativeDisplayBridge displayBridge() {
        if (displayBridge == null) displayBridge = newDisplayBridge();
        return displayBridge;
    }

    protected final boolean serviceConstructed() {
        return NativeLwjglService.isConstructed(serviceClass());
    }

    protected final void bindService() {
        NativeLwjglService.bind(serviceClass());
    }

    @Override public final void ensureDrawableInstalled() {
        displayBridge().ensureDrawableInstalled();
    }

    @Override public final boolean probe() {
        Sys.initialize();
        BackendStartGuard.arm();
        boolean ok = false;
        try {
            ok = MainThreadExec.runOnMainThread((BooleanSupplier) this::startDevice);
            return ok;
        } finally {
            if (!ok) {
                startFailed();
                BackendStartGuard.disarm();
            }
        }
    }

    @Override public final boolean createDisplay(PixelFormat pixelFormat) {
        initFailure = null;
        disarmed = false;
        registerListeners();
        final Exception openFailure = BackendDisplay.open(pixelFormat);
        if (openFailure != null) initFailure = openFailure;
        final Throwable failure = initFailure;
        if (failure != null) {
            initFailure = null;
            return windowFailed(failure);
        }
        return windowOpened();
    }

    private void registerListeners() {
        if (listenersRegistered) return;
        listenersRegistered = true;
        DisplayEvents.addPreWindowCreateListener((DisplayWindowContext ctx) -> {
            if (!disarmed) configureWindow(ctx.props());
        });
        DisplayEvents.addPostWindowCreateListener((DisplayWindowContext ctx) -> {
            if (disarmed) return;
            try {
                windowCreated(ctx.window());
            } catch (Throwable t) {
                initFailure = t;
            }
        });
    }

    @Override public final void fallBackToGL() {
        NativeLwjglService.unbind(serviceClass());
        BackendStartGuard.disarm();
        disarmed = true;
        releaseBackend();
        BackendDisplay.closeForOpenGL(gate().icons());
    }
}
