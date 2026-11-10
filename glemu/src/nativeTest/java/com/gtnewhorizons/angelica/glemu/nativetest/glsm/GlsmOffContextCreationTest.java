package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.testutil.TestThreads;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmOffContextCreationTest {

    @Test
    void creationWithoutContextThrows() throws InterruptedException {
        TestThreads.run("no-context", () -> assertThrows(IllegalStateException.class, GLSMTextures::glGenTextures));
    }
}
