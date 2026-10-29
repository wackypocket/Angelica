package com.gtnewhorizons.angelica.glsm.backend;

import com.gtnewhorizons.angelica.config.SystemProperties;

public final class BackendOptions {

    public static final String DRIVER_VULKAN = "vulkan";
    public static final String DRIVER_METAL = "metal";
    public static final String DRIVER_D3D12 = "d3d12";

    private static boolean configSdlGpu;
    private static boolean configNative;
    private static String configDriver = "";
    private static String configNativeDriver = "";
    private static int configGlVersion;

    private BackendOptions() {}

    public static void latch(boolean sdlGpu, String driver, int glVersion) {
        latch(sdlGpu, false, driver, "", glVersion);
    }

    public static void latch(boolean sdlGpu, boolean nativeBackend, String driver, String nativeDriver, int glVersion) {
        configSdlGpu = sdlGpu;
        configNative = nativeBackend;
        configDriver = driver;
        configNativeDriver = nativeDriver == null ? "" : nativeDriver;
        configGlVersion = glVersion;
    }

    public static boolean sdlGpuRequested() {
        final boolean wanted = SystemProperties.SDL_GPU_OVERRIDE != null ? SystemProperties.SDL_GPU_OVERRIDE : configSdlGpu;
        return wanted && !nativeRequested();
    }

    // Any native-backend flag set to true selects the native renderer; flags only set to false opt out; no flag leaves the config in charge.
    public static Boolean nativeOverride() {
        final Boolean vulkan = SystemProperties.VULKAN_OVERRIDE, metal = SystemProperties.METAL_OVERRIDE, d3d12 = SystemProperties.D3D12_OVERRIDE;
        if (Boolean.TRUE.equals(vulkan) || Boolean.TRUE.equals(metal) || Boolean.TRUE.equals(d3d12)) return Boolean.TRUE;
        if (vulkan != null || metal != null || d3d12 != null) return Boolean.FALSE;
        return null;
    }

    public static boolean nativeRequested() {
        final Boolean forced = nativeOverride();
        if (forced != null) return forced;
        return configNative && !Boolean.TRUE.equals(SystemProperties.SDL_GPU_OVERRIDE);
    }

    public static boolean vulkanRequested() {
        return nativeRequested() && DRIVER_VULKAN.equals(nativeDriver());
    }

    public static boolean metalRequested() {
        return nativeRequested() && DRIVER_METAL.equals(nativeDriver());
    }

    public static boolean d3d12Requested() {
        return nativeRequested() && DRIVER_D3D12.equals(nativeDriver());
    }

    public static String nativeDriver() {
        return nativeDriver(configNativeDriver);
    }

    public static String nativeDriver(String configured) {
        if (Boolean.TRUE.equals(SystemProperties.D3D12_OVERRIDE)) return DRIVER_D3D12;
        if (Boolean.TRUE.equals(SystemProperties.METAL_OVERRIDE)) return DRIVER_METAL;
        if (Boolean.TRUE.equals(SystemProperties.VULKAN_OVERRIDE)) return DRIVER_VULKAN;
        if (DRIVER_D3D12.equalsIgnoreCase(configured)) return DRIVER_D3D12;
        if (DRIVER_METAL.equalsIgnoreCase(configured)) return DRIVER_METAL;
        if (DRIVER_VULKAN.equalsIgnoreCase(configured)) return DRIVER_VULKAN;
        return DRIVER_VULKAN;
    }

    public static String sdlGpuDriver() {
        return sdlGpuDriver(configDriver);
    }

    public static String sdlGpuDriver(String driver) {
        return SystemProperties.SDL_GPU_DRIVER_OVERRIDE != null ? SystemProperties.SDL_GPU_DRIVER_OVERRIDE : driver;
    }

    public static boolean configNative() {
        return configNative;
    }

    public static boolean configSdlGpu() {
        return configSdlGpu;
    }

    public static String configDriver() {
        return configDriver;
    }

    public static String configNativeDriver() {
        return configNativeDriver;
    }

    public static int configGlVersion() {
        return configGlVersion;
    }
}
