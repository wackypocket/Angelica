package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.vulkan.frame.CommandStream;
import com.gtnewhorizons.angelica.glemu.resource.BuffersBase;
import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.resource.BufferPool;
import com.gtnewhorizons.angelica.glemu.resource.PooledBuffersBase;
import com.gtnewhorizons.angelica.vulkan.frame.HostRing;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import com.gtnewhorizons.angelica.vulkan.memory.VkBufferAlloc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

public final class Buffers extends PooledBuffersBase<Buf, VkBufferAlloc> {

    private final VulkanGL gl;
    private long coveredSerial = -1;
    private int covered, uploadCovered;

    public Buffers(VulkanGL gl) {
        super(gl, gl.stream, new BufferPool.Storage<VkBufferAlloc>() {
            @Override public VkBufferAlloc create(long size, int usage) { return VkBufferAlloc.create(gl.gpu, gl.allocator, size, HostRing.USAGE, usage); }
            @Override public void destroy(VkBufferAlloc alloc) { alloc.destroy(gl.gpu, gl.allocator); }
            @Override public long size(VkBufferAlloc alloc) { return alloc.size; }
            @Override public long mapped(VkBufferAlloc alloc) { return alloc.alloc.mapped; }
            @Override public boolean reusable(VkBufferAlloc alloc, int usage) { return usage != BuffersBase.USAGE_DEVICE || alloc.isHostVisible(); }
            @Override public void onDeferredDestroy() { gl.renderer.invalidateDescriptorCache(); }
        });
        this.gl = gl;
    }

    public Buf internalBuffer(long size, String label) {
        final Buf b = new Buf(-1);
        b.size = size;
        b.memoryUsage = BuffersBase.USAGE_DEVICE;
        b.alloc = VkBufferAlloc.create(gl.gpu, gl.allocator, size, HostRing.USAGE, b.memoryUsage);
        b.label = label;
        final VkCommandBuffer cmd = gl.stream.cmd();
        vkCmdFillBuffer(cmd, b.handle(), 0, VK_WHOLE_SIZE, 0);
        gpuWrite(b, VK_PIPELINE_STAGE_TRANSFER_BIT);
        gl.renderer.pendingTransferBarrier = true;
        return b;
    }

    @Override protected void resetTracking(Buf b) {
        b.lastUse = 0;
        b.lastGpuWrite = 0;
        b.gpuWrites++;
        b.lastMainWrite = 0;
        b.pendingStages = 0;
        b.pendingWrite = false;
    }

    public void use(Buf b, int stages) {
        final long serial = gl.stream.currentSerial();
        b.lastUse = serial;
        b.pendingStages |= stages;
        if (coveredSerial == serial) covered &= ~stages;
    }

    public void gpuWrite(Buf b, int stages) {
        b.gpuWrites++;
        use(b, stages);
        b.lastGpuWrite = b.lastUse;
        b.lastMainWrite = b.lastUse;
        b.pendingWrite = true;
    }

    private void hoistedWrite(Buf b) {
        b.gpuWrites++;
        final long serial = gl.stream.currentSerial();
        b.lastUse = serial;
        b.lastGpuWrite = serial;
        b.pendingStages |= VK_PIPELINE_STAGE_TRANSFER_BIT;
        b.pendingWrite = true;
        uploadCovered &= ~VK_PIPELINE_STAGE_TRANSFER_BIT;
    }

    @Override protected boolean canHoistWrite(Buf b) {
        return gl.stream.canHoist(SystemProperties.NATIVE_EARLY_UPLOADS ? b.lastMainWrite : b.lastUse);
    }

    private void orderBeforeTransfer(VkCommandBuffer cmd, Buf a, Buf b) {
        final long serial = gl.stream.currentSerial();
        if (coveredSerial != serial) {
            coveredSerial = serial;
            covered = uploadCovered = 0;
        }
        final boolean upload = gl.stream.isUploadCmd(cmd);
        // The upload buffer runs ahead of the main one, so a barrier there leaves the pending stages for the main buffer to order too.
        final int need = (upload ? pendingStages(a) | pendingStages(b) : takePendingStages(a) | takePendingStages(b)) & ~(upload ? uploadCovered : covered);
        if (need != 0) {
            CommandStream.memoryBarrier(cmd, need, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            if (upload) uploadCovered |= need;
            else covered |= need;
        }
    }

    private int pendingStages(Buf b) {
        if (b == null) return 0;
        if (!b.pendingWrite && gl.stream.isIdle(b.lastUse)) b.pendingStages = 0;
        return b.pendingStages;
    }

    private int takePendingStages(Buf b) {
        if (b == null) return 0;
        final int stages = pendingStages(b);
        b.pendingStages = 0;
        b.pendingWrite = false;
        return stages;
    }

    @Override protected void writeThroughCommands(Buf b, long offset, long size, long src) {
        final HostRing ring = gl.stagingRing;
        if (canHoistWrite(b)) {
            ring.alloc(size, 16);
            MemoryUtil.memCopy(src, ring.address, size);
            final VkCommandBuffer upload = gl.stream.uploadCmd();
            orderBeforeTransfer(upload, b, null);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdCopyBuffer(upload, ring.buffer, b.handle(), VkBufferCopy.calloc(1, stack).srcOffset(ring.offset).dstOffset(offset).size(size));
            }
            hoistedWrite(b);
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
        if (!canCopy(src, dst, srcOffset, dstOffset, size)) return;
        if (src != dst && canHoistWrite(dst) && gl.stream.canHoist(src.lastMainWrite) && (SystemProperties.NATIVE_EARLY_UPLOADS || gl.stream.canHoist(src.lastGpuWrite))) {
            final VkCommandBuffer upload = gl.stream.uploadCmd();
            orderBeforeTransfer(upload, src, dst);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdCopyBuffer(upload, src.handle(), dst.handle(), VkBufferCopy.calloc(1, stack).srcOffset(srcOffset).dstOffset(dstOffset).size(size));
            }
            use(src, VK_PIPELINE_STAGE_TRANSFER_BIT);
            hoistedWrite(dst);
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
        if (!canFill(b, offset, size)) return;
        if (((offset | size) & 3L) != 0L) {
            writePattern(b, offset, size, value);
            return;
        }
        gl.renderer.endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        orderBeforeTransfer(cmd, b, null);
        vkCmdFillBuffer(cmd, b.handle(), offset, size, value);
        gpuWrite(b, VK_PIPELINE_STAGE_TRANSFER_BIT);
        gl.renderer.pendingTransferBarrier = true;
    }

    @Override public void read(Buf b, long offset, long size, long dst) {
        if (b == null || b.alloc == null || size <= 0) return;
        if (b.hostVisible()) {
            // Device reads leave host-visible memory as it is, so only a pending GPU write must be waited for.
            if (b.lastGpuWrite > gl.stream.completedSerial()) gl.flushAndWait(b.lastGpuWrite);
            MemoryUtil.memCopy(b.address() + offset, dst, size);
            return;
        }
        if (b.lastUse > gl.stream.completedSerial()) gl.flushAndWait(b.lastUse);
        final VkBufferAlloc staging = VkBufferAlloc.create(gl.gpu, gl.allocator, size, HostRing.USAGE, BuffersBase.USAGE_READBACK);
        try {
            gl.renderer.endPass();
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

    @Override protected Buf newBuf(int id) {
        return new Buf(id);
    }

}
