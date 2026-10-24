package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.vulkan.frame.HostRing;
import com.gtnewhorizons.angelica.vulkan.memory.MemoryAllocator;
import com.gtnewhorizons.angelica.vulkan.memory.VkBufferAlloc;
import com.gtnewhorizons.angelica.vulkan.resource.Buf;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;

final class Buffers {
    static final int USAGE = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
        | VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT;

    private static final long RENAME_LIMIT = 4L << 20;

    private final VulkanGL gl;
    private final Int2ObjectOpenHashMap<Buf> buffers = new Int2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<ArrayDeque<long[]>> pool = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<VkBufferAlloc> poolAllocs = new Long2ObjectOpenHashMap<>();
    private int nextId = 1;
    long renames, inlineCopies;

    Buffers(VulkanGL gl) {
        this.gl = gl;
    }

    int gen() {
        int id = nextId++;
        while (buffers.containsKey(id)) id = nextId++;
        buffers.put(id, new Buf(id));
        return id;
    }

    Buf get(int id) {
        return id == 0 ? null : buffers.get(id);
    }

    Buf getOrCreate(int id) {
        if (id == 0) return null;
        Buf b = buffers.get(id);
        if (b == null) {
            b = new Buf(id);
            buffers.put(id, b);
            if (id >= nextId) nextId = id + 1;
        }
        return b;
    }

    boolean exists(int id) {
        return id != 0 && buffers.containsKey(id);
    }

    void delete(int id) {
        final Buf b = buffers.remove(id);
        if (b == null) return;
        if (b.mapStaging != null) {
            MemoryUtil.memFree(b.mapStaging);
            b.mapStaging = null;
        }
        retire(b.alloc, b.lastUse, b.memoryUsage);
        b.alloc = null;
    }

    Buf internalBuffer(long size, String label) {
        final Buf b = new Buf(-1);
        b.size = size;
        b.memoryUsage = MemoryAllocator.USAGE_DEVICE;
        b.alloc = VkBufferAlloc.create(gl.gpu, gl.allocator, size, USAGE, b.memoryUsage);
        b.label = label;
        final VkCommandBuffer cmd = gl.stream.cmd();
        vkCmdFillBuffer(cmd, b.handle(), 0, VK_WHOLE_SIZE, 0);
        gl.renderer.pendingTransferBarrier = true;
        return b;
    }

    static int memoryUsageFor(int glUsage) {
        return switch (glUsage) {
            case GL15.GL_STREAM_DRAW, GL15.GL_DYNAMIC_DRAW -> MemoryAllocator.USAGE_STREAM;
            case GL15.GL_STREAM_READ, GL15.GL_DYNAMIC_READ, GL15.GL_STATIC_READ, GL15.GL_STREAM_COPY, GL15.GL_DYNAMIC_COPY -> MemoryAllocator.USAGE_READBACK;
            default -> MemoryAllocator.USAGE_DEVICE;
        };
    }

    static int memoryUsageForStorage(int flags) {
        if ((flags & GL30.GL_MAP_READ_BIT) != 0) return MemoryAllocator.USAGE_READBACK;
        if ((flags & (GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_DYNAMIC_STORAGE_BIT)) != 0) return MemoryAllocator.USAGE_STREAM;
        return MemoryAllocator.USAGE_DEVICE;
    }

    private VkBufferAlloc acquire(long size, int memoryUsage) {
        final long key = size << 2 | memoryUsage;
        final ArrayDeque<long[]> q = pool.get(key);
        if (q != null) {
            final long[] head = q.peekFirst();
            if (head != null && gl.stream.isIdle(head[1])) {
                q.pollFirst();
                return poolAllocs.remove(head[0]);
            }
        }
        return VkBufferAlloc.create(gl.gpu, gl.allocator, size, USAGE, memoryUsage);
    }

    private void retire(VkBufferAlloc a, long lastUse, int memoryUsage) {
        if (a == null) return;
        if (a.size <= RENAME_LIMIT && memoryUsage != MemoryAllocator.USAGE_DEVICE) {
            final long key = a.size << 2 | memoryUsage;
            ArrayDeque<long[]> q = pool.get(key);
            if (q == null) {
                q = new ArrayDeque<>();
                pool.put(key, q);
            }
            if (q.size() < 8) {
                q.addLast(new long[] {a.buffer, lastUse});
                poolAllocs.put(a.buffer, a);
                return;
            }
        }
        gl.stream.deferAfter(Math.max(lastUse, 0), () -> a.destroy(gl.gpu, gl.allocator));
    }

    void data(Buf b, long size, long data, int glUsage) {
        b.size = size;
        b.usageHint = glUsage;
        final int mu = memoryUsageFor(glUsage);
        final boolean reuse = b.alloc != null && b.alloc.size == Math.max(size, 4) && b.memoryUsage == mu && gl.stream.isIdle(b.lastUse);
        if (!reuse) {
            retire(b.alloc, b.lastUse, b.memoryUsage);
            b.memoryUsage = mu;
            b.alloc = size == 0 ? null : acquire(Math.max(size, 4), mu);
            b.lastUse = 0;
        }
        if (data != 0L && size > 0) write(b, 0, size, data);
    }

    void storage(Buf b, long size, long data, int flags) {
        retire(b.alloc, b.lastUse, b.memoryUsage);
        b.size = size;
        b.storageFlags = flags;
        b.immutable = true;
        b.memoryUsage = memoryUsageForStorage(flags);
        b.alloc = acquire(Math.max(size, 4), b.memoryUsage);
        b.lastUse = 0;
        if (data != 0L && size > 0) write(b, 0, size, data);
    }

    void write(Buf b, long offset, long size, long src) {
        if (b.alloc == null || size <= 0) return;
        if (offset < 0 || offset + size > b.size) {
            gl.warnOnce("buffer write out of range on buffer " + b.id);
            return;
        }
        if (b.hostVisible()) {
            if (gl.stream.isIdle(b.lastUse) || b.persistentlyMapped) {
                MemoryUtil.memCopy(src, b.address() + offset, size);
                return;
            }
            if (b.size <= RENAME_LIMIT && !b.immutable) {
                final VkBufferAlloc fresh = acquire(b.alloc.size, b.memoryUsage);
                if (offset > 0 || offset + size < b.size) MemoryUtil.memCopy(b.address(), fresh.alloc.mapped, b.size);
                MemoryUtil.memCopy(src, fresh.alloc.mapped + offset, size);
                retire(b.alloc, b.lastUse, b.memoryUsage);
                b.alloc = fresh;
                b.lastUse = 0;
                renames++;
                gl.renderer.onBufferRenamed();
                return;
            }
        }
        inlineCopy(b, offset, size, src);
    }

    private void inlineCopy(Buf b, long offset, long size, long src) {
        gl.renderer.endPass();
        final HostRing ring = gl.stagingRing;
        ring.alloc(size, 16);
        MemoryUtil.memCopy(src, ring.address, size);
        final VkCommandBuffer cmd = gl.stream.cmd();
        gl.images.globalBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(ring.offset).dstOffset(offset).size(size);
            vkCmdCopyBuffer(cmd, ring.buffer, b.handle(), region);
        }
        b.lastUse = gl.stream.currentSerial();
        gl.renderer.pendingTransferBarrier = true;
        inlineCopies++;
    }

    void copy(Buf src, Buf dst, long srcOffset, long dstOffset, long size) {
        if (src == null || dst == null || src.alloc == null || dst.alloc == null || size <= 0) return;
        gl.renderer.endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        gl.images.globalBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(srcOffset).dstOffset(dstOffset).size(size);
            vkCmdCopyBuffer(cmd, src.handle(), dst.handle(), region);
        }
        final long serial = gl.stream.currentSerial();
        src.lastUse = serial;
        dst.lastUse = serial;
        gl.renderer.pendingTransferBarrier = true;
    }

    void fill(Buf b, long offset, long size, int value) {
        if (b == null || b.alloc == null) return;
        gl.renderer.endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        gl.images.globalBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        vkCmdFillBuffer(cmd, b.handle(), offset & ~3L, size == VK_WHOLE_SIZE ? VK_WHOLE_SIZE : (size + 3) & ~3L, value);
        b.lastUse = gl.stream.currentSerial();
        gl.renderer.pendingTransferBarrier = true;
    }

    void read(Buf b, long offset, long size, long dst) {
        if (b == null || b.alloc == null || size <= 0) return;
        if (b.lastUse > gl.stream.completedSerial()) gl.flushAndWait(b.lastUse);
        if (b.hostVisible()) {
            MemoryUtil.memCopy(b.address() + offset, dst, size);
            return;
        }
        final VkBufferAlloc staging = VkBufferAlloc.create(gl.gpu, gl.allocator, size, USAGE, MemoryAllocator.USAGE_READBACK);
        try {
            final VkCommandBuffer cmd = gl.stream.cmd();
            gl.images.globalBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdCopyBuffer(cmd, b.handle(), staging.buffer, VkBufferCopy.calloc(1, stack).srcOffset(offset).dstOffset(0).size(size));
            }
            gl.flushAndWait(gl.stream.currentSerial());
            MemoryUtil.memCopy(staging.alloc.mapped, dst, size);
        } finally {
            staging.destroy(gl.gpu, gl.allocator);
        }
    }

    ByteBuffer map(Buf b, long offset, long length, int access) {
        if (b == null || b.alloc == null || b.mapped) return null;
        if (length <= 0 || offset < 0 || offset + length > b.size) return null;
        final boolean unsync = (access & GL30.GL_MAP_UNSYNCHRONIZED_BIT) != 0 || (access & GL44.GL_MAP_PERSISTENT_BIT) != 0;
        final boolean read = (access & GL30.GL_MAP_READ_BIT) != 0;
        final boolean invalidateBuffer = (access & GL30.GL_MAP_INVALIDATE_BUFFER_BIT) != 0;
        final boolean invalidateRange = (access & GL30.GL_MAP_INVALIDATE_RANGE_BIT) != 0;
        b.mapped = true;
        b.mapOffset = offset;
        b.mapLength = length;
        b.mapAccess = access;
        if ((access & GL44.GL_MAP_PERSISTENT_BIT) != 0) b.persistentlyMapped = true;
        if (b.hostVisible()) {
            if (!unsync && !gl.stream.isIdle(b.lastUse)) {
                if (read) {
                    gl.flushAndWait(b.lastUse);
                } else if (b.size <= RENAME_LIMIT && !b.immutable) {
                    final VkBufferAlloc fresh = acquire(b.alloc.size, b.memoryUsage);
                    if (!invalidateBuffer && !(invalidateRange && offset == 0 && length == b.size)) {
                        MemoryUtil.memCopy(b.address(), fresh.alloc.mapped, b.size);
                    }
                    retire(b.alloc, b.lastUse, b.memoryUsage);
                    b.alloc = fresh;
                    b.lastUse = 0;
                    renames++;
                    gl.renderer.onBufferRenamed();
                } else {
                    gl.flushAndWait(b.lastUse);
                }
            }
            b.mapView = MemoryUtil.memByteBuffer(b.address() + offset, (int) length);
            return b.mapView;
        }
        final ByteBuffer staging = MemoryUtil.memAlloc((int) length);
        if (read || !(invalidateRange || invalidateBuffer)) read(b, offset, length, MemoryUtil.memAddress(staging));
        b.mapStaging = staging;
        b.mapView = staging;
        return staging;
    }

    boolean unmap(Buf b) {
        if (b == null || !b.mapped) return false;
        if (b.mapStaging != null) {
            if ((b.mapAccess & GL30.GL_MAP_WRITE_BIT) != 0 || b.mapAccess == 0) {
                write(b, b.mapOffset, b.mapLength, MemoryUtil.memAddress(b.mapStaging));
            }
            MemoryUtil.memFree(b.mapStaging);
            b.mapStaging = null;
        }
        b.mapped = false;
        b.mapView = null;
        b.persistentlyMapped = false;
        return true;
    }

    void flushMapped(Buf b, long offset, long length) {
        if (b == null || !b.mapped || b.mapStaging == null) return;
        write(b, b.mapOffset + offset, length, MemoryUtil.memAddress(b.mapStaging) + offset);
    }

    void destroy() {
        for (Buf b : buffers.values()) {
            if (b.alloc != null) b.alloc.destroy(gl.gpu, gl.allocator);
            if (b.mapStaging != null) MemoryUtil.memFree(b.mapStaging);
        }
        buffers.clear();
        for (VkBufferAlloc a : poolAllocs.values()) a.destroy(gl.gpu, gl.allocator);
        poolAllocs.clear();
        pool.clear();
    }
}
