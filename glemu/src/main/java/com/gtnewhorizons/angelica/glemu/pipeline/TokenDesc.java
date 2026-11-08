package com.gtnewhorizons.angelica.glemu.pipeline;

import com.gtnewhorizons.angelica.glemu.util.IntKey;

/** The parts of a Metal pipeline description that come straight from GL state. */
public abstract class TokenDesc<P> {
    public P program;
    public int topologyClass;
    public int rasterize = 1;
    public int samples = 1;
    public int alphaToCoverage, alphaToOne;
    /** The samples a fragment may touch, all of them when -1. */
    public int coverageMask = -1;
    public int colorCount;
    public final int[] colorFormat = new int[8];
    public final int[] blend = new int[8 * 8];
    public int depthFormat, stencilFormat;
    public int clipMask;
    public int vertexCount;
    public final int[] vLocation = new int[32], vFormat = new int[32], vStride = new int[32], vStep = new int[32], vRate = new int[32];

    protected final void encodeAttachments(IntKey.Probe p) {
        p.add(colorCount);
        for (int i = 0; i < colorCount; i++) {
            p.add(colorFormat[i]);
            final int b = i * 8;
            p.add(blend[b] | blend[b + 7] << 1 | blend[b + 1] << 5 | blend[b + 2] << 10 | blend[b + 3] << 15 | blend[b + 4] << 18 | blend[b + 5] << 23 | blend[b + 6] << 28);
        }
        p.add(depthFormat);
        p.add(stencilFormat);
        p.add(clipMask);
    }
}
