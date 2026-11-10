package com.gtnewhorizons.angelica.glemu.resource;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** No device at hand lets a texture be larger than a framebuffer, so this one is pretended. */
class FramebufferLimitsTest {
    private static final int LIMIT = 16384;

    private static int statusOf(int width, int height) {
        final TexBase texture = new TexBase(1, false) {};
        texture.format = Formats.RGBA8;
        texture.image = 1L;
        texture.width = width;
        texture.height = height;
        final Framebuffers framebuffers = new Framebuffers(new Framebuffers.Host() {
            @Override public TexBase attachment(int id, boolean renderbuffer) {
                return texture;
            }

            @Override public boolean renderable(int w, int h) {
                return w <= LIMIT && h <= LIMIT;
            }

            @Override public void targetsChanged() {
            }
        });
        final Fbo fbo = framebuffers.getOrCreate(1);
        fbo.colorTex[0] = 1;
        return framebuffers.status(fbo);
    }

    @Test
    void anAttachmentWithinTheLimitIsComplete() {
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, statusOf(LIMIT, LIMIT));
    }

    @Test
    void anAttachmentOverTheLimitIsUnsupported() {
        assertEquals(GL30.GL_FRAMEBUFFER_UNSUPPORTED, statusOf(LIMIT * 2, LIMIT));
        assertEquals(GL30.GL_FRAMEBUFFER_UNSUPPORTED, statusOf(LIMIT, LIMIT * 2));
    }
}
