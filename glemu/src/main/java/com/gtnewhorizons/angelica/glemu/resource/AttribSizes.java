package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

public final class AttribSizes {
    private AttribSizes() {}

    public static int typeBytes(int type) {
        return switch (type) {
            case GL11.GL_BYTE, GL11.GL_UNSIGNED_BYTE -> 1;
            case GL11.GL_SHORT, GL11.GL_UNSIGNED_SHORT, GL30.GL_HALF_FLOAT -> 2;
            case GL11.GL_DOUBLE -> 8;
            default -> 4;
        };
    }

    public static int attribBytes(int size, int type) {
        if (type == GL12.GL_UNSIGNED_INT_2_10_10_10_REV || type == GL33.GL_INT_2_10_10_10_REV || type == GL30.GL_UNSIGNED_INT_10F_11F_11F_REV) return 4;
        if (size == GL12.GL_BGRA) return 4;
        return size * typeBytes(type);
    }
}
