package com.gtnewhorizons.angelica.metal.pipeline;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;

public final class VertexFormats {
    private VertexFormats() {}
    public static boolean exact(int type, boolean normalized) {
        return switch (type) {
            case GL12.GL_UNSIGNED_INT_2_10_10_10_REV, GL33.GL_INT_2_10_10_10_REV -> normalized;
            case GL11.GL_DOUBLE, GL41.GL_FIXED -> false;
            default -> true;
        };
    }
}
