package com.gtnewhorizons.angelica.metal.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MslNanInfRewriteTest {

    private static final String MSL = """
        #include <metal_stdlib>
        #include <simd/simd.h>

        using namespace metal;

        fragment float4 main0(float4 v [[stage_in]])
        {
            float h = isnan(v.x) ? 192.0 : v.x;
            bool3 b = isinf(v.xyz);
            bool c = metal::isfinite(v.w);
            return float4(h, float(b.x), float(c), float(any(isnan(v))));
        }
        """;

    @Test
    void callsAreRedirectedToBitTests() {
        final String out = MslCompiler.bitTestNanInf(MSL);
        assertTrue(out.contains("? 192.0 : v.x"));
        assertTrue(out.contains("angelica_isnan(v.x)"));
        assertTrue(out.contains("angelica_isinf(v.xyz)"));
        assertTrue(out.contains("any(angelica_isnan(v))"));
        assertTrue(out.contains("metal::isfinite(v.w)"), "qualified calls are left alone");
        assertTrue(out.indexOf("inline bool angelica_isnan(float x)") > out.indexOf("using namespace metal;"));
        assertTrue(out.indexOf("inline bool angelica_isnan(float x)") < out.indexOf("main0("));
    }

    @Test
    void rewrittenSourceNoLongerForcesSafeMath() {
        assertTrue(ProgramLinker.needsPreciseMath(MSL.replace("isfinite", "isunordered")));
        assertFalse(ProgramLinker.needsPreciseMath(MslCompiler.bitTestNanInf(MSL)));
    }

    @Test
    void sourceWithoutNanInfIsUntouched() {
        final String plain = "using namespace metal;\nfragment float4 f() { return float4(sin(1.0)); }\n";
        assertSame(plain, MslCompiler.bitTestNanInf(plain));
        assertEquals(1, MslCompiler.bitTestNanInf(MSL).split("fragment float4 main0", -1).length - 1);
    }
}
