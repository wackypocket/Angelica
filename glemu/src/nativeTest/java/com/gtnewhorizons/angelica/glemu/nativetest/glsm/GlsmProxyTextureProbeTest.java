package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

import java.nio.ByteBuffer;

import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.LIGHTMAP_SIZE;
import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.SIZE;
import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.TEXEL;
import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.describe;
import static com.gtnewhorizons.angelica.glemu.nativetest.glsm.GlsmHeadlessRig.pixelAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmProxyTextureProbeTest {

    private static final int[] PROXY_TARGETS = {
        GL11.GL_PROXY_TEXTURE_1D, GL11.GL_PROXY_TEXTURE_2D, GL12.GL_PROXY_TEXTURE_3D, GL13.GL_PROXY_TEXTURE_CUBE_MAP, GL30.GL_PROXY_TEXTURE_1D_ARRAY,
        GL30.GL_PROXY_TEXTURE_2D_ARRAY, GL31.GL_PROXY_TEXTURE_RECTANGLE, GL40.GL_PROXY_TEXTURE_CUBE_MAP_ARRAY, GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE,
        GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE_ARRAY
    };

    private static int baseTexture;
    private static int lightmapTexture;

    @BeforeAll
    static void boot() {
        GlsmHeadlessRig.beginFrame();
        baseTexture = GlsmHeadlessRig.createSolidTexture(TEXEL);
        lightmapTexture = GlsmHeadlessRig.createLightmapTexture(false);
        GlsmHeadlessRig.endFrame();
    }

    @Test
    void vanillaMaximumTextureSizeProbeLeavesTheBoundLightmapIntact() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, lightmapTexture);

        final long handleBefore = GlsmRigHooks.textureHandle(lightmapTexture);

        assertEquals(16384, vanillaGetGLMaximumTextureSize());

        assertTrue(GlsmRigHooks.textureAllocated(lightmapTexture), "lightmap texture was dropped by the proxy probe");
        assertEquals(LIGHTMAP_SIZE, GlsmRigHooks.textureWidth(lightmapTexture), "lightmap width after probe");
        assertEquals(LIGHTMAP_SIZE, GlsmRigHooks.textureHeight(lightmapTexture), "lightmap height after probe");
        assertEquals(handleBefore, GlsmRigHooks.textureHandle(lightmapTexture), "lightmap image after probe");
        assertEquals(LIGHTMAP_SIZE, GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));

        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    @Test
    void proxyTargetCallsLeaveTheBoundTextureIntact() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, lightmapTexture);

        final long handleBefore = GlsmRigHooks.textureHandle(lightmapTexture);
        assertTrue(GlsmRigHooks.textureAllocated(lightmapTexture), "lightmap texture missing before the proxy calls");
        final int width = GlsmRigHooks.textureWidth(lightmapTexture), height = GlsmRigHooks.textureHeight(lightmapTexture), levels = GlsmRigHooks.textureLevels(lightmapTexture);
        final Object format = GlsmRigHooks.textureFormat(lightmapTexture);

        for (int target : PROXY_TARGETS) {
            final String where = "0x" + Integer.toHexString(target);
            GLSMTextures.glTexParameteri(target, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GLSMTextures.glTexParameterf(target, GL12.GL_TEXTURE_MIN_LOD, 4.0f);
            GLSMTextures.glTexSubImage2D(target, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GLSMTextures.glCopyTexSubImage2D(target, 0, 0, 0, 0, 0, 1, 1);
            GLSMTextures.glCopyTexImage2D(target, 0, GL11.GL_RGBA, 0, 0, 4, 4, 0);
            GLSMTextures.glGenerateMipmap(target);

            assertTrue(GlsmRigHooks.textureAllocated(lightmapTexture), where);
            assertEquals(width, GlsmRigHooks.textureWidth(lightmapTexture), where);
            assertEquals(height, GlsmRigHooks.textureHeight(lightmapTexture), where);
            assertEquals(levels, GlsmRigHooks.textureLevels(lightmapTexture), where);
            assertEquals(format, GlsmRigHooks.textureFormat(lightmapTexture), where);
            assertEquals(handleBefore, GlsmRigHooks.textureHandle(lightmapTexture), where);
        }

        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    @Test
    void proxyProbeDuringTerrainDrawStillShowsTheTexel() {
        GlsmHeadlessRig.bindBaseTexture(baseTexture);
        GlsmHeadlessRig.enableLightmapUnit(lightmapTexture, true);

        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
        assertEquals(16384, vanillaGetGLMaximumTextureSize());
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

        GlsmHeadlessRig.terrainRenderState();
        GlsmHeadlessRig.quadTwoUnits((short) 240);

        final int[] pixels = GlsmHeadlessRig.readTarget();
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
