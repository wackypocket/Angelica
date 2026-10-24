package com.gtnewhorizons.angelica.vulkan.device;

public final class VulkanDeviceLostException extends RuntimeException {
    public VulkanDeviceLostException(String operation) {
        super("Vulkan device lost during " + operation);
    }
}
