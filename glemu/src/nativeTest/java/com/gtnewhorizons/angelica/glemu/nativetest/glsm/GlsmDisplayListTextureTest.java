package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A list keeps what a texture call was given at compile time, runs it only when called, and never takes the mod down for using one. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmDisplayListTextureTest {

    private interface Call {
        void run();
    }

    private static ByteBuffer pixel(int r, int g, int b, int a) {
        final ByteBuffer p = BufferUtils.createByteBuffer(4);
        p.put(0, (byte) r).put(1, (byte) g).put(2, (byte) b).put(3, (byte) a);
        return p;
    }

    private static int compile(Call call) {
        final int list = GLSMDraws.glGenLists(1);
        DisplayListManager.glNewList(list, GL11.GL_COMPILE);
        call.run();
        DisplayListManager.glEndList();
        return list;
    }

    private static int texture(int target) {
        final int t = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(target, t);
        return t;
    }

    private static void assertRed(int target, int red) {
        final ByteBuffer out = BufferUtils.createByteBuffer(16);
        GLSMTextures.glGetTexImage(target, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        assertEquals(red, out.get(0) & 0xFF);
    }

    @Test
    void texImage1DKeepsThePixelsItWasCompiledWith() {
        final int t = texture(GL11.GL_TEXTURE_1D);
        final ByteBuffer client = pixel(200, 0, 0, 255);
        final int list = compile(() -> GLSMTextures.glTexImage1D(GL11.GL_TEXTURE_1D, 0, GL11.GL_RGBA8, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, client));
        client.put(0, (byte) 7);
        assertEquals(0, GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_1D, 0, GL11.GL_TEXTURE_WIDTH), "compiling only records");
        DisplayListManager.glCallList(list);
        assertRed(GL11.GL_TEXTURE_1D, 200);
        GLSMTextures.glDeleteTextures(t);
    }

    @Test
    void volumeUploadsAndUpdatesReplay() {
        final int t = texture(GL12.GL_TEXTURE_3D);
        final int make = compile(() -> GLSMTextures.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, 1, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel(50, 0, 0, 255)));
        DisplayListManager.glCallList(make);
        assertRed(GL12.GL_TEXTURE_3D, 50);
        final int update = compile(() -> GLSMTextures.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel(90, 0, 0, 255)));
        assertRed(GL12.GL_TEXTURE_3D, 50);
        DisplayListManager.glCallList(update);
        assertRed(GL12.GL_TEXTURE_3D, 90);
        GLSMTextures.glDeleteTextures(t);
    }

    @Test
    void subImage1DReplays() {
        final int t = texture(GL11.GL_TEXTURE_1D);
        GLSMTextures.glTexImage1D(GL11.GL_TEXTURE_1D, 0, GL11.GL_RGBA8, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel(10, 0, 0, 255));
        final int list = compile(() -> GLSMTextures.glTexSubImage1D(GL11.GL_TEXTURE_1D, 0, 0, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel(120, 0, 0, 255)));
        assertRed(GL11.GL_TEXTURE_1D, 10);
        DisplayListManager.glCallList(list);
        assertRed(GL11.GL_TEXTURE_1D, 120);
        GLSMTextures.glDeleteTextures(t);
    }

    /** GL reads a buffer object when the list compiles, so writing to the buffer afterwards changes nothing. */
    @Test
    void anUploadFromABufferKeepsTheBuffersDataAtCompileTime() {
        final int t = texture(GL11.GL_TEXTURE_2D);
        final int pbo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
        GLSMBuffers.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, pixel(170, 0, 0, 255), GL15.GL_STATIC_DRAW);
        final int make = compile(() -> GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L));
        final int update = compile(() -> GLSMTextures.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L));
        GLSMBuffers.glBufferSubData(GL21.GL_PIXEL_UNPACK_BUFFER, 0L, pixel(3, 0, 0, 255));
        GLSMBuffers.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        DisplayListManager.glCallList(make);
        assertRed(GL11.GL_TEXTURE_2D, 170);
        GLSMTextures.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel(1, 0, 0, 255));
        DisplayListManager.glCallList(update);
        assertRed(GL11.GL_TEXTURE_2D, 170);
        GLSMBuffers.glDeleteBuffers(pbo);
        GLSMTextures.glDeleteTextures(t);
    }

    @Test
    void framebufferCopiesReplay() {
        GlsmHeadlessRig.bindTarget();
        GL11C.glClearColor(1f, 0f, 0f, 1f);
        GL11C.glClear(GL11.GL_COLOR_BUFFER_BIT);
        final int t = texture(GL11.GL_TEXTURE_2D);
        final int make = compile(() -> GLSMTextures.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 0, 0, 2, 2, 0));
        assertEquals(0, GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH), "compiling only records");
        DisplayListManager.glCallList(make);
        assertRed(GL11.GL_TEXTURE_2D, 255);
        GL11C.glClearColor(0f, 0f, 0f, 1f);
        GL11C.glClear(GL11.GL_COLOR_BUFFER_BIT);
        final int update = compile(() -> GLSMTextures.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, 2, 2));
        assertRed(GL11.GL_TEXTURE_2D, 255);
        DisplayListManager.glCallList(update);
        assertRed(GL11.GL_TEXTURE_2D, 0);
        GLSMTextures.glDeleteTextures(t);
    }

    /** A proxy asks whether an image would fit, and the answer is wanted now, not when a list is called. */
    @Test
    void aProxyIsAnsweredWhileCompiling() {
        compile(() -> {
            GLSMTextures.glTexImage2D(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            assertEquals(4, GLSMTextures.glGetTexLevelParameteri(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
        });
    }

    @Test
    void offsetClampReplays() {
        GLSMRasterizer.glPolygonOffset(0f, 0f);
        final int list = compile(() -> GLSMRasterizer.glPolygonOffsetClamp(2f, 3f, 0.5f));
        assertEquals(0f, GLSMQueries.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS));
        DisplayListManager.glCallList(list);
        assertEquals(3f, GLSMQueries.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS));
        GLSMRasterizer.glPolygonOffset(0f, 0f);
    }
}
