package me.jellysquid.mods.sodium.client.gui;

import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import net.minecraft.client.settings.GameSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SodiumGameOptionsTest {

    private static SodiumGameOptions withLegacyMode(VSyncMode mode) {
        final SodiumGameOptions options = new SodiumGameOptions();
        options.advanced.vsyncMode = mode;
        options.advanced.migrateLegacyFields();
        return options;
    }

    @Test
    void aLegacyVsyncModeIsCarriedOverOnce() {
        final SodiumGameOptions options = withLegacyMode(VSyncMode.MAILBOX);
        assertEquals(VSyncMode.MAILBOX, options.advanced.presentMode);
        assertEquals(VSyncMode.MAILBOX, options.advanced.vsyncMode, "saved until it has been taken, in case the game stops first");
        assertEquals(Boolean.TRUE, options.advanced.takeMigratedVsync());
        assertNull(options.advanced.vsyncMode);
        assertNull(options.advanced.takeMigratedVsync());
    }

    @Test
    void aLegacyOffModeTurnsVsyncOffWithoutChoosingAPresentMode() {
        final SodiumGameOptions options = withLegacyMode(VSyncMode.OFF);
        assertNull(options.advanced.presentMode);
        assertEquals(Boolean.FALSE, options.advanced.takeMigratedVsync());
    }

    @Test
    void aLimitSavedAsUnlimitedStaysUnlimited() {
        final SodiumGameOptions options = new SodiumGameOptions();
        final GameSettings settings = new GameSettings();
        settings.limitFramerate = 260;
        options.reducer.idleFpsLimit = 260;
        options.reducer.unfocusedFpsLimit = 30;
        assertTrue(options.migrateFramerateScale(settings));
        assertEquals(FrameRateOptions.MAX_FRAMERATE, settings.limitFramerate);
        assertEquals(FrameRateOptions.MAX_FRAMERATE, options.reducer.idleFpsLimit);
        assertEquals(30, options.reducer.unfocusedFpsLimit);
    }

    @Test
    void limitsAreOnlyMigratedOnce() {
        final SodiumGameOptions options = new SodiumGameOptions();
        final GameSettings settings = new GameSettings();
        options.migrateFramerateScale(settings);
        settings.limitFramerate = 260;
        assertFalse(options.migrateFramerateScale(settings));
        assertEquals(260, settings.limitFramerate);
    }
}
