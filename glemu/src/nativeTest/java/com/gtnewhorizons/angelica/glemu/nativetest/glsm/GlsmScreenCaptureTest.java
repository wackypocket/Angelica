package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import com.gtnewhorizons.angelica.glsm.backend.ScreenCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmScreenCaptureTest {

    private static final int SRC_W = 64;
    private static final int SRC_H = 32;
    private static final int CAPTURE_W = 16;
    private static final int CAPTURE_H = 8;

    @Test
    void capturesADownscaledUserFramebuffer() {
        final int texture = GlsmHeadlessRig.newNearestTexture();
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SRC_W, SRC_H, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        final int framebuffer = GlsmHeadlessRig.fboWithColor(texture, 0);
        GlTestSupport.fillQuadrants(SRC_W, SRC_H);
        final ScreenCapture capture = GLSMFramebuffer.createScreenCapture(CAPTURE_W, CAPTURE_H);
        try {
            assertTrue(capture.begin(framebuffer, SRC_W, SRC_H, 7L), "capture was not recorded");
            GlsmHeadlessRig.endFrame();
            GlsmHeadlessRig.beginFrame();
            final long deadline = System.nanoTime() + 5_000_000_000L;
            while (!capture.poll()) {
                assertTrue(System.nanoTime() < deadline, "capture did not complete within 5 s");
                LockSupport.parkNanos(1_000_000L);
            }
            assertEquals(7L, capture.tag());
            GlTestSupport.assertQuadrantsBottomUp(capture.address(), CAPTURE_W, CAPTURE_H);
            capture.release();
        } finally {
            capture.delete();
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(framebuffer);
            GLSMTextures.glDeleteTextures(texture);
        }
    }
}
