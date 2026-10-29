package com.gtnewhorizons.angelica.dx12.gl;

import com.gtnewhorizons.angelica.glemu.gl.GlState;

import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.dx12.device.Gfx;
import com.gtnewhorizons.angelica.dx12.memory.GpuBuffer;
import com.gtnewhorizons.angelica.dx12.pipeline.RootSignatures;
import com.gtnewhorizons.angelica.dx12.pipeline.Pipelines;
import com.gtnewhorizons.angelica.dx12.pipeline.VertexFormats;
import com.gtnewhorizons.angelica.dx12.resource.Buf;
import com.gtnewhorizons.angelica.dx12.resource.DefaultFramebuffer;
import com.gtnewhorizons.angelica.glemu.resource.Fbo;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.dx12.resource.Images;
import com.gtnewhorizons.angelica.glemu.resource.SamplerObj;
import com.gtnewhorizons.angelica.dx12.resource.Target;
import com.gtnewhorizons.angelica.dx12.resource.Tex;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import com.gtnewhorizons.angelica.glemu.shader.LogicOpLowering;
import com.gtnewhorizons.angelica.dx12.shader.Program;
import com.gtnewhorizons.angelica.dx12.shader.ProgramLinker;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class Renderer {
    private static final int STAGE_VERTEX = 0, STAGE_FRAGMENT = 1, STAGE_COMPUTE = 2;
    private static final int SRV_STATE = Dx.STATE_PIXEL_SRV | Dx.STATE_NON_PIXEL_SRV;
    private static final int[] D3D_TOPOLOGY = {1, 2, 3, 4, 5};

    private final Dx12GL gl;
    public final Pipelines pipelines;
    private final Pipelines.Desc desc = new Pipelines.Desc();

    public boolean passActive;
    private final Target passTarget = new Target();
    final Target drawTarget = new Target();
    private final Target scratchTarget = new Target();
    public int targetGen = 1;

    private long boundPipeline, boundRoot, boundSerial = -1;
    private int boundTopology = -1;
    private int dynamicStamp = -1;
    long indexAddress, indexBytes;
    int indexType;
    boolean indexedDraw;

    private int lastProgram = -1, lastProgramGen = -1, lastPipelineGen = -1, lastDynamicGen = -1, lastVaoFormatGen = -1, lastVaoId = -1, lastMode = -1, lastTargetGen = -1, lastFboId = Integer.MIN_VALUE;
    private int lastStripCut = -1;
    private Object lastState;
    private long lastPipeline;
    private boolean lastDiscard;
    private int lastClass = -1;

    public boolean pendingTransferBarrier;
    boolean pendingComputeBarrier;

    private final Long2LongOpenHashMap samplers = new Long2LongOpenHashMap();
    private final Int2ObjectOpenHashMap<long[]> extraViews = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Tex> feedbackCopies = new Int2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap feedbackVersions = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    private final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap feedbackEpochs = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    private long passEpoch;

    public Tex dummy2D, dummy2DArray, dummy3D, dummyCube, dummyDepth, dummyStorage;
    private Buf dummyBuffer;

    public long draws, passes, depthDiscards, bindCalls, objectCreations;

    private long[] resBuffer = new long[32], resOffset = new long[32], resSize = new long[32];
    private long[][] resTexture = new long[32][], resSampler = new long[32][];
    private final java.lang.foreign.MemorySegment scratch = java.lang.foreign.Arena.global().allocate(4096, 8);
    private long nullSampler;
    private long[] samplerScratch = new long[64], viewScratch = new long[256];
    Renderer(Dx12GL gl) {
        this.gl = gl;
        this.pipelines = new Pipelines(gl);
    }

    private long nullSampler() {
        if (nullSampler == 0L) nullSampler = samplerDescriptor(Gfx.FILTER_NEAREST, Gfx.FILTER_NEAREST, Gfx.MIP_NOT_MIPMAPPED, Gfx.ADDRESS_CLAMP_TO_EDGE, Gfx.ADDRESS_CLAMP_TO_EDGE, Gfx.ADDRESS_CLAMP_TO_EDGE, 1, 0f, 0f, 0f, false, Gfx.COMPARE_ALWAYS, ZERO);
        return nullSampler;
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
    }

    Target resolveDrawTarget(GlState st) {
        final Fbo fbo = gl.framebuffers.get(st.drawFbo);
        resolve(drawTarget, fbo, st, true);
        return drawTarget;
    }

    void resolve(Target t, Fbo fbo, GlState st, boolean draw) {
        final int fboId = fbo == null ? 0 : fbo.id;
        final int fboGen = fbo == null ? 0 : fbo.gen;
        if (t.fboId == fboId && t.fboGen == fboGen && t.globalGen == targetGen && (fboId != 0 || !draw || !st.defaultDrawNone)) {
            return;
        }
        t.clear();
        t.fboId = fboId;
        t.fboGen = fboGen;
        t.globalGen = targetGen;
        int w = Integer.MAX_VALUE, h = Integer.MAX_VALUE;
        if (fbo == null || fbo.id == 0) {
            final DefaultFramebuffer d = gl.defaultFb;
            if (!(draw && st.defaultDrawNone)) {
                t.color[0] = d.color;
                t.colorLayers[0] = 1;
                t.colorCount = 1;
            } else {
                t.colorCount = 0;
            }
            t.depth = d.depth;
            t.hasDepth = true;
            t.hasStencil = d.depth.format.isStencil();
            t.width = d.width;
            t.height = d.height;
            return;
        }
        int count = 0;
        for (int i = 0; i < fbo.drawBuffers.length; i++) {
            final int a = fbo.drawBuffers[i];
            if (a < 0 || fbo.colorTex[a] == 0) continue;
            final Tex tex = gl.textures.attachment(fbo.colorTex[a], fbo.colorRbo[a]);
            if (tex == null || !tex.hasImage() || !gl.images.renderable(tex.format.pf())) continue;
            t.color[i] = tex;
            t.colorLevel[i] = Math.min(fbo.colorLevel[a], tex.levels - 1);
            t.colorLayer[i] = fbo.colorLayered[a] ? 0 : fbo.colorLayer[a];
            t.colorLayers[i] = fbo.colorLayered[a] ? tex.layers : 1;
            count = i + 1;
            w = Math.min(w, tex.levelWidth(t.colorLevel[i]));
            h = Math.min(h, tex.levelHeight(t.colorLevel[i]));
        }
        t.colorCount = count;
        final int depthId = fbo.depthTex != 0 ? fbo.depthTex : fbo.stencilTex;
        if (depthId != 0) {
            final boolean rbo = fbo.depthTex != 0 ? fbo.depthRbo : fbo.stencilRbo;
            final Tex tex = gl.textures.attachment(depthId, rbo);
            if (tex != null && tex.hasImage() && tex.format.isDepthOrStencil()) {
                t.depth = tex;
                t.depthLevel = Math.min(fbo.depthTex != 0 ? fbo.depthLevel : fbo.stencilLevel, tex.levels - 1);
                t.depthLayer = fbo.depthTex != 0 ? fbo.depthLayer : fbo.stencilLayer;
                t.depthLayers = fbo.depthLayered ? tex.layers : 1;
                t.hasDepth = tex.format.isDepth();
                t.hasStencil = tex.format.isStencil();
                w = Math.min(w, tex.levelWidth(t.depthLevel));
                h = Math.min(h, tex.levelHeight(t.depthLevel));
            }
        }
        t.width = w == Integer.MAX_VALUE ? 0 : w;
        t.height = h == Integer.MAX_VALUE ? 0 : h;
    }

    public Target readTarget(GlState st) {
        final Target t = scratchTarget;
        t.clear();
        t.fboId = Integer.MIN_VALUE;
        final Fbo fbo = gl.framebuffers.get(st.readFbo);
        if (fbo == null || fbo.id == 0) {
            final DefaultFramebuffer d = gl.defaultFb;
            if (!st.defaultReadNone) {
                t.color[0] = d.color;
                t.colorLayers[0] = 1;
                t.colorCount = 1;
            }
            t.depth = d.depth;
            t.hasDepth = true;
            t.hasStencil = d.depth.format.isStencil();
            t.width = d.width;
            t.height = d.height;
            return t;
        }
        final int idx = fbo.readBuffer;
        if (idx >= 0 && idx < Fbo.MAX_COLOR && fbo.colorTex[idx] != 0) {
            final Tex tex = gl.textures.attachment(fbo.colorTex[idx], fbo.colorRbo[idx]);
            if (tex != null && tex.hasImage()) {
                t.color[0] = tex;
                t.colorLevel[0] = fbo.colorLevel[idx];
                t.colorLayer[0] = fbo.colorLayered[idx] ? 0 : fbo.colorLayer[idx];
                t.colorLayers[0] = 1;
                t.colorCount = 1;
                t.width = tex.levelWidth(t.colorLevel[0]);
                t.height = tex.levelHeight(t.colorLevel[0]);
            }
        }
        final int depthId = fbo.depthTex != 0 ? fbo.depthTex : fbo.stencilTex;
        if (depthId != 0) {
            final Tex tex = gl.textures.attachment(depthId, fbo.depthTex != 0 ? fbo.depthRbo : fbo.stencilRbo);
            if (tex != null && tex.hasImage() && tex.format.isDepthOrStencil()) {
                t.depth = tex;
                t.depthLevel = fbo.depthTex != 0 ? fbo.depthLevel : fbo.stencilLevel;
                t.depthLayer = fbo.depthTex != 0 ? fbo.depthLayer : fbo.stencilLayer;
                t.hasDepth = tex.format.isDepth();
                t.hasStencil = tex.format.isStencil();
                if (t.width == 0) {
                    t.width = tex.levelWidth(t.depthLevel);
                    t.height = tex.levelHeight(t.depthLevel);
                }
            }
        }
        return t;
    }

    public void onBufferRenamed() {
    }

    public void endPass() {
        endPass(false);
    }

    void endPass(boolean frameEnd) {
        if (!passActive) return;
        finishPass(frameEnd);
    }

    private void finishPass(boolean frameEnd) {
        if (passTarget.depth != null && frameEnd && passTarget.depth == gl.defaultFb.depth) {
            passTarget.depth.contentsUndefined = true;
            depthDiscards++;
        }
        passActive = false;
    }

    private boolean sameAsPass(Target t) {
        return passActive && passTarget.fboId == t.fboId && passTarget.fboGen == t.fboGen && passTarget.globalGen == t.globalGen
            && passTarget.colorCount == t.colorCount && passTarget.width == t.width && passTarget.height == t.height
            && sameAttachments(t) && statesHold(t);
    }

    private boolean sameAttachments(Target t) {
        for (int i = 0; i < t.colorCount; i++) {
            if (passTarget.color[i] != t.color[i] || passTarget.colorLevel[i] != t.colorLevel[i] || passTarget.colorLayer[i] != t.colorLayer[i]) return false;
        }
        return passTarget.depth == t.depth && passTarget.depthLevel == t.depthLevel && passTarget.depthLayer == t.depthLayer;
    }

    private boolean statesHold(Target t) {
        for (int i = 0; i < t.colorCount; i++) {
            if (t.color[i] != null && t.color[i].state != Dx.STATE_RENDER_TARGET) return false;
        }
        return t.depth == null || t.depth.state == Dx.STATE_DEPTH_WRITE;
    }

    void ensurePass(Target t) {
        if (sameAsPass(t)) return;
        endPass();
        final long serial = gl.stream.currentSerial();
        for (int i = 0; i < t.colorCount; i++) {
            if (t.color[i] != null) gl.images.transition(t.color[i], Dx.STATE_RENDER_TARGET);
        }
        if (t.depth != null) gl.images.transition(t.depth, Dx.STATE_DEPTH_WRITE);
        final long list = gl.stream.list();
        final long run = gl.descriptors.nextRtvRun(Math.max(1, t.colorCount));
        for (int i = 0; i < t.colorCount; i++) {
            final Tex tex = t.color[i];
            final long handle = gl.descriptors.rtvAt(run, i);
            if (tex == null) {
                Com.v(gl.gpu.device, Dx.DEV_CREATE_RTV, 0L, nullRtvDesc(), handle);
                continue;
            }
            gl.images.writeRtv(handle, tex, t.colorLevel[i], t.colorLayer[i], tex.is3D() ? tex.levelDepth(t.colorLevel[i]) : t.colorLayers[i]);
            boolean clear = false;
            if (tex.pendingClear && (tex.clearAspects & Formats.ASPECT_COLOR) != 0) {
                clear = true;
                setClearColor(tex, tex.clearColor);
                tex.pendingClear = false;
                tex.needsClear = false;
            } else if (tex.needsClear) {
                clear = true;
                System.arraycopy(ZERO, 0, scratchColor, 0, 4);
                setClearColor(tex, ZERO);
                tex.needsClear = false;
            }
            if (clear) {
                Com.v(list, Dx.CL_CLEAR_RTV, handle, scratch.address(), 0, 0L);
                tex.version++;
            }
            tex.contentsUndefined = false;
            tex.lastUse = serial;
        }
        long dsv = 0L;
        if (t.depth != null) {
            final Tex tex = t.depth;
            boolean clearDepth = tex.needsClear, clearStencil = tex.needsClear;
            float depthValue = 1f;
            int stencilValue = 0;
            if (tex.needsClear) tex.needsClear = false;
            if (tex.pendingClear) {
                if ((tex.clearAspects & Formats.ASPECT_DEPTH) != 0) {
                    clearDepth = true;
                    depthValue = tex.clearDepth;
                }
                if ((tex.clearAspects & Formats.ASPECT_STENCIL) != 0) {
                    clearStencil = true;
                    stencilValue = tex.clearStencil;
                }
                tex.pendingClear = false;
            }
            dsv = gl.images.dsv(tex, t.depthLevel, t.depthLayer, t.depthLayers);
            final int flags = (clearDepth && t.hasDepth ? 1 : 0) | (clearStencil && t.hasStencil ? 2 : 0);
            if (flags != 0) {
                Com.clearDsv(list, dsv, flags, depthValue, stencilValue);
                tex.version++;
            }
            tex.contentsUndefined = false;
            tex.lastUse = serial;
        }
        passRtvRun = run;
        passDsv = dsv;
        scratch.set(JAVA_LONG, 3072, run);
        scratch.set(JAVA_LONG, 3080, dsv);
        if (t.colorCount > 0) Com.v(list, Dx.CL_RENDER_TARGETS, t.colorCount, scratch.address() + 3072, 1, dsv != 0L ? scratch.address() + 3080 : 0L);
        else Com.v(list, Dx.CL_RENDER_TARGETS, 0, 0L, 0, dsv != 0L ? scratch.address() + 3080 : 0L);
        copyTarget(t, passTarget);
        passActive = true;
        passes++;
        passEpoch++;
        resetBound();
    }

    private long nullRtvDescPtr;
    private long passRtvRun, passDsv;

    private long nullRtvDesc() {
        if (nullRtvDescPtr == 0L) {
            final java.lang.foreign.MemorySegment d = java.lang.foreign.Arena.global().allocate(32, 8);
            d.set(JAVA_INT, 0, com.gtnewhorizons.angelica.dx12.device.Dxgi.R8G8B8A8_UNORM);
            d.set(JAVA_INT, 4, 4);
            nullRtvDescPtr = d.address();
        }
        return nullRtvDescPtr;
    }

    private final float[] scratchColor = new float[4];

    private void resetBound() {
        boundPipeline = 0L;
        boundRoot = 0L;
        boundTopology = -1;
        dynamicStamp = -1;
        lastClass = -1;
        boundSerial = gl.stream.currentSerial();
        for (int c = 0; c < 3; c++) forgetRootArguments(c);
        boundVertexViews = -1;
        boundIndexView = -1L;
    }
    private static int layerCount(Target t) {
        int n = 1;
        for (int i = 0; i < t.colorCount; i++) if (t.color[i] != null) n = Math.max(n, t.colorLayers[i]);
        if (t.depth != null) n = Math.max(n, t.depthLayers);
        return n;
    }

    private void setClearColor(Tex tex, float[] c) {
        for (int i = 0; i < 4; i++) scratch.set(JAVA_FLOAT, i * 4L, c[i]);
    }
    private static void copyTarget(Target from, Target to) {
        to.clear();
        to.fboId = from.fboId;
        to.fboGen = from.fboGen;
        to.globalGen = from.globalGen;
        to.colorCount = from.colorCount;
        System.arraycopy(from.color, 0, to.color, 0, from.color.length);
        System.arraycopy(from.colorLevel, 0, to.colorLevel, 0, from.colorLevel.length);
        System.arraycopy(from.colorLayer, 0, to.colorLayer, 0, from.colorLayer.length);
        System.arraycopy(from.colorLayers, 0, to.colorLayers, 0, from.colorLayers.length);
        to.depth = from.depth;
        to.depthLevel = from.depthLevel;
        to.depthLayer = from.depthLayer;
        to.depthLayers = from.depthLayers;
        to.hasDepth = from.hasDepth;
        to.hasStencil = from.hasStencil;
        to.width = from.width;
        to.height = from.height;
    }

    public void releaseFromPass(Tex t) {
        if (passActive && passTarget.contains(t)) endPass();
    }

    public void onImageReleased(Tex t) {
        if (passActive && passTarget.contains(t)) endPass();
        targetGen++;
    }

    public void materialize(Tex t) {
        if (!t.hasImage()) return;
        if (!t.pendingClear && !t.needsClear) return;
        releaseFromPass(t);
        endPass();
        if (t.pendingClear) {
            final boolean depth = (t.clearAspects & Formats.ASPECT_DEPTH) != 0, stencil = (t.clearAspects & Formats.ASPECT_STENCIL) != 0;
            t.pendingClear = false;
            gl.images.clearImage(t, t.clearColor, depth || !t.format.isDepth() ? t.clearDepth : 1f, stencil ? t.clearStencil : 0);
        } else {
            gl.images.clearImage(t, ZERO, 1f, 0);
        }
        t.lastUse = gl.stream.currentSerial();
    }

    private static final float[] ZERO = new float[4];

    private static boolean coversEveryChannel(boolean[] cm, Tex tex) {
        final Formats.Format f = tex.format;
        return (cm[0] || f.r() == 0) && (cm[1] || f.g() == 0) && (cm[2] || f.b() == 0) && (cm[3] || f.a() == 0);
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

        final boolean inPass = sameAsPass(t);
        final boolean[] drawColor = new boolean[GlState.MAX_DRAW_BUFFERS], nativeColor = new boolean[GlState.MAX_DRAW_BUFFERS];
        boolean anyDraw = false, anyNative = false;
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
                        gl.warnOnce("partial clear of integer color attachment " + i + " is not emulated on Direct3D 12");
                    }
                } else if (full) {
                    nativeColor[i] = true;
                    anyNative = true;
                } else {
                    drawColor[i] = true;
                    anyDraw = true;
                }
            }
        }
        boolean depthDraw = false;
        if ((wantDepth || wantStencil) && t.depth != null) {
            final Tex tex = t.depth;
            final boolean dsFull = (!t.hasDepth || wantDepth || !t.hasStencil) && (!t.hasStencil || wantStencil || !t.hasDepth);
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
                if (!dsFull && tex.contentsUndefined && !tex.needsClear) {
                    depthDraw = true;
                    tex.pendingClear = false;
                }
            } else {
                depthDraw = true;
            }
        }
        final boolean nativeDepth = depthDraw && !stencilPartial;
        if (anyNative || nativeDepth) clearBound(st, t, nativeColor, nativeDepth && wantDepth, nativeDepth && wantStencil, scissored);
        if (anyDraw || depthDraw && !nativeDepth) gl.clearPass.clear(st, t, drawColor, depthDraw && !nativeDepth && wantDepth, depthDraw && !nativeDepth && wantStencil);
    }

    private void clearBound(GlState st, Target t, boolean[] colors, boolean depth, boolean stencil, boolean scissored) {
        ensurePass(t);
        final long list = gl.stream.list();
        int rects = 0;
        if (scissored) {
            final int x0 = Math.max(0, st.scX), y0 = Math.max(0, st.scY);
            scratch.set(JAVA_INT, 3300, x0);
            scratch.set(JAVA_INT, 3304, y0);
            scratch.set(JAVA_INT, 3308, Math.max(x0, Math.min(t.width, st.scX + st.scW)));
            scratch.set(JAVA_INT, 3312, Math.max(y0, Math.min(t.height, st.scY + st.scH)));
            rects = 1;
        }
        final long rect = rects == 0 ? 0L : scratch.address() + 3300;
        for (int i = 0; i < t.colorCount; i++) {
            final Tex tex = t.color[i];
            if (!colors[i] || tex == null) continue;
            setClearColor(tex, st.clearColor);
            Com.v(list, Dx.CL_CLEAR_RTV, gl.descriptors.rtvAt(passRtvRun, i), scratch.address(), rects, rect);
            tex.version++;
        }
        if ((depth || stencil) && passDsv != 0L) {
            final int flags = (depth ? 1 : 0) | (stencil ? 2 : 0);
            Com.clearDsv(list, passDsv, flags, (float) Math.max(0, Math.min(1, st.clearDepth)), st.clearStencil & 0xFF, rects, rect);
            t.depth.version++;
        }
    }

    private static boolean canDefer(Tex tex, int level, int layer, int layers) {
        return level == 0 && layer == 0 && tex.levels == 1 && tex.layers == layers;
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
        if (st.cull && st.cullFace == GL11.GL_FRONT_AND_BACK && topologyClass == Gfx.TOPOLOGY_CLASS_TRIANGLE) return false;
        if (scissorEmpty(st, t)) return false;
        logicDst = null;
        if (st.logicOpEnabled) prog = logicOpProgram(st, prog, t);

        if (!collect(st, prog, t, false)) return false;
        if (pendingComputeBarrier) {
            endPass();
            pendingComputeBarrier = false;
        }
        pendingTransferBarrier = false;
        gl.stream.storageBarrier();
        ensurePass(t);
        final long list = gl.stream.list();
        if (boundSerial != gl.stream.currentSerial()) resetBound();

        final Vao vao = st.vao;
        final int stripCut = indexedDraw && (primitive == Gfx.PRIM_LINE_STRIP || primitive == Gfx.PRIM_TRIANGLE_STRIP) ? (indexType == Gfx.INDEX_UINT16 ? 1 : 2) : 0;
        long pipeline;
        if (prog.id == lastProgram && prog.gen == lastProgramGen && st.pipelineGen == lastPipelineGen && st.dynamicGen == lastDynamicGen && vao.formatGen == lastVaoFormatGen && vao.id == lastVaoId
            && glMode == lastMode && t.globalGen == lastTargetGen && t.fboId == lastFboId && st == lastState && st.rasterizerDiscard == lastDiscard && stripCut == lastStripCut && lastPipeline != 0L) {
            pipeline = lastPipeline;
        } else {
            fillDesc(st, prog, vao, t, topologyClass);
            desc.stripCut = stripCut;
            desc.encode(pipelines.probe);
            pipeline = pipelines.get(desc);
            if (pipeline == 0L) return false;
            lastProgram = prog.id;
            lastProgramGen = prog.gen;
            lastPipelineGen = st.pipelineGen;
            lastDynamicGen = st.dynamicGen;
            lastVaoFormatGen = vao.formatGen;
            lastVaoId = vao.id;
            lastMode = glMode;
            lastTargetGen = t.globalGen;
            lastFboId = t.fboId;
            lastState = st;
            lastDiscard = st.rasterizerDiscard;
            lastStripCut = stripCut;
            lastPipeline = pipeline;
        }
        if (pipeline != boundPipeline) {
            Com.v(list, Dx.CL_SET_PSO, pipeline);
            boundPipeline = pipeline;
        }
        final RootSignatures.Layout layout = prog.rootLayout;
        if (layout.root() != boundRoot) {
            Com.v(list, Dx.CL_GFX_ROOT_SIG, layout.root());
            boundRoot = layout.root();
            forgetRootArguments(0);
            forgetRootArguments(1);
        }
        applyDynamicState(list, st, t, topologyClass);
        bindTables(prog, Program.VS, 0, layout, false, list);
        if (prog.code[Program.FS] != null && !st.rasterizerDiscard) bindTables(prog, Program.FS, 1, layout, false, list);
        if (primitive != boundTopology) {
            Com.v(list, Dx.CL_TOPOLOGY, D3D_TOPOLOGY[primitive]);
            boundTopology = primitive;
        }
        if (!bindVertexBuffers(list, st, prog, vao)) return false;
        if (prog.writesStorage) gl.stream.markStorageWrites();
        markAttachmentWrites(st, t);
        draws++;
        return true;
    }
    private void markAttachmentWrites(GlState st, Target t) {
        final long serial = gl.stream.currentSerial();
        for (int i = 0; i < t.colorCount; i++) {
            final Tex tex = t.color[i];
            if (tex == null) continue;
            tex.lastUse = serial;
            final boolean[] cm = st.colorMask[Math.min(i, GlState.MAX_DRAW_BUFFERS - 1)];
            if (!st.rasterizerDiscard && (cm[0] || cm[1] || cm[2] || cm[3])) tex.version++;
        }
        if (t.depth != null && !st.rasterizerDiscard) {
            final boolean depthWrites = st.depthTest && st.depthMask && t.hasDepth;
            final boolean stencilWrites = st.stencilTest && t.hasStencil && ((st.stencilWriteMask[0] | st.stencilWriteMask[1]) & 0xFF) != 0;
            if (depthWrites || stencilWrites) t.depth.version++;
        }
    }

    private static boolean scissorEmpty(GlState st, Target t) {
        if (!st.scissorTest) return false;
        final int x0 = Math.max(0, st.scX), y0 = Math.max(0, st.scY);
        final int x1 = Math.min(t.width, st.scX + st.scW), y1 = Math.min(t.height, st.scY + st.scH);
        return x1 <= x0 || y1 <= y0;
    }

    private final Tex[] logicScratch = new Tex[LogicOpLowering.MAX_LOCATIONS];
    private Tex[] logicDst;

    private Program logicOpProgram(GlState st, Program base, Target t) {
        final int op = LogicOpLowering.mesaOp(st.logicOp);
        if (op == LogicOpLowering.OP_COPY) return base;
        long key = op;
        boolean any = false;
        final int limit = Math.min(Math.min(t.colorCount, base.colorOutputs), LogicOpLowering.MAX_LOCATIONS);
        for (int loc = 0; loc < limit; loc++) {
            if (t.color[loc] == null) continue;
            final int cls = LogicOpLowering.classOf(t.color[loc].format.pf());
            if (cls == 0) continue;
            key = LogicOpLowering.withClass(key, loc, cls);
            any = true;
        }
        if (!any) return base;
        final Program variant = gl.programs.logicOpVariant(base, key);
        if (variant == null) return base;

        for (int s = 0; s < Program.STAGES; s++) {
            final Program.StageBlock from = base.blocks[s], to = variant.blocks[s];
            if (from == null || to == null) continue;
            MemoryUtil.memCopy(from.address, to.address, from.size);
            to.dirty = true;
        }
        for (int i = 0; i < variant.logicBaseUniform.length; i++) {
            final int b = variant.logicBaseUniform[i];
            if (b < 0) continue;
            final int[] src = base.uniforms.get(b).units, dst = variant.uniforms.get(i).units;
            if (src != null && dst != null && src.length == dst.length) System.arraycopy(src, 0, dst, 0, src.length);
        }

        final Tex[] dsts = new Tex[LogicOpLowering.MAX_LOCATIONS];
        for (int loc = 0; loc < limit; loc++) {
            if (LogicOpLowering.classAt(key, loc) == 0) continue;
            final Tex src = t.color[loc];
            Tex scratch = logicScratch[loc];
            if (scratch == null || scratch.format.pf() != src.format.pf() || scratch.width != t.width || scratch.height != t.height) {
                if (scratch != null) gl.images.release(scratch);
                scratch = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, src.format, t.width, t.height, 1, 1, "logicOpDst" + loc);
                logicScratch[loc] = scratch;
            }
            gl.textures.copyImage(src, t.colorLevel[loc], 0, 0, src.layers > 1 ? t.colorLayer[loc] : 0, scratch, 0, 0, 0, 0, t.width, t.height, 1);
            dsts[loc] = scratch;
        }
        logicDst = dsts;
        return variant;
    }

    private void fillDesc(GlState st, Program prog, Vao vao, Target t, int topologyClass) {
        final Pipelines.Desc d = desc;
        d.program = prog;
        d.topologyClass = topologyClass;
        d.alphaToCoverage = 0;
        d.rasterize = st.rasterizerDiscard ? 0 : 1;
        d.colorCount = t.colorCount;
        for (int i = 0; i < t.colorCount; i++) {
            final Tex tex = t.color[i];
            d.colorFormat[i] = tex == null ? Gfx.PF_INVALID : tex.format.pf();
            final int b = i * 8;
            final int bi = Math.min(i, GlState.MAX_DRAW_BUFFERS - 1);
            final boolean integer = tex != null && tex.format.isInteger();
            d.blend[b] = st.blend[bi] && !integer && tex != null && !st.logicOpEnabled ? 1 : 0;
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
            int wm = (cm[0] ? Gfx.WRITE_RED : 0) | (cm[1] ? Gfx.WRITE_GREEN : 0) | (cm[2] ? Gfx.WRITE_BLUE : 0) | (cm[3] ? Gfx.WRITE_ALPHA : 0);
            if (i >= prog.colorOutputs || tex == null) wm = 0;
            d.blend[b + 7] = wm;
        }
        d.depthFormat = t.depth != null && t.hasDepth ? t.depth.format.pf() : Gfx.PF_INVALID;
        d.stencilFormat = t.depth != null && t.hasStencil ? t.depth.format.pf() : Gfx.PF_INVALID;
        d.clipMask = prog.clipMask ? st.clipDistances & 0xFF : 0;
        d.cull = !st.cull ? Gfx.CULL_NONE : st.cullFace == GL11.GL_FRONT ? Gfx.CULL_FRONT : Gfx.CULL_BACK;
        d.frontCcw = st.frontFace != GL11.GL_CCW;
        d.fillLines = st.polygonModeFront == GL11.GL_LINE ? 1 : 0;
        d.depthClip = !st.depthClamp;
        final boolean lines = st.polygonModeFront == GL11.GL_LINE;
        final boolean offset = topologyClass == Gfx.TOPOLOGY_CLASS_TRIANGLE ? (lines ? st.polyOffsetLine : st.polygonModeFront == GL11.GL_POINT ? st.polyOffsetPoint : st.polyOffsetFill)
            : topologyClass == Gfx.TOPOLOGY_CLASS_LINE ? st.polyOffsetLine : st.polyOffsetPoint;
        d.depthBias = offset ? Math.round(st.polyUnits) : 0;
        d.slopeBias = offset ? st.polyFactor : 0f;
        d.depthBiasClamp = offset ? st.polyClamp : 0f;
        d.ds = depthStencilKey(st, t);

        int n = 0;
        int mask = prog.vertexInputMask;
        while (mask != 0) {
            final int loc = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            d.vLocation[n] = loc;
            final int binding = vao.bindingOf[loc];
            if (vao.enabled[loc] && vao.bindingBuffer[binding] != 0) {
                int fmt = VertexFormats.gfxFormat(vao.size[loc], vao.type[loc], vao.normalized[loc], vao.integer[loc]);
                if (fmt == Gfx.VF_INVALID || !VertexFormats.exact(vao.size[loc], vao.type[loc], vao.normalized[loc])) {
                    gl.warnOnce("vertex attribute type 0x" + Integer.toHexString(vao.type[loc]) + " (normalized=" + vao.normalized[loc] + ") has no exact Direct3D 12 format");
                    if (fmt == Gfx.VF_INVALID) fmt = Gfx.VF_FLOAT4;
                }
                d.vFormat[n] = fmt;
                d.vStride[n] = vao.stride(loc);
                final int divisor = vao.bindingDivisor[binding];
                d.vStep[n] = divisor > 0 ? Gfx.STEP_PER_INSTANCE : Gfx.STEP_PER_VERTEX;
                d.vRate[n] = Math.max(1, divisor);
            } else {
                d.vFormat[n] = switch (prog.inputKind[loc]) {
                    case 1 -> Gfx.VF_INT4;
                    case 2 -> Gfx.VF_UINT4;
                    default -> Gfx.VF_FLOAT4;
                };
                d.vStride[n] = 16;
                d.vStep[n] = Gfx.STEP_CONSTANT;
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
        return new Pipelines.DepthStencilKey(depthTest ? compareOp(st.depthFunc) : Gfx.COMPARE_ALWAYS, depthTest && st.depthMask, stencil, front, back, masks);
    }

    static int topologyClass(int primitive) {
        return switch (primitive) {
            case Gfx.PRIM_POINT -> Gfx.TOPOLOGY_CLASS_POINT;
            case Gfx.PRIM_LINE, Gfx.PRIM_LINE_STRIP -> Gfx.TOPOLOGY_CLASS_LINE;
            default -> Gfx.TOPOLOGY_CLASS_TRIANGLE;
        };
    }

    private void applyDynamicState(long list, GlState st, Target t, int topologyClass) {
        final int stamp = st.dynamicGen * 31 + st.pipelineGen * 7 + t.globalGen * 17 + t.fboId + (st == lastState ? 0 : 13) + topologyClass * 101;
        if (stamp == dynamicStamp && topologyClass == lastClass) return;
        dynamicStamp = stamp;
        lastClass = topologyClass;
        final java.lang.foreign.MemorySegment s = scratch;
        s.set(JAVA_FLOAT, 2048, st.vpX);
        s.set(JAVA_FLOAT, 2052, st.vpY);
        s.set(JAVA_FLOAT, 2056, st.vpW);
        s.set(JAVA_FLOAT, 2060, st.vpH);
        s.set(JAVA_FLOAT, 2064, (float) Math.max(0, Math.min(1, st.depthNear)));
        s.set(JAVA_FLOAT, 2068, (float) Math.max(0, Math.min(1, st.depthFar)));
        Com.v(list, Dx.CL_VIEWPORTS, 1, s.address() + 2048);
        int x0 = 0, y0 = 0, x1 = t.width, y1 = t.height;
        if (st.scissorTest) {
            x0 = Math.max(0, st.scX);
            y0 = Math.max(0, st.scY);
            x1 = Math.max(x0, Math.min(t.width, st.scX + st.scW));
            y1 = Math.max(y0, Math.min(t.height, st.scY + st.scH));
        }
        s.set(JAVA_INT, 2112, x0);
        s.set(JAVA_INT, 2116, y0);
        s.set(JAVA_INT, 2120, x1);
        s.set(JAVA_INT, 2124, y1);
        Com.v(list, Dx.CL_SCISSORS, 1, s.address() + 2112);
        s.set(JAVA_FLOAT, 2144, st.blendColor[0]);
        s.set(JAVA_FLOAT, 2148, st.blendColor[1]);
        s.set(JAVA_FLOAT, 2152, st.blendColor[2]);
        s.set(JAVA_FLOAT, 2156, st.blendColor[3]);
        Com.v(list, Dx.CL_BLEND_FACTOR, s.address() + 2144);
        Com.v(list, Dx.CL_STENCIL_REF, st.stencilRef[0] & 0xFF);
    }
    private void ensureResCapacity(int n) {
        if (resBuffer.length >= n) return;
        final int size = Math.max(n, resBuffer.length * 2);
        resBuffer = Arrays.copyOf(resBuffer, size);
        resOffset = Arrays.copyOf(resOffset, size);
        resSize = Arrays.copyOf(resSize, size);
        resBuf = Arrays.copyOf(resBuf, size);
        resTexture = Arrays.copyOf(resTexture, size);
        resSampler = Arrays.copyOf(resSampler, size);
    }

    boolean collect(GlState st, Program prog, Target target, boolean compute) {
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
                        blk.ringBuffer = gl.uniformRing.gpuAddress;
                        blk.ringOffset = 0L;
                        blk.ringSerial = serial;
                        blk.dirty = false;
                    }
                    resBuffer[bi] = blk.ringBuffer;
                    resOffset[bi] = blk.ringOffset;
                    resSize[bi] = blk.size;
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
                    gl.buffers.toRead(buf.alloc);
                    resBuffer[bi] = buf.alloc.gpuAddress;
                    resOffset[bi] = offset;
                    resSize[bi] = Math.min(65536L, bb.size > 0 ? bb.size : buf.size - offset);
                }
                case STORAGE_BUFFER -> {
                    final Program.BufferBlock bb = prog.ssbos.get(b.ref);
                    final int point = Math.min(bb.glBinding, GlState.MAX_BUFFER_BINDINGS - 1);
                    Buf buf = gl.buffers.get(bb.atomicCounters ? st.atomicBufferAt[point] : st.ssboBuffer[point]);
                    long offset = bb.atomicCounters ? st.atomicOffset[point] : st.ssboOffset[point];
                    final long size = bb.atomicCounters ? st.atomicSize[point] : st.ssboSize[point];
                    final boolean writes = writesAnyStage(b);
                    if (buf == null || buf.alloc == null || offset >= buf.size) {
                        buf = writes ? dummyDeviceBuffer() : dummyBuffer;
                        offset = 0;
                    }
                    if (writes) {
                        gl.buffers.makeDeviceWritable(buf);
                        gl.buffers.toStorage(buf.alloc);
                        gl.buffers.gpuWrite(buf);
                    } else {
                        gl.buffers.toRead(buf.alloc);
                        gl.buffers.use(buf);
                    }
                    resBuf[bi] = buf;
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
                            gl.warnOnce("texel buffers are not bound on Direct3D 12");
                            view = dummy2D.view != 0L ? dummy2D.view : sampledView(dummy2D, Gfx.TEX_2D);
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
                            gl.images.transition(sampled, SRV_STATE);
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
                        final Tex used = gl.textures.storageImage(st, unit);
                        if (writesAnyStage(b)) gl.images.transition(used, Dx.STATE_UNORDERED_ACCESS);
                        else gl.images.transition(used, SRV_STATE);
                    }
                }
            }
        }
        return true;
    }

    private Tex feedbackCopy(Tex tex) {
        Tex copy = feedbackCopies.get(tex.id);
        if (copy != null && (copy.format.pf() != tex.format.pf() || copy.width != tex.width || copy.height != tex.height || copy.levels != tex.levels || copy.layers != tex.layers || copy.target != tex.target)) {
            gl.images.release(copy);
            copy = null;
        }
        if (copy == null) {
            copy = new Tex(-tex.id - 2_000_000, false);
            copy.target = tex.target;
            copy.format = tex.format;
            copy.width = tex.width;
            copy.height = tex.height;
            copy.depth = tex.depth;
            copy.layers = tex.layers;
            copy.levels = tex.levels;
            copy.label = "feedback" + tex.id;
            gl.images.create(copy, false);
            feedbackCopies.put(tex.id, copy);
            feedbackVersions.put(tex.id, -1L);
            feedbackEpochs.put(tex.id, -1L);
        }
        copy.minFilter = tex.minFilter;
        copy.baseLevel = tex.baseLevel;
        copy.maxLevel = tex.maxLevel;
        System.arraycopy(tex.swizzle, 0, copy.swizzle, 0, 4);
        copy.sampleStencil = tex.sampleStencil;
        copy.viewDirty = copy.viewDirty || copy.baseLevel != tex.baseLevel;
        final boolean current = feedbackVersions.get(tex.id) == tex.version || feedbackEpochs.get(tex.id) == passEpoch;
        if (!current) {
            gl.textures.copyWhole(tex, copy);
            feedbackVersions.put(tex.id, tex.version);
            feedbackEpochs.put(tex.id, passEpoch + 1);
        }
        return copy;
    }

    private boolean writesAnyStage(Program.Binding b) {
        for (boolean w : b.uav) if (w) return true;
        return false;
    }

    private Buf dummyDeviceBuffer;

    private Buf dummyDeviceBuffer() {
        if (dummyDeviceBuffer == null) {
            final Buf b = new Buf(-2);
            b.size = 65536;
            b.memoryUsage = GpuBuffer.USAGE_DEVICE;
            b.alloc = GpuBuffer.create(gl.gpu, 65536, GpuBuffer.USAGE_DEVICE);
            b.label = "dummyDeviceBuffer";
            dummyDeviceBuffer = b;
        }
        return dummyDeviceBuffer;
    }

    private long nullSrv, nullUav;

    private void ensureNullDescriptors() {
        if (nullSrv != 0L) return;
        nullSrv = gl.descriptors.allocView();
        nullUav = gl.descriptors.allocView();
        for (int i = 0; i < 16; i++) scratch.set(JAVA_INT, 2300 + i * 4L, 0);
        scratch.set(JAVA_INT, 2300, com.gtnewhorizons.angelica.dx12.device.Dxgi.R8G8B8A8_UNORM);
        scratch.set(JAVA_INT, 2304, 4);
        scratch.set(JAVA_INT, 2308, 0x1688);
        scratch.set(JAVA_INT, 2316, 1);
        Com.v(gl.gpu.device, Dx.DEV_CREATE_SRV, 0L, scratch.address() + 2300, nullSrv);
        for (int i = 0; i < 16; i++) scratch.set(JAVA_INT, 2300 + i * 4L, 0);
        scratch.set(JAVA_INT, 2300, com.gtnewhorizons.angelica.dx12.device.Dxgi.R8G8B8A8_UNORM);
        scratch.set(JAVA_INT, 2304, 4);
        Com.v(gl.gpu.device, Dx.DEV_CREATE_UAV, 0L, 0L, scratch.address() + 2300, nullUav);
    }

    private void writeBufferSrv(long cpu, Buf buf, long offset, long size) {
        for (int i = 0; i < 10; i++) scratch.set(JAVA_INT, 2300 + i * 4L, 0);
        scratch.set(JAVA_INT, 2300, 39);
        scratch.set(JAVA_INT, 2304, 1);
        scratch.set(JAVA_INT, 2308, 0x1688);
        scratch.set(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 2316, offset >> 2);
        scratch.set(JAVA_INT, 2324, (int) Math.max(1, size >> 2));
        scratch.set(JAVA_INT, 2332, 1);
        Com.v(gl.gpu.device, Dx.DEV_CREATE_SRV, buf.handle(), scratch.address() + 2300, cpu);
    }

    private void writeBufferUav(long cpu, Buf buf, long offset, long size) {
        for (int i = 0; i < 10; i++) scratch.set(JAVA_INT, 2300 + i * 4L, 0);
        scratch.set(JAVA_INT, 2300, 39);
        scratch.set(JAVA_INT, 2304, 1);
        scratch.set(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 2308, offset >> 2);
        scratch.set(JAVA_INT, 2316, (int) Math.max(1, size >> 2));
        scratch.set(JAVA_INT, 2332, 1);
        Com.v(gl.gpu.device, Dx.DEV_CREATE_UAV, buf.handle(), 0L, scratch.address() + 2300, cpu);
    }

    private void bindTables(Program prog, int slot, int stage, RootSignatures.Layout layout, boolean compute, long list) {
        ensureNullDescriptors();
        final int cbvN = prog.cbvCount[slot], srvN = prog.srvCount[slot], smpN = prog.samplerCount[slot], uavN = prog.uavCount[slot];
        final var ds = gl.descriptors;
        final int bit = 1 << slot;
        final int cache = compute ? 2 : stage;
        final int views = srvN + uavN;
        final int sigLength = views + 3 * prog.ssbos.size();
        if (viewScratch.length < sigLength) viewScratch = new long[sigLength * 2];
        if (samplerScratch.length < smpN) samplerScratch = new long[smpN * 2];
        java.util.Arrays.fill(viewScratch, 0, sigLength, 0L);
        java.util.Arrays.fill(samplerScratch, 0, smpN, 0L);
        final int rootCbv = compute ? Dx.CL_CS_ROOT_CBV : Dx.CL_GFX_ROOT_CBV;
        final int pCbv = layout.param(stage, RootSignatures.K_CBV);
        final long[] cbvBound = boundCbv[cache];
        int sig = views;
        final int n = prog.bindings.size();
        for (int bi = 0; bi < n; bi++) {
            final Program.Binding b = prog.bindings.get(bi);
            if ((b.stageFlags & bit) == 0) continue;
            switch (b.kind) {
                case DEFAULT_BLOCK, UNIFORM_BUFFER -> {
                    final int reg = b.bufferReg[slot];
                    final long address = resBuffer[bi] + resOffset[bi];
                    if (cbvBound[reg] != address) {
                        Com.v(list, rootCbv, pCbv + reg, address);
                        cbvBound[reg] = address;
                    }
                }
                case STORAGE_BUFFER -> {
                    viewScratch[sig++] = resBuffer[bi];
                    viewScratch[sig++] = resOffset[bi] << 1 | (b.uav[slot] ? 1 : 0);
                    viewScratch[sig++] = resSize[bi];
                }
                case COMBINED_SAMPLER, SAMPLED_IMAGE, SAMPLER -> {
                    for (int e = 0; e < b.count; e++) {
                        if (b.textureReg[slot] >= 0 && resTexture[bi] != null) viewScratch[b.textureReg[slot] + e] = resTexture[bi][e];
                        if (b.samplerReg[slot] >= 0 && resSampler[bi] != null) samplerScratch[b.samplerReg[slot] + e] = resSampler[bi][e];
                    }
                }
                case STORAGE_IMAGE -> {
                    for (int e = 0; e < b.count; e++) {
                        if (resTexture[bi] == null) continue;
                        if (b.uav[slot]) viewScratch[srvN + b.textureReg[slot] + e] = resTexture[bi][e];
                        else viewScratch[b.textureReg[slot] + e] = resTexture[bi][e];
                    }
                }
            }
        }
        final int setTable = compute ? Dx.CL_CS_ROOT_TABLE : Dx.CL_GFX_ROOT_TABLE;
        if (views > 0) {
            long[] last = boundViews[cache];
            if (boundViewLength[cache] != sig || boundEpoch[cache] != com.gtnewhorizons.angelica.dx12.device.Descriptors.epoch || !java.util.Arrays.equals(last, 0, sig, viewScratch, 0, sig)) {
                final long srvTable, uavTable;
                if (sig == views) {
                    srvTable = srvN > 0 ? ds.viewTable(viewScratch, 0, srvN, nullSrv) : ds.ringGpu(0);
                    uavTable = uavN > 0 ? ds.viewTable(viewScratch, srvN, uavN, nullUav) : ds.ringGpu(0);
                } else {
                    final int srvBase = srvN > 0 ? ds.ringAlloc(srvN) : 0, uavBase = uavN > 0 ? ds.ringAlloc(uavN) : 0;
                    ds.copyToRing(srvBase, viewScratch, 0, srvN, nullSrv);
                    ds.copyToRing(uavBase, viewScratch, srvN, uavN, nullUav);
                    for (int bi = 0; bi < n; bi++) {
                        final Program.Binding b = prog.bindings.get(bi);
                        if ((b.stageFlags & bit) == 0 || b.kind != Program.BindingKind.STORAGE_BUFFER) continue;
                        final Buf buf = bufferOf(bi);
                        if (b.uav[slot]) writeBufferUav(ds.ringCpu(uavBase + b.bufferReg[slot]), buf, resOffset[bi], resSize[bi]);
                        else writeBufferSrv(ds.ringCpu(srvBase + b.bufferReg[slot]), buf, resOffset[bi], resSize[bi]);
                    }
                    srvTable = ds.ringGpu(srvBase);
                    uavTable = ds.ringGpu(uavBase);
                }
                final int pSrv = layout.param(stage, RootSignatures.K_SRV), pUav = layout.param(stage, RootSignatures.K_UAV);
                if (pSrv >= 0) Com.v(list, setTable, pSrv, srvTable);
                if (pUav >= 0) Com.v(list, setTable, pUav, uavTable);
                if (last.length < sig) last = boundViews[cache] = new long[sig * 2];
                System.arraycopy(viewScratch, 0, last, 0, sig);
                boundViewLength[cache] = sig;
                boundEpoch[cache] = com.gtnewhorizons.angelica.dx12.device.Descriptors.epoch;
                bindCalls++;
            }
        }
        final int pSmp = layout.param(stage, RootSignatures.K_SAMPLER);
        if (pSmp >= 0) {
            final long[] lastSamplers = boundSamplers[cache];
            if (boundSamplerTable[cache] != 0L && boundSamplerCount[cache] == smpN && boundSamplerGen[cache] == ds.samplerGeneration
                && java.util.Arrays.equals(lastSamplers, 0, smpN, samplerScratch, 0, smpN)) return;
            final long table = ds.samplerTable(samplerScratch, smpN, nullSampler());
            if (table != boundSamplerTable[cache]) {
                Com.v(list, setTable, pSmp, table);
                boundSamplerTable[cache] = table;
            }
            if (lastSamplers.length < smpN) boundSamplers[cache] = new long[smpN * 2];
            System.arraycopy(samplerScratch, 0, boundSamplers[cache], 0, smpN);
            boundSamplerCount[cache] = smpN;
            boundSamplerGen[cache] = ds.samplerGeneration;
        }
    }

    private final long[][] boundCbv = new long[3][RootSignatures.MAX_CBV];
    private final long[][] boundViews = {new long[64], new long[64], new long[64]};
    private final int[] boundViewLength = {-1, -1, -1};
    private final long[] boundEpoch = new long[3];
    private final long[] boundSamplerTable = new long[3];
    private final long[][] boundSamplers = {new long[16], new long[16], new long[16]};
    private final int[] boundSamplerCount = {-1, -1, -1};
    private final int[] boundSamplerGen = new int[3];

    private void forgetRootArguments(int cache) {
        java.util.Arrays.fill(boundCbv[cache], -1L);
        boundViewLength[cache] = -1;
        boundSamplerTable[cache] = 0L;
    }

    private Buf[] resBuf = new Buf[32];

    private Buf bufferOf(int bi) {
        return resBuf[bi];
    }
    private static int viewTypeFor(Program.Binding b) {
        return switch (b.dim) {
            case 0 -> b.arrayed ? Gfx.TEX_2D_ARRAY : Gfx.TEX_2D;
            case 2 -> Gfx.TEX_3D;
            case 3 -> b.arrayed ? Gfx.TEX_CUBE_ARRAY : Gfx.TEX_CUBE;
            default -> b.arrayed ? Gfx.TEX_2D_ARRAY : Gfx.TEX_2D;
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
            case Gfx.TEX_2D_ARRAY -> own == Gfx.TEX_2D || own == Gfx.TEX_CUBE || own == Gfx.TEX_CUBE_ARRAY;
            case Gfx.TEX_2D -> own == Gfx.TEX_2D_ARRAY && tex.layers == 1;
            default -> false;
        };
    }

    private static final int SAMPLED_FLOAT = 0, SAMPLED_SINT = 1, SAMPLED_UINT = 2;

    private final Int2ObjectOpenHashMap<Tex> extraDummies = new Int2ObjectOpenHashMap<>();

    private Tex dummyFor(int viewType, boolean shadow, int glType) {
        if (shadow && viewType == Gfx.TEX_2D) return dummyDepth;
        final int kind = sampledKind(glType);
        if (kind == SAMPLED_FLOAT) {
            switch (viewType) {
                case Gfx.TEX_2D -> { return dummy2D; }
                case Gfx.TEX_2D_ARRAY -> { return dummy2DArray; }
                case Gfx.TEX_3D -> { return dummy3D; }
                case Gfx.TEX_CUBE -> { return dummyCube; }
                default -> { }
            }
        }
        final int key = (shadow ? 1 << 12 : 0) | kind << 8 | viewType;
        Tex t = extraDummies.get(key);
        if (t != null) return t;
        final Formats.Format f = shadow ? gl.textures.format(GL14.GL_DEPTH_COMPONENT24)
            : kind == SAMPLED_FLOAT ? Formats.RGBA8 : gl.textures.format(kind == SAMPLED_SINT ? GL30.GL_RGBA8I : GL30.GL_RGBA8UI);
        final int layers = viewType == Gfx.TEX_CUBE || viewType == Gfx.TEX_CUBE_ARRAY ? 6 : 1;
        t = gl.textures.internalTexture(Images.glTargetForViewType(viewType), f, 1, 1, 1, layers, "dummy" + kind + "/" + viewType);
        endPass();
        gl.images.clearImage(t, new float[] {0, 0, 0, 1}, 1f, 0);
        extraDummies.put(key, t);
        return t;
    }

    private static int sampledKind(int glType) {
        return switch (glType) {
            case GL30.GL_INT_SAMPLER_1D, GL30.GL_INT_SAMPLER_2D, GL30.GL_INT_SAMPLER_3D, GL30.GL_INT_SAMPLER_CUBE, GL30.GL_INT_SAMPLER_1D_ARRAY,
                 GL30.GL_INT_SAMPLER_2D_ARRAY, GL31.GL_INT_SAMPLER_2D_RECT, GL31.GL_INT_SAMPLER_BUFFER, GL40.GL_INT_SAMPLER_CUBE_MAP_ARRAY,
                 GL32.GL_INT_SAMPLER_2D_MULTISAMPLE, GL32.GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY -> SAMPLED_SINT;
            case GL30.GL_UNSIGNED_INT_SAMPLER_1D, GL30.GL_UNSIGNED_INT_SAMPLER_2D, GL30.GL_UNSIGNED_INT_SAMPLER_3D, GL30.GL_UNSIGNED_INT_SAMPLER_CUBE,
                 GL30.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY, GL30.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY, GL31.GL_UNSIGNED_INT_SAMPLER_2D_RECT,
                 GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER, GL40.GL_UNSIGNED_INT_SAMPLER_CUBE_MAP_ARRAY, GL32.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE,
                 GL32.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY -> SAMPLED_UINT;
            default -> SAMPLED_FLOAT;
        };
    }

    long sampledView(Tex tex, int viewType) {
        if (tex.sampleStencil && tex.format.isDepth() && tex.format.isStencil()) gl.textures.ensureUsage(tex, Gfx.USAGE_PIXEL_FORMAT_VIEW);
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
        gl.descriptors.freeView(tex.view);
        tex.view = 0L;
        final long[] extra = extraViews.remove(tex.id);
        if (extra != null) for (long v : extra) gl.descriptors.freeView(v);
    }

    public void invalidateDescriptorCache() {
    }

    public long[] forgetViews(Tex t) {
        final long[] extra = extraViews.remove(t.id);
        if (extra == null) return new long[0];
        return Arrays.stream(extra).filter(v -> v != 0L).toArray();
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
        final int magF = linearOk && mag == GL11.GL_LINEAR ? Gfx.FILTER_LINEAR : Gfx.FILTER_NEAREST;
        final int minF = linearOk && (min == GL11.GL_LINEAR || min == GL11.GL_LINEAR_MIPMAP_NEAREST || min == GL11.GL_LINEAR_MIPMAP_LINEAR) ? Gfx.FILTER_LINEAR : Gfx.FILTER_NEAREST;
        final boolean mip = min != GL11.GL_NEAREST && min != GL11.GL_LINEAR;
        final int mipMode = !mip ? Gfx.MIP_NOT_MIPMAPPED : linearOk && (min == GL11.GL_NEAREST_MIPMAP_LINEAR || min == GL11.GL_LINEAR_MIPMAP_LINEAR) ? Gfx.MIP_LINEAR : Gfx.MIP_NEAREST;
        final boolean compare = cmpMode == GL14.GL_COMPARE_R_TO_TEXTURE && tex != null && tex.format.isDepth() || shadowBinding && tex != null && tex.format.isDepth() && cmpMode != GL11.GL_NONE;
        final int maxAniso = (int) Math.max(1, Math.min(Math.round(aniso), Math.round(gl.gpu.maxSamplerAnisotropy)));
        final int borderColor = borderColor(border);
        final float lodBias = Math.max(-15f, Math.min(15f, bias));
        long key = magF | minF << 1 | mipMode << 2 | (long) wrap(ws) << 4 | (long) wrap(wt) << 7 | (long) wrap(wr) << 10
            | (long) (compare ? 1 : 0) << 13 | (long) compareOp(cmpFunc) << 14 | (long) borderColor << 17 | (long) (mip ? maxAniso : 1) << 20;
        key = key * 31 + Float.floatToIntBits(minLod);
        key = key * 31 + Float.floatToIntBits(maxLod);
        key = key * 31 + Float.floatToIntBits(lodBias);
        for (int i = 0; i < 4; i++) key = key * 31 + Float.floatToIntBits(border[i]);
        long sampler = samplers.get(key);
        if (sampler == 0L) {
            sampler = samplerDescriptor(minF, magF, mipMode, wrap(ws), wrap(wt), wrap(wr), mip && linearOk ? maxAniso : 1, mip ? Math.max(0f, minLod) : 0f,
                mip ? Math.max(Math.max(0f, minLod), Math.min(maxLod, 1000f)) : 0f, lodBias, compare, compareOp(cmpFunc), border);
            objectCreations++;
            samplers.put(key, sampler);
        }        if (so == null && tex != null) {
            tex.sampler = sampler;
            tex.samplerDirty = false;
        }
        return sampler;
    }

    private static final int[] D3D_ADDRESS = {3, 5, 1, 2, 4, 4};

    private long samplerDescriptor(int minF, int magF, int mipMode, int ws, int wt, int wr, int maxAniso, float minLod, float maxLod, float lodBias, boolean compare, int cmpOp, float[] border) {
        final long handle = gl.descriptors.allocSampler();
        int filter = minF << 4 | magF << 2 | (mipMode == Gfx.MIP_LINEAR ? 1 : 0);
        if (maxAniso > 1) filter = 0x55;
        if (compare) filter |= 0x80;
        final long o = 2400;
        scratch.set(JAVA_INT, o, filter);
        scratch.set(JAVA_INT, o + 4, D3D_ADDRESS[ws]);
        scratch.set(JAVA_INT, o + 8, D3D_ADDRESS[wt]);
        scratch.set(JAVA_INT, o + 12, D3D_ADDRESS[wr]);
        scratch.set(JAVA_FLOAT, o + 16, lodBias);
        scratch.set(JAVA_INT, o + 20, Math.max(1, maxAniso));
        scratch.set(JAVA_INT, o + 24, compare ? cmpOp + 1 : 8);
        for (int i = 0; i < 4; i++) scratch.set(JAVA_FLOAT, o + 28 + i * 4L, border[i]);
        scratch.set(JAVA_FLOAT, o + 44, minLod);
        scratch.set(JAVA_FLOAT, o + 48, maxLod);
        Com.v(gl.gpu.device, Dx.DEV_CREATE_SAMPLER, scratch.address() + o, handle);
        return handle;
    }

    private static int wrap(int gl) {
        return switch (gl) {
            case GL12.GL_CLAMP_TO_EDGE, GL11.GL_CLAMP -> Gfx.ADDRESS_CLAMP_TO_EDGE;
            case GL13.GL_CLAMP_TO_BORDER -> Gfx.ADDRESS_CLAMP_TO_BORDER;
            case GL14.GL_MIRRORED_REPEAT -> Gfx.ADDRESS_MIRROR_REPEAT;
            case org.lwjgl.opengl.GL44.GL_MIRROR_CLAMP_TO_EDGE -> Gfx.ADDRESS_MIRROR_CLAMP_TO_EDGE;
            default -> Gfx.ADDRESS_REPEAT;
        };
    }

    private static int borderColor(float[] b) {
        final boolean opaque = b[3] >= 0.5f;
        final boolean white = b[0] >= 0.5f && b[1] >= 0.5f && b[2] >= 0.5f;
        return opaque ? (white ? Gfx.BORDER_OPAQUE_WHITE : Gfx.BORDER_OPAQUE_BLACK) : Gfx.BORDER_TRANSPARENT_BLACK;
    }

    private final float[] lastConstValues = new float[64];
    private long constRingBuffer;
    private long constRingOffset;
    private boolean constValid;
    private long constRingSerial = -1;

    private boolean bindVertexBuffers(long list, GlState st, Program prog, Vao vao) {
        final long serial = gl.stream.currentSerial();
        int n = 0;
        int mask = prog.vertexInputMask;
        while (mask != 0) {
            final int loc = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            final int binding = vao.bindingOf[loc];
            final Buf buf = vao.enabled[loc] ? gl.buffers.get(vao.bindingBuffer[binding]) : null;
            final long v = 1024 + 16L * n;
            if (buf != null && buf.alloc != null) {
                gl.buffers.use(buf);
                gl.buffers.toRead(buf.alloc);
                final long offset = vao.bindingOffset[binding] + vao.relativeOffset[loc];
                scratch.set(JAVA_LONG, v, buf.alloc.gpuAddress + offset);
                scratch.set(JAVA_INT, v + 8, (int) Math.max(4, Math.min(Integer.MAX_VALUE, buf.alloc.size - offset)));
                scratch.set(JAVA_INT, v + 12, Math.max(4, vao.stride(loc)));
            } else {
                if (!constValid || constRingSerial != serial || !Arrays.equals(lastConstValues, st.attribValue)) {
                    gl.uniformRing.alloc(Vao.MAX_ATTRIBS * 16L, 16);
                    for (int i = 0; i < Vao.MAX_ATTRIBS * 4; i++) MemoryUtil.memPutFloat(gl.uniformRing.address + i * 4L, st.attribValue[i]);
                    constRingBuffer = gl.uniformRing.gpuAddress;
                    constRingOffset = 0L;
                    constRingSerial = serial;
                    System.arraycopy(st.attribValue, 0, lastConstValues, 0, lastConstValues.length);
                    constValid = true;
                }
                scratch.set(JAVA_LONG, v, constRingBuffer + constRingOffset + loc * 16L);
                scratch.set(JAVA_INT, v + 8, 16);
                scratch.set(JAVA_INT, v + 12, 0);
            }
            n++;
        }
        if (n > 0 && (n != boundVertexViews || java.lang.foreign.MemorySegment.mismatch(scratch, 1024, 1024 + 16L * n, boundVertexBytes, 0, 16L * n) >= 0)) {
            Com.v(list, Dx.CL_VERTEX_BUFFERS, 0, n, scratch.address() + 1024);
            java.lang.foreign.MemorySegment.copy(scratch, 1024, boundVertexBytes, 0, 16L * n);
            boundVertexViews = n;
        }
        return true;
    }

    private final java.lang.foreign.MemorySegment boundVertexBytes = java.lang.foreign.Arena.global().allocate(16L * 32, 8);
    private int boundVertexViews = -1;

    boolean bindIndexBuffer(long gpuAddress, long bytes, int type) {
        indexAddress = gpuAddress;
        indexBytes = bytes;
        indexType = type;
        return true;
    }

    void drawArrays(int vertexCount, int instances, int first, int baseInstance) {
        Com.v(gl.stream.list(), Dx.CL_DRAW, vertexCount, instances, first, baseInstance);
    }

    private void setIndexBuffer(long list) {
        final long key = indexAddress ^ indexBytes * 0x9E3779B97F4A7C15L ^ (long) indexType << 62;
        if (key == boundIndexView) return;
        scratch.set(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 1900, indexAddress);
        scratch.set(JAVA_INT, 1908, (int) Math.min(Integer.MAX_VALUE, indexBytes));
        scratch.set(JAVA_INT, 1912, indexType == Gfx.INDEX_UINT16 ? com.gtnewhorizons.angelica.dx12.device.Dxgi.R16_UINT : com.gtnewhorizons.angelica.dx12.device.Dxgi.R32_UINT);
        Com.v(list, Dx.CL_INDEX_BUFFER, scratch.address() + 1900);
        boundIndexView = key;
    }

    private long boundIndexView = -1L;

    void drawIndexed(int indexCount, int instances, int firstIndex, int baseVertex, int baseInstance) {
        final long list = gl.stream.list();
        setIndexBuffer(list);
        Com.v(list, Dx.CL_DRAW_INDEXED, indexCount, instances, firstIndex, baseVertex, baseInstance);
    }

    public static final int INDIRECT_DRAW = 0, INDIRECT_DRAW_INDEXED = 1, INDIRECT_DISPATCH = 2;
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap signatures = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    private long signature(int kind, int stride) {
        final long key = (long) kind << 32 | stride;
        long sig = signatures.get(key);
        if (sig != 0L) return sig;
        scratch.set(JAVA_INT, 3200, kind);
        for (int i = 1; i < 4; i++) scratch.set(JAVA_INT, 3200 + i * 4L, 0);
        scratch.set(JAVA_INT, 3216, stride);
        scratch.set(JAVA_INT, 3220, 1);
        scratch.set(JAVA_LONG, 3224, scratch.address() + 3200);
        scratch.set(JAVA_INT, 3232, 0);
        Com.check(Com.hr(gl.gpu.device, Dx.DEV_CREATE_COMMAND_SIGNATURE, scratch.address() + 3216, 0L, Dx.IID_COMMAND_SIGNATURE, scratch.address() + 3240), "CreateCommandSignature");
        sig = scratch.get(JAVA_LONG, 3240);
        signatures.put(key, sig);
        return sig;
    }

    void executeIndirect(int kind, int stride, int count, long argumentBuffer, long offset) {
        executeIndirect(kind, stride, count, argumentBuffer, offset, 0L, 0L);
    }

    void executeIndirect(int kind, int stride, int maxCount, long argumentBuffer, long offset, long countBuffer, long countOffset) {
        final long list = gl.stream.list();
        if (kind == INDIRECT_DRAW_INDEXED) setIndexBuffer(list);
        Com.v(list, Dx.CL_EXECUTE_INDIRECT, signature(kind, stride), maxCount, argumentBuffer, offset, countBuffer, countOffset);
    }

    long drawList() {
        return gl.stream.list();
    }

    java.lang.foreign.MemorySegment scratch() {
        return scratch;
    }
    boolean prepareDispatch(GlState st) {
        final Program prog = gl.programs.current(st);
        if (prog == null || !prog.linked || !prog.compute) return false;
        if (!collect(st, prog, null, true)) return false;
        endPass();
        final long pipeline = pipelines.compute(prog);
        if (pipeline == 0L) return false;
        final long list = gl.stream.list();
        gl.stream.storageBarrier();
        Com.v(list, Dx.CL_SET_PSO, pipeline);
        Com.v(list, Dx.CL_CS_ROOT_SIG, prog.rootLayout.root());
        forgetRootArguments(2);
        bindTables(prog, Program.CS, 0, prog.rootLayout, true, list);
        boundPipeline = 0L;
        boundRoot = 0L;
        if (prog.writesStorage) gl.stream.markStorageWrites();
        pendingComputeBarrier = false;
        pendingTransferBarrier = false;
        return true;
    }
    static int compareOp(int f) {
        return switch (f) {
            case GL11.GL_NEVER -> Gfx.COMPARE_NEVER;
            case GL11.GL_LESS -> Gfx.COMPARE_LESS;
            case GL11.GL_EQUAL -> Gfx.COMPARE_EQUAL;
            case GL11.GL_LEQUAL -> Gfx.COMPARE_LESS_EQUAL;
            case GL11.GL_GREATER -> Gfx.COMPARE_GREATER;
            case GL11.GL_NOTEQUAL -> Gfx.COMPARE_NOT_EQUAL;
            case GL11.GL_GEQUAL -> Gfx.COMPARE_GREATER_EQUAL;
            default -> Gfx.COMPARE_ALWAYS;
        };
    }

    static int stencilOp(int op) {
        return switch (op) {
            case GL11.GL_ZERO -> Gfx.STENCIL_ZERO;
            case GL11.GL_REPLACE -> Gfx.STENCIL_REPLACE;
            case GL11.GL_INCR -> Gfx.STENCIL_INCREMENT_CLAMP;
            case GL11.GL_DECR -> Gfx.STENCIL_DECREMENT_CLAMP;
            case GL11.GL_INVERT -> Gfx.STENCIL_INVERT;
            case GL14.GL_INCR_WRAP -> Gfx.STENCIL_INCREMENT_WRAP;
            case GL14.GL_DECR_WRAP -> Gfx.STENCIL_DECREMENT_WRAP;
            default -> Gfx.STENCIL_KEEP;
        };
    }

    static int blendFactor(int f) {
        return switch (f) {
            case GL11.GL_ZERO -> Gfx.BLEND_ZERO;
            case GL11.GL_ONE -> Gfx.BLEND_ONE;
            case GL11.GL_SRC_COLOR -> Gfx.BLEND_SRC_COLOR;
            case GL11.GL_ONE_MINUS_SRC_COLOR -> Gfx.BLEND_ONE_MINUS_SRC_COLOR;
            case GL11.GL_DST_COLOR -> Gfx.BLEND_DST_COLOR;
            case GL11.GL_ONE_MINUS_DST_COLOR -> Gfx.BLEND_ONE_MINUS_DST_COLOR;
            case GL11.GL_SRC_ALPHA -> Gfx.BLEND_SRC_ALPHA;
            case GL11.GL_ONE_MINUS_SRC_ALPHA -> Gfx.BLEND_ONE_MINUS_SRC_ALPHA;
            case GL11.GL_DST_ALPHA -> Gfx.BLEND_DST_ALPHA;
            case GL11.GL_ONE_MINUS_DST_ALPHA -> Gfx.BLEND_ONE_MINUS_DST_ALPHA;
            case GL14.GL_CONSTANT_COLOR -> Gfx.BLEND_BLEND_COLOR;
            case GL14.GL_ONE_MINUS_CONSTANT_COLOR -> Gfx.BLEND_ONE_MINUS_BLEND_COLOR;
            case GL14.GL_CONSTANT_ALPHA -> Gfx.BLEND_BLEND_ALPHA;
            case GL14.GL_ONE_MINUS_CONSTANT_ALPHA -> Gfx.BLEND_ONE_MINUS_BLEND_ALPHA;
            case GL11.GL_SRC_ALPHA_SATURATE -> Gfx.BLEND_SRC_ALPHA_SATURATED;
            case org.lwjgl.opengl.GL33.GL_SRC1_COLOR -> Gfx.BLEND_SRC1_COLOR;
            case org.lwjgl.opengl.GL33.GL_ONE_MINUS_SRC1_COLOR -> Gfx.BLEND_ONE_MINUS_SRC1_COLOR;
            case org.lwjgl.opengl.GL15.GL_SRC1_ALPHA -> Gfx.BLEND_SRC1_ALPHA;
            case org.lwjgl.opengl.GL33.GL_ONE_MINUS_SRC1_ALPHA -> Gfx.BLEND_ONE_MINUS_SRC1_ALPHA;
            default -> Gfx.BLEND_ONE;
        };
    }

    static int blendOp(int eq) {
        return switch (eq) {
            case GL14.GL_FUNC_SUBTRACT -> Gfx.BLEND_OP_SUBTRACT;
            case GL14.GL_FUNC_REVERSE_SUBTRACT -> Gfx.BLEND_OP_REVERSE_SUBTRACT;
            case GL14.GL_MIN -> Gfx.BLEND_OP_MIN;
            case GL14.GL_MAX -> Gfx.BLEND_OP_MAX;
            default -> Gfx.BLEND_OP_ADD;
        };
    }

    static int primitive(int mode) {
        return switch (mode) {
            case GL11.GL_POINTS -> Gfx.PRIM_POINT;
            case GL11.GL_LINES -> Gfx.PRIM_LINE;
            case GL11.GL_LINE_STRIP, GL11.GL_LINE_LOOP -> Gfx.PRIM_LINE_STRIP;
            case GL11.GL_TRIANGLE_STRIP -> Gfx.PRIM_TRIANGLE_STRIP;
            default -> Gfx.PRIM_TRIANGLE;
        };
    }

    static boolean drawable(int mode) {
        return switch (mode) {
            case GL32.GL_LINES_ADJACENCY, GL32.GL_LINE_STRIP_ADJACENCY, GL32.GL_TRIANGLES_ADJACENCY, GL32.GL_TRIANGLE_STRIP_ADJACENCY, GL40.GL_PATCHES -> false;
            default -> true;
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
        samplers.clear();
        if (dummyDeviceBuffer != null && dummyDeviceBuffer.alloc != null) {
            dummyDeviceBuffer.alloc.destroy();
            dummyDeviceBuffer.alloc = null;
        }
        pipelines.destroy();
    }
}
