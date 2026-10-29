package com.gtnewhorizons.angelica.dx12.resource;

import com.gtnewhorizons.angelica.glemu.resource.Formats;

import com.gtnewhorizons.angelica.dx12.device.Com;
import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.dx12.device.Dxgi;
import com.gtnewhorizons.angelica.dx12.device.Gfx;
import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

public final class Images {
    private static final int DIM_TEX2D = 4, DIM_TEX2D_ARRAY = 5, DIM_TEX3D = 8, DIM_CUBE = 9, DIM_CUBE_ARRAY = 10;
    private static final int DSV_TEX2D = 3, DSV_TEX2D_ARRAY = 4;

    private final Dx12GL gl;
    private final MemorySegment desc = Arena.global().allocate(64, 8);
    private final MemorySegment clearColor = Arena.global().allocate(16, 4);

    public Images(Dx12GL gl) {
        this.gl = gl;
    }

    public boolean renderable(int fmt) {
        return !isCompressed(fmt) && fmt != Gfx.PF_RGB9E5_FLOAT && fmt != Gfx.PF_INVALID;
    }

    public boolean filterable(int fmt) {
        return !(isIntegerFormat(fmt) || fmt == Gfx.PF_STENCIL8 || fmt == Gfx.PF_X32_STENCIL8);
    }

    public boolean blendable(int fmt) {
        return !isIntegerFormat(fmt) && renderable(fmt);
    }

    public boolean sampleable(int fmt) {
        return fmt != Gfx.PF_INVALID && (!isCompressed(fmt) || gl.gpu.textureCompressionBC);
    }

    public static boolean needsFormatView(int base, int view) {
        if (base == view) return false;
        final int lo = Math.min(base, view), hi = Math.max(base, view);
        return !(hi == lo + 1 && (lo == Gfx.PF_RGBA8_UNORM || lo == Gfx.PF_BGRA8_UNORM || lo == Gfx.PF_BC1_RGBA || lo == Gfx.PF_BC2_RGBA || lo == Gfx.PF_BC3_RGBA || lo == Gfx.PF_BC7_RGBA_UNORM));
    }

    public static boolean isCompressed(int fmt) {
        return fmt >= Gfx.PF_BC1_RGBA && fmt <= Gfx.PF_BC7_RGBA_SRGB;
    }

    public static boolean isIntegerFormat(int fmt) {
        return switch (fmt) {
            case Gfx.PF_R8_UINT, Gfx.PF_R8_SINT, Gfx.PF_R16_UINT, Gfx.PF_R16_SINT, Gfx.PF_RG8_UINT, Gfx.PF_RG8_SINT, Gfx.PF_R32_UINT, Gfx.PF_R32_SINT,
                 Gfx.PF_RG16_UINT, Gfx.PF_RG16_SINT, Gfx.PF_RGBA8_UINT, Gfx.PF_RGBA8_SINT, Gfx.PF_RGB10A2_UINT, Gfx.PF_RG32_UINT, Gfx.PF_RG32_SINT,
                 Gfx.PF_RGBA16_UINT, Gfx.PF_RGBA16_SINT, Gfx.PF_RGBA32_UINT, Gfx.PF_RGBA32_SINT -> true;
            default -> false;
        };
    }

    public static int textureTypeFor(Tex t) {
        if (t.is3D()) return Gfx.TEX_3D;
        return switch (t.target) {
            case GL30.GL_TEXTURE_1D_ARRAY, GL30.GL_TEXTURE_2D_ARRAY -> Gfx.TEX_2D_ARRAY;
            case GL13.GL_TEXTURE_CUBE_MAP -> Gfx.TEX_CUBE;
            case GL40.GL_TEXTURE_CUBE_MAP_ARRAY -> Gfx.TEX_CUBE_ARRAY;
            default -> Gfx.TEX_2D;
        };
    }

    public static int viewTypeFor(Tex t) {
        return textureTypeFor(t);
    }

    public static int glTargetForViewType(int viewType) {
        return switch (viewType) {
            case Gfx.TEX_2D_ARRAY -> GL30.GL_TEXTURE_2D_ARRAY;
            case Gfx.TEX_3D -> org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
            case Gfx.TEX_CUBE -> GL13.GL_TEXTURE_CUBE_MAP;
            case Gfx.TEX_CUBE_ARRAY -> GL40.GL_TEXTURE_CUBE_MAP_ARRAY;
            default -> GL11.GL_TEXTURE_2D;
        };
    }

    public static int arraySize(Tex t) {
        final int type = textureTypeFor(t);
        return switch (type) {
            case Gfx.TEX_CUBE -> 6;
            case Gfx.TEX_CUBE_ARRAY -> Math.max(6, t.layers - t.layers % 6);
            case Gfx.TEX_2D_ARRAY -> Math.max(1, t.layers);
            default -> 1;
        };
    }

    public static int subresource(Tex t, int level, int slice, int plane) {
        final int levels = Math.max(1, t.levels);
        return level + slice * levels + plane * levels * arraySize(t);
    }

    public void create(Tex t, boolean storage) {
        final int fmt = t.format.pf();
        final int type = textureTypeFor(t);
        if (storage) t.extraUsage |= Gfx.USAGE_SHADER_WRITE;
        storage = (t.extraUsage & Gfx.USAGE_SHADER_WRITE) != 0;
        int usage = Gfx.USAGE_SHADER_READ | t.extraUsage;
        if (renderable(fmt)) usage |= Gfx.USAGE_RENDER_TARGET;
        if (storage && (fmt == Gfx.PF_R32_UINT || fmt == Gfx.PF_R32_SINT)) usage |= Gfx.USAGE_SHADER_ATOMIC;
        int flags = 0;
        if (Dxgi.isDepth(fmt)) flags |= Dx.RF_DEPTH_STENCIL;
        else if (renderable(fmt)) flags |= Dx.RF_RENDER_TARGET;
        if (storage && !Dxgi.isDepth(fmt) && !isCompressed(fmt)) flags |= Dx.RF_UNORDERED_ACCESS;
        try (Arena a = Arena.ofConfined()) {
            final MemorySegment props = a.allocate(20, 4);
            props.set(JAVA_INT, 0, Dx.HEAP_DEFAULT);
            props.set(JAVA_INT, 12, 1);
            props.set(JAVA_INT, 16, 1);
            final MemorySegment d = a.allocate(56, 8);
            final int width = Math.max(1, t.width);
            final int height = type == Gfx.TEX_CUBE || type == Gfx.TEX_CUBE_ARRAY ? width : Math.max(1, t.height);
            d.set(JAVA_INT, 0, type == Gfx.TEX_3D ? Dx.DIM_TEX3D : Dx.DIM_TEX2D);
            d.set(JAVA_LONG, 16, width);
            d.set(JAVA_INT, 24, height);
            d.set(JAVA_SHORT, 28, (short) (type == Gfx.TEX_3D ? Math.max(1, t.depth) : arraySize(t)));
            d.set(JAVA_SHORT, 30, (short) Math.max(1, t.levels));
            d.set(JAVA_INT, 32, Dxgi.resource(fmt));
            d.set(JAVA_INT, 36, 1);
            d.set(JAVA_INT, 44, 0);
            d.set(JAVA_INT, 48, flags);
            final MemorySegment out = a.allocate(8);
            final int hr = Com.hr(gl.gpu.device, Dx.DEV_CREATE_COMMITTED_RESOURCE, props.address(), 0, d.address(), Dx.STATE_COMMON, 0L, Dx.IID_RESOURCE, out.address());
            if (hr < 0) throw new OutOfMemoryError("Direct3D 12 could not create a " + t.width + "x" + t.height + "x" + t.depth + " texture of format " + fmt + ": 0x" + Integer.toHexString(hr));
            t.image = out.get(JAVA_LONG, 0);
        }
        t.state = Dx.STATE_COMMON;
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
        gl.descriptors.freeView(t.view);
        for (long v : extra) gl.descriptors.freeView(v);
        for (long v : t.storageViews.values()) gl.descriptors.freeView(v);
        final long image = t.image;
        gl.stream.defer(() -> Com.release(image));
        t.image = 0L;
        t.view = 0L;
        t.storageViews.clear();
        t.contentsUndefined = true;
        t.pendingClear = false;
        gl.renderer.onImageReleased(t);
    }

    private static int viewSlices(Tex t, int viewType) {
        final int total = textureTypeFor(t) == Gfx.TEX_3D ? 1 : arraySize(t);
        return switch (viewType) {
            case Gfx.TEX_2D, Gfx.TEX_3D -> 1;
            case Gfx.TEX_CUBE -> 6;
            case Gfx.TEX_CUBE_ARRAY -> Math.max(6, total - total % 6);
            default -> total;
        };
    }

    private static int mapChannel(int token) {
        return switch (token) {
            case Gfx.SWIZZLE_ZERO -> 4;
            case Gfx.SWIZZLE_ONE -> 5;
            case Gfx.SWIZZLE_RED -> 0;
            case Gfx.SWIZZLE_GREEN -> 1;
            case Gfx.SWIZZLE_BLUE -> 2;
            default -> 3;
        };
    }

    private static int component(int[] swz) {
        if (swz == null) return 0x1688;
        return mapChannel(swz[0]) | mapChannel(swz[1]) << 3 | mapChannel(swz[2]) << 6 | mapChannel(swz[3]) << 9 | 1 << 12;
    }

    private long srv(long resource, int dxgi, int viewType, int firstMip, int mips, int firstSlice, int slices, int plane, int[] swz) {
        final long handle = gl.descriptors.allocView();
        for (int i = 0; i < 12; i++) desc.set(JAVA_INT, i * 4L, 0);
        desc.set(JAVA_INT, 0, dxgi);
        desc.set(JAVA_INT, 8, component(swz));
        switch (viewType) {
            case Gfx.TEX_2D -> {
                desc.set(JAVA_INT, 4, DIM_TEX2D);
                desc.set(JAVA_INT, 16, firstMip);
                desc.set(JAVA_INT, 20, mips);
                desc.set(JAVA_INT, 24, plane);
            }
            case Gfx.TEX_3D -> {
                desc.set(JAVA_INT, 4, DIM_TEX3D);
                desc.set(JAVA_INT, 16, firstMip);
                desc.set(JAVA_INT, 20, mips);
            }
            case Gfx.TEX_CUBE -> {
                desc.set(JAVA_INT, 4, DIM_CUBE);
                desc.set(JAVA_INT, 16, firstMip);
                desc.set(JAVA_INT, 20, mips);
            }
            case Gfx.TEX_CUBE_ARRAY -> {
                desc.set(JAVA_INT, 4, DIM_CUBE_ARRAY);
                desc.set(JAVA_INT, 16, firstMip);
                desc.set(JAVA_INT, 20, mips);
                desc.set(JAVA_INT, 24, firstSlice);
                desc.set(JAVA_INT, 28, slices / 6);
            }
            default -> {
                desc.set(JAVA_INT, 4, DIM_TEX2D_ARRAY);
                desc.set(JAVA_INT, 16, firstMip);
                desc.set(JAVA_INT, 20, mips);
                desc.set(JAVA_INT, 24, firstSlice);
                desc.set(JAVA_INT, 28, slices);
                desc.set(JAVA_INT, 32, plane);
            }
        }
        Com.v(gl.gpu.device, Dx.DEV_CREATE_SRV, resource, desc.address(), handle);
        return handle;
    }

    public long createSampledView(Tex t, int viewType) {
        final Formats.Format f = t.format;
        final boolean stencil = f.isDepthOrStencil() && f.isStencil() && t.sampleStencil;
        final int dxgi = Dxgi.srv(f.pf(), stencil);
        final int levelBase = t.viewBaseLevel(), levelCount = t.viewLevelCount();
        final int slices = viewSlices(t, viewType);
        final int plane = stencil ? 1 : 0;
        if (f.isDepthOrStencil()) return srv(t.image, dxgi, viewType, levelBase, levelCount, 0, slices, plane, null);
        final int[] base = f.swizzle();
        final int[] swz = {swizzle(t.swizzle[0], base, 0), swizzle(t.swizzle[1], base, 1), swizzle(t.swizzle[2], base, 2), swizzle(t.swizzle[3], base, 3)};
        if (f.baseFormat() == GL11.GL_RGB && !f.isCompressed() && base == null && t.swizzle[3] == GL11.GL_ALPHA) swz[3] = Gfx.SWIZZLE_ONE;
        return srv(t.image, dxgi, viewType, levelBase, levelCount, 0, slices, 0, swz);
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
        if (requested == -1) return Gfx.SWIZZLE_ZERO;
        if (requested == -2) return Gfx.SWIZZLE_ONE;
        if (base != null) return base[requested];
        return Gfx.SWIZZLE_RED + requested;
    }

    public long sliceView(Tex t, int level, int slice, boolean stencil) {
        final int base = textureTypeFor(t);
        final int dxgi = Dxgi.srv(t.format.pf(), stencil && t.format.isStencil());
        final int plane = stencil && t.format.isStencil() ? 1 : 0;
        if (base == Gfx.TEX_3D) return srv(t.image, dxgi, Gfx.TEX_3D, level, 1, 0, 1, 0, null);
        return srv(t.image, dxgi, Gfx.TEX_2D_ARRAY, level, 1, slice, 1, plane, null);
    }

    long storageView(Tex t, int level, int layer, boolean layered, int fmt) {
        final int key = level | (layered ? 0xFF : layer) << 8 | (fmt & 0x3FF) << 16;
        final long cached = t.storageViews.get(key);
        if (cached != 0L) return cached;
        final int type = textureTypeFor(t);
        final long handle = gl.descriptors.allocView();
        for (int i = 0; i < 8; i++) desc.set(JAVA_INT, i * 4L, 0);
        desc.set(JAVA_INT, 0, Dxgi.view(fmt));
        if (type == Gfx.TEX_3D) {
            desc.set(JAVA_INT, 4, DIM_TEX3D);
            desc.set(JAVA_INT, 8, level);
            desc.set(JAVA_INT, 12, 0);
            desc.set(JAVA_INT, 16, -1);
        } else if (type == Gfx.TEX_2D) {
            desc.set(JAVA_INT, 4, DIM_TEX2D);
            desc.set(JAVA_INT, 8, level);
        } else {
            final int total = arraySize(t);
            desc.set(JAVA_INT, 4, DIM_TEX2D_ARRAY);
            desc.set(JAVA_INT, 8, level);
            desc.set(JAVA_INT, 12, layered ? 0 : layer);
            desc.set(JAVA_INT, 16, layered ? total : 1);
        }
        Com.v(gl.gpu.device, Dx.DEV_CREATE_UAV, t.image, 0L, desc.address(), handle);
        t.storageViews.put(key, handle);
        return handle;
    }

    public long rtv(Tex t, int level, int firstSlice, int slices) {
        final long handle = gl.descriptors.nextRtv();
        writeRtv(handle, t, level, firstSlice, slices);
        return handle;
    }

    public void writeRtv(long handle, Tex t, int level, int firstSlice, int slices) {
        for (int i = 0; i < 8; i++) desc.set(JAVA_INT, i * 4L, 0);
        desc.set(JAVA_INT, 0, Dxgi.view(t.format.pf()));
        final int type = textureTypeFor(t);
        if (type == Gfx.TEX_3D) {
            desc.set(JAVA_INT, 4, DIM_TEX3D);
            desc.set(JAVA_INT, 8, level);
            desc.set(JAVA_INT, 12, firstSlice);
            desc.set(JAVA_INT, 16, slices);
        } else if (type == Gfx.TEX_2D) {
            desc.set(JAVA_INT, 4, DIM_TEX2D);
            desc.set(JAVA_INT, 8, level);
        } else {
            desc.set(JAVA_INT, 4, DIM_TEX2D_ARRAY);
            desc.set(JAVA_INT, 8, level);
            desc.set(JAVA_INT, 12, firstSlice);
            desc.set(JAVA_INT, 16, slices);
        }
        Com.v(gl.gpu.device, Dx.DEV_CREATE_RTV, t.image, desc.address(), handle);
    }

    public long dsv(Tex t, int level, int firstSlice, int slices) {
        final long handle = gl.descriptors.nextDsv();
        for (int i = 0; i < 8; i++) desc.set(JAVA_INT, i * 4L, 0);
        desc.set(JAVA_INT, 0, Dxgi.dsv(t.format.pf()));
        final int type = textureTypeFor(t);
        if (type == Gfx.TEX_2D) {
            desc.set(JAVA_INT, 4, DSV_TEX2D);
            desc.set(JAVA_INT, 12, level);
        } else {
            desc.set(JAVA_INT, 4, DSV_TEX2D_ARRAY);
            desc.set(JAVA_INT, 12, level);
            desc.set(JAVA_INT, 16, firstSlice);
            desc.set(JAVA_INT, 20, slices);
        }
        Com.v(gl.gpu.device, Dx.DEV_CREATE_DSV, t.image, desc.address(), handle);
        return handle;
    }

    public void transition(Tex t, int state) {
        if (t.state == state) return;
        gl.stream.transition(t.image, (int) Dx.ALL_SUBRESOURCES, t.state, state);
        t.state = state;
    }

    public void transitionUpload(Tex t, int state) {
        if (t.state == state) return;
        gl.stream.uploadTransition(t.image, (int) Dx.ALL_SUBRESOURCES, t.state, state);
        t.state = state;
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
        final int slices = type == Gfx.TEX_3D ? 1 : arraySize(t);
        final boolean ds = t.format.isDepthOrStencil();
        transition(t, ds ? Dx.STATE_DEPTH_WRITE : Dx.STATE_RENDER_TARGET);
        for (int i = 0; i < 4; i++) clearColor.set(JAVA_FLOAT, i * 4L, color[i]);
        for (int level = 0; level < Math.max(1, t.levels); level++) {
            if (ds) {
                final long h = dsv(t, level, 0, slices);
                int flags = 0;
                if (t.format.isDepth()) flags |= 1;
                if (t.format.isStencil()) flags |= 2;
                Com.clearDsv(gl.stream.list(), h, flags, depth, stencil & 0xFF);
            } else {
                final long h = rtv(t, level, 0, type == Gfx.TEX_3D ? t.levelDepth(level) : slices);
                Com.v(gl.stream.list(), Dx.CL_CLEAR_RTV, h, clearColor.address(), 0, 0L);
            }
        }
        t.markWritten();
        t.needsClear = false;
        t.lastUse = gl.stream.currentSerial();
    }

    private void zeroFill(Tex t) {
        final int type = textureTypeFor(t);
        final int slices = type == Gfx.TEX_3D ? 1 : arraySize(t);
        long total = 0;
        for (int level = 0; level < Math.max(1, t.levels); level++) total = Math.max(total, Textures.alignedImageBytes(t.format, t.levelWidth(level), t.levelHeight(level), type == Gfx.TEX_3D ? t.levelDepth(level) : 1));
        if (total <= 0) return;
        gl.stagingRing.alloc(total, Textures.PLACEMENT_ALIGNMENT);
        org.lwjgl.system.MemoryUtil.memSet(gl.stagingRing.address, 0, total);
        final long buffer = gl.stagingRing.buffer, offset = gl.stagingRing.offset;
        final long list = gl.stream.list();
        for (int level = 0; level < Math.max(1, t.levels); level++) {
            final int w = t.levelWidth(level), h = t.levelHeight(level), d = type == Gfx.TEX_3D ? t.levelDepth(level) : 1;
            final long pitch = Textures.alignedRowPitch(t.format, w);
            for (int slice = 0; slice < slices; slice++) {
                gl.textures.copyBufferToTexture(list, buffer, offset, pitch, 0L, w, h, d, t, slice, level, 0, 0, 0, Gfx.BLIT_NONE);
            }
        }
        t.markWritten();
        t.needsClear = false;
        t.lastUse = gl.stream.currentSerial();
    }
}
