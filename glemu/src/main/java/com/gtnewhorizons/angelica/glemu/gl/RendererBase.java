package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.frame.HostRingBase;
import com.gtnewhorizons.angelica.glemu.resource.DefaultFramebufferBase;
import com.gtnewhorizons.angelica.glemu.resource.Fbo;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.SamplerObj;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import com.gtnewhorizons.angelica.glemu.shader.LogicOpLowering;
import com.gtnewhorizons.angelica.glemu.shader.BorderLowering;
import com.gtnewhorizons.angelica.glemu.shader.SampleRateLowering;
import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryUtil;

/**
 * The backend-independent half of a renderer: attachment resolution, pass ending, deferred clears, logic-op variants and
 * feedback copies. Pass encoding, descriptor binding and pipeline state stay with each backend.
 */
public abstract class RendererBase<T extends TexBase, P extends ProgramBase<P>, R extends TargetBase<T>> {
    protected static final float[] ZERO = new float[4];
    protected static final int SAMPLED_FLOAT = 0, SAMPLED_SINT = 1, SAMPLED_UINT = 2;

    public T dummy2D, dummy2DArray, dummy3D, dummyCube, dummyDepth, dummyStorage, dummyStorageRead;
    private final Int2ObjectOpenHashMap<T> extraDummies = new Int2ObjectOpenHashMap<>();

    protected final GlBase<T, P, ?> base;
    protected final R passTarget;
    public final R drawTarget;
    protected final R scratchTarget;
    public boolean passActive;
    public int targetGen = 1;
    protected long passEpoch;

    /** After glTextureBarrier a feedback copy has to show what was drawn since it was made. */
    public final void barrier() {
        passEpoch += 2;
    }
    protected final Int2ObjectOpenHashMap<long[]> extraViews = new Int2ObjectOpenHashMap<>();
    protected final T[] logicScratch;
    protected T[] logicDst;
    public boolean pendingComputeBarrier;
    private GlState constAttribState;
    private int constAttribVersion;
    /** Where in the constants' slot a vertex input's doubles begin, and the zeros that stand for a second half an array doesn't have. */
    protected static final long CONSTANT_DOUBLES = Vao.MAX_ATTRIBS * 16L, CONSTANT_ZEROS = CONSTANT_DOUBLES + Vao.MAX_ATTRIBS * 32L;
    private long constAttribSerial = -1;

    protected RendererBase(GlBase<T, P, ?> base, R passTarget, R drawTarget, R scratchTarget, T[] logicScratch) {
        this.base = base;
        this.passTarget = passTarget;
        this.drawTarget = drawTarget;
        this.scratchTarget = scratchTarget;
        this.logicScratch = logicScratch;
    }

    protected TexturesBase<T> textures() {
        return base.textures();
    }

    protected ProgramsBase<P> programs() {
        return base.programs();
    }

    protected abstract DefaultFramebufferBase<T> defaultFramebuffer();

    protected abstract T newTex(int id);

    protected void releaseImage(T t) {
        base.releaseImage(t);
    }

    protected void createImage(T t) {
        base.createImage(t, false);
    }

    protected boolean renderable(int pf) {
        return true;
    }

    protected boolean sameFormat(int pfA, int pfB) {
        return pfA == pfB;
    }

    public abstract void clear(GlState st, int mask);

    @SuppressWarnings("unchecked")
    final void resolveInto(TargetBase<T> into, Fbo draw, GlState st) {
        resolve((R) into, draw, st, true);
    }

    protected abstract void clearImage(T t, float[] color, float depth, int stencil, int aspects);

    /**
     * Only the aspects the deferred clear asked for take the stored values. The other aspect keeps its contents, or takes its default on a fresh image.
     */
    protected void clearPending(T t) {
        final boolean depth = (t.clearAspects & Formats.ASPECT_DEPTH) != 0, stencil = (t.clearAspects & Formats.ASPECT_STENCIL) != 0;
        final int aspects = t.needsClear ? t.format.aspect() : t.clearAspects;
        t.pendingClear = false;
        clearImage(t, t.clearColor, depth || !t.format.isDepth() ? t.clearDepth : 1f, stencil ? t.clearStencil : 0, aspects);
    }

    protected void copyImage(T src, int srcLevel, int sx, int sy, int sz, T dst, int dstLevel, int dx, int dy, int dz, int w, int h, int d) {
        textures().copyImage(src, srcLevel, sx, sy, sz, dst, dstLevel, dx, dy, dz, w, h, d);
    }

    protected abstract int dummyTarget(int viewType);

    private static boolean arrayedSampler(int glType) {
        return glType == GL32.GL_SAMPLER_2D_MULTISAMPLE_ARRAY || glType == GL32.GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY || glType == GL32.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY;
    }

    private static boolean multisampleSampler(int glType) {
        return arrayedSampler(glType) || glType == GL32.GL_SAMPLER_2D_MULTISAMPLE || glType == GL32.GL_INT_SAMPLER_2D_MULTISAMPLE || glType == GL32.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE;
    }

    protected abstract void clearDummy(T t);

    /** What an unbound sampler reads: opaque black, in a format and shape its shader can sample. */
    protected final T dummyFor(int viewType, boolean shadow, int glType) {
        final boolean multisampled = multisampleSampler(glType);
        final int target = multisampled ? (arrayedSampler(glType) ? GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY : GL32.GL_TEXTURE_2D_MULTISAMPLE) : dummyTarget(viewType);
        if (shadow && target == GL11.GL_TEXTURE_2D) return dummyDepth;
        final int kind = sampledKind(glType);
        if (!shadow && !multisampled && kind == SAMPLED_FLOAT) {
            switch (target) {
                case GL11.GL_TEXTURE_2D -> { return dummy2D; }
                case GL30.GL_TEXTURE_2D_ARRAY -> { return dummy2DArray; }
                case GL12.GL_TEXTURE_3D -> { return dummy3D; }
                case GL13.GL_TEXTURE_CUBE_MAP -> { return dummyCube; }
                default -> { }
            }
        }
        final int key = (multisampled ? 1 << 13 : 0) | (shadow ? 1 << 12 : 0) | kind << 8 | viewType;
        T t = extraDummies.get(key);
        if (t != null) return t;
        final Formats.Format f = shadow ? textures().format(GL14.GL_DEPTH_COMPONENT24)
            : kind == SAMPLED_FLOAT ? Formats.RGBA8 : textures().format(kind == SAMPLED_SINT ? GL30.GL_RGBA8I : GL30.GL_RGBA8UI);
        final int layers = target == GL13.GL_TEXTURE_CUBE_MAP || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY ? 6 : 1;
        // A multisampled sampler can only read an image with several samples, however few it needs to say black.
        final int samples = multisampled ? 4 : 1;
        t = textures().internalTexture(target, f, 1, 1, 1, layers, "dummy" + kind + "/" + viewType, created -> created.samples = samples);
        endPass();
        clearDummy(t);
        extraDummies.put(key, t);
        return t;
    }

    /** Stands in for an unusable image a shader may write. One per unit, so that what lands in it can't come back as a read of another unit, which GL says reads zero. */
    public final T unusableImage(int unit) {
        final int key = 1 << 20 | unit;
        T t = extraDummies.get(key);
        if (t == null) {
            t = textures().internalStorageTexture(Formats.RGBA8, "dummyImage" + unit, 256);
            t.needsClear = true;
            extraDummies.put(key, t);
        }
        return t;
    }

    protected final void releaseDummyTextures() {
        for (T t : Arrays.asList(dummy2D, dummy2DArray, dummy3D, dummyCube, dummyDepth, dummyStorage, dummyStorageRead)) {
            if (t != null) releaseImage(t);
        }
        for (T t : extraDummies.values()) releaseImage(t);
        extraDummies.clear();
    }

    protected void releaseSharedImages() {
        for (T t : logicScratch) if (t != null) releaseImage(t);
    }

    protected long currentSerial() {
        return base.currentSerial();
    }

    public abstract void endPass();

    public abstract void destroy();

    protected void onViewsForgotten() {}

    public R resolveDrawTarget(GlState st) {
        final Fbo fbo = base.framebuffers.get(st.drawFbo);
        resolve(drawTarget, fbo, st, true);
        return drawTarget;
    }

    public void resolve(R t, Fbo fbo, GlState st, boolean draw) {
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
            fillDefault(t, !(draw && st.defaultDrawNone));
            return;
        }
        final TexturesBase<T> textures = textures();
        int count = 0;
        for (int i = 0; i < fbo.drawBuffers.length; i++) {
            final int a = fbo.drawBuffers[i];
            if (a < 0 || fbo.colorTex[a] == 0) continue;
            final T tex = textures.attachment(fbo.colorTex[a], fbo.colorRbo[a]);
            if (tex == null || !tex.hasImage() || !renderable(tex.format.pf())) continue;
            t.color[i] = tex;
            t.colorLevel[i] = Math.min(fbo.colorLevel[a], tex.levels - 1);
            t.colorLayer[i] = fbo.colorLayered[a] ? 0 : fbo.colorLayer[a];
            t.colorLayers[i] = !fbo.colorLayered[a] ? 1 : tex.is3D() ? tex.levelDepth(t.colorLevel[i]) : tex.layers;
            count = i + 1;
            w = Math.min(w, tex.levelWidth(t.colorLevel[i]));
            h = Math.min(h, tex.levelHeight(t.colorLevel[i]));
        }
        t.colorCount = count;
        if (fillDepth(t, fbo)) {
            t.depthLayers = fbo.depthLayered ? t.depth.layers : 1;
            w = Math.min(w, t.depth.levelWidth(t.depthLevel));
            h = Math.min(h, t.depth.levelHeight(t.depthLevel));
        }
        t.width = w == Integer.MAX_VALUE ? 0 : w;
        t.height = h == Integer.MAX_VALUE ? 0 : h;
        if (!fbo.hasAttachments()) {
            t.width = fbo.defaultWidth;
            t.height = fbo.defaultHeight;
            t.emptyLayers = Math.max(1, fbo.defaultLayers);
        }
        if (base.framebuffers.status(fbo) != GL30.GL_FRAMEBUFFER_COMPLETE) t.width = t.height = 0;
    }

    /** Whether a target can be drawn to, recording GL's error when that is because its framebuffer is incomplete. */
    protected final boolean hasSize(R t) {
        if (t.width > 0 && t.height > 0) return true;
        if (t.fboId != 0 && base.framebuffers.status(base.framebuffers.get(t.fboId)) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            base.recordError(GL30.GL_INVALID_FRAMEBUFFER_OPERATION);
        }
        return false;
    }

    public R readTarget(GlState st) {
        final R t = scratchTarget;
        t.clear();
        t.fboId = Integer.MIN_VALUE;
        final Fbo fbo = base.framebuffers.get(st.readFbo);
        if (fbo == null || fbo.id == 0) {
            fillDefault(t, !st.defaultReadNone);
            return t;
        }
        final TexturesBase<T> textures = textures();
        final int idx = fbo.readBuffer;
        if (idx >= 0 && idx < Fbo.MAX_COLOR && fbo.colorTex[idx] != 0) {
            final T tex = textures.attachment(fbo.colorTex[idx], fbo.colorRbo[idx]);
            if (tex != null && tex.hasImage() && renderable(tex.format.pf())) {
                t.color[0] = tex;
                t.colorLevel[0] = fbo.colorLevel[idx];
                t.colorLayer[0] = fbo.colorLayered[idx] ? 0 : fbo.colorLayer[idx];
                t.colorLayers[0] = 1;
                t.colorCount = 1;
                t.width = tex.levelWidth(t.colorLevel[0]);
                t.height = tex.levelHeight(t.colorLevel[0]);
            }
        }
        if (fillDepth(t, fbo) && t.width == 0) {
            t.width = t.depth.levelWidth(t.depthLevel);
            t.height = t.depth.levelHeight(t.depthLevel);
        }
        return t;
    }

    public void onBufferRenamed() {
    }

    protected boolean sameAttachments(R t) {
        for (int i = 0; i < t.colorCount; i++) {
            if (passTarget.color[i] != t.color[i] || passTarget.colorLevel[i] != t.colorLevel[i] || passTarget.colorLayer[i] != t.colorLayer[i] || passTarget.colorLayers[i] != t.colorLayers[i]) return false;
        }
        return passTarget.depth == t.depth && passTarget.depthLevel == t.depthLevel && passTarget.depthLayer == t.depthLayer && passTarget.depthLayers == t.depthLayers;
    }

    private void fillDefault(R t, boolean withColor) {
        final DefaultFramebufferBase<T> d = defaultFramebuffer();
        if (withColor) {
            t.color[0] = d.color;
            t.colorLayers[0] = 1;
            t.colorCount = 1;
        }
        t.depth = d.depth;
        t.hasDepth = true;
        t.hasStencil = d.depth.format.isStencil();
        t.width = d.width;
        t.height = d.height;
    }

    private boolean fillDepth(R t, Fbo fbo) {
        final int depthId = fbo.depthTex != 0 ? fbo.depthTex : fbo.stencilTex;
        if (depthId == 0) return false;
        final T tex = textures().attachment(depthId, fbo.depthTex != 0 ? fbo.depthRbo : fbo.stencilRbo);
        if (tex == null || !tex.hasImage() || !tex.format.isDepthOrStencil()) return false;
        t.depth = tex;
        t.depthLevel = Math.min(fbo.depthTex != 0 ? fbo.depthLevel : fbo.stencilLevel, tex.levels - 1);
        t.depthLayer = fbo.depthTex != 0 ? fbo.depthLayer : fbo.stencilLayer;
        // A packed image attached for only one of the two is only that one.
        t.hasDepth = fbo.depthTex != 0 && tex.format.isDepth();
        t.hasStencil = fbo.stencilTex != 0 && tex.format.isStencil();
        return true;
    }

    protected final boolean sameAsPass(R t) {
        return passActive
            && passTarget.colorCount == t.colorCount && passTarget.width == t.width && passTarget.height == t.height
            && passTarget.hasDepth == t.hasDepth && passTarget.hasStencil == t.hasStencil
            && sameAttachments(t);
    }

    protected static int layerCount(TargetBase<?> t) {
        int n = t.emptyLayers;
        for (int i = 0; i < t.colorCount; i++) if (t.color[i] != null) n = Math.max(n, t.colorLayers[i]);
        if (t.depth != null) n = Math.max(n, t.depthLayers);
        return n;
    }

    protected static <T extends TexBase> void copyTarget(TargetBase<T> from, TargetBase<T> to) {
        to.clear();
        to.fboId = from.fboId;
        to.fboGen = from.fboGen;
        to.globalGen = from.globalGen;
        to.colorCount = from.colorCount;
        to.emptyLayers = from.emptyLayers;
        System.arraycopy(from.color, 0, to.color, 0, from.color.length);
        System.arraycopy(from.colorLevel, 0, to.colorLevel, 0, from.colorLevel.length);
        System.arraycopy(from.colorLayer, 0, to.colorLayer, 0, from.colorLayer.length);
        System.arraycopy(from.colorLayers, 0, to.colorLayers, 0, from.colorLayers.length);
        System.arraycopy(from.colorView, 0, to.colorView, 0, from.colorView.length);
        to.depth = from.depth;
        to.depthLevel = from.depthLevel;
        to.depthLayer = from.depthLayer;
        to.depthLayers = from.depthLayers;
        to.depthView = from.depthView;
        to.hasDepth = from.hasDepth;
        to.hasStencil = from.hasStencil;
        to.width = from.width;
        to.height = from.height;
    }

    public void releaseFromPass(T t) {
        if (passActive && passTarget.contains(t)) endPass();
    }

    public void onImageReleased(T t) {
        releaseFromPass(t);
        targetGen++;
    }

    public void materialize(T t) {
        if (!t.hasImage()) return;
        if (!t.pendingClear && !t.needsClear) return;
        endPass();
        if (t.pendingClear) clearPending(t);
        else clearImage(t, ZERO, 1f, 0, t.format.aspect());
        t.markGpuWrite(currentSerial());
    }

    /** The sampling parameters in effect: a bound sampler object's, else the texture's, else nearest clamped sampling for no texture. */
    protected record SamplerSource(int min, int mag, int ws, int wt, int wr, int cmpMode, int cmpFunc, float minLod, float maxLod, float bias, float aniso, float[] border) {
        public static SamplerSource of(SamplerObj so, TexBase tex) {
            if (so != null) return new SamplerSource(so.minFilter, so.magFilter, so.wrapS, so.wrapT, so.wrapR, so.compareMode, so.compareFunc, so.minLod, so.maxLod, so.lodBias, so.anisotropy, so.border);
            if (tex != null) return new SamplerSource(tex.minFilter, tex.magFilter, tex.wrapS, tex.wrapT, tex.wrapR, tex.compareMode, tex.compareFunc, tex.minLod, tex.maxLod, tex.lodBias, tex.anisotropy, tex.border);
            return new SamplerSource(GL11.GL_NEAREST, GL11.GL_NEAREST, GL12.GL_CLAMP_TO_EDGE, GL12.GL_CLAMP_TO_EDGE, GL12.GL_CLAMP_TO_EDGE, GL11.GL_NONE, GL11.GL_LEQUAL, -1000, 1000, 0, 1, ZERO);
        }
    }

    protected static boolean coversEveryChannel(boolean[] cm, TexBase tex) {
        final Formats.Format f = tex.format;
        return (cm[0] || f.r() == 0) && (cm[1] || f.g() == 0) && (cm[2] || f.b() == 0) && (cm[3] || f.a() == 0);
    }

    /**
     * A pass's load op only initialises the subresources it attaches, so a fresh image used in part is cleared whole first.
     * When the pass attaches only some layers or slices, a pending clear was asked of those alone: the image takes its
     * defaults, and the clear stays for the pass's load op.
     */
    protected void materializePartialAttachments(R t) {
        for (int i = 0; i < t.colorCount; i++) {
            final T tex = t.color[i];
            if (tex != null && tex.needsClear && !canDefer(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i])) {
                if (someLayers(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i])) initialise(tex);
                else materialize(tex);
            }
        }
        final T depth = t.depth;
        if (depth != null && depth.needsClear && !canDefer(depth, t.depthLevel, t.depthLayer, t.depthLayers)) {
            if (someLayers(depth, t.depthLevel, t.depthLayer, t.depthLayers)) initialise(depth);
            else materialize(depth);
        }
    }

    private static boolean someLayers(TexBase tex, int level, int layer, int layers) {
        return layer != 0 || layers < (tex.is3D() ? tex.levelDepth(level) : tex.layers);
    }

    private void initialise(T t) {
        if (!t.hasImage()) return;
        endPass();
        clearImage(t, ZERO, 1f, 0, t.format.aspect());
        t.needsClear = false;
        t.markGpuWrite(currentSerial());
    }

    /** A clear of an sRGB image encodes depending on GL_FRAMEBUFFER_SRGB at the time, which may have changed by the time a deferred one runs. */
    protected static boolean clearFollowsSrgbState(TexBase tex) {
        return tex.format.pf() != Formats.linearOf(tex.format.pf());
    }

    /** A clear can wait for the next pass only when it covers the whole image: level 0 of a single-level image, every layer. */
    protected static boolean canDefer(TexBase tex, int level, int layer, int layers) {
        return level == 0 && layer == 0 && tex.levels == 1 && tex.layers == layers && tex.depth <= 1;
    }

    private static boolean shadesPerSample(GlState st, int samples) {
        return st.sampleShading && st.multisample && samples > 1 && st.minSampleShading * samples > 1f;
    }

    /** For a device that runs a fragment shader per sample only when an input asks for it. */
    protected P sampleRateProgram(GlState st, P program, int samples) {
        final long key = (shadesPerSample(st, samples) ? SampleRateLowering.KEY : 0L) | borderKey(st, program);
        if (key == 0L) return program;
        final P variant = programs().logicOpVariant(program, key);
        if (variant == null) return program;
        copyUniforms(program, variant);
        return variant;
    }

    /** For a device with its own per-sample shading, which only needs a variant for border colours. */
    protected P borderProgram(GlState st, P program) {
        final long key = borderKey(st, program);
        if (key == 0L) return program;
        final P variant = programs().logicOpVariant(program, key);
        if (variant == null) return program;
        copyUniforms(program, variant);
        return variant;
    }

    private long borderKey(GlState st, P program) {
        final boolean edges = ShaderCompiler.gatherEdgesInShader && program.usesGather;
        final boolean faces = !st.seamlessCubes && program.usesCubes;
        if (!BorderLowering.seen() && !edges && !faces) return 0L;
        // The choice stands while nothing it was made from changed: the program, the sampling state, and what each sampler's unit has bound.
        int n = 0;
        if (borderPrint.length < 4 + program.uniforms.size() * 3) borderPrint = new int[(4 + program.uniforms.size() * 3) * 2];
        borderPrint[n++] = program.gen;
        borderPrint[n++] = base.samplingVersion;
        borderPrint[n++] = (st.seamlessCubes ? 1 : 0) | (BorderLowering.seen() ? 2 : 0);
        borderPrint[n++] = program.uniforms.size();
        for (ProgramBase.Uniform u : program.uniforms) {
            if (!u.isOpaque() || u.units.length != 1) continue;
            final int unit = Math.min(Math.max(0, u.units[0]), GlState.MAX_UNITS - 1), slot = borderSlot(u.glType);
            borderPrint[n++] = unit;
            borderPrint[n++] = slot < 0 ? -1 : st.textures[unit][slot];
            borderPrint[n++] = st.samplers[unit];
        }
        if (program.borderPrint != null && program.borderPrint.length == n && Arrays.equals(program.borderPrint, 0, n, borderPrint, 0, n)) return program.borderKey;
        final long key = chooseBorderKey(st, program, edges, faces);
        program.borderPrint = Arrays.copyOf(borderPrint, n);
        program.borderKey = key;
        return key;
    }

    private int[] borderPrint = new int[64];

    private long chooseBorderKey(GlState st, P program, boolean edges, boolean faces) {
        List<BorderLowering.Sampler> found = null;
        for (ProgramBase.Uniform u : program.uniforms) {
            if (!u.isOpaque() || u.units.length != 1) continue;
            final int unit = Math.min(Math.max(0, u.units[0]), GlState.MAX_UNITS - 1);
            final int slot = borderSlot(u.glType);
            if (slot < 0) continue;
            final T t = textures().get(st.textures[unit][slot]);
            if (t == null) continue;
            final SamplerObj sampler = st.samplers[unit] != 0 ? base.samplerObjects.get(st.samplers[unit]) : null;
            if (slot == GlState.T_CUBE || slot == GlState.T_CUBE_ARRAY) {
                if (!faces) continue;
                final int min = sampler != null ? sampler.minFilter : t.minFilter, mag = sampler != null ? sampler.magFilter : t.magFilter;
                final boolean linear = mag == GL11.GL_LINEAR || min == GL11.GL_LINEAR || min == GL11.GL_LINEAR_MIPMAP_NEAREST || min == GL11.GL_LINEAR_MIPMAP_LINEAR;
                if (!linear) continue;
                if (found == null) found = new ArrayList<>();
                found.add(BorderLowering.Sampler.apartFaces(u.name));
                continue;
            }
            final int wrapS = sampler != null ? sampler.wrapS : t.wrapS, wrapT = sampler != null ? sampler.wrapT : t.wrapT, wrapR = sampler != null ? sampler.wrapR : t.wrapR;
            if (edges && wrapS == GL12.GL_CLAMP_TO_EDGE && wrapT == GL12.GL_CLAMP_TO_EDGE) {
                if (found == null) found = new ArrayList<>();
                found.add(new BorderLowering.Sampler(u.name, false, false, false, "", true));
                continue;
            }
            if (wrapS != GL13.GL_CLAMP_TO_BORDER && wrapT != GL13.GL_CLAMP_TO_BORDER && wrapR != GL13.GL_CLAMP_TO_BORDER) continue;
            final float[] border = sampler != null ? sampler.border : t.border;
            final int kind = sampledKind(u.glType);
            final String color;
            if (kind == SAMPLED_FLOAT) {
                if (BorderLowering.isPreset(border[0], border[1], border[2], border[3])) continue;
                color = "(" + border[0] + ", " + border[1] + ", " + border[2] + ", " + border[3] + ")";
            } else {
                final int[] values = sampler == null && t.borderInteger != null ? t.borderInteger : new int[] {(int) border[0], (int) border[1], (int) border[2], (int) border[3]};
                if (BorderLowering.isPreset(values[0], values[1], values[2], values[3])) continue;
                final String suffix = kind == SAMPLED_SINT ? "" : "u";
                color = "(" + integerLiteral(values[0], suffix) + ", " + integerLiteral(values[1], suffix) + ", " + integerLiteral(values[2], suffix) + ", " + integerLiteral(values[3], suffix) + ")";
            }
            if (found == null) found = new ArrayList<>();
            found.add(new BorderLowering.Sampler(u.name, wrapS == GL13.GL_CLAMP_TO_BORDER, wrapT == GL13.GL_CLAMP_TO_BORDER, wrapR == GL13.GL_CLAMP_TO_BORDER, color, false));
        }
        return found == null ? 0L : BorderLowering.keyOf(found);
    }

    private static String integerLiteral(int value, String suffix) {
        return suffix.isEmpty() ? (value == Integer.MIN_VALUE ? "-2147483647 - 1" : Integer.toString(value)) : Integer.toUnsignedString(value) + "u";
    }

    /** The texture target a sampler type samples. */
    public static int biasSlot(int glType) {
        return switch (glType) {
            case GL20.GL_SAMPLER_1D_SHADOW -> GlState.T_1D;
            case GL20.GL_SAMPLER_2D_SHADOW -> GlState.T_2D;
            case GL30.GL_SAMPLER_2D_ARRAY_SHADOW -> GlState.T_2D_ARRAY;
            case GL30.GL_SAMPLER_CUBE_SHADOW, GL30.GL_INT_SAMPLER_CUBE, GL30.GL_UNSIGNED_INT_SAMPLER_CUBE -> GlState.T_CUBE;
            case GL40.GL_INT_SAMPLER_CUBE_MAP_ARRAY, GL40.GL_UNSIGNED_INT_SAMPLER_CUBE_MAP_ARRAY -> GlState.T_CUBE_ARRAY;
            case GL30.GL_SAMPLER_1D_ARRAY, GL30.GL_SAMPLER_1D_ARRAY_SHADOW, GL30.GL_INT_SAMPLER_1D_ARRAY, GL30.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY -> GlState.T_1D_ARRAY;
            default -> borderSlot(glType);
        };
    }

    private static int borderSlot(int glType) {
        return switch (glType) {
            case GL20.GL_SAMPLER_1D, GL30.GL_INT_SAMPLER_1D, GL30.GL_UNSIGNED_INT_SAMPLER_1D -> GlState.T_1D;
            case GL20.GL_SAMPLER_2D, GL30.GL_INT_SAMPLER_2D, GL30.GL_UNSIGNED_INT_SAMPLER_2D -> GlState.T_2D;
            case GL20.GL_SAMPLER_3D, GL30.GL_INT_SAMPLER_3D, GL30.GL_UNSIGNED_INT_SAMPLER_3D -> GlState.T_3D;
            case GL30.GL_SAMPLER_2D_ARRAY, GL30.GL_INT_SAMPLER_2D_ARRAY, GL30.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY -> GlState.T_2D_ARRAY;
            case GL20.GL_SAMPLER_CUBE -> GlState.T_CUBE;
            case GL40.GL_SAMPLER_CUBE_MAP_ARRAY -> GlState.T_CUBE_ARRAY;
            default -> -1;
        };
    }

    private void copyUniforms(P program, P variant) {
        for (int s = 0; s < ProgramBase.STAGES; s++) {
            final ProgramBase.StageBlock from = program.blocks[s], to = variant.blocks[s];
            if (from == null || to == null) continue;
            MemoryUtil.memCopy(from.address, to.address, from.size);
            to.dirty = true;
        }
        for (int i = 0; i < variant.logicBaseUniform.length; i++) {
            final int b = variant.logicBaseUniform[i];
            if (b < 0) continue;
            final int[] src = program.uniforms.get(b).units, dst = variant.uniforms.get(i).units;
            if (src != null && dst != null && src.length == dst.length) System.arraycopy(src, 0, dst, 0, src.length);
        }
    }

    /**
     * Logic ops have no native equivalent on these formats: a program variant reads a copy of each destination
     * attachment and applies the op itself, so every destination is copied to scratch before the draw.
     */
    protected P logicOpProgram(GlState st, P program, R t, int samples) {
        final int op = LogicOpLowering.mesaOp(st.logicOp);
        if (op == LogicOpLowering.OP_COPY) return sampleRateProgram(st, program, samples);
        long key = op | (shadesPerSample(st, samples) ? SampleRateLowering.KEY : 0L) | borderKey(st, program);
        boolean any = false;
        final int limit = Math.min(Math.min(t.colorCount, program.colorOutputs), LogicOpLowering.MAX_LOCATIONS);
        for (int loc = 0; loc < limit; loc++) {
            if (t.color[loc] == null) continue;
            final int cls = LogicOpLowering.classOf(t.color[loc].format.pf());
            if (cls == 0) continue;
            key = LogicOpLowering.withClass(key, loc, cls);
            any = true;
        }
        if (!any) return sampleRateProgram(st, program, samples);
        final P variant = programs().logicOpVariant(program, key);
        if (variant == null) return program;
        copyUniforms(program, variant);

        final T[] dsts = Arrays.copyOf(logicScratch, LogicOpLowering.MAX_LOCATIONS);
        Arrays.fill(dsts, null);
        for (int loc = 0; loc < limit; loc++) {
            if (LogicOpLowering.classAt(key, loc) == 0) continue;
            final T src = t.color[loc];
            T scratch = logicScratch[loc];
            if (scratch == null || !sameFormat(scratch.format.pf(), src.format.pf()) || scratch.width != t.width || scratch.height != t.height) {
                if (scratch != null) releaseImage(scratch);
                scratch = textures().internalTexture(GL11.GL_TEXTURE_2D, src.format, t.width, t.height, 1, 1, "logicOpDst" + loc);
                logicScratch[loc] = scratch;
            }
            copyImage(src, t.colorLevel[loc], 0, 0, src.layers > 1 ? t.colorLayer[loc] : 0, scratch, 0, 0, 0, 0, t.width, t.height, 1);
            dsts[loc] = scratch;
        }
        logicDst = dsts;
        return variant;
    }

    protected static int sampledKind(int glType) {
        return switch (glType) {
            case GL30.GL_INT_SAMPLER_1D, GL30.GL_INT_SAMPLER_2D, GL30.GL_INT_SAMPLER_3D, GL30.GL_INT_SAMPLER_CUBE, GL30.GL_INT_SAMPLER_1D_ARRAY,
                 GL30.GL_INT_SAMPLER_2D_ARRAY, GL31.GL_INT_SAMPLER_2D_RECT, GL31.GL_INT_SAMPLER_BUFFER, GL40.GL_INT_SAMPLER_CUBE_MAP_ARRAY -> SAMPLED_SINT;
            case GL30.GL_UNSIGNED_INT_SAMPLER_1D, GL30.GL_UNSIGNED_INT_SAMPLER_2D, GL30.GL_UNSIGNED_INT_SAMPLER_3D, GL30.GL_UNSIGNED_INT_SAMPLER_CUBE,
                 GL30.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY, GL30.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY, GL31.GL_UNSIGNED_INT_SAMPLER_2D_RECT,
                 GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER, GL40.GL_UNSIGNED_INT_SAMPLER_CUBE_MAP_ARRAY -> SAMPLED_UINT;
            default -> SAMPLED_FLOAT;
        };
    }

    public long[] forgetViews(T t) {
        final long[] extra = extraViews.remove(t.id);
        onViewsForgotten();
        if (extra == null) return new long[0];
        return Arrays.stream(extra).filter(v -> v != 0L).toArray();
    }

    /** Copies the constant values of disabled attribute arrays to a new ring slot when the last one no longer serves, answering whether it did. */
    protected final boolean writeConstantAttribs(GlState st, long serial, HostRingBase<?> ring) {
        if (constAttribSerial == serial && constAttribState == st && constAttribVersion == st.attribVersion) return false;
        ring.alloc(CONSTANT_ZEROS + 16L, 16);
        for (int i = 0; i < Vao.MAX_ATTRIBS * 4; i++) MemoryUtil.memPutFloat(ring.address + i * 4L, st.attribValue[i]);
        for (int i = 0; i < Vao.MAX_ATTRIBS * 4; i++) MemoryUtil.memPutDouble(ring.address + CONSTANT_DOUBLES + i * 8L, st.attribDoubles[i]);
        MemoryUtil.memSet(ring.address + CONSTANT_ZEROS, 0, 16);
        constAttribSerial = serial;
        constAttribState = st;
        constAttribVersion = st.attribVersion;
        return true;
    }

    /** The vertex attribute a vertex input location reads: its own, or for the second half of a double input's vectors, the one it is half of. */
    protected static int attribOf(ProgramBase<?> prog, int location) {
        return location < Vao.MAX_ATTRIBS ? location : prog.secondHalfOf[location - Vao.MAX_ATTRIBS];
    }

    /** Whether a vertex input takes its words from the attribute's array, which the second half of a vector the array doesn't hold cannot. */
    protected static boolean readsArray(ProgramBase<?> prog, Vao vao, int location) {
        final int attrib = attribOf(prog, location);
        return vao.enabled[attrib] && vao.bindingBuffer[vao.bindingOf[attrib]] != 0 && (location < Vao.MAX_ATTRIBS || vao.size[attrib] > 2);
    }

    /** How far into the attribute's element a location's words start: past the first two doubles for the second half. */
    protected static long wordsOffset(int location) {
        return location < Vao.MAX_ATTRIBS ? 0L : 16L;
    }

    /** Where a vertex input's constant is in the slot writeConstantAttribs fills. */
    protected static long constantOffset(ProgramBase<?> prog, Vao vao, int location) {
        final int attrib = attribOf(prog, location);
        if (prog.inputDoubles[attrib] == 0) return attrib * 16L;
        if (location >= Vao.MAX_ATTRIBS && vao.enabled[attrib] && vao.bindingBuffer[vao.bindingOf[attrib]] != 0) return CONSTANT_ZEROS;
        return CONSTANT_DOUBLES + attrib * 32L + wordsOffset(location);
    }

    /** The number of 32 bit words of a double input a location reads from an array of this many doubles: two or four. */
    protected static int doubleWords(ProgramBase<?> prog, Vao vao, int location) {
        final int size = vao.size[attribOf(prog, location)];
        return location < Vao.MAX_ATTRIBS ? (size == 1 ? 2 : 4) : (size == 3 ? 2 : 4);
    }

    public static boolean drawable(int mode) {
        return switch (mode) {
            case GL32.GL_LINES_ADJACENCY, GL32.GL_LINE_STRIP_ADJACENCY, GL32.GL_TRIANGLES_ADJACENCY, GL32.GL_TRIANGLE_STRIP_ADJACENCY, GL40.GL_PATCHES -> false;
            default -> true;
        };
    }
}
