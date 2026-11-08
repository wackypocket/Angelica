package com.gtnewhorizons.angelica.vulkan.frame;

import com.gtnewhorizons.angelica.glemu.frame.HostRingBase;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.memory.MemoryAllocator;
import com.gtnewhorizons.angelica.vulkan.memory.VkBufferAlloc;

import static org.lwjgl.vulkan.VK10.*;

public final class HostRing extends HostRingBase<VkBufferAlloc> {
    public static final int USAGE = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
        | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;

    private final Gpu gpu;
    private final MemoryAllocator allocator;
    private final int memoryUsage;

    public HostRing(Gpu gpu, MemoryAllocator allocator, CommandStream stream, long chunkSize, int memoryUsage) {
        super(stream, chunkSize);
        this.gpu = gpu;
        this.allocator = allocator;
        this.memoryUsage = memoryUsage;
    }

    @Override protected VkBufferAlloc allocate(long size) { return VkBufferAlloc.create(gpu, allocator, size, USAGE, memoryUsage); }
    @Override protected long handle(VkBufferAlloc buf) { return buf.buffer; }
    @Override protected long mapped(VkBufferAlloc buf) { return buf.alloc.mapped; }
    @Override protected void destroy(VkBufferAlloc buf) { buf.destroy(gpu, allocator); }
}
