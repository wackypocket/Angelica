package com.gtnewhorizons.angelica.d3d12.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FxcLoopsTest {
    @Test
    void hintsBoundedAndInfiniteLoops() {
        final String in = "void main() {\n    for (int i = 0; i < 8; i++) {\n        x += i;\n    }\n    for (;;) {\n        if (y) break;\n    }\n    while (true) {\n        break;\n    }\n}\n";
        final String out = FxcLoops.addLoopHints(in);
        assertEquals(in.replace("for (int", "[loop] for (int").replace("for (;;)", "[loop] for (;;)").replace("while (true)", "[loop] while (true)"), out);
    }

    @Test
    void hintsDoWhileOnceAndSkipsItsTrailingCondition() {
        final String in = "    do {\n        x++;\n    } while (false);\n    do\n    {\n        y++;\n    } while (y < 3);\n";
        assertEquals(in.replace("do {", "[loop] do {").replace("do\n", "[loop] do\n"), FxcLoops.addLoopHints(in));
    }

    @Test
    void ignoresIdentifiersAndExistingHints() {
        final String in = "int format = 1;\nint dot_product = 2;\n[unroll] for (int i = 0; i < 2; i++) {\n    a += i;\n}\n";
        assertEquals(in, FxcLoops.addLoopHints(in));
    }

    @Test
    void samplingInsideLoopsBecomesExplicitLevel() {
        final String in = "float4 f() {\n    c += t.Sample(s, uv);\n    for (int i = 0; i < 4; i++) {\n        c += t.Sample(s, uv + float2(i, 0));\n        c += t.Sample(s, uv, int2(1, 1));\n        c += t.SampleBias(s, uv, 1.5f);\n        d += sh.SampleCmp(cs, uv, z);\n        e += t.SampleLevel(s, uv, 2.0f);\n        g += t.SampleGrad(s, uv, dx, dy);\n    }\n    return c;\n}\n";
        final String expected = "float4 f() {\n    c += t.Sample(s, uv);\n    for (int i = 0; i < 4; i++) {\n        c += t.SampleLevel(s, uv + float2(i, 0), 0.0f);\n        c += t.SampleLevel(s, uv, 0.0f, int2(1, 1));\n        c += t.SampleLevel(s, uv, 1.5f);\n        d += sh.SampleCmpLevelZero(cs, uv, z);\n        e += t.SampleLevel(s, uv, 2.0f);\n        g += t.SampleGrad(s, uv, dx, dy);\n    }\n    return c;\n}\n";
        assertEquals(expected, FxcLoops.levelZeroSamplingInLoops(in));
        assertEquals(expected.replace("    for", "    [loop] for"), FxcLoops.addLoopHints(FxcLoops.levelZeroSamplingInLoops(in)));
    }

    @Test
    void samplingOutsideLoopsIsUntouched() {
        final String in = "float4 f() {\n    c += t.Sample(s, uv);\n    d += sh.SampleCmp(cs, uv, z);\n    return c;\n}\n";
        assertEquals(in, FxcLoops.levelZeroSamplingInLoops(in));
    }
}
