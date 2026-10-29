package com.gtnewhorizons.angelica.dx12;

import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dx12-device")
class ProxyTextureLimitsTest {

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

    private static final int[] PROXY_TARGETS = {
        GL11.GL_PROXY_TEXTURE_1D, GL11.GL_PROXY_TEXTURE_2D, GL12.GL_PROXY_TEXTURE_3D, GL13.GL_PROXY_TEXTURE_CUBE_MAP,
        GL30.GL_PROXY_TEXTURE_1D_ARRAY, GL30.GL_PROXY_TEXTURE_2D_ARRAY, GL31.GL_PROXY_TEXTURE_RECTANGLE,
        GL40.GL_PROXY_TEXTURE_CUBE_MAP_ARRAY,
    };

    private static void proxy2D(int level, int internalFormat, int w, int h) {
        gl.texImage2D(GL11.GL_PROXY_TEXTURE_2D, level, internalFormat, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }

    private static int proxyLevel(int target, int level, int pname) {
        return gl.getTexLevelParameteri(target, level, pname);
    }

    @Test
    void threeDimensionalProxyIsBoundedByTheVolumeLimit() {
        final int limit = gl.getInteger(GL12.GL_MAX_3D_TEXTURE_SIZE);
        gl.texImage3D(GL12.GL_PROXY_TEXTURE_3D, 0, GL30.GL_R8, limit, 1, 1, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(limit, proxyLevel(GL12.GL_PROXY_TEXTURE_3D, 0, GL11.GL_TEXTURE_WIDTH));
        gl.texImage3D(GL12.GL_PROXY_TEXTURE_3D, 0, GL30.GL_R8, 1, 1, limit, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(limit, proxyLevel(GL12.GL_PROXY_TEXTURE_3D, 0, GL12.GL_TEXTURE_DEPTH));

        gl.texImage3D(GL12.GL_PROXY_TEXTURE_3D, 0, GL30.GL_R8, limit * 2, 1, 1, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(0, proxyLevel(GL12.GL_PROXY_TEXTURE_3D, 0, GL11.GL_TEXTURE_WIDTH));
        gl.texImage3D(GL12.GL_PROXY_TEXTURE_3D, 0, GL30.GL_R8, 1, 1, limit * 2, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(0, proxyLevel(GL12.GL_PROXY_TEXTURE_3D, 0, GL12.GL_TEXTURE_DEPTH));
    }

    @Test
    void everyProxyDimensionLimitMatchesItsDeviceLimit() {
        final int max2d = gl.getInteger(GL11.GL_MAX_TEXTURE_SIZE);
        final int maxCube = gl.getInteger(GL13.GL_MAX_CUBE_MAP_TEXTURE_SIZE);
        gl.texImage1D(GL11.GL_PROXY_TEXTURE_1D, 0, GL30.GL_R8, max2d, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(max2d, proxyLevel(GL11.GL_PROXY_TEXTURE_1D, 0, GL11.GL_TEXTURE_WIDTH), "1D at MAX_TEXTURE_SIZE");
        gl.texImage1D(GL11.GL_PROXY_TEXTURE_1D, 0, GL30.GL_R8, max2d + 1, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(0, proxyLevel(GL11.GL_PROXY_TEXTURE_1D, 0, GL11.GL_TEXTURE_WIDTH), "1D past MAX_TEXTURE_SIZE");
        gl.texImage2D(GL13.GL_PROXY_TEXTURE_CUBE_MAP, 0, GL30.GL_R8, maxCube, maxCube, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(maxCube, proxyLevel(GL13.GL_PROXY_TEXTURE_CUBE_MAP, 0, GL11.GL_TEXTURE_WIDTH), "cube at MAX_CUBE_MAP_TEXTURE_SIZE");
        gl.texImage2D(GL13.GL_PROXY_TEXTURE_CUBE_MAP, 0, GL30.GL_R8, maxCube * 2, maxCube * 2, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(0, proxyLevel(GL13.GL_PROXY_TEXTURE_CUBE_MAP, 0, GL11.GL_TEXTURE_WIDTH), "cube past MAX_CUBE_MAP_TEXTURE_SIZE");
    }

    @Test
    void oneDimensionalArrayLayersUseTheLayerLimitNotTheDimensionLimit() {
        final int layers = gl.getInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS);
        gl.texImage2D(GL30.GL_PROXY_TEXTURE_1D_ARRAY, 0, GL11.GL_RGBA, 512, layers, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(512, proxyLevel(GL30.GL_PROXY_TEXTURE_1D_ARRAY, 0, GL11.GL_TEXTURE_WIDTH));
        assertEquals(layers, proxyLevel(GL30.GL_PROXY_TEXTURE_1D_ARRAY, 0, GL11.GL_TEXTURE_HEIGHT));
        gl.texImage2D(GL30.GL_PROXY_TEXTURE_1D_ARRAY, 0, GL11.GL_RGBA, 512, layers + 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(0, proxyLevel(GL30.GL_PROXY_TEXTURE_1D_ARRAY, 0, GL11.GL_TEXTURE_WIDTH));
    }

    @Test
    void arrayLayerCountDoesNotHalvePerLevel() {
        gl.texImage2D(GL30.GL_PROXY_TEXTURE_1D_ARRAY, 2, GL11.GL_RGBA, 128, 64, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(128, proxyLevel(GL30.GL_PROXY_TEXTURE_1D_ARRAY, 2, GL11.GL_TEXTURE_WIDTH));
        assertEquals(64, proxyLevel(GL30.GL_PROXY_TEXTURE_1D_ARRAY, 2, GL11.GL_TEXTURE_HEIGHT));
    }

    @Test
    void aProbedLevelIsAcceptedOnItsLevelZeroEquivalentSize() {
        proxy2D(3, GL11.GL_RGBA, 1024, 512);
        assertEquals(1024, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 3, GL11.GL_TEXTURE_WIDTH));
        assertEquals(512, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 3, GL11.GL_TEXTURE_HEIGHT));
        assertEquals(8192, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
    }

    @Test
    void aProbedLevelWhoseBaseWouldOverflowTheLimitIsRejected() {
        proxy2D(2, GL11.GL_RGBA, gl.getInteger(GL11.GL_MAX_TEXTURE_SIZE), 4);
        assertEquals(0, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 2, GL11.GL_TEXTURE_WIDTH));
        proxy2D(40, GL11.GL_RGBA, 4, 4);
        assertEquals(0, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 40, GL11.GL_TEXTURE_WIDTH));
    }

    @Test
    void proxyTargetsAnswerTheGlParameterDefaults() {
        final int target = GL11.GL_PROXY_TEXTURE_2D;
        assertEquals(GL11.GL_NEAREST_MIPMAP_LINEAR, gl.getTexParameteri(target, GL11.GL_TEXTURE_MIN_FILTER));
        assertEquals(GL11.GL_LINEAR, gl.getTexParameteri(target, GL11.GL_TEXTURE_MAG_FILTER));
        assertEquals(GL11.GL_REPEAT, gl.getTexParameteri(target, GL11.GL_TEXTURE_WRAP_S));
        assertEquals(GL11.GL_REPEAT, gl.getTexParameteri(target, GL11.GL_TEXTURE_WRAP_T));
        assertEquals(GL11.GL_REPEAT, gl.getTexParameteri(target, GL12.GL_TEXTURE_WRAP_R));
        assertEquals(0, gl.getTexParameteri(target, GL12.GL_TEXTURE_BASE_LEVEL));
        assertEquals(1000, gl.getTexParameteri(target, GL12.GL_TEXTURE_MAX_LEVEL));
        assertEquals(-1000.0f, gl.getTexParameterf(target, GL12.GL_TEXTURE_MIN_LOD));
        assertEquals(1000.0f, gl.getTexParameterf(target, GL12.GL_TEXTURE_MAX_LOD));
        assertEquals(0.0f, gl.getTexParameterf(target, GL14.GL_TEXTURE_LOD_BIAS));
        assertEquals(1.0f, gl.getTexParameterf(target, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT));
    }

    @Test
    void proxyParameterDefaultsIgnoreTheBoundTexturesOwnState() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        assertEquals(GL11.GL_NEAREST, gl.getTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER));
        assertEquals(GL11.GL_NEAREST_MIPMAP_LINEAR, gl.getTexParameteri(GL11.GL_PROXY_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER));
        gl.bindTexture(GL11.GL_TEXTURE_2D, 0);
        gl.deleteTextures(tex);
    }

    @Test
    void proxyTargetsNeverReceiveParameterWrites() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        gl.texParameteri(GL11.GL_PROXY_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        gl.texParameterf(GL11.GL_PROXY_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 4.0f);
        assertEquals(GL11.GL_NEAREST_MIPMAP_LINEAR, gl.getTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER));
        assertEquals(0.0f, gl.getTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS));
        gl.bindTexture(GL11.GL_TEXTURE_2D, 0);
        gl.deleteTextures(tex);
    }
}
