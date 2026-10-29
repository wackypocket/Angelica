package com.gtnewhorizons.angelica.d3d12;

import com.gtnewhorizons.angelica.config.SystemProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class D3d12LWJGLServiceGatingTest {

    @Test
    void anUnengagedD3d12DeclinesAndNeverArmsTheFallbackTripwire() {
        final String key = SystemProperties.KEY_CELERITAS_LWJGL_SERVICE;
        assertFalse(D3d12Gate.isEngaged(), "test JVM must not have an engaged Direct3D 12 device");
        assertNull(System.getProperty(key), "an unengaged Direct3D 12 must not bind the celeritas service");

        try {
            D3d12LWJGLService.bind();
            assertEquals(D3d12LWJGLService.class.getName(), System.getProperty(key));

            assertThrows(
                IllegalStateException.class,
                D3d12LWJGLService::create,
                "constructing the service while Direct3D 12 is unengaged must fail loudly");
        } finally {
            D3d12LWJGLService.unbind();
        }

        assertNull(System.getProperty(key));
        assertFalse(D3d12LWJGLService.isConstructed(), "declining must leave fallBackToGL() usable");
    }
}
