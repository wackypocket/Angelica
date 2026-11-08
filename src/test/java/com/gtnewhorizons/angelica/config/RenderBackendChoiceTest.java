package com.gtnewhorizons.angelica.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderBackendChoiceTest {

    @Test
    void nativeDriversMapBackToTheirChoice() {
        for (final RenderBackendChoice choice : RenderBackendChoice.values()) {
            if (choice.isNative()) assertEquals(choice, RenderBackendChoice.fromNativeDriver(choice.nativeDriver()));
        }
    }

    @Test
    void unknownDriversMapToOpenGl() {
        assertEquals(RenderBackendChoice.OPENGL, RenderBackendChoice.fromNativeDriver("unknown"));
        assertEquals(RenderBackendChoice.OPENGL, RenderBackendChoice.fromNativeDriver(""));
    }

    @Test
    void openGlAndVulkanNeedNoFlag() {
        assertTrue(RenderBackendChoice.OPENGL.isSelectable());
        assertTrue(RenderBackendChoice.VULKAN.isSelectable());
    }

    @Test
    void metalOpensOnlyWithTheExperimentalFlag() {
        assertEquals(SystemProperties.EXPERIMENTAL_BACKENDS, RenderBackendChoice.METAL.isSelectable());
        assertEquals("-Dangelica.experimentalBackends=true", RenderBackendChoice.METAL.requiredFlag());
    }

    @Test
    void sdlGpuOpensWithItsFlagOrTheExperimentalOne() {
        assertFalse(RenderBackendChoice.unlocked(false, null));
        assertFalse(RenderBackendChoice.unlocked(false, Boolean.FALSE));
        assertTrue(RenderBackendChoice.unlocked(false, Boolean.TRUE));
        assertTrue(RenderBackendChoice.unlocked(true, null));
        assertEquals("-Dangelica.experimentalBackends=true or -Dangelica.sdlgpu.enable=true", RenderBackendChoice.SDL_GPU.requiredFlag());
    }
}
