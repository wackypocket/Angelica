package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.glemu.resource.AllocBuf;
import com.gtnewhorizons.angelica.vulkan.memory.VkBufferAlloc;

public final class Buf extends AllocBuf<VkBufferAlloc> {
    public Buf(int id) {
        super(id);
    }

    @Override public long handle() {
        return alloc == null ? 0L : alloc.buffer;
    }

    @Override public long address() {
        return alloc == null ? 0L : alloc.alloc.mapped;
    }
}
