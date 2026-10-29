package com.gtnewhorizons.angelica.metal.gl;

import com.gtnewhorizons.angelica.metal.shader.Programs;

import com.gtnewhorizons.angelica.metal.resource.Textures;

import com.gtnewhorizons.angelica.glemu.gl.RendererBase;

import com.gtnewhorizons.angelica.glemu.gl.DynamicStateCache;
import com.gtnewhorizons.angelica.glemu.gl.GlState;

import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.frame.CommandStream;
import com.gtnewhorizons.angelica.metal.pipeline.Pipelines;
import com.gtnewhorizons.angelica.metal.pipeline.VertexFormats;
import com.gtnewhorizons.angelica.metal.resource.Buf;
import com.gtnewhorizons.angelica.metal.resource.DefaultFramebuffer;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.metal.resource.Images;
import com.gtnewhorizons.angelica.glemu.resource.SamplerObj;
import com.gtnewhorizons.angelica.metal.resource.Target;
import com.gtnewhorizons.angelica.metal.resource.Tex;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import com.gtnewhorizons.angelica.glemu.shader.LogicOpLowering;
import com.gtnewhorizons.angelica.metal.shader.Program;
import com.gtnewhorizons.angelica.metal.shader.ProgramLinker;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class Renderer extends RendererBase<Tex, Program, Target> {
    private static final long COLOR_ATTACHMENTS = sel("colorAttachments"), OBJECT_AT = sel("objectAtIndexedSubscript:");
    private static final long DEPTH_ATTACHMENT = sel("depthAttachment"), STENCIL_ATTACHMENT = sel("stencilAttachment");
    private static final long SET_TEXTURE = sel("setTexture:"), SET_LEVEL = sel("setLevel:"), SET_SLICE = sel("setSlice:"), SET_DEPTH_PLANE = sel("setDepthPlane:");
    private static final long SET_LOAD = sel("setLoadAction:"), SET_STORE = sel("setStoreAction:");
    private static final long SET_CLEAR_COLOR = sel("setClearColor:"), SET_CLEAR_DEPTH = sel("setClearDepth:"), SET_CLEAR_STENCIL = sel("setClearStencil:");
    private static final long SET_ARRAY_LENGTH = sel("setRenderTargetArrayLength:"), SET_TARGET_WIDTH = sel("setRenderTargetWidth:"), SET_TARGET_HEIGHT = sel("setRenderTargetHeight:");
    private static final long SET_DEFAULT_SAMPLES = sel("setDefaultRasterSampleCount:");
    private static final long SET_COLOR_STORE = sel("setColorStoreAction:atIndex:"), SET_DEPTH_STORE = sel("setDepthStoreAction:"), SET_STENCIL_STORE = sel("setStencilStoreAction:");
    private static final long SET_PIPELINE = sel("setRenderPipelineState:"), SET_DEPTH_STENCIL = sel("setDepthStencilState:");
    private static final long SET_VIEWPORT = sel("setViewport:"), SET_SCISSOR = sel("setScissorRect:"), SET_CULL = sel("setCullMode:");
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

    private long passEncoder;

    private long boundPipeline, boundDepthStencil;
    private final DynamicStateCache dynamicCache = new DynamicStateCache();
    private final long[][] boundBuffer = new long[3][32], boundOffset = new long[3][32];
    private final long[][] boundTexture = new long[3][128], boundSampler = new long[3][16];
    long indexBuffer, indexOffset;
    int indexType;

    private int lastProgram = -1, lastProgramGen = -1, lastPipelineGen = -1, lastVaoLayout = -1, lastMode = -1, lastTargetGen = -1, lastFboId = Integer.MIN_VALUE;
    private Object lastState;
    private long lastPipeline;
    private boolean lastDiscard;

    public boolean pendingTransferBarrier;
    boolean pendingComputeBarrier;

    private final Long2LongOpenHashMap samplers = new Long2LongOpenHashMap();

    public Tex dummy2D, dummy2DArray, dummy3D, dummyCube, dummyDepth, dummyStorage;
    private Buf dummyBuffer;

    public long draws, passes, depthDiscards, bindCalls, objectCreations;

    private long[] resBuffer = new long[32], resOffset = new long[32], resSize = new long[32];
    private long[][] resTexture = new long[32][], resSampler = new long[32][];
    private final long sizeScratch = MemoryUtil.nmemAlloc(32 * 4);

    Renderer(MetalGL gl) {
        super(gl, new Target(), new Target(), new Target(), new Tex[LogicOpLowering.MAX_LOCATIONS]);
        this.gl = gl;
        this.pipelines = new Pipelines(gl);
    }

    void init() {
        dummy2D = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, Formats.RGBA8, 1, 1, 1, 1, "dummy2D");
        dummy2DArray = gl.textures.internalTexture(GL30.GL_TEXTURE_2D_ARRAY, Formats.RGBA8, 1, 1, 1, 1, "dummy2DArray");
        dummy3D = gl.textures.internalTexture(GL12.GL_TEXTURE_3D, Formats.RGBA8, 1, 1, 1, 1, "dummy3D");
        dummyCube = gl.textures.internalTexture(GL13.GL_TEXTURE_CUBE_MAP, Formats.RGBA8, 1, 1, 1, 6, "dummyCube");
        dummyDepth = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, gl.textures.format(GL14.GL_DEPTH_COMPONENT24), 1, 1, 1, 1, "dummyDepth");
        dummyStorage = gl.textures.internalStorageTexture(Formats.RGBA8, "dummyStorage");
        final float[] black = {0, 0, 0, 1};
        for (Tex t : new Tex[] {dummy2D, dummy2DArray, dummy3D, dummyCube, dummyDepth, dummyStorage}) gl.images.clearImage(t, black, 1f, 0);
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

    private boolean sameAsPass(Target t) {
        return passActive && gl.stream.encoder() == passEncoder
            && passTarget.colorCount == t.colorCount && passTarget.width == t.width && passTarget.height == t.height
            && passTarget.hasDepth == t.hasDepth && passTarget.hasStencil == t.hasStencil
            && sameAttachments(t);
    }


    private static void attach(long a, Tex tex, int level, int layer) {
        call(a, SET_TEXTURE, tex.image);
        call(a, SET_LEVEL, level);
        if (tex.is3D()) {
            call(a, SET_SLICE, 0);
            call(a, SET_DEPTH_PLANE, layer);
        } else {
            call(a, SET_SLICE, layer);
        }
        call(a, SET_STORE, Mtl.STORE_UNKNOWN);
    }

    void ensurePass(Target t) {
        if (sameAsPass(t)) return;
        endPass();
        final long serial = gl.stream.currentSerial();
        final long pass = ObjC.create("MTLRenderPassDescriptor");
        try {
            final long colors = send(pass, COLOR_ATTACHMENTS);
            for (int i = 0; i < t.colorCount; i++) {
                final Tex tex = t.color[i];
                if (tex == null) continue;
                final long a = send(colors, OBJECT_AT, i);
                attach(a, tex, t.colorLevel[i], t.colorLayer[i]);
                // Deferred clears become load actions; loading an undefined texture is a don't-care, which costs no tile fill.
                int load = tex.contentsUndefined ? Mtl.LOAD_DONT_CARE : Mtl.LOAD_LOAD;
                if (tex.pendingClear && (tex.clearAspects & Formats.ASPECT_COLOR) != 0) {
                    load = Mtl.LOAD_CLEAR;
                    setClearColor(a, tex, tex.clearColor);
                    tex.pendingClear = false;
                    tex.needsClear = false;
                } else if (tex.needsClear) {
                    load = Mtl.LOAD_CLEAR;
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
                    attach(a, tex, t.depthLevel, t.depthLayer);
                    call(a, SET_LOAD, depthLoad);
                    ObjC.callD(a, SET_CLEAR_DEPTH, clearDepth);
                }
                if (t.hasStencil) {
                    final long a = send(pass, STENCIL_ATTACHMENT);
                    attach(a, tex, t.depthLevel, t.depthLayer);
                    call(a, SET_LOAD, stencilLoad);
                    call(a, SET_CLEAR_STENCIL, clearStencil);
                }
                if (depthLoad == Mtl.LOAD_CLEAR || stencilLoad == Mtl.LOAD_CLEAR) tex.version++;
                tex.contentsUndefined = false;
                tex.markGpuWrite(serial);
            }
            final int layers = layerCount(t);
            if (layers > 1) call(pass, SET_ARRAY_LENGTH, layers);
            // A pass with no attachments (rasterizer discard, transform-feedback style draws) still needs an explicit size.
            if (t.colorCount == 0 && t.depth == null) {
                call(pass, SET_TARGET_WIDTH, Math.max(1, t.width));
                call(pass, SET_TARGET_HEIGHT, Math.max(1, t.height));
                call(pass, SET_DEFAULT_SAMPLES, 1);
            }
            passEncoder = gl.stream.render(pass);
        } finally {
            ObjC.release(pass);
        }
        copyTarget(t, passTarget);
        passActive = true;
        passes++;
        passEpoch++;
        resetBound();
    }

    private void resetBound() {
        boundPipeline = 0L;
        boundDepthStencil = 0L;
        dynamicCache.invalidate();
        for (int s = 0; s < 3; s++) {
            Arrays.fill(boundBuffer[s], 0L);
            Arrays.fill(boundOffset[s], -1L);
            Arrays.fill(boundTexture[s], 0L);
            Arrays.fill(boundSampler[s], 0L);
        }
    }


    private static void setClearColor(long a, Tex tex, float[] c) {
        if (tex.format.kind() == Formats.K_UINT || tex.format.kind() == Formats.K_SINT) {
            ObjC.callClearColor(a, SET_CLEAR_COLOR, (int) c[0], (int) c[1], (int) c[2], (int) c[3]);
        } else {
            ObjC.callClearColor(a, SET_CLEAR_COLOR, c[0], c[1], c[2], c[3]);
        }
    }







    void clear(GlState st, int mask) {
        final Target t = resolveDrawTarget(st);
        if (t.width <= 0 || t.height <= 0) return;
        final boolean scissored = st.scissorTest && (st.scX > 0 || st.scY > 0 || st.scX + st.scW < t.width || st.scY + st.scH < t.height);
        if (st.scissorTest && (st.scW <= 0 || st.scH <= 0)) return;
        final boolean wantColor = (mask & GL11.GL_COLOR_BUFFER_BIT) != 0;
        final boolean wantDepth = (mask & GL11.GL_DEPTH_BUFFER_BIT) != 0 && st.depthMask && t.hasDepth;
        final boolean wantStencil = (mask & GL11.GL_STENCIL_BUFFER_BIT) != 0 && t.hasStencil && (st.stencilWriteMask[0] & 0xFF) != 0;
        final boolean stencilPartial = wantStencil && (st.stencilWriteMask[0] & 0xFF) != 0xFF;

        // Full clears outside a pass turn into the next pass's load action; everything else is drawn with the clear pass.
        final boolean inPass = sameAsPass(t);
        final boolean[] drawColor = new boolean[GlState.MAX_DRAW_BUFFERS];
        boolean anyDraw = false;
        if (wantColor) {
            for (int i = 0; i < t.colorCount; i++) {
                final Tex tex = t.color[i];
                if (tex == null) continue;
                final boolean[] cm = st.colorMask[Math.min(i, GlState.MAX_DRAW_BUFFERS - 1)];
                final boolean full = coversEveryChannel(cm, tex);
                final boolean none = !cm[0] && !cm[1] && !cm[2] && !cm[3];
                if (none) continue;
                if (!inPass && !scissored && full && canDefer(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i])) {
                    tex.pendingClear = true;
                    tex.clearAspects = Formats.ASPECT_COLOR;
                    System.arraycopy(st.clearColor, 0, tex.clearColor, 0, 4);
                } else if (tex.format.isInteger()) {
                    if (!scissored && full && canDefer(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i])) {
                        releaseFromPass(tex);
                        tex.pendingClear = true;
                        tex.clearAspects = Formats.ASPECT_COLOR;
                        System.arraycopy(st.clearColor, 0, tex.clearColor, 0, 4);
                    } else {
                        gl.warnOnce("partial clear of integer color attachment " + i + " is not emulated on Metal");
                    }
                } else {
                    drawColor[i] = true;
                    anyDraw = true;
                }
            }
        }
        boolean depthDraw = false;
        if ((wantDepth || wantStencil) && t.depth != null) {
            final Tex tex = t.depth;
            if (!inPass && !scissored && !stencilPartial && canDefer(tex, t.depthLevel, t.depthLayer, t.depthLayers)) {
                if (!tex.pendingClear) tex.clearAspects = 0;
                tex.pendingClear = true;
                if (wantDepth) {
                    tex.clearAspects |= Formats.ASPECT_DEPTH;
                    tex.clearDepth = (float) Math.max(0, Math.min(1, st.clearDepth));
                }
                if (wantStencil) {
                    tex.clearAspects |= Formats.ASPECT_STENCIL;
                    tex.clearStencil = st.clearStencil & 0xFF;
                }
            } else {
                depthDraw = true;
            }
        }
        if (!anyDraw && !depthDraw) return;
        gl.clearPass.clear(st, t, drawColor, depthDraw && wantDepth, depthDraw && wantStencil);
    }


    boolean prepareDraw(GlState st, int glMode, int primitive) {
        Program prog = gl.programs.current(st);
        if (prog == null || !prog.linked || prog.compute) {
            gl.warnOnce("draw without a linked graphics program (program " + st.program + ")");
            return false;
        }
        final Target t = resolveDrawTarget(st);
        if (t.width <= 0 || t.height <= 0) return false;
        if (st.vpW <= 0 || st.vpH <= 0) return false;
        if (st.scissorTest && (st.scW <= 0 || st.scH <= 0)) return false;
        final int topologyClass = topologyClass(primitive);
        if (st.cull && st.cullFace == GL11.GL_FRONT_AND_BACK && topologyClass == Mtl.TOPOLOGY_CLASS_TRIANGLE) return false;
        if (scissorEmpty(st, t)) return false;
        logicDst = null;
        if (st.logicOpEnabled && !gl.gpu.logicOp) prog = logicOpProgram(st, prog, t);

        if (!collect(st, prog, t, false)) return false;
        if (pendingComputeBarrier) {
            endPass();
            pendingComputeBarrier = false;
        }
        pendingTransferBarrier = false;
        ensurePass(t);
        final long enc = passEncoder;

        final Vao vao = st.vao;
        long pipeline;
        if (prog.id == lastProgram && prog.gen == lastProgramGen && st.pipelineGen == lastPipelineGen && vao.layoutId() == lastVaoLayout
            && glMode == lastMode && t.globalGen == lastTargetGen && t.fboId == lastFboId && st == lastState && st.rasterizerDiscard == lastDiscard && lastPipeline != 0L) {
            pipeline = lastPipeline;
        } else {
            fillDesc(st, prog, vao, t, topologyClass);
            desc.encode(pipelines.probe);
            pipeline = pipelines.get(desc);
            if (pipeline == 0L) return false;
            lastProgram = prog.id;
            lastProgramGen = prog.gen;
            lastPipelineGen = st.pipelineGen;
            lastVaoLayout = vao.layoutId();
            lastMode = glMode;
            lastTargetGen = t.globalGen;
            lastFboId = t.fboId;
            lastState = st;
            lastDiscard = st.rasterizerDiscard;
            lastPipeline = pipeline;
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
        if (!bindVertexBuffers(enc, st, prog, vao)) return false;
        if (prog.writesStorage) gl.stream.markStorageWrites();
        markAttachmentWrites(st, t);
        draws++;
        return true;
    }





    private Program descVertexProgram;
    private int descVertexProgramGen, descVertexLayout = -1;

    private Object dsState;
    private long dsCached;
    private int dsPipelineGen = -1, dsDynamicGen = -1, dsTargetGen = -1, dsFboId = Integer.MIN_VALUE;

    private void fillDesc(GlState st, Program prog, Vao vao, Target t, int topologyClass) {
        final Pipelines.Desc d = desc;
        d.program = prog;
        d.topologyClass = topologyClass;
        d.alphaToCoverage = 0;
        d.rasterize = st.rasterizerDiscard ? 0 : 1;
        d.colorCount = t.colorCount;
        for (int i = 0; i < t.colorCount; i++) {
            final Tex tex = t.color[i];
            d.colorFormat[i] = tex == null ? Mtl.PF_INVALID : tex.format.pf();
            final int b = i * 8;
            final int bi = Math.min(i, GlState.MAX_DRAW_BUFFERS - 1);
            final boolean integer = tex != null && tex.format.isInteger();
            d.blend[b] = st.blend[bi] && !integer && tex != null && !(st.logicOpEnabled && !gl.gpu.logicOp) ? 1 : 0;
            if (d.blend[b] != 0) {
                d.blend[b + 1] = blendFactor(st.blendSrcRgb[bi]);
                d.blend[b + 2] = blendFactor(st.blendDstRgb[bi]);
                d.blend[b + 3] = blendOp(st.blendEqRgb[bi]);
                d.blend[b + 4] = blendFactor(st.blendSrcA[bi]);
                d.blend[b + 5] = blendFactor(st.blendDstA[bi]);
                d.blend[b + 6] = blendOp(st.blendEqA[bi]);
            } else {
                d.blend[b + 1] = d.blend[b + 2] = d.blend[b + 3] = d.blend[b + 4] = d.blend[b + 5] = d.blend[b + 6] = 0;
            }
            final boolean[] cm = st.colorMask[bi];
            int wm = (cm[0] ? Mtl.WRITE_RED : 0) | (cm[1] ? Mtl.WRITE_GREEN : 0) | (cm[2] ? Mtl.WRITE_BLUE : 0) | (cm[3] ? Mtl.WRITE_ALPHA : 0);
            if (i >= prog.colorOutputs || tex == null) wm = 0;
            d.blend[b + 7] = wm;
        }
        d.depthFormat = t.depth != null && t.hasDepth ? t.depth.format.pf() : Mtl.PF_INVALID;
        d.stencilFormat = t.depth != null && t.hasStencil ? t.depth.format.pf() : Mtl.PF_INVALID;
        d.clipMask = prog.clipMask ? st.clipDistances & 0xFF : 0;

        if (prog == descVertexProgram && prog.gen == descVertexProgramGen && vao.layoutId() == descVertexLayout) return;
        descVertexProgram = prog;
        descVertexProgramGen = prog.gen;
        descVertexLayout = vao.layoutId();
        int n = 0;
        int mask = prog.vertexInputMask;
        while (mask != 0) {
            final int loc = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            d.vLocation[n] = loc;
            final int binding = vao.bindingOf[loc];
            if (vao.enabled[loc] && vao.bindingBuffer[binding] != 0) {
                int fmt = VertexFormats.mtlFormat(vao.size[loc], vao.type[loc], vao.normalized[loc], vao.integer[loc]);
                if (fmt == Mtl.VF_INVALID || !VertexFormats.exact(vao.size[loc], vao.type[loc], vao.normalized[loc])) {
                    gl.warnOnce("vertex attribute type 0x" + Integer.toHexString(vao.type[loc]) + " (normalized=" + vao.normalized[loc] + ") has no exact Metal format");
                    if (fmt == Mtl.VF_INVALID) fmt = Mtl.VF_FLOAT4;
                }
                d.vFormat[n] = fmt;
                d.vStride[n] = vao.stride(loc);
                final int divisor = vao.bindingDivisor[binding];
                d.vStep[n] = divisor > 0 ? Mtl.STEP_PER_INSTANCE : Mtl.STEP_PER_VERTEX;
                d.vRate[n] = Math.max(1, divisor);
            } else {
                d.vFormat[n] = switch (prog.inputKind[loc]) {
                    case 1 -> Mtl.VF_INT4;
                    case 2 -> Mtl.VF_UINT4;
                    default -> Mtl.VF_FLOAT4;
                };
                d.vStride[n] = 16;
                d.vStep[n] = Mtl.STEP_CONSTANT;
                d.vRate[n] = 0;
            }
            n++;
        }
        d.vertexCount = n;
    }

    private static Pipelines.DepthStencilKey depthStencilKey(GlState st, Target t) {
        final boolean hasDepth = t.depth != null && t.hasDepth;
        final boolean depthTest = st.depthTest && hasDepth;
        final boolean stencil = st.stencilTest && t.depth != null && t.hasStencil;
        int front = 0, back = 0, masks = 0;
        if (stencil) {
            front = stencilOp(st.stencilFail[0]) | stencilOp(st.stencilPass[0]) << 4 | stencilOp(st.stencilDepthFail[0]) << 8 | compareOp(st.stencilFunc[0]) << 12;
            back = stencilOp(st.stencilFail[1]) | stencilOp(st.stencilPass[1]) << 4 | stencilOp(st.stencilDepthFail[1]) << 8 | compareOp(st.stencilFunc[1]) << 12;
            masks = (st.stencilValueMask[0] & 0xFF) | (st.stencilWriteMask[0] & 0xFF) << 8 | (st.stencilValueMask[1] & 0xFF) << 16 | (st.stencilWriteMask[1] & 0xFF) << 24;
        }
        return new Pipelines.DepthStencilKey(depthTest ? compareOp(st.depthFunc) : Mtl.COMPARE_ALWAYS, depthTest && st.depthMask, stencil, front, back, masks);
    }

    static int topologyClass(int primitive) {
        return switch (primitive) {
            case Mtl.PRIM_POINT -> Mtl.TOPOLOGY_CLASS_POINT;
            case Mtl.PRIM_LINE, Mtl.PRIM_LINE_STRIP -> Mtl.TOPOLOGY_CLASS_LINE;
            default -> Mtl.TOPOLOGY_CLASS_TRIANGLE;
        };
    }

    private void applyDynamicState(long enc, GlState st, Target t, int topologyClass) {
        final DynamicStateCache c = dynamicCache;
        if (c.upToDate(st, st.pipelineGen, t.globalGen, t.fboId, topologyClass)) return;

        final double near = Math.max(0, Math.min(1, st.depthNear)), far = Math.max(0, Math.min(1, st.depthFar));
        if (c.viewport(st.vpX, st.vpY, st.vpW, st.vpH, near, far)) ObjC.callViewport(enc, SET_VIEWPORT, st.vpX, st.vpY, st.vpW, st.vpH, near, far);

        final int sx, sy, sw, sh;
        if (st.scissorTest) {
            sx = Math.max(0, st.scX);
            sy = Math.max(0, st.scY);
            sw = Math.max(sx, Math.min(t.width, st.scX + st.scW)) - sx;
            sh = Math.max(sy, Math.min(t.height, st.scY + st.scH)) - sy;
        } else {
            sx = sy = 0;
            sw = t.width;
            sh = t.height;
        }
        if (c.scissor(sx, sy, sw, sh)) ObjC.callRect(enc, SET_SCISSOR, sx, sy, sw, sh);

        final int cull = !st.cull ? Mtl.CULL_NONE : st.cullFace == GL11.GL_FRONT ? Mtl.CULL_FRONT : Mtl.CULL_BACK;
        if (c.cull(cull)) call(enc, SET_CULL, cull);
        final int winding = st.frontFace == GL11.GL_CCW ? Mtl.WINDING_CLOCKWISE : Mtl.WINDING_COUNTER_CLOCKWISE;
        if (c.winding(winding)) call(enc, SET_WINDING, winding);
        final int clip = st.depthClamp ? Mtl.DEPTH_CLAMP : Mtl.DEPTH_CLIP;
        if (c.depthClip(clip)) call(enc, SET_DEPTH_CLIP, clip);
        final boolean lines = st.polygonModeFront == GL11.GL_LINE;
        final int fill = lines ? Mtl.FILL_LINES : Mtl.FILL_FILL;
        if (c.fill(fill)) call(enc, SET_FILL, fill);

        final boolean offset = topologyClass == Mtl.TOPOLOGY_CLASS_TRIANGLE ? (lines ? st.polyOffsetLine : st.polygonModeFront == GL11.GL_POINT ? st.polyOffsetPoint : st.polyOffsetFill)
            : topologyClass == Mtl.TOPOLOGY_CLASS_LINE ? st.polyOffsetLine : st.polyOffsetPoint;
        final float units = offset ? st.polyUnits : 0f, factor = offset ? st.polyFactor : 0f, clamp = offset ? st.polyClamp : 0f;
        if (c.depthBias(units, factor, clamp)) ObjC.callF(enc, SET_DEPTH_BIAS, units, factor, clamp);
        final float[] bl = st.blendColor;
        if (c.blendColor(bl)) ObjC.callF(enc, SET_BLEND_COLOR, bl[0], bl[1], bl[2], bl[3]);
        final int refF = st.stencilRef[0] & 0xFF, refB = st.stencilRef[1] & 0xFF;
        if (c.stencilReference(refF, refB)) call(enc, SET_STENCIL_REF, refF, refB);
    }

    private void ensureResCapacity(int n) {
        if (resBuffer.length >= n) return;
        final int size = Math.max(n, resBuffer.length * 2);
        resBuffer = Arrays.copyOf(resBuffer, size);
        resOffset = Arrays.copyOf(resOffset, size);
        resSize = Arrays.copyOf(resSize, size);
        resTexture = Arrays.copyOf(resTexture, size);
        resSampler = Arrays.copyOf(resSampler, size);
    }

    private static final int MAX_RESOLVE_PASSES = 4;

    // Resolving a binding can recreate an image (first storage use, format view) and release views an earlier
    // binding already resolved, so the bindings are resolved again until no image changed.
    boolean collect(GlState st, Program prog, Target target, boolean compute) {
        for (int pass = 1; ; pass++) {
            final long imagesBefore = gl.images.generation;
            resolveBindings(st, prog, target);
            if (gl.images.generation == imagesBefore) return true;
            if (pass == MAX_RESOLVE_PASSES) {
                gl.warnOnce("program " + prog.id + " kept recreating images while its bindings were resolved");
                return true;
            }
        }
    }

    protected void resolveBindings(GlState st, Program prog, Target target) {
        final long serial = gl.stream.currentSerial();
        final int n = prog.bindings.size();
        ensureResCapacity(n);
        for (int bi = 0; bi < n; bi++) {
            final Program.Binding b = prog.bindings.get(bi);
            switch (b.kind) {
                case DEFAULT_BLOCK -> {
                    final Program.StageBlock blk = prog.blocks[b.ref];
                    if (blk.dirty || blk.ringSerial != serial) {
                        gl.uniformRing.alloc(blk.size, gl.gpu.minUniformBufferOffsetAlignment);
                        MemoryUtil.memCopy(blk.address, gl.uniformRing.address, blk.size);
                        blk.ringBuffer = gl.uniformRing.buffer;
                        blk.ringOffset = gl.uniformRing.offset;
                        blk.ringSerial = serial;
                        blk.dirty = false;
                    }
                    resBuffer[bi] = blk.ringBuffer;
                    resOffset[bi] = blk.ringOffset;
                }
                case UNIFORM_BUFFER -> {
                    final Program.BufferBlock bb = prog.ubos.get(b.ref);
                    final int point = Math.min(bb.glBinding, GlState.MAX_BUFFER_BINDINGS - 1);
                    Buf buf = gl.buffers.get(st.uboBuffer[point]);
                    long offset = st.uboOffset[point];
                    if (buf == null || buf.alloc == null || offset >= buf.size) {
                        buf = dummyBuffer;
                        offset = 0;
                    }
                    gl.buffers.use(buf);
                    resBuffer[bi] = buf.handle();
                    resOffset[bi] = offset;
                }
                case STORAGE_BUFFER -> {
                    final Program.BufferBlock bb = prog.ssbos.get(b.ref);
                    final int point = Math.min(bb.glBinding, GlState.MAX_BUFFER_BINDINGS - 1);
                    Buf buf = gl.buffers.get(bb.atomicCounters ? st.atomicBufferAt[point] : st.ssboBuffer[point]);
                    long offset = bb.atomicCounters ? st.atomicOffset[point] : st.ssboOffset[point];
                    final long size = bb.atomicCounters ? st.atomicSize[point] : st.ssboSize[point];
                    if (buf == null || buf.alloc == null || offset >= buf.size) {
                        buf = dummyBuffer;
                        offset = 0;
                    }
                    gl.buffers.gpuWrite(buf);
                    resBuffer[bi] = buf.handle();
                    resOffset[bi] = offset;
                    resSize[bi] = size > 0 ? Math.min(size, buf.size - offset) : buf.size - offset;
                }
                case COMBINED_SAMPLER, SAMPLED_IMAGE, SAMPLER -> {
                    final Program.Uniform u = prog.uniforms.get(b.ref);
                    long[] views = resTexture[bi], samps = resSampler[bi];
                    if (views == null || views.length < b.count) views = resTexture[bi] = new long[b.count];
                    if (samps == null || samps.length < b.count) samps = resSampler[bi] = new long[b.count];
                    for (int e = 0; e < b.count; e++) {
                        final int unit = Math.min(Math.max(0, u.units[e]), GlState.MAX_UNITS - 1);
                        long sampler = 0L, view = 0L;
                        if (b.descriptorType == ProgramLinker.TEXEL_BUFFER) {
                            gl.warnOnce("texel buffers are not bound on Metal");
                        } else if (b.kind != Program.BindingKind.SAMPLER) {
                            final int viewType = viewTypeFor(b);
                            Tex tex = logicDst != null && u.name.startsWith(LogicOpLowering.DST_SAMPLER_PREFIX)
                                ? logicDst[Integer.parseInt(u.name.substring(LogicOpLowering.DST_SAMPLER_PREFIX.length()))]
                                : gl.textures.get(st.textures[unit][targetSlot(b)]);
                            if (tex == null || !tex.hasImage() || !compatibleView(tex, viewType)) {
                                tex = dummyFor(viewType, b.shadow, b.glType);
                            }
                            if (tex.pendingClear || tex.needsClear) materialize(tex);
                            final Tex sampled = target != null && target.contains(tex) ? feedbackCopy(tex) : tex;
                            view = sampledView(sampled, viewType);
                            sampled.lastUse = serial;
                            tex.lastUse = serial;
                            if (b.kind == Program.BindingKind.COMBINED_SAMPLER) sampler = samplerFor(st, unit, tex, b.shadow);
                        } else {
                            sampler = samplerFor(st, unit, null, false);
                        }
                        views[e] = view;
                        samps[e] = sampler;
                    }
                }
                case STORAGE_IMAGE -> {
                    final Program.Uniform u = prog.uniforms.get(b.ref);
                    long[] views = resTexture[bi];
                    if (views == null || views.length < b.count) views = resTexture[bi] = new long[b.count];
                    for (int e = 0; e < b.count; e++) {
                        final int unit = Math.min(Math.max(0, u.units[e]), GlState.MAX_IMAGE_UNITS - 1);
                        final Tex tex = gl.textures.storageImage(st, unit);
                        if (target != null && target.contains(tex)) releaseFromPass(tex);
                        views[e] = gl.textures.storageImageView(st, unit, target);
                    }
                }
            }
        }
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
                if (sizes && b.kind == Program.BindingKind.STORAGE_BUFFER && bufferIndex < 32) {
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
        if (index >= 128 || boundTexture[stage][index] == texture) return;
        call(enc, SET_STAGE_TEXTURE[stage], texture, index);
        boundTexture[stage][index] = texture;
        bindCalls++;
    }

    private void setSampler(long enc, int stage, int index, long sampler) {
        if (index >= 16 || sampler == 0L || boundSampler[stage][index] == sampler) return;
        call(enc, SET_SAMPLER[stage], sampler, index);
        boundSampler[stage][index] = sampler;
        bindCalls++;
    }

    private static int viewTypeFor(Program.Binding b) {
        return switch (b.dim) {
            case 0 -> b.arrayed ? Mtl.TEX_2D_ARRAY : Mtl.TEX_2D;
            case 2 -> Mtl.TEX_3D;
            case 3 -> b.arrayed ? Mtl.TEX_CUBE_ARRAY : Mtl.TEX_CUBE;
            default -> b.arrayed ? Mtl.TEX_2D_ARRAY : Mtl.TEX_2D;
        };
    }

    private static int targetSlot(Program.Binding b) {
        return switch (b.dim) {
            case 0 -> b.arrayed ? GlState.T_1D_ARRAY : GlState.T_1D;
            case 2 -> GlState.T_3D;
            case 3 -> b.arrayed ? GlState.T_CUBE_ARRAY : GlState.T_CUBE;
            default -> b.arrayed ? GlState.T_2D_ARRAY : GlState.T_2D;
        };
    }

    private static boolean compatibleView(Tex tex, int viewType) {
        final int own = Images.textureTypeFor(tex);
        if (own == viewType) return true;
        return switch (viewType) {
            case Mtl.TEX_2D_ARRAY -> own == Mtl.TEX_2D || own == Mtl.TEX_CUBE || own == Mtl.TEX_CUBE_ARRAY;
            case Mtl.TEX_2D -> own == Mtl.TEX_2D_ARRAY && tex.layers == 1;
            default -> false;
        };
    }


    private final Int2ObjectOpenHashMap<Tex> extraDummies = new Int2ObjectOpenHashMap<>();

    private Tex dummyFor(int viewType, boolean shadow, int glType) {
        if (shadow && viewType == Mtl.TEX_2D) return dummyDepth;
        final int kind = sampledKind(glType);
        if (kind == SAMPLED_FLOAT) {
            switch (viewType) {
                case Mtl.TEX_2D -> { return dummy2D; }
                case Mtl.TEX_2D_ARRAY -> { return dummy2DArray; }
                case Mtl.TEX_3D -> { return dummy3D; }
                case Mtl.TEX_CUBE -> { return dummyCube; }
                default -> { }
            }
        }
        final int key = (shadow ? 1 << 12 : 0) | kind << 8 | viewType;
        Tex t = extraDummies.get(key);
        if (t != null) return t;
        final Formats.Format f = shadow ? gl.textures.format(GL14.GL_DEPTH_COMPONENT24)
            : kind == SAMPLED_FLOAT ? Formats.RGBA8 : gl.textures.format(kind == SAMPLED_SINT ? GL30.GL_RGBA8I : GL30.GL_RGBA8UI);
        final int layers = viewType == Mtl.TEX_CUBE || viewType == Mtl.TEX_CUBE_ARRAY ? 6 : 1;
        t = gl.textures.internalTexture(Images.glTargetForViewType(viewType), f, 1, 1, 1, layers, "dummy" + kind + "/" + viewType);
        endPass();
        gl.images.clearImage(t, new float[] {0, 0, 0, 1}, 1f, 0);
        extraDummies.put(key, t);
        return t;
    }


    long sampledView(Tex tex, int viewType) {
        if (tex.sampleStencil && tex.format.isDepth() && tex.format.isStencil()) gl.textures.ensureUsage(tex, Mtl.USAGE_PIXEL_FORMAT_VIEW);
        if (tex.viewDirty) refreshViews(tex);
        if (Images.viewTypeFor(tex) == viewType) {
            if (tex.view == 0L) {
                tex.view = gl.images.createSampledView(tex, viewType);
                objectCreations++;
            }
            return tex.view;
        }
        long[] extra = extraViews.get(tex.id);
        if (extra == null) {
            extra = new long[8];
            extraViews.put(tex.id, extra);
        }
        if (extra[viewType] == 0L) {
            extra[viewType] = gl.images.createSampledView(tex, viewType);
            objectCreations++;
        }
        return extra[viewType];
    }

    private void refreshViews(Tex tex) {
        tex.viewDirty = false;
        ObjC.release(tex.view);
        tex.view = 0L;
        final long[] extra = extraViews.remove(tex.id);
        if (extra != null) for (long v : extra) ObjC.release(v);
    }

    public void invalidateDescriptorCache() {
    }


    long samplerFor(GlState st, int unit, Tex tex, boolean shadowBinding) {
        final SamplerObj so = gl.samplerObjects.get(st.samplers[unit]);
        final int min, mag, ws, wt, wr, cmpMode, cmpFunc;
        final float minLod, maxLod, bias, aniso;
        final float[] border;
        if (so != null) {
            min = so.minFilter; mag = so.magFilter; ws = so.wrapS; wt = so.wrapT; wr = so.wrapR;
            minLod = so.minLod; maxLod = so.maxLod; bias = so.lodBias; aniso = so.anisotropy; cmpMode = so.compareMode; cmpFunc = so.compareFunc; border = so.border;
        } else if (tex != null) {
            if (!tex.samplerDirty && tex.sampler != 0L) return tex.sampler;
            min = tex.minFilter; mag = tex.magFilter; ws = tex.wrapS; wt = tex.wrapT; wr = tex.wrapR;
            minLod = tex.minLod; maxLod = tex.maxLod; bias = tex.lodBias; aniso = tex.anisotropy; cmpMode = tex.compareMode; cmpFunc = tex.compareFunc; border = tex.border;
        } else {
            min = GL11.GL_NEAREST; mag = GL11.GL_NEAREST; ws = wt = wr = GL12.GL_CLAMP_TO_EDGE;
            minLod = -1000; maxLod = 1000; bias = 0; aniso = 1; cmpMode = GL11.GL_NONE; cmpFunc = GL11.GL_LEQUAL; border = ZERO;
        }
        final boolean integer = tex != null && (tex.format.isInteger() || tex.format.isStencil() && tex.sampleStencil);
        final boolean linearOk = tex == null || !integer && gl.images.filterable(tex.format.pf());
        final int magF = linearOk && mag == GL11.GL_LINEAR ? Mtl.FILTER_LINEAR : Mtl.FILTER_NEAREST;
        final int minF = linearOk && (min == GL11.GL_LINEAR || min == GL11.GL_LINEAR_MIPMAP_NEAREST || min == GL11.GL_LINEAR_MIPMAP_LINEAR) ? Mtl.FILTER_LINEAR : Mtl.FILTER_NEAREST;
        final boolean mip = min != GL11.GL_NEAREST && min != GL11.GL_LINEAR;
        final int mipMode = !mip ? Mtl.MIP_NOT_MIPMAPPED : linearOk && (min == GL11.GL_NEAREST_MIPMAP_LINEAR || min == GL11.GL_LINEAR_MIPMAP_LINEAR) ? Mtl.MIP_LINEAR : Mtl.MIP_NEAREST;
        final boolean compare = cmpMode == GL14.GL_COMPARE_R_TO_TEXTURE && tex != null && tex.format.isDepth() || shadowBinding && tex != null && tex.format.isDepth() && cmpMode != GL11.GL_NONE;
        final int maxAniso = (int) Math.max(1, Math.min(Math.round(aniso), Math.round(gl.gpu.maxSamplerAnisotropy)));
        final int borderColor = borderColor(border);
        final float lodBias = Math.max(-15f, Math.min(15f, bias));
        long key = magF | minF << 1 | mipMode << 2 | (long) wrap(ws) << 4 | (long) wrap(wt) << 7 | (long) wrap(wr) << 10
            | (long) (compare ? 1 : 0) << 13 | (long) compareOp(cmpFunc) << 14 | (long) borderColor << 17 | (long) (mip ? maxAniso : 1) << 20;
        key = key * 31 + Float.floatToIntBits(minLod);
        key = key * 31 + Float.floatToIntBits(maxLod);
        key = key * 31 + Float.floatToIntBits(lodBias);
        long sampler = samplers.get(key);
        if (sampler == 0L) {
            final long d = ObjC.create("MTLSamplerDescriptor");
            try {
                call(d, SET_MIN, minF);
                call(d, SET_MAG, magF);
                call(d, SET_MIP, mipMode);
                call(d, SET_S, wrap(ws));
                call(d, SET_T, wrap(wt));
                call(d, SET_R, wrap(wr));
                if (mip && maxAniso > 1 && linearOk) call(d, SET_ANISO, maxAniso);
                ObjC.callF(d, SET_LOD_MIN, mip ? Math.max(0f, minLod) : 0f);
                ObjC.callF(d, SET_LOD_MAX, mip ? Math.max(Math.max(0f, minLod), Math.min(maxLod, 1000f)) : 0f);
                if (compare) call(d, SET_COMPARE, compareOp(cmpFunc));
                call(d, SET_BORDER, borderColor);
                if (lodBias != 0f) {
                    if (gl.gpu.samplerLodBias) ObjC.callF(d, SET_LOD_BIAS, lodBias);
                    else gl.warnOnce("GL_TEXTURE_LOD_BIAS needs macOS 26 on Metal");
                }
                sampler = send(gl.gpu.device, NEW_SAMPLER, d);
                objectCreations++;
            } finally {
                ObjC.release(d);
            }
            samplers.put(key, sampler);
        }
        if (so == null && tex != null) {
            tex.sampler = sampler;
            tex.samplerDirty = false;
        }
        return sampler;
    }

    private static int wrap(int gl) {
        return switch (gl) {
            case GL12.GL_CLAMP_TO_EDGE, GL11.GL_CLAMP -> Mtl.ADDRESS_CLAMP_TO_EDGE;
            case GL13.GL_CLAMP_TO_BORDER -> Mtl.ADDRESS_CLAMP_TO_BORDER;
            case GL14.GL_MIRRORED_REPEAT -> Mtl.ADDRESS_MIRROR_REPEAT;
            case org.lwjgl.opengl.GL44.GL_MIRROR_CLAMP_TO_EDGE -> Mtl.ADDRESS_MIRROR_CLAMP_TO_EDGE;
            default -> Mtl.ADDRESS_REPEAT;
        };
    }

    private static int borderColor(float[] b) {
        final boolean opaque = b[3] >= 0.5f;
        final boolean white = b[0] >= 0.5f && b[1] >= 0.5f && b[2] >= 0.5f;
        return opaque ? (white ? Mtl.BORDER_OPAQUE_WHITE : Mtl.BORDER_OPAQUE_BLACK) : Mtl.BORDER_TRANSPARENT_BLACK;
    }

    private final float[] lastConstValues = new float[64];
    private long constRingBuffer;
    private long constRingOffset;
    private boolean constValid;
    private long constRingSerial = -1;

    private boolean bindVertexBuffers(long enc, GlState st, Program prog, Vao vao) {
        final long serial = gl.stream.currentSerial();
        int n = 0;
        int mask = prog.vertexInputMask;
        while (mask != 0) {
            final int loc = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            final int binding = vao.bindingOf[loc];
            final Buf buf = vao.enabled[loc] ? gl.buffers.get(vao.bindingBuffer[binding]) : null;
            // Vertex buffers occupy the buffer slots after the uniform and storage buffers; disabled arrays read a constant from the ring.
            final int index = prog.vertexBufferBase + n;
            if (buf != null && buf.alloc != null) {
                gl.buffers.use(buf);
                setBuffer(enc, STAGE_VERTEX, index, buf.handle(), vao.bindingOffset[binding] + vao.relativeOffset[loc]);
            } else {
                if (!constValid || constRingSerial != serial || !Arrays.equals(lastConstValues, st.attribValue)) {
                    gl.uniformRing.alloc(Vao.MAX_ATTRIBS * 16L, 16);
                    for (int i = 0; i < Vao.MAX_ATTRIBS * 4; i++) MemoryUtil.memPutFloat(gl.uniformRing.address + i * 4L, st.attribValue[i]);
                    constRingBuffer = gl.uniformRing.buffer;
                    constRingOffset = gl.uniformRing.offset;
                    constRingSerial = serial;
                    System.arraycopy(st.attribValue, 0, lastConstValues, 0, lastConstValues.length);
                    constValid = true;
                }
                setBuffer(enc, STAGE_VERTEX, index, constRingBuffer, constRingOffset + loc * 16L);
            }
            n++;
        }
        return true;
    }

    boolean bindIndexBuffer(long buffer, long offset, int type) {
        indexBuffer = buffer;
        indexOffset = offset;
        indexType = type;
        return true;
    }

    long encoder() {
        return passEncoder;
    }

    boolean prepareDispatch(GlState st) {
        final Program prog = gl.programs.current(st);
        if (prog == null || !prog.linked || !prog.compute) return false;
        if (!collect(st, prog, null, true)) return false;
        endPass();
        final long pipeline = pipelines.compute(prog);
        if (pipeline == 0L) return false;
        final boolean fresh = gl.stream.encoderKind() != CommandStream.COMPUTE;
        final long enc = gl.stream.compute();
        if (fresh) {
            Arrays.fill(boundBuffer[STAGE_COMPUTE], 0L);
            Arrays.fill(boundOffset[STAGE_COMPUTE], -1L);
            Arrays.fill(boundTexture[STAGE_COMPUTE], 0L);
            Arrays.fill(boundSampler[STAGE_COMPUTE], 0L);
        }
        call(enc, SET_COMPUTE_PIPELINE, pipeline);
        bind(prog, Program.CS, STAGE_COMPUTE, enc);
        if (prog.writesStorage) gl.stream.markStorageWrites();
        pendingComputeBarrier = false;
        pendingTransferBarrier = false;
        return true;
    }

    static int compareOp(int f) {
        return switch (f) {
            case GL11.GL_NEVER -> Mtl.COMPARE_NEVER;
            case GL11.GL_LESS -> Mtl.COMPARE_LESS;
            case GL11.GL_EQUAL -> Mtl.COMPARE_EQUAL;
            case GL11.GL_LEQUAL -> Mtl.COMPARE_LESS_EQUAL;
            case GL11.GL_GREATER -> Mtl.COMPARE_GREATER;
            case GL11.GL_NOTEQUAL -> Mtl.COMPARE_NOT_EQUAL;
            case GL11.GL_GEQUAL -> Mtl.COMPARE_GREATER_EQUAL;
            default -> Mtl.COMPARE_ALWAYS;
        };
    }

    static int stencilOp(int op) {
        return switch (op) {
            case GL11.GL_ZERO -> Mtl.STENCIL_ZERO;
            case GL11.GL_REPLACE -> Mtl.STENCIL_REPLACE;
            case GL11.GL_INCR -> Mtl.STENCIL_INCREMENT_CLAMP;
            case GL11.GL_DECR -> Mtl.STENCIL_DECREMENT_CLAMP;
            case GL11.GL_INVERT -> Mtl.STENCIL_INVERT;
            case GL14.GL_INCR_WRAP -> Mtl.STENCIL_INCREMENT_WRAP;
            case GL14.GL_DECR_WRAP -> Mtl.STENCIL_DECREMENT_WRAP;
            default -> Mtl.STENCIL_KEEP;
        };
    }

    static int blendFactor(int f) {
        return switch (f) {
            case GL11.GL_ZERO -> Mtl.BLEND_ZERO;
            case GL11.GL_ONE -> Mtl.BLEND_ONE;
            case GL11.GL_SRC_COLOR -> Mtl.BLEND_SRC_COLOR;
            case GL11.GL_ONE_MINUS_SRC_COLOR -> Mtl.BLEND_ONE_MINUS_SRC_COLOR;
            case GL11.GL_DST_COLOR -> Mtl.BLEND_DST_COLOR;
            case GL11.GL_ONE_MINUS_DST_COLOR -> Mtl.BLEND_ONE_MINUS_DST_COLOR;
            case GL11.GL_SRC_ALPHA -> Mtl.BLEND_SRC_ALPHA;
            case GL11.GL_ONE_MINUS_SRC_ALPHA -> Mtl.BLEND_ONE_MINUS_SRC_ALPHA;
            case GL11.GL_DST_ALPHA -> Mtl.BLEND_DST_ALPHA;
            case GL11.GL_ONE_MINUS_DST_ALPHA -> Mtl.BLEND_ONE_MINUS_DST_ALPHA;
            case GL14.GL_CONSTANT_COLOR -> Mtl.BLEND_BLEND_COLOR;
            case GL14.GL_ONE_MINUS_CONSTANT_COLOR -> Mtl.BLEND_ONE_MINUS_BLEND_COLOR;
            case GL14.GL_CONSTANT_ALPHA -> Mtl.BLEND_BLEND_ALPHA;
            case GL14.GL_ONE_MINUS_CONSTANT_ALPHA -> Mtl.BLEND_ONE_MINUS_BLEND_ALPHA;
            case GL11.GL_SRC_ALPHA_SATURATE -> Mtl.BLEND_SRC_ALPHA_SATURATED;
            case org.lwjgl.opengl.GL33.GL_SRC1_COLOR -> Mtl.BLEND_SRC1_COLOR;
            case org.lwjgl.opengl.GL33.GL_ONE_MINUS_SRC1_COLOR -> Mtl.BLEND_ONE_MINUS_SRC1_COLOR;
            case org.lwjgl.opengl.GL15.GL_SRC1_ALPHA -> Mtl.BLEND_SRC1_ALPHA;
            case org.lwjgl.opengl.GL33.GL_ONE_MINUS_SRC1_ALPHA -> Mtl.BLEND_ONE_MINUS_SRC1_ALPHA;
            default -> Mtl.BLEND_ONE;
        };
    }

    static int blendOp(int eq) {
        return switch (eq) {
            case GL14.GL_FUNC_SUBTRACT -> Mtl.BLEND_OP_SUBTRACT;
            case GL14.GL_FUNC_REVERSE_SUBTRACT -> Mtl.BLEND_OP_REVERSE_SUBTRACT;
            case GL14.GL_MIN -> Mtl.BLEND_OP_MIN;
            case GL14.GL_MAX -> Mtl.BLEND_OP_MAX;
            default -> Mtl.BLEND_OP_ADD;
        };
    }

    static int primitive(int mode) {
        return switch (mode) {
            case GL11.GL_POINTS -> Mtl.PRIM_POINT;
            case GL11.GL_LINES -> Mtl.PRIM_LINE;
            case GL11.GL_LINE_STRIP, GL11.GL_LINE_LOOP -> Mtl.PRIM_LINE_STRIP;
            case GL11.GL_TRIANGLE_STRIP -> Mtl.PRIM_TRIANGLE_STRIP;
            default -> Mtl.PRIM_TRIANGLE;
        };
    }


    void destroy() {
        endPass();
        for (Tex t : new Tex[] {dummy2D, dummy2DArray, dummy3D, dummyCube, dummyDepth, dummyStorage}) {
            if (t != null) gl.images.release(t);
        }
        for (Tex t : extraDummies.values()) gl.images.release(t);
        extraDummies.clear();
        for (Tex t : feedbackCopies.values()) gl.images.release(t);
        feedbackCopies.clear();
        for (Tex t : logicScratch) if (t != null) gl.images.release(t);
        if (dummyBuffer != null && dummyBuffer.alloc != null) {
            dummyBuffer.alloc.destroy();
            dummyBuffer.alloc = null;
        }
        for (long s : samplers.values()) ObjC.release(s);
        samplers.clear();
        pipelines.destroy();
        MemoryUtil.nmemFree(sizeScratch);
    }

    @Override protected Textures textures() { return gl.textures; }
    @Override protected Programs programs() { return gl.programs; }
    @Override protected DefaultFramebuffer defaultFramebuffer() { return gl.defaultFb; }
    @Override protected Tex newTex(int id) { return new Tex(id, false); }
    @Override protected void releaseImage(Tex t) { gl.images.release(t); }
    @Override protected void createImage(Tex t) { gl.images.create(t, false); }
    @Override protected long currentSerial() { return gl.stream.currentSerial(); }
    @Override protected void copyImage(Tex src, int srcLevel, int sx, int sy, int sz, Tex dst, int dstLevel, int dx, int dy, int dz, int w, int h, int d) {
        gl.textures.copyImage(src, srcLevel, sx, sy, sz, dst, dstLevel, dx, dy, dz, w, h, d);
    }
    @Override protected boolean renderable(int pf) { return gl.images.renderable(pf); }
    @Override protected void clearImage(Tex t, float[] color, float depth, int stencil) { gl.images.clearImage(t, color, depth, stencil); }
    @Override protected void copyWhole(Tex from, Tex to) { gl.textures.copyWhole(from, to); }
}
