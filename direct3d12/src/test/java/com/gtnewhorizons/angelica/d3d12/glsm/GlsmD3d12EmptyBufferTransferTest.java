package com.gtnewhorizons.angelica.d3d12.glsm;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-d3d12")
@ExtendWith(GlsmD3d12FrameExtension.class)
class GlsmD3d12EmptyBufferTransferTest {

    private int id;
    private ByteBuffer empty;

    @BeforeEach
    void setUp() {
        id = GLSMBuffers.glGenBuffers();
        empty = MemoryUtil.memAlloc(16);
        empty.limit(0);
    }

    @AfterEach
    void tearDown() {
        MemoryUtil.memFree(empty);
        GLSMBuffers.glDeleteBuffers(id);
    }

    @Test
    void emptyBufferDataReplacesTheStoreWithoutACopy() {
        final int target = GL40.GL_DRAW_INDIRECT_BUFFER;
        GLSMBuffers.glBindBuffer(target, id);
        GLSMBuffers.glBufferData(target, 256L, GL15.GL_STATIC_DRAW);
        final long before = GlsmD3d12HeadlessRig.bufferTransfers();
        GLSMBuffers.glBufferData(target, empty, GL15.GL_STATIC_DRAW);
        assertEquals(before, GlsmD3d12HeadlessRig.bufferTransfers());
        assertEquals(0, GLSMBuffers.glGetBufferParameteri(target, GL15.GL_BUFFER_SIZE));
    }

    @Test
    void emptyStorageBufferSubDataIssuesNoCopy() {
        final int target = GL43.GL_SHADER_STORAGE_BUFFER;
        GLSMBuffers.glBindBuffer(target, id);
        GLSMBuffers.glBufferData(target, 256L, GL15.GL_DYNAMIC_DRAW);
        final long before = GlsmD3d12HeadlessRig.bufferTransfers();
        GLSMBuffers.glBufferSubData(target, 0, empty);
        assertEquals(before, GlsmD3d12HeadlessRig.bufferTransfers());
    }

    @Test
    void emptyGetBufferSubDataDoesNotReachTheDevice() {
        final int target = GL43.GL_SHADER_STORAGE_BUFFER;
        GLSMBuffers.glBindBuffer(target, id);
        GLSMBuffers.glBufferData(target, 256L, GL15.GL_DYNAMIC_DRAW);
        GLSMBuffers.glGetBufferSubData(target, 0, empty);
    }
}
