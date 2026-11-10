package com.gtnewhorizons.angelica.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullscreenPreferenceTest {
    @Test
    void theLiveValueIsSavedUntilTheWindowChangesIt() {
        final FullscreenPreference preference = new FullscreenPreference();
        assertTrue(preference.valueToSave(true));
        assertFalse(preference.valueToSave(false));
    }

    @Test
    void windowChangesAreNotRemembered() {
        final FullscreenPreference preference = new FullscreenPreference();
        preference.beforeWindowChange(false);
        assertFalse(preference.valueToSave(true));
        preference.beforeWindowChange(true);
        assertFalse(preference.valueToSave(false), "leaving again keeps the value from before the first change");
    }

    @Test
    void choosingInSettingsIsRemembered() {
        final FullscreenPreference preference = new FullscreenPreference();
        preference.beforeWindowChange(false);
        preference.chosenInSettings();
        assertTrue(preference.valueToSave(true));
    }

    @Test
    void aWindowChangeAfterChoosingRestoresTheChosenValue() {
        final FullscreenPreference preference = new FullscreenPreference();
        preference.chosenInSettings();
        preference.beforeWindowChange(true);
        assertTrue(preference.valueToSave(false));
    }
}
