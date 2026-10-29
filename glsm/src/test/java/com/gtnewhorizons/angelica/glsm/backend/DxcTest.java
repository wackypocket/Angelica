package com.gtnewhorizons.angelica.glsm.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxcTest {

    private static final String PRESENT_RESOURCE = "/com/gtnewhorizons/angelica/glsm/backend/Dxc.class";
    private static final String ABSENT_RESOURCE = "/windows/x64/absent.dll";

    @Test
    void jarIsExtractedOnceAndNotRewritten(@TempDir Path dir) throws IOException {
        final File out = dir.resolve("natives").toFile();
        final String first = Dxc.extract(PRESENT_RESOURCE, out, "x64");
        final File extracted = new File(out, "dxcompiler-x64.dll");
        assertEquals(extracted.getAbsolutePath(), first);
        assertTrue(extracted.length() > 0);
        assertTrue(extracted.setLastModified(1_000_000L));
        assertEquals(first, Dxc.extract(PRESENT_RESOURCE, out, "x64"));
        assertEquals(1_000_000L, extracted.lastModified());
        assertEquals(1, out.list().length);
    }

    @Test
    void changedFileIsRewritten(@TempDir Path dir) throws IOException {
        final File extracted = new File(dir.toFile(), "dxcompiler-arm64.dll");
        Files.write(extracted.toPath(), new byte[] { 1, 2, 3 });
        Dxc.extract(PRESENT_RESOURCE, dir.toFile(), "arm64");
        assertTrue(extracted.length() > 3);
    }

    @Test
    void missingOrUnextractableLibraryResolvesToNothing(@TempDir Path dir) throws IOException {
        final File notADirectory = Files.createFile(dir.resolve("blocker")).toFile();
        assertNull(Dxc.extract(PRESENT_RESOURCE, notADirectory, "x64"));
        assertNull(Dxc.extract(ABSENT_RESOURCE, dir.toFile(), "x64"));
    }

    @Test
    void archNames() {
        assertEquals("arm64", Dxc.archName("aarch64"));
        assertEquals("x64", Dxc.archName("amd64"));
    }
}
