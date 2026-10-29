package com.gtnewhorizons.angelica.config;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import me.jellysquid.mods.sodium.client.gui.options.named.NamedState;

public enum RenderBackendChoice implements NamedState {
    OPENGL  ("options.angelica.renderer.backend.opengl",  ""),
    SDL_GPU ("options.angelica.renderer.backend.sdl_gpu", ""),
    VULKAN  ("options.angelica.renderer.backend.vulkan",  BackendOptions.DRIVER_VULKAN),
    METAL   ("options.angelica.renderer.backend.metal",   BackendOptions.DRIVER_METAL),
    D3D12   ("options.angelica.renderer.backend.d3d12",   BackendOptions.DRIVER_D3D12);

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
            case SDL_GPU -> SystemProperties.EXPERIMENTAL_BACKENDS || Boolean.TRUE.equals(SystemProperties.SDL_GPU_OVERRIDE);
            case METAL -> SystemProperties.EXPERIMENTAL_BACKENDS || Boolean.TRUE.equals(SystemProperties.METAL_OVERRIDE);
            case D3D12 -> SystemProperties.EXPERIMENTAL_BACKENDS || Boolean.TRUE.equals(SystemProperties.D3D12_OVERRIDE);
            default -> true;
        };
    }

    public static RenderBackendChoice fromNativeDriver(String driver) {
        for (final RenderBackendChoice choice : values()) {
            if (choice.isNative() && choice.nativeDriver.equals(driver)) return choice;
        }
        return OPENGL;
    }
}
