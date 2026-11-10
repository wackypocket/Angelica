package com.gtnewhorizons.angelica.glemu;

import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** A backend whose device and GL object this runtime holds, which is every native one. */
public abstract class NativeRuntime<D, G extends GlBase<?, ?, ?>> extends BackendRuntime {
    protected final Logger log;
    private final String apiName;

    private D device;
    private D candidate;
    private G gl;
    private volatile boolean backendBound;
    private volatile long attachedWindow;

    protected NativeRuntime(String apiName, String logLabel) {
        this.apiName = apiName;
        this.log = LogManager.getLogger("Angelica/" + logLabel);
    }

    protected abstract D newDevice();

    protected abstract boolean createDevice(D device);

    protected abstract void destroyDevice(D device);

    protected abstract G attach(long window, D device);

    /** A device to try after this one failed to create, such as the next GPU, or null when none is left. */
    protected D nextDevice(D failed) {
        return null;
    }

    /** Puts another device in place of the current one, which is destroyed; for {@link #attach} once the window shows the first one cannot use it. */
    protected final void replaceDevice(D next) {
        final D old = device;
        device = next;
        if (old != null && old != next) destroyDevice(old);
    }

    /** Frees what {@link #attach} created beyond the GL object. */
    protected void detach() {
    }

    /** Whether this drawable is the backend's own, or a shared one wrapping it. */
    final boolean ownsDrawable(Object drawable) {
        return drawable != null && displayBridge().owns(drawable);
    }

    public final void shutdownDevice() {
        detach();
        if (device != null) destroyDevice(device);
        device = null;
    }

    public final D device() {
        return device;
    }

    public final G gl() {
        return gl;
    }

    /** Installs a device and GL object made without a window, for headless use. */
    public final void adopt(D device, G gl) {
        this.device = device;
        this.gl = gl;
    }

    @Override protected final boolean startDevice() {
        candidate = newDevice();
        while (!createDevice(candidate)) {
            final D next = nextDevice(candidate);
            if (next == null) return false;
            destroyDevice(candidate);
            log.warn("Trying the next {} device", apiName);
            candidate = next;
        }
        device = candidate;
        candidate = null;
        return true;
    }

    @Override protected final void startFailed() {
        if (candidate != null) destroyDevice(candidate);
        candidate = null;
    }

    @Override protected final void windowCreated(long window) {
        if (device == null) throw new IllegalStateException(apiName + " device not created");
        gl = attach(window, device);
        attachedWindow = window;
    }

    @Override protected final boolean windowOpened() {
        if (attachedWindow == 0L) return false;
        gate().setEngaged(true);
        bindService();
        backendBound = true;
        BackendManager.RENDER_BACKEND.onPostWindowCreate(attachedWindow);
        return true;
    }

    @Override protected final boolean windowFailed(Throwable failure) {
        log.error("{} could not take the window", apiName, failure);
        return false;
    }

    @Override public final void checkCanFallBack() {
        if (backendBound || serviceConstructed()) {
            throw new IllegalStateException("The " + apiName + " backend is already bound; falling back to OpenGL would leave the game on a dead backend");
        }
    }

    @Override protected final void releaseBackend() {
        attachedWindow = 0L;
        if (gl != null) {
            try {
                gl.shutdown();
            } catch (Throwable t) {
                log.warn("{} teardown during fallback failed", apiName, t);
            }
            gl = null;
        }
        shutdownDevice();
    }
}
