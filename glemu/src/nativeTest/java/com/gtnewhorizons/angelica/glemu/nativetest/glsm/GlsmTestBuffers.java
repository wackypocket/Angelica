package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;

/** Small buffers for tests that only need some storage behind a name. */
final class GlsmTestBuffers {
    private GlsmTestBuffers() {}

    static int withData(int bytes) {
        final int buffer = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, BufferUtils.createByteBuffer(bytes), GL15.GL_STATIC_DRAW);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return buffer;
    }

    static void put(int buffer, float[] values) {
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, BufferUtils.createFloatBuffer(values.length).put(values).flip(), GL15.GL_STATIC_DRAW);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    static void delete(int buffer) {
        GLSMBuffers.glDeleteBuffers(buffer);
    }
}
