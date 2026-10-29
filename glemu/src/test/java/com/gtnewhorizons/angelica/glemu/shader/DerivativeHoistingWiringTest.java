package com.gtnewhorizons.angelica.glemu.shader;


import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivativeHoistingWiringTest {

    private static final String BRANCHED_DERIVATIVE = """
        #version 420 core
        in vec2 lmCoord;
        out vec4 color;
        void main() {
            float result = lmCoord.x;
            if (lmCoord.x > 0.035) {
                result *= abs(dFdx(lmCoord.x)) + abs(dFdy(lmCoord.x));
            }
            color = vec4(result);
        }
        """;

    @Test
    void fragmentDerivativesLeaveTheBranchAndStillCompile() {
        final String out = ShaderCompiler.hoistDerivatives(BRANCHED_DERIVATIVE, GL20.GL_FRAGMENT_SHADER);
        final int branch = out.indexOf("if (lmCoord.x > 0.035)");
        assertTrue(branch > 0, out);
        assertFalse(out.substring(branch).contains("dFdx("), out);
        assertTrue(out.substring(0, branch).contains("= dFdx(lmCoord.x);"), out);

        final ShaderCompiler.Result compiled = ShaderCompiler.compile(BRANCHED_DERIVATIVE, GL20.GL_FRAGMENT_SHADER, "hoist.fsh", ShaderCompiler.DEPTH_NONE);
        assertNotNull(compiled.compiled(), compiled.log());
    }

    @Test
    void otherStagesKeepTheirDerivativesInPlace() {
        assertEquals(BRANCHED_DERIVATIVE, ShaderCompiler.hoistDerivatives(BRANCHED_DERIVATIVE, GL20.GL_VERTEX_SHADER));
    }
}
