package com.gtnewhorizons.angelica.glsm.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendStartGuardTest {

    @TempDir
    Path dir;

    private boolean boot() {
        return boot(1);
    }

    private boolean boot(int revision) {
        return boot(revision, "");
    }

    private boolean boot(int revision, String driver) {
        return BackendStartGuard.begin(dir.toFile(), true, false, revision, driver);
    }

    private File marker() {
        return dir.resolve("sdlgpu-starting").toFile();
    }

    @Test
    void bootsThatReachTheBackendNeverCountAsStrikes() {
        for (int i = 0; i < BackendStartGuard.STRIKE_LIMIT; i++) {
            assertFalse(boot());
            BackendStartGuard.arm();
            BackendStartGuard.disarm();
        }
        assertFalse(boot());
    }

    @Test
    void thirdDeathInTheDeviceWindowResets() {
        for (int i = 0; i < BackendStartGuard.STRIKE_LIMIT; i++) {
            assertFalse(boot());
            BackendStartGuard.arm();
        }
        assertTrue(boot());
        assertTrue(BackendStartGuard.tripped());
        assertFalse(marker().exists());
    }

    @Test
    void latchStopsArmingUntilTheRequirementsRevisionChanges() {
        assertFalse(boot());
        BackendStartGuard.arm();
        BackendStartGuard.disarm();
        BackendStartGuard.latch();
        assertFalse(boot());
        BackendStartGuard.arm();
        assertFalse(marker().exists());

        assertFalse(boot(2));
        BackendStartGuard.arm();
        assertTrue(marker().isFile());
    }

    @Test
    void strikesFromAnotherBackendDoNotCount() {
        for (int i = 0; i < BackendStartGuard.STRIKE_LIMIT - 1; i++) {
            assertFalse(boot(1, "vulkan"));
            BackendStartGuard.arm();
        }
        assertFalse(boot(1, "native-vulkan"));
        BackendStartGuard.arm();
        assertFalse(boot(1, "native-vulkan"));
        assertFalse(BackendStartGuard.tripped());
    }

    @Test
    void verifiedBackendsStayVerifiedWhenAlternating() {
        for (String driver : new String[] {"vulkan", "native-vulkan"}) {
            assertFalse(boot(1, driver));
            BackendStartGuard.arm();
            BackendStartGuard.disarm();
            BackendStartGuard.latch();
        }
        BackendStartGuard.arm();
        assertFalse(boot(1, "vulkan"));
        BackendStartGuard.arm();
        assertFalse(marker().exists());
        assertFalse(boot(1, "native-vulkan"));
        BackendStartGuard.arm();
        assertFalse(marker().exists());
    }
}
