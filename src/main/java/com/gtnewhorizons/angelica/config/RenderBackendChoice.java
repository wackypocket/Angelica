package com.gtnewhorizons.angelica.config;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import me.jellysquid.mods.sodium.client.gui.options.named.NamedState;

public enum RenderBackendChoice implements NamedState {
    OPENGL  ("options.angelica.renderer.backend.opengl",  ""),
    SDL_GPU ("options.angelica.renderer.backend.sdl_gpu", ""),
    VULKAN  ("options.angelica.renderer.backend.vulkan",  BackendOptions.DRIVER_VULKAN),
    METAL   ("options.angelica.renderer.backend.metal",   BackendOptions.DRIVER_METAL);

    private static RenderBackendChoice rejected;

    private final String key;
    private final String nativeDriver;

    RenderBackendChoice(String key, String nativeDriver) {
        this.key = key;
        this.nativeDriver = nativeDriver;
    }

    @Override
    public String getKey() {
        return key;
    }

    public String nativeDriver() {
        return nativeDriver;
    }

    public boolean isNative() {
        return !nativeDriver.isEmpty();
    }

    public boolean isSelectable() {
        return switch (this) {
            case SDL_GPU -> unlocked(SystemProperties.EXPERIMENTAL_BACKENDS, SystemProperties.SDL_GPU_OVERRIDE);
            case METAL -> SystemProperties.EXPERIMENTAL_BACKENDS;
            default -> true;
        };
    }

    /** The launch arguments that make a backend selectable. */
    public String requiredFlag() {
        return this == SDL_GPU ? "-Dangelica.experimentalBackends=true or -D" + SystemProperties.KEY_USE_SDL_GPU + "=true" : "-Dangelica.experimentalBackends=true";
    }

    static boolean unlocked(boolean experimental, Boolean backendFlag) {
        return experimental || Boolean.TRUE.equals(backendFlag);
    }

    /** The saved choice that was replaced at startup because its flag is not set, for the options screen. */
    public static RenderBackendChoice rejected() {
        return rejected;
    }

    public static void reject(RenderBackendChoice choice) {
        rejected = choice;
    }

    public static RenderBackendChoice fromNativeDriver(String driver) {
        for (final RenderBackendChoice choice : values()) {
            if (choice.isNative() && choice.nativeDriver.equals(driver)) return choice;
        }
        return OPENGL;
    }
}
