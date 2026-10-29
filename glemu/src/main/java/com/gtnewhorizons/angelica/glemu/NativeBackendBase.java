package com.gtnewhorizons.angelica.glemu;

import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glemu.GpuRenderBackend;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.glemu.compat.Lwjgl3GLCapabilitiesShim;
import me.eigenraven.lwjgl3ify.api.DisplayEvents;
import me.eigenraven.lwjgl3ify.api.GLCapabilitiesOverride;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDLTimer;
import org.lwjglx.opengl.Display;

import java.util.Set;

import static org.lwjgl.sdl.SDLVideo.*;

public abstract class NativeBackendBase<G extends GlBase<?, ?, ?>> extends GpuRenderBackend<G> {
    protected static final Logger LOG = LogManager.getLogger("Angelica/GPU");

    private boolean shutdown;
    private volatile boolean reenterPending;

    @Override public void init() {
        WindowedBounds.showMenuBarInFullscreen();
        if (gl == null) gl = createGl();
        gl.setMainThread(GLStateManager.getMainThread());
        gl.paceSink = new GlBase.PaceSink() {
            @Override public void gpuWait(long nanos) { recordGpuWait(nanos); }
            @Override public void gate(long durationNanos, long endNanos) { recordGate(durationNanos, endNanos); }
        };
        Lwjgl3GLCapabilitiesShim.installFunctionProvider();
        Lwjgl3GLCapabilitiesShim.installOnCurrentThread(advertised());
        DisplayEvents.addPreSwapchainInvalidatingChangeListener(change -> onPreSwapchainInvalidatingChange(change));
        LOG.info("{} backend initialized: {}", apiName(), gl.rendererString());
    }

    protected abstract G createGl();

    protected abstract String apiName();

    protected abstract boolean statsEnabled();

    protected abstract boolean gateEngaged();

    protected abstract void destroyDevice();

    protected abstract boolean ownsDrawable(Object drawable);

    private Set<String> advertised() {
        return GlBase.advertisedCapabilities(gl.gpuCaps());
    }

    @Override public void onPostWindowCreate(long window) {
        if (gl == null) gl = createGl();
        if (window != 0L) GLCapabilitiesOverride.set(advertised());
    }

    @Override public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        lock.lock();
        try {
            if (gl != null) {
                if (statsEnabled()) LOG.info("{} stats: {}", apiName(), gl.stats());
                gl.shutdown();
            }
            destroyDevice();
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isAvailable() {
        return gateEngaged();
    }

    @Override public String getName() {
        return "Angelica (" + apiName() + ")";
    }

    @Override public boolean hasContext() {
        return gl != null && !shutdown;
    }

    @Override public boolean hasContextOnThread() {
        return hasContext() && gl.hasStateOnThread();
    }

    @Override public boolean isCurrent() {
        return hasContextOnThread();
    }

    @Override public int getPriority() {
        return 200;
    }

    @Override public int getMinGLSLVersion() {
        return 460;
    }

    @Override public boolean supportsGeometryShaders() {
        return gl != null && gl.gpuCaps().geometryShader;
    }

    @Override public boolean isIndirectRequired() {
        return true;
    }

    @Override public boolean isAnisotropicSupported() {
        return gl != null && gl.gpuCaps().samplerAnisotropy;
    }

    @Override public boolean supportsProgramBinary() {
        return false;
    }

    @Override protected VSyncMode applyVSyncMode(VSyncMode preferred) {
        lock.lock();
        try {
            return gl.setVSync(preferred);
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean supportsVSyncMode(VSyncMode mode) {
        return gl != null && gl.supportsVSync(mode);
    }

    @Override protected boolean applyHonored() {
        lock.lock();
        try {
            return gl == null || gl.holdsToVblank();
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean hasSwapchainBackpressure() {
        return true;
    }

    @Override public void awaitPresent() {
        if (gl == null || !gl.hasPresentWait()) return;
        final long blocked;
        lock.lock();
        try {
            blocked = gl.awaitPresent();
        } finally {
            lock.unlock();
        }
        if (blocked > 0L) {
            final long end = System.nanoTime();
            recordGate(blocked, end);
        }
    }

    @Override public void awaitFrameSlot() {
        if (gl == null) return;
        final long blocked;
        lock.lock();
        try {
            blocked = gl.awaitFrameSlot();
        } finally {
            lock.unlock();
        }
        if (blocked > 0L) recordGpuWait(blocked);
    }

    @Override public boolean wantsDisplayUpdateGateTiming() {
        return false;
    }

    @Override public void parkNanos(long nanos) {
        SDLTimer.SDL_DelayNS(nanos);
    }

    @Override protected long queryRefreshPeriodNanos() {
        final long window = Display.getWindow();
        final int displayId = window == 0 ? SDL_GetPrimaryDisplay() : SDL_GetDisplayForWindow(window);
        if (displayId == 0) return 0L;
        final SDL_DisplayMode mode = SDL_GetCurrentDisplayMode(displayId);
        if (mode == null) return 0L;
        return RenderBackend.periodFromRational(mode.refresh_rate_numerator(), mode.refresh_rate_denominator(), mode.refresh_rate());
    }

    @Override public void onFrameEnd() {
        if (gl == null || shutdown || Thread.currentThread() != gl.mainThread()) return;
        handleSwapBuffers();
    }

    private void enableSharing() {
        shared = true;
        while (mainUnlocked) Thread.onSpinWait();
    }

    @Override public boolean handleSwapBuffers() {
        if (gl == null) return false;
        if (enterUnlocked()) {
            try {
                gl.present();
            } finally {
                exitUnlocked();
            }
            return true;
        }
        lock.lock();
        try {
            gl.present();
        } finally {
            lock.unlock();
        }
        return true;
    }

    @Override public boolean handleMakeCurrent(Object drawable) {
        if (!ownsDrawable(drawable)) return false;
        final Thread t = Thread.currentThread();
        if (t != gl.mainThread()) {
            Lwjgl3GLCapabilitiesShim.installOnCurrentThread(advertised());
            final boolean fresh = !gl.hasStateOnThread();
            lock.lock();
            try {
                enableSharing();
                gl.st();
            } finally {
                lock.unlock();
            }
            if (fresh) GLSMAttribStack.replayStateToBackend();
        } else {
            if (shared) {
                lock.lock();
                try {
                    if (!gl.otherThreadsHaveState()) shared = false;
                } finally {
                    lock.unlock();
                }
            }
            if (reenterPending) {
                reenterPending = false;
                GLSMAttribStack.replayStateToBackend();
            }
        }
        return true;
    }

    @Override public boolean handleReleaseContext(Object drawable) {
        if (!ownsDrawable(drawable)) return false;
        final Thread t = Thread.currentThread();
        if (t == gl.mainThread()) {
            shared = true;
            reenterPending = true;
            return true;
        }
        onRenderThreadReleased(t);
        return true;
    }

    @Override public void onRenderThreadReleased(Thread thread) {
        if (gl == null || shutdown || thread == gl.mainThread()) return;
        lock.lock();
        try {
            gl.releaseThreadState();
        } finally {
            lock.unlock();
        }
        Lwjgl3GLCapabilitiesShim.clearOnCurrentThread();
    }

    private final WindowedBounds windowedBounds = new WindowedBounds();

    @Override public void saveWindowedBounds() { windowedBounds.save(); }

    @Override public void restoreWindowedBounds() { windowedBounds.restore(); }

    @Override public boolean tracksWindowFullscreen() { return true; }

    @Override public boolean isWindowFullscreen() { return WindowedBounds.isFullscreen(); }

    @Override public void onPreSwapchainInvalidatingChange(Object change) {
        super.onPreSwapchainInvalidatingChange(change);
        if (gl == null || shutdown || !gl.hasWindow()) return;
        lock.lock();
        try {
            gl.quiesceForSwapchainChange();
        } finally {
            lock.unlock();
        }
    }

    @Override public void invalidateStateMirror() {
    }
}
