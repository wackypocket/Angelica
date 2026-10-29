package com.gtnewhorizons.angelica.dx12.pipeline;

import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.dx12.device.Gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class PsoDesc {
    public static final int SIZE = 656;

    private static final int ROOT = 0, VS = 8, PS = 24, BLEND = 120, SAMPLE_MASK = 448, RASTER = 452, DEPTH = 496, LAYOUT = 552, STRIP_CUT = 568, TOPOLOGY = 572;
    private static final int RT_COUNT = 576, RT_FORMATS = 580, DSV_FORMAT = 612, SAMPLE = 616;
    private static final int RT_BLEND_STRIDE = 40;

    public static final int TOPOLOGY_POINT = 1, TOPOLOGY_LINE = 2, TOPOLOGY_TRIANGLE = 3;

    private final MemorySegment seg;

    public PsoDesc(Arena arena) {
        seg = arena.allocate(SIZE, 8);
        sampleMask(0xFFFFFFFF);
        sampleCount(1);
        for (int i = 0; i < 8; i++) renderTargetBlend(i, false, 2, 1, 1, 2, 1, 1, 0xF);
        depthStencil(false, false, 8, false, 0xFF, 0xFF, 1, 1, 1, 8, 1, 1, 1, 8);
        rasterizer(3, 1, false, 0, 0f, 0f, true);
        topologyType(TOPOLOGY_TRIANGLE);
    }

    public PsoDesc rootSignature(long root) {
        seg.set(JAVA_LONG, ROOT, root);
        return this;
    }

    public PsoDesc vertexShader(MemorySegment code) {
        seg.set(JAVA_LONG, VS, code.address());
        seg.set(JAVA_LONG, VS + 8, code.byteSize());
        return this;
    }

    public PsoDesc pixelShader(MemorySegment code) {
        seg.set(JAVA_LONG, PS, code == null ? 0L : code.address());
        seg.set(JAVA_LONG, PS + 8, code == null ? 0L : code.byteSize());
        return this;
    }

    public PsoDesc alphaToCoverage(boolean on) {
        seg.set(JAVA_INT, BLEND, on ? 1 : 0);
        return this;
    }

    public PsoDesc independentBlend(boolean on) {
        seg.set(JAVA_INT, BLEND + 4, on ? 1 : 0);
        return this;
    }

    public PsoDesc renderTargetBlend(int i, boolean enable, int src, int dst, int op, int srcA, int dstA, int opA, int writeMask) {
        final int o = BLEND + 8 + i * RT_BLEND_STRIDE;
        seg.set(JAVA_INT, o, enable ? 1 : 0);
        seg.set(JAVA_INT, o + 4, 0);
        seg.set(JAVA_INT, o + 8, src);
        seg.set(JAVA_INT, o + 12, dst);
        seg.set(JAVA_INT, o + 16, op);
        seg.set(JAVA_INT, o + 20, srcA);
        seg.set(JAVA_INT, o + 24, dstA);
        seg.set(JAVA_INT, o + 28, opA);
        seg.set(JAVA_INT, o + 32, 4);
        seg.set(JAVA_BYTE, o + 36, (byte) writeMask);
        return this;
    }

    public PsoDesc sampleMask(int mask) {
        seg.set(JAVA_INT, SAMPLE_MASK, mask);
        return this;
    }

    public PsoDesc rasterizer(int fillMode, int cullMode, boolean frontCcw, int depthBias, float biasClamp, float slopeBias, boolean depthClip) {
        seg.set(JAVA_INT, RASTER, fillMode);
        seg.set(JAVA_INT, RASTER + 4, cullMode);
        seg.set(JAVA_INT, RASTER + 8, frontCcw ? 1 : 0);
        seg.set(JAVA_INT, RASTER + 12, depthBias);
        seg.set(JAVA_FLOAT, RASTER + 16, biasClamp);
        seg.set(JAVA_FLOAT, RASTER + 20, slopeBias);
        seg.set(JAVA_INT, RASTER + 24, depthClip ? 1 : 0);
        return this;
    }

    public PsoDesc depthStencil(boolean depth, boolean depthWrite, int depthFunc, boolean stencil, int readMask, int writeMask,
                                int frontFail, int frontDepthFail, int frontPass, int frontFunc, int backFail, int backDepthFail, int backPass, int backFunc) {
        seg.set(JAVA_INT, DEPTH, depth ? 1 : 0);
        seg.set(JAVA_INT, DEPTH + 4, depthWrite ? 1 : 0);
        seg.set(JAVA_INT, DEPTH + 8, depthFunc);
        seg.set(JAVA_INT, DEPTH + 12, stencil ? 1 : 0);
        seg.set(JAVA_BYTE, DEPTH + 16, (byte) readMask);
        seg.set(JAVA_BYTE, DEPTH + 17, (byte) writeMask);
        seg.set(JAVA_INT, DEPTH + 20, frontFail);
        seg.set(JAVA_INT, DEPTH + 24, frontDepthFail);
        seg.set(JAVA_INT, DEPTH + 28, frontPass);
        seg.set(JAVA_INT, DEPTH + 32, frontFunc);
        seg.set(JAVA_INT, DEPTH + 36, backFail);
        seg.set(JAVA_INT, DEPTH + 40, backDepthFail);
        seg.set(JAVA_INT, DEPTH + 44, backPass);
        seg.set(JAVA_INT, DEPTH + 48, backFunc);
        return this;
    }

    public PsoDesc inputLayout(MemorySegment elements, int count) {
        seg.set(JAVA_LONG, LAYOUT, elements == null ? 0L : elements.address());
        seg.set(JAVA_INT, LAYOUT + 8, count);
        return this;
    }

    public PsoDesc stripCut(int value) {
        seg.set(JAVA_INT, STRIP_CUT, value);
        return this;
    }

    public PsoDesc topologyType(int type) {
        seg.set(JAVA_INT, TOPOLOGY, type);
        return this;
    }

    public PsoDesc renderTargets(int count, int... formats) {
        seg.set(JAVA_INT, RT_COUNT, count);
        for (int i = 0; i < 8; i++) seg.set(JAVA_INT, RT_FORMATS + i * 4, i < formats.length ? formats[i] : Dx.FMT_UNKNOWN);
        return this;
    }

    public PsoDesc depthFormat(int format) {
        seg.set(JAVA_INT, DSV_FORMAT, format);
        return this;
    }

    public PsoDesc sampleCount(int count) {
        seg.set(JAVA_INT, SAMPLE, count);
        return this;
    }

    public long address() {
        return seg.address();
    }

    public long create(Gpu gpu, Arena arena) {
        final MemorySegment out = arena.allocate(8);
        Com.check(Com.hr(gpu.device, Dx.DEV_CREATE_GRAPHICS_PSO, seg.address(), Dx.IID_PIPELINE_STATE, out.address()), "CreateGraphicsPipelineState");
        return out.get(JAVA_LONG, 0);
    }
}
