package com.gtnewhorizons.angelica.vulkan.memory;

public final class Allocation {
    MemoryAllocator.Block block;
    public final long memory;
    public final long offset;
    public final long size;
    public final long mapped;

    Allocation(MemoryAllocator.Block block, long memory, long offset, long size, long mapped) {
        this.block = block;
        this.memory = memory;
        this.offset = offset;
        this.size = size;
        this.mapped = mapped;
    }

    public boolean isHostVisible() {
        return mapped != 0L;
    }

    public int memoryType() {
        return block == null ? -1 : block.type;
    }
}
