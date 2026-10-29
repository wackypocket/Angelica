package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.metal.gl.MetalGL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("metal-device")
class BufferDeleteUnbindTest {

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

    private static final int UBO_INDEX = 3;
    private static final int SSBO_INDEX = 5;
    private static final int VERTEX_BINDING = 2;
    private static final int ATTRIB = 4;

    private static final int[] GENERIC_TARGETS = {
        GL40.GL_DRAW_INDIRECT_BUFFER, GL43.GL_DISPATCH_INDIRECT_BUFFER, GL31.GL_COPY_READ_BUFFER,
        GL31.GL_COPY_WRITE_BUFFER, GL21.GL_PIXEL_PACK_BUFFER, GL21.GL_PIXEL_UNPACK_BUFFER,
    };
    private static final int[] GENERIC_QUERIES = {
        GL40.GL_DRAW_INDIRECT_BUFFER_BINDING, GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING, GL42.GL_COPY_READ_BUFFER_BINDING,
        GL42.GL_COPY_WRITE_BUFFER_BINDING, GL21.GL_PIXEL_PACK_BUFFER_BINDING, GL21.GL_PIXEL_UNPACK_BUFFER_BINDING,
    };

    private int buffer;
    private int other;
    private int vao;

    private void bindEverywhere() {
        buffer = gl.genBuffers();
        other = gl.genBuffers();
        vao = gl.genVertexArrays();
        gl.bindVertexArray(vao);
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        gl.bufferData(GL15.GL_ARRAY_BUFFER, 256L, GL15.GL_STATIC_DRAW);
        gl.vertexAttribPointer(ATTRIB, 4, GL11.GL_FLOAT, false, 16, 0L);
        gl.bindBufferRange(GL31.GL_UNIFORM_BUFFER, UBO_INDEX, buffer, 16L, 64L);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_INDEX, buffer);
        gl.bindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, buffer);
        gl.bindVertexBuffer(VERTEX_BINDING, buffer, 8L, 12);
        for (int target : GENERIC_TARGETS) gl.bindBuffer(target, buffer);
    }

    @AfterEach
    void unbind() {
        gl.bindBufferBase(GL31.GL_UNIFORM_BUFFER, UBO_INDEX, 0);
        gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_INDEX, 0);
        gl.bindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        for (int target : GENERIC_TARGETS) gl.bindBuffer(target, 0);
        gl.bindVertexArray(0);
        if (vao != 0) gl.deleteVertexArrays(vao);
        gl.deleteBuffers(other);
    }

    private void assertBindings(int expected) {
        final GlState st = gl.st();
        assertEquals(expected, gl.getIntegerIndexed(GL31.GL_UNIFORM_BUFFER_BINDING, UBO_INDEX), "indexed UBO");
        assertEquals(expected == 0 ? 0 : 16, gl.getIntegerIndexed(GL31.GL_UNIFORM_BUFFER_START, UBO_INDEX), "UBO range start");
        assertEquals(expected == 0 ? 0 : 64, gl.getIntegerIndexed(GL31.GL_UNIFORM_BUFFER_SIZE, UBO_INDEX), "UBO range size");
        assertEquals(expected, gl.getInteger(GL31.GL_UNIFORM_BUFFER_BINDING), "generic UBO");
        assertEquals(expected, gl.getIntegerIndexed(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, SSBO_INDEX), "indexed SSBO");
        assertEquals(expected, gl.getInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING), "generic SSBO");
        assertEquals(expected, gl.getInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING), "element buffer");
        assertEquals(expected, gl.getInteger(GL15.GL_ARRAY_BUFFER_BINDING), "array buffer");
        assertEquals(expected, st.vao.bindingBuffer[VERTEX_BINDING], "vertex buffer binding");
        assertEquals(expected, st.vao.bindingBuffer[st.vao.bindingOf[ATTRIB]], "attribute's buffer");
        for (int i = 0; i < GENERIC_QUERIES.length; i++) {
            assertEquals(expected, gl.getInteger(GENERIC_QUERIES[i]), "binding 0x" + Integer.toHexString(GENERIC_QUERIES[i]));
        }
    }

    @Test
    void deleteClearsEveryBindingOfTheDeletedBuffer() {
        bindEverywhere();
        assertBindings(buffer);

        gl.deleteBuffers(buffer);

        assertBindings(0);
        assertEquals(8L, gl.st().vao.bindingOffset[VERTEX_BINDING], "the vertex binding keeps its offset");
        assertEquals(12, gl.st().vao.bindingStride[VERTEX_BINDING], "the vertex binding keeps its stride");
    }

    @Test
    void intBufferDeleteClearsEveryBindingOfTheDeletedBuffer() {
        bindEverywhere();
        final IntBuffer ids = MemoryUtil.memAllocInt(2);
        try {
            ids.put(0, other).put(1, buffer);
            gl.deleteBuffers(ids);
        } finally {
            MemoryUtil.memFree(ids);
        }
        assertBindings(0);
        other = 0;
    }

    @Test
    void deletingAnUnrelatedBufferChangesNothing() {
        bindEverywhere();
        gl.deleteBuffers(other);
        gl.deleteBuffers(0);
        assertBindings(buffer);
        other = 0;
        gl.deleteBuffers(buffer);
    }
}
