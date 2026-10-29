package com.gtnewhorizons.angelica.dx12.resource;

import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;
import com.gtnewhorizons.angelica.glemu.resource.BufBase;

import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;

import com.gtnewhorizons.angelica.glemu.resource.Formats;

import com.gtnewhorizons.angelica.glemu.resource.TexBase;

import com.gtnewhorizons.angelica.dx12.device.Gfx;
import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.dx12.device.Dxgi;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import com.gtnewhorizons.angelica.dx12.memory.GpuBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

public final class Textures extends TexturesBase<Tex> {
    private final Dx12GL gl;
    public long uploads, uploadBytes, hoistedUploads;

    public Textures(Dx12GL gl) {
        this.gl = gl;
        depth24Stencil8 = Gfx.PF_DEPTH32_FLOAT_STENCIL8;
        depth24 = Gfx.PF_DEPTH32_FLOAT;
    }

    public void pickDepthFormats() {
        final boolean d24 = gl.gpu.depth24Stencil8;
        depth24Stencil8 = d24 ? Gfx.PF_DEPTH24_UNORM_STENCIL8 : Gfx.PF_DEPTH32_FLOAT_STENCIL8;
        depth24 = d24 ? Gfx.PF_DEPTH24_UNORM_STENCIL8 : Gfx.PF_DEPTH32_FLOAT;
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
        copyLevelsRaw(from, to, sliceCount(from), Math.min(from.levels, to.levels));
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
        s.state = t.state;
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
        copyLevelsRaw(from, to, sliceCount(from), n);
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
            case Gfx.TEX_CUBE -> 6;
            case Gfx.TEX_2D_ARRAY, Gfx.TEX_CUBE_ARRAY -> Math.max(1, t.layers);
            default -> 1;
        };
    }

    public static final long PLACEMENT_ALIGNMENT = 512, PITCH_ALIGNMENT = 256;

    private final java.lang.foreign.MemorySegment dstLoc = java.lang.foreign.Arena.global().allocate(48, 8), srcLoc = java.lang.foreign.Arena.global().allocate(48, 8);
    private final java.lang.foreign.MemorySegment box = java.lang.foreign.Arena.global().allocate(24, 4);

    static long align(long v, long a) {
        return (v + a - 1) / a * a;
    }

    static long rowBytes(Formats.Format f, int w) {
        return f.isCompressed() ? (long) ((w + 3) / 4) * f.blockBytes() : (long) w * f.texelBytes();
    }

    static long rowCount(Formats.Format f, int h) {
        return f.isCompressed() ? (h + 3) / 4 : h;
    }

    public static long alignedRowPitch(Formats.Format f, int w) {
        return align(rowBytes(f, w), PITCH_ALIGNMENT);
    }

    public static long alignedImageBytes(Formats.Format f, int w, int h, int d) {
        return align(alignedRowPitch(f, w) * rowCount(f, h) * Math.max(1, d), PLACEMENT_ALIGNMENT);
    }

    private void staging(GpuBuffer b, int state) {
        gl.stream.transition(b.buffer, 0, b.state, state);
        b.state = state;
    }

    private static int planeOf(Tex t, int options) {
        return options == Gfx.BLIT_STENCIL_FROM_DEPTH_STENCIL && t.format.isStencil() ? 1 : 0;
    }

    private static int planes(Tex t) {
        return t.format.isDepth() && t.format.isStencil() ? 2 : 1;
    }

    private void setSubresource(java.lang.foreign.MemorySegment loc, long resource, int subresource) {
        loc.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, resource);
        loc.set(java.lang.foreign.ValueLayout.JAVA_INT, 8, 0);
        loc.set(java.lang.foreign.ValueLayout.JAVA_INT, 16, subresource);
    }

    private void setFootprint(java.lang.foreign.MemorySegment loc, long buffer, long offset, int dxgi, int w, int h, int d, long pitch) {
        loc.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, buffer);
        loc.set(java.lang.foreign.ValueLayout.JAVA_INT, 8, 1);
        loc.set(java.lang.foreign.ValueLayout.JAVA_LONG, 16, offset);
        loc.set(java.lang.foreign.ValueLayout.JAVA_INT, 24, dxgi);
        loc.set(java.lang.foreign.ValueLayout.JAVA_INT, 28, w);
        loc.set(java.lang.foreign.ValueLayout.JAVA_INT, 32, h);
        loc.set(java.lang.foreign.ValueLayout.JAVA_INT, 36, d);
        loc.set(java.lang.foreign.ValueLayout.JAVA_INT, 40, (int) pitch);
    }

    private static int footprintFormat(Tex t, int options) {
        if (t.format.isDepthOrStencil()) {
            if (planeOf(t, options) == 1) return 60;
            return switch (t.format.pf()) {
                case Gfx.PF_DEPTH16_UNORM -> 53;
                case Gfx.PF_DEPTH32_FLOAT, Gfx.PF_DEPTH32_FLOAT_STENCIL8 -> 39;
                default -> Dxgi.R24_UNORM_X8_TYPELESS;
            };
        }
        return Dxgi.view(t.format.pf());
    }

    private static int blockAlign(Formats.Format f, int v) {
        return f.isCompressed() ? (v + 3) & ~3 : v;
    }

    private void setBox(int x, int y, int z, int w, int h, int d) {
        box.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, x);
        box.set(java.lang.foreign.ValueLayout.JAVA_INT, 4, y);
        box.set(java.lang.foreign.ValueLayout.JAVA_INT, 8, z);
        box.set(java.lang.foreign.ValueLayout.JAVA_INT, 12, x + w);
        box.set(java.lang.foreign.ValueLayout.JAVA_INT, 16, y + h);
        box.set(java.lang.foreign.ValueLayout.JAVA_INT, 20, z + d);
    }

    private void toState(long list, Tex t, int state) {
        if (gl.stream.isUploadList(list)) {
            gl.images.transitionUpload(t, state);
        } else {
            gl.images.transition(t, state);
            gl.stream.flushBarriers();
        }
    }

    public void copyLevelsRaw(Tex from, Tex to, int slices, int levels) {
        gl.images.transition(from, Dx.STATE_COPY_SOURCE);
        gl.images.transition(to, Dx.STATE_COPY_DEST);
        final long list = gl.stream.list();
        final int planes = Math.min(planes(from), planes(to));
        for (int plane = 0; plane < planes; plane++) {
            for (int s = 0; s < slices; s++) {
                for (int l = 0; l < levels; l++) {
                    setSubresource(dstLoc, to.image, Images.subresource(to, l, s, plane));
                    setSubresource(srcLoc, from.image, Images.subresource(from, l, s, plane));
                    Com.v(list, Dx.CL_COPY_TEXTURE, dstLoc.address(), 0, 0, 0, srcLoc.address(), 0L);
                }
            }
        }
    }

    public void copyBufferToTexture(long list, long buffer, long offset, long bytesPerRow, long bytesPerImage, int w, int h, int d, Tex t, int slice, int level, int x, int y, int z, int options) {
        toState(list, t, Dx.STATE_COPY_DEST);
        if (gl.stream.isUploadList(list)) t.lastTransferWrite = gl.stream.currentSerial();
        final int plane = planeOf(t, options);
        setSubresource(dstLoc, t.image, Images.subresource(t, level, slice, plane));
        setFootprint(srcLoc, buffer, offset, footprintFormat(t, options), blockAlign(t.format, w), blockAlign(t.format, h), d, bytesPerRow);
        Com.v(list, Dx.CL_COPY_TEXTURE, dstLoc.address(), x, y, z, srcLoc.address(), 0L);
    }

    public void copyTextureToBuffer(long list, Tex t, int slice, int level, int x, int y, int z, int w, int h, int d, long buffer, long offset, long bytesPerRow, int options) {
        toState(list, t, Dx.STATE_COPY_SOURCE);
        final int plane = planeOf(t, options);
        setSubresource(srcLoc, t.image, Images.subresource(t, level, slice, plane));
        setFootprint(dstLoc, buffer, offset, footprintFormat(t, options), blockAlign(t.format, w), blockAlign(t.format, h), d, bytesPerRow);
        setBox(x, y, z, w, h, d);
        Com.v(list, Dx.CL_COPY_TEXTURE, dstLoc.address(), 0, 0, 0, srcLoc.address(), box.address());
    }

    public void copyTextureToTexture(Tex s, int sSlice, int sLevel, int sx, int sy, int sz, int w, int h, int d, Tex dst, int dSlice, int dLevel, int dx, int dy, int dz) {
        gl.images.transition(s, Dx.STATE_COPY_SOURCE);
        gl.images.transition(dst, Dx.STATE_COPY_DEST);
        final long list = gl.stream.list();
        final int planes = Math.min(planes(s), planes(dst));
        for (int plane = 0; plane < planes; plane++) {
            setSubresource(dstLoc, dst.image, Images.subresource(dst, dLevel, dSlice, plane));
            setSubresource(srcLoc, s.image, Images.subresource(s, sLevel, sSlice, plane));
            setBox(sx, sy, sz, w, h, d);
            Com.v(list, Dx.CL_COPY_TEXTURE, dstLoc.address(), dx, dy, dz, srcLoc.address(), box.address());
        }
    }

    @Override protected void generateMips(Tex t) {
        final int slices = t.is3D() ? 1 : Math.max(1, sliceCount(t));
        if (t.is3D() || t.isCube() && slices != 6 && false) {
            gl.warnOnce("mipmap generation for 3D textures is not supported on Direct3D 12");
            return;
        }
        for (int slice = 0; slice < slices; slice++) {
            Tex prev = internalTexture(GL11.GL_TEXTURE_2D, t.format, t.width, t.height, 1, 1, "mipgen");
            gl.blitPass.blit(t, 0, slice, 0, 0, t.width, t.height, prev, 0, 0, 0, 0, t.width, t.height, true, false);
            for (int level = 1; level < t.levels; level++) {
                final int w = t.levelWidth(level), h = t.levelHeight(level);
                final Tex next = internalTexture(GL11.GL_TEXTURE_2D, t.format, w, h, 1, 1, "mipgen");
                gl.blitPass.blit(prev, 0, 0, 0, 0, TexBase.mipSourceExtent(prev.width, w), TexBase.mipSourceExtent(prev.height, h), next, 0, 0, 0, 0, w, h, true, false);
                copyTextureToTexture(next, 0, 0, 0, 0, 0, w, h, 1, t, slice, level, 0, 0, 0);
                final Tex done = prev;
                gl.stream.defer(() -> gl.images.release(done));
                prev = next;
            }
            final Tex last = prev;
            gl.stream.defer(() -> gl.images.release(last));
        }
    }
    static int blitOptions(Tex t, int aspect) {
        if (!t.format.isDepth() || !t.format.isStencil()) return Gfx.BLIT_NONE;
        return aspect == Formats.ASPECT_STENCIL ? Gfx.BLIT_STENCIL_FROM_DEPTH_STENCIL : Gfx.BLIT_DEPTH_FROM_DEPTH_STENCIL;
    }

    @Override protected void uploadFrom(GlState st, Tex t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int format, int type, long src) {
        final Formats.Format f = t.format;
        if (x < 0 || y < 0 || z < 0 || x + w > t.levelWidth(level) || y + h > t.levelHeight(level) || t.is3D() && z + d > t.levelDepth(level) || layer + layerCount > Math.max(sliceCount(t), 1)) {
            gl.warnOnce("texture upload outside level " + level + " of texture " + t.id);
            return;
        }
        final int depthSlices = Math.max(1, d);
        final long rowBytes = rowBytes(f, w);
        final long imageRows = rowCount(f, h);
        final long pitch = align(rowBytes, PITCH_ALIGNMENT);
        final long imageStride = pitch * imageRows;
        final long layerStride = align(imageStride * depthSlices, PLACEMENT_ALIGNMENT);
        final long bytes = layerStride * layerCount;
        final long tight = rowBytes * imageRows * depthSlices * layerCount;
        gl.stagingRing.alloc(bytes, PLACEMENT_ALIGNMENT);
        final long staging = gl.stagingRing.address;
        final long stagingBuffer = gl.stagingRing.buffer;
        final long stagingOffset = gl.stagingRing.offset;
        final long packed;
        final boolean repack = pitch != rowBytes || layerStride != rowBytes * imageRows * depthSlices;
        packed = repack ? MemoryUtil.nmemAlloc(Math.max(1, tight)) : staging;
        try {
            if (f.isCompressed()) {
                MemoryUtil.memCopy(src, packed, tight);
            } else {
                final PixelConvert.Store s = st.unpack;
                if (!PixelConvert.unpack(f, format, type, src, w, h, depthSlices * layerCount, s, packed)) {
                    gl.warnOnce("cannot convert pixels format=0x" + Integer.toHexString(format) + " type=0x" + Integer.toHexString(type) + " into " + f.pf());
                }
            }
            if (repack) {
                for (int l = 0; l < layerCount; l++) {
                    for (int img = 0; img < depthSlices; img++) {
                        final long from = packed + ((long) l * depthSlices + img) * rowBytes * imageRows;
                        final long to = staging + l * layerStride + img * imageStride;
                        for (long r = 0; r < imageRows; r++) MemoryUtil.memCopy(from + r * rowBytes, to + r * pitch, rowBytes);
                    }
                }
            }
        } finally {
            if (repack) MemoryUtil.nmemFree(packed);
        }
        final boolean hoist = gl.stream.canHoist(t.lastUse) && !t.pendingClear && !t.needsClear;
        final long list;
        if (hoist) {
            list = gl.stream.uploadList();
            hoistedUploads++;
        } else {
            gl.renderer.materialize(t);
            gl.renderer.releaseFromPass(t);
            gl.renderer.endPass();
            list = gl.stream.list();
        }
        final int options = blitOptions(t, Formats.ASPECT_DEPTH);
        for (int l = 0; l < layerCount; l++) {
            copyBufferToTexture(list, stagingBuffer, stagingOffset + l * layerStride, pitch, imageStride, w, h, depthSlices, t, layer + l, level, x, y, z, options);
        }        t.needsClear = false;
        t.markWritten();
        if (!hoist) t.lastUse = gl.stream.currentSerial();
        uploads++;
        uploadBytes += bytes;
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
            final long pitch = align((long) cw * px, PITCH_ALIGNMENT);
            final long bytes = pitch * ch;
            final GpuBuffer staging = GpuBuffer.create(gl.gpu, bytes, GpuBuffer.USAGE_DEVICE);
            final long list = gl.stream.list();
            staging(staging, Dx.STATE_COPY_DEST);
            copyTextureToBuffer(list, s, s.is3D() ? 0 : srcLayer, srcLevel, cx0, cy0, s.is3D() ? srcLayer : 0, cw, ch, 1, staging.buffer, 0, pitch, blitOptions(s, Formats.ASPECT_DEPTH));
            staging(staging, Dx.STATE_COPY_SOURCE);
            copyBufferToTexture(list, staging.buffer, 0, pitch, bytes, cw, ch, 1, dst, dst.is3D() ? 0 : dstLayer, level, dx, dy, dst.is3D() ? dz : 0, blitOptions(dst, Formats.ASPECT_DEPTH));
            gl.stream.defer(staging::destroy);        } else if (s.format.pf() == dst.format.pf()) {
            copyTextureToTexture(s, s.is3D() ? 0 : srcLayer, srcLevel, cx0, cy0, s.is3D() ? srcLayer : 0, cw, ch, 1, dst, dst.is3D() ? 0 : dstLayer, level, dx, dy, dst.is3D() ? dz : 0);
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
            case Gfx.PF_DEPTH16_UNORM -> 16;
            case Gfx.PF_DEPTH24_UNORM_STENCIL8 -> 24;
            case Gfx.PF_DEPTH32_FLOAT, Gfx.PF_DEPTH32_FLOAT_STENCIL8 -> 32;
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
        final int slices = srcLayered || dstLayered ? d : 1;
        final int depth = srcLayered || dstLayered ? 1 : d;
        if (src.format.pf() == dst.format.pf()) {
            for (int i = 0; i < slices; i++) {
                copyTextureToTexture(src, srcLayered ? sz + i : 0, srcLevel, sx, sy, srcLayered ? 0 : sz, w, h, depth, dst, dstLayered ? dz + i : 0, dstLevel, dx, dy, dstLayered ? 0 : dz);
            }
        } else {
            final int texel = Formats.texelBytesOf(src.format.pf());
            final long pitch = align((long) w * texel, PITCH_ALIGNMENT);
            final long imageBytes = align(pitch * h * depth, PLACEMENT_ALIGNMENT);
            final GpuBuffer staging = GpuBuffer.create(gl.gpu, imageBytes * slices, GpuBuffer.USAGE_DEVICE);
            final long list = gl.stream.list();
            for (int i = 0; i < slices; i++) {
                final long at = imageBytes * i;
                staging(staging, Dx.STATE_COPY_DEST);
                copyTextureToBuffer(list, src, srcLayered ? sz + i : 0, srcLevel, sx, sy, srcLayered ? 0 : sz, w, h, depth, staging.buffer, at, pitch, Gfx.BLIT_NONE);
                staging(staging, Dx.STATE_COPY_SOURCE);
                copyBufferToTexture(list, staging.buffer, at, pitch, imageBytes, w, h, depth, dst, dstLayered ? dz + i : 0, dstLevel, dx, dy, dstLayered ? 0 : dz, Gfx.BLIT_NONE);
            }
            gl.stream.defer(staging::destroy);
        }        dst.markWritten();
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
        final int texel = aspect == Formats.ASPECT_STENCIL ? 1 : t.format.isDepthOrStencil() ? (t.format.pf() == Gfx.PF_DEPTH16_UNORM ? 2 : 4) : t.format.texelBytes();
        final long pitch = align((long) w * texel, PITCH_ALIGNMENT);
        final long bytes = pitch * h;
        final GpuBuffer staging = GpuBuffer.create(gl.gpu, bytes, GpuBuffer.USAGE_READBACK);
        try {
            copyTextureToBuffer(gl.stream.list(), t, t.is3D() ? 0 : layer, level, x, y, t.is3D() ? layer : 0, w, h, 1, staging.buffer, 0, pitch, blitOptions(t, aspect));
            t.lastUse = gl.stream.currentSerial();
            gl.flushAndWait(gl.stream.currentSerial());
            final Formats.Format view = aspect == Formats.ASPECT_STENCIL ? null : t.format;
            if (view == null) {
                for (int row = 0; row < h; row++) {
                    for (int col = 0; col < w; col++) {
                        MemoryUtil.memPutByte(dst + (long) row * PixelConvert.rowStride(w, format, type, pack) + col, MemoryUtil.memGetByte(staging.mapped + row * pitch + col));
                    }
                }
                return true;
            }
            if (!PixelConvert.pack(view, staging.mapped, w, h, format, type, pack, dst, pitch)) return false;
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
        if ((t.usage & Gfx.USAGE_SHADER_WRITE) == 0 && t.id > 0) {
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
        if (Images.needsFormatView(t.format.pf(), fmt) && t.id > 0) ensureUsage(t, Gfx.USAGE_PIXEL_FORMAT_VIEW);
        if (Images.needsFormatView(t.format.pf(), fmt) && (t.usage & Gfx.USAGE_PIXEL_FORMAT_VIEW) == 0) fmt = t.format.pf();
        return gl.images.storageView(t, Math.min(st.imageLevel[unit], t.levels - 1), st.imageLayer[unit], st.imageLayered[unit], fmt);
    }

    public Tex storageImage(GlState st, int unit) {
        final Tex t = get(st.imageTex[unit]);
        return t == null || !t.hasImage() ? gl.renderer.dummyStorage : t;
    }

    private void enableStorage(Tex t) {
        ensureUsage(t, Gfx.USAGE_SHADER_WRITE);
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
