package com.gtnewhorizons.angelica.dx12.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.testutil.TestThreads;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("glsm-dx12")
@ExtendWith(GlsmDx12FrameExtension.class)
class GlsmDx12OffContextCreationTest {

    @Test
    void creationWithoutContextThrows() throws InterruptedException {
        TestThreads.run("sdl-no-context", () -> assertThrows(IllegalStateException.class, GLSMTextures::glGenTextures));
    }
}
