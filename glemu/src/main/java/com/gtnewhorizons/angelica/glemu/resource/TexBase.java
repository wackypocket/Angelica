package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL40;

public abstract class TexBase {
    public final int id;
    public final boolean renderbuffer;
    public int target;

    public Formats.Format format;
    public int width, height, depth = 1, layers = 1;
    public int levels;
    public boolean immutable;
    public int definedLevels;

    public long image;
    public int usage;
    public boolean needsClear;
    public long lastUse;
    public long lastTransferWrite = -1;

    public long view;
    public boolean viewDirty = true;

    public int minFilter = GL11.GL_NEAREST_MIPMAP_LINEAR, magFilter = GL11.GL_LINEAR;
    public int wrapS = GL11.GL_REPEAT, wrapT = GL11.GL_REPEAT, wrapR = GL11.GL_REPEAT;
    public float minLod = -1000f, maxLod = 1000f, lodBias;
    public int baseLevel, maxLevel = 1000;
    public int compareMode = GL11.GL_NONE, compareFunc = GL11.GL_LEQUAL;
    public float anisotropy = 1f;
    public final float[] border = new float[4];
    public final int[] swizzle = {GL11.GL_RED, GL11.GL_GREEN, GL11.GL_BLUE, GL11.GL_ALPHA};
    public boolean sampleStencil;
    public long sampler;
    public boolean samplerDirty = true;

    public boolean pendingClear;
    public final float[] clearColor = new float[4];
    public float clearDepth;
    public int clearStencil;
    public int clearAspects;

    public String label;

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

    public boolean isShadowCompare() {
        return compareMode == GL14.GL_COMPARE_R_TO_TEXTURE;
    }
}
