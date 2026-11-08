package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Evaluators, selection, feedback and client array elements, which all end in ordinary vertices or in buffers the caller reads. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmLegacyDrawingTest {

    private static final int S = GlsmHeadlessRig.SIZE;

    /** Padded to 128 floats, since LWJGL checks evaluator and pixel map buffers against generous bounds, whatever the orders say. */
    private static FloatBuffer floats(float... values) {
        return BufferUtils.createFloatBuffer(Math.max(values.length, 128)).put(0, values);
    }

    @Test
    void aBezierPatchRasterizes() {
        GlsmHeadlessRig.clearTargetToBlack();
        final FloatBuffer corners = floats(-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, -0.5f, 0.5f, 0f, 0.5f, 0.5f, 0f);
        GL11.glMap2f(GL11.GL_MAP2_VERTEX_3, 0f, 1f, 3, 2, 0f, 1f, 6, 2, corners);
        GL11.glEnable(GL11.GL_MAP2_VERTEX_3);
        assertTrue(GL11.glIsEnabled(GL11.GL_MAP2_VERTEX_3));
        GL11.glMapGrid2f(4, 0f, 1f, 4, 0f, 1f);
        GL11.glColor4f(0f, 1f, 0f, 1f);
        GL11.glEvalMesh2(GL11.GL_FILL, 0, 4, 0, 4);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glDisable(GL11.GL_MAP2_VERTEX_3);
        GlsmHeadlessRig.assertTargetPixel(S / 2, S / 2, GlsmHeadlessRig.GREEN, "inside the patch");
        GlsmHeadlessRig.assertTargetPixel(4, 4, GlsmHeadlessRig.BLACK, "outside the patch");
    }

    @Test
    void aColorMapColorsItsVerticesAndLeavesTheCurrentColor() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glColor4f(0f, 0f, 1f, 1f);
        final FloatBuffer positions = floats(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f);
        final FloatBuffer colors = floats(1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f);
        GL11.glMap2f(GL11.GL_MAP2_VERTEX_3, 0f, 1f, 3, 2, 0f, 1f, 6, 2, positions);
        GL11.glMap2f(GL11.GL_MAP2_COLOR_4, 0f, 1f, 4, 2, 0f, 1f, 8, 2, colors);
        GL11.glEnable(GL11.GL_MAP2_VERTEX_3);
        GL11.glEnable(GL11.GL_MAP2_COLOR_4);
        GL11.glMapGrid2f(1, 0f, 1f, 1, 0f, 1f);
        GL11.glEvalMesh2(GL11.GL_FILL, 0, 1, 0, 1);
        GL11.glDisable(GL11.GL_MAP2_COLOR_4);
        GL11.glDisable(GL11.GL_MAP2_VERTEX_3);
        final FloatBuffer current = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, current);
        assertEquals(1f, current.get(2), 0.001f);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.RED, "the mapped color");
    }

    @Test
    void mapStateReadsBack() {
        GL11.glMap1f(GL11.GL_MAP1_VERTEX_3, 2f, 6f, 3, 2, floats(1f, 2f, 3f, 4f, 5f, 6f));
        final FloatBuffer out = BufferUtils.createFloatBuffer(6);
        GL11.glGetMapfv(GL11.GL_MAP1_VERTEX_3, GL11.GL_COEFF, out);
        assertEquals(5f, out.get(4), 0.001f);
        final IntBuffer order = BufferUtils.createIntBuffer(4);
        GL11.glGetMapiv(GL11.GL_MAP1_VERTEX_3, GL11.GL_ORDER, order);
        assertEquals(2, order.get(0));
        GL11.glGetMapfv(GL11.GL_MAP1_VERTEX_3, GL11.GL_DOMAIN, out);
        assertEquals(6f, out.get(1), 0.001f);
    }

    @Test
    void selectionReportsTheNamesOfWhatLandsInTheVolume() {
        final IntBuffer hits = BufferUtils.createIntBuffer(64);
        GL11.glSelectBuffer(hits);
        GL11.glRenderMode(GL11.GL_SELECT);
        GL11.glInitNames();
        GL11.glPushName(7);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex3f(-0.5f, -0.5f, 0f);
        GL11.glVertex3f(0.5f, -0.5f, 0f);
        GL11.glVertex3f(0f, 0.5f, 0f);
        GL11.glEnd();
        GL11.glLoadName(8);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex3f(5f, 5f, 0f);
        GL11.glVertex3f(6f, 5f, 0f);
        GL11.glVertex3f(5f, 6f, 0f);
        GL11.glEnd();
        GL11.glLoadName(9);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(-5f, -5f, 0f);
        GL11.glVertex3f(5f, -5f, 0f);
        GL11.glVertex3f(5f, 5f, 0f);
        GL11.glVertex3f(-5f, 5f, 0f);
        GL11.glEnd();
        assertEquals(2, GL11.glRenderMode(GL11.GL_RENDER));
        assertEquals(1, hits.get(0), "one name on the stack");
        assertEquals(7, hits.get(3));
        assertEquals(9, hits.get(7), "a quad larger than the volume has no vertex in it, and still hits");
    }

    @Test
    void selectionDrawsNothing() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glSelectBuffer(BufferUtils.createIntBuffer(16));
        GL11.glRenderMode(GL11.GL_SELECT);
        GlsmHeadlessRig.solidQuad(1f, 0f, 0f);
        GL11.glRenderMode(GL11.GL_RENDER);
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.BLACK, "nothing drawn");
    }

    @Test
    void feedbackCapturesImmediateVertices() {
        final FloatBuffer out = BufferUtils.createFloatBuffer(32);
        GL11.glFeedbackBuffer(GL11.GL_2D, out);
        GL11.glRenderMode(GL11.GL_FEEDBACK);
        GL11.glBegin(GL11.GL_POINTS);
        GL11.glVertex3f(0f, 0f, 0f);
        GL11.glEnd();
        assertEquals(3, GL11.glRenderMode(GL11.GL_RENDER));
        assertEquals(GL11.GL_POINT_TOKEN, (int) out.get(0));
        assertEquals(S / 2f, out.get(1), 0.5f);
    }

    /** Calls not redirected reach GLSM as bare pointers, which have no length: the draw decides how much is read. */
    @Test
    void clientArraysGivenAsPointersDraw() {
        GlsmHeadlessRig.clearTargetToBlack();
        final FloatBuffer positions = floats(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f);
        final ByteBuffer colors = BufferUtils.createByteBuffer(16);
        for (int i = 0; i < 4; i++) colors.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);
        colors.flip();
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.nglVertexPointer(2, GL11.GL_FLOAT, 0, org.lwjgl.system.MemoryUtil.memAddress(positions));
        GL11.nglColorPointer(4, GL11.GL_UNSIGNED_BYTE, 0, org.lwjgl.system.MemoryUtil.memAddress(colors));
        org.lwjgl.opengl.GL11C.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.RED, "drawn from client arrays");
        GlsmHeadlessRig.clearTargetToBlack();
        final ByteBuffer indices = BufferUtils.createByteBuffer(4).put(new byte[] {0, 1, 2, 3});
        indices.flip();
        org.lwjgl.opengl.GL11C.nglDrawElements(GL11.GL_TRIANGLE_FAN, 4, GL11.GL_UNSIGNED_BYTE, org.lwjgl.system.MemoryUtil.memAddress(indices));
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.RED, "drawn from client indices");
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }

    @Test
    void arrayElementsReadTheClientArrays() {
        GlsmHeadlessRig.clearTargetToBlack();
        final FloatBuffer positions = floats(-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 0, positions);
        GL11.glColor4f(0f, 0f, 1f, 1f);
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < 4; i++) GL11.glArrayElement(i);
        GL11.glEnd();
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.BLUE, "the quad");
    }

    @Test
    void aPixelMapRemapsAnImage() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glPixelMapfv(GL11.GL_PIXEL_MAP_R_TO_R, floats(0f, 0f).limit(2));
        GL11.glPixelMapfv(GL11.GL_PIXEL_MAP_G_TO_G, floats(1f, 1f).limit(2));
        GL11.glPixelMapfv(GL11.GL_PIXEL_MAP_B_TO_B, floats(0f, 0f).limit(2));
        GL11.glPixelMapfv(GL11.GL_PIXEL_MAP_A_TO_A, floats(1f, 1f).limit(2));
        final FloatBuffer back = BufferUtils.createFloatBuffer(32);
        GL11.glGetPixelMapfv(GL11.GL_PIXEL_MAP_G_TO_G, back);
        assertEquals(1f, back.get(1), 0.001f);
        GL11.glPixelTransferi(GL11.GL_MAP_COLOR, GL11.GL_TRUE);
        final ByteBuffer image = BufferUtils.createByteBuffer(16 * 4);
        for (int i = 0; i < 16; i++) image.put(i * 4, (byte) 255).put(i * 4 + 3, (byte) 255);
        GL11.glRasterPos2f(-1f, -1f);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);
        GL11.glPixelTransferi(GL11.GL_MAP_COLOR, GL11.GL_FALSE);
        GlsmHeadlessRig.assertTargetPixel(1, 1, GlsmHeadlessRig.GREEN, "red mapped to green");
        assertFalse(GL11.glIsEnabled(GL11.GL_AUTO_NORMAL));
    }

    @Test
    void arrayElementsCountInSelection() {
        final FloatBuffer positions = floats(-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0f, 0.5f, 0f);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 0, positions);
        final IntBuffer hits = BufferUtils.createIntBuffer(16);
        GL11.glSelectBuffer(hits);
        GL11.glRenderMode(GL11.GL_SELECT);
        GL11.glInitNames();
        GL11.glPushName(5);
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int i = 0; i < 3; i++) GL11.glArrayElement(i);
        GL11.glEnd();
        assertEquals(1, GL11.glRenderMode(GL11.GL_RENDER));
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        assertEquals(5, hits.get(3));
    }

    @Test
    void anEvaluatedVertexLeavesTheCurrentTexCoord() {
        GL11.glTexCoord2f(0.25f, 0.75f);
        GL11.glMap1f(GL11.GL_MAP1_VERTEX_3, 0f, 1f, 3, 2, floats(-1f, 0f, 0f, 1f, 0f, 0f));
        GL11.glMap1f(GL11.GL_MAP1_TEXTURE_COORD_2, 0f, 1f, 2, 2, floats(0f, 0f, 1f, 1f));
        GL11.glEnable(GL11.GL_MAP1_VERTEX_3);
        GL11.glEnable(GL11.GL_MAP1_TEXTURE_COORD_2);
        GL11.glBegin(GL11.GL_POINTS);
        GL11.glEvalCoord1f(0.5f);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_MAP1_TEXTURE_COORD_2);
        GL11.glDisable(GL11.GL_MAP1_VERTEX_3);
        final FloatBuffer current = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(GL11.GL_CURRENT_TEXTURE_COORDS, current);
        assertEquals(0.25f, current.get(0), 0.001f);
        assertEquals(0.75f, current.get(1), 0.001f);
    }

    @Test
    void namesCompiledIntoAListApplyOnReplay() {
        final int list = GL11.glGenLists(1);
        GL11.glNewList(list, GL11.GL_COMPILE);
        GL11.glLoadName(9);
        GL11.glEndList();
        final IntBuffer hits = BufferUtils.createIntBuffer(16);
        GL11.glSelectBuffer(hits);
        GL11.glRenderMode(GL11.GL_SELECT);
        GL11.glInitNames();
        GL11.glPushName(0);
        GL11.glCallList(list);
        GL11.glBegin(GL11.GL_POINTS);
        GL11.glVertex2f(0f, 0f);
        GL11.glEnd();
        assertEquals(1, GL11.glRenderMode(GL11.GL_RENDER));
        assertEquals(9, hits.get(3));
        GL11.glDeleteLists(list, 1);
    }

    @Test
    void aLegacyShaderReadsTheFixedFunctionInputs() {
        final int program = GlTestSupport.program("""
            #version 120
            varying vec4 tint;
            void main() {
                gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                gl_TexCoord[0] = gl_MultiTexCoord0;
                tint = gl_Color;
            }
            """, """
            #version 120
            uniform sampler2D tex;
            varying vec4 tint;
            void main() { gl_FragColor = tint * texture2D(tex, gl_TexCoord[0].st); }
            """);
        final int white = GlsmHeadlessRig.createSolidTexture(0xFFFFFFFF);
        try {
            GlsmHeadlessRig.clearTargetToBlack();
            GlsmHeadlessRig.bindBaseTexture(white);
            GLSMPrograms.glUseProgram(program);
            GlsmHeadlessRig.solidQuad(1f, 0f, 0f);
            GLSMPrograms.glUseProgram(0);
            GlsmHeadlessRig.assertTargetPixel(GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.SIZE / 2, GlsmHeadlessRig.RED, "the vertex color through the shader");
        } finally {
            GLSMTextures.glDeleteTextures(white);
            GLSMPrograms.glDeleteProgram(program);
        }
    }
}
