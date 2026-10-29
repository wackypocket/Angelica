package com.gtnewhorizons.angelica.d3d12.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.testutil.TestThreads;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("glsm-d3d12")
@ExtendWith(GlsmD3d12FrameExtension.class)
class GlsmD3d12OffContextCreationTest {

    @Test
    void creationWithoutContextThrows() throws InterruptedException {
        TestThreads.run("sdl-no-context", () -> assertThrows(IllegalStateException.class, GLSMTextures::glGenTextures));
    }
}
