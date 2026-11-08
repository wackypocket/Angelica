package com.gtnewhorizons.angelica.vulkan.frame;

import com.gtnewhorizons.angelica.glemu.nativetest.TimingRetry;
import com.gtnewhorizons.angelica.glemu.nativetest.device.PresentDeviceTestBase;
import com.gtnewhorizons.angelica.glemu.nativetest.glsm.DeviceRequirement;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.LongBuffer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("vulkan-device")
class PresentDeviceTest extends PresentDeviceTestBase {

    static Gpu gpu;
    static VulkanGL gl;
    static long window;

    @BeforeAll
    static void setUp() {
        assumeTrue(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "SDL video unavailable");
        gpu = new Gpu();
        DeviceRequirement.require(gpu.create(), "no Vulkan device");
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
        presenting = gl;
    }

    @AfterAll
    static void tearDown() {
        if (gl != null) gl.shutdown();
        if (window != 0L) SDLVideo.SDL_DestroyWindow(window);
        if (gpu != null) gpu.destroy();
    }

    @Test
    void presentWaitHoldsEachFrameToTheDisplay() {
        TimingRetry.run(PresentDeviceTest::measurePresentWaitHoldsEachFrameToTheDisplay);
    }

    private static void measurePresentWaitHoldsEachFrameToTheDisplay() {
        assumeTrue(gl.setVSync(VSyncMode.ON) == VSyncMode.ON, "fifo unavailable");
        assumeTrue(gl.hasPresentWait(), "no present wait on this device");
        final int frames = 120, warmup = 40;
        long waited = 0L, presented = 0L;
        for (int frame = 0; frame < frames; frame++) {
            SDLEvents.SDL_PumpEvents();
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
    void workShorterThanTheFrameBudgetIsNotWaitedPast() {
        TimingRetry.run(PresentDeviceTest::measureWorkShorterThanTheFrameBudgetIsNotWaitedPast);
    }

    private static void measureWorkShorterThanTheFrameBudgetIsNotWaitedPast() {
        assumeTrue(gl.setVSync(VSyncMode.ON) == VSyncMode.ON, "fifo unavailable");
        assumeTrue(gl.hasPresentWait(), "no present wait on this device");
        final long refreshFrom = System.nanoTime();
        for (int frame = 0; frame < 100; frame++) {
            SDLEvents.SDL_PumpEvents();
            gl.clear(GL11.GL_COLOR_BUFFER_BIT);
            gl.awaitFrameSlot();
            gl.present();
        }
        assumeTrue((System.nanoTime() - refreshFrom) / 100 < 20_000_000L, "this display refreshes too slowly for the frame budget below");
        final int frames = 60, warmup = 20;
        final long work = 18_000_000L;
        long measuredFrom = 0L;
        for (int frame = 0; frame < frames; frame++) {
            if (frame == warmup) measuredFrom = System.nanoTime();
            final long until = System.nanoTime() + work;
            while (System.nanoTime() < until) Thread.onSpinWait();
            SDLEvents.SDL_PumpEvents();
            gl.clear(GL11.GL_COLOR_BUFFER_BIT);
            gl.awaitFrameSlot();
            gl.present();
            gl.awaitPresent();
        }
        final long perFrame = (System.nanoTime() - measuredFrom) / (frames - warmup);
        assertTrue(perFrame < 25_000_000L, "18ms of CPU work per frame ran at " + perFrame / 1e6 + "ms per frame: the loop is waiting on its own frame (depth one)");
    }

    @Test
    void presentingSurvivesAResize() {
        assertPresentingSurvivesAResize(window);
    }
}
