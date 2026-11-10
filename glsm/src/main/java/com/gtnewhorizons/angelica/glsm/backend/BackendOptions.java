package com.gtnewhorizons.angelica.glsm.backend;

import com.gtnewhorizons.angelica.config.SystemProperties;

public final class BackendOptions {

    public static final String DRIVER_VULKAN = "vulkan";
    public static final String DRIVER_METAL = "metal";

    private static boolean configSdlGpu;
    private static boolean configNative;
    private static String configDriver = "";
    private static String configNativeDriver = "";
    private static int configGlVersion;

    private BackendOptions() {}

    public static void latch(boolean sdlGpu, boolean nativeBackend, String driver, String nativeDriver, int glVersion) {
        configSdlGpu = sdlGpu;
        configNative = nativeBackend;
        configDriver = driver;
        configNativeDriver = nativeDriver == null ? "" : nativeDriver;
        configGlVersion = glVersion;
    }

    public static boolean sdlGpuRequested() {
        return sdlGpuRequested(SystemProperties.SDL_GPU_OVERRIDE, configSdlGpu, nativeRequested());
    }

    static boolean sdlGpuRequested(Boolean sdlGpuFlag, boolean configured, boolean nativeRequested) {
        final boolean wanted = sdlGpuFlag != null ? sdlGpuFlag : configured;
        return wanted && !nativeRequested;
    }

    // Any native-backend flag set to true selects the native renderer; flags only set to false opt out; no flag leaves the config in charge.
    public static Boolean nativeOverride() {
        return nativeOverride(SystemProperties.VULKAN_OVERRIDE, SystemProperties.METAL_OVERRIDE);
    }

    static Boolean nativeOverride(Boolean vulkan, Boolean metal) {
        if (Boolean.TRUE.equals(vulkan) || Boolean.TRUE.equals(metal)) return Boolean.TRUE;
        if (vulkan != null || metal != null) return Boolean.FALSE;
        return null;
    }

    public static boolean nativeRequested() {
        return nativeRequested(nativeOverride(), configNative, SystemProperties.SDL_GPU_OVERRIDE);
    }

    static boolean nativeRequested(Boolean forced, boolean configured, Boolean sdlGpuFlag) {
        if (forced != null) return forced;
        return configured && !Boolean.TRUE.equals(sdlGpuFlag);
    }

    public static boolean vulkanRequested() {
        return nativeRequested() && DRIVER_VULKAN.equals(nativeDriver());
    }

    public static boolean metalRequested() {
        return nativeRequested() && DRIVER_METAL.equals(nativeDriver());
    }

    public static String nativeDriver() {
        return nativeDriver(configNativeDriver);
    }

    public static String nativeDriver(String configured) {
        return nativeDriver(configured, SystemProperties.VULKAN_OVERRIDE, SystemProperties.METAL_OVERRIDE);
    }

    static String nativeDriver(String configured, Boolean vulkan, Boolean metal) {
        if (Boolean.TRUE.equals(metal)) return DRIVER_METAL;
        if (Boolean.TRUE.equals(vulkan)) return DRIVER_VULKAN;
        if (DRIVER_METAL.equalsIgnoreCase(configured)) return DRIVER_METAL;
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
