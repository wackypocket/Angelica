package com.gtnewhorizons.angelica.glemu.nativetest;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeLwjglService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeLWJGLServiceGatingTest {

    @Test
    void anUnengagedBackendDeclinesAndNeverArmsTheFallbackTripwire() {
        final String key = SystemProperties.KEY_CELERITAS_LWJGL_SERVICE;
        assertFalse(TestBackend.gate().isEngaged(), "test JVM must not have an engaged device");
        assertNull(System.getProperty(key), "an unengaged backend must not bind the celeritas service");

        try {
            NativeLwjglService.bind(TestBackend.serviceClass());
            assertEquals(TestBackend.serviceClass().getName(), System.getProperty(key));

            assertThrows(
                IllegalStateException.class,
                TestBackend::createService,
                "constructing the service while the backend is unengaged must fail loudly");
        } finally {
            NativeLwjglService.unbind(TestBackend.serviceClass());
        }

        assertNull(System.getProperty(key));
        assertFalse(TestBackend.serviceConstructed(), "declining must leave fallBackToGL() usable");
    }
}
