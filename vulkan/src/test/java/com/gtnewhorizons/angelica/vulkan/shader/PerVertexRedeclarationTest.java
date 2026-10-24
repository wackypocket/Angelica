package com.gtnewhorizons.angelica.vulkan.shader;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PerVertexRedeclarationTest {

    private static final int BUILTIN_CLIP_DISTANCE = 3, BUILTIN_CULL_DISTANCE = 4;

    private static final String VS = """
        #version 460
        uniform vec4 planes[8];
        void main() {
            gl_Position = vec4(0.0);
            for (int i = 0; i < 8; i++) gl_ClipDistance[i] = dot(planes[i], gl_Position);
        }
        """;

    @Test
    void eightClipDistancesLeaveNoRoomForImplicitCullDistance() {
        final ShaderCompiler.Result r = ShaderCompiler.compile(VS, GL20.GL_VERTEX_SHADER, "clip8", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        final Spirv spv = new Spirv(ByteBuffer.wrap(r.compiled().spirv()).order(ByteOrder.LITTLE_ENDIAN));
        final int[] w = spv.words;

        int clipStruct = -1, clipMember = -1;
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) != Spirv.OP_MEMBER_DECORATE || w[i + 3] != Spirv.DEC_BUILTIN) continue;
            assertFalse(w[i + 4] == BUILTIN_CULL_DISTANCE, "gl_PerVertex still declares gl_CullDistance");
            if (w[i + 4] == BUILTIN_CLIP_DISTANCE) {
                clipStruct = w[i + 1];
                clipMember = w[i + 2];
            }
        }
        final Spirv.Type block = spv.type(clipStruct);
        assertNotNull(block, "no ClipDistance member found");
        assertEquals(8, spv.arrayLength(spv.type(block.operands[clipMember])));
    }

    @Test
    void shadersThatDeclareCullDistanceAreLeftAlone() {
        final String src = "#version 460\nvoid main() { gl_Position = vec4(0.0); gl_ClipDistance[0] = 1.0; gl_CullDistance[0] = 1.0; }\n";
        assertEquals(src, ShaderCompiler.applyPerVertexRedeclaration(src, GL20.GL_VERTEX_SHADER));
    }
}
