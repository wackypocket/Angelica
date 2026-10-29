package com.gtnewhorizons.angelica.vulkan.frame;

import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.LongBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("vulkan-device")
class PresentDeviceTest {

    static Gpu gpu;
    static VulkanGL gl;
    static long window;

    @BeforeAll
    static void setUp() {
        assumeTrue(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "SDL video unavailable");
        gpu = new Gpu();
        assumeTrue(gpu.create(), "no Vulkan device");
        window = SDLVideo.SDL_CreateWindow("present test", 160, 120, SDLVideo.SDL_WINDOW_VULKAN | SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY);
        assumeTrue(window != 0L, "no window: " + SDLError.SDL_GetError());
        final long surface;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final LongBuffer p = stack.mallocLong(1);
            assumeTrue(SDLVulkan.nSDL_Vulkan_CreateSurface(window, gpu.instance.address(), 0L, MemoryUtil.memAddress(p)), "no surface: " + SDLError.SDL_GetError());
            surface = p.get(0);
        }
        assumeTrue(gpu.supportsPresent(surface), "device cannot present to the window");
        gl = new VulkanGL(gpu, Thread.currentThread(), null);
        gl.attachWindow(window, surface);
    }

    @AfterAll
    static void tearDown() {
        if (gl != null) gl.shutdown();
        if (window != 0L) SDLVideo.SDL_DestroyWindow(window);
        if (gpu != null) gpu.destroy();
    }

    private static long stat(String name) {
        final Matcher m = Pattern.compile(name + "=(\\d+)").matcher(gl.stats());
        assertTrue(m.find(), name + " missing from " + gl.stats());
        return Long.parseLong(m.group(1));
    }

    @Test
    void framesReachTheSwapchain() {
        final long before = stat("presented");
        for (int frame = 0; frame < 8; frame++) {
            org.lwjgl.sdl.SDLEvents.SDL_PumpEvents();
            gl.clearColor(frame / 8f, 0.5f, 1f - frame / 8f, 1f);
            gl.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            gl.present();
        }
        assertEquals(before + 8, stat("presented"), "frames were skipped: " + gl.stats());
        assertTrue(gl.swapchainFormat() != 0, "no swapchain");
    }

    @Test
    void theFifoGateCoversTheDriverBlock() {
        assumeTrue(gl.setVSync(VSyncMode.ON) == VSyncMode.ON, "fifo unavailable");
        final int frames = 120, warmup = 40;
        final long[] gate = new long[1];
        gl.paceSink = new GlBase.PaceSink() {
            @Override public void gpuWait(long nanos) {}
            @Override public void gate(long durationNanos, long endNanos) { gate[0] = durationNanos; }
        };
        long blocked = 0L, reported = 0L;
        try {
            for (int frame = 0; frame < frames; frame++) {
                org.lwjgl.sdl.SDLEvents.SDL_PumpEvents();
                gl.clear(GL11.GL_COLOR_BUFFER_BIT);
                gl.awaitFrameSlot();
                gate[0] = 0L;
                final long t0 = System.nanoTime();
                gl.present();
                if (frame >= warmup) {
                    blocked += System.nanoTime() - t0;
                    reported += gate[0];
                }
            }
        } finally {
            gl.paceSink = null;
        }
        final long perFrame = blocked / (frames - warmup);
        assumeTrue(perFrame >= 4_000_000L, "the driver is not holding frames to vblank here (" + perFrame / 1e6 + "ms per present)");
        assertTrue(reported * 4 >= blocked * 3, "the gate reported " + reported / 1e6 + "ms of the " + blocked / 1e6 + "ms the driver held the CPU in present");
    }

    @Test
    void presentWaitHoldsEachFrameToTheDisplay() {
        assumeTrue(gl.setVSync(VSyncMode.ON) == VSyncMode.ON, "fifo unavailable");
        assumeTrue(gl.hasPresentWait(), "no present wait on this device");
        final int frames = 120, warmup = 40;
        long waited = 0L, presented = 0L;
        for (int frame = 0; frame < frames; frame++) {
            org.lwjgl.sdl.SDLEvents.SDL_PumpEvents();
            gl.clear(GL11.GL_COLOR_BUFFER_BIT);
            gl.awaitFrameSlot();
            final long t0 = System.nanoTime();
            gl.present();
            final long t1 = System.nanoTime();
            final long w = gl.awaitPresent();
            if (frame >= warmup) {
                presented += t1 - t0;
                waited += w;
            }
        }
        final long perFrame = (waited + presented) / (frames - warmup);
        assumeTrue(perFrame >= 4_000_000L, "the driver is not holding frames to vblank here (" + perFrame / 1e6 + "ms per frame)");
        assertTrue(waited * 4 >= (waited + presented) * 3, "present-wait held " + waited / 1e6 + "ms but vkQueuePresentKHR still blocked for " + presented / 1e6 + "ms");
    }

    @Test
    void theDepthSurvivesAStretchOfPresentsThatNeverWaited() {
        assumeTrue(gl.setVSync(VSyncMode.ON) == VSyncMode.ON, "fifo unavailable");
        assumeTrue(gl.hasPresentWait(), "no present wait on this device");
        for (int frame = 0; frame < 100; frame++) {
            org.lwjgl.sdl.SDLEvents.SDL_PumpEvents();
            gl.clear(GL11.GL_COLOR_BUFFER_BIT);
            gl.awaitFrameSlot();
            gl.present();
        }
        final int frames = 60, warmup = 20;
        final long work = 18_000_000L;
        long measuredFrom = 0L;
        for (int frame = 0; frame < frames; frame++) {
            if (frame == warmup) measuredFrom = System.nanoTime();
            final long until = System.nanoTime() + work;
            while (System.nanoTime() < until) Thread.onSpinWait();
            org.lwjgl.sdl.SDLEvents.SDL_PumpEvents();
            gl.clear(GL11.GL_COLOR_BUFFER_BIT);
            gl.awaitFrameSlot();
            gl.present();
            gl.awaitPresent();
        }
        final long perFrame = (System.nanoTime() - measuredFrom) / (frames - warmup);
        assertTrue(perFrame < 25_000_000L, "18ms of CPU work per frame ran at " + perFrame / 1e6 + "ms per frame: the loop is waiting on its own frame (depth one)");
    }
}
