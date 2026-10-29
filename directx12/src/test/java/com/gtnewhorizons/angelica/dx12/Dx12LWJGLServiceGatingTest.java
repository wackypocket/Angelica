package com.gtnewhorizons.angelica.dx12;

import com.gtnewhorizons.angelica.config.SystemProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Dx12LWJGLServiceGatingTest {

    @Test
    void anUnengagedDx12DeclinesAndNeverArmsTheFallbackTripwire() {
        final String key = SystemProperties.KEY_CELERITAS_LWJGL_SERVICE;
        assertFalse(Dx12Gate.isEngaged(), "test JVM must not have an engaged Direct3D 12 device");
        assertNull(System.getProperty(key), "an unengaged Direct3D 12 must not bind the celeritas service");

        try {
            Dx12LWJGLService.bind();
            assertEquals(Dx12LWJGLService.class.getName(), System.getProperty(key));

            assertThrows(
                IllegalStateException.class,
                Dx12LWJGLService::create,
                "constructing the service while Direct3D 12 is unengaged must fail loudly");
        } finally {
            Dx12LWJGLService.unbind();
        }

        assertNull(System.getProperty(key));
        assertFalse(Dx12LWJGLService.isConstructed(), "declining must leave fallBackToGL() usable");
    }
}
