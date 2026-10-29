package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.resource.DefaultFramebufferBase;
import com.gtnewhorizons.angelica.glemu.resource.Fbo;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;
import com.gtnewhorizons.angelica.glemu.shader.LogicOpLowering;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

// The backend-independent half of a renderer: attachment resolution, pass ending, deferred clears, logic-op variants and
// feedback copies. Pass encoding, descriptor binding and pipeline state stay with each backend.
public abstract class RendererBase<T extends TexBase, P extends ProgramBase<P>, R extends TargetBase<T>> {
    protected static final float[] ZERO = new float[4];
    protected static final int SAMPLED_FLOAT = 0, SAMPLED_SINT = 1, SAMPLED_UINT = 2;

    protected final GlBase<T, P, ?> base;
    protected final R passTarget;
    public final R drawTarget;
    protected final R scratchTarget;
    public boolean passActive;
    public int targetGen = 1;
    protected long passEpoch;
    protected final Int2ObjectOpenHashMap<long[]> extraViews = new Int2ObjectOpenHashMap<>();
    protected final Int2ObjectOpenHashMap<T> feedbackCopies = new Int2ObjectOpenHashMap<>();
    protected final Int2LongOpenHashMap feedbackVersions = new Int2LongOpenHashMap();
    protected final Int2LongOpenHashMap feedbackEpochs = new Int2LongOpenHashMap();
    protected final T[] logicScratch;
    protected T[] logicDst;

    protected RendererBase(GlBase<T, P, ?> base, R passTarget, R drawTarget, R scratchTarget, T[] logicScratch) {
        this.base = base;
        this.passTarget = passTarget;
        this.drawTarget = drawTarget;
        this.scratchTarget = scratchTarget;
        this.logicScratch = logicScratch;
    }

    protected abstract TexturesBase<T> textures();

    protected abstract ProgramsBase<P> programs();

    protected abstract DefaultFramebufferBase<T> defaultFramebuffer();

    protected abstract T newTex(int id);

    protected abstract void releaseImage(T t);

    protected abstract void createImage(T t);

    protected boolean renderable(int pf) {
        return true;
    }

    protected boolean sameFormat(int pfA, int pfB) {
        return pfA == pfB;
    }

    protected abstract void clearImage(T t, float[] color, float depth, int stencil);

    // Only the aspects the deferred clear asked for take the stored values; the other aspect gets its default.
    protected void clearPending(T t) {
        final boolean depth = (t.clearAspects & Formats.ASPECT_DEPTH) != 0, stencil = (t.clearAspects & Formats.ASPECT_STENCIL) != 0;
        t.pendingClear = false;
        clearImage(t, t.clearColor, depth || !t.format.isDepth() ? t.clearDepth : 1f, stencil ? t.clearStencil : 0);
    }

    protected abstract void copyImage(T src, int srcLevel, int sx, int sy, int sz, T dst, int dstLevel, int dx, int dy, int dz, int w, int h, int d);

    protected void copyWhole(T from, T to) {
        throw new UnsupportedOperationException("feedback copies are not used by this backend");
    }

    protected abstract long currentSerial();

    public abstract void endPass();

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
            final DefaultFramebufferBase<T> d = defaultFramebuffer();
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
            t.colorLayers[i] = fbo.colorLayered[a] ? tex.layers : 1;
            count = i + 1;
            w = Math.min(w, tex.levelWidth(t.colorLevel[i]));
            h = Math.min(h, tex.levelHeight(t.colorLevel[i]));
        }
        t.colorCount = count;
        final int depthId = fbo.depthTex != 0 ? fbo.depthTex : fbo.stencilTex;
        if (depthId != 0) {
            final boolean rbo = fbo.depthTex != 0 ? fbo.depthRbo : fbo.stencilRbo;
            final T tex = textures.attachment(depthId, rbo);
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

    public R readTarget(GlState st) {
        final R t = scratchTarget;
        t.clear();
        t.fboId = Integer.MIN_VALUE;
        final Fbo fbo = base.framebuffers.get(st.readFbo);
        if (fbo == null || fbo.id == 0) {
            final DefaultFramebufferBase<T> d = defaultFramebuffer();
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
        final TexturesBase<T> textures = textures();
        final int idx = fbo.readBuffer;
        if (idx >= 0 && idx < Fbo.MAX_COLOR && fbo.colorTex[idx] != 0) {
            final T tex = textures.attachment(fbo.colorTex[idx], fbo.colorRbo[idx]);
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
            final T tex = textures.attachment(depthId, fbo.depthTex != 0 ? fbo.depthRbo : fbo.stencilRbo);
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

    protected boolean sameAttachments(R t) {
        for (int i = 0; i < t.colorCount; i++) {
            if (passTarget.color[i] != t.color[i] || passTarget.colorLevel[i] != t.colorLevel[i] || passTarget.colorLayer[i] != t.colorLayer[i] || passTarget.colorLayers[i] != t.colorLayers[i]) return false;
        }
        return passTarget.depth == t.depth && passTarget.depthLevel == t.depthLevel && passTarget.depthLayer == t.depthLayer && passTarget.depthLayers == t.depthLayers;
    }

    protected static int layerCount(TargetBase<?> t) {
        int n = 1;
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
        if (passActive && passTarget.contains(t)) endPass();
        targetGen++;
        final T copy = feedbackCopies.remove(t.id);
        if (copy != null) {
            feedbackVersions.remove(t.id);
            feedbackEpochs.remove(t.id);
            releaseImage(copy);
        }
    }

    public void materialize(T t) {
        if (!t.hasImage()) return;
        if (!t.pendingClear && !t.needsClear) return;
        releaseFromPass(t);
        endPass();
        if (t.pendingClear) clearPending(t);
        else clearImage(t, ZERO, 1f, 0);
        t.markGpuWrite(currentSerial());
    }

    protected static boolean coversEveryChannel(boolean[] cm, TexBase tex) {
        final Formats.Format f = tex.format;
        return (cm[0] || f.r() == 0) && (cm[1] || f.g() == 0) && (cm[2] || f.b() == 0) && (cm[3] || f.a() == 0);
    }

    // A clear can wait for the next pass only when it covers the whole image: level 0 of a single-level image, every layer.
    protected static boolean canDefer(TexBase tex, int level, int layer, int layers) {
        return level == 0 && layer == 0 && tex.levels == 1 && tex.layers == layers;
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
                tex.lastGpuWrite = serial;
            }
        }
        if (t.depth != null && !st.rasterizerDiscard) {
            final boolean depthWrites = st.depthTest && st.depthMask && t.hasDepth;
            final boolean stencilWrites = st.stencilTest && t.hasStencil && ((st.stencilWriteMask[0] | st.stencilWriteMask[1]) & 0xFF) != 0;
            if (depthWrites || stencilWrites) {
                t.depth.version++;
                t.depth.lastGpuWrite = serial;
            }
        }
    }

    protected static boolean scissorEmpty(GlState st, TargetBase<?> t) {
        if (!st.scissorTest) return false;
        final int x0 = Math.max(0, st.scX), y0 = Math.max(0, st.scY);
        final int x1 = Math.min(t.width, st.scX + st.scW), y1 = Math.min(t.height, st.scY + st.scH);
        return x1 <= x0 || y1 <= y0;
    }

    // Logic ops have no native equivalent on these formats: a program variant reads a copy of each destination
    // attachment and applies the op itself, so every destination is copied to scratch before the draw.
    protected P logicOpProgram(GlState st, P base, R t) {
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
        final P variant = programs().logicOpVariant(base, key);
        if (variant == null) return base;

        for (int s = 0; s < ProgramBase.STAGES; s++) {
            final ProgramBase.StageBlock from = base.blocks[s], to = variant.blocks[s];
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

    // A shader may sample an attachment of the open pass; it reads a copy, refreshed at most once per pass or texture version.
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
        copy.minFilter = tex.minFilter;
        copy.baseLevel = tex.baseLevel;
        copy.maxLevel = tex.maxLevel;
        System.arraycopy(tex.swizzle, 0, copy.swizzle, 0, 4);
        copy.sampleStencil = tex.sampleStencil;
        copy.viewDirty = copy.viewDirty || copy.baseLevel != tex.baseLevel;
        final boolean current = feedbackVersions.get(tex.id) == tex.version || feedbackEpochs.get(tex.id) == passEpoch;
        if (!current) {
            copyWhole(tex, copy);
            feedbackVersions.put(tex.id, tex.version);
            feedbackEpochs.put(tex.id, passEpoch + 1);
        }
        return copy;
    }

    protected static int sampledKind(int glType) {
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

    public long[] forgetViews(T t) {
        final long[] extra = extraViews.remove(t.id);
        onViewsForgotten();
        if (extra == null) return new long[0];
        return Arrays.stream(extra).filter(v -> v != 0L).toArray();
    }

    public static boolean drawable(int mode) {
        return switch (mode) {
            case GL32.GL_LINES_ADJACENCY, GL32.GL_LINE_STRIP_ADJACENCY, GL32.GL_TRIANGLES_ADJACENCY, GL32.GL_TRIANGLE_STRIP_ADJACENCY, GL40.GL_PATCHES -> false;
            default -> true;
        };
    }
}
