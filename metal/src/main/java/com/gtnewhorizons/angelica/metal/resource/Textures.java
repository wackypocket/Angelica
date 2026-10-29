package com.gtnewhorizons.angelica.metal.resource;

import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;
import com.gtnewhorizons.angelica.glemu.resource.BufBase;

import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;

import com.gtnewhorizons.angelica.glemu.resource.Formats;

import com.gtnewhorizons.angelica.glemu.resource.FormatUtil;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;

import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import com.gtnewhorizons.angelica.metal.memory.MtlBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class Textures extends TexturesBase<Tex> {
    private static final long COPY_B2T = sel("copyFromBuffer:sourceOffset:sourceBytesPerRow:sourceBytesPerImage:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:options:");
    private static final long COPY_T2B = sel("copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toBuffer:destinationOffset:destinationBytesPerRow:destinationBytesPerImage:options:");
    private static final long COPY_T2T = sel("copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:");
    private static final long COPY_LEVELS = sel("copyFromTexture:sourceSlice:sourceLevel:toTexture:destinationSlice:destinationLevel:sliceCount:levelCount:");
    private static final long GENERATE_MIPMAPS = sel("generateMipmapsForTexture:");

    private final MetalGL gl;
    public long uploads, uploadBytes, hoistedUploads;

    public Textures(MetalGL gl) {
        this.gl = gl;
        depth24Stencil8 = Mtl.PF_DEPTH32_FLOAT_STENCIL8;
        depth24 = Mtl.PF_DEPTH32_FLOAT;
    }

    public void pickDepthFormats() {
        depth24Stencil8 = Mtl.PF_DEPTH32_FLOAT_STENCIL8;
        depth24 = Mtl.PF_DEPTH32_FLOAT;
    }

    public Tex internalStorageTexture(Formats.Format f, String label) {
        final Tex t = new Tex(nextInternalId--, false);
        t.target = GL11.GL_TEXTURE_2D;
        t.format = f;
        t.width = t.height = 1;
        t.levels = 1;
        t.label = label;
        t.minFilter = GL11.GL_NEAREST;
        gl.images.create(t, true);
        return t;
    }

    public void copyWhole(Tex from, Tex to) {
        gl.renderer.materialize(from);
        gl.renderer.releaseFromPass(from);
        gl.renderer.releaseFromPass(to);
        gl.renderer.endPass();
        ObjC.call(gl.stream.blit(), COPY_LEVELS, from.image, 0, 0, to.image, 0, 0, sliceCount(from), Math.min(from.levels, to.levels));
        to.contentsUndefined = from.contentsUndefined;
        to.version++;
        to.needsClear = false;
        final long serial = gl.stream.currentSerial();
        from.lastUse = serial;
        to.lastUse = serial;
    }

        public void specify(Tex t, Formats.Format f, int level, int w, int h, int d, int layers) {
        specify(t, f, level, w, h, d, layers, false);
    }

    @Override protected Tex snapshot(Tex t) {
        final Tex s = new Tex(t.id, t.renderbuffer);
        s.target = t.target;
        s.format = t.format;
        s.width = t.width;
        s.height = t.height;
        s.depth = t.depth;
        s.layers = t.layers;
        s.levels = t.levels;
        s.image = t.image;
        s.view = t.view;
        s.usage = t.usage;
        s.contentsUndefined = t.contentsUndefined;
        s.lastUse = t.lastUse;
        s.storageViews.putAll(t.storageViews);
        s.pendingClear = t.pendingClear;
        s.needsClear = t.needsClear;
        System.arraycopy(t.clearColor, 0, s.clearColor, 0, 4);
        s.clearDepth = t.clearDepth;
        s.clearStencil = t.clearStencil;
        s.clearAspects = t.clearAspects;
        return s;
    }

    @Override protected void copyLevels(Tex from, Tex to) {
        gl.renderer.materialize(from);
        gl.renderer.endPass();
        final int n = Math.min(from.levels, to.levels);
        ObjC.call(gl.stream.blit(), COPY_LEVELS, from.image, 0, 0, to.image, 0, 0, sliceCount(from), n);
        to.needsClear = false;
        to.contentsUndefined = from.contentsUndefined;
        to.version++;
        final long serial = gl.stream.currentSerial();
        from.lastUse = serial;
        to.lastUse = serial;
        gl.images.release(from);
    }

    static int sliceCount(Tex t) {
        return switch (Images.textureTypeFor(t)) {
            case Mtl.TEX_CUBE -> 6;
            case Mtl.TEX_2D_ARRAY, Mtl.TEX_CUBE_ARRAY -> Math.max(1, t.layers);
            default -> 1;
        };
    }

    public void copyBufferToTexture(long blit, long buffer, long offset, long bytesPerRow, long bytesPerImage, int w, int h, int d, Tex t, int slice, int level, int x, int y, int z, int options) {
        ObjC.copyBufferToTexture(blit, COPY_B2T, buffer, offset, bytesPerRow, bytesPerImage, w, h, d, t.image, slice, level, x, y, z, options);
    }

    static int blitOptions(Tex t, int aspect) {
        if (!t.format.isDepth() || !t.format.isStencil()) return Mtl.BLIT_NONE;
        return aspect == Formats.ASPECT_STENCIL ? Mtl.BLIT_STENCIL_FROM_DEPTH_STENCIL : Mtl.BLIT_DEPTH_FROM_DEPTH_STENCIL;
    }

    @Override protected void uploadFrom(GlState st, Tex t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int format, int type, long src) {
        final Formats.Format f = t.format;
        if (x < 0 || y < 0 || z < 0 || x + w > t.levelWidth(level) || y + h > t.levelHeight(level) || t.is3D() && z + d > t.levelDepth(level) || layer + layerCount > Math.max(sliceCount(t), 1)) {
            gl.warnOnce("texture upload outside level " + level + " of texture " + t.id);
            return;
        }
        final int rows = h * Math.max(1, d) * layerCount;
        final long bytes;
        if (f.isCompressed()) {
            bytes = FormatUtil.imageBytes(f, w, h) * Math.max(1, d) * layerCount;
        } else {
            bytes = (long) w * rows * f.texelBytes();
        }
        gl.stagingRing.alloc(bytes, 16);
        final long staging = gl.stagingRing.address;
        final long stagingBuffer = gl.stagingRing.buffer;
        final long stagingOffset = gl.stagingRing.offset;
        if (f.isCompressed()) {
            MemoryUtil.memCopy(src, staging, bytes);
        } else {
            final PixelConvert.Store s = st.unpack;
            final int imgDepth = Math.max(1, d) * layerCount;
            if (!PixelConvert.unpack(f, format, type, src, w, h, imgDepth, s, staging)) {
                gl.warnOnce("cannot convert pixels format=0x" + Integer.toHexString(format) + " type=0x" + Integer.toHexString(type) + " into " + f.pf());
            }
        }
        final boolean hoist = gl.stream.canHoist(t.lastUse) && !t.pendingClear && !t.needsClear;
        final long blit;
        if (hoist) {
            blit = gl.stream.uploadBlit();
            hoistedUploads++;
        } else {
            gl.renderer.materialize(t);
            gl.renderer.releaseFromPass(t);
            gl.renderer.endPass();
            blit = gl.stream.blit();
        }
        final long rowBytes = f.isCompressed() ? (long) ((w + 3) / 4) * f.blockBytes() : (long) w * f.texelBytes();
        final long imageRows = f.isCompressed() ? (h + 3) / 4 : h;
        final long imageBytes = rowBytes * imageRows;
        final int depthSlices = Math.max(1, d);
        final int options = blitOptions(t, Formats.ASPECT_DEPTH);
        for (int l = 0; l < layerCount; l++) {
            copyBufferToTexture(blit, stagingBuffer, stagingOffset + l * imageBytes * depthSlices, rowBytes, imageBytes, w, h, depthSlices, t, layer + l, level, x, y, z, options);
        }
        t.needsClear = false;
        t.markWritten();
        if (!hoist) t.lastUse = gl.stream.currentSerial();
        uploads++;
        uploadBytes += bytes;
    }

    @Override protected void generateMips(Tex t) {
        if (t.is3D() || t.target == GL11.GL_TEXTURE_1D || t.target == GL30.GL_TEXTURE_1D_ARRAY) {
            ObjC.call(gl.stream.blit(), GENERATE_MIPMAPS, t.image);
            return;
        }
        final int slices = Math.max(1, sliceCount(t));
        for (int slice = 0; slice < slices; slice++) {
            for (int level = 1; level < t.levels; level++) {
                final int w = t.levelWidth(level), h = t.levelHeight(level);
                gl.blitPass.blit(t, level - 1, slice, 0, 0, TexBase.mipSourceExtent(t.levelWidth(level - 1), w), TexBase.mipSourceExtent(t.levelHeight(level - 1), h),
                    t, level, slice, 0, 0, w, h, true, false);
            }
        }
    }

    public void copyFromFramebuffer(GlState st, Tex dst, int target, int level, int dx, int dy, int dz, int sx, int sy, int w, int h) {
        if (dst == null || !dst.hasImage() || w <= 0 || h <= 0) return;
        final Target src = gl.renderer.readTarget(st);
        final boolean depth = dst.format.isDepthOrStencil();
        final Tex s = depth ? src.depth : src.color[0];
        if (s == null) return;
        final boolean depthViaBuffer = depth && s.format.pf() != dst.format.pf();
        if (depthViaBuffer && (depthBits(s.format.pf()) == 0 || depthBits(s.format.pf()) != depthBits(dst.format.pf()))) {
            gl.warnOnce("glCopyTexImage between depth formats " + s.format.pf() + " and " + dst.format.pf() + " is not supported");
            return;
        }
        final int srcLevel = depth ? src.depthLevel : src.colorLevel[0];
        final int srcLayer = depth ? src.depthLayer : src.colorLayer[0];
        int x0 = sx, y0 = sy, x1 = sx + w, y1 = sy + h;
        final int sw = s.levelWidth(srcLevel), sh = s.levelHeight(srcLevel);
        final int cx0 = Math.max(0, x0), cy0 = Math.max(0, y0), cx1 = Math.min(sw, x1), cy1 = Math.min(sh, y1);
        if (cx1 <= cx0 || cy1 <= cy0) return;
        dx += cx0 - x0;
        dy += cy0 - y0;
        final int cw = Math.min(cx1 - cx0, dst.levelWidth(level) - dx), ch = Math.min(cy1 - cy0, dst.levelHeight(level) - dy);
        if (cw <= 0 || ch <= 0 || dx < 0 || dy < 0) return;
        final int dstLayer = dst.isCube() ? cubeFace(target) : (dst.target == GL30.GL_TEXTURE_2D_ARRAY ? dz : 0);
        gl.renderer.materialize(s);
        gl.renderer.materialize(dst);
        gl.renderer.releaseFromPass(s);
        gl.renderer.releaseFromPass(dst);
        gl.renderer.endPass();
        if (depthViaBuffer) {
            final int px = depthBits(s.format.pf()) == 16 ? 2 : 4;
            final long bytes = (long) cw * ch * px;
            final MtlBuffer staging = MtlBuffer.create(gl.gpu, bytes, MtlBuffer.USAGE_DEVICE);
            final long blit = gl.stream.blit();
            ObjC.copyTextureToBuffer(blit, COPY_T2B, s.image, s.is3D() ? 0 : srcLayer, srcLevel, cx0, cy0, s.is3D() ? srcLayer : 0, cw, ch, 1, staging.buffer, 0, (long) cw * px, bytes, blitOptions(s, Formats.ASPECT_DEPTH));
            copyBufferToTexture(blit, staging.buffer, 0, (long) cw * px, bytes, cw, ch, 1, dst, dst.is3D() ? 0 : dstLayer, level, dx, dy, dst.is3D() ? dz : 0, blitOptions(dst, Formats.ASPECT_DEPTH));
            gl.stream.defer(staging::destroy);
        } else if (s.format.pf() == dst.format.pf()) {
            ObjC.copyTextureToTexture(gl.stream.blit(), COPY_T2T, s.image, s.is3D() ? 0 : srcLayer, srcLevel, cx0, cy0, s.is3D() ? srcLayer : 0, cw, ch, 1,
                dst.image, dst.is3D() ? 0 : dstLayer, level, dx, dy, dst.is3D() ? dz : 0);
        } else {
            gl.blitPass.blit(s, srcLevel, srcLayer, cx0, cy0, cx0 + cw, cy0 + ch, dst, level, dst.is3D() ? dz : dstLayer, dx, dy, dx + cw, dy + ch, false, false);
        }
        dst.markWritten();
        dst.needsClear = false;
        final long serial = gl.stream.currentSerial();
        s.lastUse = serial;
        dst.lastUse = serial;
    }

    private static int depthBits(int fmt) {
        return switch (fmt) {
            case Mtl.PF_DEPTH16_UNORM -> 16;
            case Mtl.PF_DEPTH24_UNORM_STENCIL8 -> 24;
            case Mtl.PF_DEPTH32_FLOAT, Mtl.PF_DEPTH32_FLOAT_STENCIL8 -> 32;
            default -> 0;
        };
    }

    public void copyImage(Tex src, int srcLevel, int sx, int sy, int sz, Tex dst, int dstLevel, int dx, int dy, int dz, int w, int h, int d) {
        if (src == null || dst == null || !src.hasImage() || !dst.hasImage()) return;
        gl.renderer.materialize(src);
        gl.renderer.materialize(dst);
        gl.renderer.releaseFromPass(src);
        gl.renderer.releaseFromPass(dst);
        gl.renderer.endPass();
        final boolean srcLayered = sliceCount(src) > 1, dstLayered = sliceCount(dst) > 1;
        final long blit = gl.stream.blit();
        final int slices = srcLayered || dstLayered ? d : 1;
        final int depth = srcLayered || dstLayered ? 1 : d;
        if (src.format.pf() == dst.format.pf()) {
            for (int i = 0; i < slices; i++) {
                ObjC.copyTextureToTexture(blit, COPY_T2T, src.image, srcLayered ? sz + i : 0, srcLevel, sx, sy, srcLayered ? 0 : sz, w, h, depth,
                    dst.image, dstLayered ? dz + i : 0, dstLevel, dx, dy, dstLayered ? 0 : dz);
            }
        } else {
            final int texel = Formats.texelBytesOf(src.format.pf());
            final long rowBytes = (long) w * texel, imageBytes = rowBytes * h;
            final MtlBuffer staging = MtlBuffer.create(gl.gpu, imageBytes * depth * slices, MtlBuffer.USAGE_DEVICE);
            for (int i = 0; i < slices; i++) {
                final long at = imageBytes * depth * i;
                ObjC.copyTextureToBuffer(blit, COPY_T2B, src.image, srcLayered ? sz + i : 0, srcLevel, sx, sy, srcLayered ? 0 : sz, w, h, depth, staging.buffer, at, rowBytes, imageBytes, Mtl.BLIT_NONE);
                copyBufferToTexture(blit, staging.buffer, at, rowBytes, imageBytes, w, h, depth, dst, dstLayered ? dz + i : 0, dstLevel, dx, dy, dstLayered ? 0 : dz, Mtl.BLIT_NONE);
            }
            gl.stream.defer(staging::destroy);
        }
        dst.markWritten();
        dst.needsClear = false;
        final long serial = gl.stream.currentSerial();
        src.lastUse = serial;
        dst.lastUse = serial;
    }

    @Override public boolean readImage(Tex t, int aspect, int level, int layer, int x, int y, int w, int h, int format, int type, PixelConvert.Store pack, long dst) {
        if (t == null || !t.hasImage() || w <= 0 || h <= 0) return false;
        gl.renderer.materialize(t);
        gl.renderer.releaseFromPass(t);
        gl.renderer.endPass();
        final int texel = aspect == Formats.ASPECT_STENCIL ? 1 : t.format.isDepthOrStencil() ? (t.format.pf() == Mtl.PF_DEPTH16_UNORM ? 2 : 4) : t.format.texelBytes();
        final long bytes = (long) w * h * texel;
        final MtlBuffer staging = MtlBuffer.create(gl.gpu, bytes, MtlBuffer.USAGE_READBACK);
        try {
            ObjC.copyTextureToBuffer(gl.stream.blit(), COPY_T2B, t.image, t.is3D() ? 0 : layer, level, x, y, t.is3D() ? layer : 0, w, h, 1,
                staging.buffer, 0, (long) w * texel, bytes, blitOptions(t, aspect));
            t.lastUse = gl.stream.currentSerial();
            gl.flushAndWait(gl.stream.currentSerial());
            final Formats.Format view = aspect == Formats.ASPECT_STENCIL ? null : t.format;
            if (view == null) {
                for (int row = 0; row < h; row++) {
                    for (int col = 0; col < w; col++) {
                        MemoryUtil.memPutByte(dst + (long) row * PixelConvert.rowStride(w, format, type, pack) + col, MemoryUtil.memGetByte(staging.mapped + (long) row * w + col));
                    }
                }
                return true;
            }
            if (!PixelConvert.pack(view, staging.mapped, w, h, format, type, pack, dst)) return false;
            if (format == GL30.GL_DEPTH_STENCIL && t.format.isStencil()) mergeStencil(t, level, layer, x, y, w, h, type, pack, dst);
            return true;
        } finally {
            staging.destroy();
        }
    }

    public long storageImageView(GlState st, int unit, Target target) {
        Tex t = get(st.imageTex[unit]);
        if (t == null || !t.hasImage()) {
            t = gl.renderer.dummyStorage;
        }
        if ((t.usage & Mtl.USAGE_SHADER_WRITE) == 0 && t.id > 0) {
            enableStorage(t);
        }
        gl.renderer.materialize(t);
        t.lastUse = gl.stream.currentSerial();
        t.markWritten();
        int fmt = t.format.pf();
        final int glFmt = st.imageFormat[unit];
        if (glFmt != 0) {
            final Formats.Format f = Formats.of(glFmt, depth24Stencil8, depth24);
            if (f != null && Formats.texelBytesOf(f.pf()) == Formats.texelBytesOf(fmt)) fmt = f.pf();
        }
        if (Images.needsFormatView(t.format.pf(), fmt) && t.id > 0) ensureUsage(t, Mtl.USAGE_PIXEL_FORMAT_VIEW);
        if (Images.needsFormatView(t.format.pf(), fmt) && (t.usage & Mtl.USAGE_PIXEL_FORMAT_VIEW) == 0) fmt = t.format.pf();
        return gl.images.storageView(t, Math.min(st.imageLevel[unit], t.levels - 1), st.imageLayer[unit], st.imageLayered[unit], fmt);
    }

    public Tex storageImage(GlState st, int unit) {
        final Tex t = get(st.imageTex[unit]);
        return t == null || !t.hasImage() ? gl.renderer.dummyStorage : t;
    }

    private void enableStorage(Tex t) {
        ensureUsage(t, Mtl.USAGE_SHADER_WRITE);
    }

    public void ensureUsage(Tex t, int usage) {
        if (!t.hasImage() || (t.usage & usage) == usage) return;
        t.extraUsage |= usage;
        final Tex old = snapshot(t);
        t.image = 0L;
        t.view = 0L;
        t.storageViews.clear();
        gl.renderer.onImageReleased(t);
        gl.images.create(t, false);
        copyLevels(old, t);
        t.viewDirty = true;
    }

    @Override protected Tex newTex(int id, boolean renderbuffer) {
        return new Tex(id, renderbuffer);
    }

    @Override protected void warnOnce(String message) {
        gl.warnOnce(message);
    }

    @Override protected int getInteger(int pname) {
        return gl.getInteger(pname);
    }

    @Override protected void createImage(Tex t) {
        gl.images.create(t, false);
    }

    @Override protected void releaseImage(Tex t) {
        gl.images.release(t);
    }

    @Override protected void onImageReleased(Tex t) {
        gl.renderer.onImageReleased(t);
    }

    @Override protected void clearImageHandles(Tex t) {
        t.image = 0L;
        t.view = 0L;
        t.storageViews.clear();
    }

    @Override protected void targetsChanged() {
        gl.renderer.targetGen++;
    }

    @Override protected void onTextureDeleted(int id, boolean renderbuffer) {
        gl.framebuffers.onTextureDeleted(id, renderbuffer);
    }

    @Override protected boolean sampleable(Formats.Format f) {
        return gl.images.sampleable(f.pf());
    }

    @Override protected BufBase unpackBuffer(int id) {
        return gl.buffers.get(id);
    }

    @Override protected void readBuffer(BufBase buffer, long offset, long size, long dst) {
        gl.buffers.read((Buf) buffer, offset, size, dst);
    }

    @Override protected boolean isIdle(long serial) {
        return gl.stream.isIdle(serial);
    }

    @Override protected void flushAndWait(long serial) {
        gl.flushAndWait(serial);
    }

    @Override protected boolean canGenerateMips(Tex t) {
        final int fmt = t.format.pf();
        if (!t.format.isDepthOrStencil() && gl.images.renderable(fmt) && gl.images.filterable(fmt)) return true;
        gl.warnOnce("cannot generate mipmaps for format " + fmt);
        return false;
    }

    @Override protected void prepareForMipGeneration(Tex t) {
        gl.renderer.materialize(t);
        gl.renderer.releaseFromPass(t);
        gl.renderer.endPass();
    }

    @Override protected void mipsGenerated(Tex t) {
        t.markWritten();
        t.lastUse = gl.stream.currentSerial();
    }
}
