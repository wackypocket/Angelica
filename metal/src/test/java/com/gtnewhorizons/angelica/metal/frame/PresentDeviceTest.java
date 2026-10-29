package com.gtnewhorizons.angelica.metal.frame;

import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.sdl.SDLVideo;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("metal-device")
class PresentDeviceTest {

    static Gpu gpu;
    static MetalGL gl;
    static long window;
    static long view;

    @BeforeAll
    static void setUp() {
        assumeTrue(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "SDL video unavailable");
        gpu = new Gpu();
        assumeTrue(gpu.create(), "no Metal device");
        window = SDLVideo.SDL_CreateWindow("present test", 160, 120, SDLVideo.SDL_WINDOW_METAL | SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY);
        assumeTrue(window != 0L, "no window: " + SDLError.SDL_GetError());
        view = SDLMetal.SDL_Metal_CreateView(window);
        assumeTrue(view != 0L, "no Metal view: " + SDLError.SDL_GetError());
        final long layer = SDLMetal.SDL_Metal_GetLayer(view);
        assumeTrue(layer != 0L, "no CAMetalLayer: " + SDLError.SDL_GetError());
        gl = new MetalGL(gpu, Thread.currentThread(), null);
        gl.attachWindow(window, layer);
    }

    @AfterAll
    static void tearDown() {
        if (gl != null) gl.shutdown();
        if (view != 0L) SDLMetal.SDL_Metal_DestroyView(view);
        if (window != 0L) SDLVideo.SDL_DestroyWindow(window);
        if (gpu != null) gpu.destroy();
    }

    private static long stat(String name) {
        final Matcher m = Pattern.compile(name + "=(\\d+)").matcher(gl.stats());
        assertTrue(m.find(), name + " missing from " + gl.stats());
        return Long.parseLong(m.group(1));
    }

    @Test
    void framesReachTheDrawable() {
        final long before = stat("presented");
        for (int frame = 0; frame < 8; frame++) {
            org.lwjgl.sdl.SDLEvents.SDL_PumpEvents();
            gl.clearColor(frame / 8f, 0.5f, 1f - frame / 8f, 1f);
            gl.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            gl.present();
        }
        assertEquals(before + 8, stat("presented"), "frames were skipped: " + gl.stats());
        assertTrue(gl.swapchainFormat() != 0, "no drawable format");
    }
}
