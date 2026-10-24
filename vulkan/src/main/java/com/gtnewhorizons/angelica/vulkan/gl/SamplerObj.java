package com.gtnewhorizons.angelica.vulkan.gl;

import org.lwjgl.opengl.GL11;

public final class SamplerObj {
    public final int id;
    public int minFilter = GL11.GL_NEAREST_MIPMAP_LINEAR, magFilter = GL11.GL_LINEAR;
    public int wrapS = GL11.GL_REPEAT, wrapT = GL11.GL_REPEAT, wrapR = GL11.GL_REPEAT;
    public float minLod = -1000f, maxLod = 1000f, lodBias;
    public int compareMode = GL11.GL_NONE, compareFunc = GL11.GL_LEQUAL;
    public float anisotropy = 1f;
    public final float[] border = new float[4];
    public long sampler;
    public boolean dirty = true;

    public SamplerObj(int id) {
        this.id = id;
    }
}
