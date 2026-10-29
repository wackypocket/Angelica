package com.gtnewhorizons.angelica.dx12.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.dx12.resource.Tex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;

import static com.gtnewhorizons.angelica.dx12.glsm.GlsmDx12HeadlessRig.LIGHTMAP_SIZE;
import static com.gtnewhorizons.angelica.dx12.glsm.GlsmDx12HeadlessRig.SIZE;
import static com.gtnewhorizons.angelica.dx12.glsm.GlsmDx12HeadlessRig.TEXEL;
import static com.gtnewhorizons.angelica.dx12.glsm.GlsmDx12HeadlessRig.describe;
import static com.gtnewhorizons.angelica.dx12.glsm.GlsmDx12HeadlessRig.pixelAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("glsm-dx12")
@ExtendWith(GlsmDx12FrameExtension.class)
class GlsmDx12ProxyTextureProbeTest {

    private static final int[] PROXY_TARGETS = {
        0x8063, 0x8064, 0x8070, 0x851B, 0x8C19, 0x8C1B, 0x84F7, 0x900B, 0x9101, 0x9103
    };

    private static int baseTexture;
    private static int lightmapTexture;

    @BeforeAll
    static void boot() {
        GlsmDx12HeadlessRig.beginFrame();
        baseTexture = GlsmDx12HeadlessRig.createSolidTexture(TEXEL);
        lightmapTexture = GlsmDx12HeadlessRig.createLightmapTexture(false);
        GlsmDx12HeadlessRig.endFrame();
    }

    @Test
    void vanillaMaximumTextureSizeProbeLeavesTheBoundLightmapIntact() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, lightmapTexture);

        final long handleBefore = GlsmDx12HeadlessRig.textureImage(lightmapTexture);

        assertEquals(16384, vanillaGetGLMaximumTextureSize());

        final Tex meta = GlsmDx12HeadlessRig.texture(lightmapTexture);
        assertNotNull(meta, "lightmap texture was dropped by the proxy probe");
        assertEquals(LIGHTMAP_SIZE, meta.width, "lightmap width after probe");
        assertEquals(LIGHTMAP_SIZE, meta.height, "lightmap height after probe");
        assertEquals(handleBefore, GlsmDx12HeadlessRig.textureImage(lightmapTexture), "lightmap image after probe");
        assertEquals(LIGHTMAP_SIZE, GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));

        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    @Test
    void proxyTargetCallsLeaveTheBoundTextureIntact() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, lightmapTexture);

        final long handleBefore = GlsmDx12HeadlessRig.textureImage(lightmapTexture);
        final Tex tex = GlsmDx12HeadlessRig.texture(lightmapTexture);
        assertNotNull(tex, "lightmap texture missing before the proxy calls");
        final int width = tex.width, height = tex.height, levels = tex.levels;
        final Object format = tex.format;

        for (int target : PROXY_TARGETS) {
            final String where = "0x" + Integer.toHexString(target);
            GLSMTextures.glTexParameteri(target, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GLSMTextures.glTexParameterf(target, GL12.GL_TEXTURE_MIN_LOD, 4.0f);
            GLSMTextures.glTexSubImage2D(target, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GLSMTextures.glCopyTexSubImage2D(target, 0, 0, 0, 0, 0, 1, 1);
            GLSMTextures.glCopyTexImage2D(target, 0, GL11.GL_RGBA, 0, 0, 4, 4, 0);
            GLSMTextures.glGenerateMipmap(target);

            final Tex after = GlsmDx12HeadlessRig.texture(lightmapTexture);
            assertNotNull(after, where);
            assertEquals(width, after.width, where);
            assertEquals(height, after.height, where);
            assertEquals(levels, after.levels, where);
            assertEquals(format, after.format, where);
            assertEquals(handleBefore, GlsmDx12HeadlessRig.textureImage(lightmapTexture), where);
        }

        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    @Test
    void proxyProbeDuringTerrainDrawStillShowsTheTexel() {
        GlsmDx12HeadlessRig.bindBaseTexture(baseTexture);
        GlsmDx12HeadlessRig.enableLightmapUnit(lightmapTexture, true);

        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
        assertEquals(16384, vanillaGetGLMaximumTextureSize());
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

        GlsmDx12HeadlessRig.terrainRenderState();
        GlsmDx12HeadlessRig.quadTwoUnits((short) 240);

        final int[] pixels = GlsmDx12HeadlessRig.readTarget();
        final int center = pixelAt(pixels, SIZE, SIZE / 2, SIZE / 2);
        final int corner = pixelAt(pixels, SIZE, 2, 2);
        assertEquals(TEXEL, center, () -> "center " + describe(center));
        assertEquals(TEXEL, corner, () -> "corner " + describe(corner));
    }

    private static int vanillaGetGLMaximumTextureSize() {
        for (int i = 16384; i > 0; i >>= 1) {
            GLSMTextures.glTexImage2D(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_RGBA, i, i, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            final int j = GLSMTextures.glGetTexLevelParameteri(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            if (j != 0) {
                return i;
            }
        }
        return -1;
    }
}
