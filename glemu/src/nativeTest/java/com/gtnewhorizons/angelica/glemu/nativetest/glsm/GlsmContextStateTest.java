package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glemu.compat.GlEntryPoints;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBImaging;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL41;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** State GL keeps per context stays with the thread that set it, as GlState does for the rest. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmContextStateTest {

    private static void onAnotherThread(Runnable body) throws InterruptedException {
        NativeGlsmRig.onAnotherThread(true, body);
    }

    private static long floats(float... values) {
        return MemoryUtil.memAddress(BufferUtils.createFloatBuffer(values.length).put(values).flip());
    }

    @Test
    void aStipplePatternBelongsToItsThread() throws Exception {
        final ByteBuffer mine = BufferUtils.createByteBuffer(128);
        final ByteBuffer theirs = BufferUtils.createByteBuffer(128);
        final ByteBuffer read = BufferUtils.createByteBuffer(128);
        for (int i = 0; i < 128; i++) {
            mine.put(i, (byte) 0x0F);
            theirs.put(i, (byte) 0xF0);
        }
        GlEntryPoints.glPolygonStipple(MemoryUtil.memAddress(mine));
        onAnotherThread(() -> {
            GlEntryPoints.glPolygonStipple(MemoryUtil.memAddress(theirs));
            GlEntryPoints.glGetPolygonStipple(MemoryUtil.memAddress(read));
            assertEquals((byte) 0xF0, read.get(0));
        });
        GlEntryPoints.glGetPolygonStipple(MemoryUtil.memAddress(read));
        assertEquals((byte) 0x0F, read.get(0));
    }

    @Test
    void aPixelMapBelongsToItsThread() throws Exception {
        final FloatBuffer read = BufferUtils.createFloatBuffer(2);
        GlEntryPoints.glPixelMapfv(GL11.GL_PIXEL_MAP_R_TO_R, 2, floats(0.25f, 0.5f));
        try {
            onAnotherThread(() -> {
                GlEntryPoints.glPixelMapfv(GL11.GL_PIXEL_MAP_R_TO_R, 2, floats(0.75f, 1f));
                GlEntryPoints.glGetPixelMapfv(GL11.GL_PIXEL_MAP_R_TO_R, MemoryUtil.memAddress(read));
                assertEquals(0.75f, read.get(0));
            });
            GlEntryPoints.glGetPixelMapfv(GL11.GL_PIXEL_MAP_R_TO_R, MemoryUtil.memAddress(read));
            assertEquals(0.25f, read.get(0));
        } finally {
            GlEntryPoints.glPixelMapfv(GL11.GL_PIXEL_MAP_R_TO_R, 1, floats(0f));
        }
    }

    @Test
    void anEvaluatorMapBelongsToItsThread() throws Exception {
        final FloatBuffer domain = BufferUtils.createFloatBuffer(2);
        GlEntryPoints.glMap1f(GL11.GL_MAP1_VERTEX_3, 0f, 1f, 3, 2, floats(0f, 0f, 0f, 1f, 1f, 1f));
        onAnotherThread(() -> {
            GlEntryPoints.glMap1f(GL11.GL_MAP1_VERTEX_3, 5f, 7f, 3, 2, floats(0f, 0f, 0f, 1f, 1f, 1f));
            GlEntryPoints.glGetMapfv(GL11.GL_MAP1_VERTEX_3, GL11.GL_DOMAIN, MemoryUtil.memAddress(domain));
            assertEquals(5f, domain.get(0));
        });
        GlEntryPoints.glGetMapfv(GL11.GL_MAP1_VERTEX_3, GL11.GL_DOMAIN, MemoryUtil.memAddress(domain));
        assertEquals(0f, domain.get(0));
        assertEquals(1f, domain.get(1));
    }

    @Test
    void aColorTableBelongsToItsThread() throws Exception {
        final IntBuffer width = BufferUtils.createIntBuffer(1);
        final ByteBuffer texels = BufferUtils.createByteBuffer(8);
        GlEntryPoints.glColorTable(ARBImaging.GL_COLOR_TABLE, GL11.GL_RGBA8, 2, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(texels));
        onAnotherThread(() -> {
            GlEntryPoints.glGetColorTableParameteriv(ARBImaging.GL_COLOR_TABLE, ARBImaging.GL_COLOR_TABLE_WIDTH, MemoryUtil.memAddress(width));
            assertEquals(0, width.get(0));
        });
        GlEntryPoints.glGetColorTableParameteriv(ARBImaging.GL_COLOR_TABLE, ARBImaging.GL_COLOR_TABLE_WIDTH, MemoryUtil.memAddress(width));
        assertEquals(2, width.get(0));
    }

    @Test
    void aBoundPipelineBelongsToItsThreadThoughThePipelineIsShared() throws Exception {
        final IntBuffer names = BufferUtils.createIntBuffer(1);
        GlEntryPoints.glGenProgramPipelines(1, MemoryUtil.memAddress(names));
        final int pipeline = names.get(0);
        onAnotherThread(() -> {
            GlEntryPoints.glBindProgramPipeline(pipeline);
            assertEquals(GL11.GL_TRUE, GlEntryPoints.glIsProgramPipeline(pipeline) ? GL11.GL_TRUE : GL11.GL_FALSE);
            assertEquals(pipeline, NativeGlsmRig.gl().getInteger(GL41.GL_PROGRAM_PIPELINE_BINDING));
            GlEntryPoints.glBindProgramPipeline(0);
        });
        assertEquals(0, NativeGlsmRig.gl().getInteger(GL41.GL_PROGRAM_PIPELINE_BINDING));
        GlEntryPoints.glDeleteProgramPipelines(1, MemoryUtil.memAddress(names));
    }
}
