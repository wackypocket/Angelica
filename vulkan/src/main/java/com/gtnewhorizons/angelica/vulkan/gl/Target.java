package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.vulkan.resource.Tex;

final class Target {
    final Tex[] color = new Tex[Fbo.MAX_COLOR];
    final int[] colorLevel = new int[Fbo.MAX_COLOR];
    final int[] colorLayer = new int[Fbo.MAX_COLOR];
    final int[] colorLayers = new int[Fbo.MAX_COLOR];
    final long[] colorView = new long[Fbo.MAX_COLOR];
    int colorCount;
    Tex depth;
    int depthLevel, depthLayer, depthLayers = 1;
    long depthView;
    boolean hasDepth, hasStencil;
    int width, height;
    int fboId = Integer.MIN_VALUE;
    int fboGen = -1;
    int globalGen = -1;

    void clear() {
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

    boolean contains(Tex t) {
        if (t == null) return false;
        if (depth == t) return true;
        for (int i = 0; i < colorCount; i++) if (color[i] == t) return true;
        return false;
    }
}
