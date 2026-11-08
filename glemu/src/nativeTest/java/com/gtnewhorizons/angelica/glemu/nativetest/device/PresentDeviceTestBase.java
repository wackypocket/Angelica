package com.gtnewhorizons.angelica.glemu.nativetest.device;

import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.nativetest.TimingRetry;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLVideo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Presenting to a real window. Each backend opens the window its own way and hands over the GL here. */
public abstract class PresentDeviceTestBase {

    protected static GlBase<?, ?, ?> presenting;

    protected static long stat(String name) {
        final Matcher m = Pattern.compile(name + "=(\\d+)").matcher(presenting.stats());
        assertTrue(m.find(), name + " missing from " + presenting.stats());
        return Long.parseLong(m.group(1));
    }

    @Test
    void framesReachTheWindow() {
        final long before = stat("presented");
        for (int frame = 0; frame < 8; frame++) {
            SDLEvents.SDL_PumpEvents();
            presenting.clearColor(frame / 8f, 0.5f, 1f - frame / 8f, 1f);
            presenting.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            presenting.present();
        }
        assertEquals(before + 8, stat("presented"), "frames were skipped: " + presenting.stats());
    }

    /** A resize drains the GPU first, as the window system's resize hook does, and presenting carries on at the new size. */
    protected static void assertPresentingSurvivesAResize(long window) {
        final long before = stat("presented");
        try {
            presenting.quiesceForSwapchainChange();
            assertTrue(SDLVideo.SDL_SetWindowSize(window, 240, 180), SDLError.SDL_GetError());
            for (int frame = 0; frame < 8; frame++) {
                SDLEvents.SDL_PumpEvents();
                presenting.clearColor(0f, 1f, 0f, 1f);
                presenting.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                presenting.present();
            }
        } finally {
            SDLVideo.SDL_SetWindowSize(window, 160, 120);
            SDLEvents.SDL_PumpEvents();
        }
        assertTrue(stat("presented") > before, presenting.stats());
    }

    /** Whether present() itself blocks on the display, rather than handing the frame to another thread. */
    protected boolean presentBlocksOnTheDriver() {
        return true;
    }

    @Test
    void theFifoGateCoversTheDriverBlock() {
        assumeTrue(presentBlocksOnTheDriver(), "this backend presents from another thread");
        TimingRetry.run(PresentDeviceTestBase::measureTheFifoGateCoversTheDriverBlock);
    }

    private static void measureTheFifoGateCoversTheDriverBlock() {
        assumeTrue(presenting.setVSync(VSyncMode.ON) == VSyncMode.ON, "fifo unavailable");
        final int frames = 120, warmup = 40;
        final long[] gate = new long[1];
        presenting.paceSink = new GlBase.PaceSink() {
            @Override public void gpuWait(long nanos) {}
            @Override public void gate(long durationNanos, long endNanos) { gate[0] = durationNanos; }
        };
        long blocked = 0L, reported = 0L;
        try {
            for (int frame = 0; frame < frames; frame++) {
                SDLEvents.SDL_PumpEvents();
                presenting.clear(GL11.GL_COLOR_BUFFER_BIT);
                presenting.awaitFrameSlot();
                gate[0] = 0L;
                final long t0 = System.nanoTime();
                presenting.present();
                if (frame >= warmup) {
                    blocked += System.nanoTime() - t0;
                    reported += gate[0];
                }
            }
        } finally {
            presenting.paceSink = null;
        }
        final long perFrame = blocked / (frames - warmup);
        assumeTrue(perFrame >= 4_000_000L, "the driver is not holding frames to vblank here (" + perFrame / 1e6 + "ms)");
        assertTrue(reported * 4 >= blocked * 3, "the gate reported " + reported / 1e6 + "ms of the " + blocked / 1e6 + "ms the driver blocked");
    }
}
