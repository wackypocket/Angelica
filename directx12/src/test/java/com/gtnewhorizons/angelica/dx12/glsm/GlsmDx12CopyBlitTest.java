package com.gtnewhorizons.angelica.dx12.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.IntBinaryOperator;
import java.util.stream.Stream;

import static com.gtnewhorizons.angelica.dx12.glsm.GlsmDx12HeadlessRig.BLUE;
import static com.gtnewhorizons.angelica.dx12.glsm.GlsmDx12HeadlessRig.GREEN;
import static com.gtnewhorizons.angelica.dx12.glsm.GlsmDx12HeadlessRig.RED;
import static com.gtnewhorizons.angelica.dx12.glsm.GlsmDx12HeadlessRig.SIZE;

@Tag("glsm-dx12")
@ExtendWith(GlsmDx12FrameExtension.class)
class GlsmDx12CopyBlitTest {

    private static final int SCISSOR_ROWS = 16;
    private static final byte SENTINEL_BYTE = 0x7B;
    private static final int SENTINEL = 0x7B7B7B7B;

    @BeforeAll
    static void installFinalTarget() {
        GlsmDx12HeadlessRig.installFinalTarget(SIZE, SIZE);
    }

    @AfterAll
    static void dropFinalTarget() {
        GlsmDx12HeadlessRig.dropFinalTarget();
    }

    private static void assertRow(int[] pixels, int y, int expected, String label) {
        for (int x = 0; x < SIZE; x += 8) {
            GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, x, y, expected, label + " row " + y);
        }
    }

    @Test
    void scissorOnTheUserFboClipsTheGlBottomRows() {
        GlsmDx12HeadlessRig.bindTarget();
        GlsmDx12HeadlessRig.clearTo(0.0f, 0.0f, 1.0f, 1.0f);

        GLSMEnable.glEnable(GL11.GL_SCISSOR_TEST);
        GLSMRasterizer.glScissor(0, 0, SIZE, SCISSOR_ROWS);
        GlsmDx12HeadlessRig.solidQuad(1.0f, 0.0f, 0.0f);
        GLSMEnable.glDisable(GL11.GL_SCISSOR_TEST);

        final int[] pixels = GlsmDx12HeadlessRig.readTarget();
        assertRow(pixels, 0, RED, "inside the scissor");
        assertRow(pixels, SCISSOR_ROWS - 1, RED, "inside the scissor");
        assertRow(pixels, SCISSOR_ROWS, BLUE, "outside the scissor");
        assertRow(pixels, SIZE - 1, BLUE, "outside the scissor");
    }

    private static int createTwoLevelTexture(int level0Argb, int level1Argb) {
        final int id = GLSMTextures.glGenTextures();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 1);
        GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0.0f);
        GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, 1.0f);
        GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0.0f);
        GlsmDx12HeadlessRig.uploadSolidLevel(0, SIZE, level0Argb);
        GlsmDx12HeadlessRig.uploadSolidLevel(1, SIZE / 2, level1Argb);
        return id;
    }

    private static int copyGreenIntoLevelOne() {
        GlsmDx12HeadlessRig.bindTarget();
        GlsmDx12HeadlessRig.clearTo(0.0f, 0.0f, 0.0f, 1.0f);
        GlsmDx12HeadlessRig.solidQuad(0.0f, 1.0f, 0.0f);

        final int texture = createTwoLevelTexture(RED, BLUE);
        GLSMTextures.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 1, 0, 0, 0, 0, SIZE / 2, SIZE / 2);
        return texture;
    }

    @Test
    void copyTexSubImage2DWritesTheRequestedMipLevel() {
        final int texture = copyGreenIntoLevelOne();

        GlsmDx12HeadlessRig.bindTarget();
        GlsmDx12HeadlessRig.clearTo(0.0f, 0.0f, 0.0f, 1.0f);
        GlsmDx12HeadlessRig.texturedQuad(texture, 0.5f);

        final int[] pixels = GlsmDx12HeadlessRig.readTarget();
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 2, SIZE / 2, GREEN, "minified sample of level 1");
    }

    @Test
    void copyTexSubImage2DLeavesTheOtherMipLevelsUntouched() {
        final int texture = copyGreenIntoLevelOne();

        GlsmDx12HeadlessRig.bindTarget();
        GlsmDx12HeadlessRig.clearTo(0.0f, 0.0f, 0.0f, 1.0f);
        GlsmDx12HeadlessRig.texturedQuad(texture, 1.0f);

        final int[] pixels = GlsmDx12HeadlessRig.readTarget();
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 4, SIZE / 4, RED, "magnified sample of the level 0 region the copy would land in");
    }

    @Test
    void readPixelsCrossingTheTopEdgeReturnsTheInRangeRows() {
        GlsmDx12HeadlessRig.bindTarget();
        GlsmDx12HeadlessRig.clearTo(0.0f, 0.0f, 1.0f, 1.0f);
        GlsmDx12HeadlessRig.halfQuad(0.0f, 1.0f, 0.0f, 1.0f, 0.0f);

        final int[] pixels = GlsmDx12HeadlessRig.readTarget(0, SIZE / 2, SIZE, SIZE, SENTINEL_BYTE);
        assertRow(pixels, 0, GREEN, "first in-range row");
        assertRow(pixels, SIZE / 2 - 1, GREEN, "last in-range row");
        assertRow(pixels, SIZE / 2, SENTINEL, "first out-of-range row");
        assertRow(pixels, SIZE - 1, SENTINEL, "last out-of-range row");
    }

    @Test
    void blitFramebufferWithColorAndDepthBitsMovesColor() {
        GlsmDx12HeadlessRig.bindTarget();
        GlsmDx12HeadlessRig.clearTo(0.0f, 1.0f, 0.0f, 1.0f);
        GlsmDx12HeadlessRig.solidQuad(0.0f, 1.0f, 0.0f);

        final int destination = GlsmDx12HeadlessRig.createDepthStencilFbo();
        GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
        GLSMBlend.glClearColor(0.0f, 0.0f, 1.0f, 1.0f);
        GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GlsmDx12HeadlessRig.solidQuad(0.0f, 0.0f, 1.0f);

        GlsmDx12HeadlessRig.bindTarget();
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination);
        GLSMFramebuffer.glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE,
            GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, destination);
        final int[] pixels = GlsmDx12HeadlessRig.readTarget();
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 2, SIZE / 2, GREEN, "color aspect of a color+depth blit");
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, 4, SIZE - 5, GREEN, "color aspect of a color+depth blit");
    }

    private static void blitLevelOneInto(int dstSize, int dstLevel) {
        final int source = GlsmDx12HeadlessRig.createSolidMipTexture(SIZE, 1, RED);
        GlsmDx12HeadlessRig.uploadSolidLevel(1, SIZE / 2, GREEN);
        final int destination = GlsmDx12HeadlessRig.createSolidMipTexture(dstSize, dstLevel, BLUE);
        final int readFbo = GlsmDx12HeadlessRig.fboWithColor(source, 1);
        final int drawFbo = GlsmDx12HeadlessRig.fboWithColor(destination, dstLevel);

        final int dstExtent = Math.max(1, dstSize >> dstLevel);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GLSMFramebuffer.glBlitFramebuffer(0, 0, SIZE / 2, SIZE / 2, 0, 0, dstExtent, dstExtent, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        GlsmDx12HeadlessRig.assertUniform(GlsmDx12HeadlessRig.readTextureLevel(destination, dstLevel), GREEN, "destination level " + dstLevel + " after blitting source level 1");
        GlsmDx12HeadlessRig.assertUniform(GlsmDx12HeadlessRig.readTextureLevel(source, 0), RED, "source level 0 is not the blit source");
        if (dstLevel != 0) GlsmDx12HeadlessRig.assertUniform(GlsmDx12HeadlessRig.readTextureLevel(destination, 0), BLUE, "destination level 0 is not the blit target");

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GLSMFramebuffer.glDeleteFramebuffers(readFbo);
        GLSMFramebuffer.glDeleteFramebuffers(drawFbo);
        GLSMTextures.glDeleteTextures(source);
        GLSMTextures.glDeleteTextures(destination);
    }

    @Test
    void attachingToTheReadFramebufferLeavesTheDrawFramebufferAlone() {
        final int source = GlsmDx12HeadlessRig.createSolidTexture(GREEN);
        final int drawColor = GlsmDx12HeadlessRig.createSolidTexture(BLUE);
        final int readFbo = GLSMFramebuffer.glGenFramebuffers();
        final int drawFbo = GlsmDx12HeadlessRig.fboWithColor(drawColor, 0);
        try {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, source, 0);
            GLSMFramebuffer.glBlitFramebuffer(0, 0, 2, 2, 0, 0, 2, 2, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            GlsmDx12HeadlessRig.assertUniform(GlsmDx12HeadlessRig.readTextureLevel(drawColor, 0), GREEN, "the blit reads the read framebuffer's attachment");

            GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GLSMBlend.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
            GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GlsmDx12HeadlessRig.assertUniform(GlsmDx12HeadlessRig.readTextureLevel(drawColor, 0), RED, "the draw framebuffer kept its own attachment");
            GlsmDx12HeadlessRig.assertUniform(GlsmDx12HeadlessRig.readTextureLevel(source, 0), GREEN, "the read attachment never became a draw target");
        } finally {
            GLSMBlend.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(readFbo);
            GLSMFramebuffer.glDeleteFramebuffers(drawFbo);
            GLSMTextures.glDeleteTextures(source);
            GLSMTextures.glDeleteTextures(drawColor);
        }
    }

    static Stream<Arguments> levelBlits() {
        return Stream.of(
            Arguments.of("1:1 into level 0: reads the source attachment level", SIZE / 2, 0),
            Arguments.of("scaled into level 0: reads the source attachment level", SIZE, 0),
            Arguments.of("into level 1: writes the destination attachment level", SIZE, 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("levelBlits")
    void blitFramebufferHonoursAttachmentLevels(String name, int dstSize, int dstLevel) {
        blitLevelOneInto(dstSize, dstLevel);
    }

    @Test
    void blitFramebufferIntoFbo0SurvivesTheFramesFirstFbo0Pass() {
        GlsmDx12HeadlessRig.bindTarget();
        GlsmDx12HeadlessRig.clearTo(0.0f, 1.0f, 0.0f, 1.0f);
        GlsmDx12HeadlessRig.solidQuad(0.0f, 1.0f, 0.0f);

        GLSMBlend.glClearColor(0.0f, 0.0f, 1.0f, 1.0f);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        GLSMFramebuffer.glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
        GLSMBlend.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        GLSMEnable.glEnable(GL11.GL_SCISSOR_TEST);
        GLSMRasterizer.glScissor(0, 0, 4, 4);
        GlsmDx12HeadlessRig.solidQuad(1.0f, 0.0f, 0.0f);
        GLSMEnable.glDisable(GL11.GL_SCISSOR_TEST);

        final int[] pixels = GlsmDx12HeadlessRig.readTarget();
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 2, SIZE / 2, GREEN, "blit into fbo0 survived the first fbo0 pass");
    }

    @Test
    void blitFramebufferFromFbo0ToTheUserFboKeepsGlOrientation() {
        GlsmDx12HeadlessRig.bindTarget();
        GlsmDx12HeadlessRig.clearTo(0.0f, 0.0f, 0.0f, 1.0f);
        GlsmDx12HeadlessRig.solidQuad(0.0f, 0.0f, 0.0f);

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
        GLSMBlend.glClearColor(0.0f, 0.0f, 1.0f, 1.0f);
        GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GlsmDx12HeadlessRig.halfQuad(-1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        GlsmDx12HeadlessRig.halfQuad(0.0f, 1.0f, 1.0f, 0.0f, 0.0f);

        GlsmDx12HeadlessRig.bindTarget();
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GLSMFramebuffer.glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        GlsmDx12HeadlessRig.bindTarget();
        final int[] pixels = GlsmDx12HeadlessRig.readTarget();
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 2, 4, GREEN, "blitted fbo0 bottom");
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 2, SIZE - 5, RED, "blitted fbo0 top");
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 2, SIZE / 2 - 4, GREEN, "blitted fbo0 below the split");
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 2, SIZE / 2 + 4, RED, "blitted fbo0 above the split");
    }

    @Test
    void blitFramebufferStencilSurvivesTheDestinationsFirstDraw() {
        GlsmDx12HeadlessRig.bindTarget();
        GlsmDx12HeadlessRig.writeStencilOneInBottomHalf();

        final int destination = GlsmDx12HeadlessRig.createDepthStencilFbo();
        GLSMBlend.glClearColor(0.0f, 0.0f, 1.0f, 1.0f);
        GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        GlsmDx12HeadlessRig.bindTarget();
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination);
        GLSMFramebuffer.glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT, GL11.GL_NEAREST);

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, destination);
        GlsmDx12HeadlessRig.drawRedWhereStencilIsOne();

        final int[] pixels = GlsmDx12HeadlessRig.readTarget();
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 2, 4, RED, "stencil carried by the blit");
        GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 2, SIZE - 5, BLUE, "outside the blitted stencil");
    }

    private static final int UNTOUCHED = 0xFF101010;
    private static final int CELL = 8;

    private static int pattern(int x, int y) {
        return 0xFF000040 | ((x / CELL) * 32 << 16) | ((y / CELL) * 32 << 8);
    }

    private static int createPatternTexture() {
        final int id = GlsmDx12HeadlessRig.newNearestTexture();
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        final ByteBuffer texels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    GlsmDx12HeadlessRig.putArgb(texels, y * SIZE + x, pattern(x, y));
                }
            }
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
        } finally {
            MemoryUtil.memFree(texels);
        }
        return id;
    }

    private static int[] blitPattern(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1) {
        final int source = createPatternTexture();
        final int destination = GlsmDx12HeadlessRig.createSolidMipTexture(SIZE, 0, UNTOUCHED);
        final int readFbo = GlsmDx12HeadlessRig.fboWithColor(source, 0);
        final int drawFbo = GlsmDx12HeadlessRig.fboWithColor(destination, 0);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GLSMFramebuffer.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        final int[] pixels = GlsmDx12HeadlessRig.readTextureLevel(destination, 0);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GLSMFramebuffer.glDeleteFramebuffers(readFbo);
        GLSMFramebuffer.glDeleteFramebuffers(drawFbo);
        GLSMTextures.glDeleteTextures(source);
        GLSMTextures.glDeleteTextures(destination);
        return pixels;
    }

    private static Arguments mirror(String name, int[] srcRect, int[] dstRect, IntBinaryOperator expected) {
        return Arguments.of(name, srcRect, dstRect, expected);
    }

    static Stream<Arguments> mirroredBlits() {
        return Stream.of(
            mirror("reversed source X mirrors horizontally", new int[]{SIZE, 0, 0, SIZE}, new int[]{0, 0, SIZE, SIZE}, (x, y) -> pattern(SIZE - 1 - x, y)),
            mirror("reversed destination Y mirrors vertically", new int[]{0, 0, SIZE, SIZE}, new int[]{0, SIZE, SIZE, 0}, (x, y) -> pattern(x, SIZE - 1 - y)),
            mirror("both ranges reversed does not mirror", new int[]{SIZE, SIZE, 0, 0}, new int[]{SIZE, SIZE, 0, 0}, (x, y) -> pattern(x, y)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mirroredBlits")
    void blitFramebufferMirrorsByRangeDirection(String name, int[] src, int[] dst, IntBinaryOperator expected) {
        final int[] pixels = blitPattern(src[0], src[1], src[2], src[3], dst[0], dst[1], dst[2], dst[3]);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, x, y, expected.applyAsInt(x, y), name);
            }
        }
    }

    @Test
    void mirroredScaledBlitClipsTheOutOfBoundsSourceOnTheMirroredSide() {
        final int[] pixels = blitPattern(SIZE + SIZE / 2, 0, SIZE / 2, SIZE / 2, 0, 0, SIZE / 2, SIZE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (x < SIZE / 4 || x >= SIZE / 2) {
                    GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, x, y, UNTOUCHED, "outside the in-bounds part of the mirrored source");
                    continue;
                }
                final int sx = SIZE + SIZE / 2 - 1 - 2 * x;
                final int sy = y / 2;
                if ((sx - 1) / CELL != (sx + 1) / CELL || (sy - 1) / CELL != (sy + 1) / CELL) continue;
                GlsmDx12HeadlessRig.assertPixel(pixels, SIZE, x, y, pattern(sx, sy), "mirrored scaled blit");
            }
        }
    }

    @Test
    void blitFramebufferWritesEveryDrawBuffer() {
        final int source = createPatternTexture();
        final int first = GlsmDx12HeadlessRig.createSolidMipTexture(SIZE, 0, UNTOUCHED);
        final int skipped = GlsmDx12HeadlessRig.createSolidMipTexture(SIZE, 0, UNTOUCHED);
        final int third = GlsmDx12HeadlessRig.createSolidMipTexture(SIZE, 0, UNTOUCHED);
        final int readFbo = GlsmDx12HeadlessRig.fboWithColor(source, 0);
        final int drawFbo = GlsmDx12HeadlessRig.fboWithColor(first, 0);
        GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, skipped, 0);
        GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT2, GL11.GL_TEXTURE_2D, third, 0);
        final IntBuffer bufs = MemoryUtil.memAllocInt(3);
        try {
            bufs.put(0, GL30.GL_COLOR_ATTACHMENT0).put(1, GL11.GL_NONE).put(2, GL30.GL_COLOR_ATTACHMENT2);
            GLSMFramebuffer.glDrawBuffers(bufs);
        } finally {
            MemoryUtil.memFree(bufs);
        }

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GLSMFramebuffer.glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        final int[] firstPixels = GlsmDx12HeadlessRig.readTextureLevel(first, 0);
        final int[] thirdPixels = GlsmDx12HeadlessRig.readTextureLevel(third, 0);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                GlsmDx12HeadlessRig.assertPixel(firstPixels, SIZE, x, y, pattern(x, y), "draw buffer 0");
                GlsmDx12HeadlessRig.assertPixel(thirdPixels, SIZE, x, y, pattern(x, y), "draw buffer 2");
            }
        }
        GlsmDx12HeadlessRig.assertUniform(GlsmDx12HeadlessRig.readTextureLevel(skipped, 0), UNTOUCHED, "attachment 1 behind a GL_NONE draw buffer");

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GLSMFramebuffer.glDeleteFramebuffers(readFbo);
        GLSMFramebuffer.glDeleteFramebuffers(drawFbo);
        GLSMTextures.glDeleteTextures(source);
        GLSMTextures.glDeleteTextures(first);
        GLSMTextures.glDeleteTextures(skipped);
        GLSMTextures.glDeleteTextures(third);
    }
}
