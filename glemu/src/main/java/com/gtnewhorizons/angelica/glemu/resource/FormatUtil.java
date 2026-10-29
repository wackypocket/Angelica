package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

public final class FormatUtil {
    private FormatUtil() {}

    public static int sizedFor(int glFormat, int glType) {
        return switch (glFormat) {
            case GL11.GL_RGBA, GL12.GL_BGRA -> glType == GL11.GL_FLOAT ? GL30.GL_RGBA32F : glType == GL30.GL_HALF_FLOAT ? GL30.GL_RGBA16F : GL11.GL_RGBA8;
            case GL11.GL_RGB, GL12.GL_BGR -> glType == GL11.GL_FLOAT ? GL30.GL_RGB32F : glType == GL30.GL_HALF_FLOAT ? GL30.GL_RGB16F : GL11.GL_RGB8;
            case GL11.GL_RED -> glType == GL11.GL_FLOAT ? GL30.GL_R32F : GL30.GL_R8;
            case GL30.GL_RG -> glType == GL11.GL_FLOAT ? GL30.GL_RG32F : GL30.GL_RG8;
            default -> glFormat;
        };
    }

    public static int blockDim(Formats.Format f) {
        return f.isCompressed() ? 4 : 1;
    }

    public static long imageBytes(Formats.Format f, int w, int h) {
        if (f.isCompressed()) return (long) ((w + 3) / 4) * ((h + 3) / 4) * f.blockBytes();
        return (long) w * h * f.texelBytes();
    }
}
