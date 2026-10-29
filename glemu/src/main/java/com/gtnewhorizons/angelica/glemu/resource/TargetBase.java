package com.gtnewhorizons.angelica.glemu.resource;

import com.gtnewhorizons.angelica.glemu.resource.Fbo;

public abstract class TargetBase<T extends TexBase> {
    public final T[] color;
    public final int[] colorLevel = new int[Fbo.MAX_COLOR];
    public final int[] colorLayer = new int[Fbo.MAX_COLOR];
    public final int[] colorLayers = new int[Fbo.MAX_COLOR];
    public final long[] colorView = new long[Fbo.MAX_COLOR];
    public int colorCount;
    public T depth;
    public int depthLevel, depthLayer, depthLayers = 1;
    public long depthView;
    public boolean hasDepth, hasStencil;
    public int width, height;
    public int fboId = Integer.MIN_VALUE;
    public int fboGen = -1;
    public int globalGen = -1;

    protected TargetBase(T[] color) {
        this.color = color;
    }

    public void clear() {
        for (int i = 0; i < color.length; i++) {
            color[i] = null;
            colorView[i] = 0L;
        }
        colorCount = 0;
        depth = null;
        depthView = 0L;
        hasDepth = hasStencil = false;
        width = height = 0;
    }

    public boolean contains(T t) {
        if (t == null) return false;
        if (depth == t) return true;
        for (int i = 0; i < colorCount; i++) if (color[i] == t) return true;
        return false;
    }
}
