package com.gtnewhorizons.angelica.glsm.backend;

import com.gtnewhorizons.angelica.config.SystemProperties;

public final class BackendOptions {

    private static boolean configSdlGpu;
    private static boolean configVulkan;
    private static String configDriver = "";
    private static int configGlVersion;

    private BackendOptions() {}

    public static void latch(boolean sdlGpu, String driver, int glVersion) {
        latch(sdlGpu, false, driver, glVersion);
    }

    public static void latch(boolean sdlGpu, boolean vulkan, String driver, int glVersion) {
        configSdlGpu = sdlGpu;
        configVulkan = vulkan;
        configDriver = driver;
        configGlVersion = glVersion;
    }

    public static boolean sdlGpuRequested() {
        if (SystemProperties.SDL_GPU_OVERRIDE != null) return SystemProperties.SDL_GPU_OVERRIDE && !Boolean.TRUE.equals(SystemProperties.VULKAN_OVERRIDE);
        return configSdlGpu && !vulkanRequested();
    }

    public static boolean vulkanRequested() {
        return SystemProperties.VULKAN_OVERRIDE != null ? SystemProperties.VULKAN_OVERRIDE : configVulkan;
    }

    public static String sdlGpuDriver() {
        return sdlGpuDriver(configDriver);
    }

    public static String sdlGpuDriver(String driver) {
        return SystemProperties.SDL_GPU_DRIVER_OVERRIDE != null ? SystemProperties.SDL_GPU_DRIVER_OVERRIDE : driver;
    }

    public static boolean configVulkan() {
        return configVulkan;
    }

    public static boolean configSdlGpu() {
        return configSdlGpu;
    }

    public static String configDriver() {
        return configDriver;
    }

    public static int configGlVersion() {
        return configGlVersion;
    }
}
