package com.gtnewhorizons.angelica.glemu;

import me.eigenraven.lwjgl3ify.client.MainThreadExec;
import org.lwjgl.system.MemoryStack;
import org.lwjglx.opengl.Display;

import java.nio.IntBuffer;
import java.util.function.BooleanSupplier;

import static org.lwjgl.sdl.SDLHints.SDL_HINT_VIDEO_MAC_FULLSCREEN_MENU_VISIBILITY;
import static org.lwjgl.sdl.SDLHints.SDL_SetHint;
import static org.lwjgl.sdl.SDLVideo.*;

public final class WindowedBounds {
    private final int[] bounds = new int[4];
    private boolean valid;

    public static void showMenuBarInFullscreen() {
        MainThreadExec.runOnMainThread(() -> {
            SDL_SetHint(SDL_HINT_VIDEO_MAC_FULLSCREEN_MENU_VISIBILITY, "1");
        });
    }

    public static boolean isFullscreen() {
        final long window = Display.getWindow();
        if (window == 0L) return false;
        return MainThreadExec.runOnMainThread((BooleanSupplier) () -> (SDL_GetWindowFlags(window) & SDL_WINDOW_FULLSCREEN) != 0L);
    }

    public void save() {
        final long window = Display.getWindow();
        if (window == 0L) return;
        MainThreadExec.runOnMainThread(() -> {
            if ((SDL_GetWindowFlags(window) & SDL_WINDOW_FULLSCREEN) != 0L) return;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1), w = stack.mallocInt(1), h = stack.mallocInt(1);
                SDL_GetWindowPosition(window, x, y);
                SDL_GetWindowSize(window, w, h);
                bounds[0] = x.get(0);
                bounds[1] = y.get(0);
                bounds[2] = w.get(0);
                bounds[3] = h.get(0);
                valid = w.get(0) > 1 && h.get(0) > 1;
            }
        });
    }

    public void restore() {
        final long window = Display.getWindow();
        if (window == 0L || !valid) return;
        MainThreadExec.runOnMainThread(() -> {
            SDL_RestoreWindow(window);
            SDL_SetWindowSize(window, bounds[2], bounds[3]);
            SDL_SetWindowPosition(window, bounds[0], bounds[1]);
            SDL_SyncWindow(window);
        });
    }
}
