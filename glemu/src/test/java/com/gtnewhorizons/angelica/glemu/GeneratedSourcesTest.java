package com.gtnewhorizons.angelica.glemu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The checked-in sources are what the scripts generate, so a table edit cannot be lost by forgetting to run one. */
class GeneratedSourcesTest {

    private static final Path SOURCES = Path.of("glemu/src/main/java/com/gtnewhorizons/angelica/glemu");

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("glemu/scripts"))) dir = dir.getParent();
        assumeTrue(dir != null, "not running inside the repository");
        return dir;
    }

    private static boolean pythonAvailable() {
        try {
            final Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void assertGenerates(String script, Path output, List<String> files, Path checkedIn) throws Exception {
        assumeTrue(pythonAvailable(), "python3 is not installed");
        final Path root = repositoryRoot();
        final ProcessBuilder builder = new ProcessBuilder("python3", script).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().put("ANGELICA_GEN_DIR", output.toString());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        final Process process = builder.start();
        final String log = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "timed out");
        assertEquals(0, process.exitValue(), log);
        for (String file : files) {
            assertEquals(Files.readString(root.resolve(checkedIn).resolve(file)), Files.readString(output.resolve(file)),
                file + " differs from what " + script + " generates; run it from the repository root");
        }
    }

    @Test
    void theEntryPointsAreUpToDate(@TempDir Path output) throws Exception {
        assertGenerates("glemu/scripts/gen_entry_points.py", output, List.of("GlEntryPoints.java"), SOURCES.resolve("compat"));
    }

    @Test
    void theBackendForwardersAreUpToDate(@TempDir Path output) throws Exception {
        assertGenerates("glemu/scripts/gen_backend.py", output, List.of("GlOps.java", "GpuRenderBackend.java"), SOURCES);
    }
}
