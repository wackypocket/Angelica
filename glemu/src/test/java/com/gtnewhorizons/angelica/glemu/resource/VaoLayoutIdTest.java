package com.gtnewhorizons.angelica.glemu.resource;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class VaoLayoutIdTest {

    private static Vao vao(int id, int buffer, long offset, int type) {
        final Vao v = new Vao(id);
        v.enabled[0] = true;
        v.size[0] = 3;
        v.type[0] = type;
        v.bindingBuffer[0] = buffer;
        v.bindingOffset[0] = offset;
        v.bindingStride[0] = 12;
        v.formatGen++;
        return v;
    }

    @Test
    void identicalLayoutsOnDifferentBuffersShareAnId() {
        assertEquals(vao(1, 7, 0L, GL11.GL_FLOAT).layoutId(), vao(2, 9, 256L, GL11.GL_FLOAT).layoutId());
    }

    @Test
    void differentFormatsGetDifferentIds() {
        assertNotEquals(vao(1, 7, 0L, GL11.GL_FLOAT).layoutId(), vao(2, 7, 0L, GL11.GL_SHORT).layoutId());
    }

    @Test
    void anAttributeWithoutABufferIsNotPartOfTheLayout() {
        final Vao bound = vao(1, 7, 0L, GL11.GL_FLOAT);
        final Vao unbound = vao(2, 0, 0L, GL11.GL_FLOAT);
        final Vao empty = new Vao(3);
        assertNotEquals(bound.layoutId(), unbound.layoutId());
        assertEquals(empty.layoutId(), unbound.layoutId());
    }

    @Test
    void changingTheFormatChangesTheId() {
        final Vao v = vao(1, 7, 0L, GL11.GL_FLOAT);
        final int before = v.layoutId();
        v.bindingDivisor[0] = 1;
        v.formatGen++;
        final int instanced = v.layoutId();
        assertNotEquals(before, instanced);

        v.bindingDivisor[0] = 0;
        v.formatGen++;
        assertEquals(before, v.layoutId(), "returning to an earlier layout reuses its id");
    }
}
