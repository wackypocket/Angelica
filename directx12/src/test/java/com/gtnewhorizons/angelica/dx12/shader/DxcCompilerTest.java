package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;
import com.gtnewhorizons.angelica.glemu.shader.Spirv;
import com.gtnewhorizons.angelica.glemu.shader.StageReflection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "ANGELICA_DXC_LIB", matches = ".+")
class DxcCompilerTest {

    private static DxcCompiler dxc;

    @BeforeAll
    static void load() {
        dxc = DxcCompiler.load(Path.of(System.getenv("ANGELICA_DXC_LIB")));
    }

    private static final String FS = """
        #version 330 core
        uniform sampler2D tex;
        uniform int steps;
        in vec2 uv;
        out vec4 fragColor;
        void main() {
            float acc = 0.0;
            for (int i = 0; i < steps; i++) acc += texture(tex, uv + float(i) * 0.01).x;
            fragColor = vec4(acc);
        }
        """;

    private static String hlsl(String glsl, int glType) {
        final ShaderCompiler.Result r = ShaderCompiler.compile(glsl, glType, "dxc-test", ShaderCompiler.DEPTH_NONE);
        assertNotNull(r.compiled(), r.log());
        final Spirv spirv = new Spirv(ByteBuffer.wrap(r.compiled().spirv()).order(ByteOrder.LITTLE_ENDIAN));
        final StageReflection refl = new StageReflection(spirv, Collections.emptySet());
        final List<HlslCompiler.Binding> bindings = new ArrayList<>();
        int binding = 0, srv = 0;
        if (refl.defaultBlockVar >= 0) {
            spirv.setDecoration(refl.defaultBlockVar, Spirv.DEC_BINDING, binding);
            spirv.setDecoration(refl.defaultBlockVar, Spirv.DEC_DESCRIPTOR_SET, 0);
            bindings.add(new HlslCompiler.Binding(binding++, Program.BindingKind.DEFAULT_BLOCK, 1, 0, -1, -1, false));
        }
        for (StageReflection.Resource res : refl.resources) {
            spirv.setDecoration(res.varId, Spirv.DEC_BINDING, binding);
            spirv.setDecoration(res.varId, Spirv.DEC_DESCRIPTOR_SET, 0);
            bindings.add(new HlslCompiler.Binding(binding++, Program.BindingKind.COMBINED_SAMPLER, 1, -1, srv, srv++, false));
        }
        return HlslCompiler.toHlsl(spirv.words, glType, bindings);
    }

    private static boolean hasPart(byte[] container, String fourcc) {
        return new String(container, StandardCharsets.ISO_8859_1).contains(fourcc);
    }

    @Test
    void compilesASampleInADynamicLoopToDxil() {
        final byte[] out = dxc.compile(hlsl(FS, GL20.GL_FRAGMENT_SHADER), "ps_6_0");
        assertArrayEquals("DXBC".getBytes(StandardCharsets.US_ASCII), java.util.Arrays.copyOf(out, 4), "DXIL ships in a DXBC container");
        assertTrue(hasPart(out, "DXIL"), "the container must carry a DXIL part");
    }

    @Test
    void errorsCarryTheCompilerMessage() {
        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> dxc.compile("float4 main() : SV_Target { return nope; }", "ps_6_0"));
        assertTrue(e.getMessage().contains("DXC(ps_6_0) failed") && e.getMessage().contains("nope"), e.getMessage());
    }

    @Test
    void compilesConcurrentlyOnSeparateThreads() {
        final String src = hlsl(FS, GL20.GL_FRAGMENT_SHADER);
        final List<CompletableFuture<byte[]>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) jobs.add(CompletableFuture.supplyAsync(() -> dxc.compile(src, "ps_6_0")));
        final byte[] first = jobs.get(0).join();
        for (CompletableFuture<byte[]> job : jobs) assertArrayEquals(first, job.join());
    }
}
