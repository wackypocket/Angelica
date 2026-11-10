package com.gtnewhorizons.angelica.glemu.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipBlitAxisTest {

    private static int[] clipped(int s0, int s1, int d0, int d1, int srcSize, int dstSize) {
        final int[] e = {s0, s1, d0, d1};
        assertTrue(GlBase.clipBlitAxis(e, srcSize, dstSize));
        return e;
    }

    @Test
    void aSpanInsideBothSurfacesIsUntouched() {
        assertArrayEquals(new int[] {0, 8, 0, 16}, clipped(0, 8, 0, 16, 8, 16));
    }

    @Test
    void clippingTheSourceScalesTheDestination() {
        assertArrayEquals(new int[] {0, 4, 8, 16}, clipped(-4, 4, 0, 16, 8, 16));
    }

    @Test
    void aFlippedSpanStaysFlipped() {
        assertArrayEquals(new int[] {8, 0, 0, 8}, clipped(8, 0, 0, 8, 8, 8));
    }

    @Test
    void emptyOrOutsideSpansAreRefused() {
        assertFalse(GlBase.clipBlitAxis(new int[] {4, 4, 0, 8}, 8, 8));
        assertFalse(GlBase.clipBlitAxis(new int[] {0, 8, 16, 24}, 8, 8));
    }
}
