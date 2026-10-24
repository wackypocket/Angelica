package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.vulkan.compat.Lwjgl3GLCapabilitiesShim;
import com.gtnewhorizons.angelica.vulkan.device.VkDrawable;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import me.eigenraven.lwjgl3ify.api.DisplayEvents;
import me.eigenraven.lwjgl3ify.api.GLCapabilitiesOverride;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDLTimer;
import org.lwjglx.opengl.Display;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static org.lwjgl.sdl.SDLVideo.*;

public abstract class VulkanBackendBase extends RenderBackend {
    protected static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    protected VulkanGL gl;
    protected final ReentrantLock lock = new ReentrantLock();
    protected volatile boolean shared;
    private boolean shutdown;
    private volatile boolean reenterPending;

    @Override public void init() {
        if (gl == null) gl = VulkanRuntime.gl();
        gl.setMainThread(GLStateManager.getMainThread());
        gl.paceSink = new VulkanGL.PaceSink() {
            @Override public void gpuWait(long nanos) { recordGpuWait(nanos); }
            @Override public void gate(long durationNanos, long endNanos) { recordGate(durationNanos, endNanos); }
        };
        Lwjgl3GLCapabilitiesShim.installFunctionProvider();
        Lwjgl3GLCapabilitiesShim.installOnCurrentThread(advertised());
        DisplayEvents.addPreSwapchainInvalidatingChangeListener(change -> onPreSwapchainInvalidatingChange(change));
        LOG.info("Vulkan backend initialized: {}", gl.rendererString());
    }

    private Set<String> advertised() {
        return VulkanGL.advertisedCapabilities(gl.gpu);
    }

    @Override public void onPostWindowCreate(long window) {
        if (gl == null) gl = VulkanRuntime.gl();
        if (window != 0L) GLCapabilitiesOverride.set(advertised());
    }

    @Override public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        lock.lock();
        try {
            if (gl != null) {
                if (SystemProperties.VULKAN_STATS) LOG.info("Vulkan stats: {}", gl.stats());
                gl.shutdown();
            }
            if (VulkanRuntime.gpu() != null) VulkanRuntime.gpu().destroy();
        } finally {
            lock.unlock();
        }
    }

    @Override public boolean isAvailable() {
        return VulkanGate.isRequested() && VulkanGate.isEngaged();
    }

    @Override public String getName() {
        return "Vulkan";
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

    @Override public boolean isVulkan() {
        return true;
    }

    @Override public boolean supportsGeometryShaders() {
        return gl != null && gl.gpu.geometryShader;
    }

    @Override public boolean isAnisotropicSupported() {
        return gl != null && gl.gpu.samplerAnisotropy;
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

    @Override public boolean handleSwapBuffers() {
        if (gl == null) return false;
        if (shared) {
            lock.lock();
            try {
                gl.present();
            } finally {
                lock.unlock();
            }
        } else {
            gl.present();
        }
        return true;
    }


    @Override public boolean handleMakeCurrent(Object drawable) {
        if (!isVulkanDrawable(drawable)) return false;
        final Thread t = Thread.currentThread();
        if (t != gl.mainThread()) {
            shared = true;
            Lwjgl3GLCapabilitiesShim.installOnCurrentThread(advertised());
            final boolean fresh = !gl.hasStateOnThread();
            lock.lock();
            try {
                gl.st();
            } finally {
                lock.unlock();
            }
            if (fresh) GLStateManager.replayStateToBackend();
        } else {
            if (!gl.otherThreadsHaveState()) shared = false;
            if (reenterPending) {
                // the splash thread changed state through GLSM's shared cache; our render-thread context never saw it
                reenterPending = false;
                GLStateManager.replayStateToBackend();
            }
        }
        return true;
    }

    @Override public boolean handleReleaseContext(Object drawable) {
        if (!isVulkanDrawable(drawable)) return false;
        final Thread t = Thread.currentThread();
        if (t == gl.mainThread()) {
            // another thread is about to take the display context (Forge splash): lock from here on
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

    private static boolean isVulkanDrawable(Object drawable) {
        if (drawable == null) return false;
        if (drawable instanceof VkDrawable) return true;
        return VulkanDisplayBridge.isVkSharedDrawable(drawable);
    }

    @Override public void onPreSwapchainInvalidatingChange(Object change) {
        super.onPreSwapchainInvalidatingChange(change);
    }

    @Override public void invalidateStateMirror() {
    }

    public VulkanGL vulkan() {
        return gl;
    }
}
