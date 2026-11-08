package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;

import java.util.Arrays;

public final class SamplerObj {
    public final int id;
    public int minFilter = GL11.GL_NEAREST_MIPMAP_LINEAR, magFilter = GL11.GL_LINEAR;
    public int wrapS = GL11.GL_REPEAT, wrapT = GL11.GL_REPEAT, wrapR = GL11.GL_REPEAT;
    public float minLod = -1000f, maxLod = 1000f, lodBias;
    public int compareMode = GL11.GL_NONE, compareFunc = GL11.GL_LEQUAL;
    public float anisotropy = 1f;
    public final float[] border = new float[4];
    private final long[] samplers = new long[8];

    public SamplerObj(int id) {
        this.id = id;
    }

    /**
     * The native sampler already made for these parameters and a texture with these traits, or 0. A sampler object is bound
     * on hot paths such as chunk rendering, where deriving it every draw would allocate.
     */
    public long sampler(int textureTraits) {
        return samplers[textureTraits];
    }

    public void keepSampler(int textureTraits, long sampler) {
        samplers[textureTraits] = sampler;
    }

    public void forgetSamplers() {
        Arrays.fill(samplers, 0L);
    }
}
