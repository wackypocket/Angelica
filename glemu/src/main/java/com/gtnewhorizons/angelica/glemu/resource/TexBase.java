package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL40;

public abstract class TexBase {
    public final int id;
    /** Whether the name has been bound, which is what makes a generated name an object. */
    public boolean named;
    public final boolean renderbuffer;
    public int target;

    public Formats.Format format;
    /** Compressed uploads as given, by level and cube face, since no readback path can return blocks. */
    public byte[][] compressedLevels;
    public int width, height, depth = 1, layers = 1;
    public int levels;
    /** Levels the image has only because one above them was given first, which GL counts as undefined. */
    public int missingLevels;
    /** What a view reports as GL_TEXTURE_IMMUTABLE_LEVELS, which is its original texture's count. */
    public int immutableLevels;
    /** The size of each level a new shape replaced, as {width, height, depth}, which GL goes on reporting until it is respecified. */
    public int[][] replacedLevels;
    public boolean immutable;

    public long image;
    public int usage;
    public boolean needsClear;
    /** How many samples each texel holds: 1 unless GL asked for multisampling, and then a count the device supports. */
    public int samples = 1;
    public long version;
    public long lastUse;
    public long lastGpuWrite;
    public long lastStateChange;

    public void markGpuWrite(long serial) {
        lastUse = serial;
        lastGpuWrite = serial;
        writes++;
    }

    /** Counts what has written this, so that what shares its data as a view can tell. */
    public long writes;

    public long view;
    public boolean viewDirty = true;

    public int minFilter = GL11.GL_NEAREST_MIPMAP_LINEAR, magFilter = GL11.GL_LINEAR;
    public int wrapS = GL11.GL_REPEAT, wrapT = GL11.GL_REPEAT, wrapR = GL11.GL_REPEAT;
    public float minLod = -1000f, maxLod = 1000f, lodBias;
    public int baseLevel, maxLevel = 1000;
    public int compareMode = GL11.GL_NONE, compareFunc = GL11.GL_LEQUAL;
    public float anisotropy = 1f;
    /** What glTextureView was asked for, for the queries about it. */
    public int viewMinLevel, viewNumLevels, viewMinLayer, viewNumLayers;
    /** The target GL was told, where {@code target} is what it is stored as: a rectangle texture is a 2D image with rules of its own. */
    public int declaredTarget;
    /** Which faces of a cube map have their first level given, while it is built one face at a time. */
    public int definedFaces;
    public final float[] border = new float[4];
    /** The border an integer texture was given by glTexParameterI, which a float can't hold exactly. */
    public int[] borderInteger;
    public final int[] swizzle = {GL11.GL_RED, GL11.GL_GREEN, GL11.GL_BLUE, GL11.GL_ALPHA};
    public boolean sampleStencil;

    /** Whether a shader reads stencil rather than depth. A stencil index image, which GL says has nothing else, is stored with a depth too. */
    public boolean samplesStencil() {
        return sampleStencil || format != null && format.glInternal() == org.lwjgl.opengl.GL30.GL_STENCIL_INDEX8;
    }
    public long sampler;
    public boolean samplerDirty = true;

    public boolean pendingClear;
    public final float[] clearColor = new float[4];
    public float clearDepth;
    public int clearStencil;
    public int clearAspects;

    public String label;

    /** Keyed by the view's level, layers and format, which take more than an int between them. */
    public final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap storageViews = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    /** Copies what identifies this texture's image, for a snapshot taken just before the image is replaced. */
    protected void copyImageStateTo(TexBase s) {
        s.target = target;
        s.format = format;
        s.width = width;
        s.height = height;
        s.depth = depth;
        s.layers = layers;
        s.levels = levels;
        s.image = image;
        s.view = view;
        s.lastUse = lastUse;
        s.storageViews.putAll(storageViews);
        s.pendingClear = pendingClear;
        s.needsClear = needsClear;
        s.samples = samples;
        System.arraycopy(clearColor, 0, s.clearColor, 0, 4);
        s.clearDepth = clearDepth;
        s.clearStencil = clearStencil;
        s.clearAspects = clearAspects;
    }

    protected TexBase(int id, boolean renderbuffer) {
        this.id = id;
        this.renderbuffer = renderbuffer;
        if (renderbuffer) {
            minFilter = GL11.GL_NEAREST;
            magFilter = GL11.GL_NEAREST;
        }
    }

    public boolean hasImage() {
        return image != 0L;
    }

    public int aspect() {
        return format == null ? Formats.ASPECT_COLOR : format.aspect();
    }

    public int levelWidth(int level) {
        return Math.max(1, width >> level);
    }

    public int levelHeight(int level) {
        return Math.max(1, height >> level);
    }

    public int levelDepth(int level) {
        return Math.max(1, depth >> level);
    }

    public static int fullChain(int w, int h, int d) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(w, Math.max(h, d)));
    }

    public int wantedMipLevels() {
        return Math.min(fullChain(width, height, depth), maxLevel < 1000 ? maxLevel + 1 : 1000);
    }

    public int levelsToAllocate(int level, boolean sameShape, int baseW, int baseH, int baseD) {
        int n = Math.max(level + 1, 1);
        if (maxLevel < 1000) n = Math.max(n, maxLevel + 1);
        if (sameShape) n = Math.max(n, levels);
        return Math.min(n, fullChain(baseW, baseH, baseD));
    }

    public static int mipSourceExtent(int sourceSize, int destSize) {
        return Math.min(sourceSize, 2 * destSize);
    }

    /** Whether GL would sample this at all under a min filter: one using mipmaps needs every level down to the last, or the texture reads as black. */
    public boolean completeFor(int filter) {
        if (!mipmapRangeGiven(filter)) return false;
        if (filter == GL11.GL_NEAREST || filter == GL11.GL_LINEAR || immutable || target == GL31.GL_TEXTURE_BUFFER || target == GL32.GL_TEXTURE_2D_MULTISAMPLE || target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY) return true;
        final int base = viewBaseLevel();
        final int chain = base + fullChain(levelWidth(base), levelHeight(base), is3D() ? levelDepth(base) : 1);
        return levels >= Math.min(chain, maxLevel < 1000 ? maxLevel + 1 : chain);
    }

    private boolean mipmapRangeGiven(int filter) {
        if (missingLevels == 0) return true;
        final int base = viewBaseLevel();
        if (filter == GL11.GL_NEAREST || filter == GL11.GL_LINEAR) return (missingLevels >>> base & 1) == 0;
        final int last = Math.min(levels - 1, Math.min(maxLevel, 31));
        for (int level = base; level <= last; level++) {
            if ((missingLevels >>> level & 1) != 0) return false;
        }
        return true;
    }

    public boolean usesMipmaps() {
        return minFilter != GL11.GL_NEAREST && minFilter != GL11.GL_LINEAR;
    }

    public boolean isCube() {
        return target == GL13.GL_TEXTURE_CUBE_MAP || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY;
    }

    public boolean is3D() {
        return target == GL12.GL_TEXTURE_3D;
    }

    public int viewBaseLevel() {
        return Math.max(0, Math.min(baseLevel, levels - 1));
    }

    public int viewLevelCount() {
        final int base = viewBaseLevel();
        final int last = Math.min(levels - 1, Math.max(base, maxLevel));
        return last - base + 1;
    }
}
