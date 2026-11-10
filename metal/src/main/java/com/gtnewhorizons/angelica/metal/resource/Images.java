package com.gtnewhorizons.angelica.metal.resource;

import com.gtnewhorizons.angelica.glemu.resource.PixelFormats;
import org.lwjgl.opengl.GL11;
import com.gtnewhorizons.angelica.glemu.gl.TokenMaps;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.TokenImagesBase;
import com.gtnewhorizons.angelica.metal.device.Gpu;
import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;

public final class Images extends TokenImagesBase<Tex> {
    private static final long SET_TYPE = sel("setTextureType:"), SET_FORMAT = sel("setPixelFormat:"), SET_WIDTH = sel("setWidth:"), SET_HEIGHT = sel("setHeight:");
    private static final long SET_DEPTH = sel("setDepth:"), SET_LEVELS = sel("setMipmapLevelCount:"), SET_ARRAY = sel("setArrayLength:"), SET_USAGE = sel("setUsage:");
    private static final long SET_STORAGE = sel("setStorageMode:"), NEW_TEXTURE = sel("newTextureWithDescriptor:");
    private static final long SET_SAMPLE_COUNT = sel("setSampleCount:");
    private static final long NEW_VIEW = sel("newTextureViewWithPixelFormat:textureType:levels:slices:swizzle:");
    private static final long NEW_VIEW_PLAIN = sel("newTextureViewWithPixelFormat:textureType:levels:slices:");
    private static final long COLOR_ATTACHMENTS = sel("colorAttachments"), OBJECT_AT = sel("objectAtIndexedSubscript:"), DEPTH_ATTACHMENT = sel("depthAttachment");
    private static final long STENCIL_ATTACHMENT = sel("stencilAttachment"), SET_TEXTURE = sel("setTexture:"), SET_LEVEL = sel("setLevel:"), SET_SLICE = sel("setSlice:");
    private static final long SET_DEPTH_PLANE = sel("setDepthPlane:"), SET_LOAD = sel("setLoadAction:"), SET_STORE = sel("setStoreAction:");
    private static final long SET_CLEAR_COLOR = sel("setClearColor:"), SET_CLEAR_DEPTH = sel("setClearDepth:"), SET_CLEAR_STENCIL = sel("setClearStencil:");
    private static final long SUPPORTS_FLOAT_FILTER = sel("supports32BitFloatFiltering");

    private final MetalGL gl;
    private final boolean float32Filtering;
    private final boolean textureAtomics = Gpu.isMacOs14OrLater();

    public Images(MetalGL gl) {
        this.gl = gl;
        this.float32Filtering = ObjC.responds(gl.gpu.device, "supports32BitFloatFiltering") && ObjC.sendZ(gl.gpu.device, SUPPORTS_FLOAT_FILTER);
    }

    @Override public boolean filterable(int fmt) {
        if (TokenMaps.isIntegerFormat(fmt) || fmt == Mtl.PF_STENCIL8 || fmt == Mtl.PF_X32_STENCIL8) return false;
        if (fmt == Mtl.PF_R32_FLOAT || fmt == Mtl.PF_RG32_FLOAT || fmt == Mtl.PF_RGBA32_FLOAT || fmt == Mtl.PF_DEPTH32_FLOAT || fmt == Mtl.PF_DEPTH32_FLOAT_STENCIL8) {
            return float32Filtering || !gl.gpu.isApple();
        }
        return true;
    }

    @Override public boolean sampleable(int fmt) {
        if (fmt >= PixelFormats.PF_EAC_R11_UNORM && fmt <= PixelFormats.PF_ETC2_RGB8A1_SRGB) return gl.gpu.isApple();
        return fmt != Mtl.PF_INVALID && (!isCompressed(fmt) || gl.gpu.textureCompressionBC);
    }

    public static boolean needsFormatView(int base, int view) {
        if (base == view) return false;
        final int lo = Math.min(base, view), hi = Math.max(base, view);
        return !(hi == lo + 1 && (lo == Mtl.PF_RGBA8_UNORM || lo == Mtl.PF_BGRA8_UNORM || lo == Mtl.PF_BC1_RGBA || lo == Mtl.PF_BC2_RGBA || lo == Mtl.PF_BC3_RGBA || lo == Mtl.PF_BC7_RGBA_UNORM));
    }

    @Override public void create(Tex t, boolean storage) {
        generation++;
        final int fmt = t.format.pf();
        final int type = TokenMaps.textureTypeFor(t);
        if (storage) t.extraUsage |= Mtl.USAGE_SHADER_WRITE;
        storage = (t.extraUsage & Mtl.USAGE_SHADER_WRITE) != 0;
        int usage = Mtl.USAGE_SHADER_READ | t.extraUsage;
        if (renderable(fmt)) usage |= Mtl.USAGE_RENDER_TARGET;
        if (renderable(fmt) && Formats.linearOf(fmt) != fmt) usage |= Mtl.USAGE_PIXEL_FORMAT_VIEW;
        // View usage keeps these out of lossless compression, which rewrites their texels into another form of the same value and so breaks copies between formats.
        if (fmt == PixelFormats.PF_RGB9E5_FLOAT || fmt == PixelFormats.PF_RG11B10_FLOAT) usage |= Mtl.USAGE_PIXEL_FORMAT_VIEW;
        // A multisampled depth-stencil texture's stencil is read through a view; without this usage the image would be replaced at the first read.
        if (t.samples > 1 && !t.renderbuffer && t.format.isDepth() && t.format.isStencil()) usage |= Mtl.USAGE_PIXEL_FORMAT_VIEW;
        if (storage && textureAtomics && (fmt == Mtl.PF_R32_UINT || fmt == Mtl.PF_R32_SINT)) usage |= Mtl.USAGE_SHADER_ATOMIC;
        final long d = ObjC.create("MTLTextureDescriptor");
        try {
            call(d, SET_TYPE, type);
            call(d, SET_FORMAT, fmt);
            call(d, SET_WIDTH, Math.max(1, t.width));
            call(d, SET_HEIGHT, type == Mtl.TEX_CUBE || type == Mtl.TEX_CUBE_ARRAY ? Math.max(1, t.width) : Math.max(1, t.height));
            call(d, SET_DEPTH, type == Mtl.TEX_3D ? Math.max(1, t.depth) : 1);
            call(d, SET_LEVELS, Math.max(1, t.levels));
            call(d, SET_SAMPLE_COUNT, Math.max(1, t.samples));
            final int slices = type == Mtl.TEX_CUBE ? 1 : type == Mtl.TEX_CUBE_ARRAY ? Math.max(1, t.layers / 6) : type == Mtl.TEX_2D_ARRAY || type == Mtl.TEX_2D_MS_ARRAY ? Math.max(1, t.layers) : 1;
            call(d, SET_ARRAY, slices);
            call(d, SET_USAGE, usage);
            call(d, SET_STORAGE, Mtl.STORAGE_PRIVATE);
            t.image = send(gl.gpu.device, NEW_TEXTURE, d);
        } finally {
            ObjC.release(d);
        }
        if (t.image == 0L) throw new OutOfMemoryError("Metal could not create a " + t.width + "x" + t.height + "x" + t.depth + " texture of format " + fmt);
        t.usage = usage;
        t.contentsUndefined = true;
        t.needsClear = renderable(fmt);
        t.view = 0L;
        t.viewDirty = true;
        t.storageViews.clear();
        t.samplerDirty = true;
        gl.debug.name(t.image, t.label);
    }

    @Override public void release(Tex t) {
        if (t.image == 0L) return;
        generation++;
        gl.renderer.releaseFromPass(t);
        final long[] extra = gl.renderer.forgetViews(t);
        ObjC.release(t.view);
        for (long v : extra) ObjC.release(v);
        for (long v : t.storageViews.values()) ObjC.release(v);
        ObjC.release(t.image);
        t.image = 0L;
        t.view = 0L;
        t.storageViews.clear();
        t.contentsUndefined = true;
        t.pendingClear = false;
        gl.renderer.onImageReleased(t);
    }

    private static int viewSlices(Tex t, int viewType) {
        final int base = TokenMaps.textureTypeFor(t);
        final int total = base == Mtl.TEX_CUBE ? 6 : base == Mtl.TEX_3D ? 1 : Math.max(1, t.layers);
        return switch (viewType) {
            case Mtl.TEX_2D, Mtl.TEX_3D -> 1;
            case Mtl.TEX_CUBE -> 6;
            case Mtl.TEX_CUBE_ARRAY -> Math.max(6, total - total % 6);
            default -> total;
        };
    }

    public long createSampledView(Tex t, int viewType) {
        final Formats.Format f = t.format;
        int fmt = f.pf();
        if (f.isDepth() && f.isStencil() && t.samplesStencil()) fmt = Mtl.PF_X32_STENCIL8;
        final int levelBase = t.viewBaseLevel(), levelCount = t.viewLevelCount();
        final int slices = viewSlices(t, viewType);
        if (f.isDepthOrStencil()) {
            return ObjC.newTextureView(t.image, NEW_VIEW, fmt, viewType, levelBase, levelCount, 0, slices, TokenMaps.depthViewSwizzle(t.swizzle));
        }
        final int[] swz = TokenMaps.viewSwizzle(f, t.swizzle);
        return ObjC.newTextureView(t.image, NEW_VIEW, fmt, viewType, levelBase, levelCount, 0, slices, swz);
    }

    public long sliceView(Tex t, int level, int slice) {
        return ObjC.newTextureView(t.image, NEW_VIEW_PLAIN, t.format.pf(), Mtl.TEX_2D, level, 1, slice, 1, null);
    }

    /** The texture a pass attaches: while GL_FRAMEBUFFER_SRGB is off an sRGB format goes through a view without the encoding. */
    public long attachmentTexture(Tex t, boolean srgbWrites) {
        final int linear = Formats.linearOf(t.format.pf());
        if (srgbWrites || linear == t.format.pf()) return t.image;
        final long key = 1L << 63;
        final long cached = t.storageViews.get(key);
        if (cached != 0L) return cached;
        final int type = TokenMaps.textureTypeFor(t);
        final long view = ObjC.newTextureView(t.image, NEW_VIEW_PLAIN, linear, type, 0, Math.max(1, t.levels), 0, type == Mtl.TEX_3D ? 1 : Math.max(1, t.layers), null);
        t.storageViews.put(key, view);
        return view;
    }

    long storageView(Tex t, int level, int layer, boolean layered, int fmt) {
        final long key = level | (long) layer << 8 | (long) (fmt & 0x3FFFFFFF) << 32 | (layered ? 1L << 62 : 0L);
        final long cached = t.storageViews.get(key);
        if (cached != 0L) return cached;
        int type = TokenMaps.textureTypeFor(t);
        int baseSlice = 0, count = viewSlices(t, type);
        // Shaders here hold cube images as arrays of faces.
        if (type == Mtl.TEX_CUBE || type == Mtl.TEX_CUBE_ARRAY) type = Mtl.TEX_2D_ARRAY;
        if (!layered && t.is3D() && layer != 0) gl.warnOnce("Metal cannot bind one slice of a 3D texture as an image; binding the whole texture");
        if (!layered && !t.is3D() && (t.layers > 1 || type == Mtl.TEX_2D_ARRAY)) {
            type = Mtl.TEX_2D;
            baseSlice = layer;
            count = 1;
        }
        final long view = ObjC.newTextureView(t.image, NEW_VIEW_PLAIN, fmt, type, level, 1, baseSlice, count, null);
        t.storageViews.put(key, view);
        return view;
    }

    @Override public void clearImage(Tex t, float[] color, float depth, int stencil, int aspects) {
        // Metal has no clear command: renderable formats clear through an empty pass per level, slice and plane, others are zero-filled by blit.
        if (!t.hasImage()) return;
        gl.renderer.endPass();
        final int fmt = t.format.pf();
        if (!renderable(fmt)) {
            zeroFill(t);
            return;
        }
        final int type = TokenMaps.textureTypeFor(t);
        final int slices = TokenMaps.sliceCount(t);
        final boolean integer = TokenMaps.isIntegerFormat(fmt);
        for (int level = 0; level < Math.max(1, t.levels); level++) {
            final int planes = type == Mtl.TEX_3D ? t.levelDepth(level) : 1;
            for (int slice = 0; slice < slices; slice++) {
                for (int plane = 0; plane < planes; plane++) {
                    final long pass = ObjC.create("MTLRenderPassDescriptor");
                    try {
                        if (t.format.isDepthOrStencil()) {
                            final boolean clearDepth = (aspects & Formats.ASPECT_DEPTH) != 0, clearStencil = (aspects & Formats.ASPECT_STENCIL) != 0;
                            if (clearDepth) attach(send(pass, DEPTH_ATTACHMENT), t.image, level, slice, plane, Mtl.LOAD_CLEAR);
                            if (clearStencil) attach(send(pass, STENCIL_ATTACHMENT), t.image, level, slice, plane, Mtl.LOAD_CLEAR);
                            if (clearDepth) ObjC.callD(send(pass, DEPTH_ATTACHMENT), SET_CLEAR_DEPTH, depth);
                            if (clearStencil) call(send(pass, STENCIL_ATTACHMENT), SET_CLEAR_STENCIL, stencil & 0xFF);
                        } else {
                            final long a = send(send(pass, COLOR_ATTACHMENTS), OBJECT_AT, 0);
                            attach(a, t.image, level, slice, plane, Mtl.LOAD_CLEAR);
                            setClearColor(a, integer, color);
                        }
                        gl.stream.render(pass);
                        gl.stream.endEncoder();
                    } finally {
                        ObjC.release(pass);
                    }
                }
            }
        }
        t.markWritten();
        t.needsClear = false;
        t.markGpuWrite(gl.stream.currentSerial());
    }

    public static void setClearColor(long attachment, boolean integer, float[] c) {
        if (integer) ObjC.callClearColor(attachment, SET_CLEAR_COLOR, (int) c[0], (int) c[1], (int) c[2], (int) c[3]);
        else ObjC.callClearColor(attachment, SET_CLEAR_COLOR, c[0], c[1], c[2], c[3]);
    }

    public static void attach(long attachment, long texture, int level, int slice, int plane, int load) {
        call(attachment, SET_TEXTURE, texture);
        call(attachment, SET_LEVEL, level);
        call(attachment, SET_SLICE, slice);
        call(attachment, SET_DEPTH_PLANE, plane);
        call(attachment, SET_LOAD, load);
        call(attachment, SET_STORE, Mtl.STORE_STORE);
    }

    private void zeroFill(Tex t) {
        long bytes = 0;
        for (int level = 0; level < Math.max(1, t.levels); level++) bytes = Math.max(bytes, levelBytes(t, level));
        if (bytes <= 0) return;
        gl.stagingRing.alloc(bytes, 16);
        MemoryUtil.memSet(gl.stagingRing.address, 0, bytes);
        final long buffer = gl.stagingRing.buffer, offset = gl.stagingRing.offset;
        final long blit = gl.stream.blit();
        final int type = TokenMaps.textureTypeFor(t);
        final int slices = TokenMaps.sliceCount(t);
        for (int level = 0; level < Math.max(1, t.levels); level++) {
            final int w = t.levelWidth(level), h = t.levelHeight(level), d = type == Mtl.TEX_3D ? t.levelDepth(level) : 1;
            final long rowBytes = rowBytes(t.format, w);
            final long rows = t.format.isCompressed() ? (h + 3) / 4 : h;
            for (int slice = 0; slice < slices; slice++) {
                gl.textures.copyBufferToTexture(blit, buffer, offset, rowBytes, rowBytes * rows, w, h, d, t, slice, level, 0, 0, 0, Mtl.BLIT_NONE);
            }
        }
        t.markWritten();
        t.needsClear = false;
        t.markGpuWrite(gl.stream.currentSerial());
    }

    static long rowBytes(Formats.Format f, int w) {
        return f.isCompressed() ? (long) ((w + 3) / 4) * f.blockBytes() : (long) w * f.texelBytes();
    }

    private static long levelBytes(Tex t, int level) {
        final int h = t.levelHeight(level);
        final long rows = t.format.isCompressed() ? (h + 3) / 4 : h;
        return rowBytes(t.format, t.levelWidth(level)) * rows * (t.is3D() ? t.levelDepth(level) : 1);
    }
}
