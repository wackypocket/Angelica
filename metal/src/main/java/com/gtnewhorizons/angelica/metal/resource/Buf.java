package com.gtnewhorizons.angelica.metal.resource;

import com.gtnewhorizons.angelica.glemu.resource.BufBase;
import com.gtnewhorizons.angelica.metal.memory.MtlBuffer;

public final class Buf extends BufBase {
    public MtlBuffer alloc;

    public Buf(int id) {
        super(id);
    }

    @Override public boolean hasStorage() {
        return alloc != null;
    }

    @Override public long handle() {
        return alloc == null ? 0L : alloc.buffer;
    }

    @Override public long address() {
        return alloc == null ? 0L : alloc.mapped;
    }
}
