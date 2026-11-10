package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.ffp.CubeInstancedAttribs;
import com.gtnewhorizons.angelica.glsm.ffp.InstancedAttribs;
import com.gtnewhorizons.angelica.glsm.hooks.ImmediateExtendedAttribHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmAttribBindingTest {

    private static final String INSTANCED_VERTEX = """
        #version 330 core
        layout(location = 0) in vec3 a_Position;
        layout(location = %d) in vec4 iris_InstEntity;
        layout(location = %d) in vec4 iris_CubeTex;
        void main() { gl_Position = vec4(a_Position, 1.0) + iris_InstEntity + iris_CubeTex; }
        """.formatted(InstancedAttribs.LOC_ENTITY, CubeInstancedAttribs.LOC_CUBE_TEX);

    private static final String IMMEDIATE_VERTEX = """
        #version 330 core
        layout(location = 0) in vec3 a_Position;
        layout(location = %d) in vec4 mc_Entity;
        layout(location = %d) in vec4 iris_Entity;
        void main() { gl_Position = vec4(a_Position, 1.0) + mc_Entity + iris_Entity; }
        """.formatted(ImmediateExtendedAttribHandler.LOC_MC_ENTITY, ImmediateExtendedAttribHandler.LOC_ENTITY_IDS);

    @Test
    void namesTheRewriteChangesAreFoundAsTheShaderWroteThem() {
        final int program = GlTestSupport.program("""
            #version 330 core
            layout(location = 0) in vec3 position;
            layout(location = 1) in vec4 char;
            out vec4 tint;
            void main() {
                gl_Position = vec4(position, 1.0);
                tint = char;
            }
            """, """
            #version 330 core
            uniform sampler2D sampler;
            uniform float export;
            in vec4 tint;
            out vec4 fragColor;
            void main() { fragColor = texture(sampler, vec2(0.5)) * tint * export; }
            """);
        assertTrue(GLSMPrograms.glGetUniformLocation(program, "sampler") >= 0, "sampler");
        assertTrue(GLSMPrograms.glGetUniformLocation(program, "export") >= 0, "export");
        assertEquals(1, GLSMPrograms.glGetAttribLocation(program, "char"));
        final Set<String> active = new HashSet<>();
        for (int i = 0; i < GLSMPrograms.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS); i++) active.add(BackendManager.RENDER_BACKEND.getActiveUniform(program, i, 64, BufferUtils.createIntBuffer(2)));
        assertEquals(Set.of("sampler", "export"), active);
        GLSMPrograms.glDeleteProgram(program);
    }

    @Test
    void instancedEntityIdsStayAtTheInstanceSlot() {
        final int program = linkWithIrisBindings(INSTANCED_VERTEX);
        try {
            assertEquals(InstancedAttribs.LOC_ENTITY, GLSMPrograms.glGetAttribLocation(program, "iris_InstEntity"));
            assertEquals(CubeInstancedAttribs.LOC_CUBE_TEX, GLSMPrograms.glGetAttribLocation(program, "iris_CubeTex"));
        } finally {
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @Test
    void immediateEntityIdsDoNotMoveOntoMcEntity() {
        final int program = linkWithIrisBindings(IMMEDIATE_VERTEX);
        try {
            assertEquals(ImmediateExtendedAttribHandler.LOC_MC_ENTITY, GLSMPrograms.glGetAttribLocation(program, "mc_Entity"));
            assertEquals(ImmediateExtendedAttribHandler.LOC_ENTITY_IDS, GLSMPrograms.glGetAttribLocation(program, "iris_Entity"));
        } finally {
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    private static int linkWithIrisBindings(String vertexSource) {
        final int program = GLSMPrograms.glCreateProgram();
        GLSMPrograms.glAttachShader(program, GlTestSupport.compile(GL20.GL_VERTEX_SHADER, vertexSource));
        GLSMPrograms.glAttachShader(program, GlTestSupport.compile(GL20.GL_FRAGMENT_SHADER, GlTestSupport.TRIVIAL_FS));
        GLSMPrograms.glBindAttribLocation(program, InstancedAttribs.LOC_ENTITY, "iris_InstEntity");
        GLSMPrograms.glBindAttribLocation(program, ImmediateExtendedAttribHandler.LOC_MC_ENTITY, "mc_Entity");
        GLSMPrograms.glLinkProgram(program);
        return program;
    }
}
