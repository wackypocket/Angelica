package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.vulkan.memory.VkBufferAlloc;

import java.nio.ByteBuffer;

public final class Buf {
    public final int id;
    public long size;
    public int usageHint;
    public int storageFlags;
    public boolean immutable;
    public int memoryUsage = -1;

    public VkBufferAlloc alloc;
    public long lastUse;

    // mapping
    public boolean mapped;
    public long mapOffset, mapLength;
    public int mapAccess;
    public ByteBuffer mapView;
    public ByteBuffer mapStaging;
    public boolean persistentlyMapped;

    public String label;

    public Buf(int id) {
        this.id = id;
    }

    public long handle() {
        return alloc == null ? 0L : alloc.buffer;
    }

    public long address() {
        return alloc == null ? 0L : alloc.alloc.mapped;
    }

    public boolean hostVisible() {
        return alloc != null && alloc.alloc.mapped != 0L;
    }
}
