package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("vulkan-device")
class ImageUnitBindingStateTest {

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

    @AfterEach
    void unbind() {
        for (int unit = 0; unit < 3; unit++) gl.bindImageTexture(unit, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R32UI);
    }

    @Test
    void bindImageTextureRecordsTheFullTuple() {
        gl.bindImageTexture(2, 77, 3, true, 5, GL15.GL_READ_WRITE, GL30.GL_R32UI);
        final GlState st = gl.st();
        assertEquals(77, st.imageTex[2]);
        assertEquals(3, st.imageLevel[2]);
        assertTrue(st.imageLayered[2]);
        assertEquals(5, st.imageLayer[2]);
        assertEquals(GL15.GL_READ_WRITE, st.imageAccess[2]);
        assertEquals(GL30.GL_R32UI, st.imageFormat[2]);
    }

    @Test
    void getIntegerIndexedAnswersImageBindingsFromState() {
        gl.bindImageTexture(1, 42, 2, false, 4, GL15.GL_READ_ONLY, GL30.GL_RGBA32UI);
        assertEquals(42, gl.getIntegerIndexed(GL42.GL_IMAGE_BINDING_NAME, 1));
        assertEquals(2, gl.getIntegerIndexed(GL42.GL_IMAGE_BINDING_LEVEL, 1));
        assertEquals(GL11.GL_FALSE, gl.getIntegerIndexed(GL42.GL_IMAGE_BINDING_LAYERED, 1));
        assertEquals(4, gl.getIntegerIndexed(GL42.GL_IMAGE_BINDING_LAYER, 1));
        assertEquals(GL15.GL_READ_ONLY, gl.getIntegerIndexed(GL42.GL_IMAGE_BINDING_ACCESS, 1));
        assertEquals(GL30.GL_RGBA32UI, gl.getIntegerIndexed(GL42.GL_IMAGE_BINDING_FORMAT, 1));
    }

    @Test
    void outOfRangeUnitIsIgnored() {
        gl.bindImageTexture(0, 11, 0, true, 0, GL15.GL_READ_ONLY, GL30.GL_R32UI);
        gl.bindImageTexture(GlState.MAX_IMAGE_UNITS, 99, 0, true, 0, GL15.GL_READ_ONLY, GL30.GL_R32UI);
        final GlState st = gl.st();
        assertEquals(11, st.imageTex[0], "an out-of-range unit must not disturb a valid one");
        for (int i = 0; i < GlState.MAX_IMAGE_UNITS; i++) assertNotEquals(99, st.imageTex[i], "unit " + i + " took an out-of-range bind");
    }
}
