package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Images drawn at the raster position, and the accumulation buffer, which nothing but these calls can reach. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmLegacyPixelsTest {

    private static final int S = GlsmHeadlessRig.SIZE;

    private static ByteBuffer solid(int size, int r, int g, int b) {
        final ByteBuffer image = BufferUtils.createByteBuffer(size * size * 4);
        for (int i = 0; i < size * size; i++) {
            image.put(i * 4, (byte) r).put(i * 4 + 1, (byte) g).put(i * 4 + 2, (byte) b).put(i * 4 + 3, (byte) 255);
        }
        return image;
    }

    @AfterEach
    void reset() {
        GL11.glPixelZoom(1f, 1f);
        GL11.glPixelTransferf(GL11.GL_RED_SCALE, 1f);
        GL14.glWindowPos2i(0, 0);
    }

    @Test
    void stencilPixelsReachTheStencilBuffer() {
        final int fbo = GlsmHeadlessRig.createDepthStencilFbo();
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GLSMRasterizer.glViewport(0, 0, GlsmHeadlessRig.SIZE, GlsmHeadlessRig.SIZE);
        GL11.glClearStencil(0);
        GLSMFramebuffer.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        final ByteBuffer values = BufferUtils.createByteBuffer(16);
        for (int i = 0; i < 16; i++) values.put(i, (byte) (i < 8 ? 7 : 9));
        GL14.glWindowPos2i(0, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glDrawPixels(4, 4, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, values);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        assertEquals(7, stencilAt(1, 1));
        assertEquals(9, stencilAt(1, 3));
        assertEquals(0, stencilAt(8, 8), "outside the image");
        GlsmHeadlessRig.bindTarget();
    }

    private static int stencilAt(int x, int y) {
        final ByteBuffer out = BufferUtils.createByteBuffer(4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, out);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        return out.get(0) & 0xFF;
    }

    @Test
    void drawPixelsLandsAtTheWindowPosition() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL14.glWindowPos2i(8, 8);
        GL11.glDrawPixels(8, 8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solid(8, 255, 0, 0));
        GlsmHeadlessRig.assertTargetPixel(10, 10, GlsmHeadlessRig.RED, "inside the image");
        GlsmHeadlessRig.assertTargetPixel(20, 20, GlsmHeadlessRig.BLACK, "outside the image");
    }

    @Test
    void rasterPosGoesThroughTheMatrices() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glRasterPos2f(0f, 0f);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solid(4, 0, 255, 0));
        GlsmHeadlessRig.assertTargetPixel(S / 2 + 1, S / 2 + 1, GlsmHeadlessRig.GREEN, "above and right of the center");
        GlsmHeadlessRig.assertTargetPixel(S / 2 - 2, S / 2 - 2, GlsmHeadlessRig.BLACK, "below and left of the center");
    }

    @Test
    void aRasterPositionOutsideTheViewDrawsNothing() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glRasterPos2f(5f, 5f);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solid(4, 0, 255, 0));
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.BLACK, "nothing drawn");
    }

    @Test
    void aNegativeZoomFlipsDownwardAndIsNotCulled() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL14.glWindowPos2i(16, 32);
        GL11.glPixelZoom(2f, -2f);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solid(4, 0, 0, 255));
        GL11.glDisable(GL11.GL_CULL_FACE);
        GlsmHeadlessRig.assertTargetPixel(20, 28, GlsmHeadlessRig.BLUE, "below the raster position");
        GlsmHeadlessRig.assertTargetPixel(20, 36, GlsmHeadlessRig.BLACK, "above the raster position");
    }

    @Test
    void pixelTransferScaleTintsTheImage() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glPixelTransferf(GL11.GL_RED_SCALE, 0f);
        GL14.glWindowPos2i(0, 0);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solid(4, 255, 255, 0));
        GlsmHeadlessRig.assertTargetPixel(1, 1, GlsmHeadlessRig.GREEN, "red scaled away");
    }

    @Test
    void bitmapPaintsSetBitsInTheRasterColorAndMoves() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glColor4f(1f, 0f, 0f, 1f);
        GL14.glWindowPos2i(8, 8);
        final ByteBuffer bits = BufferUtils.createByteBuffer(8);
        for (int row = 0; row < 8; row++) bits.put(row, (byte) 0xF0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glBitmap(8, 8, 0f, 0f, 16f, 0f, bits);
        GL11.glColor4f(0f, 1f, 0f, 1f);
        GL14.glWindowPos2i(24, 8);
        GL11.glBitmap(8, 8, 0f, 0f, 0f, 0f, bits);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GlsmHeadlessRig.assertTargetPixel(9, 9, GlsmHeadlessRig.RED, "a set bit");
        GlsmHeadlessRig.assertTargetPixel(13, 9, GlsmHeadlessRig.BLACK, "a clear bit");
        GlsmHeadlessRig.assertTargetPixel(25, 9, GlsmHeadlessRig.GREEN, "the second bitmap");
    }

    @Test
    void imagesLeaveFixedFunctionDrawingInPlace() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL14.glWindowPos2i(0, 0);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solid(4, 255, 0, 0));
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solid(4, 255, 0, 0));
        assertEquals(0, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        GlsmHeadlessRig.solidQuad(0f, 1f, 0f);
        GlsmHeadlessRig.assertUniform(GlsmHeadlessRig.readTarget(), GlsmHeadlessRig.GREEN, "a quad after the images");
    }

    @Test
    void theCallersTextureSurvivesAnImage() {
        GlsmHeadlessRig.clearTargetToBlack();
        final int texture = GlsmHeadlessRig.newNearestTexture();
        GL14.glWindowPos2i(0, 0);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solid(4, 255, 0, 0));
        assertEquals(texture, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        GL11.glDeleteTextures(texture);
    }

    @Test
    void copyPixelsCopiesInsideTheTarget() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL14.glWindowPos2i(0, 0);
        GL11.glDrawPixels(8, 8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, solid(8, 255, 0, 0));
        GL14.glWindowPos2i(32, 32);
        GL11.glCopyPixels(0, 0, 8, 8, GL11.GL_COLOR);
        GlsmHeadlessRig.assertTargetPixel(34, 34, GlsmHeadlessRig.RED, "the copy");
        GlsmHeadlessRig.assertTargetPixel(20, 20, GlsmHeadlessRig.BLACK, "between them");
    }

    @Test
    void drawPixelsWritesDepth() {
        GlsmHeadlessRig.clearTargetToBlack();
        GL11.glDepthMask(true);
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        final FloatBuffer depth = BufferUtils.createFloatBuffer(16);
        for (int i = 0; i < 16; i++) depth.put(i, 0.25f);
        GL14.glWindowPos2i(0, 0);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        GL11.glDrawPixels(4, 4, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        final FloatBuffer out = BufferUtils.createFloatBuffer(1);
        GL11.glReadPixels(1, 1, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, out);
        assertEquals(0.25f, out.get(0), 0.001f);
    }

    @Test
    void accumulationAddsScalesAndReturns() {
        GlsmHeadlessRig.bindTarget();
        GL11.glClearAccum(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_ACCUM_BUFFER_BIT);
        GlsmHeadlessRig.clearTo(1f, 0f, 0f, 1f);
        GL11.glAccum(GL11.GL_ACCUM, 0.5f);
        GlsmHeadlessRig.clearTo(0f, 1f, 0f, 1f);
        GL11.glAccum(GL11.GL_ACCUM, 0.5f);
        GlsmHeadlessRig.clearTo(0f, 0f, 0f, 1f);
        GL11.glAccum(GL11.GL_RETURN, 1f);
        final int[] pixels = GlsmHeadlessRig.readTarget();
        final int center = GlsmHeadlessRig.pixelAt(pixels, S, S / 2, S / 2);
        assertEquals(0x80, center >> 16 & 0xFF, 2, "half of the red frame");
        assertEquals(0x80, center >> 8 & 0xFF, 2, "half of the green frame");
    }

    @Test
    void polygonStippleRoundTrips() {
        final ByteBuffer pattern = BufferUtils.createByteBuffer(128);
        pattern.put(5, (byte) 0x5A);
        GL11.glPolygonStipple(pattern);
        final ByteBuffer out = BufferUtils.createByteBuffer(128);
        GL11.glGetPolygonStipple(out);
        assertEquals((byte) 0x5A, out.get(5));
    }

    @Test
    void aCompiledListDrawsItsPixelsOnReplayOnly() {
        GlsmHeadlessRig.clearTargetToBlack();
        final int list = GL11.glGenLists(1);
        final ByteBuffer image = solid(4, 0, 255, 0);
        GL11.glNewList(list, GL11.GL_COMPILE);
        GL14.glWindowPos2i(8, 8);
        GL11.glDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);
        GL11.glEndList();
        GlsmHeadlessRig.assertTargetPixel(9, 9, GlsmHeadlessRig.BLACK, "compiling draws nothing");
        for (int i = 0; i < image.capacity(); i++) image.put(i, (byte) 0);
        GL11.glCallList(list);
        GlsmHeadlessRig.assertTargetPixel(9, 9, GlsmHeadlessRig.GREEN, "the pixels as they were at compile time");
        GL11.glDeleteLists(list, 1);
    }

    @Test
    void aCompiledListMovesTheRasterPositionOnReplay() {
        GlsmHeadlessRig.clearTargetToBlack();
        final ByteBuffer bits = BufferUtils.createByteBuffer(8);
        for (int row = 0; row < 8; row++) bits.put(row, (byte) 0xFF);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        final int list = GL11.glGenLists(1);
        GL11.glNewList(list, GL11.GL_COMPILE);
        GL11.glBitmap(8, 8, 0f, 0f, 16f, 0f, bits);
        GL11.glEndList();
        GL11.glColor4f(0f, 1f, 0f, 1f);
        GL14.glWindowPos2i(0, 0);
        GL11.glCallList(list);
        GL11.glCallList(list);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glDeleteLists(list, 1);
        final int[] pixels = GlsmHeadlessRig.readTarget();
        GlsmHeadlessRig.assertPixel(pixels, S, 2, 2, GlsmHeadlessRig.GREEN, "the first replay");
        GlsmHeadlessRig.assertPixel(pixels, S, 18, 2, GlsmHeadlessRig.GREEN, "the second replay, moved on");
        GlsmHeadlessRig.assertPixel(pixels, S, 10, 2, GlsmHeadlessRig.BLACK, "the gap between them");
    }

    @Test
    void pixelsCanComeFromAnUnpackBuffer() {
        GlsmHeadlessRig.clearTargetToBlack();
        final int buffer = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
        GL15C.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, solid(4, 0, 0, 255), GL15C.GL_STATIC_DRAW);
        GL14.glWindowPos2i(0, 0);
        GL11.nglDrawPixels(4, 4, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL15C.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL15C.glDeleteBuffers(buffer);
        GlsmHeadlessRig.assertTargetPixel(1, 1, GlsmHeadlessRig.BLUE, "the buffer's image");
    }

    @Test
    void aBitmapHonorsUnpackSkips() {
        GlsmHeadlessRig.clearTargetToBlack();
        final ByteBuffer bits = BufferUtils.createByteBuffer(2);
        bits.put(0, (byte) 0x00).put(1, (byte) 0x80);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 1);
        GL11.glColor4f(0f, 1f, 0f, 1f);
        GL14.glWindowPos2i(4, 4);
        GL11.glBitmap(1, 1, 0f, 0f, 0f, 0f, bits);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlsmHeadlessRig.assertTargetPixel(4, 4, GlsmHeadlessRig.GREEN, "the bit in the second row");
    }

    @Test
    void accumulationReturnsPastDepthAndLeavesItAlone() {
        GlsmHeadlessRig.bindTarget();
        GlsmHeadlessRig.clearTo(0f, 1f, 0f, 1f);
        GL11.glAccum(GL11.GL_LOAD, 1f);
        GL11.glClearDepth(0.5);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_NEVER);
        GL11.glAccum(GL11.GL_RETURN, 1f);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glClearDepth(1.0);
        final FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        GL11.glReadPixels(1, 1, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
        assertEquals(0.5f, depth.get(0), 0.001f, "depth untouched");
        GlsmHeadlessRig.assertTargetPixel(1, 1, GlsmHeadlessRig.GREEN, "color returned despite the depth test");
    }
}
