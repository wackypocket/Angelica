package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.config.SystemProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VulkanLWJGLServiceGatingTest {

    @Test
    void anUnengagedVulkanDeclinesAndNeverArmsTheFallbackTripwire() {
        final String key = SystemProperties.KEY_CELERITAS_LWJGL_SERVICE;
        assertFalse(VulkanGate.isEngaged(), "test JVM must not have an engaged Vulkan device");
        assertNull(System.getProperty(key), "an unengaged Vulkan must not bind the celeritas service");

        try {
            VulkanLWJGLService.bind();
            assertEquals(VulkanLWJGLService.class.getName(), System.getProperty(key));

            assertThrows(
                IllegalStateException.class,
                VulkanLWJGLService::create,
                "constructing the service while Vulkan is unengaged must fail loudly");
        } finally {
            VulkanLWJGLService.unbind();
        }

        assertNull(System.getProperty(key));
        assertFalse(VulkanLWJGLService.isConstructed(), "declining must leave fallBackToGL() usable");
    }
}
