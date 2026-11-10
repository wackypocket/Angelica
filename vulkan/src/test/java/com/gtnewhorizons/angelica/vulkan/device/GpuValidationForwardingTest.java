package com.gtnewhorizons.angelica.vulkan.device;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.vulkan.EXTDebugUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GpuValidationForwardingTest {

    private static int[] forwarded(int severity, int types) {
        final int[] got = new int[2];
        Gpu.forwardValidation((type, glSeverity, message) -> {
            got[0] = type;
            got[1] = glSeverity;
        }, severity, types, "message");
        return got;
    }

    @Test
    void aValidationErrorIsAHighSeverityError() {
        final int[] got = forwarded(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT, EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT);
        assertEquals(KHRDebug.GL_DEBUG_TYPE_ERROR, got[0]);
        assertEquals(KHRDebug.GL_DEBUG_SEVERITY_HIGH, got[1]);
    }

    @Test
    void aPerformanceWarningKeepsItsType() {
        final int[] got = forwarded(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT, EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
        assertEquals(KHRDebug.GL_DEBUG_TYPE_PERFORMANCE, got[0]);
        assertEquals(KHRDebug.GL_DEBUG_SEVERITY_MEDIUM, got[1]);
    }
}
