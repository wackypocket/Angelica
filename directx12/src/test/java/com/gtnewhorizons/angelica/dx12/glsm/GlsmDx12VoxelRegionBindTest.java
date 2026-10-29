package com.gtnewhorizons.angelica.dx12.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL43;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-dx12")
@ExtendWith(GlsmDx12FrameExtension.class)
class GlsmDx12VoxelRegionBindTest {

    private static final int BOUND_SLOT = 5;

    private static int glsmGenericSsbo() {
        return GlsmDx12HeadlessRig.glsmBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER);
    }

    private static void unbind(int slot) {
        GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, slot, 0);
    }

    @Test
    void glsmBindBufferBaseReachesBothCaches() {
        final int buf = GLSMBuffers.glGenBuffers();
        try {
            GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BOUND_SLOT, buf);
            assertEquals(buf, RENDER_BACKEND.getIntegerIndexed(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, BOUND_SLOT), "Dx12 indexed SSBO slot");
            assertEquals(buf, glsmGenericSsbo(), "GLSM generic SSBO binding");
        } finally {
            unbind(BOUND_SLOT);
            GLSMBuffers.glDeleteBuffers(buf);
        }
    }

    @Test
    void deletingTheRegionBufferClearsTheGlsmGenericBinding() {
        final int buf = GLSMBuffers.glGenBuffers();
        try {
            GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BOUND_SLOT, buf);
            assertEquals(buf, glsmGenericSsbo());
            GLSMBuffers.glDeleteBuffers(buf);
            assertEquals(0, glsmGenericSsbo(), "delete must see the tracked SSBO binding");
        } finally {
            unbind(BOUND_SLOT);
        }
    }
}
