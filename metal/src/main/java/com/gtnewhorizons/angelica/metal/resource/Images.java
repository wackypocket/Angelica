package com.gtnewhorizons.angelica.metal.resource;

import com.gtnewhorizons.angelica.glemu.resource.Formats;

import com.gtnewhorizons.angelica.metal.device.Mtl;
import com.gtnewhorizons.angelica.metal.device.ObjC;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import static com.gtnewhorizons.angelica.metal.device.ObjC.call;
import static com.gtnewhorizons.angelica.metal.device.ObjC.send;
import static com.gtnewhorizons.angelica.metal.device.ObjC.sel;

public final class Images {
    private static final long SET_TYPE = sel("setTextureType:"), SET_FORMAT = sel("setPixelFormat:"), SET_WIDTH = sel("setWidth:"), SET_HEIGHT = sel("setHeight:");
    private static final long SET_DEPTH = sel("setDepth:"), SET_LEVELS = sel("setMipmapLevelCount:"), SET_ARRAY = sel("setArrayLength:"), SET_USAGE = sel("setUsage:");
    private static final long SET_STORAGE = sel("setStorageMode:"), NEW_TEXTURE = sel("newTextureWithDescriptor:");
    private static final long NEW_VIEW = sel("newTextureViewWithPixelFormat:textureType:levels:slices:swizzle:");
    private static final long NEW_VIEW_PLAIN = sel("newTextureViewWithPixelFormat:textureType:levels:slices:");
    private static final long COLOR_ATTACHMENTS = sel("colorAttachments"), OBJECT_AT = sel("objectAtIndexedSubscript:"), DEPTH_ATTACHMENT = sel("depthAttachment");
    private static final long STENCIL_ATTACHMENT = sel("stencilAttachment"), SET_TEXTURE = sel("setTexture:"), SET_LEVEL = sel("setLevel:"), SET_SLICE = sel("setSlice:");
    private static final long SET_DEPTH_PLANE = sel("setDepthPlane:"), SET_LOAD = sel("setLoadAction:"), SET_STORE = sel("setStoreAction:");
    private static final long SET_CLEAR_COLOR = sel("setClearColor:"), SET_CLEAR_DEPTH = sel("setClearDepth:"), SET_CLEAR_STENCIL = sel("setClearStencil:");
    private static final long SUPPORTS_FLOAT_FILTER = sel("supports32BitFloatFiltering");

    private final MetalGL gl;
    private final boolean float32Filtering;
    private final boolean textureAtomics = osMajor() >= 14;

    private static int osMajor() {
        try {
            return Integer.parseInt(System.getProperty("os.version", "0").split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public Images(MetalGL gl) {
        this.gl = gl;
        this.float32Filtering = ObjC.responds(gl.gpu.device, "supports32BitFloatFiltering") && ObjC.sendZ(gl.gpu.device, SUPPORTS_FLOAT_FILTER);
    }

    public boolean renderable(int fmt) {
        return !isCompressed(fmt) && fmt != Mtl.PF_RGB9E5_FLOAT && fmt != Mtl.PF_INVALID;
    }

    public boolean filterable(int fmt) {
        if (isIntegerFormat(fmt) || fmt == Mtl.PF_STENCIL8 || fmt == Mtl.PF_X32_STENCIL8) return false;
        if (fmt == Mtl.PF_R32_FLOAT || fmt == Mtl.PF_RG32_FLOAT || fmt == Mtl.PF_RGBA32_FLOAT || fmt == Mtl.PF_DEPTH32_FLOAT || fmt == Mtl.PF_DEPTH32_FLOAT_STENCIL8) {
            return float32Filtering || !gl.gpu.isApple();
        }
        return true;
    }

    public boolean blendable(int fmt) {
        return !isIntegerFormat(fmt) && renderable(fmt);
    }

    public boolean sampleable(int fmt) {
        return fmt != Mtl.PF_INVALID && (!isCompressed(fmt) || gl.gpu.textureCompressionBC);
    }

    public static boolean needsFormatView(int base, int view) {
        if (base == view) return false;
        final int lo = Math.min(base, view), hi = Math.max(base, view);
        return !(hi == lo + 1 && (lo == Mtl.PF_RGBA8_UNORM || lo == Mtl.PF_BGRA8_UNORM || lo == Mtl.PF_BC1_RGBA || lo == Mtl.PF_BC2_RGBA || lo == Mtl.PF_BC3_RGBA || lo == Mtl.PF_BC7_RGBA_UNORM));
    }

    public static boolean isCompressed(int fmt) {
        return fmt >= Mtl.PF_BC1_RGBA && fmt <= Mtl.PF_BC7_RGBA_SRGB;
    }

    public static boolean isIntegerFormat(int fmt) {
        return switch (fmt) {
            case Mtl.PF_R8_UINT, Mtl.PF_R8_SINT, Mtl.PF_R16_UINT, Mtl.PF_R16_SINT, Mtl.PF_RG8_UINT, Mtl.PF_RG8_SINT, Mtl.PF_R32_UINT, Mtl.PF_R32_SINT,
                 Mtl.PF_RG16_UINT, Mtl.PF_RG16_SINT, Mtl.PF_RGBA8_UINT, Mtl.PF_RGBA8_SINT, Mtl.PF_RGB10A2_UINT, Mtl.PF_RG32_UINT, Mtl.PF_RG32_SINT,
                 Mtl.PF_RGBA16_UINT, Mtl.PF_RGBA16_SINT, Mtl.PF_RGBA32_UINT, Mtl.PF_RGBA32_SINT -> true;
            default -> false;
        };
    }

    public static int textureTypeFor(Tex t) {
        if (t.is3D()) return Mtl.TEX_3D;
        return switch (t.target) {
            case GL30.GL_TEXTURE_1D_ARRAY, GL30.GL_TEXTURE_2D_ARRAY -> Mtl.TEX_2D_ARRAY;
            case GL13.GL_TEXTURE_CUBE_MAP -> Mtl.TEX_CUBE;
            case GL40.GL_TEXTURE_CUBE_MAP_ARRAY -> Mtl.TEX_CUBE_ARRAY;
            default -> Mtl.TEX_2D;
        };
    }

    public static int viewTypeFor(Tex t) {
        return textureTypeFor(t);
    }

    public static int glTargetForViewType(int viewType) {
        return switch (viewType) {
            case Mtl.TEX_2D_ARRAY -> GL30.GL_TEXTURE_2D_ARRAY;
            case Mtl.TEX_3D -> org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
            case Mtl.TEX_CUBE -> GL13.GL_TEXTURE_CUBE_MAP;
            case Mtl.TEX_CUBE_ARRAY -> GL40.GL_TEXTURE_CUBE_MAP_ARRAY;
            default -> GL11.GL_TEXTURE_2D;
        };
    }

    public void create(Tex t, boolean storage) {
        final int fmt = t.format.pf();
        final int type = textureTypeFor(t);
        if (storage) t.extraUsage |= Mtl.USAGE_SHADER_WRITE;
        storage = (t.extraUsage & Mtl.USAGE_SHADER_WRITE) != 0;
        int usage = Mtl.USAGE_SHADER_READ | t.extraUsage;
        if (renderable(fmt)) usage |= Mtl.USAGE_RENDER_TARGET;
        if (storage && textureAtomics && (fmt == Mtl.PF_R32_UINT || fmt == Mtl.PF_R32_SINT)) usage |= Mtl.USAGE_SHADER_ATOMIC;
        final long d = ObjC.create("MTLTextureDescriptor");
        try {
            call(d, SET_TYPE, type);
            call(d, SET_FORMAT, fmt);
            call(d, SET_WIDTH, Math.max(1, t.width));
            call(d, SET_HEIGHT, type == Mtl.TEX_CUBE || type == Mtl.TEX_CUBE_ARRAY ? Math.max(1, t.width) : Math.max(1, t.height));
            call(d, SET_DEPTH, type == Mtl.TEX_3D ? Math.max(1, t.depth) : 1);
            call(d, SET_LEVELS, Math.max(1, t.levels));
            final int slices = type == Mtl.TEX_CUBE ? 1 : type == Mtl.TEX_CUBE_ARRAY ? Math.max(1, t.layers / 6) : type == Mtl.TEX_2D_ARRAY ? Math.max(1, t.layers) : 1;
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
        if (t.label != null) gl.debug.name(t.image, t.label);
    }

    public void release(Tex t) {
        if (t.image == 0L) return;
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
        final int base = textureTypeFor(t);
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
        if (f.isDepthOrStencil() && f.isStencil() && t.sampleStencil) fmt = Mtl.PF_X32_STENCIL8;
        final int levelBase = t.viewBaseLevel(), levelCount = t.viewLevelCount();
        final int slices = viewSlices(t, viewType);
        if (f.isDepthOrStencil()) {
            return ObjC.newTextureView(t.image, NEW_VIEW_PLAIN, fmt, viewType, levelBase, levelCount, 0, slices, null);
        }
        final int[] base = f.swizzle();
        final int[] swz = {swizzle(t.swizzle[0], base, 0), swizzle(t.swizzle[1], base, 1), swizzle(t.swizzle[2], base, 2), swizzle(t.swizzle[3], base, 3)};
        if (f.baseFormat() == GL11.GL_RGB && !f.isCompressed() && base == null && t.swizzle[3] == GL11.GL_ALPHA) swz[3] = Mtl.SWIZZLE_ONE;
        return ObjC.newTextureView(t.image, NEW_VIEW, fmt, viewType, levelBase, levelCount, 0, slices, swz);
    }

    private static int swizzle(int glSwizzle, int[] base, int channel) {
        final int requested = switch (glSwizzle) {
            case GL11.GL_RED -> 0;
            case GL11.GL_GREEN -> 1;
            case GL11.GL_BLUE -> 2;
            case GL11.GL_ALPHA -> 3;
            case GL11.GL_ZERO -> -1;
            case GL11.GL_ONE -> -2;
            default -> channel;
        };
        if (requested == -1) return Mtl.SWIZZLE_ZERO;
        if (requested == -2) return Mtl.SWIZZLE_ONE;
        if (base != null) return base[requested];
        return Mtl.SWIZZLE_RED + requested;
    }

    public long sliceView(Tex t, int level, int slice, boolean stencil) {
        int fmt = t.format.pf();
        if (t.format.isDepth() && t.format.isStencil()) fmt = stencil ? Mtl.PF_X32_STENCIL8 : fmt;
        final int base = textureTypeFor(t);
        final int type = base == Mtl.TEX_3D ? Mtl.TEX_3D : Mtl.TEX_2D;
        return ObjC.newTextureView(t.image, NEW_VIEW_PLAIN, fmt, type, level, 1, base == Mtl.TEX_3D ? 0 : slice, 1, null);
    }

    long storageView(Tex t, int level, int layer, boolean layered, int fmt) {
        final int key = level | (layered ? 0xFF : layer) << 8 | (fmt & 0x3FF) << 16;
        final long cached = t.storageViews.get(key);
        if (cached != 0L) return cached;
        int type = textureTypeFor(t);
        int baseSlice = 0, count = viewSlices(t, type == Mtl.TEX_CUBE ? Mtl.TEX_2D_ARRAY : type);
        if (!layered && !t.is3D() && (t.layers > 1 || type == Mtl.TEX_CUBE)) {
            type = Mtl.TEX_2D;
            baseSlice = layer;
            count = 1;
        } else if (type == Mtl.TEX_CUBE || type == Mtl.TEX_CUBE_ARRAY) {
            type = Mtl.TEX_2D_ARRAY;
        }
        final long view = ObjC.newTextureView(t.image, NEW_VIEW_PLAIN, fmt, type, level, 1, baseSlice, count, null);
        t.storageViews.put(key, view);
        return view;
    }

    public void clearImage(Tex t, float[] color, float depth, int stencil) {
        if (!t.hasImage()) return;
        gl.renderer.releaseFromPass(t);
        gl.renderer.endPass();
        final int fmt = t.format.pf();
        if (!renderable(fmt)) {
            zeroFill(t);
            return;
        }
        final int type = textureTypeFor(t);
        final int slices = type == Mtl.TEX_CUBE ? 6 : type == Mtl.TEX_3D ? 1 : Math.max(1, type == Mtl.TEX_CUBE_ARRAY ? t.layers - t.layers % 6 : t.layers);
        final boolean integer = isIntegerFormat(fmt);
        for (int level = 0; level < Math.max(1, t.levels); level++) {
            final int planes = type == Mtl.TEX_3D ? t.levelDepth(level) : 1;
            for (int slice = 0; slice < slices; slice++) {
                for (int plane = 0; plane < planes; plane++) {
                    final long pass = ObjC.create("MTLRenderPassDescriptor");
                    try {
                        if (t.format.isDepthOrStencil()) {
                            if (t.format.isDepth()) attach(send(pass, DEPTH_ATTACHMENT), t.image, level, slice, plane);
                            if (t.format.isStencil()) attach(send(pass, STENCIL_ATTACHMENT), t.image, level, slice, plane);
                            if (t.format.isDepth()) ObjC.callD(send(pass, DEPTH_ATTACHMENT), SET_CLEAR_DEPTH, depth);
                            if (t.format.isStencil()) call(send(pass, STENCIL_ATTACHMENT), SET_CLEAR_STENCIL, stencil & 0xFF);
                        } else {
                            final long a = send(send(pass, COLOR_ATTACHMENTS), OBJECT_AT, 0);
                            attach(a, t.image, level, slice, plane);
                            if (integer) ObjC.callClearColor(a, SET_CLEAR_COLOR, (int) color[0], (int) color[1], (int) color[2], (int) color[3]);
                            else ObjC.callClearColor(a, SET_CLEAR_COLOR, color[0], color[1], color[2], color[3]);
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
        t.lastUse = gl.stream.currentSerial();
    }

    private static void attach(long attachment, long texture, int level, int slice, int plane) {
        call(attachment, SET_TEXTURE, texture);
        call(attachment, SET_LEVEL, level);
        call(attachment, SET_SLICE, slice);
        call(attachment, SET_DEPTH_PLANE, plane);
        call(attachment, SET_LOAD, Mtl.LOAD_CLEAR);
        call(attachment, SET_STORE, Mtl.STORE_STORE);
    }

    private void zeroFill(Tex t) {
        long bytes = 0;
        for (int level = 0; level < Math.max(1, t.levels); level++) bytes = Math.max(bytes, levelBytes(t, level));
        if (bytes <= 0) return;
        final long zero = bytes;
        gl.stagingRing.alloc(zero, 16);
        org.lwjgl.system.MemoryUtil.memSet(gl.stagingRing.address, 0, zero);
        final long buffer = gl.stagingRing.buffer, offset = gl.stagingRing.offset;
        final long blit = gl.stream.blit();
        final int type = textureTypeFor(t);
        final int slices = type == Mtl.TEX_CUBE ? 6 : type == Mtl.TEX_3D ? 1 : Math.max(1, t.layers);
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
        t.lastUse = gl.stream.currentSerial();
    }

    static long rowBytes(Formats.Format f, int w) {
        if (f.isCompressed()) return (long) ((w + 3) / 4) * f.blockBytes();
        return (long) w * Formats.texelBytesOf(f.pf());
    }

    private static long levelBytes(Tex t, int level) {
        final int h = t.levelHeight(level);
        final long rows = t.format.isCompressed() ? (h + 3) / 4 : h;
        return rowBytes(t.format, t.levelWidth(level)) * rows * (t.is3D() ? t.levelDepth(level) : 1);
    }
}
