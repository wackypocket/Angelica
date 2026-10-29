package com.gtnewhorizons.angelica.vulkan.glsm;

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

import static com.gtnewhorizons.angelica.vulkan.glsm.GlsmVulkanHeadlessRig.BLUE;
import static com.gtnewhorizons.angelica.vulkan.glsm.GlsmVulkanHeadlessRig.GREEN;
import static com.gtnewhorizons.angelica.vulkan.glsm.GlsmVulkanHeadlessRig.SIZE;

@Tag("glsm-vulkan")
@ExtendWith(GlsmVulkanFrameExtension.class)
class GlsmVulkanTessellatorEntityIdsTest {

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
        GlsmVulkanHeadlessRig.beginFrameAndClear();
        GLSMPrograms.glUseProgram(program);
        TessellatorEntityIdsFixture.drawIdsChangedMidBatch();

        final int[] pixels = GlsmVulkanHeadlessRig.readTarget();
        GlsmVulkanHeadlessRig.assertPixel(pixels, SIZE, SIZE / 4, SIZE / 2, GREEN, "left quad was added under item 7");
        GlsmVulkanHeadlessRig.assertPixel(pixels, SIZE, 3 * SIZE / 4, SIZE / 2, BLUE, "right quad was added under item 50020");
    }

    @Test
    void idsChangedAfterTheLastVertexLeaveTheBatchAlone() {
        GlsmVulkanHeadlessRig.beginFrameAndClear();
        GLSMPrograms.glUseProgram(program);
        TessellatorEntityIdsFixture.drawIdsChangedAfterTheLastVertex();

        final int[] pixels = GlsmVulkanHeadlessRig.readTarget();
        GlsmVulkanHeadlessRig.assertPixel(pixels, SIZE, SIZE / 4, SIZE / 2, GREEN, "every vertex was added under item 7");
        GlsmVulkanHeadlessRig.assertPixel(pixels, SIZE, 3 * SIZE / 4, SIZE / 2, GREEN, "every vertex was added under item 7");
    }
}
