package com.gtnewhorizons.angelica.glsm.backend;

import com.gtnewhorizons.angelica.config.SystemProperties;

import java.util.Locale;

public final class BackendOptions {

    public static final String DRIVER_VULKAN = "vulkan";
    public static final String DRIVER_METAL = "metal";
    public static final String DRIVER_DX12 = "dx12";

    private static boolean configSdlGpu;
    private static boolean configAngelica;
    private static String configDriver = "";
    private static String configAngelicaDriver = "";
    private static int configGlVersion;

    private BackendOptions() {}

    public static void latch(boolean sdlGpu, String driver, int glVersion) {
        latch(sdlGpu, false, driver, "", glVersion);
    }

    public static void latch(boolean sdlGpu, boolean angelica, String driver, String angelicaDriver, int glVersion) {
        configSdlGpu = sdlGpu;
        configAngelica = angelica;
        configDriver = driver;
        configAngelicaDriver = angelicaDriver == null ? "" : angelicaDriver;
        configGlVersion = glVersion;
    }

    public static boolean sdlGpuRequested() {
        final boolean wanted = SystemProperties.SDL_GPU_OVERRIDE != null ? SystemProperties.SDL_GPU_OVERRIDE : configSdlGpu;
        return wanted && !angelicaRequested();
    }

    public static Boolean angelicaOverride() {
        final Boolean vulkan = SystemProperties.VULKAN_OVERRIDE, metal = SystemProperties.METAL_OVERRIDE, dx12 = SystemProperties.DX12_OVERRIDE;
        if (Boolean.TRUE.equals(vulkan) || Boolean.TRUE.equals(metal) || Boolean.TRUE.equals(dx12)) return Boolean.TRUE;
        if (vulkan != null || metal != null || dx12 != null) return Boolean.FALSE;
        return null;
    }

    public static boolean angelicaRequested() {
        final Boolean forced = angelicaOverride();
        if (forced != null) return forced;
        return configAngelica && !Boolean.TRUE.equals(SystemProperties.SDL_GPU_OVERRIDE);
    }

    public static boolean vulkanRequested() {
        return angelicaRequested() && DRIVER_VULKAN.equals(angelicaDriver());
    }

    public static boolean metalRequested() {
        return angelicaRequested() && DRIVER_METAL.equals(angelicaDriver());
    }

    public static boolean dx12Requested() {
        return angelicaRequested() && DRIVER_DX12.equals(angelicaDriver());
    }

    public static String angelicaDriver() {
        return angelicaDriver(configAngelicaDriver);
    }

    public static String angelicaDriver(String configured) {
        if (Boolean.TRUE.equals(SystemProperties.DX12_OVERRIDE)) return DRIVER_DX12;
        if (Boolean.TRUE.equals(SystemProperties.METAL_OVERRIDE)) return DRIVER_METAL;
        if (Boolean.TRUE.equals(SystemProperties.VULKAN_OVERRIDE)) return DRIVER_VULKAN;
        if (DRIVER_DX12.equalsIgnoreCase(configured)) return DRIVER_DX12;
        if (DRIVER_METAL.equalsIgnoreCase(configured)) return DRIVER_METAL;
        if (DRIVER_VULKAN.equalsIgnoreCase(configured)) return DRIVER_VULKAN;
        return isMacOS() ? DRIVER_METAL : DRIVER_VULKAN;
    }

    public static boolean angelicaDriverForced() {
        return Boolean.TRUE.equals(SystemProperties.METAL_OVERRIDE) || Boolean.TRUE.equals(SystemProperties.VULKAN_OVERRIDE) || Boolean.TRUE.equals(SystemProperties.DX12_OVERRIDE);
    }

    private static boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    public static String sdlGpuDriver() {
        return sdlGpuDriver(configDriver);
    }

    public static String sdlGpuDriver(String driver) {
        return SystemProperties.SDL_GPU_DRIVER_OVERRIDE != null ? SystemProperties.SDL_GPU_DRIVER_OVERRIDE : driver;
    }

    public static boolean configAngelica() {
        return configAngelica;
    }

    public static boolean configSdlGpu() {
        return configSdlGpu;
    }

    public static String configDriver() {
        return configDriver;
    }

    public static String configAngelicaDriver() {
        return configAngelicaDriver;
    }

    public static int configGlVersion() {
        return configGlVersion;
    }
}
