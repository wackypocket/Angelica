package com.gtnewhorizons.angelica.glemu.shader;


import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpirvStripUnusedOpaqueTest {

    private static final String FS = """
        #version 460
        layout(location = 0) out vec4 o;
        uniform sampler2D used;
        uniform sampler2D viaHelper;
        uniform sampler2D unused;
        uniform sampler2DShadow alsoUnused;
        uniform sampler2D unusedToo;
        uniform sampler2D deadOnly;
        uniform float scale;
        vec4 helper(sampler2D s) { return texture(s, vec2(0.5)); }
        vec4 neverCalled() { return texture(deadOnly, vec2(0.5)); }
        void main() {
            o = texture(used, vec2(0.0)) * scale + helper(viaHelper);
        }
        """;

    @Test
    void onlyStaticallyUsedSamplersAreReflected() {
        final ShaderCompiler.Result r = ShaderCompiler.compile(FS, GL20.GL_FRAGMENT_SHADER, "strip", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        final StageReflection refl = new StageReflection(new Spirv(ByteBuffer.wrap(r.compiled().spirv())), Collections.emptySet());

        final Set<String> samplers = new HashSet<>();
        for (StageReflection.Resource res : refl.resources) samplers.add(res.name);
        assertEquals(Set.of("used", "viaHelper"), samplers);
        assertEquals(1, refl.defaultBlock.size(), "plain uniforms are untouched");
    }

    @Test
    void moduleWithOnlyUsedSamplersIsUnchanged() {
        final ShaderCompiler.Result r = ShaderCompiler.compile("#version 460\nlayout(location = 0) out vec4 o;\nuniform sampler2D t;\nvoid main() { o = texture(t, vec2(0.0)); }\n",
            GL20.GL_FRAGMENT_SHADER, "allused", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        final byte[] once = r.compiled().spirv();
        assertEquals(ByteBuffer.wrap(once), ByteBuffer.wrap(Spirv.stripUnusedOpaque(once)));
    }
}
