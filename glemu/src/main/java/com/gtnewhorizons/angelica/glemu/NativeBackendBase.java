package com.gtnewhorizons.angelica.glemu;

import com.gtnewhorizons.angelica.glemu.compat.GlLegacy;
import com.gtnewhorizons.angelica.glemu.compat.GlPipelines;
import com.gtnewhorizons.angelica.glemu.compat.Lwjgl3GLCapabilitiesShim;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;
import com.gtnewhorizons.angelica.glsm.GLScreenCapture;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.ScreenCapture;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.lwjgl3.WindowedBounds;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import me.eigenraven.lwjgl3ify.api.DisplayEvents;
import me.eigenraven.lwjgl3ify.api.GLCapabilitiesOverride;
import org.lwjgl.sdl.SDLTimer;
import org.lwjgl.system.MemoryUtil;


public abstract class NativeBackendBase<G extends GlBase<?, ?, ?>> extends GpuRenderBackend<G> {
    @Override public final com.gtnewhorizons.angelica.glsm.backend.GlCallChecks checks() {
        return gl.validation();
    }


    private boolean shutdown;
    private volatile boolean reenterPending;

    /**
     * The lock LWJGL's function-pointer calls hold around the whole call. Null inside an unlocked call on the main thread (a debug
     * callback, say), because a thread sharing the context holds the lock while it waits for that call to finish.
     */
    public final ReentrantLock directCallLock() {
        return insideUnlockedCall() ? null : lock;
    }

    public final G glForDirectCalls() {
        return gl;
    }

    @Override public boolean supportsDebugOutput() {
        return true;
    }

    @Override public void programReleased() {
        GlPipelines.programReleased();
    }

    @Override public String getObjectLabel(int identifier, int name) {
        return gl.objectLabelOf(identifier, name);
    }

    @Override public boolean rasterPos(float x, float y, float z, float w) {
        GlLegacy.rasterPos(x, y, z, w);
        return true;
    }

    @Override public boolean pixelTransfer(int pname, float value) {
        if (gl.validation().pixelTransfer(pname)) GlLegacy.pixelTransfer(pname, value);
        return true;
    }

    @Override public boolean accum(int op, float value) {
        if (gl.validation().accum(op)) GlLegacy.accum(op, value);
        return true;
    }

    @Override public boolean polygonStipple(ByteBuffer pattern) {
        GlLegacy.polygonStipple(MemoryUtil.memAddress(pattern));
        return true;
    }

    @Override public ScreenCapture createScreenCapture(int width, int height) {
        return new GLScreenCapture(width, height);
    }

    @Override public void init() {
        WindowedBounds.showMenuBarInFullscreen();
        if (gl == null) gl = createGl();
        gl.setMainThread(GLStateManager.getMainThread());
        mainThread = GLStateManager.getMainThread();
        gl.paceSink = new GlBase.PaceSink() {
            @Override public void gpuWait(long nanos) { recordGpuWait(nanos); }
            @Override public void gate(long durationNanos, long endNanos) { recordGate(durationNanos, endNanos); }
        };
        Lwjgl3GLCapabilitiesShim.installFunctionProvider();
        Lwjgl3GLCapabilitiesShim.installOnCurrentThread(advertised());
        DisplayEvents.addPreSwapchainInvalidatingChangeListener(change -> onPreSwapchainInvalidatingChange(change));
        gl.log().info("{} backend initialized: {}", apiName(), gl.rendererString());
    }

    protected abstract NativeRuntime<?, G> runtime();

    private G createGl() {
        return runtime().gl();
    }

    protected abstract String apiName();

    private boolean gateEngaged() {
        final NativeGate gate = runtime().gate();
        return gate.isRequested() && gate.isEngaged();
    }

    private void destroyDevice() {
        runtime().shutdownDevice();
    }

    private boolean ownsDrawable(Object drawable) {
        return runtime().ownsDrawable(drawable);
    }

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
                if (gl.statsEnabled()) gl.log().info("{} stats: {}", apiName(), gl.stats());
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
        return apiName();
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

    @Override public boolean isIndirectRequired() {
        return true;
    }

    @Override public boolean supportsInPlaceMappedWrites() {
        return framesInFlight() < MAX_FRAMES_IN_FLIGHT;
    }

    protected abstract int framesInFlight();

    @Override public boolean isAnisotropicSupported() {
        return gl != null && gl.gpuCaps().samplerAnisotropy;
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
        gl.followWindowDisplay();
        final long blocked;
        lock.lock();
        try {
            blocked = gl.awaitPresent();
        } finally {
            lock.unlock();
        }
        if (blocked > 0L) {
            recordGate(blocked, System.nanoTime());
        }
    }

    /** Enough lead to fill a present queue up to 4 frames deep, so the driver is seen to block. */
    @Override public int probeLeadPeriods() {
        return 6;
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
        return BackendDisplay.refreshPeriodNanos();
    }

    @Override public void onFrameEnd() {
        if (gl == null || shutdown || Thread.currentThread() != gl.mainThread()) return;
        handleSwapBuffers();
    }

    private void enableSharing() {
        shared = true;
        // Once another thread wants the context, wait for the main thread to leave its unlocked present section.
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
        // Any thread may hold the emulated context; a thread's first use gets the GLSM state replayed into a fresh per-thread GL state.
        if (t != gl.mainThread()) {
            final boolean fresh = !gl.hasStateOnThread();
            if (fresh) Lwjgl3GLCapabilitiesShim.installOnCurrentThread(advertised());
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

    @Override public boolean isNative() { return true; }

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
