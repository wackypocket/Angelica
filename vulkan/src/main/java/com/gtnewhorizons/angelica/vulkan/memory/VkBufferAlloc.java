package com.gtnewhorizons.angelica.vulkan.memory;

import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static org.lwjgl.vulkan.VK10.*;

public final class VkBufferAlloc {
    public final long buffer;
    public final Allocation alloc;
    public final long size;

    private VkBufferAlloc(long buffer, Allocation alloc, long size) {
        this.buffer = buffer;
        this.alloc = alloc;
        this.size = size;
    }

    private static final long MIN_MAP_ALIGNMENT = 64L;

    public static VkBufferAlloc create(Gpu gpu, MemoryAllocator allocator, long size, int usage, int memoryUsage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(Math.max(size, 4))
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateBuffer(gpu.device, info, null, p), "vkCreateBuffer");
            final long buffer = p.get(0);
            final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(gpu.device, buffer, req);
            // GL promises mapped pointers this aligned, however little the device asks for.
            MemoryUtil.memPutLong(req.address() + VkMemoryRequirements.ALIGNMENT, Math.max(req.alignment(), MIN_MAP_ALIGNMENT));
            final Allocation a;
            try {
                a = allocator.allocate(req, memoryUsage, false);
            } catch (RuntimeException | OutOfMemoryError e) {
                vkDestroyBuffer(gpu.device, buffer, null);
                throw e;
            }
            VkResults.check(vkBindBufferMemory(gpu.device, buffer, a.memory, a.offset), "vkBindBufferMemory");
            return new VkBufferAlloc(buffer, a, size);
        }
    }

    public boolean isHostVisible() {
        return alloc.mapped != 0L;
    }

    public void destroy(Gpu gpu, MemoryAllocator allocator) {
        vkDestroyBuffer(gpu.device, buffer, null);
        allocator.free(alloc);
    }
}
