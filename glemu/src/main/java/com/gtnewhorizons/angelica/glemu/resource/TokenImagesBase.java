package com.gtnewhorizons.angelica.glemu.resource;

import com.gtnewhorizons.angelica.glemu.gl.NativeTokens;

/** Image operations in {@link NativeTokens}. */
public abstract class TokenImagesBase<T extends TokenTex> {
    public long generation;

    public abstract void create(T t, boolean storage);

    public abstract void release(T t);

    public abstract boolean filterable(int fmt);

    public abstract boolean sampleable(int fmt);

    public abstract void clearImage(T t, float[] color, float depth, int stencil, int aspects);

    public void clearImage(T t, float[] color, float depth, int stencil) {
        clearImage(t, color, depth, stencil, t.format.aspect());
    }

    public boolean renderable(int fmt) {
        return !isCompressed(fmt) && fmt != NativeTokens.PF_RGB9E5_FLOAT && fmt != NativeTokens.PF_INVALID;
    }

    public static boolean isCompressed(int fmt) {
        return fmt >= NativeTokens.PF_BC1_RGBA && fmt <= NativeTokens.PF_BC7_RGBA_SRGB;
    }
}
