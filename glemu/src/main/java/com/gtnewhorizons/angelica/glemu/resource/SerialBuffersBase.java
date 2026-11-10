package com.gtnewhorizons.angelica.glemu.resource;

import com.gtnewhorizons.angelica.glemu.frame.SerialTracker;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import org.lwjgl.system.MemoryUtil;

/** Use tracking for backends whose barriers do not depend on which stages touched a buffer. */
public abstract class SerialBuffersBase<B extends AllocBuf<A>, A> extends PooledBuffersBase<B, A> {

    protected SerialBuffersBase(GlBase<?, ?, B> base, SerialTracker stream, BufferPool.Storage<A> storage) {
        super(base, stream, storage);
    }

    /** A zeroed buffer for the backend's own use, outside the GL namespace. */
    public final B internalBuffer(long size, String label) {
        final B b = newBuf(-1);
        b.size = size;
        b.memoryUsage = USAGE_STREAM;
        b.alloc = storage.create(size, b.memoryUsage);
        b.label = label;
        MemoryUtil.memSet(storage.mapped(b.alloc), 0, size);
        return b;
    }

    protected long currentSerial() {
        return base.currentSerial();
    }

    public void use(B b) {
        b.lastUse = currentSerial();
    }

    public void gpuWrite(B b) {
        b.gpuWrites++;
        use(b);
        b.lastGpuWrite = b.lastUse;
        b.lastMainWrite = b.lastUse;
        b.pendingWrite = true;
    }

    protected final void hoistedWrite(B b) {
        b.gpuWrites++;
        final long serial = currentSerial();
        b.lastUse = serial;
        b.lastGpuWrite = serial;
        b.pendingWrite = true;
    }

    @Override protected void resetTracking(B b) {
        b.gpuWrites++;
        b.lastUse = 0;
        b.lastGpuWrite = 0;
        b.lastMainWrite = 0;
        b.pendingWrite = false;
    }
}
