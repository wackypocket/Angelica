package com.gtnewhorizons.angelica.mixins.interfaces;

public interface IGameSettingsExt {
    boolean angelica$showFpsGraph();
    void angelica$setShowFpsGraph(boolean renderFpsGraph);
    /** Follows the window into or out of fullscreen without changing what the next boot uses. */
    void angelica$followWindowFullscreen(boolean fullscreen);
    /** Makes the current fullscreen value the one the next boot uses. */
    void angelica$fullscreenChosenInSettings();
}
