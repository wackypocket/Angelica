package com.gtnewhorizons.angelica.vulkan.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

final class DriverInfo {
    private DriverInfo() {}

    static String describe(VkPhysicalDevice pd, MemoryStack stack) {
        final VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
        final VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(driver.address());
        vkGetPhysicalDeviceProperties2(pd, props);
        final String info = driver.driverInfoString();
        return info.isEmpty() ? driver.driverNameString() : driver.driverNameString() + " " + info;
    }
}
