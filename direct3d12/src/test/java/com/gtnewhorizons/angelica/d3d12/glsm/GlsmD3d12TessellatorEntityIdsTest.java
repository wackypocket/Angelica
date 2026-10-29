package com.gtnewhorizons.angelica.d3d12.glsm;

import com.gtnewhorizons.angelica.glsm.EntityIdsOnlyHandler;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.ImmediateExtendedAttribHandler;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorEntityIdsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.gtnewhorizons.angelica.d3d12.glsm.GlsmD3d12HeadlessRig.BLUE;
import static com.gtnewhorizons.angelica.d3d12.glsm.GlsmD3d12HeadlessRig.GREEN;
import static com.gtnewhorizons.angelica.d3d12.glsm.GlsmD3d12HeadlessRig.SIZE;

@Tag("glsm-d3d12")
@ExtendWith(GlsmD3d12FrameExtension.class)
class GlsmD3d12TessellatorEntityIdsTest {

    private ImmediateExtendedAttribHandler previousHandler;
    private int program;

    @BeforeEach
    void setUp() {
        previousHandler = EntityIdsOnlyHandler.install();
        program = GlTestSupport.program(TessellatorEntityIdsFixture.VERTEX, TessellatorEntityIdsFixture.FRAGMENT);
    }

    @AfterEach
    void tearDown() {
        GLSMPrograms.glUseProgram(0);
        GLSMPrograms.glDeleteProgram(program);
        GLSMHooks.immediateExtendedHandler = previousHandler;
    }

    @Test
    void idsChangedMidBatchApplyToTheVerticesAddedAfterTheChange() {
        GlsmD3d12HeadlessRig.beginFrameAndClear();
        GLSMPrograms.glUseProgram(program);
        TessellatorEntityIdsFixture.drawIdsChangedMidBatch();

        final int[] pixels = GlsmD3d12HeadlessRig.readTarget();
        GlsmD3d12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 4, SIZE / 2, GREEN, "left quad was added under item 7");
        GlsmD3d12HeadlessRig.assertPixel(pixels, SIZE, 3 * SIZE / 4, SIZE / 2, BLUE, "right quad was added under item 50020");
    }

    @Test
    void idsChangedAfterTheLastVertexLeaveTheBatchAlone() {
        GlsmD3d12HeadlessRig.beginFrameAndClear();
        GLSMPrograms.glUseProgram(program);
        TessellatorEntityIdsFixture.drawIdsChangedAfterTheLastVertex();

        final int[] pixels = GlsmD3d12HeadlessRig.readTarget();
        GlsmD3d12HeadlessRig.assertPixel(pixels, SIZE, SIZE / 4, SIZE / 2, GREEN, "every vertex was added under item 7");
        GlsmD3d12HeadlessRig.assertPixel(pixels, SIZE, 3 * SIZE / 4, SIZE / 2, GREEN, "every vertex was added under item 7");
    }
}
