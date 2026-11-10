package com.gtnewhorizons.angelica.metal.resource;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.resource.BufferPool;
import com.gtnewhorizons.angelica.glemu.resource.SerialBuffersBase;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.frame.HostRing;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import com.gtnewhorizons.angelica.metal.memory.MtlBuffer;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class Buffers extends SerialBuffersBase<Buf, MtlBuffer> {
    private static final long COPY = sel("copyFromBuffer:sourceOffset:toBuffer:destinationOffset:size:"), FILL = sel("fillBuffer:range:value:");

    private final MetalGL gl;

    public Buffers(MetalGL gl) {
        super(gl, gl.stream, new BufferPool.Storage<MtlBuffer>() {
            @Override public MtlBuffer create(long size, int usage) { return MtlBuffer.create(gl.gpu, size, usage); }
            @Override public void destroy(MtlBuffer alloc) { alloc.destroy(); }
            @Override public long size(MtlBuffer alloc) { return alloc.size; }
            @Override public long mapped(MtlBuffer alloc) { return alloc.mapped; }
            @Override public boolean reusable(MtlBuffer alloc, int usage) { return usage != MtlBuffer.USAGE_DEVICE || alloc.isHostVisible(); }
        });
        this.gl = gl;
    }

    @Override protected boolean canHoistWrite(Buf b) {
        return gl.stream.canHoist(SystemProperties.NATIVE_EARLY_UPLOADS ? b.lastMainWrite : b.lastUse);
    }

    /** macOS blits take offsets and sizes in multiples of 4, so an unaligned write merges with the words at its edges. */
    private void writeWidened(Buf b, long offset, long size, long src) {
        final long start = offset & ~3L, length = (offset + size - start + 3L) & ~3L;
        final long merged = MemoryUtil.nmemAlloc(length);
        try {
            read(b, start, Math.min(length, b.alloc.size - start), merged);
            MemoryUtil.memCopy(src, merged + offset - start, size);
            writeThroughCommands(b, start, length, merged);
        } finally {
            MemoryUtil.nmemFree(merged);
        }
    }

    @Override protected void writeThroughCommands(Buf b, long offset, long size, long src) {
        if (((offset | size) & 3L) != 0L) {
            writeWidened(b, offset, size, src);
            return;
        }
        final HostRing ring = gl.stagingRing;
        if (canHoistWrite(b)) {
            fillRing(ring, src, size);
            ObjC.call(gl.stream.uploadBlit(), COPY, ring.buffer, ring.offset, b.handle(), offset, size);
            hoistedWrite(b);
            hoistedCopies++;
            return;
        }
        gl.renderer.endPass();
        fillRing(ring, src, size);
        ObjC.call(gl.stream.blit(), COPY, ring.buffer, ring.offset, b.handle(), offset, size);
        gpuWrite(b);
        inlineCopies++;
    }

    private static void fillRing(HostRing ring, long src, long size) {
        ring.alloc(size, 16);
        MemoryUtil.memCopy(src, ring.address, size);
    }

    @Override public void copy(Buf src, Buf dst, long srcOffset, long dstOffset, long size) {
        if (!canCopy(src, dst, srcOffset, dstOffset, size)) return;
        if (((srcOffset | dstOffset | size) & 3L) != 0L) {
            final long data = MemoryUtil.nmemAlloc(size);
            try {
                read(src, srcOffset, size, data);
                write(dst, dstOffset, size, data);
            } finally {
                MemoryUtil.nmemFree(data);
            }
            return;
        }
        if (src != dst && canHoistWrite(dst) && gl.stream.canHoist(src.lastMainWrite) && (SystemProperties.NATIVE_EARLY_UPLOADS || gl.stream.canHoist(src.lastGpuWrite))) {
            ObjC.call(gl.stream.uploadBlit(), COPY, src.handle(), srcOffset, dst.handle(), dstOffset, size);
            use(src);
            hoistedWrite(dst);
            hoistedCopies++;
            return;
        }
        gl.renderer.endPass();
        ObjC.call(gl.stream.blit(), COPY, src.handle(), srcOffset, dst.handle(), dstOffset, size);
        use(src);
        gpuWrite(dst);
    }

    @Override public void fill(Buf b, long offset, long size, int value) {
        if (!canFill(b, offset, size)) return;
        final int lo = value & 0xFF;
        if (value == lo * 0x01010101 && ((offset | size) & 3L) == 0L) {
            gl.renderer.endPass();
            ObjC.fillBuffer(gl.stream.blit(), FILL, b.handle(), offset, size, lo);
            gpuWrite(b);
            return;
        }
        writePattern(b, offset, size, value);
    }

    @Override public void read(Buf b, long offset, long size, long dst) {
        if (b == null || b.alloc == null || size <= 0) return;
        if (b.hostVisible()) {
            // Device reads leave host-visible memory as it is, so only a pending GPU write must be waited for.
            if (b.lastGpuWrite > gl.stream.completedSerial()) gl.flushAndWait(b.lastGpuWrite);
            MemoryUtil.memCopy(b.address() + offset, dst, size);
            return;
        }
        final long start = offset & ~3L, length = (offset + size - start + 3L) & ~3L;
        final MtlBuffer staging = MtlBuffer.create(gl.gpu, length, MtlBuffer.USAGE_READBACK);
        try {
            gl.renderer.endPass();
            ObjC.call(gl.stream.blit(), COPY, b.handle(), start, staging.buffer, 0L, length);
            use(b);
            gl.flushAndWait(gl.stream.currentSerial());
            MemoryUtil.memCopy(staging.mapped + offset - start, dst, size);
        } finally {
            staging.destroy();
        }
    }

    @Override protected Buf newBuf(int id) {
        return new Buf(id);
    }

}
