package com.gtnewhorizons.angelica.glemu.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicStateCacheTest {

    private static GlState state() {
        return new GlState(Thread.currentThread());
    }

    @Test
    void everyValueIsEmittedAfterInvalidate() {
        final DynamicStateCache c = new DynamicStateCache();
        apply(c);
        c.invalidate();
        assertTrue(c.viewport(0, 0, 64, 64, 0, 1));
        assertTrue(c.scissor(0, 0, 64, 64));
        assertTrue(c.depthBias(0f, 0f, 0f));
        assertTrue(c.blendColor(new float[4]));
        assertTrue(c.lineWidth(1f));
        assertTrue(c.cull(0));
        assertTrue(c.winding(0));
        assertTrue(c.depthClip(0));
        assertTrue(c.fill(0));
        assertTrue(c.stencilCompareMask(0xFF, 0xFF));
        assertTrue(c.stencilWriteMask(0xFF, 0xFF));
        assertTrue(c.stencilReference(0, 0));
    }

    @Test
    void unchangedValuesAreSkipped() {
        final DynamicStateCache c = new DynamicStateCache();
        apply(c);
        assertFalse(c.viewport(0, 0, 64, 64, 0, 1));
        assertFalse(c.scissor(0, 0, 64, 64));
        assertFalse(c.depthBias(0f, 0f, 0f));
        assertFalse(c.blendColor(new float[4]));
        assertFalse(c.lineWidth(1f));
        assertFalse(c.cull(0));
        assertFalse(c.stencilCompareMask(0xFF, 0xFF));
        assertFalse(c.stencilReference(0, 0));

        assertTrue(c.scissor(0, 0, 32, 64));
        assertTrue(c.stencilReference(0, 1), "front and back are tracked separately");
    }

    @Test
    void recheckKeepsValuesButForcesAComparison() {
        final DynamicStateCache c = new DynamicStateCache();
        final GlState st = state();
        assertFalse(c.upToDate(st, 0, 1, 0, 0));
        apply(c);
        assertTrue(c.upToDate(st, 0, 1, 0, 0));
        c.recheck();
        assertFalse(c.upToDate(st, 0, 1, 0, 0));
        assertFalse(c.viewport(0, 0, 64, 64, 0, 1), "recheck must not forget what the command buffer already holds");
    }

    @Test
    void theStampComparesEveryInputExactly() {
        final DynamicStateCache c = new DynamicStateCache();
        final GlState st = state();
        c.upToDate(st, 0, 1, 0, 0);
        st.dynamicGen++;
        assertFalse(c.upToDate(st, 0, 1, -31, 0), "a dynamicGen bump paired with an fboId change must not cancel out");
        assertFalse(c.upToDate(st, 1, 1, -31, 0));
        assertFalse(c.upToDate(st, 1, 2, -31, 0));
        assertFalse(c.upToDate(st, 1, 2, -31, 1));
        assertFalse(c.upToDate(state(), 1, 2, -31, 1));
    }

    private static void apply(DynamicStateCache c) {
        c.viewport(0, 0, 64, 64, 0, 1);
        c.scissor(0, 0, 64, 64);
        c.depthBias(0f, 0f, 0f);
        c.blendColor(new float[4]);
        c.lineWidth(1f);
        c.cull(0);
        c.winding(0);
        c.depthClip(0);
        c.fill(0);
        c.stencilCompareMask(0xFF, 0xFF);
        c.stencilWriteMask(0xFF, 0xFF);
        c.stencilReference(0, 0);
    }
}
