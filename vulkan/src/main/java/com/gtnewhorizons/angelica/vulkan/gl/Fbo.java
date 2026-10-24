package com.gtnewhorizons.angelica.vulkan.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class Fbo {
    public static final int MAX_COLOR = 8;

    public final int id;
    public final int[] colorTex = new int[MAX_COLOR];
    public final boolean[] colorRbo = new boolean[MAX_COLOR];
    public final int[] colorLevel = new int[MAX_COLOR];
    public final int[] colorLayer = new int[MAX_COLOR];
    public int depthTex, depthLevel, depthLayer;
    public boolean depthRbo;
    public int stencilTex, stencilLevel, stencilLayer;
    public boolean stencilRbo;
    public final boolean[] colorLayered = new boolean[MAX_COLOR];
    public boolean depthLayered;

    public int[] drawBuffers = {0};
    public int readBuffer = 0;
    public int gen;

    public Fbo(int id) {
        this.id = id;
        if (id == 0) {
            drawBuffers = new int[] {0};
            readBuffer = 0;
        }
    }

    public static int attachmentIndex(int glAttachment) {
        if (glAttachment >= GL30.GL_COLOR_ATTACHMENT0 && glAttachment < GL30.GL_COLOR_ATTACHMENT0 + MAX_COLOR) return glAttachment - GL30.GL_COLOR_ATTACHMENT0;
        return switch (glAttachment) {
            case GL11.GL_BACK, GL11.GL_FRONT, GL11.GL_BACK_LEFT, GL11.GL_FRONT_LEFT, GL11.GL_FRONT_AND_BACK, GL11.GL_LEFT, GL11.GL_COLOR -> 0;
            default -> -1;
        };
    }
}
