package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** glCopyTexImage into a stencil-only format takes the read framebuffer's stencil, as both drivers measured do. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmStencilCopyTest {

    @Test
    void theFramebuffersStencilCopiesIntoAStencilTexture() {
        final int fbo = GlsmHeadlessRig.createDepthStencilFbo();
        final int texture = GL11C.glGenTextures();
        try {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glClearStencil(0x5A);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
            GL11C.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            // What earlier tests left is read away first: GL keeps one error per flag, and there can be several.
            while (GL11C.glGetError() != GL11.GL_NO_ERROR) { }
            GL11C.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_STENCIL_INDEX8, 0, 0, 4, 4, 0);
            assertEquals(GL11.GL_NO_ERROR, GL11C.glGetError(), "copying stencil into GL_STENCIL_INDEX8");
            final ByteBuffer read = BufferUtils.createByteBuffer(16);
            GL11C.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, read);
            assertEquals(GL11.GL_NO_ERROR, GL11C.glGetError());
            for (int i = 0; i < 16; i++) assertEquals(0x5A, read.get(i) & 0xFF, "texel " + i);
        } finally {
            GL11.glClearStencil(0);
            GL11C.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL11C.glDeleteTextures(texture);
            GL30.glDeleteFramebuffers(fbo);
        }
    }
}
