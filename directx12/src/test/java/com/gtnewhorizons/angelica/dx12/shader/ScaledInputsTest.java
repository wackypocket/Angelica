package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;
import com.gtnewhorizons.angelica.glemu.shader.Spirv;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaledInputsTest {

    private static final String VS = """
        #version 330 core
        layout(location = 0) in vec3 a_Position;
        layout(location = 1) in vec2 mc_midTexCoord;
        layout(location = 2) in vec4 at_midBlock;
        out vec2 v_mid;
        out float v_block;
        void main() {
            gl_Position = vec4(a_Position, 1.0);
            v_mid = mc_midTexCoord / 65535.0 + vec2(mc_midTexCoord.x);
            v_block = at_midBlock.y + at_midBlock.w;
        }
        """;

    private static int[] words() {
        final ShaderCompiler.Result r = ShaderCompiler.compile(VS, GL20.GL_VERTEX_SHADER, "scaled-test", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        return new Spirv(ByteBuffer.wrap(r.compiled().spirv()).order(ByteOrder.LITTLE_ENDIAN)).words;
    }

    @Test
    void integerFedInputsAreRetypedAndConverted() throws Exception {
        final int[] scaled = ScaledInputs.rewrite(words(), 1 << 1 | 1 << 2, 1 << 2);
        final String hlsl = HlslCompiler.toHlsl(scaled, GL20.GL_VERTEX_SHADER, List.of());
        assertTrue(hlsl.matches("(?s).*uint2 mc_midTexCoord : TEXCOORD1;.*"), "unsigned data must arrive as uint2:\n" + hlsl);
        assertTrue(hlsl.matches("(?s).*int4 at_midBlock : TEXCOORD2;.*"), "signed data must arrive as int4:\n" + hlsl);
        assertTrue(hlsl.matches("(?s).*float3 a_Position : TEXCOORD0;.*"), "float-fed inputs stay float:\n" + hlsl);
        assertFalse(hlsl.contains("asfloat"), "values must be converted, never reinterpreted:\n" + hlsl);

        final String lib = System.getenv("ANGELICA_DXC_LIB");
        if (lib != null) {
            final byte[] dxil = DxcCompiler.load(Path.of(lib)).compile(hlsl, "vs_6_0");
            assertArrayEquals("DXBC".getBytes(StandardCharsets.US_ASCII), java.util.Arrays.copyOf(dxil, 4));
        }
    }

    @Test
    void nothingToConvertKeepsTheModule() {
        final int[] w = words();
        assertSame(w, ScaledInputs.rewrite(w, 0, 0));
        assertSame(w, ScaledInputs.rewrite(w, 1 << 7, 0), "a location with no input leaves the module alone");
    }
}
