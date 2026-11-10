package com.gtnewhorizons.angelica.lwjgl3;

import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;

/** A backend whose window is an SDL window, which it can read back and restore. */
public abstract class WindowTrackingRenderBackend extends RenderBackend {
    private final WindowedBounds windowedBounds = new WindowedBounds();

    @Override
    public void saveWindowedBounds() {
        windowedBounds.save();
    }

    @Override
    public void restoreWindowedBounds() {
        windowedBounds.restore();
    }

    @Override
    public boolean tracksWindowFullscreen() {
        return true;
    }

    @Override
    public boolean isWindowFullscreen() {
        return WindowedBounds.isFullscreen();
    }
}
