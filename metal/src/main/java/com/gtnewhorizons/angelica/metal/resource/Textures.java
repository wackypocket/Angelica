package com.gtnewhorizons.angelica.metal.resource;

import org.lwjgl.system.MemoryUtil;
import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.gl.TokenMaps;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import com.gtnewhorizons.angelica.glemu.resource.TokenTexturesBase;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import com.gtnewhorizons.angelica.metal.memory.MtlBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class Textures extends TokenTexturesBase<Tex> {
    private static final long COPY_B2T = sel("copyFromBuffer:sourceOffset:sourceBytesPerRow:sourceBytesPerImage:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:options:");
    private static final long COPY_T2B = sel("copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toBuffer:destinationOffset:destinationBytesPerRow:destinationBytesPerImage:options:");
    private static final long COPY_T2T = sel("copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:");
    private static final long COPY_LEVELS = sel("copyFromTexture:sourceSlice:sourceLevel:toTexture:destinationSlice:destinationLevel:sliceCount:levelCount:");
    private static final long GENERATE_MIPMAPS = sel("generateMipmapsForTexture:");
    private final MetalGL gl;
    public Textures(MetalGL gl) {
        super(gl);
        this.gl = gl;
        depth24Stencil8 = Mtl.PF_DEPTH32_FLOAT_STENCIL8;
        depth24 = Mtl.PF_DEPTH32_FLOAT;
    }

    @Override protected void copyAllLevels(Tex from, Tex to) {
        ObjC.call(gl.stream.blit(), COPY_LEVELS, from.image, 0, 0, to.image, 0, 0, TokenMaps.sliceCount(from), Math.min(from.levels, to.levels));
    }

    @Override public void copyTextureToTexture(Tex s, int sSlice, int sLevel, int sx, int sy, int sz, int w, int h, int d, Tex dst, int dSlice, int dLevel, int dx, int dy, int dz) {
        ObjC.copyTextureToTexture(gl.stream.blit(), COPY_T2T, s.image, sSlice, sLevel, sx, sy, sz, w, h, d, dst.image, dSlice, dLevel, dx, dy, dz);
    }

    @Override protected Tex snapshot(Tex t) {
        final Tex s = new Tex(t.id, t.renderbuffer);
        t.copyStateTo(s);
        return s;
    }

    public void copyBufferToTexture(long blit, long buffer, long offset, long bytesPerRow, long bytesPerImage, int w, int h, int d, Tex t, int slice, int level, int x, int y, int z, int options) {
        ObjC.copyBufferToTexture(blit, COPY_B2T, buffer, offset, bytesPerRow, bytesPerImage, w, h, d, t.image, slice, level, x, y, z, options);
    }

    private boolean canHoistUpload(Tex t) {
        return gl.stream.canHoist(t.lastUse) || SystemProperties.NATIVE_EARLY_UPLOADS && gl.stream.canHoist(t.lastGpuWrite);
    }

    @Override protected void uploadFrom(GlState st, Tex t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int format, int type, long src) {
        final Formats.Format f = t.format;
        final long bytes = tightUploadBytes(f, w, h, d, layerCount);
        gl.stagingRing.alloc(bytes, 16);
        final long staging = gl.stagingRing.address;
        final long stagingBuffer = gl.stagingRing.buffer;
        final long stagingOffset = gl.stagingRing.offset;
        if (!writeUpload(st, f, format, type, src, w, h, d, layerCount, staging)) return;
        // An upload to a texture the open submission has not used (with early uploads, not written) rides the upload command buffer, which commits ahead of the main one.
        final boolean hoist = canHoistUpload(t) && !t.pendingClear && !t.needsClear;
        final long blit;
        if (hoist) {
            blit = gl.stream.uploadBlit();
            hoistedUploads++;
        } else {
            gl.renderer.materialize(t);
            gl.renderer.endPass();
            blit = gl.stream.blit();
        }
        final long rowBytes = Images.rowBytes(f, w);
        final long imageRows = f.isCompressed() ? (h + 3) / 4 : h;
        final long imageBytes = rowBytes * imageRows;
        final int depthSlices = Math.max(1, d);
        final int options = blitOptions(t, Formats.ASPECT_DEPTH);
        for (int l = 0; l < layerCount; l++) {
            copyBufferToTexture(blit, stagingBuffer, stagingOffset + l * imageBytes * depthSlices, rowBytes, imageBytes, w, h, depthSlices, t, layer + l, level, x, y, z, options);
        }
        t.needsClear = false;
        t.markWritten();
        if (!hoist) t.markGpuWrite(gl.stream.currentSerial());
        uploads++;
        uploadBytes += bytes;
    }

    @Override protected void writeStencil(Tex t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, long tight) {
        final long image = (long) w * h * d;
        gl.stagingRing.alloc(image * layerCount, 16);
        MemoryUtil.memCopy(tight, gl.stagingRing.address, image * layerCount);
        gl.renderer.materialize(t);
        gl.renderer.endPass();
        final long blit = gl.stream.blit();
        for (int l = 0; l < layerCount; l++) {
            copyBufferToTexture(blit, gl.stagingRing.buffer, gl.stagingRing.offset + l * image, w, (long) w * h, w, h, d, t, layer + l, level, x, y, z, blitOptions(t, Formats.ASPECT_STENCIL));
        }
        t.markGpuWrite(gl.stream.currentSerial());
    }

    @Override protected void generateMips(Tex t) {
        if (t.is3D() || t.target == GL11.GL_TEXTURE_1D || t.target == GL30.GL_TEXTURE_1D_ARRAY) {
            ObjC.call(gl.stream.blit(), GENERATE_MIPMAPS, t.image);
            return;
        }
        final int slices = TokenMaps.sliceCount(t);
        for (int slice = 0; slice < slices; slice++) {
            for (int level = 1; level < t.levels; level++) {
                final int w = t.levelWidth(level), h = t.levelHeight(level);
                gl.blitPass.blit(t, level - 1, slice, 0, 0, TexBase.mipSourceExtent(t.levelWidth(level - 1), w), TexBase.mipSourceExtent(t.levelHeight(level - 1), h),
                    t, level, slice, 0, 0, w, h, true, false);
            }
        }
    }

    @Override protected void copyDepthThroughBuffer(Tex s, int srcLayer, int srcLevel, int sx, int sy, int w, int h, Tex dst, int dstLayer, int level, int dx, int dy, int dz) {
        final int px = TokenMaps.depthBits(s.format.pf()) == 16 ? 2 : 4;
        final long bytes = (long) w * h * px;
        final MtlBuffer staging = MtlBuffer.create(gl.gpu, bytes, MtlBuffer.USAGE_DEVICE);
        final long blit = gl.stream.blit();
        ObjC.copyTextureToBuffer(blit, COPY_T2B, s.image, s.is3D() ? 0 : srcLayer, srcLevel, sx, sy, s.is3D() ? srcLayer : 0, w, h, 1, staging.buffer, 0, (long) w * px, bytes, blitOptions(s, Formats.ASPECT_DEPTH));
        copyBufferToTexture(blit, staging.buffer, 0, (long) w * px, bytes, w, h, 1, dst, dst.is3D() ? 0 : dstLayer, level, dx, dy, dst.is3D() ? dz : 0, blitOptions(dst, Formats.ASPECT_DEPTH));
        gl.stream.defer(staging::destroy);
    }

    @Override protected void copyImageThroughBuffer(Tex src, int srcLevel, int sx, int sy, int sz, boolean srcLayered, Tex dst, int dstLevel, int dx, int dy, int dz, boolean dstLayered, int w, int h, int depth, int slices) {
        final long blit = gl.stream.blit();
        final long rowBytes = Formats.imageBytes(src.format, w, 1), imageBytes = Formats.imageBytes(src.format, w, h);
        final MtlBuffer staging = MtlBuffer.create(gl.gpu, imageBytes * depth * slices, MtlBuffer.USAGE_DEVICE);
        for (int i = 0; i < slices; i++) {
            final long at = imageBytes * depth * i;
            ObjC.copyTextureToBuffer(blit, COPY_T2B, src.image, srcLayered ? sz + i : 0, srcLevel, sx, sy, srcLayered ? 0 : sz + i, w, h, depth, staging.buffer, at, rowBytes, imageBytes, Mtl.BLIT_NONE);
            copyBufferToTexture(blit, staging.buffer, at, rowBytes, imageBytes, w, h, depth, dst, dstLayered ? dz + i : 0, dstLevel, dx, dy, dstLayered ? 0 : dz + i, Mtl.BLIT_NONE);
        }
        gl.stream.defer(staging::destroy);
    }

    @Override public boolean readImage(Tex t, int aspect, int level, int layer, int x, int y, int w, int h, int format, int type, PixelConvert.Store pack, long dst) {
        if (t == null || !t.hasImage() || w <= 0 || h <= 0) return false;
        gl.renderer.materialize(t);
        gl.renderer.endPass();
        final int texel = aspect == Formats.ASPECT_STENCIL ? 1 : t.format.isDepthOrStencil() ? (t.format.pf() == Mtl.PF_DEPTH16_UNORM ? 2 : 4) : t.format.texelBytes();
        final long bytes = (long) w * h * texel;
        final MtlBuffer staging = MtlBuffer.create(gl.gpu, bytes, MtlBuffer.USAGE_READBACK);
        try {
            ObjC.copyTextureToBuffer(gl.stream.blit(), COPY_T2B, t.image, t.is3D() ? 0 : layer, level, x, y, t.is3D() ? layer : 0, w, h, 1,
                staging.buffer, 0, (long) w * texel, bytes, blitOptions(t, aspect));
            t.lastUse = gl.stream.currentSerial();
            gl.flushAndWait(gl.stream.currentSerial());
            if (aspect == Formats.ASPECT_STENCIL) {
                copyStencilRows(staging.mapped, w, w, h, format, type, pack, dst);
                return true;
            }
            if (!PixelConvert.pack(t.format, staging.mapped, w, h, format, type, pack, dst)) return false;
            if (format == GL30.GL_DEPTH_STENCIL && t.format.isStencil()) mergeStencil(t, level, layer, x, y, w, h, type, pack, dst);
            return true;
        } finally {
            staging.destroy();
        }
    }

    @Override protected int storageViewFormat(Tex t, int wanted) {
        if (!Images.needsFormatView(t.format.pf(), wanted)) return wanted;
        if (t.id > 0) ensureUsage(t, Mtl.USAGE_PIXEL_FORMAT_VIEW);
        return (t.usage & Mtl.USAGE_PIXEL_FORMAT_VIEW) != 0 ? wanted : t.format.pf();
    }

    @Override protected long storageView(Tex t, int level, int layer, boolean layered, int format, boolean write) {
        return gl.images.storageView(t, level, layer, layered, format);
    }

    @Override protected Tex newTex(int id, boolean renderbuffer) {
        return new Tex(id, renderbuffer);
    }
}
