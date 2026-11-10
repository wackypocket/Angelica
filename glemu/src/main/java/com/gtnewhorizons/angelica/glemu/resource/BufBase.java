package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public abstract class BufBase {
    public final int id;
    /** Whether the name has been bound, which is what makes a generated name an object. */
    public boolean named;
    public long size;
    public int usageHint;
    public int storageFlags;
    public boolean immutable;
    public int memoryUsage = -1;

    public long lastUse;
    public long lastGpuWrite;
    public long lastMainWrite;
    /** Counts writes the device makes, which a copy of the contents cannot see through {@link #contentVersion}. */
    public int gpuWrites;
    /** Bumped on every write through the API, so anything holding a copy of the contents can tell it is stale. */
    public int contentVersion;
    public int pendingStages;
    public boolean pendingWrite;

    public boolean mapped;
    public long mapOffset, mapLength;
    public int mapAccess;
    public ByteBuffer mapView;
    public ByteBuffer mapStaging;
    public boolean persistentlyMapped;

    public String label;

    /** The contents as the API sees them, kept for a buffer whose indices are read for conversion while copies into it are still queued. */
    public long indexShadow;
    public long indexShadowSize;
    public int indexShadowVersion;
    public int indexShadowGpuWrites;

    protected BufBase(int id) {
        this.id = id;
    }

    public abstract boolean hasStorage();

    public abstract long handle();

    public abstract long address();

    public boolean hostVisible() {
        return address() != 0L;
    }

    public boolean indexShadowCurrent() {
        return indexShadow != 0L && indexShadowSize == size && indexShadowVersion == contentVersion && indexShadowGpuWrites == gpuWrites && !mapped && !persistentlyMapped;
    }

    public void freeIndexShadow() {
        if (indexShadow != 0L) MemoryUtil.nmemFree(indexShadow);
        indexShadow = 0L;
        indexShadowSize = 0L;
    }
}
