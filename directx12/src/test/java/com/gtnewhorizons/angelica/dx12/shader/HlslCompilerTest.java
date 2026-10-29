package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.glemu.shader.StageReflection;
import com.gtnewhorizons.angelica.glemu.shader.Spirv;
import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;

import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class HlslCompilerTest {

    private static final String VS = """
        #version 460 core
        layout(location = 0) in vec3 a_Position;
        layout(location = 2) in vec2 a_TexCoord0;
        layout(std140, binding = 0) uniform Matrices { mat4 mvp; } u;
        uniform sampler2D vsSampler;
        uniform float bias;
        out vec2 v_TexCoord0;
        void main() {
            gl_Position = u.mvp * vec4(a_Position, 1.0 + texture(vsSampler, vec2(0.0)).r * bias);
            v_TexCoord0 = a_TexCoord0;
        }
        """;

    private static final String FS = """
        #version 460 core
        in vec2 v_TexCoord0;
        uniform sampler2D u_Sampler0;
        layout(std140, binding = 1) uniform Color { vec4 tint; } c;
        out vec4 fragColor;
        void main() { fragColor = texture(u_Sampler0, v_TexCoord0) * c.tint; }
        """;

    private static final String CS = """
        #version 460 core
        layout(local_size_x = 64) in;
        layout(std430, binding = 0) readonly buffer Src { uint src[]; };
        layout(std430, binding = 1) buffer Dst { uint dst[]; };
        shared uint partial[64];
        void main() {
            partial[gl_LocalInvocationID.x] = src[gl_GlobalInvocationID.x];
            barrier();
            if (gl_LocalInvocationID.x == 0u) {
                uint sum = 0u;
                for (int i = 0; i < 64; i++) sum += partial[i];
                dst[gl_WorkGroupID.x] = sum;
            }
        }
        """;

    @AfterEach
    void resetCaches() {
        Reflect.setStatic(ShaderDiskCache.class, "root", null);
        Reflect.<java.util.Map<?, ?>>getStatic(HlslCompiler.class, "CACHE").clear();
    }

    private record Stage(int[] words, List<HlslCompiler.Binding> bindings) {}

    private static Stage stage(String glsl, int glType) {
        final ShaderCompiler.Result r = ShaderCompiler.compile(glsl, glType, "hlsl-test", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        final Spirv spirv = new Spirv(ByteBuffer.wrap(r.compiled().spirv()).order(ByteOrder.LITTLE_ENDIAN));
        final StageReflection refl = new StageReflection(spirv, Collections.emptySet(), r.compiled().bindings());
        final List<HlslCompiler.Binding> bindings = new ArrayList<>();
        int binding = 0, cbv = 0, srv = 0, uav = 0, sampler = 0;
        if (refl.defaultBlockVar >= 0) {
            spirv.setDecoration(refl.defaultBlockVar, Spirv.DEC_BINDING, binding);
            spirv.setDecoration(refl.defaultBlockVar, Spirv.DEC_DESCRIPTOR_SET, 0);
            bindings.add(new HlslCompiler.Binding(binding++, Program.BindingKind.DEFAULT_BLOCK, 1, cbv++, -1, -1, false));
        }
        for (StageReflection.Resource res : refl.resources) {
            spirv.setDecoration(res.varId, Spirv.DEC_BINDING, binding);
            spirv.setDecoration(res.varId, Spirv.DEC_DESCRIPTOR_SET, 0);
            final HlslCompiler.Binding b = switch (res.kind) {
                case UNIFORM_BUFFER -> new HlslCompiler.Binding(binding, Program.BindingKind.UNIFORM_BUFFER, 1, cbv++, -1, -1, false);
                case STORAGE_BUFFER -> res.readOnly
                    ? new HlslCompiler.Binding(binding, Program.BindingKind.STORAGE_BUFFER, 1, srv++, -1, -1, false)
                    : new HlslCompiler.Binding(binding, Program.BindingKind.STORAGE_BUFFER, 1, uav++, -1, -1, true);
                case COMBINED_SAMPLER -> new HlslCompiler.Binding(binding, Program.BindingKind.COMBINED_SAMPLER, 1, -1, srv++, sampler++, false);
                default -> throw new AssertionError(res.kind);
            };
            bindings.add(b);
            binding++;
        }
        return new Stage(spirv.words, bindings);
    }

    static Stream<Arguments> stages() {
        return Stream.of(
            Arguments.of("vertex", VS, GL20.GL_VERTEX_SHADER, List.of("space0", "space1"), List.of("space2", "space3")),
            Arguments.of("fragment", FS, GL20.GL_FRAGMENT_SHADER, List.of("space2", "space3"), List.of("space0", "space1")),
            Arguments.of("compute", CS, GL43.GL_COMPUTE_SHADER, List.of("space0", "space2"), List.of("space3")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stages")
    void everyStageCompilesToDxbc(String name, String glsl, int glType, List<String> present, List<String> absent) {
        final Stage s = stage(glsl, glType);
        final byte[] dxbc = HlslCompiler.compile(s.words, glType, s.bindings);
        assertTrue(dxbc.length > 4, name + ": DXBC too short");
        assertArrayEquals("DXBC".getBytes(StandardCharsets.US_ASCII), java.util.Arrays.copyOf(dxbc, 4), name + ": no DXBC container magic");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stages")
    void stageUsesOnlyItsOwnRegisterSpaces(String name, String glsl, int glType, List<String> present, List<String> absent) {
        final Stage s = stage(glsl, glType);
        final String hlsl = HlslCompiler.toHlsl(s.words, glType, s.bindings);
        for (String space : present) assertTrue(hlsl.contains(space), name + ": HLSL must reference " + space + ".\n" + hlsl);
        for (String space : absent) assertFalse(hlsl.contains(space), name + ": HLSL must not reference " + space + ".\n" + hlsl);
    }

    @Test
    void readOnlyStorageIsAnSrvAndWritableStorageIsAUav() {
        final Stage s = stage(CS, GL43.GL_COMPUTE_SHADER);
        final String hlsl = HlslCompiler.toHlsl(s.words, GL43.GL_COMPUTE_SHADER, s.bindings);
        assertTrue(hlsl.matches("(?s).*ByteAddressBuffer\\s+\\w+\\s*:\\s*register\\(t0,\\s*space0\\).*"), "read-only SSBO must be t0 in space0:\n" + hlsl);
        assertTrue(hlsl.matches("(?s).*RWByteAddressBuffer\\s+\\w+\\s*:\\s*register\\(u0,\\s*space2\\).*"), "writable SSBO must be u0 in space2:\n" + hlsl);
    }

    @Test
    void computeKeepsGroupsharedMemoryAndBarrier() {
        final Stage s = stage(CS, GL43.GL_COMPUTE_SHADER);
        final String hlsl = HlslCompiler.toHlsl(s.words, GL43.GL_COMPUTE_SHADER, s.bindings);
        assertTrue(hlsl.contains("groupshared"), "shared array must become groupshared:\n" + hlsl);
        assertTrue(hlsl.contains("GroupMemoryBarrierWithGroupSync"), "barrier() must become a group sync:\n" + hlsl);
    }

    @Test
    void vertexOutputIsFlippedForTheTopLeftFramebufferOrigin() {
        final Stage s = stage(VS, GL20.GL_VERTEX_SHADER);
        final String hlsl = HlslCompiler.toHlsl(s.words, GL20.GL_VERTEX_SHADER, s.bindings);
        assertTrue(hlsl.contains("gl_Position.y = -gl_Position.y"), "vertex Y must be flipped:\n" + hlsl);
    }

    @Test
    void garbageHlslSurfacesTheCompilerMessage() {
        final RuntimeException ex = assertThrows(RuntimeException.class, () -> D3DCompiler.compile("this is not valid hlsl !!!", "main", "vs_5_1", 0));
        assertTrue(ex.getMessage().contains("D3DCompile(vs_5_1) failed"), ex.getMessage());
        assertTrue(ex.getMessage().contains("error"), "the compiler's own error text must be included: " + ex.getMessage());
    }

    @Test
    void repeatedCompilesReuseTheBlob() {
        final Stage s = stage(FS, GL20.GL_FRAGMENT_SHADER);
        final byte[] first = HlslCompiler.compile(s.words, GL20.GL_FRAGMENT_SHADER, s.bindings);
        assertSame(first, HlslCompiler.compile(s.words, GL20.GL_FRAGMENT_SHADER, s.bindings));
    }

    @Test
    void diskCacheHitReturnsTheStoredBlob(@TempDir Path dir) {
        ShaderDiskCache.configure(dir, "t");
        final Stage s = stage(FS, GL20.GL_FRAGMENT_SHADER);
        final byte[] real = HlslCompiler.compile(s.words, GL20.GL_FRAGMENT_SHADER, s.bindings);
        Reflect.<java.util.Map<?, ?>>getStatic(HlslCompiler.class, "CACHE").clear();
        assertArrayEquals(real, HlslCompiler.compile(s.words, GL20.GL_FRAGMENT_SHADER, s.bindings), "a disk hit must return the compiled blob");
    }

    @Test
    void clipMaskSpecializationRewritesTheConstant() {
        final String vs = """
            #version 460 core
            layout(location = 0) in vec4 pos;
            void main() {
                gl_Position = pos;
                gl_ClipDistance[0] = pos.x;
                gl_ClipDistance[1] = pos.y;
            }
            """;
        final Stage s = stage(vs, GL20.GL_VERTEX_SHADER);
        assertTrue(Spirv.hasSpecId(s.words, ShaderCompiler.CLIP_MASK_SPEC_ID), "clip distances must get the clip-mask constant");
        final String all = HlslCompiler.toHlsl(s.words, GL20.GL_VERTEX_SHADER, s.bindings);
        final String one = HlslCompiler.toHlsl(Spirv.specialize(s.words, ShaderCompiler.CLIP_MASK_SPEC_ID, 1), GL20.GL_VERTEX_SHADER, s.bindings);
        final String constant = "#define SPIRV_CROSS_CONSTANT_ID_" + ShaderCompiler.CLIP_MASK_SPEC_ID + " ";
        assertTrue(all.contains(constant + "255"), "the default mask enables every plane:\n" + all);
        assertTrue(one.contains(constant + "1\n") || one.contains(constant + "1\r"), "the specialized mask must be 1:\n" + one);
        assertEquals(s.words.length, Spirv.specialize(s.words, ShaderCompiler.CLIP_MASK_SPEC_ID, 1).length, "specialization rewrites in place");
    }
}
