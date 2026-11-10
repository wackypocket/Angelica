package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BorderLoweringTest {
    private static final String SAMPLED = "uniform sampler2D tex;\nvoid main() { c = texture(tex, uv); d = textureLod(tex, uv, 1.0); }";

    @Test
    void onlyBlackAndWhiteArePresets() {
        assertTrue(BorderLowering.isPreset(0f, 0f, 0f, 0f));
        assertTrue(BorderLowering.isPreset(0f, 0f, 0f, 1f));
        assertTrue(BorderLowering.isPreset(1f, 1f, 1f, 1f));
        assertFalse(BorderLowering.isPreset(1f, 0f, 0f, 1f));
        assertFalse(BorderLowering.isPreset(1f, 1f, 1f, 0f));
    }

    @Test
    void theSameSamplersShareAKey() {
        final BorderLowering.Sampler sampler = new BorderLowering.Sampler("same", true, true, false, "(1.0, 0.0, 0.0, 1.0)", false);
        assertEquals(BorderLowering.keyOf(List.of(sampler)), BorderLowering.keyOf(List.of(sampler)));
        final long other = BorderLowering.keyOf(List.of(new BorderLowering.Sampler("same", true, false, false, "(1.0, 0.0, 0.0, 1.0)", false)));
        assertTrue(other != BorderLowering.keyOf(List.of(sampler)));
    }

    @Test
    void aSamplePastTheEdgeBecomesTheColor() {
        final long key = BorderLowering.keyOf(List.of(new BorderLowering.Sampler("tex", true, true, false, "(1.0, 0.0, 0.0, 1.0)", false)));
        final String lowered = BorderLowering.lower(SAMPLED, key, true);
        assertTrue(lowered.contains("c = angelica_bordered_tex( uv)") || lowered.contains("c = angelica_bordered_tex(uv)"));
        assertTrue(lowered.contains("angelica_bordered_tex_lod("));
        assertTrue(lowered.contains("c.x < 0.0 || c.x >= 1.0 || c.y < 0.0 || c.y >= 1.0"));
        assertTrue(lowered.contains("vec4(1.0, 0.0, 0.0, 1.0)"));
    }

    @Test
    void onlyAFragmentShaderGetsTheBias() {
        final long key = BorderLowering.keyOf(List.of(new BorderLowering.Sampler("tex", true, false, false, "(0.5, 0.5, 0.5, 1.0)", false)));
        assertTrue(BorderLowering.lower(SAMPLED, key, true).contains("float bias"));
        assertFalse(BorderLowering.lower(SAMPLED, key, false).contains("float bias"));
    }

    @Test
    void facesStayApartByKeepingToTheirLastTexels() {
        final String source = "uniform samplerCube sky;\nvoid main() { c = texture(sky, d); e = textureLod(sky, d, 2.0); }";
        final String lowered = BorderLowering.lower(source, BorderLowering.keyOf(List.of(BorderLowering.Sampler.apartFaces("sky"))), true);
        assertTrue(lowered.contains("1.0 - 1.0 / size"));
        assertTrue(lowered.contains("textureGrad(sky,"));
        assertFalse(lowered.contains("= texture(sky"));
        assertFalse(lowered.contains("= textureLod(sky"));
    }

    @Test
    void outsideAFragmentShaderFacesUseTheBaseLevel() {
        final String source = "uniform samplerCubeArray sky;\nvoid main() { c = texture(sky, d); }";
        final String lowered = BorderLowering.lower(source, BorderLowering.keyOf(List.of(BorderLowering.Sampler.apartFaces("sky"))), false);
        assertTrue(lowered.contains("(vec4 c) { return angelica_faces_sky_lod(c, 0.0); }"));
        assertFalse(lowered.contains("dFdx"));
    }
}
