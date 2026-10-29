package com.gtnewhorizons.angelica.glemu.resource;

import java.nio.ByteBuffer;

public abstract class BufBase {
    public final int id;
    public long size;
    public int usageHint;
    public int storageFlags;
    public boolean immutable;
    public int memoryUsage = -1;

    public long lastUse;
    public long lastGpuWrite;
    public int pendingStages;
    public boolean pendingWrite;

    public boolean mapped;
    public long mapOffset, mapLength;
    public int mapAccess;
    public ByteBuffer mapView;
    public ByteBuffer mapStaging;
    public boolean persistentlyMapped;

    public String label;

    protected BufBase(int id) {
        this.id = id;
    }

    public abstract boolean hasStorage();

    public abstract long handle();

    public abstract long address();

    public boolean hostVisible() {
        return address() != 0L;
    }
}
