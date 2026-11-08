package com.gtnewhorizons.angelica.metal.pipeline;

import com.gtnewhorizons.angelica.glemu.gl.DepthStencilKey;
import com.gtnewhorizons.angelica.glemu.pipeline.PipelinesBase;
import com.gtnewhorizons.angelica.glemu.pipeline.TokenDesc;
import com.gtnewhorizons.angelica.glemu.util.IntKey;
import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import com.gtnewhorizons.angelica.metal.shader.Program;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;

public final class Pipelines extends PipelinesBase<Pipelines.Desc> {
    private static final Logger LOG = LogManager.getLogger("Angelica/Metal");

    private static final long AUTORELEASE = sel("autorelease");
    private static final long SET_VERTEX_FUNCTION = sel("setVertexFunction:"), SET_FRAGMENT_FUNCTION = sel("setFragmentFunction:");
    private static final long COLOR_ATTACHMENTS = sel("colorAttachments"), OBJECT_AT = sel("objectAtIndexedSubscript:");
    private static final long SET_PIXEL_FORMAT = sel("setPixelFormat:"), SET_BLENDING = sel("setBlendingEnabled:");
    private static final long SET_SRC_RGB = sel("setSourceRGBBlendFactor:"), SET_DST_RGB = sel("setDestinationRGBBlendFactor:"), SET_RGB_OP = sel("setRgbBlendOperation:");
    private static final long SET_SRC_A = sel("setSourceAlphaBlendFactor:"), SET_DST_A = sel("setDestinationAlphaBlendFactor:"), SET_A_OP = sel("setAlphaBlendOperation:");
    private static final long SET_WRITE_MASK = sel("setWriteMask:"), SET_DEPTH_FORMAT = sel("setDepthAttachmentPixelFormat:");
    private static final long SET_STENCIL_FORMAT = sel("setStencilAttachmentPixelFormat:"), SET_TOPOLOGY = sel("setInputPrimitiveTopology:");
    private static final long SET_RASTER_SAMPLES = sel("setRasterSampleCount:");
    private static final long SET_ALPHA_TO_COVERAGE = sel("setAlphaToCoverageEnabled:"), SET_ALPHA_TO_ONE = sel("setAlphaToOneEnabled:");
    private static final long SET_VERTEX_DESCRIPTOR = sel("setVertexDescriptor:"), ATTRIBUTES = sel("attributes"), LAYOUTS = sel("layouts");
    private static final long SET_FORMAT = sel("setFormat:"), SET_OFFSET = sel("setOffset:"), SET_BUFFER_INDEX = sel("setBufferIndex:");
    private static final long SET_STRIDE = sel("setStride:"), SET_STEP_FUNCTION = sel("setStepFunction:"), SET_STEP_RATE = sel("setStepRate:");
    private static final long NEW_RENDER_PIPELINE = sel("newRenderPipelineStateWithDescriptor:error:");
    private static final long NEW_COMPUTE_PIPELINE = sel("newComputePipelineStateWithFunction:error:");
    private static final long SET_DEPTH_COMPARE = sel("setDepthCompareFunction:"), SET_DEPTH_WRITE = sel("setDepthWriteEnabled:");
    private static final long SET_FRONT_STENCIL = sel("setFrontFaceStencil:"), SET_BACK_STENCIL = sel("setBackFaceStencil:");
    private static final long SET_STENCIL_COMPARE = sel("setStencilCompareFunction:"), SET_STENCIL_FAIL = sel("setStencilFailureOperation:");
    private static final long SET_DEPTH_FAIL = sel("setDepthFailureOperation:"), SET_DEPTH_STENCIL_PASS = sel("setDepthStencilPassOperation:");
    private static final long SET_READ_MASK = sel("setReadMask:"), NEW_DEPTH_STENCIL = sel("newDepthStencilStateWithDescriptor:");
    private static final long MAX_THREADS = sel("maxTotalThreadsPerThreadgroup"), SET_RASTERIZATION = sel("setRasterizationEnabled:");

    private final MetalGL gl;
    private final Object2LongOpenHashMap<DepthStencilKey> depthStencil = new Object2LongOpenHashMap<>();

    public static final class Desc extends TokenDesc<Program> {
        public void encode(IntKey.Probe p) {
            p.reset();
            p.add(program.id);
            p.add(program.gen);
            p.add(topologyClass | rasterize << 4);
            p.add(samples | alphaToCoverage << 8 | alphaToOne << 9);
            p.add(coverageMask);
            encodeAttachments(p);
            p.add(vertexCount);
            for (int i = 0; i < vertexCount; i++) {
                p.add(vLocation[i] | vStep[i] << 8 | vRate[i] << 12);
                p.add(vFormat[i]);
                p.add(vStride[i]);
            }
            p.seal();
        }
    }

    public Pipelines(MetalGL gl) {
        super(Tracy.zoneId("mtlPipelineCreate", Tracy.COLOR_CLIENT));
        this.gl = gl;
    }

    @Override protected long createUnzoned(Desc d) {
        return buildWithBlendFallback(d, d.blend, d.colorCount, this::build, () -> gl.warnOnce("program " + d.program.id + " draws with blending its attachment formats cannot blend; blending is off for it"));
    }

    private long build(Desc d, boolean noBlend) {
        final Program prog = d.program;
        final long vertex = d.rasterize == 0 ? gl.programs.linker.vertexWithoutRasterization(prog, d.clipMask)
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
            final boolean noDepth = prog.fragmentWritesDepth && d.depthFormat == Mtl.PF_INVALID;
            final long fragment = d.coverageMask != -1 ? gl.programs.linker.fragmentWithCoverage(prog, d.coverageMask, !noDepth)
                : noDepth ? gl.programs.linker.fragmentWithoutDepth(prog) : prog.functions[Program.FS];
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
            // Metal refuses a declared class other than points for a shader writing a point size, which vertex shaders do for glPointSize.
            if (!d.program.vertexWritesPointSize || d.topologyClass == Mtl.TOPOLOGY_CLASS_POINT) call(desc, SET_TOPOLOGY, d.topologyClass);
            call(desc, SET_RASTER_SAMPLES, Math.max(1, d.samples));
            call(desc, SET_ALPHA_TO_COVERAGE, (long) d.alphaToCoverage);
            call(desc, SET_ALPHA_TO_ONE, (long) d.alphaToOne);
            final long attributes = send(vd, ATTRIBUTES), layouts = send(vd, LAYOUTS);
            for (int i = 0; i < d.vertexCount; i++) {
                final int bufferIndex = prog.vertexBufferBase + i;
                final long attr = send(attributes, OBJECT_AT, d.vLocation[i]);
                call(attr, SET_FORMAT, d.vFormat[i]);
                call(attr, SET_OFFSET, 0);
                call(attr, SET_BUFFER_INDEX, bufferIndex);
                final long layout = send(layouts, OBJECT_AT, bufferIndex);
                if (d.vStride[i] % 4 != 0) gl.warnOnce("Metal requires vertex strides to be a multiple of 4, got " + d.vStride[i]);
                call(layout, SET_STRIDE, Math.max(4, d.vStride[i]));
                // A stride of zero reads the same element for every vertex, which Metal says as a constant step.
                call(layout, SET_STEP_FUNCTION, d.vStride[i] == 0 ? 0 : d.vStep[i]);
                call(layout, SET_STEP_RATE, d.vStride[i] == 0 ? 0 : d.vRate[i]);
            }
            call(desc, SET_VERTEX_DESCRIPTOR, vd);
            final long state = ObjC.sendBlocking(gl.gpu.device, NEW_RENDER_PIPELINE, desc, error);
            if (state == 0L) {
                LOG.error("Render pipeline for program {}{} failed: {}", prog.id, noBlend ? " without blending" : "", ObjC.errorDescription(MemoryUtil.memGetAddress(error)));
                return 0L;
            }
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
        if (prog.computeFailedGen == prog.gen) return 0L;
        final long pool = ObjC.poolPush();
        final long error = MemoryUtil.nmemCalloc(1, 8);
        try {
            final long state = ObjC.sendBlocking(gl.gpu.device, NEW_COMPUTE_PIPELINE, prog.functions[Program.CS], error);
            if (state == 0L) {
                prog.computeFailedGen = prog.gen;
                LOG.error("Compute pipeline for program {} failed: {}", prog.id, ObjC.errorDescription(MemoryUtil.memGetAddress(error)));
                return 0L;
            }
            final long maxThreads = send(state, MAX_THREADS);
            if ((long) prog.localX * prog.localY * prog.localZ > maxThreads) {
                LOG.error("Compute program {} uses {}x{}x{} threads; this pipeline allows {}", prog.id, prog.localX, prog.localY, prog.localZ, maxThreads);
                ObjC.release(state);
                prog.computeFailedGen = prog.gen;
                return 0L;
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
        call(s, SET_WRITE_MASK, writeMask);
        ObjC.send(s, AUTORELEASE);
        return s;
    }

    public void destroy() {
        destroyPipelines();
        for (long s : depthStencil.values()) ObjC.release(s);
        depthStencil.clear();
    }

    @Override protected int programOf(Desc desc) {
        return desc.program.id;
    }

    @Override protected void destroyNow(long pipeline) {
        ObjC.release(pipeline);
    }

    @Override protected void destroyLater(long pipeline) {
        gl.stream.defer(() -> ObjC.release(pipeline));
    }
}
