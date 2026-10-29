package com.gtnewhorizons.angelica.glemu.gl;

import java.util.Arrays;

public final class DynamicStateCache {
    private static final int UNSET = Integer.MIN_VALUE;

    private GlState state;
    private int dynamicGen, pipelineGen, targetGen, fboId, topologyClass;

    private final double[] viewport = new double[6];
    private final int[] scissor = new int[4];
    private final float[] depthBias = new float[3];
    private final float[] blendColor = new float[4];
    private float lineWidth;
    private int cull, winding, depthClip, fill;
    private int stencilCompareMask, stencilWriteMask, stencilReference;
    public long emitted;

    public DynamicStateCache() {
        invalidate();
    }

    public void recheck() {
        state = null;
    }

    public void invalidate() {
        recheck();
        Arrays.fill(viewport, Double.NaN);
        Arrays.fill(scissor, UNSET);
        Arrays.fill(depthBias, Float.NaN);
        Arrays.fill(blendColor, Float.NaN);
        lineWidth = Float.NaN;
        cull = winding = depthClip = fill = UNSET;
        stencilCompareMask = stencilWriteMask = stencilReference = UNSET;
    }

    public boolean upToDate(GlState st, int pipelineGen, int targetGen, int fboId, int topologyClass) {
        if (st == state && st.dynamicGen == dynamicGen && pipelineGen == this.pipelineGen && targetGen == this.targetGen
            && fboId == this.fboId && topologyClass == this.topologyClass) return true;
        state = st;
        dynamicGen = st.dynamicGen;
        this.pipelineGen = pipelineGen;
        this.targetGen = targetGen;
        this.fboId = fboId;
        this.topologyClass = topologyClass;
        return false;
    }

    public boolean viewport(double x, double y, double w, double h, double near, double far) {
        final double[] v = viewport;
        if (v[0] == x && v[1] == y && v[2] == w && v[3] == h && v[4] == near && v[5] == far) return false;
        v[0] = x; v[1] = y; v[2] = w; v[3] = h; v[4] = near; v[5] = far;
        emitted++;
        return true;
    }

    public boolean scissor(int x, int y, int w, int h) {
        final int[] s = scissor;
        if (s[0] == x && s[1] == y && s[2] == w && s[3] == h) return false;
        s[0] = x; s[1] = y; s[2] = w; s[3] = h;
        emitted++;
        return true;
    }

    public boolean depthBias(float constant, float slope, float clamp) {
        final float[] b = depthBias;
        if (b[0] == constant && b[1] == slope && b[2] == clamp) return false;
        b[0] = constant; b[1] = slope; b[2] = clamp;
        emitted++;
        return true;
    }

    public boolean blendColor(float[] c) {
        final float[] b = blendColor;
        if (b[0] == c[0] && b[1] == c[1] && b[2] == c[2] && b[3] == c[3]) return false;
        System.arraycopy(c, 0, b, 0, 4);
        emitted++;
        return true;
    }

    public boolean lineWidth(float w) {
        if (lineWidth == w) return false;
        lineWidth = w;
        emitted++;
        return true;
    }

    public boolean cull(int v) {
        if (cull == v) return false;
        cull = v;
        emitted++;
        return true;
    }

    public boolean winding(int v) {
        if (winding == v) return false;
        winding = v;
        emitted++;
        return true;
    }

    public boolean depthClip(int v) {
        if (depthClip == v) return false;
        depthClip = v;
        emitted++;
        return true;
    }

    public boolean fill(int v) {
        if (fill == v) return false;
        fill = v;
        emitted++;
        return true;
    }

    public boolean stencilCompareMask(int front, int back) {
        final int v = packStencil(front, back);
        if (stencilCompareMask == v) return false;
        stencilCompareMask = v;
        emitted++;
        return true;
    }

    public boolean stencilWriteMask(int front, int back) {
        final int v = packStencil(front, back);
        if (stencilWriteMask == v) return false;
        stencilWriteMask = v;
        emitted++;
        return true;
    }

    public boolean stencilReference(int front, int back) {
        final int v = packStencil(front, back);
        if (stencilReference == v) return false;
        stencilReference = v;
        emitted++;
        return true;
    }

    private static int packStencil(int front, int back) {
        return (front & 0xFF) | (back & 0xFF) << 8;
    }
}
