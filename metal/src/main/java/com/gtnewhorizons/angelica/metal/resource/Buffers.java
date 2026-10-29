package com.gtnewhorizons.angelica.metal.resource;

import com.gtnewhorizons.angelica.glemu.resource.BuffersBase;

import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.frame.HostRing;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import com.gtnewhorizons.angelica.metal.memory.MtlBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class Buffers extends BuffersBase<Buf> {
    public static final long WHOLE_SIZE = -1L;
    private static final long COPY = sel("copyFromBuffer:sourceOffset:toBuffer:destinationOffset:size:"), FILL = sel("fillBuffer:range:value:");

    private static final long RENAME_LIMIT = 4L << 20;
    private static final long POOL_BYTE_LIMIT = 64L << 20;

    private final MetalGL gl;
    private final Long2ObjectOpenHashMap<ArrayDeque<long[]>> pool = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<MtlBuffer> poolAllocs = new Long2ObjectOpenHashMap<>();
    private long pooledBytes;
    private long trimGeneration;
    public long renames, inlineCopies, hoistedCopies;

    public Buffers(MetalGL gl) {
        this.gl = gl;
    }

    public Buf internalBuffer(long size, String label) {
        final Buf b = new Buf(-1);
        b.size = size;
        b.memoryUsage = MtlBuffer.USAGE_STREAM;
        b.alloc = MtlBuffer.create(gl.gpu, size, b.memoryUsage);
        b.label = label;
        MemoryUtil.memSet(b.alloc.mapped, 0, size);
        return b;
    }

    private MtlBuffer acquire(long size, int memoryUsage) {
        final long key = size << 2 | memoryUsage;
        final ArrayDeque<long[]> q = pool.get(key);
        if (q != null) {
            final long[] head = q.peekFirst();
            if (head != null && gl.stream.isIdle(head[1])) {
                q.pollFirst();
                final MtlBuffer a = poolAllocs.remove(head[0]);
                pooledBytes -= a.size;
                return a;
            }
        }
        return MtlBuffer.create(gl.gpu, size, memoryUsage);
    }

    private void retire(MtlBuffer a, long lastUse, int memoryUsage) {
        if (a == null) return;
        if (a.size <= RENAME_LIMIT && memoryUsage != MtlBuffer.USAGE_DEVICE) {
            final long key = a.size << 2 | memoryUsage;
            ArrayDeque<long[]> q = pool.get(key);
            if (q == null) {
                q = new ArrayDeque<>();
                pool.put(key, q);
            }
            if (pooledBytes + a.size <= POOL_BYTE_LIMIT) {
                q.addLast(new long[] {a.buffer, lastUse, trimGeneration});
                poolAllocs.put(a.buffer, a);
                pooledBytes += a.size;
                return;
            }
        }
        destroyLater(a, lastUse);
    }

    @Override protected void resetTracking(Buf b) {
        b.lastUse = 0;
        b.lastGpuWrite = 0;
        b.pendingWrite = false;
    }

    private void destroyLater(MtlBuffer a, long lastUse) {
        gl.stream.deferAfter(Math.max(lastUse, 0), () -> {
            a.destroy();
        });
    }

    @Override public void trim() {
        final var it = pool.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            final ArrayDeque<long[]> q = it.next().getValue();
            while (!q.isEmpty() && q.peekFirst()[2] < trimGeneration) {
                final long[] e = q.pollFirst();
                final MtlBuffer a = poolAllocs.remove(e[0]);
                pooledBytes -= a.size;
                destroyLater(a, e[1]);
            }
            if (q.isEmpty()) it.remove();
        }
        trimGeneration++;
    }

    public void use(Buf b) {
        b.lastUse = gl.stream.currentSerial();
    }

    public void gpuWrite(Buf b) {
        use(b);
        b.lastGpuWrite = b.lastUse;
        b.pendingWrite = true;
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
            if (b.size <= RENAME_LIMIT && !b.immutable && b.memoryUsage != MtlBuffer.USAGE_DEVICE && (whole || gl.stream.isIdle(b.lastGpuWrite))) {
                final MtlBuffer fresh = acquire(b.alloc.size, b.memoryUsage);
                if (!whole) MemoryUtil.memCopy(b.address(), fresh.mapped, b.size);
                MemoryUtil.memCopy(src, fresh.mapped + offset, size);
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
            ObjC.call(gl.stream.uploadBlit(), COPY, ring.buffer, ring.offset, b.handle(), offset, size);
            b.lastUse = b.lastGpuWrite = gl.stream.currentSerial();
            hoistedCopies++;
            return;
        }
        gl.renderer.endPass();
        ring.alloc(size, 16);
        MemoryUtil.memCopy(src, ring.address, size);
        ObjC.call(gl.stream.blit(), COPY, ring.buffer, ring.offset, b.handle(), offset, size);
        gpuWrite(b);
        inlineCopies++;
    }

    @Override public void copy(Buf src, Buf dst, long srcOffset, long dstOffset, long size) {
        if (src == null || dst == null || src.alloc == null || dst.alloc == null || size <= 0) return;
        if (srcOffset < 0 || dstOffset < 0 || srcOffset + size > src.size || dstOffset + size > dst.size) {
            gl.warnOnce("glCopyBufferSubData out of range on buffers " + src.id + " -> " + dst.id);
            return;
        }
        if (src != dst && gl.stream.canHoist(dst.lastUse) && gl.stream.canHoist(src.lastGpuWrite)) {
            ObjC.call(gl.stream.uploadBlit(), COPY, src.handle(), srcOffset, dst.handle(), dstOffset, size);
            use(src);
            gpuWrite(dst);
            hoistedCopies++;
            return;
        }
        gl.renderer.endPass();
        ObjC.call(gl.stream.blit(), COPY, src.handle(), srcOffset, dst.handle(), dstOffset, size);
        use(src);
        gpuWrite(dst);
    }

    @Override public void fill(Buf b, long offset, long size, int value) {
        if (b == null || b.alloc == null) return;
        if (size != WHOLE_SIZE && (offset < 0 || size < 0 || offset + size > b.size)) {
            gl.warnOnce("glClearBufferSubData out of range on buffer " + b.id);
            return;
        }
        final long start = offset & ~3L;
        final long length = size == WHOLE_SIZE ? b.alloc.size - start : Math.min((size + 3) & ~3L, b.alloc.size - start);
        if (length <= 0) return;
        final int lo = value & 0xFF;
        if (value == lo * 0x01010101) {
            gl.renderer.endPass();
            ObjC.fillBuffer(gl.stream.blit(), FILL, b.handle(), start, length, lo);
            gpuWrite(b);
            return;
        }
        final long pattern = MemoryUtil.nmemAlloc(length);
        try {
            for (long o = 0; o + 4 <= length; o += 4) MemoryUtil.memPutInt(pattern + o, value);
            write(b, start, length, pattern);
        } finally {
            MemoryUtil.nmemFree(pattern);
        }
    }

    @Override public void read(Buf b, long offset, long size, long dst) {
        if (b == null || b.alloc == null || size <= 0) return;
        if (b.hostVisible()) {
            if (b.lastUse > gl.stream.completedSerial()) gl.flushAndWait(b.lastUse);
            MemoryUtil.memCopy(b.address() + offset, dst, size);
            return;
        }
        final MtlBuffer staging = MtlBuffer.create(gl.gpu, size, MtlBuffer.USAGE_READBACK);
        try {
            gl.renderer.endPass();
            ObjC.call(gl.stream.blit(), COPY, b.handle(), offset, staging.buffer, 0L, size);
            use(b);
            gl.flushAndWait(gl.stream.currentSerial());
            MemoryUtil.memCopy(staging.mapped, dst, size);
        } finally {
            staging.destroy();
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
        if (b.hostVisible() && (b.memoryUsage != MtlBuffer.USAGE_DEVICE || gl.stream.isIdle(b.lastUse))) {
            if (!unsync && !gl.stream.isIdle(b.lastUse)) {
                if (read) {
                    gl.flushAndWait(b.lastUse);
                } else if (b.size <= RENAME_LIMIT && !b.immutable && (discardOld || gl.stream.isIdle(b.lastGpuWrite))) {
                    final MtlBuffer fresh = acquire(b.alloc.size, b.memoryUsage);
                    if (!discardOld) {
                        MemoryUtil.memCopy(b.address(), fresh.mapped, b.size);
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
            if (b.alloc != null) b.alloc.destroy();
            if (b.mapStaging != null) MemoryUtil.memFree(b.mapStaging);
        }
        buffers.clear();
        for (MtlBuffer a : poolAllocs.values()) a.destroy();
        poolAllocs.clear();
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
