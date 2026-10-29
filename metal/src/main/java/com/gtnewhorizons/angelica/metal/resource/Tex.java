package com.gtnewhorizons.angelica.metal.resource;

import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

public final class Tex extends TexBase {
    public int extraUsage;
    public boolean contentsUndefined = true;
    public long version;

    public final Int2LongOpenHashMap storageViews = new Int2LongOpenHashMap();

    public Tex(int id, boolean renderbuffer) {
        super(id, renderbuffer);
    }

    public void markWritten() {
        contentsUndefined = false;
        version++;
    }
}
