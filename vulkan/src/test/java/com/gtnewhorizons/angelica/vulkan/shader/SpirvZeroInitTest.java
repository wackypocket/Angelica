package com.gtnewhorizons.angelica.vulkan.shader;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpirvZeroInitTest {

    private static final String FS = """
        #version 460
        layout(location = 0) out vec4 o;
        uniform sampler2D tex;
        uniform int n;
        vec4 accumulate() {
            vec4 acc;
            float w[4];
            for (int i = 0; i < n; i++) acc += texelFetch(tex, ivec2(i, 0), 0);
            return acc;
        }
        void main() { o = accumulate(); }
        """;

    @Test
    void uninitialisedLocalsGetNullInitialisers() {
        final ShaderCompiler.Result r = ShaderCompiler.compile(FS, GL20.GL_FRAGMENT_SHADER, "zeroinit", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        final int[] w = words(r.compiled().spirv());

        final java.util.Set<Integer> nulls = new java.util.HashSet<>();
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) == Spirv.OP_CONSTANT_NULL) nulls.add(w[i + 2]);
        }
        int locals = 0;
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) != Spirv.OP_VARIABLE || w[i + 3] != 7) continue;
            locals++;
            assertEquals(5, w[i] >>> 16, "Function variable %" + w[i + 2] + " has no initialiser");
            assertTrue(nulls.contains(w[i + 4]), "initialiser of %" + w[i + 2] + " is not an OpConstantNull");
        }
        assertTrue(locals > 0, "expected Function-scope variables in the module");
    }

    @Test
    void moduleWithoutLocalsIsUnchanged() {
        final ShaderCompiler.Result r = ShaderCompiler.compile("#version 460\nlayout(location = 0) out vec4 o;\nvoid main() { o = vec4(1.0); }\n",
            GL20.GL_FRAGMENT_SHADER, "nolocals", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        final byte[] once = r.compiled().spirv();
        assertEquals(ByteBuffer.wrap(once), ByteBuffer.wrap(Spirv.zeroInitLocals(once)));
    }

    private static int[] words(byte[] spirv) {
        final IntBuffer ib = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        final int[] w = new int[ib.remaining()];
        ib.get(w);
        return w;
    }
}
