package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.glemu.resource.BuffersBase;

import com.gtnewhorizons.angelica.vulkan.frame.HostRing;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import com.gtnewhorizons.angelica.vulkan.memory.MemoryAllocator;
import com.gtnewhorizons.angelica.vulkan.memory.VkBufferAlloc;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;

public final class Buffers extends BuffersBase<Buf> {
    static final int USAGE = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
        | VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT;

    private static final long RENAME_LIMIT = 4L << 20;
    private static final long POOL_BYTE_LIMIT = 64L << 20;

    private final VulkanGL gl;
    private record Pooled(VkBufferAlloc alloc, long lastUse, long generation) {}

    private final Long2ObjectOpenHashMap<ArrayDeque<Pooled>> pool = new Long2ObjectOpenHashMap<>();
    private long coveredSerial = -1;
    private int covered, uploadCovered;
    private long pooledBytes;
    private long trimGeneration;
    public long renames, inlineCopies, hoistedCopies;

    public Buffers(VulkanGL gl) {
        this.gl = gl;
    }

    public Buf internalBuffer(long size, String label) {
        final Buf b = new Buf(-1);
        b.size = size;
        b.memoryUsage = MemoryAllocator.USAGE_DEVICE;
        b.alloc = VkBufferAlloc.create(gl.gpu, gl.allocator, size, USAGE, b.memoryUsage);
        b.label = label;
        final VkCommandBuffer cmd = gl.stream.cmd();
        vkCmdFillBuffer(cmd, b.handle(), 0, VK_WHOLE_SIZE, 0);
        gpuWrite(b, VK_PIPELINE_STAGE_TRANSFER_BIT);
        gl.renderer.pendingTransferBarrier = true;
        return b;
    }

    private VkBufferAlloc acquire(long size, int memoryUsage) {
        final long key = size << 2 | memoryUsage;
        final ArrayDeque<Pooled> q = pool.get(key);
        if (q != null) {
            final Pooled head = q.peekFirst();
            if (head != null && gl.stream.isIdle(head.lastUse)) {
                q.pollFirst();
                final VkBufferAlloc a = head.alloc;
                pooledBytes -= a.size;
                return a;
            }
        }
        return VkBufferAlloc.create(gl.gpu, gl.allocator, size, USAGE, memoryUsage);
    }

    private void retire(VkBufferAlloc a, long lastUse, int memoryUsage) {
        if (a == null) return;
        if (a.size <= RENAME_LIMIT && memoryUsage != MemoryAllocator.USAGE_DEVICE) {
            final long key = a.size << 2 | memoryUsage;
            ArrayDeque<Pooled> q = pool.get(key);
            if (q == null) {
                q = new ArrayDeque<>();
                pool.put(key, q);
            }
            if (pooledBytes + a.size <= POOL_BYTE_LIMIT) {
                q.addLast(new Pooled(a, lastUse, trimGeneration));
                pooledBytes += a.size;
                return;
            }
        }
        destroyLater(a, lastUse);
    }

    @Override protected void resetTracking(Buf b) {
        b.lastUse = 0;
        b.lastGpuWrite = 0;
        b.pendingStages = 0;
        b.pendingWrite = false;
    }

    private void destroyLater(VkBufferAlloc a, long lastUse) {
        gl.stream.deferAfter(Math.max(lastUse, 0), () -> {
            a.destroy(gl.gpu, gl.allocator);
            gl.renderer.invalidateDescriptorCache();
        });
    }

    @Override public void trim() {
        final var it = pool.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            final ArrayDeque<Pooled> q = it.next().getValue();
            while (!q.isEmpty() && q.peekFirst().generation < trimGeneration) {
                final Pooled e = q.pollFirst();
                pooledBytes -= e.alloc.size;
                destroyLater(e.alloc, e.lastUse);
            }
            if (q.isEmpty()) it.remove();
        }
        trimGeneration++;
    }

    public void use(Buf b, int stages) {
        final long serial = gl.stream.currentSerial();
        b.lastUse = serial;
        b.pendingStages |= stages;
        if (coveredSerial == serial) covered &= ~stages;
    }

    public void gpuWrite(Buf b, int stages) {
        use(b, stages);
        b.lastGpuWrite = b.lastUse;
        b.pendingWrite = true;
    }

    private void orderBeforeTransfer(VkCommandBuffer cmd, Buf a, Buf b) {
        final long serial = gl.stream.currentSerial();
        if (coveredSerial != serial) {
            coveredSerial = serial;
            covered = uploadCovered = 0;
        }
        final boolean upload = gl.stream.isUploadCmd(cmd);
        final int need = (pendingStages(a) | pendingStages(b)) & ~(upload ? uploadCovered : covered);
        if (need != 0) {
            gl.images.globalBarrier(cmd, need, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            if (upload) uploadCovered |= need;
            else covered |= need;
        }
    }

    private int pendingStages(Buf b) {
        if (b == null) return 0;
        if (!b.pendingWrite && gl.stream.isIdle(b.lastUse)) b.pendingStages = 0;
        final int stages = b.pendingStages;
        b.pendingStages = 0;
        b.pendingWrite = false;
        return stages;
    }

    @Override public void write(Buf b, long offset, long size, long src) {
        if (b.alloc == null || size <= 0) return;
        if (offset < 0 || offset + size > b.size) {
            gl.warnOnce("buffer write out of range on buffer " + b.id);
            return;
        }
        if (b.hostVisible()) {
            if (gl.stream.isIdle(b.lastUse) || b.persistentlyMapped && gl.stream.isIdle(b.lastGpuWrite)) {
                MemoryUtil.memCopy(src, b.address() + offset, size);
                return;
            }
            final boolean whole = offset == 0 && size == b.size;
            if (b.size <= RENAME_LIMIT && !b.immutable && b.memoryUsage != MemoryAllocator.USAGE_DEVICE && (whole || gl.stream.isIdle(b.lastGpuWrite))) {
                final VkBufferAlloc fresh = acquire(b.alloc.size, b.memoryUsage);
                if (!whole) MemoryUtil.memCopy(b.address(), fresh.alloc.mapped, b.size);
                MemoryUtil.memCopy(src, fresh.alloc.mapped + offset, size);
                retire(b.alloc, b.lastUse, b.memoryUsage);
                b.alloc = fresh;
                resetTracking(b);
                renames++;
                gl.renderer.onBufferRenamed();
                return;
            }
        }
        inlineCopy(b, offset, size, src);
    }

    private void inlineCopy(Buf b, long offset, long size, long src) {
        final HostRing ring = gl.stagingRing;
        if (gl.stream.canHoist(b.lastUse)) {
            ring.alloc(size, 16);
            MemoryUtil.memCopy(src, ring.address, size);
            final VkCommandBuffer upload = gl.stream.uploadCmd();
            orderBeforeTransfer(upload, b, null);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdCopyBuffer(upload, ring.buffer, b.handle(), VkBufferCopy.calloc(1, stack).srcOffset(ring.offset).dstOffset(offset).size(size));
            }
            b.lastUse = b.lastGpuWrite = gl.stream.currentSerial();
            uploadCovered &= ~VK_PIPELINE_STAGE_TRANSFER_BIT;
            hoistedCopies++;
            return;
        }
        gl.renderer.endPass();
        ring.alloc(size, 16);
        MemoryUtil.memCopy(src, ring.address, size);
        final VkCommandBuffer cmd = gl.stream.cmd();
        orderBeforeTransfer(cmd, b, null);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(ring.offset).dstOffset(offset).size(size);
            vkCmdCopyBuffer(cmd, ring.buffer, b.handle(), region);
        }
        gpuWrite(b, VK_PIPELINE_STAGE_TRANSFER_BIT);
        gl.renderer.pendingTransferBarrier = true;
        inlineCopies++;
    }

    @Override public void copy(Buf src, Buf dst, long srcOffset, long dstOffset, long size) {
        if (src == null || dst == null || src.alloc == null || dst.alloc == null || size <= 0) return;
        if (srcOffset < 0 || dstOffset < 0 || srcOffset + size > src.size || dstOffset + size > dst.size) {
            gl.warnOnce("glCopyBufferSubData out of range on buffers " + src.id + " -> " + dst.id);
            return;
        }
        if (src != dst && gl.stream.canHoist(dst.lastUse) && gl.stream.canHoist(src.lastGpuWrite)) {
            final VkCommandBuffer upload = gl.stream.uploadCmd();
            orderBeforeTransfer(upload, src, dst);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdCopyBuffer(upload, src.handle(), dst.handle(), VkBufferCopy.calloc(1, stack).srcOffset(srcOffset).dstOffset(dstOffset).size(size));
            }
            use(src, VK_PIPELINE_STAGE_TRANSFER_BIT);
            gpuWrite(dst, VK_PIPELINE_STAGE_TRANSFER_BIT);
            uploadCovered &= ~VK_PIPELINE_STAGE_TRANSFER_BIT;
            hoistedCopies++;
            return;
        }
        gl.renderer.endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        orderBeforeTransfer(cmd, src, dst);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(srcOffset).dstOffset(dstOffset).size(size);
            vkCmdCopyBuffer(cmd, src.handle(), dst.handle(), region);
        }
        use(src, VK_PIPELINE_STAGE_TRANSFER_BIT);
        gpuWrite(dst, VK_PIPELINE_STAGE_TRANSFER_BIT);
        gl.renderer.pendingTransferBarrier = true;
    }

    @Override public void fill(Buf b, long offset, long size, int value) {
        if (b == null || b.alloc == null) return;
        if (size != VK_WHOLE_SIZE && (offset < 0 || size < 0 || offset + size > b.size)) {
            gl.warnOnce("glClearBufferSubData out of range on buffer " + b.id);
            return;
        }
        gl.renderer.endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        orderBeforeTransfer(cmd, b, null);
        vkCmdFillBuffer(cmd, b.handle(), offset & ~3L, size == VK_WHOLE_SIZE ? VK_WHOLE_SIZE : (size + 3) & ~3L, value);
        gpuWrite(b, VK_PIPELINE_STAGE_TRANSFER_BIT);
        gl.renderer.pendingTransferBarrier = true;
    }

    @Override public void read(Buf b, long offset, long size, long dst) {
        if (b == null || b.alloc == null || size <= 0) return;
        if (b.lastUse > gl.stream.completedSerial()) gl.flushAndWait(b.lastUse);
        if (b.hostVisible()) {
            MemoryUtil.memCopy(b.address() + offset, dst, size);
            return;
        }
        final VkBufferAlloc staging = VkBufferAlloc.create(gl.gpu, gl.allocator, size, USAGE, MemoryAllocator.USAGE_READBACK);
        try {
            final VkCommandBuffer cmd = gl.stream.cmd();
            orderBeforeTransfer(cmd, b, null);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdCopyBuffer(cmd, b.handle(), staging.buffer, VkBufferCopy.calloc(1, stack).srcOffset(offset).dstOffset(0).size(size));
            }
            use(b, VK_PIPELINE_STAGE_TRANSFER_BIT);
            gl.flushAndWait(gl.stream.currentSerial());
            MemoryUtil.memCopy(staging.alloc.mapped, dst, size);
        } finally {
            staging.destroy(gl.gpu, gl.allocator);
        }
    }

    @Override public ByteBuffer map(Buf b, long offset, long length, int access) {
        if (b == null || b.alloc == null || b.mapped) return null;
        if (length <= 0 || offset < 0 || offset + length > b.size) return null;
        if ((access & GL44.GL_MAP_PERSISTENT_BIT) != 0 && (!b.immutable || (b.storageFlags & GL44.GL_MAP_PERSISTENT_BIT) == 0)) return null;
        final boolean unsync = (access & GL30.GL_MAP_UNSYNCHRONIZED_BIT) != 0 || (access & GL44.GL_MAP_PERSISTENT_BIT) != 0;
        final boolean read = (access & GL30.GL_MAP_READ_BIT) != 0;
        final boolean invalidateBuffer = (access & GL30.GL_MAP_INVALIDATE_BUFFER_BIT) != 0;
        final boolean invalidateRange = (access & GL30.GL_MAP_INVALIDATE_RANGE_BIT) != 0;
        final boolean discardOld = invalidateBuffer || invalidateRange && offset == 0 && length == b.size;
        b.mapped = true;
        b.mapOffset = offset;
        b.mapLength = length;
        b.mapAccess = access;
        if ((access & GL44.GL_MAP_PERSISTENT_BIT) != 0) b.persistentlyMapped = true;
        if (b.hostVisible() && (b.memoryUsage != MemoryAllocator.USAGE_DEVICE || gl.stream.isIdle(b.lastUse))) {
            if (!unsync && !gl.stream.isIdle(b.lastUse)) {
                if (read) {
                    gl.flushAndWait(b.lastUse);
                } else if (b.size <= RENAME_LIMIT && !b.immutable && (discardOld || gl.stream.isIdle(b.lastGpuWrite))) {
                    final VkBufferAlloc fresh = acquire(b.alloc.size, b.memoryUsage);
                    if (!discardOld) {
                        MemoryUtil.memCopy(b.address(), fresh.alloc.mapped, b.size);
                    }
                    retire(b.alloc, b.lastUse, b.memoryUsage);
                    b.alloc = fresh;
                    resetTracking(b);
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

    @Override public void destroy() {
        for (Buf b : buffers.values()) {
            if (b.alloc != null) b.alloc.destroy(gl.gpu, gl.allocator);
            if (b.mapStaging != null) MemoryUtil.memFree(b.mapStaging);
        }
        buffers.clear();
        for (ArrayDeque<Pooled> q : pool.values()) for (Pooled e : q) e.alloc.destroy(gl.gpu, gl.allocator);
        pool.clear();
        pooledBytes = 0;
    }

    @Override protected Buf newBuf(int id) {
        return new Buf(id);
    }

    @Override protected void warnOnce(String message) {
        gl.warnOnce(message);
    }

    @Override protected boolean isIdle(long serial) {
        return gl.stream.isIdle(serial);
    }

    @Override protected long storageSize(Buf b) {
        return b.alloc == null ? -1 : b.alloc.size;
    }

    @Override protected void releaseStorage(Buf b) {
        retire(b.alloc, b.lastUse, b.memoryUsage);
        b.alloc = null;
    }

    @Override protected void allocateStorage(Buf b, long size, int memoryUsage) {
        b.alloc = acquire(size, memoryUsage);
    }
}
