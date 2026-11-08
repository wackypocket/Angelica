package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.frame.HostRingBase;
import com.gtnewhorizons.angelica.glemu.pipeline.TokenDesc;
import com.gtnewhorizons.angelica.glemu.pipeline.TokenVertexFormats;
import com.gtnewhorizons.angelica.glemu.resource.AllocBuf;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.SamplerObj;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;
import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;
import com.gtnewhorizons.angelica.glemu.resource.TokenTex;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import com.gtnewhorizons.angelica.glemu.shader.LogicOpLowering;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;
import com.gtnewhorizons.angelica.glemu.shader.TokenBinding;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Binding resolution in {@link NativeTokens}. */
public abstract class TokenRendererBase<T extends TokenTex, P extends ProgramBase<P>, R extends TargetBase<T>, B extends AllocBuf<A>, A> extends RendererBase<T, P, R> {
    private static final int MAX_RESOLVE_PASSES = 4;

    public long draws, passes, depthDiscards, bindCalls, objectCreations;
    private final TokenGlBase<T, P, B, A> token;
    private final Int2ObjectOpenHashMap<T> feedbackCopies = new Int2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap feedbackVersions = new Int2LongOpenHashMap();
    private final Int2LongOpenHashMap feedbackEpochs = new Int2LongOpenHashMap();
    protected long[] resBuffer = new long[32], resOffset = new long[32], resSize = new long[32];
    protected long[][] resTexture = new long[32][], resSampler = new long[32][];

    protected TokenRendererBase(TokenGlBase<T, P, B, A> gl, R passTarget, R drawTarget, R scratchTarget, T[] logicScratch) {
        super(gl, passTarget, drawTarget, scratchTarget, logicScratch);
        this.token = gl;
    }

    /** Counts image creations, which can invalidate bindings already resolved for a draw. */
    private long imageGeneration() {
        return token.images().generation;
    }

    private void copyWhole(T from, T to) {
        token.textures().copyWhole(from, to);
    }

    @Override protected boolean renderable(int pf) {
        return token.images().renderable(pf);
    }

    @Override protected void clearImage(T t, float[] color, float depth, int stencil, int aspects) {
        token.images().clearImage(t, color, depth, stencil, aspects);
    }

    @Override public void onImageReleased(T t) {
        super.onImageReleased(t);
        final T copy = feedbackCopies.remove(t.id);
        if (copy != null) {
            feedbackVersions.remove(t.id);
            feedbackEpochs.remove(t.id);
            releaseImage(copy);
        }
    }

    protected static boolean polygonOffsetOn(GlState st, int topologyClass, boolean lines) {
        if (topologyClass == NativeTokens.TOPOLOGY_CLASS_TRIANGLE) return lines ? st.polyOffsetLine : st.polygonModeFront == GL11.GL_POINT ? st.polyOffsetPoint : st.polyOffsetFill;
        return topologyClass == NativeTokens.TOPOLOGY_CLASS_LINE ? st.polyOffsetLine : st.polyOffsetPoint;
    }

    @Override protected void releaseSharedImages() {
        for (T t : feedbackCopies.values()) releaseImage(t);
        feedbackCopies.clear();
        super.releaseSharedImages();
    }

    /** A current copy of the texture, always refreshed, for an operation that samples it while writing to it. */
    public T copyForSampling(T tex) {
        feedbackVersions.put(tex.id, -1L);
        feedbackEpochs.put(tex.id, -1L);
        return feedbackCopy(tex);
    }

    /** A shader may sample an attachment of the open pass; it reads a copy, refreshed at most once per pass or texture version. */
    protected T feedbackCopy(T tex) {
        T copy = feedbackCopies.get(tex.id);
        if (copy != null && (copy.format.pf() != tex.format.pf() || copy.width != tex.width || copy.height != tex.height || copy.levels != tex.levels || copy.layers != tex.layers || copy.target != tex.target)) {
            releaseImage(copy);
            copy = null;
        }
        if (copy == null) {
            copy = newTex(-tex.id - 2_000_000);
            copy.target = tex.target;
            copy.format = tex.format;
            copy.width = tex.width;
            copy.height = tex.height;
            copy.depth = tex.depth;
            copy.layers = tex.layers;
            copy.levels = tex.levels;
            copy.label = "feedback" + tex.id;
            createImage(copy);
            feedbackCopies.put(tex.id, copy);
            feedbackVersions.put(tex.id, -1L);
            feedbackEpochs.put(tex.id, -1L);
        }
        copy.viewDirty = copy.viewDirty || copy.baseLevel != tex.baseLevel || copy.maxLevel != tex.maxLevel
            || copy.sampleStencil != tex.sampleStencil || !Arrays.equals(copy.swizzle, tex.swizzle);
        copy.minFilter = tex.minFilter;
        copy.baseLevel = tex.baseLevel;
        copy.maxLevel = tex.maxLevel;
        System.arraycopy(tex.swizzle, 0, copy.swizzle, 0, 4);
        copy.sampleStencil = tex.sampleStencil;
        final boolean current = feedbackVersions.get(tex.id) == tex.version || feedbackEpochs.get(tex.id) == passEpoch;
        if (!current) {
            copyWhole(tex, copy);
            feedbackVersions.put(tex.id, tex.version);
            feedbackEpochs.put(tex.id, passEpoch + 1);
        }
        return copy;
    }

    protected void markAttachmentWrites(GlState st, R t) {
        final long serial = currentSerial();
        for (int i = 0; i < t.colorCount; i++) {
            final T tex = t.color[i];
            if (tex == null) continue;
            tex.lastUse = serial;
            final boolean[] cm = st.colorMask[Math.min(i, GlState.MAX_DRAW_BUFFERS - 1)];
            if (!st.rasterizerDiscard && (cm[0] || cm[1] || cm[2] || cm[3])) {
                tex.version++;
                tex.markGpuWrite(serial);
            }
        }
        if (t.depth != null && !st.rasterizerDiscard) {
            final boolean depthWrites = st.depthTest && st.depthMask && t.hasDepth;
            final boolean stencilWrites = st.stencilTest && t.hasStencil && ((st.stencilWriteMask[0] | st.stencilWriteMask[1]) & 0xFF) != 0;
            if (depthWrites || stencilWrites) {
                t.depth.version++;
                t.depth.markGpuWrite(serial);
            }
        }
    }

    protected static boolean scissorEmpty(GlState st, TargetBase<?> t) {
        if (!st.scissorTest) return false;
        final int x0 = Math.max(0, st.scX), y0 = Math.max(0, st.scY);
        final int x1 = Math.min(t.width, st.scX + st.scW), y1 = Math.min(t.height, st.scY + st.scH);
        return x1 <= x0 || y1 <= y0;
    }

    protected abstract long createSampledView(T tex, int viewType);

    protected abstract void releaseView(long view);

    /** Runs before a texture's view is looked up, for a backend that must change the image to view it this way. */
    protected void prepareSampledView(T tex) {
    }

    public final long sampledView(T tex, int viewType) {
        prepareSampledView(tex);
        if (tex.viewDirty) refreshViews(tex);
        if (TokenMaps.textureTypeFor(tex) == viewType) {
            if (tex.view == 0L) {
                tex.view = createSampledView(tex, viewType);
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
            extra[viewType] = createSampledView(tex, viewType);
            objectCreations++;
        }
        return extra[viewType];
    }

    private void refreshViews(T tex) {
        tex.viewDirty = false;
        if (tex.view != 0L) releaseView(tex.view);
        tex.view = 0L;
        final long[] extra = extraViews.remove(tex.id);
        if (extra == null) return;
        for (long v : extra) if (v != 0L) releaseView(v);
    }

    protected abstract void drawClear(GlState st, R t, boolean[] color, boolean depth, boolean stencil);

    public void clear(GlState st, int mask) {
        final R t = resolveDrawTarget(st);
        if (!hasSize(t)) return;
        final boolean scissored = st.scissorTest && (st.scX > 0 || st.scY > 0 || st.scX + st.scW < t.width || st.scY + st.scH < t.height);
        if (st.scissorTest && (st.scW <= 0 || st.scH <= 0)) return;
        final boolean wantColor = (mask & GL11.GL_COLOR_BUFFER_BIT) != 0;
        final boolean wantDepth = (mask & GL11.GL_DEPTH_BUFFER_BIT) != 0 && st.depthMask && t.hasDepth;
        final boolean wantStencil = (mask & GL11.GL_STENCIL_BUFFER_BIT) != 0 && t.hasStencil && (st.stencilWriteMask[0] & 0xFF) != 0;
        final boolean stencilPartial = wantStencil && (st.stencilWriteMask[0] & 0xFF) != 0xFF;

        // Unscissored full clears outside a pass are deferred to the next one; the rest are drawn by the clear pass.
        // That pass writes one layer and no integer format, so such clears are deferred where they can be and otherwise
        // cleared by region outside the pass; a layered target ends the open pass first so its clear can be deferred.
        if (sameAsPass(t) && !scissored && t.colorCount > 0 && t.color[0] != null && t.colorLayers[0] > 1) endPass();
        final boolean inPass = sameAsPass(t);
        final boolean[] drawColor = new boolean[GlState.MAX_DRAW_BUFFERS];
        boolean anyDraw = false;
        if (wantColor) {
            for (int i = 0; i < t.colorCount; i++) {
                final T tex = t.color[i];
                if (tex == null) continue;
                final boolean[] cm = st.colorMask[Math.min(i, GlState.MAX_DRAW_BUFFERS - 1)];
                final boolean full = coversEveryChannel(cm, tex);
                final boolean none = !cm[0] && !cm[1] && !cm[2] && !cm[3];
                if (none) continue;
                if (!inPass && !scissored && full && !clearFollowsSrgbState(tex) && canDefer(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i])) {
                    // The open pass may hold this image under another target; a later draw there must land after the clear.
                    releaseFromPass(tex);
                    tex.pendingClear = true;
                    tex.writes++;
                    tex.clearAspects = Formats.ASPECT_COLOR;
                    System.arraycopy(st.clearColor, 0, tex.clearColor, 0, 4);
                } else if (tex.format.isInteger()) {
                    if (!scissored && full && canDefer(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i])) {
                        releaseFromPass(tex);
                        tex.pendingClear = true;
                        tex.writes++;
                        tex.clearAspects = Formats.ASPECT_COLOR;
                        System.arraycopy(st.clearColor, 0, tex.clearColor, 0, 4);
                    } else {
                        base.clearAttachmentRegion(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i], st);
                    }
                } else if (t.colorLayers[i] > 1 && full) {
                    base.clearAttachmentRegion(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i], st);
                } else {
                    drawColor[i] = true;
                    anyDraw = true;
                }
            }
        }
        boolean depthDraw = false;
        if ((wantDepth || wantStencil) && t.depth != null) {
            final T tex = t.depth;
            if (!inPass && !scissored && !stencilPartial && canDefer(tex, t.depthLevel, t.depthLayer, t.depthLayers)) {
                if (!tex.pendingClear) tex.clearAspects = 0;
                releaseFromPass(tex);
                tex.pendingClear = true;
                tex.writes++;
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
        if (anyDraw || depthDraw) drawClear(st, t, drawColor, depthDraw && wantDepth, depthDraw && wantStencil);
    }

    protected final void createDummies(TexturesBase<T> textures) {
        dummy2D = textures.internalTexture(GL11.GL_TEXTURE_2D, Formats.RGBA8, 1, 1, 1, 1, "dummy2D");
        dummy2DArray = textures.internalTexture(GL30.GL_TEXTURE_2D_ARRAY, Formats.RGBA8, 1, 1, 1, 1, "dummy2DArray");
        dummy3D = textures.internalTexture(GL12.GL_TEXTURE_3D, Formats.RGBA8, 1, 1, 1, 1, "dummy3D");
        dummyCube = textures.internalTexture(GL13.GL_TEXTURE_CUBE_MAP, Formats.RGBA8, 1, 1, 1, 6, "dummyCube");
        dummyDepth = textures.internalTexture(GL11.GL_TEXTURE_2D, textures.format(GL14.GL_DEPTH_COMPONENT24), 1, 1, 1, 1, "dummyDepth");
        dummyStorage = textures.internalStorageTexture(Formats.RGBA8, "dummyStorage");
        dummyStorageRead = textures.internalStorageTexture(Formats.RGBA8, "dummyStorageRead", 256);
        final float[] black = {0, 0, 0, 1};
        clearImage(dummyStorageRead, new float[4], 1f, 0, dummyStorageRead.format.aspect());
        for (T t : List.of(dummy2D, dummy2DArray, dummy3D, dummyCube, dummyDepth, dummyStorage)) clearImage(t, black, 1f, 0, t.format.aspect());
    }

    protected final P drawProgram(GlState st) {
        final P prog = programs().current(st);
        if (prog != null && prog.linked && !prog.compute && !prog.vertexless && !prog.noShaders) return prog;
        if (prog != null && prog.linked && prog.noShaders) {
            base.warnOnce("draw with program " + st.program + ", which has no shaders, draws nothing");
            return null;
        }
        base.warnOnce("draw without a linked graphics program (program " + st.program + ")");
        base.recordError(GL11.GL_INVALID_OPERATION);
        return null;
    }

    protected final boolean canDraw(GlState st, R t, int topologyClass) {
        if (!hasSize(t)) return false;
        if (st.vpW <= 0 || st.vpH <= 0) return false;
        if (st.scissorTest && (st.scW <= 0 || st.scH <= 0)) return false;
        if (st.cull && st.cullFace == GL11.GL_FRONT_AND_BACK && topologyClass == NativeTokens.TOPOLOGY_CLASS_TRIANGLE) return false;
        return !scissorEmpty(st, t);
    }

    /** Whether the backend reads this attribute type as GL means it, rather than as the closest format it has. */
    protected abstract boolean vertexFormatExact(int type, int size, boolean normalized);

    protected final void fillAttachmentDesc(TokenDesc<P> d, GlState st, P prog, R t, int topologyClass) {
        d.program = prog;
        d.samples = targetSamples(t);
        d.alphaToCoverage = d.samples > 1 && st.multisample && st.sampleAlphaToCoverage ? 1 : 0;
        d.alphaToOne = d.samples > 1 && st.multisample && st.sampleAlphaToOne ? 1 : 0;
        d.coverageMask = GlBase.coverageMask(st, d.samples) == (1 << d.samples) - 1 ? -1 : GlBase.coverageMask(st, d.samples);
        if (d.samples > 1 && !st.multisample && !centersSamples()) base.warnOnce("drawing to a multisampled target with GL_MULTISAMPLE off still averages edges");
        d.topologyClass = topologyClass;
        d.rasterize = st.rasterizerDiscard ? 0 : 1;
        d.colorCount = t.colorCount;
        for (int i = 0; i < t.colorCount; i++) {
            final T tex = t.color[i];
            d.colorFormat[i] = tex == null ? NativeTokens.PF_INVALID : st.framebufferSrgb ? tex.format.pf() : Formats.linearOf(tex.format.pf());
            final int b = i * 8;
            final int bi = Math.min(i, GlState.MAX_DRAW_BUFFERS - 1);
            final boolean integer = tex != null && tex.format.isInteger();
            d.blend[b] = st.blend[bi] && !integer && tex != null && !st.logicOpEnabled ? 1 : 0;
            if (d.blend[b] != 0) {
                d.blend[b + 1] = TokenMaps.blendFactor(st.blendSrcRgb[bi]);
                d.blend[b + 2] = TokenMaps.blendFactor(st.blendDstRgb[bi]);
                d.blend[b + 3] = TokenMaps.blendOp(st.blendEqRgb[bi]);
                d.blend[b + 4] = TokenMaps.blendFactor(st.blendSrcA[bi]);
                d.blend[b + 5] = TokenMaps.blendFactor(st.blendDstA[bi]);
                d.blend[b + 6] = TokenMaps.blendOp(st.blendEqA[bi]);
            } else {
                d.blend[b + 1] = d.blend[b + 2] = d.blend[b + 3] = d.blend[b + 4] = d.blend[b + 5] = d.blend[b + 6] = 0;
            }
            final boolean[] cm = st.colorMask[bi];
            int wm = (cm[0] ? NativeTokens.WRITE_RED : 0) | (cm[1] ? NativeTokens.WRITE_GREEN : 0) | (cm[2] ? NativeTokens.WRITE_BLUE : 0) | (cm[3] ? NativeTokens.WRITE_ALPHA : 0);
            if (i >= prog.colorOutputs || tex == null) wm = 0;
            d.blend[b + 7] = wm;
        }
        d.depthFormat = t.depth != null && t.hasDepth ? t.depth.format.pf() : NativeTokens.PF_INVALID;
        d.stencilFormat = t.depth != null && t.hasStencil ? t.depth.format.pf() : NativeTokens.PF_INVALID;
        d.clipMask = prog.clipMask ? st.clipDistances & 0xFF : 0;
    }

    protected final void fillVertexDesc(TokenDesc<P> d, P prog, Vao vao) {
        int n = 0;
        int mask = prog.vertexInputMask;
        while (mask != 0) {
            final int location = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            final int loc = attribOf(prog, location);
            d.vLocation[n] = location;
            final int binding = vao.bindingOf[loc];
            if (readsArray(prog, vao, location)) {
                int fmt = prog.inputDoubles[loc] > 0 ? (doubleWords(prog, vao, location) == 2 ? NativeTokens.VF_UINT2 : NativeTokens.VF_UINT4)
                    : TokenVertexFormats.signedAs(prog.inputKind[loc], TokenVertexFormats.format(vao.readSize(loc), vao.readType(loc), vao.readNormalized(loc), vao.integer[loc]));
                if (prog.inputDoubles[loc] == 0 && (fmt == NativeTokens.VF_INVALID || !vertexFormatExact(vao.readType(loc), vao.readSize(loc), vao.readNormalized(loc)))) {
                    base.warnOnce("vertex attribute type 0x" + Integer.toHexString(vao.type[loc]) + " (size=" + vao.size[loc] + ", normalized=" + vao.normalized[loc] + ") has no exact " + base.apiName() + " format");
                    if (fmt == NativeTokens.VF_INVALID) fmt = NativeTokens.VF_FLOAT4;
                }
                d.vFormat[n] = fmt;
                final int divisor = vao.bindingDivisor[binding];
                if (vao.readStride(loc) == 0) {
                    d.vStride[n] = 16;
                    d.vStep[n] = NativeTokens.STEP_CONSTANT;
                    d.vRate[n] = 0;
                } else {
                    d.vStride[n] = vao.readStride(loc);
                    d.vStep[n] = divisor > 0 ? NativeTokens.STEP_PER_INSTANCE : NativeTokens.STEP_PER_VERTEX;
                    d.vRate[n] = Math.max(1, divisor);
                }
            } else {
                d.vFormat[n] = switch (prog.inputKind[location]) {
                    case 1 -> NativeTokens.VF_INT4;
                    case 2 -> NativeTokens.VF_UINT4;
                    default -> NativeTokens.VF_FLOAT4;
                };
                d.vStride[n] = 16;
                d.vStep[n] = NativeTokens.STEP_CONSTANT;
                d.vRate[n] = 0;
            }
            n++;
        }
        d.vertexCount = n;
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

    /** Whether a pass can put every sample at the pixel's centre, for a draw with GL_MULTISAMPLE off. */
    protected boolean centersSamples() {
        return false;
    }

    protected int targetSamples(TargetBase<T> t) {
        for (int i = 0; i < t.colorCount; i++) {
            if (t.color[i] != null) return t.color[i].samples;
        }
        return t.depth != null ? t.depth.samples : 1;
    }

    /** The texture a sampler binding reads, initialised, with a placeholder when the unit holds nothing it can view. */
    protected final T sampledTexture(GlState st, String uniform, int unit, TokenBinding b, int viewType) {
        T tex = logicDst != null && uniform.startsWith(LogicOpLowering.DST_SAMPLER_PREFIX)
            ? logicDst[Integer.parseInt(uniform.substring(LogicOpLowering.DST_SAMPLER_PREFIX.length()))]
            : textures().get(st.textures[unit][TokenMaps.targetSlot(b.dim, b.arrayed)]);
        if (tex == null || !tex.hasImage() || !TokenMaps.compatibleView(tex, viewType)
            || !uniform.startsWith(LogicOpLowering.DST_SAMPLER_PREFIX) && !token.sampledComplete(st, unit, tex)) tex = dummyFor(viewType, b.shadow, b.glType);
        if (tex.pendingClear || tex.needsClear) materialize(tex);
        return tex;
    }

    protected abstract List<? extends TokenBinding> bindingsOf(P prog);

    protected abstract B dummyBufferFor(boolean writes);

    protected abstract void resolveUniformBuffer(int bi, B buf, long offset);

    protected abstract boolean writesStorage(TokenBinding b, ProgramBase.BufferBlock block);

    /** Records a storage buffer's use. Returns the buffer to bind, which at offset 0 replaces one that cannot be written. */
    protected abstract B resolveStorageBuffer(int bi, B buf, boolean writes);

    protected record SamplerKey(long state, float minLod, float maxLod, float lodBias, float borderR, float borderG, float borderB, float borderA) {}

    /** What a backend's sampler object is built from. */
    protected record SamplerState(int minFilter, int magFilter, int mipMode, int wrapS, int wrapT, int wrapR, int maxAniso, boolean mip, boolean linearOk,
                                  float minLod, float maxLod, float lodBias, boolean compare, int compareOp, float[] border) {}

    protected abstract long createSampler(SamplerState s);

    protected final Object2LongOpenHashMap<SamplerKey> samplers = new Object2LongOpenHashMap<>();

    protected long samplerFor(GlState st, int unit, T tex) {
        final SamplerObj so = base.samplerObjects.get(st.samplers[unit]);
        if (so == null && tex != null && !tex.samplerDirty && tex.sampler != 0L) return tex.sampler;
        final boolean integer = tex != null && (tex.format.isInteger() || tex.format.isStencil() && tex.samplesStencil());
        final boolean linearOk = tex == null || !integer && token.images().filterable(tex.format.pf());
        final int traits = (integer ? 1 : 0) | (linearOk ? 2 : 0) | (tex != null && tex.format.isDepth() ? 4 : 0);
        if (so != null && so.sampler(traits) != 0L) return so.sampler(traits);
        final SamplerSource src = SamplerSource.of(so, tex);
        final int min = src.min();
        final float[] border = src.border();
        final int magF = linearOk && src.mag() == GL11.GL_LINEAR ? NativeTokens.FILTER_LINEAR : NativeTokens.FILTER_NEAREST;
        final int minF = linearOk && (min == GL11.GL_LINEAR || min == GL11.GL_LINEAR_MIPMAP_NEAREST || min == GL11.GL_LINEAR_MIPMAP_LINEAR) ? NativeTokens.FILTER_LINEAR : NativeTokens.FILTER_NEAREST;
        final boolean mip = min != GL11.GL_NEAREST && min != GL11.GL_LINEAR;
        final int mipMode = !mip ? NativeTokens.MIP_NOT_MIPMAPPED : linearOk && (min == GL11.GL_NEAREST_MIPMAP_LINEAR || min == GL11.GL_LINEAR_MIPMAP_LINEAR) ? NativeTokens.MIP_LINEAR : NativeTokens.MIP_NEAREST;
        final boolean compare = src.cmpMode() == GL14.GL_COMPARE_R_TO_TEXTURE && tex != null && tex.format.isDepth();
        final int maxAniso = (int) Math.max(1, Math.min(Math.round(src.aniso()), Math.round(base.gpuCaps().maxSamplerAnisotropy)));
        final int wrapS = TokenMaps.wrap(src.ws()), wrapT = TokenMaps.wrap(src.wt()), wrapR = TokenMaps.wrap(src.wr());
        final int compareOp = TokenMaps.compareOp(src.cmpFunc());
        final float lodBias = ShaderCompiler.lodBiasInShader ? 0f : Math.max(-15f, Math.min(15f, src.bias()));
        final long state = magF | minF << 1 | mipMode << 2 | (long) wrapS << 4 | (long) wrapT << 7 | (long) wrapR << 10
            | (long) (compare ? 1 : 0) << 13 | (long) compareOp << 14 | (long) (mip ? maxAniso : 1) << 20;
        final SamplerKey key = new SamplerKey(state, src.minLod(), src.maxLod(), lodBias, border[0], border[1], border[2], border[3]);
        long sampler = samplers.getLong(key);
        if (sampler == 0L) {
            sampler = createSampler(new SamplerState(minF, magF, mipMode, wrapS, wrapT, wrapR, maxAniso, mip, linearOk, src.minLod(), src.maxLod(), lodBias, compare, compareOp, border));
            objectCreations++;
            samplers.put(key, sampler);
        }
        if (so != null) so.keepSampler(traits, sampler);
        if (so == null && tex != null) {
            tex.sampler = sampler;
            tex.samplerDirty = false;
        }
        return sampler;
    }

    protected abstract long storageView(GlState st, int unit, TokenBinding b);

    protected final void resolveBindings(GlState st, P prog, R target) {
        final long serial = currentSerial();
        final List<? extends TokenBinding> bindings = bindingsOf(prog);
        final int n = bindings.size();
        ensureResCapacity(n);
        for (int bi = 0; bi < n; bi++) {
            final TokenBinding b = bindings.get(bi);
            switch (b.kind) {
                case DEFAULT_BLOCK -> resolveDefaultBlock(bi, prog.blocks[b.ref], serial);
                case UNIFORM_BUFFER -> {
                    final int point = Math.min(prog.ubos.get(b.ref).glBinding, GlState.MAX_BUFFER_BINDINGS - 1);
                    final B buf = token.buffers().get(st.uboBuffer[point]);
                    final long offset = st.uboOffset[point];
                    if (buf == null || !buf.hasStorage() || offset >= buf.size) resolveUniformBuffer(bi, dummyBufferFor(false), 0);
                    else resolveUniformBuffer(bi, buf, offset);
                }
                case STORAGE_BUFFER -> resolveStorage(bi, b, prog.ssbos.get(b.ref), st);
                case COMBINED_SAMPLER, SAMPLED_IMAGE, SAMPLER -> resolveSamplers(bi, b, prog.uniforms.get(b.ref), st, target, serial);
                case STORAGE_IMAGE -> {
                    final ProgramBase.Uniform u = prog.uniforms.get(b.ref);
                    long[] views = resTexture[bi];
                    if (views == null || views.length < b.count) views = resTexture[bi] = new long[b.count];
                    for (int e = 0; e < b.count; e++) {
                        final int unit = Math.min(Math.max(0, u.units[e]), GlState.MAX_IMAGE_UNITS - 1);
                        final T tex = token.textures().storageImage(st, unit);
                        if (target != null && target.contains(tex)) releaseFromPass(tex);
                        views[e] = storageView(st, unit, b);
                    }
                }
            }
        }
    }

    private void resolveDefaultBlock(int bi, ProgramBase.StageBlock blk, long serial) {
        if (blk.dirty || blk.ringSerial != serial) {
            final HostRingBase<?> ring = token.uniformRing();
            ring.alloc(blk.size, base.gpuCaps().minUniformBufferOffsetAlignment);
            MemoryUtil.memCopy(blk.address, ring.address, blk.size);
            blk.ringBuffer = ring.buffer;
            blk.ringOffset = ring.offset;
            blk.ringSerial = serial;
            blk.dirty = false;
        }
        resBuffer[bi] = blk.ringBuffer;
        resOffset[bi] = blk.ringOffset;
    }

    private void resolveStorage(int bi, TokenBinding b, ProgramBase.BufferBlock bb, GlState st) {
        final int point = Math.min(bb.glBinding, GlState.MAX_BUFFER_BINDINGS - 1);
        B buf = token.buffers().get(bb.atomicCounters ? st.atomicBufferAt[point] : st.ssboBuffer[point]);
        long offset = bb.atomicCounters ? st.atomicOffset[point] : st.ssboOffset[point];
        final long size = bb.atomicCounters ? st.atomicSize[point] : st.ssboSize[point];
        final boolean writes = writesStorage(b, bb);
        if (buf == null || !buf.hasStorage() || offset >= buf.size) {
            buf = dummyBufferFor(writes);
            offset = 0;
        }
        final B bound = resolveStorageBuffer(bi, buf, writes);
        if (bound != buf) offset = 0;
        resBuffer[bi] = bound.handle();
        resOffset[bi] = offset;
        resSize[bi] = size > 0 ? Math.min(size, bound.size - offset) : bound.size - offset;
    }

    private void resolveSamplers(int bi, TokenBinding b, ProgramBase.Uniform u, GlState st, R target, long serial) {
        long[] views = resTexture[bi], samps = resSampler[bi];
        if (views == null || views.length < b.count) views = resTexture[bi] = new long[b.count];
        if (samps == null || samps.length < b.count) samps = resSampler[bi] = new long[b.count];
        for (int e = 0; e < b.count; e++) {
            final int unit = Math.min(Math.max(0, u.units[e]), GlState.MAX_UNITS - 1);
            long sampler = 0L, view = 0L;
            if (b.kind != ProgramBase.BindingKind.SAMPLER) {
                final int viewType = TokenMaps.viewTypeFor(b.dim, b.arrayed);
                final T tex = sampledTexture(st, u.name, unit, b, viewType);
                final T sampled = target != null && target.contains(tex) ? feedbackCopy(tex) : tex;
                view = sampledView(sampled, viewType);
                sampled.lastUse = serial;
                tex.lastUse = serial;
                if (b.kind == ProgramBase.BindingKind.COMBINED_SAMPLER) sampler = samplerFor(st, unit, tex);
            } else {
                sampler = samplerFor(st, unit, null);
            }
            views[e] = view;
            samps[e] = sampler;
        }
    }

    protected final void collect(GlState st, P prog, R target) {
        for (int pass = 1; ; pass++) {
            final long imagesBefore = imageGeneration();
            resolveBindings(st, prog, target);
            if (imageGeneration() == imagesBefore) return;
            if (pass == MAX_RESOLVE_PASSES) {
                base.warnOnce("program " + prog.id + " kept recreating images while its bindings were resolved");
                return;
            }
        }
    }

    @Override protected final int dummyTarget(int viewType) {
        return TokenMaps.glTargetForViewType(viewType);
    }

    @Override protected final void clearDummy(T t) {
        clearImage(t, new float[] {0, 0, 0, 1}, 1f, 0, t.format.aspect());
    }
}
