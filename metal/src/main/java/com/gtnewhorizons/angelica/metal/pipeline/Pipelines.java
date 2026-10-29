package com.gtnewhorizons.angelica.metal.pipeline;

import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import com.gtnewhorizons.angelica.metal.shader.Program;
import com.gtnewhorizons.angelica.glemu.util.IntKey;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class Pipelines {
    private static final Logger LOG = LogManager.getLogger("Angelica/Metal");

    private static final long SET_VERTEX_FUNCTION = sel("setVertexFunction:"), SET_FRAGMENT_FUNCTION = sel("setFragmentFunction:");
    private static final long COLOR_ATTACHMENTS = sel("colorAttachments"), OBJECT_AT = sel("objectAtIndexedSubscript:");
    private static final long SET_PIXEL_FORMAT = sel("setPixelFormat:"), SET_BLENDING = sel("setBlendingEnabled:");
    private static final long SET_SRC_RGB = sel("setSourceRGBBlendFactor:"), SET_DST_RGB = sel("setDestinationRGBBlendFactor:"), SET_RGB_OP = sel("setRgbBlendOperation:");
    private static final long SET_SRC_A = sel("setSourceAlphaBlendFactor:"), SET_DST_A = sel("setDestinationAlphaBlendFactor:"), SET_A_OP = sel("setAlphaBlendOperation:");
    private static final long SET_WRITE_MASK = sel("setWriteMask:"), SET_DEPTH_FORMAT = sel("setDepthAttachmentPixelFormat:");
    private static final long SET_STENCIL_FORMAT = sel("setStencilAttachmentPixelFormat:"), SET_TOPOLOGY = sel("setInputPrimitiveTopology:");
    private static final long SET_ALPHA_TO_COVERAGE = sel("setAlphaToCoverageEnabled:"), SET_RASTER_SAMPLES = sel("setRasterSampleCount:");
    private static final long SET_VERTEX_DESCRIPTOR = sel("setVertexDescriptor:"), ATTRIBUTES = sel("attributes"), LAYOUTS = sel("layouts");
    private static final long SET_FORMAT = sel("setFormat:"), SET_OFFSET = sel("setOffset:"), SET_BUFFER_INDEX = sel("setBufferIndex:");
    private static final long SET_STRIDE = sel("setStride:"), SET_STEP_FUNCTION = sel("setStepFunction:"), SET_STEP_RATE = sel("setStepRate:");
    private static final long NEW_RENDER_PIPELINE = sel("newRenderPipelineStateWithDescriptor:error:");
    private static final long NEW_COMPUTE_PIPELINE = sel("newComputePipelineStateWithFunction:error:");
    private static final long SET_DEPTH_COMPARE = sel("setDepthCompareFunction:"), SET_DEPTH_WRITE = sel("setDepthWriteEnabled:");
    private static final long SET_FRONT_STENCIL = sel("setFrontFaceStencil:"), SET_BACK_STENCIL = sel("setBackFaceStencil:");
    private static final long SET_STENCIL_COMPARE = sel("setStencilCompareFunction:"), SET_STENCIL_FAIL = sel("setStencilFailureOperation:");
    private static final long SET_DEPTH_FAIL = sel("setDepthFailureOperation:"), SET_DEPTH_STENCIL_PASS = sel("setDepthStencilPassOperation:");
    private static final long SET_READ_MASK = sel("setReadMask:"), SET_STENCIL_WRITE_MASK = sel("setWriteMask:"), NEW_DEPTH_STENCIL = sel("newDepthStencilStateWithDescriptor:");
    private static final long MAX_THREADS = sel("maxTotalThreadsPerThreadgroup"), SET_RASTERIZATION = sel("setRasterizationEnabled:");

    private final MetalGL gl;
    private IntKey[] keys = new IntKey[1024];
    private long[] values = new long[1024];
    private int size;
    private final List<Long> all = new ArrayList<>();
    private final Object2LongOpenHashMap<DepthStencilKey> depthStencil = new Object2LongOpenHashMap<>();
    public final IntKey.Probe probe = new IntKey.Probe();
    long created;

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

        public void encode(IntKey.Probe p) {
            p.reset();
            p.add(program.id);
            p.add(program.gen);
            p.add(topologyClass | alphaToCoverage << 4 | rasterize << 5);
            p.add(colorCount);
            for (int i = 0; i < colorCount; i++) {
                p.add(colorFormat[i]);
                final int b = i * 8;
                p.add(blend[b] | blend[b + 7] << 1 | blend[b + 1] << 5 | blend[b + 2] << 10 | blend[b + 3] << 15 | blend[b + 4] << 18 | blend[b + 5] << 23 | blend[b + 6] << 28);
            }
            p.add(depthFormat);
            p.add(stencilFormat);
            p.add(clipMask);
            p.add(vertexCount);
            for (int i = 0; i < vertexCount; i++) {
                p.add(vLocation[i] | vStep[i] << 8 | vRate[i] << 12);
                p.add(vFormat[i]);
                p.add(vStride[i]);
            }
            p.seal();
        }
    }

    public record DepthStencilKey(int depthCompare, boolean depthWrite, boolean stencil, int front, int back, int masks) {}

    public Pipelines(MetalGL gl) {
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

    private long build(Desc d, boolean noBlend) {
        final Program prog = d.program;
        final long vertex = d.rasterize == 0 ? gl.programs.linker.vertexWithoutRasterization(prog)
            : prog.clipMask ? gl.programs.linker.clipFunction(prog, d.clipMask) : prog.functions[Program.VS];
        if (vertex == 0L) {
            LOG.error("Program {} has no vertex function for clip mask {}", prog.id, d.clipMask);
            return 0L;
        }
        final long pool = ObjC.poolPush();
        final long desc = ObjC.create("MTLRenderPipelineDescriptor");
        final long vd = ObjC.create("MTLVertexDescriptor");
        final long error = MemoryUtil.nmemCalloc(1, 8);
        try {
            call(desc, SET_VERTEX_FUNCTION, vertex);
            final long fragment = prog.fragmentWritesDepth && d.depthFormat == Mtl.PF_INVALID ? gl.programs.linker.fragmentWithoutDepth(prog) : prog.functions[Program.FS];
            if (fragment != 0L && d.rasterize != 0) call(desc, SET_FRAGMENT_FUNCTION, fragment);
            if (d.rasterize == 0) call(desc, SET_RASTERIZATION, 0L);
            final long colors = send(desc, COLOR_ATTACHMENTS);
            for (int i = 0; i < d.colorCount; i++) {
                final long a = send(colors, OBJECT_AT, i);
                call(a, SET_PIXEL_FORMAT, d.colorFormat[i]);
                final int b = i * 8;
                final boolean blend = d.blend[b] != 0 && !noBlend;
                call(a, SET_BLENDING, blend ? 1L : 0L);
                if (blend) {
                    call(a, SET_SRC_RGB, d.blend[b + 1]);
                    call(a, SET_DST_RGB, d.blend[b + 2]);
                    call(a, SET_RGB_OP, d.blend[b + 3]);
                    call(a, SET_SRC_A, d.blend[b + 4]);
                    call(a, SET_DST_A, d.blend[b + 5]);
                    call(a, SET_A_OP, d.blend[b + 6]);
                }
                call(a, SET_WRITE_MASK, d.colorFormat[i] == Mtl.PF_INVALID ? 0 : d.blend[b + 7]);
            }
            call(desc, SET_DEPTH_FORMAT, d.depthFormat);
            call(desc, SET_STENCIL_FORMAT, d.stencilFormat);
            call(desc, SET_TOPOLOGY, d.topologyClass);
            call(desc, SET_ALPHA_TO_COVERAGE, d.alphaToCoverage);
            call(desc, SET_RASTER_SAMPLES, 1);
            final long attributes = send(vd, ATTRIBUTES), layouts = send(vd, LAYOUTS);
            for (int i = 0; i < d.vertexCount; i++) {
                final int bufferIndex = prog.vertexBufferBase + i;
                final long attr = send(attributes, OBJECT_AT, d.vLocation[i]);
                call(attr, SET_FORMAT, d.vFormat[i]);
                call(attr, SET_OFFSET, 0);
                call(attr, SET_BUFFER_INDEX, bufferIndex);
                final long layout = send(layouts, OBJECT_AT, bufferIndex);
                call(layout, SET_STRIDE, Math.max(4, d.vStride[i]));
                call(layout, SET_STEP_FUNCTION, d.vStep[i]);
                call(layout, SET_STEP_RATE, d.vRate[i]);
            }
            call(desc, SET_VERTEX_DESCRIPTOR, vd);
            final long t0 = System.nanoTime();
            final long state = send(gl.gpu.device, NEW_RENDER_PIPELINE, desc, error);
            if (state == 0L) {
                LOG.error("Render pipeline for program {}{} failed: {}", prog.id, noBlend ? " without blending" : "", ObjC.errorDescription(MemoryUtil.memGetAddress(error)));
                return 0L;
            }
            created++;
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (ms > 20) LOG.debug("Pipeline for program {} took {} ms", prog.id, ms);
            return state;
        } finally {
            MemoryUtil.nmemFree(error);
            ObjC.release(vd);
            ObjC.release(desc);
            ObjC.poolPop(pool);
        }
    }

    public long compute(Program prog) {
        if (prog.computePipeline != 0L) return prog.computePipeline;
        final long pool = ObjC.poolPush();
        final long error = MemoryUtil.nmemCalloc(1, 8);
        try {
            final long state = send(gl.gpu.device, NEW_COMPUTE_PIPELINE, prog.functions[Program.CS], error);
            if (state == 0L) {
                LOG.error("Compute pipeline for program {} failed: {}", prog.id, ObjC.errorDescription(MemoryUtil.memGetAddress(error)));
                return 0L;
            }
            final long maxThreads = send(state, MAX_THREADS);
            if ((long) prog.localX * prog.localY * prog.localZ > maxThreads) {
                LOG.error("Compute program {} uses {}x{}x{} threads; this pipeline allows {}", prog.id, prog.localX, prog.localY, prog.localZ, maxThreads);
            }
            prog.computePipeline = state;
            return state;
        } finally {
            MemoryUtil.nmemFree(error);
            ObjC.poolPop(pool);
        }
    }

    public long depthStencil(DepthStencilKey k) {
        final long cached = depthStencil.getLong(k);
        if (cached != 0L) return cached;
        final long pool = ObjC.poolPush();
        final long desc = ObjC.create("MTLDepthStencilDescriptor");
        try {
            call(desc, SET_DEPTH_COMPARE, k.depthCompare());
            call(desc, SET_DEPTH_WRITE, k.depthWrite() ? 1L : 0L);
            if (k.stencil()) {
                call(desc, SET_FRONT_STENCIL, stencil(k.front(), k.masks() & 0xFF, k.masks() >>> 8 & 0xFF));
                call(desc, SET_BACK_STENCIL, stencil(k.back(), k.masks() >>> 16 & 0xFF, k.masks() >>> 24 & 0xFF));
            }
            final long state = send(gl.gpu.device, NEW_DEPTH_STENCIL, desc);
            depthStencil.put(k, state);
            return state;
        } finally {
            ObjC.release(desc);
            ObjC.poolPop(pool);
        }
    }

    private static long stencil(int ops, int readMask, int writeMask) {
        final long s = ObjC.create("MTLStencilDescriptor");
        call(s, SET_STENCIL_FAIL, ops & 0xF);
        call(s, SET_DEPTH_STENCIL_PASS, ops >>> 4 & 0xF);
        call(s, SET_DEPTH_FAIL, ops >>> 8 & 0xF);
        call(s, SET_STENCIL_COMPARE, ops >>> 12 & 0xF);
        call(s, SET_READ_MASK, readMask);
        call(s, SET_STENCIL_WRITE_MASK, writeMask);
        ObjC.send(s, sel("autorelease"));
        return s;
    }

    public void destroy() {
        for (long p : all) ObjC.release(p);
        all.clear();
        for (long s : depthStencil.values()) ObjC.release(s);
        depthStencil.clear();
        java.util.Arrays.fill(keys, null);
        size = 0;
    }

    public int count() {
        return all.size();
    }
}
