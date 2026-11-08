package com.gtnewhorizons.angelica.glemu.resource;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.gl.NativeTokens;
import com.gtnewhorizons.angelica.glemu.gl.TokenGlBase;
import com.gtnewhorizons.angelica.glemu.gl.TokenMaps;
import com.gtnewhorizons.angelica.glemu.gl.TokenRendererBase;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

/** Texture management in {@link NativeTokens}. */
public abstract class TokenTexturesBase<T extends TokenTex> extends TexturesBase<T> {

    public long uploads, uploadBytes, hoistedUploads;
    private final TokenGlBase<T, ?, ?, ?> token;

    protected TokenTexturesBase(TokenGlBase<T, ?, ?, ?> gl) {
        super(gl);
        this.token = gl;
    }

    private TokenImagesBase<T> images() {
        return token.images();
    }

    private TokenRendererBase<T, ?, ?, ?, ?> renderer() {
        return token.rendererBase();
    }

    protected abstract void copyAllLevels(T from, T to);

    /** Moves depth between formats no copy allows, through a buffer. */
    protected abstract void copyDepthThroughBuffer(T s, int srcLayer, int srcLevel, int sx, int sy, int w, int h, T dst, int dstLayer, int level, int dx, int dy, int dz);

    public abstract void copyTextureToTexture(T s, int sSlice, int sLevel, int sx, int sy, int sz, int w, int h, int d, T dst, int dSlice, int dLevel, int dx, int dy, int dz);

    protected abstract void copyImageThroughBuffer(T src, int srcLevel, int sx, int sy, int sz, boolean srcLayered, T dst, int dstLevel, int dx, int dy, int dz, boolean dstLayered, int w, int h, int depth, int slices);

    /** The blit option that picks one aspect's plane of a combined depth-stencil format, or none for any other format. */
    public static int blitOptions(TokenTex t, int aspect) {
        if (!t.format.isDepth() || !t.format.isStencil()) return NativeTokens.BLIT_NONE;
        return aspect == Formats.ASPECT_STENCIL ? NativeTokens.BLIT_STENCIL_FROM_DEPTH_STENCIL : NativeTokens.BLIT_DEPTH_FROM_DEPTH_STENCIL;
    }

    @Override public void copyImage(T src, int srcLevel, int sx, int sy, int sz, T dst, int dstLevel, int dx, int dy, int dz, int w, int h, int d) {
        if (src == null || dst == null || !src.hasImage() || !dst.hasImage()) return;
        if (src.format.isCompressed() != dst.format.isCompressed()) {
            base.warnOnce("copies between compressed and uncompressed images are not supported");
            return;
        }
        renderer().materialize(src);
        renderer().materialize(dst);
        renderer().endPass();
        final boolean srcLayered = TokenMaps.sliceCount(src) > 1, dstLayered = TokenMaps.sliceCount(dst) > 1;
        final int slices = srcLayered || dstLayered ? d : 1;
        final int depth = srcLayered || dstLayered ? 1 : d;
        if (src.format.pf() == dst.format.pf()) {
            for (int i = 0; i < slices; i++) {
                copyTextureToTexture(src, srcLayered ? sz + i : 0, srcLevel, sx, sy, srcLayered ? 0 : sz + i, w, h, depth, dst, dstLayered ? dz + i : 0, dstLevel, dx, dy, dstLayered ? 0 : dz + i);
            }
        } else {
            copyImageThroughBuffer(src, srcLevel, sx, sy, sz, srcLayered, dst, dstLevel, dx, dy, dz, dstLayered, w, h, depth, slices);
        }
        dst.markCopiedFrom(src, base.currentSerial());
    }

    protected void blitRegion(T s, int srcLevel, int srcLayer, int sx, int sy, int w, int h, T dst, int level, int dstLayer, int dx, int dy) {
        token.blitThroughPass(s, srcLevel, srcLayer, sx, sy, sx + w, sy + h, dst, level, dstLayer, dx, dy, dx + w, dy + h, false, false);
    }

    public void copyWhole(T from, T to) {
        renderer().materialize(from);
        renderer().endPass();
        copyAllLevels(from, to);
        to.contentsUndefined = from.contentsUndefined;
        to.version++;
        to.needsClear = false;
        final long serial = base.currentSerial();
        from.lastUse = serial;
        to.markGpuWrite(serial);
    }

    @Override protected void copyLevels(T from, T to) {
        copyWhole(from, to);
        images().release(from);
    }

    @Override public void copyFromFramebuffer(GlState st, T dst, int target, int level, int dx, int dy, int dz, int sx, int sy, int w, int h) {
        if (dst == null || !dst.hasImage() || w <= 0 || h <= 0) return;
        final TargetBase<T> src = renderer().readTarget(st);
        final boolean depth = dst.format.isDepthOrStencil();
        final T s = depth ? src.depth : src.color[0];
        if (s == null) return;
        final boolean depthViaBuffer = depth && s.format.pf() != dst.format.pf();
        if (depthViaBuffer && (TokenMaps.depthBits(s.format.pf()) == 0 || TokenMaps.depthBits(s.format.pf()) != TokenMaps.depthBits(dst.format.pf()))) {
            warnOnce("glCopyTexImage between depth formats " + s.format.pf() + " and " + dst.format.pf() + " is not supported");
            base.recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        final int srcLevel = depth ? src.depthLevel : src.colorLevel[0];
        final int srcLayer = depth ? src.depthLayer : src.colorLayer[0];
        final int cx0 = Math.max(0, sx), cy0 = Math.max(0, sy), cx1 = Math.min(s.levelWidth(srcLevel), sx + w), cy1 = Math.min(s.levelHeight(srcLevel), sy + h);
        if (cx1 <= cx0 || cy1 <= cy0) return;
        dx += cx0 - sx;
        dy += cy0 - sy;
        final int cw = Math.min(cx1 - cx0, dst.levelWidth(level) - dx), ch = Math.min(cy1 - cy0, dst.levelHeight(level) - dy);
        if (cw <= 0 || ch <= 0 || dx < 0 || dy < 0) return;
        final int dstLayer = dst.target == GL13.GL_TEXTURE_CUBE_MAP ? cubeFace(target) : (dst.target == GL30.GL_TEXTURE_2D_ARRAY || dst.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY ? dz : 0);
        renderer().materialize(s);
        renderer().materialize(dst);
        renderer().endPass();
        if (depthViaBuffer) {
            copyDepthThroughBuffer(s, srcLayer, srcLevel, cx0, cy0, cw, ch, dst, dstLayer, level, dx, dy, dz);
        } else if (s.format.pf() == dst.format.pf()) {
            copyTextureToTexture(s, s.is3D() ? 0 : srcLayer, srcLevel, cx0, cy0, s.is3D() ? srcLayer : 0, cw, ch, 1, dst, dst.is3D() ? 0 : dstLayer, level, dx, dy, dst.is3D() ? dz : 0);
        } else {
            blitRegion(s, srcLevel, srcLayer, cx0, cy0, cw, ch, dst, level, dst.is3D() ? dz : dstLayer, dx, dy);
        }
        dst.markCopiedFrom(s, base.currentSerial());
    }

    /** The format a storage view can use for this image when the shader asks for another of the same size. */
    protected abstract int storageViewFormat(T t, int wanted);

    protected abstract long storageView(T t, int level, int layer, boolean layered, int format, boolean write);

    public long storageImageView(GlState st, int unit, boolean write) {
        final T t = storageImage(st, unit);
        if (write && (t.usage & NativeTokens.USAGE_SHADER_WRITE) == 0 && t.id > 0) enableStorage(t);
        renderer().materialize(t);
        if (write) {
            t.markGpuWrite(base.currentSerial());
            t.markWritten();
        } else {
            t.lastUse = base.currentSerial();
        }
        int format = t.format.pf();
        final int glFormat = st.imageFormat[unit];
        if (glFormat != 0) {
            final Formats.Format f = Formats.of(glFormat, depth24Stencil8, depth24);
            if (f != null && Formats.texelBytesOf(f.pf()) == Formats.texelBytesOf(format)) format = storageViewFormat(t, f.pf());
        }
        return storageView(t, Math.min(st.imageLevel[unit], t.levels - 1), st.imageLayer[unit], st.imageLayered[unit], format, write);
    }

    public T storageImage(GlState st, int unit) {
        final T t = get(st.imageTex[unit]);
        if (t != null && t.hasImage() && t.completeFor(t.minFilter)) {
            if (t.is3D() && !st.imageLayered[unit]) return sliceImage(t, st.imageLevel[unit], st.imageLayer[unit], st.imageAccess[unit] != org.lwjgl.opengl.GL15.GL_READ_ONLY);
            return t;
        }
        return st.imageAccess[unit] == org.lwjgl.opengl.GL15.GL_READ_ONLY ? renderer().dummyStorageRead : renderer().unusableImage(unit);
    }

    protected void enableStorage(T t) {
        ensureUsage(t, NativeTokens.USAGE_SHADER_WRITE);
    }

    public void ensureUsage(T t, int usage) {
        if (!t.hasImage() || (t.usage & usage) == usage) return;
        t.extraUsage |= usage;
        final T old = snapshot(t);
        clearImageHandles(t);
        onImageReleased(t);
        images().create(t, false);
        copyLevels(old, t);
        t.viewDirty = true;
    }

    @Override protected void clearImageHandles(T t) {
        t.image = 0L;
        t.view = 0L;
        t.storageViews.clear();
    }

    @Override protected boolean sampleable(Formats.Format f) {
        return images().sampleable(f.pf());
    }

    @Override protected boolean canFilterDepthOnDevice() {
        return false;
    }

    @Override protected boolean canGenerateMips(T t) {
        final int fmt = t.format.pf();
        if (!t.format.isDepthOrStencil() && images().renderable(fmt) && images().filterable(fmt)) return true;
        warnOnce("cannot generate mipmaps for format " + fmt);
        base.recordError(GL11.GL_INVALID_OPERATION);
        return false;
    }

    @Override protected void mipsGenerated(T t) {
        t.markWritten();
        t.markGpuWrite(base.currentSerial());
    }
}
