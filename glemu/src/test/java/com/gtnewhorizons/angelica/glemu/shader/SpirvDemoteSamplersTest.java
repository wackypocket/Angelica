package com.gtnewhorizons.angelica.glemu.shader;


import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpirvDemoteSamplersTest {

    private static final String FS = """
        #version 460
        layout(location = 0) out vec4 o;
        uniform sampler2D fetched;
        uniform sampler2D sized;
        uniform sampler2D filtered;
        uniform sampler2D mixed;
        uniform sampler2D passed;
        uniform sampler2D gathered;
        vec4 helper(sampler2D s) { return texelFetch(s, ivec2(0), 0); }
        void main() {
            ivec2 sz = textureSize(sized, 0);
            o = texelFetch(fetched, ivec2(1, 2), 0) + texelFetch(fetched, sz, 0)
                + texture(filtered, vec2(0.5))
                + texelFetch(mixed, ivec2(0), 0) + texture(mixed, vec2(0.5))
                + helper(passed)
                + textureGather(gathered, vec2(0.5));
        }
        """;

    private static Spirv compile() {
        final ShaderCompiler.Result r = ShaderCompiler.compile(FS, GL20.GL_FRAGMENT_SHADER, "demote", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        return new Spirv(ByteBuffer.wrap(r.compiled().spirv()).order(ByteOrder.LITTLE_ENDIAN));
    }

    @Test
    void onlySamplerStateFreeReadsAreFetchOnly() {
        assertEquals(Set.of("fetched", "sized"), compile().fetchOnlySamplers());
    }

    @Test
    void demotedSamplersReflectAsSampledImages() {
        final Spirv demoted = new Spirv(ByteBuffer.wrap(compile().demoteSamplers(Set.of("fetched", "sized"))).order(ByteOrder.LITTLE_ENDIAN));
        final Map<String, StageReflection.Resource> byName = new HashMap<>();
        for (StageReflection.Resource res : new StageReflection(demoted, Collections.emptySet()).resources) byName.put(res.name, res);

        assertEquals(StageReflection.Kind.SAMPLED_IMAGE, byName.get("fetched").kind);
        assertEquals(StageReflection.Kind.SAMPLED_IMAGE, byName.get("sized").kind);
        assertEquals(GL20.GL_SAMPLER_2D, byName.get("fetched").glType, "GL still sees a sampler2D uniform");
        assertEquals(StageReflection.Kind.COMBINED_SAMPLER, byName.get("filtered").kind);
        assertEquals(StageReflection.Kind.COMBINED_SAMPLER, byName.get("mixed").kind);
        assertEquals(StageReflection.Kind.COMBINED_SAMPLER, byName.get("passed").kind);
        assertEquals(StageReflection.Kind.COMBINED_SAMPLER, byName.get("gathered").kind);
    }

    @Test
    void demotedModuleTranslatesToMsl() {
        final byte[] demoted = compile().demoteSamplers(Set.of("fetched", "sized"));
        final String msl = toMsl(demoted);
        assertNotNull(msl);
    }

    @Test
    void nothingToDemoteReturnsNull() {
        assertNull(compile().demoteSamplers(Set.of("filtered_typo")));
    }

    private static String toMsl(byte[] spirv) {
        final IntBuffer words = MemoryUtil.memAllocInt(spirv.length / 4);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            words.put(ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()).flip();
            final PointerBuffer p = stack.mallocPointer(1);
            assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_context_create(p));
            final long ctx = p.get(0);
            try {
                assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_context_parse_spirv(ctx, words, words.remaining(), p), Spvc.spvc_context_get_last_error_string(ctx));
                final long ir = p.get(0);
                assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_context_create_compiler(ctx, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p));
                final long compiler = p.get(0);
                assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_compiler_compile(compiler, p), Spvc.spvc_context_get_last_error_string(ctx));
                return MemoryUtil.memUTF8(p.get(0));
            } finally {
                Spvc.spvc_context_destroy(ctx);
            }
        } finally {
            MemoryUtil.memFree(words);
        }
    }
}
