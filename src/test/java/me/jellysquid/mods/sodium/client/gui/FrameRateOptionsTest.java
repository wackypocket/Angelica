package me.jellysquid.mods.sodium.client.gui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameRateOptionsTest {

    @ParameterizedTest(name = "migrateLegacyLimit({0}) -> {1}")
    @CsvSource({"260, 401", "120, 120", "259, 259", "261, 261", "401, 401", "0, 0"})
    void onlyTheOldUnlimitedValueMoves(int saved, int expected) {
        assertEquals(expected, FrameRateOptions.migrateLegacyLimit(saved));
    }
}
