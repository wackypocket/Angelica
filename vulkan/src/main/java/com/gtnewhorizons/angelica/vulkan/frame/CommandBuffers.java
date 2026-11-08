package com.gtnewhorizons.angelica.vulkan.frame;

import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

final class CommandBuffers {
    private CommandBuffers() {}

    static long createPool(Gpu gpu, int queueFamily) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack)
                .sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT | VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                .queueFamilyIndex(queueFamily);
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateCommandPool(gpu.device, info, null, p), "vkCreateCommandPool");
            return p.get(0);
        }
    }

    static VkCommandBuffer allocate(Gpu gpu, long pool, int level) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack)
                .sType$Default()
                .commandPool(pool)
                .level(level)
                .commandBufferCount(1);
            final PointerBuffer p = stack.mallocPointer(1);
            VkResults.check(vkAllocateCommandBuffers(gpu.device, info, p), "vkAllocateCommandBuffers");
            return new VkCommandBuffer(p.get(0), gpu.device);
        }
    }
}
