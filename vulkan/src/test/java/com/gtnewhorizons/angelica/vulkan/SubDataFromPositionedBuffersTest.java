package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("vulkan-device")
class SubDataFromPositionedBuffersTest {

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

    private static byte[] read(int target, int offset, int length) {
        final ByteBuffer out = MemoryUtil.memAlloc(length);
        try {
            gl.getBufferSubData(target, offset, out);
            final byte[] r = new byte[length];
            out.get(r);
            return r;
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {GL31.GL_UNIFORM_BUFFER, GL15.GL_ELEMENT_ARRAY_BUFFER})
    void subDataFromANonZeroPositionLandsAtTheAbsoluteOffset(int target) {
        final int vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        final int id = gl.genBuffers();
        gl.bindBuffer(target, id);
        gl.bufferData(target, 64, GL15.GL_DYNAMIC_DRAW);
        final ByteBuffer payload = MemoryUtil.memCalloc(32);
        try {
            for (int i = 16; i < 24; i++) payload.put(i, (byte) (0x10 + (i - 16)));
            payload.position(16).limit(24);

            gl.bufferSubData(target, 40, payload);

            final byte[] got = read(target, 40, 8);
            for (int i = 0; i < 8; i++) assertEquals((byte) (0x10 + i), got[i], "byte " + i + " must land at the absolute offset, not position()+offset");
            assertEquals(16, payload.position(), "bufferSubData must not move the caller's buffer position");
            assertEquals(24, payload.limit(), "bufferSubData must not move the caller's buffer limit");
        } finally {
            MemoryUtil.memFree(payload);
            gl.bindBuffer(target, 0);
            gl.deleteBuffers(id);
            gl.bindVertexArray(0);
            gl.deleteVertexArrays(vao);
        }
    }

    @Test
    void secondSubDataDoesNotDriftFromThePriorWrite() {
        final int id = gl.genBuffers();
        gl.bindBuffer(GL31.GL_UNIFORM_BUFFER, id);
        gl.bufferData(GL31.GL_UNIFORM_BUFFER, 64, GL15.GL_DYNAMIC_DRAW);
        final ByteBuffer a = MemoryUtil.memAlloc(8);
        final ByteBuffer b = MemoryUtil.memAlloc(8);
        try {
            for (int i = 0; i < 8; i++) a.put(i, (byte) 1);
            for (int i = 0; i < 8; i++) b.put(i, (byte) 2);
            gl.bufferSubData(GL31.GL_UNIFORM_BUFFER, 0, a);
            gl.bufferSubData(GL31.GL_UNIFORM_BUFFER, 8, b);
            final byte[] got = read(GL31.GL_UNIFORM_BUFFER, 0, 16);
            for (int i = 0; i < 8; i++) assertEquals((byte) 1, got[i]);
            for (int i = 0; i < 8; i++) assertEquals((byte) 2, got[8 + i]);
        } finally {
            MemoryUtil.memFree(a);
            MemoryUtil.memFree(b);
            gl.bindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
            gl.deleteBuffers(id);
        }
    }
}
