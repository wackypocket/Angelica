package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.opengl.GL43;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmSsboBindingCacheTest {

    private static final int BOUND_SLOT = 5;

    private static int glsmGenericSsbo() {
        return GlsmHeadlessRig.glsmBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER);
    }

    private static void unbind(int slot) {
        GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, slot, 0);
    }

    @Test
    void glsmBindBufferBaseReachesBothCaches() {
        final int buf = GLSMBuffers.glGenBuffers();
        try {
            GLSMBuffers.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BOUND_SLOT, buf);
            assertEquals(buf, GlsmRigHooks.boundStorageBuffer(BOUND_SLOT), "the backend indexed SSBO slot");
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
