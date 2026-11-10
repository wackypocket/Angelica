package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompiledCodecTest {

    @Test
    void aResultComesBackWhole() {
        final Map<String, Integer> bindings = new LinkedHashMap<>();
        bindings.put("z", 3);
        bindings.put("a", 9);
        final ShaderCompiler.Compiled in = new ShaderCompiler.Compiled(new byte[] { 3, 2, 0x23, 7 },
            new LinkedHashSet<>(List.of("flag", "on")), Set.of(), bindings, Map.of("v", 1), Map.of());
        final ShaderCompiler.Compiled out = CompiledCodec.decode(CompiledCodec.encode(in));
        assertArrayEquals(in.spirv(), out.spirv());
        assertEquals(List.of("flag", "on"), List.copyOf(out.boolUniforms()));
        assertEquals(in.explicitInputs(), out.explicitInputs());
        assertEquals(List.of("z", "a"), List.copyOf(out.bindings().keySet()));
        assertEquals(in.bindings(), out.bindings());
        assertEquals(in.explicitVaryings(), out.explicitVaryings());
        assertEquals(in.explicitUniforms(), out.explicitUniforms());
        out.explicitUniforms().put("still", 1);
    }

    @Test
    void damagedBytesAreAMiss() {
        final byte[] good = CompiledCodec.encode(new ShaderCompiler.Compiled(new byte[8], Set.of(), Set.of(), Map.of(), Map.of(), Map.of()));
        assertNull(CompiledCodec.decode(null));
        assertNull(CompiledCodec.decode(new byte[] { 0, 0, 0, 9 }));
        assertNull(CompiledCodec.decode(java.util.Arrays.copyOf(good, good.length - 1)));
        final byte[] longer = java.util.Arrays.copyOf(good, good.length + 1);
        assertNull(CompiledCodec.decode(longer));
    }
}
