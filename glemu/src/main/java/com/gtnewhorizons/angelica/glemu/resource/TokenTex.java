package com.gtnewhorizons.angelica.glemu.resource;

/** A texture of a backend that creates images with usage flags and tracks whether their contents are defined. */
public abstract class TokenTex extends TexBase {
    public int extraUsage;
    public boolean contentsUndefined = true;

    protected TokenTex(int id, boolean renderbuffer) {
        super(id, renderbuffer);
    }

    /** Records a copy from {@code source} into this image in the submission with this serial. */
    public void markCopiedFrom(TexBase source, long serial) {
        markWritten();
        needsClear = false;
        source.lastUse = serial;
        markGpuWrite(serial);
    }

    public void markWritten() {
        contentsUndefined = false;
        version++;
    }

    @Override protected void copyImageStateTo(TexBase s) {
        super.copyImageStateTo(s);
        s.usage = usage;
        ((TokenTex) s).contentsUndefined = contentsUndefined;
    }
}
