package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("metal-device")
class DispatchIndirectBindingTest {

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

    @AfterEach
    void unbind() {
        gl.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        gl.bindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, 0);
    }

    @Test
    void dispatchIndirectHasItsOwnBindingPoint() {
        final int draw = gl.genBuffers(), dispatch = gl.genBuffers();
        gl.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, draw);
        gl.bindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, dispatch);
        assertEquals(draw, gl.getInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING));
        assertEquals(dispatch, gl.getInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING));
    }

    @Test
    void bindingDispatchIndirectLeavesTheDrawIndirectSlotAlone() {
        final int draw = gl.genBuffers(), dispatch = gl.genBuffers();
        gl.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, draw);
        gl.bindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, dispatch);
        gl.bindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, 0);
        assertEquals(draw, gl.getInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING));
        assertEquals(0, gl.getInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING));
    }
}
