package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL40;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TessellatorTest {
    private static Tessellator quads(int spacing) {
        return new Tessellator(GL11.GL_QUADS, spacing, GL11.GL_CCW, false, 64);
    }

    private static Set<Long> points(Tessellator t) {
        final Set<Long> out = new HashSet<>();
        for (int i = 0; i + 2 < t.coordinates.size(); i += 3) out.add(key(t.coordinates.getFloat(i), t.coordinates.getFloat(i + 1)));
        return out;
    }

    private static long key(float u, float v) {
        return (long) Float.floatToIntBits(u + 0.0f) << 32 | Float.floatToIntBits(v + 0.0f) & 0xFFFFFFFFL;
    }

    @Test
    void quadCoordinatesAreExactlySymmetric() {
        final Tessellator t = quads(GL11.GL_EQUAL);
        t.tessellate(new float[] {7, 7, 7, 7}, new float[] {4, 5});
        final Set<Long> points = points(t);
        for (int i = 0; i + 2 < t.coordinates.size(); i += 3) {
            final float u = t.coordinates.getFloat(i), v = t.coordinates.getFloat(i + 1);
            assertTrue(points.contains(key(1 - u, v)), "no mirror of " + u + ", " + v + " in u");
            assertTrue(points.contains(key(u, 1 - v)), "no mirror of " + u + ", " + v + " in v");
        }
    }

    @Test
    void anInnerLevelOfOneWithOddSpacingLeavesItsOuterSpansWithoutLength() {
        final Tessellator t = quads(GL40.GL_FRACTIONAL_ODD);
        t.tessellate(new float[] {3, 3, 3, 3}, new float[] {1, 3});
        boolean spansTheWidth = false;
        for (int p = 0; p + 2 < t.primitives.size(); p += 3) {
            final int a = t.primitives.getInt(p), b = t.primitives.getInt(p + 1);
            final float ua = t.coordinates.getFloat(a * 3), ub = t.coordinates.getFloat(b * 3), va = t.coordinates.getFloat(a * 3 + 1), vb = t.coordinates.getFloat(b * 3 + 1);
            spansTheWidth |= va == vb && Math.abs(ua - ub) == 1.0f && va > 0.0f && va < 1.0f;
        }
        assertTrue(spansTheWidth);
    }

    @Test
    void allLevelsOfOneMakeOneQuad() {
        final Tessellator t = quads(GL11.GL_EQUAL);
        assertEquals(4, t.tessellate(new float[] {1, 1, 1, 1}, new float[] {1, 1}));
    }
}
