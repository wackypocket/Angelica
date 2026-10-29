package com.gtnewhorizons.angelica.dx12;

import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dx12-device")
class FramebufferAttachmentQueryTest {

    static Dx12TestRig rig;
    static Dx12GL gl;

    @BeforeAll
    static void setUp() {
        rig = Dx12TestRig.create();
        gl = rig.gl;
    }

    @AfterAll
    static void tearDown() {
        if (rig != null) rig.close();
    }

    @AfterEach
    void resetBindings() {
        rig.resetBindings();
    }

    private int depthSizeOfD24S8() {
        final int fbo = gl.genFramebuffers();
        final int rb = gl.genRenderbuffers();
        gl.bindRenderbuffer(GL30.GL_RENDERBUFFER, rb);
        gl.renderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, 4, 4);
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, rb);
        return gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
    }

    @Test
    void defaultFramebufferReportsEightStencilBits() {
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        assertEquals(8, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL11.GL_STENCIL, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE));
    }

    @Test
    void defaultFramebufferReportsDepthAndColorSizes() {
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        final int depth = gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL11.GL_DEPTH, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        assertTrue(depth == 24 || depth == 32, "default depth bits: " + depth);
        assertEquals(8, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL11.GL_BACK_LEFT, GL30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE));
        assertEquals(8, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL11.GL_BACK_LEFT, GL30.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE));
        assertEquals(GL30.GL_FRAMEBUFFER_DEFAULT, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL11.GL_BACK_LEFT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
    }

    @Test
    void packedDepthStencilAttachmentReportsBothAspects() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, 4, 4, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer) null);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, tex, 0);
        for (int attachment : new int[] {GL30.GL_DEPTH_ATTACHMENT, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_DEPTH_STENCIL_ATTACHMENT}) {
            assertEquals(8, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE), "attachment 0x" + Integer.toHexString(attachment));
        }
        final int depth = gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        assertTrue(depth == 24 || depth == 32, "depth bits: " + depth);
        assertEquals(tex, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME));
        assertEquals(GL11.GL_TEXTURE, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    @Test
    void depthOnlyAttachmentReportsNoStencil() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, 4, 4, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, tex, 0);
        assertEquals(0, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE));
        final int depth = gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        assertTrue(depth == 24 || depth == 32, "depth bits: " + depth);
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    @Test
    void missingAttachmentReportsNone() {
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        assertEquals(GL11.GL_NONE, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
        assertEquals(0, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE));
        gl.deleteFramebuffers(fbo);
    }

    @Test
    void colorAttachmentReportsChannelSizes() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + 1, GL11.GL_TEXTURE_2D, tex, 0);
        assertEquals(8, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + 1, GL30.GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE));
        assertEquals(tex, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + 1, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME));
        assertEquals(GL11.GL_NONE, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
        gl.deleteFramebuffers(fbo);
        gl.deleteTextures(tex);
    }

    @Test
    void packedRenderbufferReportsTheSameDepthAsATexture() {
        final int depth = depthSizeOfD24S8();
        assertTrue(depth == 24 || depth == 32, "depth bits: " + depth);
        assertEquals(GL30.GL_RENDERBUFFER, gl.getFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
    }
}
