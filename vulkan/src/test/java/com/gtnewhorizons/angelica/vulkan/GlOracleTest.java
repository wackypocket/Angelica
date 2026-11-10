package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlCallProbe;
import com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlConformanceScenarios;
import com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlConformanceProbes;
import com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlGuidedProbe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Records what the machine's real OpenGL driver answers, with nothing of ours in the way, for
 * glemu/scripts/compare_conformance.py. macOS only offers 4.1 core, so the legacy scenarios wait for a driver with a compatibility profile.
 */
@Tag("gl-oracle")
class GlOracleTest {
    private static final boolean MAC = System.getProperty("os.name").startsWith("Mac");

    @Test
    void recordAnswers() throws IOException, ClassNotFoundException {
        assertTrue(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "SDL could not start");
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 4);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, MAC ? 1 : 6);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, MAC ? SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE : SDLVideo.SDL_GL_CONTEXT_PROFILE_COMPATIBILITY);
        if (MAC) SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_FLAGS, SDLVideo.SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_DEPTH_SIZE, 24);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_STENCIL_SIZE, 8);
        final long window = SDLVideo.SDL_CreateWindow("GL oracle", 64, 64, SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
        assertNotEquals(0L, window, "no window");
        final long context = SDLVideo.SDL_GL_CreateContext(window);
        assertNotEquals(0L, context, "no context");
        GL.createCapabilities(MAC);
        final var needs = MAC ? EnumSet.of(GlConformanceScenarios.Needs.CORE_41) : EnumSet.allOf(GlConformanceScenarios.Needs.class);
        GlConformanceScenarios.runAll(Path.of("build/conformance/oracle.txt"), needs, () -> {});
        GlCallProbe.run(Path.of("build/conformance/probe-oracle.txt"));
        GlGuidedProbe.run(Path.of("build/conformance/guided-oracle.txt"), () -> {});
        GlConformanceProbes.modPath(Path.of("build/conformance/modpath-oracle.txt"), () -> {}, true);
    }
}
