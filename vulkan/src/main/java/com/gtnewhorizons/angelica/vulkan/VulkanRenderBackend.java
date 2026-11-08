package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeBackendBase;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;

public final class VulkanRenderBackend extends NativeBackendBase<VulkanGL> {
    @Override protected VulkanRuntime runtime() {
        return VulkanRuntime.INSTANCE;
    }

    @Override protected int framesInFlight() {
        return SystemProperties.VULKAN_FRAMES_IN_FLIGHT;
    }

    @Override protected String apiName() {
        return "Vulkan";
    }

    public VulkanGL vulkan() {
        return gl;
    }
}
