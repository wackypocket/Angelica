package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier.Check;

import java.nio.ByteBuffer;
import java.util.List;


public final class VulkanGate {

    private VulkanGate() {}

    public static final String LWJGL3IFY_MIN_VERSION = "3.0.25";
    public static final int REQUIREMENTS_REVISION = 1;
    public static final String GUARD_KEY = "native-vulkan";

    private static final List<Check> REQUIREMENTS = List.of(
        new Check("/org/lwjgl/sdl/SDLVulkan.class", "no lwjgl-sdl on the classpath"),
        new Check("/me/eigenraven/lwjgl3ify/api/DisplayEvents.class", "lwjgl3ify older than " + LWJGL3IFY_MIN_VERSION + ", update the mod: https://modrinth.com/mod/lwjgl3ify"),
        new Check("/org/lwjgl/util/shaderc/Shaderc.class", "no lwjgl-shaderc on the classpath; re-apply the lwjgl3ify instance zip"));

    private static final NativeGate GATE = new NativeGate("Vulkan", VulkanGate.class, REQUIREMENTS,
        () -> null,
        BackendOptions::vulkanRequested, "com.gtnewhorizons.angelica.vulkan.VulkanRenderBackend",
        new NativeGate.Hooks() {
            @Override public boolean probe() {
                return VulkanRuntime.probe();
            }

            @Override public boolean createDisplay(Object pixelFormat, Object attribs) {
                return VulkanRuntime.createDisplay(pixelFormat, attribs);
            }

            @Override public void checkCanFallBack() {
                VulkanRuntime.checkCanFallBack();
            }

            @Override public void fallBackToGL() {
                VulkanRuntime.fallBackToGL();
            }

            @Override public void ensureDrawableInstalled() {
                VulkanRuntime.ensureDrawableInstalled();
            }
        });

    public static boolean isRequested() {
        return GATE.isRequested();
    }

    public static boolean isVulkanAvailable() {
        return GATE.isAvailable();
    }

    public static String missingRequirement() {
        return GATE.missingRequirement();
    }

    public static void rememberIcons(ByteBuffer[] icons) {
        GATE.rememberIcons(icons);
    }

    static ByteBuffer[] icons() {
        return GATE.icons();
    }

    public static boolean isDeviceReady() {
        return GATE.isDeviceReady();
    }

    public static boolean isEngaged() {
        return GATE.isEngaged();
    }

    public static void setEngaged(boolean value) {
        GATE.setEngaged(value);
    }

    public static boolean isActive() {
        return GATE.isActive();
    }

    public static boolean createDisplay(Object pixelFormat, Object attribs) {
        return GATE.createDisplay(pixelFormat, attribs);
    }

    public static void fallBackToGL() {
        GATE.fallBackToGL();
    }

    public static void ensureDrawableInstalled() {
        GATE.ensureDrawableInstalled();
    }
}
