package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Records what this backend answers for glemu/scripts/compare_conformance.py to compare with a real driver's answers. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmConformanceTest {

    @Test
    void recordAnswers() throws IOException {
        GlConformanceScenarios.runAll(Path.of("build/conformance/backend.txt"), EnumSet.allOf(GlConformanceScenarios.Needs.class), () -> {
            GlsmHeadlessRig.endFrame();
            GlsmHeadlessRig.beginFrameAndReset();
        });
    }

    /** A limit can only be under what GL requires for a reason written down, so a new shortfall cannot pass unnoticed. */
    @Test
    void limitsMeetWhatGlRequires() throws IOException {
        final java.util.Set<String> excused = new java.util.HashSet<>();
        for (String line : java.nio.file.Files.readAllLines(Path.of("../glemu/scripts/conformance-limit-exceptions.tsv"))) {
            if (!line.startsWith("#")) excused.add(line.split("\t")[0]);
        }
        final java.util.List<String> unexcused = GlRequiredLimits.shortfalls(Path.of("build/conformance/limits-backend.txt")).stream()
            .filter(row -> !excused.contains(row.split("\t")[0])).toList();
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), unexcused);
    }

    /** Only on request: its nonsense arguments leave state no other test in this JVM could run after. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    void recordProbeAnswers() throws IOException, ClassNotFoundException {
        assumeTrue(System.getenv("ANGELICA_CONFORMANCE_PROBE") != null);
        GlCallProbe.run(Path.of("build/conformance/probe-backend.txt"));
        final Runnable between = () -> {
            GlsmHeadlessRig.endFrame();
            GlsmHeadlessRig.beginFrameAndReset();
        };
        GlGuidedProbe.run(Path.of("build/conformance/guided-backend.txt"), between);
        GlConformanceProbes.modPath(Path.of("build/conformance/modpath-backend.txt"), between, false);
    }
}
