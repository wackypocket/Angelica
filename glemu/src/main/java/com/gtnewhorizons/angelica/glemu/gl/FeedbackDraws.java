package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.compat.GlTransformFeedback;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * A capture the vertex shader cannot place by its own vertex number, as a draw through indices repeats vertices and the strips, fans and loops share them.
 * The shader stores into scratch buffers by vertex while the draw goes ahead, and the primitives are then written out vertex by vertex, which stalls.
 */
final class FeedbackDraws {
    private final GlBase<?, ?, ?> gl;
    private final int[] scratch = new int[GlTransformFeedback.STREAMS];
    private boolean busy;

    FeedbackDraws(GlBase<?, ?, ?> gl) {
        this.gl = gl;
    }

    boolean applies(GlState st, int mode, boolean indexed) {
        if (busy || st.conditionalSkip || GlTransformFeedback.inParts() || !GlTransformFeedback.capturing()) return false;
        final int perPrimitive = GeometryDraws.verticesPerInput(mode);
        if (perPrimitive == 0 || perPrimitive > 3) return false;
        return indexed || mode == GL11.GL_LINE_STRIP || mode == GL11.GL_LINE_LOOP || mode == GL11.GL_TRIANGLE_STRIP || mode == GL11.GL_TRIANGLE_FAN;
    }

    void arrays(GlState st, int mode, int first, int count, int instances) {
        if (count <= 0 || instances <= 0) return;
        final int[] vertices = new int[count];
        for (int i = 0; i < count; i++) vertices[i] = first + i;
        run(st, mode, vertices, count, first, count, instances, -1L, () -> gl.drawArraysInstanced(mode, first, count, instances));
    }

    void elements(GlState st, int mode, int count, int type, long indices, int instances, int baseVertex) {
        if (count <= 0 || instances <= 0) return;
        final int[] vertices = gl.readIndices(st, count, type, indices);
        final long restart = !st.primitiveRestart ? -1L : !st.primitiveRestartFixed ? Integer.toUnsignedLong(st.primitiveRestartIndex)
            : type == GL11.GL_UNSIGNED_BYTE ? 0xFFL : type == GL11.GL_UNSIGNED_SHORT ? 0xFFFFL : 0xFFFFFFFFL;
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            if (Integer.toUnsignedLong(vertices[i]) == restart) continue;
            vertices[i] += baseVertex;
            lowest = Math.min(lowest, vertices[i]);
            highest = Math.max(highest, vertices[i]);
        }
        if (lowest > highest) return;
        run(st, mode, vertices, count, lowest, highest - lowest + 1, instances, restart == -1L ? -1L : restart + baseVertex,
            () -> gl.drawElementsInstancedBaseVertex(mode, count, type, indices, instances, baseVertex));
    }

    private void run(GlState st, int mode, int[] vertices, int count, int lowest, int span, int instances, long restart, Runnable draw) {
        final int[] strides = GlTransformFeedback.strideBytes();
        final int generic = gl.getInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        final long[] bytes = new long[strides.length];
        for (int b = 0; b < strides.length; b++) {
            if (strides[b] == 0) continue;
            if (scratch[b] == 0) scratch[b] = gl.genBuffers();
            bytes[b] = Math.multiplyExact((long) span * instances, strides[b]);
            gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, scratch[b]);
            gl.bufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes[b], GL15.GL_STREAM_DRAW);
        }
        GlTransformFeedback.storeIn(scratch, bytes, lowest, span, st.baseInstance);
        busy = true;
        GlTransformFeedback.drawingInParts(true);
        try {
            draw.run();
        } finally {
            busy = false;
            GlTransformFeedback.drawingInParts(false);
            GlTransformFeedback.storeInCaptureBuffers();
        }
        gl.memoryBarrier(GL42.GL_ALL_BARRIER_BITS);
        gl.finish();
        final ByteBuffer[] stored = new ByteBuffer[strides.length];
        for (int b = 0; b < strides.length; b++) {
            if (strides[b] == 0) continue;
            stored[b] = ByteBuffer.allocateDirect(Math.toIntExact(bytes[b])).order(ByteOrder.nativeOrder());
            gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, scratch[b]);
            gl.getBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, stored[b]);
        }
        final IntArrayList assembled = new IntArrayList();
        int start = 0;
        while (start < count) {
            int end = start;
            while (end < count && (restart == -1L || Integer.toUnsignedLong(vertices[end]) != restart)) end++;
            GeometryDraws.assembleRun(false, mode, vertices, start, end - start, lowest, assembled);
            start = end + 1;
        }
        final int[] order = new int[assembled.size() * instances];
        for (int instance = 0; instance < instances; instance++) {
            for (int i = 0; i < assembled.size(); i++) order[instance * assembled.size() + i] = assembled.getInt(i) + instance * span;
        }
        GlTransformFeedback.writeVertices(stored, order, GeometryDraws.verticesPerInput(mode));
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, generic);
    }
}
