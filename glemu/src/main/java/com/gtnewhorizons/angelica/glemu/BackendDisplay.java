package com.gtnewhorizons.angelica.glemu;

import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import java.nio.ByteBuffer;
import me.eigenraven.lwjgl3ify.api.DisplayEvents;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjglx.input.Mouse;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.PixelFormat;

import static org.lwjgl.sdl.SDLVideo.SDL_GetCurrentDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetPrimaryDisplay;

/** The window steps every backend without an OpenGL context shares. */
public final class BackendDisplay {
    private BackendDisplay() {}

    /** Opens the window with no GL context; returns what stopped it, or null. */
    public static Exception open(PixelFormat pixelFormat) {
        DisplayEvents.setCreateGLContext(false);
        try {
            Display.create(pixelFormat, null, 0L);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    /** The refresh period of the window's display, or the primary one before a window exists; 0 when unknown. */
    public static long refreshPeriodNanos() {
        return refreshPeriodNanos(Display.getWindow());
    }

    /** The refresh period of the given window's display, or the primary one when it is 0; 0 when unknown. */
    public static long refreshPeriodNanos(long window) {
        final int displayId = window == 0 ? SDL_GetPrimaryDisplay() : SDL_GetDisplayForWindow(window);
        if (displayId == 0) return 0L;
        final SDL_DisplayMode mode = SDL_GetCurrentDisplayMode(displayId);
        if (mode == null) return 0L;
        return RenderBackend.periodFromRational(mode.refresh_rate_numerator(), mode.refresh_rate_denominator(), mode.refresh_rate());
    }

    /** Closes the window so OpenGL can open its own. */
    public static void closeForOpenGL(ByteBuffer[] icons) {
        Mouse.setGrabbed(false);
        DisplayEvents.setCreateGLContext(true);
        if (Display.isCreated()) Display.destroy();
        Display.isCloseRequested(); // reading it clears the close request the destroyed window raised
        if (icons != null) Display.setIcon(icons);
    }
}
