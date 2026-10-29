package com.gtnewhorizons.angelica.glemu.shader;


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
    void localsReadBeforeWrittenGetNullInitialisers() {
        final ShaderCompiler.Result r = ShaderCompiler.compile(FS, GL20.GL_FRAGMENT_SHADER, "zeroinit", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        final int[] w = words(r.compiled().spirv());

        final java.util.Set<Integer> nulls = new java.util.HashSet<>();
        final java.util.Map<Integer, String> names = new java.util.HashMap<>();
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) == Spirv.OP_CONSTANT_NULL) nulls.add(w[i + 2]);
            if ((w[i] & 0xFFFF) == Spirv.OP_NAME) names.put(w[i + 1], literal(w, i + 2, i + (w[i] >>> 16)));
        }
        final java.util.Map<String, Integer> init = new java.util.HashMap<>();
        for (int i = 5; i < w.length; i += w[i] >>> 16) {
            if ((w[i] & 0xFFFF) != Spirv.OP_VARIABLE || w[i + 3] != 7) continue;
            final String name = names.getOrDefault(w[i + 2], "%" + w[i + 2]);
            if ((w[i] >>> 16) == 5) {
                assertTrue(nulls.contains(w[i + 4]), "initialiser of " + name + " is not an OpConstantNull");
                init.put(name, w[i + 4]);
            } else {
                init.put(name, null);
            }
        }
        assertNotNull(init.get("acc"), "acc has no initialiser");
        if (init.containsKey("w")) assertNotNull(init.get("w"), "w has no initialiser");
        assertTrue(init.containsKey("i"), "expected a variable named i");
        assertEquals(null, init.get("i"), "i is written first and needs no initialiser");
    }

    @Test
    void moduleWithoutLocalsIsUnchanged() {
        final ShaderCompiler.Result r = ShaderCompiler.compile("#version 460\nlayout(location = 0) out vec4 o;\nvoid main() { o = vec4(1.0); }\n",
            GL20.GL_FRAGMENT_SHADER, "nolocals", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        final byte[] once = r.compiled().spirv();
        assertEquals(ByteBuffer.wrap(once), ByteBuffer.wrap(Spirv.zeroInitLocals(once)));
    }

    private static String literal(int[] w, int from, int end) {
        final StringBuilder sb = new StringBuilder();
        for (int i = from; i < end; i++) {
            for (int b = 0; b < 4; b++) {
                final int c = (w[i] >>> (8 * b)) & 0xFF;
                if (c == 0) return sb.toString();
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    private static int[] words(byte[] spirv) {
        final IntBuffer ib = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        final int[] w = new int[ib.remaining()];
        ib.get(w);
        return w;
    }
}
