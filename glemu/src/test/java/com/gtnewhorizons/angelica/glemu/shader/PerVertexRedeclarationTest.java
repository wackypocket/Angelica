package com.gtnewhorizons.angelica.glemu.shader;


import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static int clipArrayLength(String vs) {
        final ShaderCompiler.Result r = ShaderCompiler.compile(vs, GL20.GL_VERTEX_SHADER, "clipN", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        final Spirv spv = new Spirv(ByteBuffer.wrap(r.compiled().spirv()).order(ByteOrder.LITTLE_ENDIAN));
        final int[] w = spv.words;
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) == Spirv.OP_MEMBER_DECORATE && w[i + 3] == Spirv.DEC_BUILTIN && w[i + 4] == BUILTIN_CLIP_DISTANCE) {
                return spv.arrayLength(spv.type(spv.type(w[i + 1]).operands[w[i + 2]]));
            }
        }
        throw new AssertionError("no ClipDistance member found");
    }

    @Test
    void constantIndicesSizeTheArrayToWhatIsWritten() {
        assertEquals(1, clipArrayLength("#version 460\nvoid main() { gl_Position = vec4(0.0); gl_ClipDistance[0] = 1.0; }\n"));
        assertEquals(3, clipArrayLength("#version 460\nvoid main() { gl_Position = vec4(0.0); gl_ClipDistance[0] = 1.0; gl_ClipDistance[ 2 ] = 1.0; }\n"));
    }

    @Test
    void clipMaskBecomesASpecializationConstant() {
        final ShaderCompiler.Result r = ShaderCompiler.compile("#version 460\nvoid main() { gl_Position = vec4(0.0); gl_ClipDistance[0] = -1.0; }\n",
            GL20.GL_VERTEX_SHADER, "clipmask", ShaderCompiler.DEPTH_AT_END_OF_MAIN);
        assertNotNull(r.compiled(), r.log());
        final int[] w = new Spirv(ByteBuffer.wrap(r.compiled().spirv()).order(ByteOrder.LITTLE_ENDIAN)).words;
        assertTrue(Spirv.hasSpecId(w, ShaderCompiler.CLIP_MASK_SPEC_ID), "no clip-mask specialization constant");
    }

    @Test
    void geometryShadersMaskClipDistancesAtEachEmit() {
        final String gs = """
            #version 460
            layout(triangles) in;
            layout(triangle_strip, max_vertices = 3) out;
            void main() {
                for (int i = 0; i < 3; i++) {
                    gl_Position = gl_in[i].gl_Position;
                    gl_ClipDistance[0] = 1.0;
                    EmitVertex();
                }
                EndPrimitive();
            }
            """;
        for (int depth : new int[] {ShaderCompiler.DEPTH_NONE, ShaderCompiler.DEPTH_AT_EMIT}) {
            final ShaderCompiler.Result r = ShaderCompiler.compile(gs, org.lwjgl.opengl.GL32.GL_GEOMETRY_SHADER, "clipgs" + depth, depth);
            assertNotNull(r.compiled(), r.log());
            final int[] w = new Spirv(ByteBuffer.wrap(r.compiled().spirv()).order(ByteOrder.LITTLE_ENDIAN)).words;
            assertTrue(Spirv.hasSpecId(w, ShaderCompiler.CLIP_MASK_SPEC_ID), "no clip-mask specialization constant with depth mode " + depth);
        }
    }

    @Test
    void shadersThatDeclareCullDistanceAreLeftAlone() {
        final String src = "#version 460\nvoid main() { gl_Position = vec4(0.0); gl_ClipDistance[0] = 1.0; gl_CullDistance[0] = 1.0; }\n";
        assertEquals(src, ShaderCompiler.applyPerVertexRedeclaration(src, GL20.GL_VERTEX_SHADER));
    }
}
