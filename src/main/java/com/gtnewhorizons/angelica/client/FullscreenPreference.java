package com.gtnewhorizons.angelica.client;

/**
 * Keeps the fullscreen value saved for the next boot apart from the live one. The window's own controls change the live value so the
 * settings stay accurate, but only choosing it in the settings changes what the next boot uses.
 */
public final class FullscreenPreference {
    private boolean heldBack;
    private boolean saved;

    /** Call before the live value follows the window. */
    public void beforeWindowChange(boolean current) {
        if (heldBack) return;
        heldBack = true;
        saved = current;
    }

    public void chosenInSettings() {
        heldBack = false;
    }

    public boolean valueToSave(boolean current) {
        return heldBack ? saved : current;
    }
}
