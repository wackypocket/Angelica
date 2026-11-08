package com.gtnewhorizons.angelica.glsm.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GlCallLogTest {

    @Test
    void writesCallsAndRefusalsWithTheirCaller(@TempDir Path dir) throws IOException {
        GlCallLog.call("glBindTexture");
        GlCallLog.call("glBindTexture");
        GlCallLog.refusal("GL_INVALID_ENUM");
        final Path file = dir.resolve("nested/calls.tsv");
        GlCallLog.write(file);
        final List<String> lines = Files.readAllLines(file);
        assertTrue(lines.contains("call\tglBindTexture\t2"), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("refusal\t") && l.contains("GL_INVALID_ENUM") && l.endsWith("\t1")), lines::toString);
    }
}
