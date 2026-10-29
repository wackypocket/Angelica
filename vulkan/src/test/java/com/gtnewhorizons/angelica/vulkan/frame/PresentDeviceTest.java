package com.gtnewhorizons.angelica.vulkan.frame;

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
}
