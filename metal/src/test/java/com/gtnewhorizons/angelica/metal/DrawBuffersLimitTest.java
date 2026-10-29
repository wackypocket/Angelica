package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("metal-device")
class DrawBuffersLimitTest {

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

    private static IntBuffer attachments(int count) {
        final IntBuffer bufs = MemoryUtil.memAllocInt(count);
        for (int i = 0; i < count; i++) bufs.put(i, GL30.GL_COLOR_ATTACHMENT0 + i);
        return bufs;
    }

    @Test
    void namedDrawBuffersOverTheLimitAreIgnored() {
        final int max = gl.getInteger(GL20.GL_MAX_DRAW_BUFFERS);
        final int fbo = gl.genFramebuffers();
        IntBuffer bufs = attachments(2);
        try {
            gl.namedFramebufferDrawBuffers(fbo, bufs);
        } finally {
            MemoryUtil.memFree(bufs);
        }
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        assertEquals(GL30.GL_COLOR_ATTACHMENT0 + 1, gl.getInteger(GL20.GL_DRAW_BUFFER0 + 1), "a legal count must apply, or the refusal proves nothing");
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        bufs = attachments(max + 1);
        try {
            gl.namedFramebufferDrawBuffers(fbo, bufs);
        } finally {
            MemoryUtil.memFree(bufs);
        }
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        assertEquals(GL30.GL_COLOR_ATTACHMENT0 + 1, gl.getInteger(GL20.GL_DRAW_BUFFER0 + 1), "a count above MAX_DRAW_BUFFERS must leave the draw buffers untouched");
        assertEquals(GL11.GL_NONE, gl.getInteger(GL20.GL_DRAW_BUFFER0 + 2), "a count above MAX_DRAW_BUFFERS must leave the draw buffers untouched");
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        bufs = attachments(max);
        try {
            gl.namedFramebufferDrawBuffers(fbo, bufs);
        } finally {
            MemoryUtil.memFree(bufs);
        }
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        assertEquals(GL30.GL_COLOR_ATTACHMENT0 + max - 1, gl.getInteger(GL20.GL_DRAW_BUFFER0 + max - 1), "exactly the limit is legal");
        gl.deleteFramebuffers(fbo);
    }

    @Test
    void boundDrawBuffersOverTheLimitAreIgnored() {
        final int max = gl.getInteger(GL20.GL_MAX_DRAW_BUFFERS);
        final int fbo = gl.genFramebuffers();
        gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        final IntBuffer bufs = attachments(max + 1);
        try {
            gl.drawBuffers(bufs);
        } finally {
            MemoryUtil.memFree(bufs);
        }
        assertEquals(GL30.GL_COLOR_ATTACHMENT0, gl.getInteger(GL20.GL_DRAW_BUFFER0), "a count above MAX_DRAW_BUFFERS must leave the draw buffers untouched");
        assertEquals(GL11.GL_NONE, gl.getInteger(GL20.GL_DRAW_BUFFER0 + 1), "a count above MAX_DRAW_BUFFERS must leave the draw buffers untouched");
        gl.deleteFramebuffers(fbo);
    }
}
