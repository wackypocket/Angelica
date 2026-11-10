package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinkChecksTest {
    private static Shader shader(int type, String body) {
        final Shader s = new Shader(1, type);
        s.source = "#version 460 core\n" + body;
        return s;
    }

    private static String problem(String vertex, String fragment) {
        return LinkChecks.problem(List.of(shader(GL20.GL_VERTEX_SHADER, vertex + "\nvoid main() {}\n"), shader(GL20.GL_FRAGMENT_SHADER, fragment + "\nvoid main() {}\n")));
    }

    @Test
    void stagesRedeclaringGlPerVertexDifferentlyDoNotLink() {
        final Shader vertex = shader(GL20.GL_VERTEX_SHADER, "out gl_PerVertex { vec4 gl_Position; };\nvoid main() {}\n");
        final Shader geometry = shader(org.lwjgl.opengl.GL32.GL_GEOMETRY_SHADER, "layout(points) in;\nlayout(points, max_vertices = 1) out;\nin gl_PerVertex { float gl_PointSize; } gl_in[];\nvoid main() {}\n");
        assertNotNull(LinkChecks.separableProblem(List.of(vertex, geometry)));
        assertNull(LinkChecks.problem(List.of(vertex, geometry)));
        final Shader matching = shader(org.lwjgl.opengl.GL32.GL_GEOMETRY_SHADER, "layout(points) in;\nlayout(points, max_vertices = 1) out;\nin gl_PerVertex { vec4 gl_Position; } gl_in[];\nvoid main() {}\n");
        assertNull(LinkChecks.separableProblem(List.of(vertex, matching)));
    }

    @Test
    void aUniformDeclaredAlikeLinks() {
        assertNull(problem("uniform highp vec4 a;", "uniform vec4 a;"));
    }

    @Test
    void aUniformOfAnotherTypeDoesNot() {
        assertNotNull(problem("uniform vec4 a;", "uniform vec3 a;"));
    }

    @Test
    void anImageOfAnotherFormatDoesNot() {
        assertNotNull(problem("layout(rgba32f) uniform image1D a;", "layout(rg32f) uniform image1D a;"));
    }

    @Test
    void blocksGroupingTheirMembersDifferentlyStillMatch() {
        assertNull(problem("buffer B { float x, y; };", "buffer B { float x; float y; };"));
    }

    @Test
    void aBlockWithAnInstanceNameInOnlyOneStageDoesNot() {
        assertNotNull(problem("buffer B { float x; };", "buffer B { float x; } b;"));
    }

    @Test
    void differentInstanceNamesDoMatch() {
        assertNull(problem("buffer B { float x; } a;", "buffer B { float x; } b;"));
    }

    @Test
    void aMemberOfAnUnnamedBlockCannotAlsoBeAUniform() {
        assertNotNull(problem("uniform D { float f; };", "uniform float f;"));
    }

    @Test
    void aBlockMemberBehindAnInstanceNameCan() {
        assertNull(problem("uniform D { float f; } d;", "uniform float f;"));
    }

    @Test
    void countersMayNotOverlap() {
        assertNotNull(problem("", "layout(binding = 0, offset = 4) uniform atomic_uint a;\nlayout(binding = 0, offset = 4) uniform atomic_uint b;"));
    }

    @Test
    void aCounterKeepsOnePlaceAcrossStages() {
        assertNotNull(problem("layout(binding = 0, offset = 0) uniform atomic_uint a;", "layout(binding = 1, offset = 0) uniform atomic_uint a;"));
    }

    @Test
    void aStageNeedsAMain() {
        assertNotNull(LinkChecks.problem(List.of(shader(GL20.GL_FRAGMENT_SHADER, "void notMain() {}\n"))));
    }

    @Test
    void computeShadersMustDeclareOneWorkGroupSize() {
        assertNotNull(LinkChecks.problem(List.of(shader(GL43.GL_COMPUTE_SHADER, "void main() {}\n"))));
        assertNotNull(LinkChecks.problem(List.of(
            shader(GL43.GL_COMPUTE_SHADER, "layout(local_size_x = 2) in;\nvoid main() {}\n"),
            shader(GL43.GL_COMPUTE_SHADER, "layout(local_size_x = 1) in;\nvoid other() {}\n"))));
        assertNull(LinkChecks.problem(List.of(shader(GL43.GL_COMPUTE_SHADER, "layout(local_size_x = (2)) in;\nlayout(local_size_y = 3) in;\nvoid main() {}\n"))));
    }

    @Test
    void spirvAndGlslDoNotMix() {
        final Shader binary = shader(GL20.GL_VERTEX_SHADER, "void main() {}\n");
        binary.spirv = new int[0];
        assertNotNull(LinkChecks.problem(List.of(binary, shader(GL20.GL_FRAGMENT_SHADER, "void main() {}\n"))));
    }
}
