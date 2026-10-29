package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("metal-device")
class BufferQueryTest {

    static MetalTestRig rig;
    static MetalGL gl;

    @BeforeAll
    static void setUp() {
        rig = MetalTestRig.create();
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

    @Test
    void fbo0ReadsAsBack() {
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        assertEquals(GL11.GL_BACK, gl.getInteger(GL11.GL_DRAW_BUFFER));
        assertEquals(GL11.GL_BACK, gl.getInteger(GL20.GL_DRAW_BUFFER0));
        assertEquals(GL11.GL_BACK, gl.getInteger(GL11.GL_READ_BUFFER));
    }

    @Test
    void boundFboReadsAsItsColourAttachment() {
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        assertEquals(GL30.GL_COLOR_ATTACHMENT0, gl.getInteger(GL20.GL_DRAW_BUFFER0));
        assertEquals(GL30.GL_COLOR_ATTACHMENT0, gl.getInteger(GL11.GL_READ_BUFFER));
        gl.drawBuffers(GL30.GL_COLOR_ATTACHMENT0 + 3);
        assertEquals(GL30.GL_COLOR_ATTACHMENT0 + 3, gl.getInteger(GL20.GL_DRAW_BUFFER0));
        gl.deleteFramebuffers(fbo);
    }

    @Test
    void noneDrawBufferReadsAsNone() {
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        gl.drawBuffer(GL11.GL_NONE);
        assertEquals(GL11.GL_NONE, gl.getInteger(GL20.GL_DRAW_BUFFER0));
        gl.deleteFramebuffers(fbo);
    }

    @Test
    void boundTextureReadsTheUnitsSlot() {
        final int a = gl.genTextures(), b = gl.genTextures();
        gl.activeTexture(GL13.GL_TEXTURE0);
        gl.bindTexture(GL11.GL_TEXTURE_2D, a);
        gl.activeTexture(GL13.GL_TEXTURE3);
        gl.bindTexture(GL11.GL_TEXTURE_2D, b);
        assertEquals(b, gl.getInteger(GL11.GL_TEXTURE_BINDING_2D));
        gl.activeTexture(GL13.GL_TEXTURE0);
        assertEquals(a, gl.getInteger(GL11.GL_TEXTURE_BINDING_2D));
        gl.bindTexture(GL11.GL_TEXTURE_2D, 0);
        gl.activeTexture(GL13.GL_TEXTURE3);
        gl.bindTexture(GL11.GL_TEXTURE_2D, 0);
    }
}
