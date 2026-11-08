package com.gtnewhorizons.angelica.glsm.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendOptionsTest {

    @Test
    void noNativeFlagLeavesTheConfigInCharge() {
        assertNull(BackendOptions.nativeOverride(null, null));
        assertTrue(BackendOptions.nativeRequested(null, true, null));
        assertFalse(BackendOptions.nativeRequested(null, false, null));
    }

    @Test
    void anyNativeFlagSetToTrueSelectsNative() {
        assertEquals(Boolean.TRUE, BackendOptions.nativeOverride(true, null));
        assertEquals(Boolean.TRUE, BackendOptions.nativeOverride(false, true));
        assertEquals(Boolean.TRUE, BackendOptions.nativeOverride(null, true));
        assertTrue(BackendOptions.nativeRequested(Boolean.TRUE, false, Boolean.TRUE));
    }

    @Test
    void nativeFlagsOnlySetToFalseOptOut() {
        assertEquals(Boolean.FALSE, BackendOptions.nativeOverride(false, null));
        assertEquals(Boolean.FALSE, BackendOptions.nativeOverride(false, false));
        assertFalse(BackendOptions.nativeRequested(Boolean.FALSE, true, null));
    }

    @Test
    void sdlGpuFlagBeatsANativeConfig() {
        assertFalse(BackendOptions.nativeRequested(null, true, Boolean.TRUE));
        assertTrue(BackendOptions.nativeRequested(null, true, Boolean.FALSE));
    }

    @Test
    void sdlGpuFollowsItsFlagThenTheConfig() {
        assertTrue(BackendOptions.sdlGpuRequested(null, true, false));
        assertFalse(BackendOptions.sdlGpuRequested(null, false, false));
        assertTrue(BackendOptions.sdlGpuRequested(Boolean.TRUE, false, false));
        assertFalse(BackendOptions.sdlGpuRequested(Boolean.FALSE, true, false));
    }

    @Test
    void nativeWinsOverSdlGpu() {
        assertFalse(BackendOptions.sdlGpuRequested(Boolean.TRUE, true, true));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(emptyValue = "", value = {"vulkan,vulkan", "metal,metal", "METAL,metal", "'',vulkan", "unknown,vulkan"})
    void configuredDriverFallsBackToVulkan(String configured, String expected) {
        assertEquals(expected, BackendOptions.nativeDriver(configured, null, null));
    }

    @Test
    void driverFlagsBeatTheConfigWithMetalFirst() {
        assertEquals(BackendOptions.DRIVER_VULKAN, BackendOptions.nativeDriver("metal", true, null));
        assertEquals(BackendOptions.DRIVER_METAL, BackendOptions.nativeDriver("vulkan", true, true));
    }

    @Test
    void aDriverFlagSetToFalseDoesNotChooseThatDriver() {
        assertEquals(BackendOptions.DRIVER_METAL, BackendOptions.nativeDriver("metal", false, null));
    }
}
