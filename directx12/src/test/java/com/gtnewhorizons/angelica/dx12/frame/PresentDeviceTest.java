package com.gtnewhorizons.angelica.dx12.frame;

import com.gtnewhorizons.angelica.dx12.device.Gpu;
import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("dx12-device")
class PresentDeviceTest {

    static Gpu gpu;
    static Dx12GL gl;
    static long window;

    @BeforeAll
    static void setUp() {
        assumeTrue(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "SDL video unavailable");
        gpu = new Gpu();
        assumeTrue(gpu.create(), "no Direct3D 12 device");
        window = SDLVideo.SDL_CreateWindow("present test", 160, 120, 0L);
        assumeTrue(window != 0L, "no window: " + SDLError.SDL_GetError());
        final long hwnd = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(window), SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        assumeTrue(hwnd != 0L, "no HWND");
        gl = new Dx12GL(gpu, Thread.currentThread(), null);
        gl.attachWindow(window, hwnd);
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
