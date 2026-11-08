package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniformInitializersTest {

    private static List<UniformInitializers.Initial> of(String declarations) {
        return UniformInitializers.of("#version 460\n" + declarations + "\nvoid main() { gl_Position = vec4(0.0); }\n", GL20.GL_VERTEX_SHADER);
    }

    private static int bits(float value) {
        return Float.floatToRawIntBits(value);
    }

    @Test
    void scalarsVectorsAndArraysFoldToTheirComponents() {
        final List<UniformInitializers.Initial> found = of("""
            uniform float a = 1.5;
            uniform ivec2 b[2] = ivec2[2](ivec2(1, 2), ivec2(3, 4));
            uniform bool c = true;
            """);
        assertEquals(3, found.size());
        assertEquals("a", found.get(0).name());
        assertArrayEquals(new int[] {bits(1.5f)}, found.get(0).words());
        assertArrayEquals(new int[] {1, 2, 3, 4}, found.get(1).words());
        assertArrayEquals(new int[] {1}, found.get(2).words());
    }

    @Test
    void constantsAndSeveralDeclaratorsAreFollowed() {
        final List<UniformInitializers.Initial> found = of("""
            const float half_ = 0.5;
            uniform float a = half_ * 3.0, b, c = 2.0;
            """);
        assertEquals(2, found.size());
        assertArrayEquals(new int[] {bits(1.5f)}, found.get(0).words());
        assertEquals("c", found.get(1).name());
    }

    @Test
    void uniformsWithoutOneAndBlocksHaveNone() {
        assertTrue(of("uniform float a; layout(std140) uniform Block { vec4 v; } block;").isEmpty());
    }
}
