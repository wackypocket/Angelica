package com.gtnewhorizons.angelica.dx12.pipeline;

import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.dx12.device.Dxgi;
import com.gtnewhorizons.angelica.dx12.device.Gfx;
import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import com.gtnewhorizons.angelica.dx12.shader.Bytecode;
import com.gtnewhorizons.angelica.dx12.shader.Program;
import com.gtnewhorizons.angelica.glemu.util.IntKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class Pipelines {
    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    private static final int[] BLEND_FACTOR = {1, 2, 3, 4, 5, 6, 9, 10, 7, 8, 11, 14, 15, 14, 15, 16, 17, 18, 19};
    private static final int[] BLEND_OP = {1, 2, 3, 4, 5};
    private static final MemorySegment SEMANTIC = Arena.global().allocateFrom("TEXCOORD");

    private final Dx12GL gl;
    private IntKey[] keys = new IntKey[1024];
    private long[] values = new long[1024];
    private int size;
    private final List<Long> all = new ArrayList<>();
    public final IntKey.Probe probe = new IntKey.Probe();
    long created;

    public record DepthStencilKey(int depthCompare, boolean depthWrite, boolean stencil, int front, int back, int masks) {}

    public static final class Desc {
        public Program program;
        public int topologyClass;
        public int alphaToCoverage;
        public int rasterize = 1;
        public int colorCount;
        public final int[] colorFormat = new int[8];
        public final int[] blend = new int[8 * 8];
        public int depthFormat, stencilFormat;
        public int clipMask;
        public int vertexCount;
        public final int[] vLocation = new int[16], vFormat = new int[16], vStride = new int[16], vStep = new int[16], vRate = new int[16];
        public int cull, fillLines;
        public boolean frontCcw, depthClip = true;
        public int depthBias;
        public float depthBiasClamp, slopeBias;
        public DepthStencilKey ds = new DepthStencilKey(Gfx.COMPARE_ALWAYS, false, false, 0, 0, 0);
        public int stripCut;

        public void encode(IntKey.Probe p) {
            p.reset();
            p.add(program.id);
            p.add(program.gen);
            p.add(topologyClass | alphaToCoverage << 4 | rasterize << 5 | cull << 6 | fillLines << 8 | (frontCcw ? 1 : 0) << 9 | (depthClip ? 1 : 0) << 10 | stripCut << 11);
            p.add(colorCount);
            for (int i = 0; i < colorCount; i++) {
                p.add(colorFormat[i]);
                final int b = i * 8;
                p.add(blend[b] | blend[b + 7] << 1 | blend[b + 1] << 5 | blend[b + 2] << 10 | blend[b + 3] << 15 | blend[b + 4] << 18 | blend[b + 5] << 23 | blend[b + 6] << 28);
            }
            p.add(depthFormat);
            p.add(stencilFormat);
            p.add(clipMask);
            p.add(depthBias);
            p.add(Float.floatToRawIntBits(depthBiasClamp));
            p.add(Float.floatToRawIntBits(slopeBias));
            p.add(ds.depthCompare() | (ds.depthWrite() ? 1 : 0) << 4 | (ds.stencil() ? 1 : 0) << 5);
            if (ds.stencil()) {
                p.add(ds.front());
                p.add(ds.back());
                p.add(ds.masks());
            }
            p.add(vertexCount);
            for (int i = 0; i < vertexCount; i++) {
                p.add(vLocation[i] | vStep[i] << 8 | vRate[i] << 12);
                p.add(vFormat[i]);
            }
            p.seal();
        }
    }

    public Pipelines(Dx12GL gl) {
        this.gl = gl;
    }

    public long get(Desc d) {
        final IntKey.Probe p = probe;
        int mask = keys.length - 1;
        int i = p.hash & mask;
        while (true) {
            final IntKey k = keys[i];
            if (k == null) break;
            if (p.matches(k)) return values[i];
            i = (i + 1) & mask;
        }
        final long pipeline = create(d);
        if (size * 2 >= keys.length) {
            rehash();
            mask = keys.length - 1;
            i = p.hash & mask;
            while (keys[i] != null) i = (i + 1) & mask;
        }
        keys[i] = p.freeze();
        values[i] = pipeline;
        size++;
        if (pipeline != 0L) all.add(pipeline);
        return pipeline;
    }

    private void rehash() {
        final IntKey[] oldKeys = keys;
        final long[] oldValues = values;
        keys = new IntKey[oldKeys.length * 2];
        values = new long[oldKeys.length * 2];
        final int mask = keys.length - 1;
        for (int j = 0; j < oldKeys.length; j++) {
            final IntKey k = oldKeys[j];
            if (k == null) continue;
            int i = k.hashCode() & mask;
            while (keys[i] != null) i = (i + 1) & mask;
            keys[i] = k;
            values[i] = oldValues[j];
        }
    }

    private long create(Desc d) {
        long state = build(d, false);
        if (state == 0L) {
            boolean anyBlend = false;
            for (int i = 0; i < d.colorCount; i++) anyBlend |= d.blend[i * 8] != 0;
            if (anyBlend) {
                state = build(d, true);
                if (state != 0L) gl.warnOnce("program " + d.program.id + " draws with blending its attachment formats cannot blend; blending is off for it");
            }
        }
        return state;
    }

    private static int writeMask(int gfxMask) {
        return ((gfxMask & Gfx.WRITE_RED) != 0 ? 1 : 0) | ((gfxMask & Gfx.WRITE_GREEN) != 0 ? 2 : 0) | ((gfxMask & Gfx.WRITE_BLUE) != 0 ? 4 : 0) | ((gfxMask & Gfx.WRITE_ALPHA) != 0 ? 8 : 0);
    }

    private static int d3dCompare(int token) {
        return token + 1;
    }

    private static int d3dStencilOp(int token) {
        return token + 1;
    }

    private long build(Desc d, boolean noBlend) {
        final Program prog = d.program;
        int scaled = 0, signed = 0;
        for (int i = 0; i < d.vertexCount; i++) {
            final int loc = d.vLocation[i];
            if (loc >= 16 || prog.inputKind[loc] != 0 || !VertexFormats.isIntegerFormat(d.vFormat[i])) continue;
            scaled |= 1 << loc;
            if (VertexFormats.isSignedIntegerFormat(d.vFormat[i])) signed |= 1 << loc;
        }
        final Bytecode vs = prog.clipMask || scaled != 0 ? gl.programs.linker.vertexVariant(prog, d.clipMask, scaled, signed) : prog.code[Program.VS];
        if (vs == null) {
            LOG.error("Program {} has no vertex shader variant for this pipeline", prog.id);
            return 0L;
        }
        final Bytecode fs = d.rasterize != 0 ? prog.code[Program.FS] : null;
        try (Arena a = Arena.ofConfined()) {
            final PsoDesc pso = new PsoDesc(a).rootSignature(prog.rootLayout.root());
            pso.vertexShader(MemorySegment.ofAddress(vs.address).reinterpret(vs.size));
            pso.pixelShader(fs == null ? null : MemorySegment.ofAddress(fs.address).reinterpret(fs.size));

            final int rts = d.rasterize == 0 ? 0 : d.colorCount;
            final int[] formats = new int[rts];
            for (int i = 0; i < rts; i++) {
                formats[i] = d.colorFormat[i] == Gfx.PF_INVALID ? Dxgi.UNKNOWN : Dxgi.view(d.colorFormat[i]);
                final int b = i * 8;
                final boolean blend = d.blend[b] != 0 && !noBlend && formats[i] != Dxgi.UNKNOWN;
                pso.renderTargetBlend(i, blend, BLEND_FACTOR[d.blend[b + 1]], BLEND_FACTOR[d.blend[b + 2]], BLEND_OP[d.blend[b + 3]],
                    BLEND_FACTOR[d.blend[b + 4]], BLEND_FACTOR[d.blend[b + 5]], BLEND_OP[d.blend[b + 6]], formats[i] == Dxgi.UNKNOWN ? 0 : writeMask(d.blend[b + 7]));
            }
            pso.independentBlend(true).alphaToCoverage(d.alphaToCoverage != 0).renderTargets(rts, formats);
            final int cull = d.cull == Gfx.CULL_NONE ? 1 : d.cull == Gfx.CULL_FRONT ? 2 : 3;
            pso.rasterizer(d.fillLines != 0 ? 2 : 3, cull, d.frontCcw, d.depthBias, d.depthBiasClamp, d.slopeBias, d.depthClip);

            final DepthStencilKey k = d.ds;
            final boolean haveDepth = d.rasterize != 0 && d.depthFormat != Gfx.PF_INVALID;
            final boolean haveStencil = d.rasterize != 0 && d.stencilFormat != Gfx.PF_INVALID;
            if (haveDepth || haveStencil) {
                pso.depthFormat(Dxgi.dsv(haveDepth ? d.depthFormat : d.stencilFormat));
                final boolean stencil = k.stencil() && haveStencil;
                pso.depthStencil(haveDepth && (k.depthCompare() != Gfx.COMPARE_ALWAYS || k.depthWrite()), haveDepth && k.depthWrite(), d3dCompare(k.depthCompare()), stencil,
                    k.masks() & 0xFF, k.masks() >>> 8 & 0xFF,
                    d3dStencilOp(k.front() & 0xF), d3dStencilOp(k.front() >>> 8 & 0xF), d3dStencilOp(k.front() >>> 4 & 0xF), d3dCompare(k.front() >>> 12 & 0xF),
                    d3dStencilOp(k.back() & 0xF), d3dStencilOp(k.back() >>> 8 & 0xF), d3dStencilOp(k.back() >>> 4 & 0xF), d3dCompare(k.back() >>> 12 & 0xF));
            }
            pso.topologyType(d.topologyClass == Gfx.TOPOLOGY_CLASS_POINT ? PsoDesc.TOPOLOGY_POINT : d.topologyClass == Gfx.TOPOLOGY_CLASS_LINE ? PsoDesc.TOPOLOGY_LINE : PsoDesc.TOPOLOGY_TRIANGLE);
            pso.stripCut(d.stripCut == 1 ? 1 : d.stripCut == 2 ? 2 : 0);

            final MemorySegment elements = a.allocate(32L * Math.max(1, d.vertexCount), 8);
            for (int i = 0; i < d.vertexCount; i++) {
                final long o = 32L * i;
                elements.set(JAVA_LONG, o, SEMANTIC.address());
                elements.set(JAVA_INT, o + 8, d.vLocation[i]);
                elements.set(JAVA_INT, o + 12, Dxgi.vertex(d.vFormat[i]));
                elements.set(JAVA_INT, o + 16, i);
                elements.set(JAVA_INT, o + 20, 0);
                elements.set(JAVA_INT, o + 24, d.vStep[i] == Gfx.STEP_PER_VERTEX ? 0 : 1);
                elements.set(JAVA_INT, o + 28, d.vStep[i] == Gfx.STEP_PER_VERTEX ? 0 : d.vStep[i] == Gfx.STEP_CONSTANT ? 0 : d.vRate[i]);
            }
            pso.inputLayout(elements, d.vertexCount);

            final long t0 = System.nanoTime();
            final MemorySegment out = a.allocate(8);
            final int hr = Com.hr(gl.gpu.device, Dx.DEV_CREATE_GRAPHICS_PSO, pso.address(), Dx.IID_PIPELINE_STATE, out.address());
            if (hr < 0) {
                LOG.error("Pipeline for program {}{} failed: 0x{}", prog.id, noBlend ? " without blending" : "", Integer.toHexString(hr));
                return 0L;
            }
            created++;
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (ms > 20) LOG.debug("Pipeline for program {} took {} ms", prog.id, ms);
            return out.get(JAVA_LONG, 0);
        }
    }

    public long compute(Program prog) {
        if (prog.computePipeline != 0L) return prog.computePipeline;
        final Bytecode cs = prog.code[Program.CS];
        if (cs == null) return 0L;
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment desc = a.allocate(56, 8);
            desc.set(JAVA_LONG, 0, prog.rootLayout.root());
            desc.set(JAVA_LONG, 8, cs.address);
            desc.set(JAVA_LONG, 16, cs.size);
            final MemorySegment out = a.allocate(8);
            final int hr = Com.hr(gl.gpu.device, Dx.DEV_CREATE_COMPUTE_PSO, desc.address(), Dx.IID_PIPELINE_STATE, out.address());
            if (hr < 0) {
                LOG.error("Compute pipeline for program {} failed: 0x{}", prog.id, Integer.toHexString(hr));
                return 0L;
            }
            if ((long) prog.localX * prog.localY * prog.localZ > gl.gpu.maxComputeWorkGroupInvocations) {
                LOG.error("Compute program {} uses {}x{}x{} threads; this device allows {}", prog.id, prog.localX, prog.localY, prog.localZ, gl.gpu.maxComputeWorkGroupInvocations);
            }
            prog.computePipeline = out.get(JAVA_LONG, 0);
            return prog.computePipeline;
        }
    }

    public void destroy() {
        for (long p : all) Com.release(p);
        all.clear();
        java.util.Arrays.fill(keys, null);
        size = 0;
    }

    public int count() {
        return all.size();
    }
}
