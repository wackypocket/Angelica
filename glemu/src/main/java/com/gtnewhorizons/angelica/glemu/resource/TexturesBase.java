package com.gtnewhorizons.angelica.glemu.resource;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.util.IdTable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public abstract class TexturesBase<T extends TexBase> {
    protected final IdTable<T> textures = new IdTable<>();
    protected final IdTable<T> renderbuffers = new IdTable<>();
    protected int nextTexId = 1, nextRboId = 1, nextInternalId = -1000;
    protected int depth24Stencil8, depth24;

    protected abstract T newTex(int id, boolean renderbuffer);

    protected abstract void warnOnce(String message);

    protected abstract int getInteger(int pname);

    protected abstract void createImage(T t);

    protected abstract void releaseImage(T t);

    protected abstract void onImageReleased(T t);

    protected abstract void clearImageHandles(T t);

    protected abstract T snapshot(T t);

    protected abstract void copyLevels(T from, T to);

    protected abstract void targetsChanged();

    protected abstract void onTextureDeleted(int id, boolean renderbuffer);

    protected abstract boolean sampleable(Formats.Format f);

    protected abstract BufBase unpackBuffer(int id);

    protected abstract void readBuffer(BufBase buffer, long offset, long size, long dst);

    protected abstract boolean isIdle(long serial);

    protected abstract void flushAndWait(long serial);

    protected abstract void uploadFrom(GlState st, T t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int format, int type, long src);

    public abstract boolean readImage(T t, int aspect, int level, int layer, int x, int y, int w, int h, int format, int type, PixelConvert.Store pack, long dst);

    public abstract void copyFromFramebuffer(GlState st, T dst, int target, int level, int dx, int dy, int dz, int sx, int sy, int w, int h);

    protected abstract boolean canGenerateMips(T t);

    protected abstract void prepareForMipGeneration(T t);

    protected abstract void generateMips(T t);

    protected abstract void mipsGenerated(T t);

    public void specify(T t, Formats.Format f, int level, int w, int h, int d, int layers) {
        specify(t, f, level, w, h, d, layers, false);
    }

    protected void specify(T t, Formats.Format f, int level, int w, int h, int d, int layers, boolean grow) {
        final int baseW = Math.max(1, w << level), baseH = t.target == GL11.GL_TEXTURE_1D || t.target == GL30.GL_TEXTURE_1D_ARRAY ? Math.max(1, h) : Math.max(1, h << level);
        final int baseD = t.is3D() ? Math.max(1, d << level) : 1;
        final boolean sameShape = t.hasImage() && t.format.pf() == f.pf() && t.format.glInternal() == f.glInternal() && t.width == baseW && t.height == baseH && t.depth == baseD && t.layers == layers;
        final int levels = t.levelsToAllocate(level, sameShape, baseW, baseH, baseD);
        if (sameShape && level < t.levels && !(grow && levels > t.levels)) {
            t.definedLevels |= 1 << level;
            return;
        }
        final T old = sameShape ? snapshot(t) : null;
        if (!sameShape) {
            releaseImage(t);
            t.definedLevels = 0;
        } else {
            clearImageHandles(t);
            onImageReleased(t);
        }
        t.format = f;
        t.width = baseW;
        t.height = baseH;
        t.depth = baseD;
        t.layers = layers;
        t.levels = levels;
        createImage(t);
        if (old != null) copyLevels(old, t);
        t.definedLevels |= 1 << level;
        targetsChanged();
    }

    public void generateMipmap(T t) {
        if (t == null || !t.hasImage()) return;
        final int want = t.wantedMipLevels();
        if (t.levels < want && !t.immutable) {
            final int keepMax = t.maxLevel;
            t.maxLevel = want - 1;
            specify(t, t.format, 0, t.width, t.height, t.depth, t.layers, true);
            t.maxLevel = keepMax;
        }
        if (t.levels <= 1) return;
        if (!canGenerateMips(t)) return;
        prepareForMipGeneration(t);
        generateMips(t);
        t.definedLevels = (1 << t.levels) - 1;
        mipsGenerated(t);
    }

    public T attachment(int id, boolean rbo) {
        return rbo ? renderbuffers.get(id) : textures.get(id);
    }

    public static int canonicalTarget(int target) {
        if (target >= GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z) return GL13.GL_TEXTURE_CUBE_MAP;
        if (target == GL13.GL_PROXY_TEXTURE_CUBE_MAP) return GL13.GL_TEXTURE_CUBE_MAP;
        return switch (target) {
            case GL31.GL_TEXTURE_RECTANGLE -> GL11.GL_TEXTURE_2D;
            default -> target;
        };
    }

    public void compressedTexImage(GlState st, int target, int level, int internalFormat, int w, int h, int d, long data, int size) {
        final T t = getOrCreate(st.boundTexture(target), target);
        if (t == null || w <= 0 || h <= 0) return;
        final Formats.Format f = format(internalFormat);
        specify(t, f, level, w, h, Math.max(1, d), t.isCube() ? 6 : 1);
        if (data != 0L) upload(st, t, level, 0, 0, 0, w, h, 1, cubeFace(target), 1, 0, 0, data, false);
    }

    public static int cubeFace(int target) {
        return target >= GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z ? target - GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X : 0;
    }

    public void delete(int id) {
        final T t = textures.remove(id);
        if (t == null) return;
        releaseImage(t);
        onTextureDeleted(id, false);
    }

    public void deleteRenderbuffer(int id) {
        final T t = renderbuffers.remove(id);
        if (t == null) return;
        releaseImage(t);
        onTextureDeleted(id, true);
    }

    protected static boolean depthIsLayers(int target) {
        return target == GL30.GL_PROXY_TEXTURE_2D_ARRAY || target == GL40.GL_PROXY_TEXTURE_CUBE_MAP_ARRAY || target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE_ARRAY;
    }

    public void destroy() {
        for (T t : textures.values()) releaseImage(t);
        for (T t : renderbuffers.values()) releaseImage(t);
        textures.clear();
        renderbuffers.clear();
    }

    public boolean exists(int id) {
        return textures.containsKey(id);
    }

    public int gen() {
        int id = nextTexId++;
        while (textures.containsKey(id)) id = nextTexId++;
        textures.put(id, newTex(id, false));
        return id;
    }

    public int genRenderbuffer() {
        int id = nextRboId++;
        while (renderbuffers.containsKey(id)) id = nextRboId++;
        renderbuffers.put(id, newTex(id, true));
        return id;
    }

    public T get(int id) {
        return id == 0 ? null : textures.get(id);
    }

    public T getOrCreate(int id, int target) {
        if (id == 0) return null;
        T t = textures.get(id);
        if (t == null) {
            t = newTex(id, false);
            textures.put(id, t);
            if (id >= nextTexId) nextTexId = id + 1;
        }
        if (t.target == 0 && target != 0) t.target = canonicalTarget(target);
        return t;
    }

    protected static boolean heightIsLayers(int target) {
        return target == GL30.GL_PROXY_TEXTURE_1D_ARRAY;
    }

    public T internalTexture(int target, Formats.Format f, int w, int h, int d, int layers, String label) {
        final T t = newTex(nextInternalId--, false);
        t.target = target;
        t.format = f;
        t.width = w;
        t.height = h;
        t.depth = d;
        t.layers = layers;
        t.levels = 1;
        t.label = label;
        t.minFilter = GL11.GL_NEAREST;
        createImage(t);
        return t;
    }

    public static boolean isProxy(int target) {
        return proxySlot(target) >= 0;
    }

    public static boolean isTextureTarget(int target) {
        return GlState.targetIndex(target) >= 0 || target == GL14.GL_TEXTURE_FILTER_CONTROL || target == GL42.GL_TEXTURE_IMMUTABLE_FORMAT;
    }

    public static long levelZeroSize(int size, int level) {
        if (size <= 0 || level < 0 || level > 30) return -1;
        final long base = (long) size << level;
        return base > Integer.MAX_VALUE ? -1 : base;
    }

    public static int proxyParam(GlState st, int target, int level, int pname) {
        final int slot = proxySlot(target);
        final int[] p = slot < 0 ? null : st.proxies[slot];
        if (p == null || level < 0 || level > 30) return 0;
        return switch (pname) {
            case GL11.GL_TEXTURE_WIDTH -> Math.max(1, p[0] >> level);
            case GL11.GL_TEXTURE_HEIGHT -> heightIsLayers(target) ? p[1] : Math.max(1, p[1] >> level);
            case GL12.GL_TEXTURE_DEPTH -> depthIsLayers(target) ? p[2] : target == GL12.GL_PROXY_TEXTURE_3D ? Math.max(1, p[2] >> level) : p[2];
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> p[3];
            default -> 0;
        };
    }

    public static int proxySlot(int target) {
        return switch (target) {
            case GL11.GL_PROXY_TEXTURE_1D -> 0;
            case GL11.GL_PROXY_TEXTURE_2D -> 1;
            case GL12.GL_PROXY_TEXTURE_3D -> 2;
            case GL13.GL_PROXY_TEXTURE_CUBE_MAP -> 3;
            case GL30.GL_PROXY_TEXTURE_1D_ARRAY -> 4;
            case GL30.GL_PROXY_TEXTURE_2D_ARRAY -> 5;
            case GL31.GL_PROXY_TEXTURE_RECTANGLE -> 6;
            case GL40.GL_PROXY_TEXTURE_CUBE_MAP_ARRAY -> 7;
            case GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE -> 8;
            case GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE_ARRAY -> 9;
            default -> -1;
        };
    }

    public T renderbuffer(int id) {
        if (id == 0) return null;
        T t = renderbuffers.get(id);
        if (t == null) {
            t = newTex(id, true);
            renderbuffers.put(id, t);
            if (id >= nextRboId) nextRboId = id + 1;
        }
        t.target = GL11.GL_TEXTURE_2D;
        return t;
    }

    public boolean renderbufferExists(int id) {
        return renderbuffers.containsKey(id);
    }

    public void storage(T t, int levels, int internalFormat, int w, int h, int d) {
        final Formats.Format f = format(internalFormat);
        releaseImage(t);
        t.format = f;
        t.width = Math.max(1, w);
        t.height = t.target == GL30.GL_TEXTURE_1D_ARRAY ? 1 : Math.max(1, h);
        t.depth = t.is3D() ? Math.max(1, d) : 1;
        t.layers = t.isCube() ? 6 * (t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY ? Math.max(1, d / 6) : 1)
            : (t.target == GL30.GL_TEXTURE_2D_ARRAY ? Math.max(1, d) : t.target == GL30.GL_TEXTURE_1D_ARRAY ? Math.max(1, h) : 1);
        t.levels = Math.max(1, Math.min(levels, TexBase.fullChain(t.width, t.height, t.depth)));
        t.immutable = true;
        t.definedLevels = (1 << t.levels) - 1;
        createImage(t);
        targetsChanged();
    }

    public void subImage(GlState st, T t, int target, int level, int x, int y, int z, int w, int h, int d, int format, int type, long pixels, boolean fromPbo) {
        if (w <= 0 || h <= 0 || d <= 0 || level >= t.levels) return;
        if (pixels == 0L && !fromPbo) return;
        int layer = 0, layerCount = 1, zOff = 0, depth = 1;
        if (t.isCube() && t.target == GL13.GL_TEXTURE_CUBE_MAP) {
            layer = cubeFace(target);
        } else if (t.target == GL30.GL_TEXTURE_2D_ARRAY || t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY) {
            layer = z;
            layerCount = d;
        } else if (t.target == GL30.GL_TEXTURE_1D_ARRAY) {
            layer = y;
            layerCount = h;
            y = 0;
            h = 1;
        } else if (t.is3D()) {
            zOff = z;
            depth = d;
        }
        upload(st, t, level, x, y, zOff, w, h, depth, layer, layerCount, format, type, pixels, fromPbo);
    }

    public void texImage(GlState st, int target, int level, int internalFormat, int w, int h, int d, int format, int type, long pixels, boolean fromPbo) {
        if (isProxy(target)) {
            proxy(st, target, level, internalFormat, w, h, d);
            return;
        }
        final T t = getOrCreate(st.boundTexture(target), target);
        if (t == null) return;
        if (t.immutable) {
            warnOnce("glTexImage on an immutable texture " + t.id);
            return;
        }
        if (w <= 0 || h <= 0 || d <= 0) {
            if (level == 0 && t.hasImage()) {
                releaseImage(t);
                t.width = t.height = 0;
                t.levels = 0;
                t.definedLevels = 0;
                t.viewDirty = true;
            }
            return;
        }
        int internal = internalFormat;
        if (internal == format && (internal == GL11.GL_RGBA || internal == GL11.GL_RGB || internal == GL11.GL_RED || internal == GL30.GL_RG)) {
            internal = FormatUtil.sizedFor(format, type);
        }
        final Formats.Format f = format(internal);
        final int layers = t.isCube() ? 6 : (t.target == GL30.GL_TEXTURE_2D_ARRAY || t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY ? d : (t.target == GL30.GL_TEXTURE_1D_ARRAY ? h : 1));
        final int depth = t.is3D() ? d : 1;
        final int height = t.target == GL30.GL_TEXTURE_1D_ARRAY ? 1 : h;
        specify(t, f, level, w, height, depth, layers);
        if (pixels != 0L || fromPbo) {
            final int face = cubeFace(target);
            final int baseLayer = t.isCube() ? face : 0;
            final int layerCount = t.isCube() ? 1 : (t.target == GL30.GL_TEXTURE_2D_ARRAY || t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY || t.target == GL30.GL_TEXTURE_1D_ARRAY ? layers : 1);
            upload(st, t, level, 0, 0, 0, w, height, t.is3D() ? depth : 1, baseLayer, layerCount, format, type, pixels, fromPbo);
        }
    }

    public void texStorage(GlState st, int target, int levels, int internalFormat, int w, int h, int d) {
        final T t = getOrCreate(st.boundTexture(target), target);
        if (t == null) return;
        storage(t, levels, internalFormat, w, h, d);
    }

    public void texSubImage(GlState st, int target, int level, int x, int y, int z, int w, int h, int d, int format, int type, long pixels, boolean fromPbo) {
        final T t = get(st.boundTexture(target));
        if (t == null || !t.hasImage()) return;
        subImage(st, t, target, level, x, y, z, w, h, d, format, type, pixels, fromPbo);
    }

    public void upload(GlState st, T t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int format, int type, long pixels, boolean fromPbo) {
        final Formats.Format f = t.format;
        long src = pixels;
        ByteBuffer readback = null;
        if (fromPbo) {
            final BufBase pbo = unpackBuffer(st.pixelUnpack);
            if (pbo == null || !pbo.hasStorage()) return;
            if (pbo.hostVisible()) {
                if (!isIdle(pbo.lastGpuWrite)) flushAndWait(pbo.lastGpuWrite);
                src = pbo.address() + pixels;
            } else {
                final long size = f.isCompressed()
                    ? FormatUtil.imageBytes(f, w, h) * Math.max(1, d) * layerCount
                    : PixelConvert.clientBytes(w, h, Math.max(1, d) * layerCount, format, type, st.unpack);
                if (pixels < 0 || pixels + size > pbo.size) {
                    warnOnce("unpack PBO range out of bounds");
                    return;
                }
                readback = MemoryUtil.memAlloc((int) size);
                readBuffer(pbo, pixels, size, MemoryUtil.memAddress(readback));
                src = MemoryUtil.memAddress(readback);
            }
        }
        try {
            uploadFrom(st, t, level, x, y, z, w, h, d, layer, layerCount, format, type, src);
        } finally {
            if (readback != null) MemoryUtil.memFree(readback);
        }
    }

    protected void proxy(GlState st, int target, int level, int internalFormat, int w, int h, int d) {
        final int slot = proxySlot(target);
        if (slot < 0) return;
        st.proxies[slot] = null;
        final Formats.Format f = Formats.of(internalFormat, depth24Stencil8, depth24);
        if (f == null || !sampleable(f)) return;
        final boolean is3D = target == GL12.GL_PROXY_TEXTURE_3D;
        final int maxDim = getInteger(is3D ? GL12.GL_MAX_3D_TEXTURE_SIZE
            : target == GL13.GL_PROXY_TEXTURE_CUBE_MAP || target == GL40.GL_PROXY_TEXTURE_CUBE_MAP_ARRAY ? GL13.GL_MAX_CUBE_MAP_TEXTURE_SIZE : GL11.GL_MAX_TEXTURE_SIZE);
        final int maxLayers = getInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS);
        if (level > 31 - Integer.numberOfLeadingZeros(maxDim)) return;
        final long baseW = levelZeroSize(w, level);
        final long baseH = heightIsLayers(target) ? h : levelZeroSize(h, level);
        final long baseD = depthIsLayers(target) ? d : is3D ? levelZeroSize(d, level) : 1;
        if (baseW <= 0 || baseW > maxDim) return;
        if (baseH <= 0 || baseH > (heightIsLayers(target) ? maxLayers : maxDim)) return;
        if (baseD <= 0 || baseD > (depthIsLayers(target) ? maxLayers : maxDim)) return;
        st.proxies[slot] = new int[] {(int) baseW, (int) baseH, (int) baseD, internalFormat};
    }

    protected void mergeStencil(T t, int level, int layer, int x, int y, int w, int h, int type, PixelConvert.Store pack, long dst) {
        final int stencilOffset = switch (type) {
            case GL30.GL_UNSIGNED_INT_24_8 -> 0;
            case GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV -> 4;
            default -> -1;
        };
        if (stencilOffset < 0) return;
        final PixelConvert.Store tight = new PixelConvert.Store();
        tight.alignment = 1;
        final long stencil = MemoryUtil.nmemAlloc((long) w * h);
        try {
            if (!readImage(t, Formats.ASPECT_STENCIL, level, layer, x, y, w, h, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, tight, stencil)) return;
            final long stride = PixelConvert.rowStride(w, GL30.GL_DEPTH_STENCIL, type, pack);
            final int px = PixelConvert.pixelBytes(GL30.GL_DEPTH_STENCIL, type);
            final long base = dst + (long) pack.skipRows * stride + (long) pack.skipPixels * px;
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    MemoryUtil.memPutByte(base + row * stride + (long) col * px + stencilOffset, MemoryUtil.memGetByte(stencil + (long) row * w + col));
                }
            }
        } finally {
            MemoryUtil.nmemFree(stencil);
        }
    }

    public Formats.Format format(int glInternal) {
        Formats.Format f = Formats.of(glInternal, depth24Stencil8, depth24);
        if (f == null) {
            warnOnce("unsupported internal format 0x" + Integer.toHexString(glInternal) + ", using RGBA8");
            f = Formats.RGBA8;
        }
        if (f.isCompressed() && !sampleable(f)) {
            warnOnce("compressed format 0x" + Integer.toHexString(glInternal) + " unsupported by the device");
        }
        return f;
    }
}
