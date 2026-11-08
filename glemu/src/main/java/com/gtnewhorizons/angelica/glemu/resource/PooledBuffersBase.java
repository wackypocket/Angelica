package com.gtnewhorizons.angelica.glemu.resource;

import com.gtnewhorizons.angelica.glemu.frame.SerialTracker;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** Buffers whose storage comes from a pool, renamed to a fresh allocation rather than waited on while the GPU still reads them. */
public abstract class PooledBuffersBase<B extends AllocBuf<A>, A> extends BuffersBase<B> {
    protected final GlBase<?, ?, B> base;
    protected final BufferPool<A> pool;
    protected final BufferPool.Storage<A> storage;
    private static final long RENAME_COPY_LIMIT = 64L << 10;

    public long renames, inlineCopies, hoistedCopies;

    protected PooledBuffersBase(GlBase<?, ?, B> base, SerialTracker stream, BufferPool.Storage<A> storage) {
        this.base = base;
        this.storage = storage;
        this.pool = new BufferPool<>(stream, storage);
    }

    /** Puts a write to a buffer that cannot take it directly into the command stream. */
    protected abstract void writeThroughCommands(B b, long offset, long size, long src);

    /** Whether a write to this buffer can land before the frame's commands. */
    protected abstract boolean canHoistWrite(B b);

    /** A partial write to a large buffer goes through the commands instead, as renaming would copy the whole buffer. */
    private boolean shouldRename(B b, boolean whole) {
        return whole || b.size <= RENAME_COPY_LIMIT;
    }

    private void rename(B b, boolean keepContents) {
        final A fresh = pool.acquire(storage.size(b.alloc), b.memoryUsage);
        if (keepContents) MemoryUtil.memCopy(b.address(), storage.mapped(fresh), b.size);
        pool.retire(b.alloc, b.lastUse, b.memoryUsage);
        b.alloc = fresh;
        resetTracking(b);
        renames++;
        base.rendererBase().onBufferRenamed();
    }

    @Override public void write(B b, long offset, long size, long src) {
        if (b.alloc == null || size <= 0) return;
        if (offset < 0 || offset + size > b.size) {
            warnOnce("buffer write out of range on buffer " + b.id);
            recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        if (b.hostVisible()) {
            if (isIdle(b.lastUse) || b.persistentlyMapped && isIdle(b.lastGpuWrite)) {
                MemoryUtil.memCopy(src, b.address() + offset, size);
                return;
            }
            final boolean whole = offset == 0 && size == b.size;
            if (b.size <= BufferPool.RENAME_LIMIT && !b.immutable && (whole || isIdle(b.lastGpuWrite)) && shouldRename(b, whole)) {
                rename(b, !whole);
                MemoryUtil.memCopy(src, b.address() + offset, size);
                return;
            }
        }
        writeThroughCommands(b, offset, size, src);
    }

    @Override public ByteBuffer map(B b, long offset, long length, int access) {
        if (b == null || b.alloc == null || b.mapped) return null;
        if (length <= 0 || offset < 0 || offset + length > b.size) return null;
        if ((access & GL44.GL_MAP_PERSISTENT_BIT) != 0 && (!b.immutable || (b.storageFlags & GL44.GL_MAP_PERSISTENT_BIT) == 0)) return null;
        // Persistent only means staying mapped while in use: the map itself waits as any other does.
        final boolean unsync = (access & GL30.GL_MAP_UNSYNCHRONIZED_BIT) != 0;
        final boolean read = (access & GL30.GL_MAP_READ_BIT) != 0;
        final boolean invalidateBuffer = (access & GL30.GL_MAP_INVALIDATE_BUFFER_BIT) != 0;
        final boolean invalidateRange = (access & GL30.GL_MAP_INVALIDATE_RANGE_BIT) != 0;
        final boolean discardOld = invalidateBuffer || invalidateRange && offset == 0 && length == b.size;
        b.mapped = true;
        b.mapOffset = offset;
        b.mapLength = length;
        b.mapAccess = access;
        if ((access & GL44.GL_MAP_PERSISTENT_BIT) != 0) b.persistentlyMapped = true;
        // Host-visible buffers map in place, renaming like write() when the GPU still reads them; others map through a host copy that unmap writes back.
        if (b.hostVisible() && (b.memoryUsage != USAGE_DEVICE || isIdle(b.lastUse))) {
            if (!unsync && !isIdle(b.lastUse)) {
                if (read) {
                    base.flushAndWait(b.lastUse);
                } else if (b.size <= BufferPool.RENAME_LIMIT && !b.immutable && (discardOld || isIdle(b.lastGpuWrite))) {
                    rename(b, !discardOld);
                } else {
                    base.flushAndWait(b.lastUse);
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

    @Override public void trim() {
        pool.trim();
    }

    @Override public void destroy() {
        for (B b : buffers.values()) {
            if (b.alloc != null) storage.destroy(b.alloc);
            if (b.mapStaging != null) MemoryUtil.memFree(b.mapStaging);
        }
        buffers.clear();
        pool.destroy();
    }

    @Override protected void warnOnce(String message) {
        base.warnOnce(message);
    }

    @Override protected void recordError(int error) {
        base.recordError(error);
    }

    @Override protected boolean isIdle(long serial) {
        return base.isIdle(serial);
    }

    @Override protected long storageSize(B b) {
        return b.alloc == null ? -1 : storage.size(b.alloc);
    }

    @Override protected void releaseStorage(B b) {
        pool.retire(b.alloc, b.lastUse, b.memoryUsage);
        b.alloc = null;
    }

    @Override protected void allocateStorage(B b, long size, int memoryUsage) {
        b.alloc = pool.acquire(size, memoryUsage);
    }
}
