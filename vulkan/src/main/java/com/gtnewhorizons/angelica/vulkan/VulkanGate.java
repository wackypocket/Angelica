package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.loading.DependencyVerifier.Check;
import java.util.List;

public final class VulkanGate {

    private VulkanGate() {}

    private static final List<Check> REQUIREMENTS = List.of(
        new Check("/org/lwjgl/sdl/SDLVulkan.class", "no lwjgl-sdl on the classpath"),
        NativeGate.LWJGL3IFY,
        NativeGate.SHADERC);

    public static final NativeGate GATE = new NativeGate("Vulkan", "Vulkan", VulkanGate.class, REQUIREMENTS,
        () -> null,
        BackendOptions::vulkanRequested, "com.gtnewhorizons.angelica.vulkan.VulkanRenderBackend",
        () -> VulkanRuntime.INSTANCE, 1, "native-vulkan");
}
