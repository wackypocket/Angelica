package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpirvSplitBlockArraysTest {

    private static int blockVariables(String body) {
        final String source = "#version 460\nlayout(local_size_x = 1) in;\nlayout(std430, binding = 0) buffer Data { float v; int n; } data[3][2];\nuniform int i, j;\nvoid main() { " + body + " }\n";
        final ShaderCompiler.Result result = ShaderCompiler.compile(source, GL43.GL_COMPUTE_SHADER, "split", ShaderCompiler.DEPTH_NONE);
        assertNotNull(result.compiled(), result.log());
        final Spirv spirv = new Spirv(ByteBuffer.wrap(result.compiled().spirv()).order(ByteOrder.LITTLE_ENDIAN));
        int count = 0;
        for (Spirv.Variable v : spirv.variables) {
            if (spirv.name(v.id).startsWith(Spirv.BLOCK_ELEMENT)) count++;
        }
        return count;
    }

    @Test
    void aConstantSubscriptSplitsTheArray() {
        assertEquals(6, blockVariables("data[1][1].v = 1.0;"));
    }

    @Test
    void aSubscriptKnownOnlyAtRunTimeSplitsItToo() {
        assertEquals(6, blockVariables("data[i][j].v += data[j][i].v;"));
    }

    @Test
    void atomicsThroughARunTimeSubscriptSplitIt() {
        assertEquals(6, blockVariables("atomicAdd(data[i][1].n, 1);"));
    }
}
