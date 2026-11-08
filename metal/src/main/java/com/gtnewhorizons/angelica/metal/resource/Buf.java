package com.gtnewhorizons.angelica.metal.resource;

import com.gtnewhorizons.angelica.glemu.resource.AllocBuf;
import com.gtnewhorizons.angelica.metal.memory.MtlBuffer;

public final class Buf extends AllocBuf<MtlBuffer> {
    public Buf(int id) {
        super(id);
    }

    @Override public long handle() {
        return alloc == null ? 0L : alloc.buffer;
    }

    @Override public long address() {
        return alloc == null ? 0L : alloc.mapped;
    }
}
