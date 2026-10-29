package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeBackendBase;
import com.gtnewhorizons.angelica.vulkan.device.VkDrawable;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;

public abstract class VulkanBackendBase extends NativeBackendBase<VulkanGL> {
    @Override protected VulkanGL createGl() {
        return VulkanRuntime.gl();
    }

    @Override public boolean isVulkan() {
        return true;
    }

    @Override public boolean supportsInPlaceMappedWrites() {
        return SystemProperties.VULKAN_FRAMES_IN_FLIGHT < MAX_FRAMES_IN_FLIGHT;
    }

    @Override protected String apiName() {
        return "Vulkan";
    }

    @Override protected boolean statsEnabled() {
        return SystemProperties.VULKAN_STATS;
    }

    @Override protected boolean gateEngaged() {
        return VulkanGate.isRequested() && VulkanGate.isEngaged();
    }

    @Override protected void destroyDevice() {
        if (VulkanRuntime.gpu() != null) VulkanRuntime.gpu().destroy();
    }

    @Override protected boolean ownsDrawable(Object drawable) {
        if (drawable == null) return false;
        if (drawable instanceof VkDrawable) return true;
        return VulkanDisplayBridge.isVkSharedDrawable(drawable);
    }

    public VulkanGL vulkan() {
        return gl;
    }
}
