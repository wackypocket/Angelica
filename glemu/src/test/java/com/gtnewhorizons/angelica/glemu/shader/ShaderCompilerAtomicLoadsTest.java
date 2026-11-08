package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderCompilerAtomicLoadsTest {
    @Test
    void aLoadOfAnImageWithAtomicsAddsZero() {
        final String out = ShaderCompiler.withAtomicLoads("layout(r32ui) uniform uimage2D a;\nvoid main() { imageAtomicAdd(a, c, 1u); x = imageLoad(a, f(c, 2)).x; }");
        assertTrue(out.contains("x = uvec4(imageAtomicAdd(a, f(c, 2), 0u), 0u, 0u, 1u).x;"));
    }

    @Test
    void aSignedImageStaysSigned() {
        final String out = ShaderCompiler.withAtomicLoads("layout(r32i) uniform iimage2D a;\nvoid main() { imageAtomicMin(a, c, 1); x = imageLoad(a, c); }");
        assertTrue(out.contains("x = ivec4(imageAtomicAdd(a, c, 0), 0, 0, 1);"));
    }

    @Test
    void anImageWithoutAtomicsIsLeftAlone() {
        final String source = "layout(r32ui) uniform uimage2D a, b;\nuniform uimage2D d;\nvoid main() { imageAtomicAdd(d, c, 1u); x = imageLoad(a, c); }";
        assertEquals(source, ShaderCompiler.withAtomicLoads(source));
    }
}
