package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.config.SystemProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetalLWJGLServiceGatingTest {

    @Test
    void anUnengagedMetalDeclinesAndNeverArmsTheFallbackTripwire() {
        final String key = SystemProperties.KEY_CELERITAS_LWJGL_SERVICE;
        assertFalse(MetalGate.isEngaged(), "test JVM must not have an engaged Metal device");
        assertNull(System.getProperty(key), "an unengaged Metal must not bind the celeritas service");

        try {
            MetalLWJGLService.bind();
            assertEquals(MetalLWJGLService.class.getName(), System.getProperty(key));

            assertThrows(
                IllegalStateException.class,
                MetalLWJGLService::create,
                "constructing the service while Metal is unengaged must fail loudly");
        } finally {
            MetalLWJGLService.unbind();
        }

        assertNull(System.getProperty(key));
        assertFalse(MetalLWJGLService.isConstructed(), "declining must leave fallBackToGL() usable");
    }
}
