package com.gtnewhorizons.angelica.vulkan.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDevicePushDescriptorPropertiesKHR;

import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

final class DriverInfo {
    private DriverInfo() {}

    static String describe(VkPhysicalDevice pd, int apiVersion, MemoryStack stack) {
        if (apiVersion < VK_API_VERSION_1_2) return "unknown driver";
        final VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
        final VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(driver.address());
        vkGetPhysicalDeviceProperties2(pd, props);
        final String info = driver.driverInfoString();
        return info.isEmpty() ? driver.driverNameString() : driver.driverNameString() + " " + info;
    }

    static int maxPushDescriptors(VkPhysicalDevice pd, MemoryStack stack) {
        final VkPhysicalDevicePushDescriptorPropertiesKHR push = VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack).sType$Default();
        final VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(push.address());
        vkGetPhysicalDeviceProperties2(pd, props);
        return push.maxPushDescriptors();
    }
}
