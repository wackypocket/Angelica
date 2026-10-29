package com.gtnewhorizons.angelica.vulkan.frame;

import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.memory.VkBufferAlloc;
import com.gtnewhorizons.angelica.vulkan.memory.MemoryAllocator;

import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;

public final class HostRing {
    public static final int USAGE = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
        | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;

    private final Gpu gpu;
    private final MemoryAllocator allocator;
    private final CommandStream stream;
    private final long chunkSize;
    private final int memoryUsage;

    private Chunk current;
    private final ArrayDeque<Chunk> retired = new ArrayDeque<>();
    private final ArrayDeque<Chunk> spare = new ArrayDeque<>();

    public long buffer;
    public long offset;
    public long address;

    private static final class Chunk {
        final VkBufferAlloc buf;
        long head;
        long retireSerial;

        Chunk(VkBufferAlloc buf) {
            this.buf = buf;
        }
    }

    public HostRing(Gpu gpu, MemoryAllocator allocator, CommandStream stream, long chunkSize, int memoryUsage) {
        this.gpu = gpu;
        this.allocator = allocator;
        this.stream = stream;
        this.chunkSize = chunkSize;
        this.memoryUsage = memoryUsage;
    }

    public void alloc(long size, long alignment) {
        if (size > chunkSize / 2) {
            allocDedicated(size);
            return;
        }
        if (current != null) {
            final long aligned = (current.head + alignment - 1) / alignment * alignment;
            if (aligned + size <= chunkSize) {
                publish(current, aligned, size);
                return;
            }
            current.retireSerial = stream.currentSerial();
            retired.addLast(current);
            current = null;
        }
        current = nextChunk();
        publish(current, 0, size);
    }

    private void publish(Chunk c, long at, long size) {
        c.head = at + size;
        buffer = c.buf.buffer;
        offset = at;
        address = c.buf.alloc.mapped + at;
    }

    private Chunk nextChunk() {
        while (!retired.isEmpty() && stream.isIdle(retired.peekFirst().retireSerial)) {
            final Chunk c = retired.pollFirst();
            c.head = 0;
            spare.addLast(c);
        }
        final Chunk reuse = spare.pollFirst();
        if (reuse != null) return reuse;
        return new Chunk(VkBufferAlloc.create(gpu, allocator, chunkSize, USAGE, memoryUsage));
    }

    private void allocDedicated(long size) {
        final VkBufferAlloc big = VkBufferAlloc.create(gpu, allocator, size, USAGE, memoryUsage);
        stream.defer(() -> big.destroy(gpu, allocator));
        buffer = big.buffer;
        offset = 0;
        address = big.alloc.mapped;
    }

    public void trim(int keep) {
        while (spare.size() > keep) spare.pollLast().buf.destroy(gpu, allocator);
    }

    public void destroy() {
        if (current != null) current.buf.destroy(gpu, allocator);
        current = null;
        for (Chunk c : retired) c.buf.destroy(gpu, allocator);
        for (Chunk c : spare) c.buf.destroy(gpu, allocator);
        retired.clear();
        spare.clear();
    }
}
