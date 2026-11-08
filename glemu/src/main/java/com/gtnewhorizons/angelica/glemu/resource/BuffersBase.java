package com.gtnewhorizons.angelica.glemu.resource;

import com.gtnewhorizons.angelica.glemu.util.IdTable;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

public abstract class BuffersBase<B extends BufBase> {
    public static final int USAGE_DEVICE = 0;
    public static final int USAGE_UPLOAD = 1;
    public static final int USAGE_READBACK = 2;
    public static final int USAGE_STREAM = 3;

    protected final IdTable<B> buffers = new IdTable<>();
    protected int nextId = 1;

    protected abstract B newBuf(int id);

    protected abstract void warnOnce(String message);

    protected abstract void recordError(int error);

    protected abstract boolean isIdle(long serial);

    protected abstract long storageSize(B b);

    protected abstract void releaseStorage(B b);

    protected abstract void allocateStorage(B b, long size, int memoryUsage);

    protected abstract void resetTracking(B b);

    public abstract void write(B b, long offset, long size, long src);

    public abstract void read(B b, long offset, long size, long dst);

    public abstract ByteBuffer map(B b, long offset, long length, int access);

    public abstract void copy(B src, B dst, long srcOffset, long dstOffset, long size);

    public abstract void fill(B b, long offset, long size, int value);

    /** Whether a fill has anything to do, warning when its range is out of bounds. */
    protected final boolean canFill(B b, long offset, long size) {
        if (b == null || !b.hasStorage()) return false;
        if (offset < 0 || size < 0 || offset + size > b.size) {
            warnOnce("glClearBufferSubData out of range on buffer " + b.id);
            recordError(GL11.GL_INVALID_VALUE);
            return false;
        }
        return size != 0;
    }

    /** Whether a copy has anything to do, warning when its range is out of bounds. */
    protected final boolean canCopy(B src, B dst, long srcOffset, long dstOffset, long size) {
        if (src == null || dst == null || !src.hasStorage() || !dst.hasStorage() || size <= 0) return false;
        if (srcOffset < 0 || dstOffset < 0 || srcOffset + size > src.size || dstOffset + size > dst.size) {
            warnOnce("glCopyBufferSubData out of range on buffers " + src.id + " -> " + dst.id);
            recordError(GL11.GL_INVALID_VALUE);
            return false;
        }
        return true;
    }

    /** Writes a repeating word, each byte in its place in the word, over a range a device fill cannot take. */
    protected final void writePattern(B b, long offset, long size, int value) {
        final long pattern = MemoryUtil.nmemAlloc(size);
        try {
            for (long o = 0; o < size; o++) MemoryUtil.memPutByte(pattern + o, (byte) (value >>> ((offset + o & 3L) * 8)));
            write(b, offset, size, pattern);
        } finally {
            MemoryUtil.nmemFree(pattern);
        }
    }

    public abstract void trim();

    public abstract void destroy();

    public int gen() {
        int id = nextId++;
        while (buffers.containsKey(id)) id = nextId++;
        buffers.put(id, newBuf(id));
        return id;
    }

    public B get(int id) {
        return id == 0 ? null : buffers.get(id);
    }

    public B getOrCreate(int id) {
        if (id == 0) return null;
        B b = buffers.get(id);
        if (b == null) {
            b = newBuf(id);
            buffers.put(id, b);
            if (id >= nextId) nextId = id + 1;
        }
        return b;
    }

    public boolean exists(int id) {
        return id != 0 && buffers.containsKey(id);
    }

    public void delete(int id) {
        final B b = buffers.remove(id);
        if (b == null) return;
        if (b.mapStaging != null) {
            MemoryUtil.memFree(b.mapStaging);
            b.mapStaging = null;
        }
        b.freeIndexShadow();
        releaseStorage(b);
    }

    static int memoryUsageFor(int glUsage) {
        return switch (glUsage) {
            case GL15.GL_STREAM_DRAW, GL15.GL_DYNAMIC_DRAW -> USAGE_STREAM;
            case GL15.GL_STREAM_READ, GL15.GL_DYNAMIC_READ, GL15.GL_STATIC_READ, GL15.GL_STREAM_COPY, GL15.GL_DYNAMIC_COPY -> USAGE_READBACK;
            default -> USAGE_DEVICE;
        };
    }

    static int memoryUsageForStorage(int flags) {
        if ((flags & GL30.GL_MAP_READ_BIT) != 0) return USAGE_READBACK;
        if ((flags & (GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_DYNAMIC_STORAGE_BIT)) != 0) return USAGE_STREAM;
        return USAGE_DEVICE;
    }

    public void data(B b, long size, long data, int glUsage) {
        if (size < 0) {
            recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        b.contentVersion++;
        if (b.immutable) {
            warnOnce("glBufferData on the immutable storage of buffer " + b.id);
            recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (b.mapped) unmap(b);
        b.size = size;
        b.usageHint = glUsage;
        final int mu = memoryUsageFor(glUsage);
        // Storage the GPU may still read is replaced rather than overwritten, so in-flight draws keep the old contents.
        final boolean reuse = storageSize(b) == Math.max(size, 4) && b.memoryUsage == mu && isIdle(b.lastUse);
        if (!reuse) {
            releaseStorage(b);
            b.memoryUsage = mu;
            if (size > 0 && !allocate(b, Math.max(size, 4), mu)) return;
            resetTracking(b);
        }
        if (data != 0L && size > 0) write(b, 0, size, data);
    }

    /** GL answers a failed allocation with an error, leaving the buffer without storage. */
    private boolean allocate(B b, long size, int usage) {
        try {
            allocateStorage(b, size, usage);
            return true;
        } catch (OutOfMemoryError e) {
            warnOnce("out of memory for a buffer of " + size + " bytes");
            b.size = 0;
            recordError(GL11.GL_OUT_OF_MEMORY);
            return false;
        }
    }

    public void storage(B b, long size, long data, int flags) {
        if (size < 0) {
            recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        b.contentVersion++;
        if (b.immutable) {
            warnOnce("glBufferStorage on the immutable storage of buffer " + b.id);
            recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (b.mapped) unmap(b);
        releaseStorage(b);
        b.size = size;
        b.storageFlags = flags;
        b.immutable = true;
        b.memoryUsage = memoryUsageForStorage(flags);
        if (!allocate(b, Math.max(size, 4), b.memoryUsage)) {
            b.immutable = false;
            return;
        }
        resetTracking(b);
        if (data != 0L && size > 0) write(b, 0, size, data);
    }

    public boolean unmap(B b) {
        if (b == null || !b.mapped) return false;
        b.contentVersion++;
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

    public void flushMapped(B b, long offset, long length) {
        if (b == null || !b.mapped || b.mapStaging == null) return;
        b.contentVersion++;
        write(b, b.mapOffset + offset, length, MemoryUtil.memAddress(b.mapStaging) + offset);
    }
}
