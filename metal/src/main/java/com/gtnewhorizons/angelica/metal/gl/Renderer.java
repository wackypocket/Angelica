package com.gtnewhorizons.angelica.metal.gl;

import com.gtnewhorizons.angelica.glemu.gl.PipelineStamp;
import com.gtnewhorizons.angelica.glemu.gl.DynamicStateCache;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.gl.TokenRendererBase;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import com.gtnewhorizons.angelica.glemu.shader.LogicOpLowering;
import com.gtnewhorizons.angelica.glemu.shader.TokenBinding;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.frame.CommandStream;
import com.gtnewhorizons.angelica.metal.memory.MtlBuffer;
import com.gtnewhorizons.angelica.metal.pipeline.Pipelines;
import com.gtnewhorizons.angelica.metal.pipeline.VertexFormats;
import com.gtnewhorizons.angelica.metal.resource.Images;
import com.gtnewhorizons.angelica.metal.resource.Buf;
import com.gtnewhorizons.angelica.metal.resource.DefaultFramebuffer;
import com.gtnewhorizons.angelica.metal.resource.Target;
import com.gtnewhorizons.angelica.metal.resource.Tex;
import com.gtnewhorizons.angelica.metal.shader.Program;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.glemu.gl.TokenMaps.*;
import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;

public final class Renderer extends TokenRendererBase<Tex, Program, Target, Buf, MtlBuffer> {
    private static final long SET_CLEAR_DEPTH = sel("setClearDepth:"), SET_CLEAR_STENCIL = sel("setClearStencil:");
    private static final long COLOR_ATTACHMENTS = sel("colorAttachments"), OBJECT_AT = sel("objectAtIndexedSubscript:");
    private static final long DEPTH_ATTACHMENT = sel("depthAttachment"), STENCIL_ATTACHMENT = sel("stencilAttachment");
    private static final long SET_TEXTURE = sel("setTexture:"), SET_LEVEL = sel("setLevel:"), SET_SLICE = sel("setSlice:"), SET_DEPTH_PLANE = sel("setDepthPlane:");
    private static final long SET_LOAD = sel("setLoadAction:"), SET_STORE = sel("setStoreAction:");
    private static final long SET_ARRAY_LENGTH = sel("setRenderTargetArrayLength:"), SET_TARGET_WIDTH = sel("setRenderTargetWidth:"), SET_TARGET_HEIGHT = sel("setRenderTargetHeight:");
    private static final long SET_DEFAULT_SAMPLES = sel("setDefaultRasterSampleCount:"), SET_SAMPLE_POSITIONS = sel("setSamplePositions:count:");
    /** Whether the current pass has every sample at the pixel's centre. */
    private boolean passCentered;
    private static final long SET_COLOR_STORE = sel("setColorStoreAction:atIndex:"), SET_DEPTH_STORE = sel("setDepthStoreAction:"), SET_STENCIL_STORE = sel("setStencilStoreAction:");
    private static final long SET_PIPELINE = sel("setRenderPipelineState:"), SET_DEPTH_STENCIL = sel("setDepthStencilState:");
    private static final long SET_VIEWPORTS = sel("setViewports:count:"), SET_SCISSORS = sel("setScissorRects:count:");
    private static final long SET_VIEWPORT = sel("setViewport:"), SET_SCISSOR = sel("setScissorRect:"), SET_CULL = sel("setCullMode:");
    private static final long SET_VISIBILITY_BUFFER = sel("setVisibilityResultBuffer:");
    private static final long SET_WINDING = sel("setFrontFacingWinding:"), SET_DEPTH_CLIP = sel("setDepthClipMode:"), SET_FILL = sel("setTriangleFillMode:");
    private static final long SET_DEPTH_BIAS = sel("setDepthBias:slopeScale:clamp:"), SET_BLEND_COLOR = sel("setBlendColorRed:green:blue:alpha:");
    private static final long SET_STENCIL_REF = sel("setStencilFrontReferenceValue:backReferenceValue:");
    private static final long[] SET_BUFFER = {sel("setVertexBuffer:offset:atIndex:"), sel("setFragmentBuffer:offset:atIndex:"), sel("setBuffer:offset:atIndex:")};
    private static final long[] SET_BUFFER_OFFSET = {sel("setVertexBufferOffset:atIndex:"), sel("setFragmentBufferOffset:atIndex:"), sel("setBufferOffset:atIndex:")};
    private static final long[] SET_BYTES = {sel("setVertexBytes:length:atIndex:"), sel("setFragmentBytes:length:atIndex:"), sel("setBytes:length:atIndex:")};
    private static final long[] SET_STAGE_TEXTURE = {sel("setVertexTexture:atIndex:"), sel("setFragmentTexture:atIndex:"), sel("setTexture:atIndex:")};
    private static final long[] SET_SAMPLER = {sel("setVertexSamplerState:atIndex:"), sel("setFragmentSamplerState:atIndex:"), sel("setSamplerState:atIndex:")};
    private static final long SET_COMPUTE_PIPELINE = sel("setComputePipelineState:");
    private static final long SET_MIN = sel("setMinFilter:"), SET_MAG = sel("setMagFilter:"), SET_MIP = sel("setMipFilter:");
    private static final long SET_S = sel("setSAddressMode:"), SET_T = sel("setTAddressMode:"), SET_R = sel("setRAddressMode:"), SET_ANISO = sel("setMaxAnisotropy:");
    private static final long SET_LOD_MIN = sel("setLodMinClamp:"), SET_LOD_MAX = sel("setLodMaxClamp:"), SET_COMPARE = sel("setCompareFunction:");
    private static final long SET_BORDER = sel("setBorderColor:"), SET_LOD_BIAS = sel("setLodBias:"), NEW_SAMPLER = sel("newSamplerStateWithDescriptor:");

    private static final int STAGE_VERTEX = 0, STAGE_FRAGMENT = 1, STAGE_COMPUTE = 2;

    private final MetalGL gl;
    public final Pipelines pipelines;
    private final Pipelines.Desc desc = new Pipelines.Desc();

    long passEncoder;

    private long boundPipeline, boundDepthStencil;
    private final DynamicStateCache dynamicCache = new DynamicStateCache();
    private final long[][] boundBuffer = new long[3][32], boundOffset = new long[3][32];
    private final long[][] boundTexture = new long[3][128], boundSampler = new long[3][16];
    long indexBuffer, indexOffset;
    int indexType;

    private final PipelineStamp pipelineStamp = new PipelineStamp();

    private Buf dummyBuffer;

    private final long sizeScratch = MemoryUtil.nmemAlloc(32 * 4);

    private Program descVertexProgram;
    private int descVertexProgramGen, descVertexLayout = -1;
    private Object dsState;
    private long dsCached;
    private int dsPipelineGen = -1, dsDynamicGen = -1, dsTargetGen = -1, dsFboId = Integer.MIN_VALUE;
    private long constRingBuffer;
    private long constRingOffset;

    Renderer(MetalGL gl) {
        super(gl, new Target(), new Target(), new Target(), new Tex[LogicOpLowering.MAX_LOCATIONS]);
        this.gl = gl;
        this.pipelines = new Pipelines(gl);
    }

    void init() {
        createDummies(gl.textures);
        dummyBuffer = gl.buffers.internalBuffer(65536, "dummyBuffer");
        gl.stream.setBeforeEncoderEnd(this::onEncoderEnding);
    }

    public void endPass() {
        endPass(false);
    }

    void endPass(boolean frameEnd) {
        if (!passActive) return;
        finishPass(frameEnd);
        gl.stream.endEncoder();
    }

    private void onEncoderEnding() {
        if (passActive && gl.stream.encoder() == passEncoder) finishPass(false);
    }

    private void finishPass(boolean frameEnd) {
        final long enc = passEncoder;
        gl.occlusionEnding();
        for (int i = 0; i < passTarget.colorCount; i++) {
            if (passTarget.color[i] != null) call(enc, SET_COLOR_STORE, Mtl.STORE_STORE, i);
        }
        if (passTarget.depth != null) {
            final boolean drop = frameEnd && passTarget.depth == gl.defaultFb.depth;
            final int store = drop ? Mtl.STORE_DONT_CARE : Mtl.STORE_STORE;
            if (passTarget.hasDepth) call(enc, SET_DEPTH_STORE, store);
            if (passTarget.hasStencil) call(enc, SET_STENCIL_STORE, store);
            if (drop) {
                passTarget.depth.contentsUndefined = true;
                depthDiscards++;
            }
        }
        passActive = false;
        passEncoder = 0L;
    }

    private static void attach(long a, Tex tex, long view, int level, int layer) {
        call(a, SET_TEXTURE, view);
        call(a, SET_LEVEL, level);
        if (tex.is3D()) {
            call(a, SET_SLICE, 0);
            call(a, SET_DEPTH_PLANE, layer);
        } else {
            call(a, SET_SLICE, layer);
        }
        call(a, SET_STORE, Mtl.STORE_UNKNOWN);
    }

    @Override protected boolean centersSamples() {
        return gl.gpu.programmableSamplePositions;
    }

    void ensurePass(Target t) {
        ensurePass(t, false);
    }

    /**
     * With every sample at the pixel's centre, a multisampled target is drawn as GL draws with GL_MULTISAMPLE off: a pixel is covered by all its
     * samples or none, as its centre is, and each takes the centre's values.
     */
    void ensurePass(Target t, boolean centered) {
        if (sameAsPass(t) && passCentered == centered) return;
        endPass();
        materializePartialAttachments(t);
        final long serial = gl.stream.currentSerial();
        final long pass = ObjC.create("MTLRenderPassDescriptor");
        try {
            final long colors = send(pass, COLOR_ATTACHMENTS);
            for (int i = 0; i < t.colorCount; i++) {
                final Tex tex = t.color[i];
                if (tex == null) continue;
                final long a = send(colors, OBJECT_AT, i);
                attach(a, tex, gl.images.attachmentTexture(tex, gl.st().framebufferSrgb), t.colorLevel[i], t.colorLayer[i]);
                // Deferred clears become load actions; loading an undefined texture is a don't-care, which costs no tile fill.
                int load = tex.contentsUndefined ? Mtl.LOAD_DONT_CARE : Mtl.LOAD_LOAD;
                if (tex.pendingClear && (tex.clearAspects & Formats.ASPECT_COLOR) != 0) {
                    load = Mtl.LOAD_CLEAR;
                    Images.setClearColor(a, tex.format.isInteger(), tex.clearColor);
                    tex.pendingClear = false;
                    tex.needsClear = false;
                } else if (tex.needsClear) {
                    load = Mtl.LOAD_CLEAR;
                    Images.setClearColor(a, tex.format.isInteger(), ZERO);
                    tex.needsClear = false;
                }
                call(a, SET_LOAD, load);
                if (load == Mtl.LOAD_CLEAR) tex.version++;
                tex.contentsUndefined = false;
                tex.markGpuWrite(serial);
            }
            if (t.depth != null) {
                final Tex tex = t.depth;
                int depthLoad = tex.contentsUndefined ? Mtl.LOAD_DONT_CARE : Mtl.LOAD_LOAD;
                int stencilLoad = depthLoad;
                double clearDepth = 1.0;
                int clearStencil = 0;
                if (tex.needsClear) {
                    depthLoad = stencilLoad = Mtl.LOAD_CLEAR;
                    tex.needsClear = false;
                }
                if (tex.pendingClear) {
                    if ((tex.clearAspects & Formats.ASPECT_DEPTH) != 0) {
                        depthLoad = Mtl.LOAD_CLEAR;
                        clearDepth = tex.clearDepth;
                    }
                    if ((tex.clearAspects & Formats.ASPECT_STENCIL) != 0) {
                        stencilLoad = Mtl.LOAD_CLEAR;
                        clearStencil = tex.clearStencil;
                    }
                    tex.pendingClear = false;
                }
                if (t.hasDepth) {
                    final long a = send(pass, DEPTH_ATTACHMENT);
                    attach(a, tex, tex.image, t.depthLevel, t.depthLayer);
                    call(a, SET_LOAD, depthLoad);
                    ObjC.callD(a, SET_CLEAR_DEPTH, clearDepth);
                }
                if (t.hasStencil) {
                    final long a = send(pass, STENCIL_ATTACHMENT);
                    attach(a, tex, tex.image, t.depthLevel, t.depthLayer);
                    call(a, SET_LOAD, stencilLoad);
                    call(a, SET_CLEAR_STENCIL, clearStencil);
                }
                if (depthLoad == Mtl.LOAD_CLEAR || stencilLoad == Mtl.LOAD_CLEAR) tex.version++;
                tex.contentsUndefined = false;
                tex.markGpuWrite(serial);
            }
            // Set even for one layer: a vertex shader writing gl_Layer is refused by validation in a pass of length zero.
            call(pass, SET_ARRAY_LENGTH, Math.max(1, layerCount(t)));
            // A pass with no attachments (rasterizer discard, transform-feedback style draws) still needs an explicit size.
            if (t.colorCount == 0 && t.depth == null) {
                call(pass, SET_TARGET_WIDTH, Math.max(1, t.width));
                call(pass, SET_TARGET_HEIGHT, Math.max(1, t.height));
                call(pass, SET_DEFAULT_SAMPLES, 1);
            }
            call(pass, SET_VISIBILITY_BUFFER, gl.occlusionBuffer());
            if (centered) {
                final int samples = targetSamples(t);
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    final java.nio.FloatBuffer positions = stack.mallocFloat(samples * 2);
                    for (int i = 0; i < samples * 2; i++) positions.put(i, 0.5f);
                    call(pass, SET_SAMPLE_POSITIONS, org.lwjgl.system.MemoryUtil.memAddress(positions), samples);
                }
            }
            passCentered = centered;
            passEncoder = gl.stream.render(pass);
        } finally {
            ObjC.release(pass);
        }
        copyTarget(t, passTarget);
        passActive = true;
        gl.occlusionBegan();
        passes++;
        passEpoch++;
        resetBound();
    }

    private void forgetBindings(int stage) {
        Arrays.fill(boundBuffer[stage], 0L);
        Arrays.fill(boundOffset[stage], -1L);
        Arrays.fill(boundTexture[stage], 0L);
        Arrays.fill(boundSampler[stage], 0L);
    }

    private void resetBound() {
        boundPipeline = 0L;
        boundDepthStencil = 0L;
        dynamicCache.invalidate();
        for (int s = 0; s < STAGE_COMPUTE; s++) forgetBindings(s);
    }

    @Override protected void drawClear(GlState st, Target t, boolean[] color, boolean depth, boolean stencil) {
        gl.clearPass.clear(st, t, color, depth, stencil);
    }

    boolean prepareDraw(GlState st, int glMode, int primitive) {
        if (st.conditionalSkip) return false;
        gl.syncBeforeDraw(st);
        gl.applyPointSize(st, glMode);
        gl.applySampleCount(st);
        Program prog = drawProgram(st);
        if (prog == null) return false;
        final Target t = resolveDrawTarget(st);
        final int topologyClass = topologyClass(primitive);
        if (!canDraw(st, t, topologyClass)) return false;
        logicDst = null;
        if (st.logicOpEnabled) prog = logicOpProgram(st, prog, t, targetSamples(t));
        else prog = sampleRateProgram(st, prog, targetSamples(t));

        collect(st, prog, t);
        if (pendingComputeBarrier) {
            endPass();
            pendingComputeBarrier = false;
        }
        ensurePass(t, centersSamples() && targetSamples(t) > 1 && !st.multisample);
        final long enc = passEncoder;

        final Vao vao = st.vao;
        final long stampExtra = st.rasterizerDiscard ? 1L : 0L;
        long pipeline = pipelineStamp.get(prog, st, vao, glMode, t, stampExtra);
        if (pipeline == 0L) {
            fillDesc(st, prog, vao, t, topologyClass);
            desc.encode(pipelines.probe);
            pipeline = pipelines.get(desc);
            if (pipeline == 0L) return false;
            pipelineStamp.set(prog, st, vao, glMode, t, stampExtra, pipeline);
        }
        if (pipeline != boundPipeline) {
            call(enc, SET_PIPELINE, pipeline);
            boundPipeline = pipeline;
        }
        final long ds;
        if (st == dsState && st.pipelineGen == dsPipelineGen && st.dynamicGen == dsDynamicGen && t.globalGen == dsTargetGen && t.fboId == dsFboId) {
            ds = dsCached;
        } else {
            ds = dsCached = pipelines.depthStencil(depthStencilKey(st, t));
            dsState = st;
            dsPipelineGen = st.pipelineGen;
            dsDynamicGen = st.dynamicGen;
            dsTargetGen = t.globalGen;
            dsFboId = t.fboId;
        }
        if (ds != boundDepthStencil) {
            call(enc, SET_DEPTH_STENCIL, ds);
            boundDepthStencil = ds;
        }
        applyDynamicState(enc, st, t, topologyClass);
        bind(prog, Program.VS, STAGE_VERTEX, enc);
        if (prog.functions[Program.FS] != 0L && !st.rasterizerDiscard) bind(prog, Program.FS, STAGE_FRAGMENT, enc);
        bindVertexBuffers(enc, st, prog, vao);
        if (prog.writesStorage) gl.stream.markStorageWrites();
        markAttachmentWrites(st, t);
        draws++;
        return true;
    }

    private void fillDesc(GlState st, Program prog, Vao vao, Target t, int topologyClass) {
        final Pipelines.Desc d = desc;
        fillAttachmentDesc(d, st, prog, t, topologyClass);

        if (prog == descVertexProgram && prog.gen == descVertexProgramGen && vao.layoutId() == descVertexLayout) return;
        descVertexProgram = prog;
        descVertexProgramGen = prog.gen;
        descVertexLayout = vao.layoutId();
        fillVertexDesc(d, prog, vao);
    }

    @Override protected boolean vertexFormatExact(int type, int size, boolean normalized) {
        return VertexFormats.exact(type, normalized);
    }

    private boolean arraySet;
    private final double[] viewportValues = new double[6];
    private final int[] scissorValues = new int[4];

    /** Sets every viewport and scissor for a shader that writes gl_ViewportIndex; the dynamic-state cache does not see these, so it forgets its viewport once the array is dropped. */
    private void applyViewportArray(long enc, GlState st, Target t) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final long viewports = stack.nmalloc(8, GlState.MAX_VIEWPORTS * 48), scissors = stack.nmalloc(8, GlState.MAX_VIEWPORTS * 32);
            for (int i = 0; i < GlState.MAX_VIEWPORTS; i++) {
                gl.viewportAt(st, i, viewportValues);
                for (int v = 0; v < 6; v++) MemoryUtil.memPutDouble(viewports + i * 48L + v * 8L, viewportValues[v]);
                gl.scissorAt(st, i, t.width, t.height, scissorValues);
                for (int v = 0; v < 4; v++) MemoryUtil.memPutLong(scissors + i * 32L + v * 8L, scissorValues[v]);
            }
            call(enc, SET_VIEWPORTS, viewports, (long) GlState.MAX_VIEWPORTS);
            call(enc, SET_SCISSORS, scissors, (long) GlState.MAX_VIEWPORTS);
        }
        arraySet = true;
    }

    private void applyDynamicState(long enc, GlState st, Target t, int topologyClass) {
        final DynamicStateCache c = dynamicCache;
        final Program drawing = gl.programs.current(st);
        final boolean array = drawing != null && drawing.writesViewportIndex;
        if (array) {
            applyViewportArray(enc, st, t);
        } else if (arraySet) {
            arraySet = false;
            c.forgetViewports();
        }
        if (c.upToDate(st, st.pipelineGen, t.globalGen, t.fboId, topologyClass)) return;

        final double near = Math.max(0, Math.min(1, st.depthNear)), far = Math.max(0, Math.min(1, st.depthFar));
        if (!array && c.viewport(st.vpX, st.vpY, st.vpW, st.vpH, near, far)) ObjC.callViewport(enc, SET_VIEWPORT, st.vpX, st.vpY, st.vpW, st.vpH, near, far);

        final int sx, sy, sw, sh;
        if (st.scissorTest) {
            // Metal wants the rect inside the target, where GL takes a scissor anywhere.
            sx = Math.min(Math.max(0, st.scX), t.width);
            sy = Math.min(Math.max(0, st.scY), t.height);
            sw = Math.max(sx, Math.min(t.width, st.scX + st.scW)) - sx;
            sh = Math.max(sy, Math.min(t.height, st.scY + st.scH)) - sy;
        } else {
            sx = sy = 0;
            sw = t.width;
            sh = t.height;
        }
        if (!array && c.scissor(sx, sy, sw, sh)) ObjC.callRect(enc, SET_SCISSOR, sx, sy, sw, sh);

        final int cull = !st.cull ? Mtl.CULL_NONE : st.cullFace == GL11.GL_FRONT ? Mtl.CULL_FRONT : Mtl.CULL_BACK;
        if (c.cull(cull)) call(enc, SET_CULL, cull);
        final int winding = st.frontFace == GL11.GL_CCW == ((st.clipState & 1) == 0) ? Mtl.WINDING_CLOCKWISE : Mtl.WINDING_COUNTER_CLOCKWISE;
        if (c.winding(winding)) call(enc, SET_WINDING, winding);
        final int clip = st.depthClamp ? Mtl.DEPTH_CLAMP : Mtl.DEPTH_CLIP;
        if (c.depthClip(clip)) call(enc, SET_DEPTH_CLIP, clip);
        final boolean lines = st.polygonModeFront == GL11.GL_LINE;
        final int fill = lines ? Mtl.FILL_LINES : Mtl.FILL_FILL;
        if (c.fill(fill)) call(enc, SET_FILL, fill);

        final boolean offset = polygonOffsetOn(st, topologyClass, lines);
        final float units = offset ? st.polyUnits : 0f, factor = offset ? st.polyFactor : 0f, clamp = offset ? st.polyClamp : 0f;
        if (c.depthBias(units, factor, clamp)) ObjC.callF(enc, SET_DEPTH_BIAS, units, factor, clamp);
        final float[] bl = st.blendColor;
        if (c.blendColor(bl)) ObjC.callF(enc, SET_BLEND_COLOR, bl[0], bl[1], bl[2], bl[3]);
        final int refF = st.stencilRef[0] & 0xFF, refB = st.stencilRef[1] & 0xFF;
        if (c.stencilReference(refF, refB)) call(enc, SET_STENCIL_REF, refF, refB);
    }

    @Override protected List<Program.Binding> bindingsOf(Program prog) {
        return prog.bindings;
    }

    @Override protected Buf dummyBufferFor(boolean writes) {
        return dummyBuffer;
    }

    @Override protected void resolveUniformBuffer(int bi, Buf buf, long offset) {
        gl.buffers.use(buf);
        resBuffer[bi] = buf.handle();
        resOffset[bi] = offset;
    }

    @Override protected boolean writesStorage(TokenBinding b, Program.BufferBlock block) {
        return !block.readOnly;
    }

    @Override protected Buf resolveStorageBuffer(int bi, Buf buf, boolean writes) {
        if (writes) gl.buffers.gpuWrite(buf);
        else gl.buffers.use(buf);
        return buf;
    }

    @Override protected long storageView(GlState st, int unit, TokenBinding b) {
        return gl.textures.storageImageView(st, unit, ((Program.Binding) b).writes);
    }

    private void bind(Program prog, int slot, int stage, long enc) {
        final int n = prog.bindings.size();
        int sizeEntries = 0;
        final boolean sizes = prog.sizeBuffer[slot] >= 0;
        for (int bi = 0; bi < n; bi++) {
            final Program.Binding b = prog.bindings.get(bi);
            final int bufferIndex = b.mtlBuffer[slot];
            if (bufferIndex >= 0) {
                setBuffer(enc, stage, bufferIndex, resBuffer[bi], resOffset[bi]);
                if (sizes && b.kind == Program.BindingKind.STORAGE_BUFFER) {
                    while (sizeEntries <= bufferIndex) MemoryUtil.memPutInt(sizeScratch + 4L * sizeEntries++, 0);
                    MemoryUtil.memPutInt(sizeScratch + 4L * bufferIndex, (int) Math.min(Integer.MAX_VALUE, resSize[bi]));
                }
            }
            final int textureIndex = b.mtlTexture[slot];
            if (textureIndex >= 0 && resTexture[bi] != null) {
                for (int e = 0; e < b.count; e++) setTexture(enc, stage, textureIndex + e, resTexture[bi][e]);
            }
            final int samplerIndex = b.mtlSampler[slot];
            if (samplerIndex >= 0 && resSampler[bi] != null) {
                for (int e = 0; e < b.count; e++) setSampler(enc, stage, samplerIndex + e, resSampler[bi][e]);
            }
        }
        if (sizes) {
            call(enc, SET_BYTES[stage], sizeScratch, Math.max(4, sizeEntries * 4L), prog.sizeBuffer[slot]);
            boundBuffer[stage][prog.sizeBuffer[slot]] = 0L;
            boundOffset[stage][prog.sizeBuffer[slot]] = -1L;
        }
    }

    private void setBuffer(long enc, int stage, int index, long buffer, long offset) {
        if (boundBuffer[stage][index] == buffer) {
            if (boundOffset[stage][index] == offset) return;
            call(enc, SET_BUFFER_OFFSET[stage], offset, index);
        } else {
            call(enc, SET_BUFFER[stage], buffer, offset, index);
            boundBuffer[stage][index] = buffer;
        }
        boundOffset[stage][index] = offset;
        bindCalls++;
    }

    private void setTexture(long enc, int stage, int index, long texture) {
        if (boundTexture[stage][index] == texture) return;
        call(enc, SET_STAGE_TEXTURE[stage], texture, index);
        boundTexture[stage][index] = texture;
        bindCalls++;
    }

    private void setSampler(long enc, int stage, int index, long sampler) {
        if (sampler == 0L || boundSampler[stage][index] == sampler) return;
        call(enc, SET_SAMPLER[stage], sampler, index);
        boundSampler[stage][index] = sampler;
        bindCalls++;
    }

    @Override protected void prepareSampledView(Tex tex) {
        if (tex.samplesStencil() && tex.format.isDepth() && tex.format.isStencil()) gl.textures.ensureUsage(tex, Mtl.USAGE_PIXEL_FORMAT_VIEW);
    }

    @Override protected long createSampledView(Tex tex, int viewType) {
        return gl.images.createSampledView(tex, viewType);
    }

    @Override protected void releaseView(long view) {
        ObjC.release(view);
    }

    @Override protected long createSampler(SamplerState s) {
        final long d = ObjC.create("MTLSamplerDescriptor");
        try {
            call(d, SET_MIN, s.minFilter());
            call(d, SET_MAG, s.magFilter());
            call(d, SET_MIP, s.mipMode());
            call(d, SET_S, s.wrapS());
            call(d, SET_T, s.wrapT());
            call(d, SET_R, s.wrapR());
            if (s.mip() && s.maxAniso() > 1 && s.linearOk()) call(d, SET_ANISO, s.maxAniso());
            ObjC.callF(d, SET_LOD_MIN, s.mip() ? Math.max(0f, s.minLod()) : 0f);
            ObjC.callF(d, SET_LOD_MAX, s.mip() ? Math.max(Math.max(0f, s.minLod()), Math.min(s.maxLod(), 1000f)) : 0f);
            if (s.compare()) call(d, SET_COMPARE, s.compareOp());
            call(d, SET_BORDER, borderColor(s.border()));
            if (s.lodBias() != 0f) {
                if (gl.gpu.samplerLodBias) ObjC.callF(d, SET_LOD_BIAS, s.lodBias());
                else gl.warnOnce("GL_TEXTURE_LOD_BIAS needs macOS 26 on Metal");
            }
            return send(gl.gpu.device, NEW_SAMPLER, d);
        } finally {
            ObjC.release(d);
        }
    }

    private int borderColor(float[] b) {
        final boolean exact = (b[3] == 0f && b[0] == 0f && b[1] == 0f && b[2] == 0f) || (b[3] == 1f && b[0] == b[1] && b[1] == b[2] && (b[0] == 0f || b[0] == 1f));
        if (!exact) gl.warnOnce("Metal only has transparent black, opaque black and opaque white border colors; using the closest");
        final boolean opaque = b[3] >= 0.5f;
        final boolean white = b[0] >= 0.5f && b[1] >= 0.5f && b[2] >= 0.5f;
        return opaque ? (white ? Mtl.BORDER_OPAQUE_WHITE : Mtl.BORDER_OPAQUE_BLACK) : Mtl.BORDER_TRANSPARENT_BLACK;
    }

    private void bindVertexBuffers(long enc, GlState st, Program prog, Vao vao) {
        final long serial = gl.stream.currentSerial();
        int n = 0;
        int mask = prog.vertexInputMask;
        while (mask != 0) {
            final int location = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            final int loc = attribOf(prog, location);
            final Buf buf = readsArray(prog, vao, location) ? gl.buffers.get(vao.readBuffer(loc)) : null;
            // Vertex buffers occupy the buffer slots after the uniform and storage buffers; disabled arrays read a constant from the ring.
            final int index = prog.vertexBufferBase + n;
            if (buf != null && buf.alloc != null) {
                gl.buffers.use(buf);
                setBuffer(enc, STAGE_VERTEX, index, buf.handle(), vao.readOffset(loc) + wordsOffset(location));
            } else {
                if (writeConstantAttribs(st, serial, gl.uniformRing)) {
                    constRingBuffer = gl.uniformRing.buffer;
                    constRingOffset = gl.uniformRing.offset;
                }
                setBuffer(enc, STAGE_VERTEX, index, constRingBuffer, constRingOffset + constantOffset(prog, vao, location));
            }
            n++;
        }
    }

    void bindIndexBuffer(long buffer, long offset, int type) {
        indexBuffer = buffer;
        indexOffset = offset;
        indexType = type;
    }

    long encoder() {
        return passEncoder;
    }

    boolean prepareDispatch(GlState st) {
        gl.syncBeforeDraw(st);
        final Program prog = gl.programs.current(st);
        if (prog == null || !prog.linked || !prog.compute) return false;
        collect(st, prog, null);
        endPass();
        final long pipeline = pipelines.compute(prog);
        if (pipeline == 0L) return false;
        final boolean fresh = gl.stream.encoderKind() != CommandStream.COMPUTE;
        final long enc = gl.stream.compute();
        if (fresh) forgetBindings(STAGE_COMPUTE);
        call(enc, SET_COMPUTE_PIPELINE, pipeline);
        bind(prog, Program.CS, STAGE_COMPUTE, enc);
        if (prog.writesStorage) gl.stream.markStorageWrites();
        pendingComputeBarrier = false;
        return true;
    }

    @Override public void destroy() {
        endPass();
        releaseDummyTextures();
        releaseSharedImages();
        if (dummyBuffer != null && dummyBuffer.alloc != null) {
            dummyBuffer.alloc.destroy();
            dummyBuffer.alloc = null;
        }
        for (long s : samplers.values()) ObjC.release(s);
        samplers.clear();
        pipelines.destroy();
        MemoryUtil.nmemFree(sizeScratch);
    }

    @Override protected DefaultFramebuffer defaultFramebuffer() { return gl.defaultFb; }
    @Override protected Tex newTex(int id) { return new Tex(id, false); }
}
