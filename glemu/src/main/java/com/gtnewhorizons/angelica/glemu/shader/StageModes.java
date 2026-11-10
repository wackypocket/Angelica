package com.gtnewhorizons.angelica.glemu.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

/** What a program's tessellation and geometry stages declare in their layouts, as glGetProgramiv reports it. Unset values are GL's defaults. */
public final class StageModes {
    private static final int INVOCATIONS = 0, SPACING_EQUAL = 1, SPACING_EVEN = 2, SPACING_ODD = 3, ORDER_CW = 4, ORDER_CCW = 5, POINT_MODE = 10,
        INPUT_POINTS = 19, INPUT_LINES = 20, INPUT_LINES_ADJACENCY = 21, TRIANGLES = 22, INPUT_TRIANGLES_ADJACENCY = 23, QUADS = 24, ISOLINES = 25,
        OUTPUT_VERTICES = 26, OUTPUT_POINTS = 27, OUTPUT_LINE_STRIP = 28, OUTPUT_TRIANGLE_STRIP = 29;

    public boolean tessControl, tessEvaluation, geometry;
    public int tessOutputVertices;
    public int tessMode, tessSpacing = GL11.GL_EQUAL, tessOrder = GL11.GL_CCW;
    public boolean tessPointMode;
    public int geometryVerticesOut, geometryInput, geometryOutput, geometryInvocations = 1;

    public void read(int slot, Spirv spirv) {
        tessControl |= slot == ProgramBase.TCS;
        tessEvaluation |= slot == ProgramBase.TES;
        geometry |= slot == ProgramBase.GS;
        final int[] w = spirv.words;
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) != Spirv.OP_EXECUTION_MODE) continue;
            final int operand = (w[i] >>> 16) > 3 ? w[i + 3] : 0;
            switch (slot) {
                case ProgramBase.TCS -> { if (w[i + 2] == OUTPUT_VERTICES) tessOutputVertices = operand; }
                case ProgramBase.TES -> readEvaluation(w[i + 2]);
                case ProgramBase.GS -> readGeometry(w[i + 2], operand);
                default -> {}
            }
        }
    }

    private void readEvaluation(int mode) {
        switch (mode) {
            case TRIANGLES -> tessMode = GL11.GL_TRIANGLES;
            case QUADS -> tessMode = GL11.GL_QUADS;
            case ISOLINES -> tessMode = GL40.GL_ISOLINES;
            case SPACING_EQUAL -> tessSpacing = GL11.GL_EQUAL;
            case SPACING_EVEN -> tessSpacing = GL40.GL_FRACTIONAL_EVEN;
            case SPACING_ODD -> tessSpacing = GL40.GL_FRACTIONAL_ODD;
            case ORDER_CW -> tessOrder = GL11.GL_CW;
            case ORDER_CCW -> tessOrder = GL11.GL_CCW;
            case POINT_MODE -> tessPointMode = true;
            default -> {}
        }
    }

    private void readGeometry(int mode, int operand) {
        switch (mode) {
            case INVOCATIONS -> geometryInvocations = operand;
            case OUTPUT_VERTICES -> geometryVerticesOut = operand;
            case INPUT_POINTS -> geometryInput = GL11.GL_POINTS;
            case INPUT_LINES -> geometryInput = GL11.GL_LINES;
            case INPUT_LINES_ADJACENCY -> geometryInput = GL32.GL_LINES_ADJACENCY;
            case TRIANGLES -> geometryInput = GL11.GL_TRIANGLES;
            case INPUT_TRIANGLES_ADJACENCY -> geometryInput = GL32.GL_TRIANGLES_ADJACENCY;
            case OUTPUT_POINTS -> geometryOutput = GL11.GL_POINTS;
            case OUTPUT_LINE_STRIP -> geometryOutput = GL11.GL_LINE_STRIP;
            case OUTPUT_TRIANGLE_STRIP -> geometryOutput = GL11.GL_TRIANGLE_STRIP;
            default -> {}
        }
    }
}
