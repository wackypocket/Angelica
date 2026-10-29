package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("vulkan-device")
class ProxyTextureTargetTest {

    static VulkanTestRig rig;
    static VulkanGL gl;

    @BeforeAll
    static void setUp() {
        rig = VulkanTestRig.create();
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
    void proxyTexImage2DRecordsDimensionsWithoutTouchingTheBoundTexture() {
        final int max = gl.getInteger(GL11.GL_MAX_TEXTURE_SIZE);
        final int tex = gl.genTextures();
        gl.activeTexture(GL13.GL_TEXTURE1);
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);

        proxy2D(0, GL11.GL_RGBA, max, max);

        assertEquals(tex, gl.getInteger(GL11.GL_TEXTURE_BINDING_2D));
        assertEquals(max, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
        assertEquals(max, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT));
        assertEquals(max >> 3, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 3, GL11.GL_TEXTURE_WIDTH));
        assertEquals(GL11.GL_RGBA, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
        assertEquals(0, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_BORDER));
        assertEquals(0, gl.getTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH), "the bound texture got no storage");
        gl.bindTexture(GL11.GL_TEXTURE_2D, 0);
        gl.deleteTextures(tex);
    }

    @Test
    void oversizedProxyTexImage2DZeroesEveryProxyField() {
        final int max = gl.getInteger(GL11.GL_MAX_TEXTURE_SIZE);
        proxy2D(0, GL11.GL_RGBA, max, max);
        proxy2D(0, GL11.GL_RGBA, max * 2, max * 2);
        assertEquals(0, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
        assertEquals(0, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT));
        assertEquals(0, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
    }

    @Test
    void proxyArrayTargetIsLimitedByLayerCount() {
        final int layers = gl.getInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS);
        gl.texImage3D(GL30.GL_PROXY_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA, 64, 64, layers, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(layers, proxyLevel(GL30.GL_PROXY_TEXTURE_2D_ARRAY, 0, GL12.GL_TEXTURE_DEPTH));
        gl.texImage3D(GL30.GL_PROXY_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA, 64, 64, layers + 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(0, proxyLevel(GL30.GL_PROXY_TEXTURE_2D_ARRAY, 0, GL12.GL_TEXTURE_DEPTH));
        assertEquals(0, proxyLevel(GL30.GL_PROXY_TEXTURE_2D_ARRAY, 0, GL11.GL_TEXTURE_WIDTH));
    }

    @Test
    void proxyStateIsScopedToTheProbedTarget() {
        gl.texImage2D(GL13.GL_PROXY_TEXTURE_CUBE_MAP, 0, GL11.GL_RGBA, 4096, 4096, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        gl.texImage3D(GL12.GL_PROXY_TEXTURE_3D, 0, GL11.GL_RGBA, 1 << 20, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        proxy2D(0, GL11.GL_RGBA, 256, 256);

        assertEquals(256, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
        assertEquals(4096, proxyLevel(GL13.GL_PROXY_TEXTURE_CUBE_MAP, 0, GL11.GL_TEXTURE_WIDTH), "the 2D probe must not overwrite the cube probe");
        assertEquals(0, proxyLevel(GL12.GL_PROXY_TEXTURE_3D, 0, GL12.GL_TEXTURE_DEPTH), "the rejected 3D probe stays rejected");
    }

    @Test
    void unmappableProxyFormatIsRejected() {
        proxy2D(0, GL11.GL_RGBA, 256, 256);
        proxy2D(0, 0x1BADF00D, 256, 256);
        assertEquals(0, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
        assertEquals(0, proxyLevel(GL11.GL_PROXY_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
    }

    @Test
    void proxyBindIsInert() {
        final int tex = gl.genTextures();
        gl.bindTexture(GL11.GL_TEXTURE_2D, tex);
        for (int target : PROXY_TARGETS) {
            gl.bindTexture(target, 99);
            assertEquals(tex, gl.getInteger(GL11.GL_TEXTURE_BINDING_2D), "0x" + Integer.toHexString(target));
        }
        gl.bindTexture(GL11.GL_TEXTURE_2D, 0);
        gl.deleteTextures(tex);
    }
}
