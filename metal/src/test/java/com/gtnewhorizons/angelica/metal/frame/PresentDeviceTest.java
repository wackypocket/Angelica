package com.gtnewhorizons.angelica.metal.frame;

import com.gtnewhorizons.angelica.glemu.nativetest.device.PresentDeviceTestBase;
import com.gtnewhorizons.angelica.glemu.nativetest.glsm.DeviceRequirement;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.sdl.SDLVideo;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("metal-device")
class PresentDeviceTest extends PresentDeviceTestBase {

    static Gpu gpu;
    static MetalGL gl;
    static long window;
    static long view;

    @BeforeAll
    static void setUp() {
        assumeTrue(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "SDL video unavailable");
        gpu = new Gpu();
        DeviceRequirement.require(gpu.create(), "no Metal device");
        window = SDLVideo.SDL_CreateWindow("present test", 160, 120, SDLVideo.SDL_WINDOW_METAL | SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY);
        assumeTrue(window != 0L, "no window: " + SDLError.SDL_GetError());
        view = SDLMetal.SDL_Metal_CreateView(window);
        assumeTrue(view != 0L, "no Metal view: " + SDLError.SDL_GetError());
        final long layer = SDLMetal.SDL_Metal_GetLayer(view);
        assumeTrue(layer != 0L, "no CAMetalLayer: " + SDLError.SDL_GetError());
        gl = new MetalGL(gpu, Thread.currentThread());
        gl.attachWindow(window, layer);
        presenting = gl;
    }

    @AfterAll
    static void tearDown() {
        if (gl != null) gl.shutdown();
        if (view != 0L) SDLMetal.SDL_Metal_DestroyView(view);
        if (window != 0L) SDLVideo.SDL_DestroyWindow(window);
        if (gpu != null) gpu.destroy();
    }

    @Override
    protected boolean presentBlocksOnTheDriver() {
        return false;
    }

    @Test
    void presentedFramesAreCommittedToDrawables() {
        gl.drainPresents();
        final long before = gl.committedPresents();
        for (int frame = 0; frame < 4; frame++) {
            SDLEvents.SDL_PumpEvents();
            gl.clear(GL11.GL_COLOR_BUFFER_BIT);
            gl.present();
        }
        gl.drainPresents();
        assertTrue(gl.committedPresents() > before, "a skipped drawable may drop a frame, but not all of them");
    }

    @Test
    void presentingSurvivesAResize() {
        assertPresentingSurvivesAResize(window);
    }
}
