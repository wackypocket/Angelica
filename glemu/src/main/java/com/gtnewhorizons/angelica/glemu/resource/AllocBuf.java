package com.gtnewhorizons.angelica.glemu.resource;

public abstract class AllocBuf<A> extends BufBase {
    public A alloc;

    protected AllocBuf(int id) {
        super(id);
    }

    @Override public boolean hasStorage() {
        return alloc != null;
    }
}
