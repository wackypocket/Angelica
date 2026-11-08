package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Only on request, through glemu/scripts/cts/cts_run.py: runs the Khronos conformance suite against this backend. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmKhronosCtsTest {

    private static final int WINDOW_SIZE = 256;

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    void run() throws IOException {
        final String library = System.getenv("ANGELICA_CTS_LIBRARY");
        assumeTrue(library != null);
        System.load(library);
        GlsmHeadlessRig.installFinalTarget(WINDOW_SIZE, WINDOW_SIZE);
        GlConformanceScenarios.reset();
        org.lwjgl.opengl.GL11.glViewport(0, 0, WINDOW_SIZE, WINDOW_SIZE);
        com.gtnewhorizons.angelica.glemu.gl.GlValidation.strictCore = true;
        final String[] args = Files.readAllLines(Path.of(System.getenv("ANGELICA_CTS_ARGS"))).toArray(new String[0]);
        GlCtsBridge.watchForHangs(Long.parseLong(System.getenv("ANGELICA_CTS_HANG_SECONDS")));
        GlCtsBridge.run(args);
    }
}
