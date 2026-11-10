package com.gtnewhorizons.angelica.glemu.resource;

import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.gl.TokenMaps;
import com.gtnewhorizons.angelica.glemu.util.IdTable;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryUtil;

public abstract class TexturesBase<T extends TexBase> {
    protected final IdTable<T> textures = new IdTable<>();
    protected final IdTable<T> renderbuffers = new IdTable<>();
    protected int nextTexId = 1, nextRboId = 1, nextInternalId = -1000;
    protected int depth24Stencil8, depth24;

    protected final GlBase<T, ?, ?> base;

    protected TexturesBase(GlBase<T, ?, ?> base) {
        this.base = base;
    }

    protected abstract T newTex(int id, boolean renderbuffer);

    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet borrowedNames = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
    private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<T> defaultObjects = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

    protected void warnOnce(String message) {
        base.warnOnce(message);
    }

    protected int getInteger(int pname) {
        return base.getInteger(pname);
    }

    protected void createImage(T t) {
        base.createImage(t, false);
    }

    protected void createStorageImage(T t) {
        base.createImage(t, true);
    }

    protected void releaseImage(T t) {
        base.releaseImage(t);
    }

    protected void onImageReleased(T t) {
        base.rendererBase().onImageReleased(t);
    }

    protected void targetsChanged() {
        base.targetsChanged();
    }

    protected void onTextureDeleted(int id, boolean renderbuffer) {
        base.framebuffers.onTextureDeleted(id, renderbuffer);
    }

    protected BufBase unpackBuffer(int id) {
        return base.buffers().get(id);
    }

    protected void readBuffer(BufBase buffer, long offset, long size, long dst) {
        readFrom(base.buffers(), buffer, offset, size, dst);
    }

    @SuppressWarnings("unchecked")
    private static <B extends BufBase> void readFrom(BuffersBase<B> buffers, BufBase buffer, long offset, long size, long dst) {
        buffers.read((B) buffer, offset, size, dst);
    }

    protected boolean isIdle(long serial) {
        return base.isIdle(serial);
    }

    protected void flushAndWait(long serial) {
        base.flushAndWait(serial);
    }

    protected void prepareForMipGeneration(T t) {
        base.rendererBase().materialize(t);
        base.rendererBase().endPass();
    }

    protected abstract void clearImageHandles(T t);

    protected abstract T snapshot(T t);

    protected abstract void copyLevels(T from, T to);

    protected abstract boolean sampleable(Formats.Format f);

    protected abstract void uploadFrom(GlState st, T t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int format, int type, long src);

    public abstract boolean readImage(T t, int aspect, int level, int layer, int x, int y, int w, int h, int format, int type, PixelConvert.Store pack, long dst);

    public abstract void copyFromFramebuffer(GlState st, T dst, int target, int level, int dx, int dy, int dz, int sx, int sy, int w, int h);

    protected abstract boolean canGenerateMips(T t);

    protected abstract void generateMips(T t);

    protected abstract void mipsGenerated(T t);

    private void rememberReplacedLevels(T t) {
        if (!t.hasImage()) return;
        final int[][] kept = new int[Math.min(t.levels, 31)][];
        for (int i = 0; i < kept.length; i++) {
            if ((t.missingLevels >>> i & 1) == 0) kept[i] = new int[] {t.levelWidth(i), t.levelHeight(i), t.is3D() ? t.levelDepth(i) : t.layers};
        }
        t.replacedLevels = kept;
    }

    /** @return false when the size is over the limits, which changes nothing */
    public boolean specify(T t, Formats.Format f, int level, int w, int h, int d, int layers) {
        return specify(t, f, level, w, h, d, layers, false);
    }

    protected boolean specify(T t, Formats.Format f, int level, int w, int h, int d, int layers, boolean grow) {
        final boolean flat = t.target == GL11.GL_TEXTURE_1D || t.target == GL30.GL_TEXTURE_1D_ARRAY;
        // Level sizes round down, so a level above 0 only says what the base size may be: an image it fits keeps its shape.
        final boolean sameShape = t.hasImage() && t.format.pf() == f.pf() && t.format.glInternal() == f.glInternal() && t.layers == layers
            && t.levelWidth(level) == Math.max(1, w) && (flat ? t.height : t.levelHeight(level)) == Math.max(1, h) && (!t.is3D() || t.levelDepth(level) == Math.max(1, d));
        if (!sameShape && !withinLimits(t, levelZeroSize(Math.max(1, w), level), flat ? Math.max(1, h) : levelZeroSize(Math.max(1, h), level),
            t.is3D() ? levelZeroSize(Math.max(1, d), level) : 1, layers)) return false;
        final int baseW = sameShape ? t.width : Math.max(1, w << level);
        final int baseH = sameShape ? t.height : flat ? Math.max(1, h) : Math.max(1, h << level);
        final int baseD = sameShape ? t.depth : t.is3D() ? Math.max(1, d << level) : 1;
        final int levels = t.levelsToAllocate(level, sameShape, baseW, baseH, baseD);
        // A level within an image of the same shape keeps that image; one past its levels rebuilds it with the old levels copied in, and a new shape starts over.
        if (sameShape && level < t.levels && !(grow && levels > t.levels)) {
            t.missingLevels &= ~(1 << level);
            return true;
        }
        if (sameShape) {
            for (int skipped = t.levels; skipped < level && skipped < 31; skipped++) t.missingLevels |= 1 << skipped;
            t.missingLevels &= ~(1 << level);
        } else {
            rememberReplacedLevels(t);
            t.missingLevels = level >= 31 ? -1 : (1 << level) - 1;
        }
        final T old = sameShape ? snapshot(t) : null;
        if (!sameShape) {
            releaseImage(t);
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
        if (!allocate(t)) return false;
        // An image nothing has written yet has nothing to keep, and its new levels are still to be cleared with it.
        if (old != null && !old.needsClear) copyLevels(old, t);
        targetsChanged();
        return true;
    }

    /** GL answers a failed allocation with an error, leaving the texture without an image. */
    public final boolean allocate(T t) {
        try {
            createImage(t);
            return true;
        } catch (OutOfMemoryError e) {
            warnOnce("out of memory for an image of " + t.width + "x" + t.height + "x" + t.depth + " with " + t.layers + " layers");
            base.recordError(GL11.GL_OUT_OF_MEMORY);
            return false;
        } catch (IllegalStateException e) {
            // A device can refuse a shape GL allows, such as Metal's depth images in one dimension.
            warnOnce("the device refused an image: " + e.getMessage());
            base.recordError(GL11.GL_OUT_OF_MEMORY);
            return false;
        }
    }

    /** GL answers an image over its limits with an error, where a driver may abort the process, so one never reaches the device. */
    public boolean withinLimits(T t, long w, long h, long d, int layers) {
        final int max = getInteger(t.renderbuffer ? GL30.GL_MAX_RENDERBUFFER_SIZE : t.is3D() ? GL12.GL_MAX_3D_TEXTURE_SIZE : t.isCube() ? GL13.GL_MAX_CUBE_MAP_TEXTURE_SIZE : GL11.GL_MAX_TEXTURE_SIZE);
        if (w > 0 && w <= max && h > 0 && h <= max && d > 0 && d <= max && layers <= getInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS)) return true;
        warnOnce("ignoring an image of " + w + "x" + h + "x" + d + " with " + layers + " layers, over the device's limits");
        base.recordError(GL11.GL_INVALID_VALUE);
        return false;
    }

    public void generateMipmap(T t) {
        if (t == null) return;
        t.writes++;
        t.missingLevels = 0;
        if (!t.hasImage()) return;
        final int want = t.wantedMipLevels();
        if (t.levels < want && !t.immutable) {
            final int keepMax = t.maxLevel;
            t.maxLevel = want - 1;
            specify(t, t.format, 0, t.width, t.height, t.depth, t.layers, true);
            t.maxLevel = keepMax;
        }
        if (t.levels <= 1) return;
        if (t.format.isDepth() && !t.format.isStencil() && !canFilterDepthOnDevice()) {
            generateDepthMipsByReading(t);
            return;
        }
        if (!canGenerateMips(t)) return;
        prepareForMipGeneration(t);
        generateMips(t);
        mipsGenerated(t);
    }

    /** Whether the device can make a depth image's mipmaps itself. */
    protected boolean canFilterDepthOnDevice() {
        return true;
    }

    /** Each level the average of four depths of the one before, which GL leaves open and a device without depth filtering cannot do. */
    private void generateDepthMipsByReading(T t) {
        final GlState st = base.st();
        final PixelConvert.Store saved = new PixelConvert.Store();
        saved.copyFrom(st.unpack);
        final int unpackBuffer = st.pixelUnpack;
        st.unpack.copyFrom(new PixelConvert.Store());
        st.pixelUnpack = 0;
        final PixelConvert.Store pack = new PixelConvert.Store();
        pack.alignment = 1;
        try {
            final int slices = Math.max(1, t.layers);
            for (int level = 1; level < t.levels; level++) {
                final int pw = t.levelWidth(level - 1), ph = t.levelHeight(level - 1), w = t.levelWidth(level), h = t.levelHeight(level);
                final long from = MemoryUtil.nmemAlloc((long) pw * ph * Float.BYTES), to = MemoryUtil.nmemAlloc((long) w * h * Float.BYTES);
                try {
                    for (int layer = 0; layer < slices; layer++) {
                        if (!readImage(t, Formats.ASPECT_DEPTH, level - 1, layer, 0, 0, pw, ph, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pack, from)) continue;
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                final int x0 = Math.min(x * pw / w, pw - 1), y0 = Math.min(y * ph / h, ph - 1);
                                final int x1 = Math.min(x0 + (pw > w ? 1 : 0), pw - 1), y1 = Math.min(y0 + (ph > h ? 1 : 0), ph - 1);
                                final float sum = MemoryUtil.memGetFloat(from + (y0 * (long) pw + x0) * Float.BYTES) + MemoryUtil.memGetFloat(from + (y0 * (long) pw + x1) * Float.BYTES)
                                    + MemoryUtil.memGetFloat(from + (y1 * (long) pw + x0) * Float.BYTES) + MemoryUtil.memGetFloat(from + (y1 * (long) pw + x1) * Float.BYTES);
                                MemoryUtil.memPutFloat(to + (y * (long) w + x) * Float.BYTES, sum / 4f);
                            }
                        }
                        upload(st, t, level, 0, 0, 0, w, h, 1, layer, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, to, false);
                    }
                } finally {
                    MemoryUtil.nmemFree(from);
                    MemoryUtil.nmemFree(to);
                }
            }
        } finally {
            st.pixelUnpack = unpackBuffer;
            st.unpack.copyFrom(saved);
        }
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

    public static int cubeFace(int target) {
        return target >= GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z ? target - GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X : 0;
    }

    /** A texture that is a renderbuffer's storage, for EXT_direct_state_access and NV_explicit_multisample's texture renderbuffers. */
    public void aliasRenderbuffer(int texture, int renderbuffer) {
        final T t = renderbuffers.get(renderbuffer);
        if (t == null) return;
        t.target = GL11.GL_TEXTURE_2D;
        textures.put(texture, t);
    }

    public void delete(int id) {
        final T t = textures.remove(id);
        if (t == null) return;
        if (renderbuffers.get(t.id) == t) return;
        if (t.is3D()) forgetSliceImages(t);
        releaseImage(t);
        onTextureDeleted(id, false);
    }

    public void deleteRenderbuffer(int id) {
        final T t = renderbuffers.remove(id);
        if (t == null) return;
        textures.removeValue(t);
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

    /** A name to sample an image by for one internal draw, which a renderbuffer has none of among textures. */
    public final int borrowName(T t) {
        if (!t.renderbuffer && t.id > 0) return t.id;
        int id = nextTexId++;
        while (textures.containsKey(id)) id = nextTexId++;
        textures.put(id, t);
        borrowedNames.add(id);
        return id;
    }

    public final void returnName(int id) {
        if (borrowedNames.remove(id)) textures.remove(id);
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

    /** Texture 0 of a target, which has no image here but keeps the parameters set on it as any other texture does. */
    public final T defaultObject(int target) {
        return defaultObjects.computeIfAbsent(canonicalTarget(target), key -> {
            final T t = newTex(0, false);
            t.target = key;
            t.declaredTarget = target;
            return t;
        });
    }

    public T getOrCreate(int id, int target) {
        if (id == 0) return null;
        T t = textures.get(id);
        if (t == null) {
            t = newTex(id, false);
            textures.put(id, t);
            if (id >= nextTexId) nextTexId = id + 1;
        }
        if (t.target == 0 && target != 0) {
            t.target = canonicalTarget(target);
            t.declaredTarget = target;
            if (target == GL31.GL_TEXTURE_RECTANGLE) {
                t.minFilter = GL11.GL_LINEAR;
                t.wrapS = t.wrapT = t.wrapR = GL12.GL_CLAMP_TO_EDGE;
            }
        }
        return t;
    }

    protected static boolean heightIsLayers(int target) {
        return target == GL30.GL_PROXY_TEXTURE_1D_ARRAY;
    }

    public T internalTexture(int target, Formats.Format f, int w, int h, int d, int layers, String label) {
        return internalTexture(target, f, w, h, d, layers, label, t -> {});
    }

    public T internalTexture(int target, Formats.Format f, int w, int h, int d, int layers, String label, Consumer<T> beforeCreate) {
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
        beforeCreate.accept(t);
        createImage(t);
        return t;
    }

    public abstract void copyImage(T src, int srcLevel, int srcX, int srcY, int srcZ, T dst, int dstLevel, int dstX, int dstY, int dstZ, int width, int height, int depth);

    public T internalStorageTexture(Formats.Format f, String label) {
        return internalStorageTexture(f, label, 1);
    }

    public T internalStorageTexture(Formats.Format f, String label, int size) {
        return internalStorageTexture(f, label, size, size);
    }

    /** A slice of a 3D image bound on its own, which no device here can view as a 2D image. It runs on a copy, written back at a barrier. */
    private final class SliceImage {
        final T volume, slice;
        final int level, layer;
        long writesSeen = -1;
        boolean written;

        SliceImage(T volume, T slice, int level, int layer) {
            this.volume = volume;
            this.slice = slice;
            this.level = level;
            this.layer = layer;
        }
    }

    private final java.util.Map<Long, SliceImage> sliceImages = new java.util.HashMap<>();

    public final T sliceImage(T volume, int level, int layer, boolean writes) {
        final int usable = Math.min(level, volume.levels - 1);
        final long key = (long) volume.id << 32 | (long) usable << 16 | layer;
        SliceImage image = sliceImages.get(key);
        if (image == null) {
            image = new SliceImage(volume, internalStorageTexture(volume.format, "slice" + volume.id, volume.levelWidth(usable), volume.levelHeight(usable)), usable, layer);
            sliceImages.put(key, image);
        }
        if (!image.written && image.writesSeen != volume.writes) {
            copyImage(volume, usable, 0, 0, layer, image.slice, 0, 0, 0, 0, volume.levelWidth(usable), volume.levelHeight(usable), 1);
            image.writesSeen = volume.writes;
        }
        image.written |= writes;
        return image.slice;
    }

    public final void writeBackSliceImages() {
        if (sliceImages.isEmpty()) return;
        for (SliceImage image : sliceImages.values()) {
            if (!image.written) continue;
            copyImage(image.slice, 0, 0, 0, 0, image.volume, image.level, 0, 0, image.layer, image.volume.levelWidth(image.level), image.volume.levelHeight(image.level), 1);
            image.volume.writes++;
            image.writesSeen = image.volume.writes;
            image.written = false;
        }
    }

    private void forgetSliceImages(T volume) {
        if (sliceImages.isEmpty()) return;
        sliceImages.values().removeIf(image -> {
            if (image.volume != volume) return false;
            base.releaseImage(image.slice);
            return true;
        });
    }

    public T internalStorageTexture(Formats.Format f, String label, int width, int height) {
        final T t = newTex(nextInternalId--, false);
        t.target = GL11.GL_TEXTURE_2D;
        t.format = f;
        t.width = width;
        t.height = height;
        t.levels = 1;
        t.label = label;
        t.minFilter = GL11.GL_NEAREST;
        createStorageImage(t);
        return t;
    }

    public static boolean isProxy(int target) {
        return proxySlot(target) >= 0;
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
        final int height = t.target == GL30.GL_TEXTURE_1D_ARRAY ? 1 : Math.max(1, h);
        final int depth = t.is3D() ? Math.max(1, d) : 1;
        final int layers = t.isCube() ? 6 * (t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY ? Math.max(1, d / 6) : 1)
            : (t.target == GL30.GL_TEXTURE_2D_ARRAY ? Math.max(1, d) : t.target == GL30.GL_TEXTURE_1D_ARRAY ? Math.max(1, h) : 1);
        if (!withinLimits(t, Math.max(1, w), height, depth, layers)) return;
        releaseImage(t);
        t.format = f;
        t.width = Math.max(1, w);
        t.height = height;
        t.depth = depth;
        t.layers = layers;
        t.levels = Math.max(1, Math.min(levels, TexBase.fullChain(t.width, t.height, t.depth)));
        t.immutable = true;
        if (!allocate(t)) {
            t.immutable = false;
            return;
        }
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

    /** @return false when the size is over the limits, which changes nothing */
    public boolean texImage(GlState st, int target, int level, int internalFormat, int w, int h, int d, int format, int type, long pixels, boolean fromPbo) {
        if (isProxy(target)) {
            proxy(st, target, level, internalFormat, w, h, d);
            return true;
        }
        final T t = getOrCreate(st.boundTexture(target), target);
        if (t == null) return true;
        if (t.immutable) {
            warnOnce("glTexImage on an immutable texture " + t.id);
            base.recordError(GL11.GL_INVALID_OPERATION);
            return true;
        }
        if (w < 0 || h < 0 || d < 0) {
            base.recordError(GL11.GL_INVALID_VALUE);
            return false;
        }
        if (w <= 0 || h <= 0 || d <= 0) {
            if (level == 0 && t.hasImage()) {
                releaseImage(t);
                t.width = t.height = 0;
                t.levels = 0;
                t.viewDirty = true;
            }
            return true;
        }
        int internal = internalFormat;
        if (internal == format && (internal == GL11.GL_RGBA || internal == GL11.GL_RGB || internal == GL11.GL_RED || internal == GL30.GL_RG)) {
            internal = Formats.sizedFor(format, type);
        }
        final Formats.Format f = format(internal);
        final int layers = t.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : (t.target == GL30.GL_TEXTURE_2D_ARRAY || t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY ? d : (t.target == GL30.GL_TEXTURE_1D_ARRAY ? h : 1));
        final int depth = t.is3D() ? d : 1;
        final int height = t.target == GL30.GL_TEXTURE_1D_ARRAY ? 1 : h;
        if (!specify(t, f, level, w, height, depth, layers)) return false;
        if (t.target == GL13.GL_TEXTURE_CUBE_MAP && level == 0) t.definedFaces |= 1 << cubeFace(target);
        if (pixels != 0L || fromPbo) {
            final int face = cubeFace(target);
            final int baseLayer = t.target == GL13.GL_TEXTURE_CUBE_MAP ? face : 0;
            final int layerCount = t.target == GL13.GL_TEXTURE_CUBE_MAP ? 1 : (t.target == GL30.GL_TEXTURE_2D_ARRAY || t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY || t.target == GL30.GL_TEXTURE_1D_ARRAY ? layers : 1);
            upload(st, t, level, 0, 0, 0, w, height, t.is3D() ? depth : 1, baseLayer, layerCount, format, type, pixels, fromPbo);
        }
        return true;
    }

    public void texStorage(GlState st, int target, int levels, int internalFormat, int w, int h, int d) {
        final T t = getOrCreate(st.boundTexture(target), target);
        if (t == null) return;
        storage(t, levels, internalFormat, w, h, d);
    }

    public void texSubImage(GlState st, int target, int level, int x, int y, int z, int w, int h, int d, int format, int type, long pixels, boolean fromPbo) {
        final T t = get(st.boundTexture(target));
        if (t == null || !t.hasImage() || level >= t.levels) {
            base.recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        subImage(st, t, target, level, x, y, z, w, h, d, format, type, pixels, fromPbo);
    }

    public void upload(GlState st, T t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int format, int type, long pixels, boolean fromPbo) {
        if (x < 0 || y < 0 || z < 0 || x + w > t.levelWidth(level) || y + h > t.levelHeight(level) || t.is3D() && z + d > t.levelDepth(level) || layer + layerCount > Math.max(TokenMaps.sliceCount(t), 1)) {
            warnOnce("texture upload outside level " + level + " of texture " + t.id);
            base.recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        t.writes++;
        final Formats.Format f = t.format;
        long src = pixels;
        ByteBuffer readback = null;
        if (fromPbo) {
            final BufBase pbo = unpackBuffer(st.pixelUnpack);
            if (pbo == null || !pbo.hasStorage()) return;
            final long size = f.isCompressed()
                ? Formats.imageBytes(f, w, h) * Math.max(1, d) * layerCount
                : PixelConvert.clientBytes(w, h, Math.max(1, d) * layerCount, format, type, st.unpack);
            if (pixels < 0 || pixels + size > pbo.size) {
                warnOnce("unpack PBO range out of bounds");
                base.recordError(GL11.GL_INVALID_OPERATION);
                return;
            }
            if (pbo.hostVisible()) {
                if (!isIdle(pbo.lastGpuWrite)) flushAndWait(pbo.lastGpuWrite);
                src = pbo.address() + pixels;
            } else {
                readback = MemoryUtil.memAlloc((int) size);
                readBuffer(pbo, pixels, size, MemoryUtil.memAddress(readback));
                src = MemoryUtil.memAddress(readback);
            }
        }
        try {
            if (format == GL11.GL_STENCIL_INDEX && f.isStencil()) uploadStencilIndices(st, t, level, x, y, z, w, h, d, layer, layerCount, type, src);
            else if (f.isCompressed() && type != 0) compressAndUpload(st, t, level, x, y, z, w, h, d, layer, layerCount, format, type, src);
            else uploadFrom(st, t, level, x, y, z, w, h, d, layer, layerCount, format, type, src);
            if (format == GL30.GL_DEPTH_STENCIL && f.isDepth() && f.isStencil()) uploadStencil(st, t, level, x, y, z, w, h, d, layer, layerCount, type, src);
        } finally {
            if (readback != null) MemoryUtil.memFree(readback);
        }
    }

    /** Uncompressed pixels for a compressed image, which GL compresses itself. Type 0 is how an upload of blocks is told apart. */
    private void compressAndUpload(GlState st, T t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int format, int type, long src) {
        final int slices = Math.max(1, d) * layerCount;
        final long bytes = Formats.imageBytes(t.format, w, h) * slices;
        final long blocks = MemoryUtil.nmemAlloc(bytes);
        try {
            if (!PixelConvert.compress(t.format, format, type, src, w, h, slices, st.unpack, blocks)) {
                warnOnce("cannot compress pixels format=0x" + Integer.toHexString(format) + " type=0x" + Integer.toHexString(type));
                base.recordError(GL11.GL_INVALID_OPERATION);
                return;
            }
            uploadFrom(st, t, level, x, y, z, w, h, d, layer, layerCount, 0, 0, blocks);
            base.retainCompressed(t, t.target == GL13.GL_TEXTURE_CUBE_MAP ? layer : 0, level, x, y, z, w, h, slices, blocks);
        } finally {
            MemoryUtil.nmemFree(blocks);
        }
    }

    /** The size of an upload in the image's own format with no gaps between rows. */
    protected static long tightUploadBytes(Formats.Format f, int w, int h, int d, int layerCount) {
        final long depth = (long) Math.max(1, d) * layerCount;
        return f.isCompressed() ? Formats.imageBytes(f, w, h) * depth : w * h * depth * f.texelBytes();
    }

    private void uploadStencilIndices(GlState st, T t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int type, long src) {
        final int slices = Math.max(1, d) * layerCount;
        final long stencil = MemoryUtil.nmemAlloc((long) w * h * slices);
        try {
            if (PixelConvert.unpack(Formats.of(GL30.GL_R8, 0, 0), GL11.GL_STENCIL_INDEX, type, src, w, h, slices, st.unpack, stencil)) {
                writeStencil(t, level, x, y, z, w, h, Math.max(1, d), layer, layerCount, stencil);
            } else {
                base.recordError(GL11.GL_INVALID_OPERATION);
            }
        } finally {
            MemoryUtil.nmemFree(stencil);
        }
    }

    /** Packed pixels set the stencil too, which goes into an aspect of its own. */
    private void uploadStencil(GlState st, T t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int type, long src) {
        final int stencilOffset = type == GL30.GL_UNSIGNED_INT_24_8 ? 0 : type == GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV ? 4 : -1;
        if (stencilOffset < 0) return;
        final int slices = Math.max(1, d) * layerCount;
        final PixelConvert.Store store = st.unpack;
        final long stride = PixelConvert.rowStride(w, GL30.GL_DEPTH_STENCIL, type, store);
        final int px = PixelConvert.pixelBytes(GL30.GL_DEPTH_STENCIL, type);
        final long image = (store.imageHeight > 0 ? store.imageHeight : h) * stride;
        final long first = src + store.skipImages * image + store.skipRows * stride + store.skipPixels * (long) px;
        final long stencil = MemoryUtil.nmemAlloc((long) w * h * slices);
        try {
            long out = stencil;
            for (int slice = 0; slice < slices; slice++) {
                for (int row = 0; row < h; row++) {
                    final long in = first + slice * image + row * stride + stencilOffset;
                    for (int col = 0; col < w; col++) MemoryUtil.memPutByte(out++, MemoryUtil.memGetByte(in + (long) col * px));
                }
            }
            writeStencil(t, level, x, y, z, w, h, Math.max(1, d), layer, layerCount, stencil);
        } finally {
            MemoryUtil.nmemFree(stencil);
        }
    }

    protected abstract void writeStencil(T t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, long tight);

    /** Writes client pixels tightly in the image's format, answering false with a warning when they cannot convert. */
    protected final boolean writeUpload(GlState st, Formats.Format f, int format, int type, long src, int w, int h, int d, int layerCount, long dst) {
        final int depth = Math.max(1, d) * layerCount;
        if (f.isCompressed()) {
            MemoryUtil.memCopy(src, dst, Formats.imageBytes(f, w, h) * depth);
            return true;
        }
        if (PixelConvert.unpack(f, format, type, src, w, h, depth, st.unpack, dst)) return true;
        warnOnce("cannot convert pixels format=0x" + Integer.toHexString(format) + " type=0x" + Integer.toHexString(type) + " into format " + f.pf());
        base.recordError(GL11.GL_INVALID_OPERATION);
        return false;
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
        // Depth and stencil are read back separately; the stencil bytes are written into their slot of each packed depth-stencil texel.
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

    public boolean knownInternalFormat(int glInternal) {
        return Formats.of(glInternal, depth24Stencil8, depth24) != null;
    }

    private static final int[] COMPRESSED_CANDIDATES = {0x83F0, 0x83F1, 0x83F2, 0x83F3, 0x8C4C, 0x8C4D, 0x8C4E, 0x8C4F, 0x8DBB, 0x8DBC, 0x8DBD, 0x8DBE, 0x8E8C, 0x8E8D, 0x8E8E, 0x8E8F,
        0x9270, 0x9271, 0x9272, 0x9273, 0x9274, 0x9275, 0x9276, 0x9277, 0x9278, 0x9279};
    private int[] compressedFormats;

    /** The specific compressed formats this device can sample, as GL_COMPRESSED_TEXTURE_FORMATS lists them. */
    public int[] compressedFormats() {
        if (compressedFormats == null) {
            compressedFormats = java.util.Arrays.stream(COMPRESSED_CANDIDATES).filter(c -> {
                final Formats.Format f = Formats.of(c, depth24Stencil8, depth24);
                return f != null && f.isCompressed() && sampleable(f);
            }).toArray();
        }
        return compressedFormats;
    }

    /** Images hold their sample count for life, so a change of it starts the image again. */
    public void setSamples(T t, int samples) {
        if (t.samples == samples) return;
        if (t.hasImage()) releaseImage(t);
        t.samples = samples;
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

    /** Copies read-back stencil bytes out to the caller's rows, where the pack state says they start. */
    protected static void copyStencilRows(long src, long srcStride, int w, int h, int format, int type, PixelConvert.Store pack, long dst) {
        final long dstStride = PixelConvert.rowStride(w, format, type, pack);
        final int px = PixelConvert.pixelBytes(format, type);
        final long first = dst + pack.skipRows * dstStride + (long) pack.skipPixels * px;
        for (int row = 0; row < h; row++) {
            final long in = src + row * srcStride, out = first + row * dstStride;
            if (px == 1) {
                MemoryUtil.memCopy(in, out, w);
                continue;
            }
            for (int x = 0; x < w; x++) {
                final int stencil = MemoryUtil.memGetByte(in + x) & 0xFF;
                final long at = out + (long) x * px;
                if (px == 2) MemoryUtil.memPutShort(at, (short) stencil);
                else if (type == GL11.GL_FLOAT) MemoryUtil.memPutFloat(at, stencil);
                else MemoryUtil.memPutInt(at, stencil);
            }
        }
    }
}
