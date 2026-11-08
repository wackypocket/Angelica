package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.compat.GlTransformFeedback;
import com.gtnewhorizons.angelica.glemu.shader.GeometryLowering;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glemu.shader.StageModes;
import com.gtnewhorizons.angelica.glemu.shader.Tessellator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * Draws a program whose geometry shader GeometryLowering rewrote: the vertex shader stores its outputs, the compute shader runs once per
 * primitive and invocation storing what it emits, and the vertex shader then hands those emitted vertices to the fragment shader.
 */
final class GeometryDraws {
    private static final int UNSIGNED_INT = GL11.GL_UNSIGNED_INT;

    private final GlBase<?, ?, ?> gl;
    private boolean busy;
    private int vertexBuffer, primitiveBuffer, emittedBuffer, indexBuffer, emptyVao;
    private final int[] scratch = new int[5];

    GeometryDraws(GlBase<?, ?, ?> gl) {
        this.gl = gl;
    }

    boolean applies(GlState st) {
        if (busy) return false;
        final ProgramBase<?> p = gl.programs().current(st);
        return p != null && p.linked && p.geometry != null;
    }

    void arrays(GlState st, int mode, int first, int count, int instances) {
        if (count <= 0 || instances <= 0) return;
        final int[] vertices = new int[count];
        for (int i = 0; i < count; i++) vertices[i] = first + i;
        // Drawn as points so every vertex reaches the vertex shader, adjacency-only ones included.
        run(st, mode, vertices, count, first, count, instances, -1L, () -> gl.drawArraysInstanced(GL11.GL_POINTS, first, count, instances));
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
            () -> gl.drawElementsInstancedBaseVertex(GL11.GL_POINTS, count, type, indices, instances, baseVertex));
    }

    private void run(GlState st, int mode, int[] vertices, int count, int base, int span, int instances, long restart, Runnable storeVertices) {
        final ProgramBase<?> p = gl.programs().current(st);
        final ProgramBase.EmulatedGeometry g = p.geometry;
        final boolean tessellated = g.evaluation != null;
        if (tessellated != (mode == GL40.GL_PATCHES)) return;
        final IntArrayList assembled = new IntArrayList();
        if (tessellated) {
            for (int i = 0; i + st.patchVertices <= count; i += st.patchVertices) {
                for (int v = 0; v < st.patchVertices; v++) assembled.add(vertices[i + v] - base);
            }
        } else if (!assemble(st, mode, g.verticesIn, vertices, count, base, restart, assembled)) {
            return;
        }
        final int groupSize = tessellated ? st.patchVertices : g.verticesIn;
        final int perInstance = assembled.size() / groupSize;
        if (perInstance == 0) return;
        if (vertexBuffer == 0) {
            vertexBuffer = gl.genBuffers();
            primitiveBuffer = gl.genBuffers();
            emittedBuffer = gl.genBuffers();
            indexBuffer = gl.genBuffers();
            emptyVao = gl.genVertexArrays();
        }
        final ByteBuffer primitives = ByteBuffer.allocateDirect(assembled.size() * instances * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int instance = 0; instance < instances; instance++) {
            for (int i = 0; i < assembled.size(); i++) primitives.putInt(assembled.getInt(i) + instance * span);
        }
        primitives.flip();
        final int generic = gl.getInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        final int program = st.program;
        final int vao = st.vao.id;
        final boolean restart0 = st.primitiveRestart, discarding = st.rasterizerDiscard;
        final long vertexBytes = Math.multiplyExact((long) span * instances, g.vertexBytes);
        size(vertexBuffer, vertexBytes);
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, primitiveBuffer);
        gl.bufferData(GL43.GL_SHADER_STORAGE_BUFFER, primitives, GL15.GL_STREAM_DRAW);
        final long primitiveBytes = primitives.remaining();
        bind(GeometryLowering.VERTICES_BINDING, vertexBuffer, vertexBytes);
        bind(GeometryLowering.PRIMITIVES_BINDING, primitiveBuffer, primitiveBytes);
        busy = true;
        GlTransformFeedback.drawingInParts(true);
        try {
            uniform(p, GeometryLowering.STAGE, 0);
            uniform(p, GeometryLowering.BASE, base);
            uniform(p, GeometryLowering.SPAN, span);
            uniform(p, GeometryLowering.FIRST_INSTANCE, st.baseInstance);
            if (!discarding) gl.enable(GL30.GL_RASTERIZER_DISCARD);
            storeVertices.run();
            if (!discarding) gl.disable(GL30.GL_RASTERIZER_DISCARD);
            gl.memoryBarrier(GL42.GL_ALL_BARRIER_BITS);

            int[] drawn = null;
            int drawMode = g.drawMode;
            long sourceBytes = vertexBytes;
            int sourceBuffer = vertexBuffer;
            int groups = perInstance * instances;
            int primitivesPerInstance = perInstance;
            if (tessellated) {
                final int patches = perInstance * instances;
                final int controlBuffer = scratch(0), patchBuffer = scratch(1), coordinateBuffer = scratch(2), evaluationBuffer = scratch(3), levelsBuffer = scratch(4);
                final int controlVertices = g.declaresControlVertices ? g.controlVertices : st.patchVertices;
                size(controlBuffer, Math.multiplyExact((long) patches * g.controlVertices, g.controlBytes));
                size(patchBuffer, Math.multiplyExact((long) patches, g.patchBytes));
                final long levelBytes = patches * 6L * Float.BYTES;
                size(levelsBuffer, levelBytes);
                bind(GeometryLowering.CONTROL_BINDING, controlBuffer, (long) patches * g.controlVertices * g.controlBytes);
                bind(GeometryLowering.PATCH_BINDING, patchBuffer, patches * g.patchBytes);
                bind(GeometryLowering.LEVELS_BINDING, levelsBuffer, levelBytes);
                gl.programsCopyUniforms(p, g.control);
                gl.useProgram(g.control.id);
                setDefaultLevels(g.control, st);
                uniform(g.control, GeometryLowering.PATCH_VERTICES, st.patchVertices);
                uniform(g.control, GeometryLowering.PRIMITIVES_PER_INSTANCE, perInstance);
                gl.dispatchStage(patches);
                gl.useProgram(program);
                gl.memoryBarrier(GL42.GL_ALL_BARRIER_BITS);
                gl.finish();

                final ByteBuffer levels = ByteBuffer.allocateDirect((int) levelBytes).order(ByteOrder.nativeOrder());
                gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, levelsBuffer);
                gl.getBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, levels);
                final StageModes modes = p.stageModes;
                final Tessellator tessellator = new Tessellator(modes.tessMode, modes.tessSpacing, modes.tessOrder, modes.tessPointMode, 64);
                final float[] outer = new float[4], inner = new float[2];
                final IntArrayList owners = new IntArrayList();
                for (int patch = 0; patch < patches; patch++) {
                    for (int i = 0; i < 4; i++) outer[i] = levels.getFloat((patch * 6 + i) * Float.BYTES);
                    for (int i = 0; i < 2; i++) inner[i] = levels.getFloat((patch * 6 + 4 + i) * Float.BYTES);
                    final int generated = tessellator.tessellate(outer, inner);
                    for (int i = 0; i < generated; i++) owners.add(patch);
                }
                final int generatedVertices = owners.size();
                if (generatedVertices == 0) return;
                final ByteBuffer coordinates = ByteBuffer.allocateDirect(generatedVertices * 16).order(ByteOrder.nativeOrder());
                for (int i = 0; i < generatedVertices; i++) {
                    coordinates.putFloat(tessellator.coordinates.getFloat(i * 3)).putFloat(tessellator.coordinates.getFloat(i * 3 + 1)).putFloat(tessellator.coordinates.getFloat(i * 3 + 2)).putInt(owners.getInt(i));
                }
                coordinates.flip();
                gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, coordinateBuffer);
                gl.bufferData(GL43.GL_SHADER_STORAGE_BUFFER, coordinates, GL15.GL_STREAM_DRAW);
                final long coordinateBytes = coordinates.remaining();
                final long evaluationBytes = Math.multiplyExact((long) generatedVertices, g.evaluationBytes);
                size(evaluationBuffer, evaluationBytes);
                bind(GeometryLowering.COORDINATE_BINDING, coordinateBuffer, coordinateBytes);
                bind(GeometryLowering.EMITTED_BINDING, evaluationBuffer, evaluationBytes);
                gl.programsCopyUniforms(p, g.evaluation);
                gl.useProgram(g.evaluation.id);
                uniform(g.evaluation, GeometryLowering.CONTROL_VERTICES, controlVertices);
                uniform(g.evaluation, GeometryLowering.PRIMITIVES_PER_INSTANCE, perInstance);
                gl.dispatchStage(generatedVertices);
                gl.useProgram(program);
                gl.memoryBarrier(GL42.GL_ALL_BARRIER_BITS);
                sourceBuffer = evaluationBuffer;
                sourceBytes = evaluationBytes;
                final int[] generatedPrimitives = tessellator.primitives.toIntArray();
                if (g.compute == null) {
                    drawn = g.evaluationCulls ? withoutCulled(g, evaluationBuffer, generatedVertices, generatedPrimitives, tessellator.verticesPerPrimitive()) : generatedPrimitives;
                    drawMode = tessellator.verticesPerPrimitive() == 1 ? GL11.GL_POINTS : tessellator.verticesPerPrimitive() == 2 ? GL11.GL_LINES : GL11.GL_TRIANGLES;
                } else {
                    if (tessellator.verticesPerPrimitive() != g.verticesIn) return;
                    final ByteBuffer list = ByteBuffer.allocateDirect(generatedPrimitives.length * Integer.BYTES).order(ByteOrder.nativeOrder());
                    list.asIntBuffer().put(generatedPrimitives);
                    gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, primitiveBuffer);
                    gl.bufferData(GL43.GL_SHADER_STORAGE_BUFFER, list, GL15.GL_STREAM_DRAW);
                    bind(GeometryLowering.PRIMITIVES_BINDING, primitiveBuffer, list.remaining());
                    groups = generatedPrimitives.length / g.verticesIn;
                    primitivesPerInstance = groups;
                    if (groups == 0) return;
                }
            }

            int indexCount;
            if (g.compute != null) {
                final int runs = groups * g.invocations;
                final long emittedBytes = Math.multiplyExact((long) runs * (g.maxVertices + 1), g.emittedBytes);
                final long indexBytes = Math.max(4L, (long) runs * g.indicesPerRun * Integer.BYTES);
                size(emittedBuffer, emittedBytes);
                size(indexBuffer, indexBytes);
                bind(GeometryLowering.VERTICES_BINDING, sourceBuffer, sourceBytes);
                bind(GeometryLowering.EMITTED_BINDING, emittedBuffer, emittedBytes);
                bind(GeometryLowering.INDICES_BINDING, indexBuffer, indexBytes);
                gl.programsCopyUniforms(p, g.compute);
                gl.useProgram(g.compute.id);
                uniform(g.compute, GeometryLowering.PRIMITIVES_PER_INSTANCE, primitivesPerInstance);
                gl.dispatchStage(runs);
                gl.useProgram(program);
                gl.memoryBarrier(GL42.GL_ALL_BARRIER_BITS);
                indexCount = runs * g.indicesPerRun;
                if (GlTransformFeedback.capturing() || gl.countingGeneratedPrimitives() || gl.countingStatistics()) {
                    gl.finish();
                    final ByteBuffer indices = ByteBuffer.allocateDirect(Math.max(4, indexCount * Integer.BYTES)).order(ByteOrder.nativeOrder());
                    gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, indexBuffer);
                    gl.getBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, indices);
                    final int[] list = new int[indexCount];
                    indices.asIntBuffer().get(list);
                    report(g, emittedBuffer, runs * (g.maxVertices + 1), list, g.maxVertices + 1, drawMode);
                }
            } else {
                indexCount = drawn.length;
                if (GlTransformFeedback.capturing() || gl.countingGeneratedPrimitives()) {
                    gl.finish();
                    report(g, sourceBuffer, (int) (sourceBytes / g.evaluationBytes), drawn, 0, drawMode);
                }
            }

            uniform(p, GeometryLowering.STAGE, 1);
            uniform(p, GeometryLowering.LAYER_LIMIT, gl.drawLayerLimit());
            gl.bindVertexArray(emptyVao);
            gl.bindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            if (drawn != null) {
                final ByteBuffer list = ByteBuffer.allocateDirect(drawn.length * Integer.BYTES).order(ByteOrder.nativeOrder());
                list.asIntBuffer().put(drawn);
                gl.bufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, list, GL15.GL_STREAM_DRAW);
            }
            if (restart0) gl.disable(GL31.GL_PRIMITIVE_RESTART);
            if (indexCount > 0) gl.drawElementsInstancedBaseVertex(drawMode, indexCount, UNSIGNED_INT, 0L, 1, 0);
        } finally {
            busy = false;
            GlTransformFeedback.drawingInParts(false);
            if (restart0) gl.enable(GL31.GL_PRIMITIVE_RESTART);
            if (!discarding && st.rasterizerDiscard) gl.disable(GL30.GL_RASTERIZER_DISCARD);
            gl.bindVertexArray(vao);
            gl.useProgram(program);
            uniform(p, GeometryLowering.STAGE, 0);
            for (int binding : new int[] {GeometryLowering.VERTICES_BINDING, GeometryLowering.PRIMITIVES_BINDING, GeometryLowering.EMITTED_BINDING, GeometryLowering.INDICES_BINDING,
                GeometryLowering.CONTROL_BINDING, GeometryLowering.PATCH_BINDING, GeometryLowering.COORDINATE_BINDING, GeometryLowering.LEVELS_BINDING}) {
                gl.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, 0);
            }
            gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, generic);
        }
    }

    /** Leaves out the primitives whose stored cull distances say GL would cull them. */
    private int[] withoutCulled(ProgramBase.EmulatedGeometry g, int buffer, int records, int[] primitives, int perPrimitive) {
        gl.finish();
        final ByteBuffer stored = ByteBuffer.allocateDirect(Math.toIntExact((long) records * g.emittedStride)).order(ByteOrder.nativeOrder());
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        gl.getBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, stored);
        final int offset = g.emittedOffsets.get("gl_CullDistance");
        final IntArrayList kept = new IntArrayList(primitives.length);
        for (int at = 0; at + perPrimitive <= primitives.length; at += perPrimitive) {
            boolean culled = false;
            for (int plane = 0; plane < 8 && !culled; plane++) {
                culled = true;
                for (int v = 0; v < perPrimitive; v++) {
                    if (stored.getFloat(primitives[at + v] * g.emittedStride + offset + plane * Float.BYTES) >= 0.0f) culled = false;
                }
            }
            if (!culled) for (int v = 0; v < perPrimitive; v++) kept.add(primitives[at + v]);
        }
        return kept.toIntArray();
    }

    /** Hands what the last stage stored to transform feedback and the query counting primitives. The last of each {@code every} indices is an empty slot; none are when it is 0. */
    private void report(ProgramBase.EmulatedGeometry g, int buffer, int records, int[] indices, int every, int mode) {
        final int perPrimitive = mode == GL11.GL_POINTS ? 1 : mode == GL11.GL_LINES ? 2 : 3;
        int kept = indices.length;
        if (every > 0) {
            kept = 0;
            for (int i = 0; i < indices.length; i++) {
                if (indices[i] % every == every - 1) indices[i] = -1;
                else kept++;
            }
        }
        final Integer streamAt = g.emittedOffsets.get(GlTransformFeedback.STREAM_RECORD);
        final boolean capturing = GlTransformFeedback.capturing() && g.emittedStride > 0;
        if (g.compute != null) gl.noteGeometryEmitted(kept / perPrimitive);
        if (streamAt == null) GlTransformFeedback.noteGenerated(0, kept / perPrimitive);
        if (streamAt == null && !capturing) return;
        final ByteBuffer stored = ByteBuffer.allocateDirect(Math.toIntExact((long) records * g.emittedStride)).order(ByteOrder.nativeOrder());
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        gl.getBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, stored);
        if (streamAt != null) {
            for (int index : indices) {
                if (index >= 0) GlTransformFeedback.noteGenerated(stored.getInt(index * g.emittedStride + streamAt), 1L);
            }
        }
        if (capturing) GlTransformFeedback.captureStored(stored, g.emittedStride, g.emittedOffsets, indices, -1, perPrimitive);
    }

    private int scratch(int index) {
        if (scratch[index] == 0) scratch[index] = gl.genBuffers();
        return scratch[index];
    }

    private void bind(int binding, int buffer, long bytes) {
        gl.bindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, binding, buffer, 0L, bytes);
    }

    /** What a program without a control shader tessellates by, which GL keeps as context state. */
    private void setDefaultLevels(ProgramBase<?> p, GlState st) {
        final int outer = gl.getUniformLocation(p.id, GeometryLowering.DEFAULT_OUTER), inner = gl.getUniformLocation(p.id, GeometryLowering.DEFAULT_INNER);
        if (outer >= 0) gl.uniform4f(outer, st.patchDefaultOuter[0], st.patchDefaultOuter[1], st.patchDefaultOuter[2], st.patchDefaultOuter[3]);
        if (inner >= 0) gl.uniform2f(inner, st.patchDefaultInner[0], st.patchDefaultInner[1]);
    }

    private void size(int buffer, long bytes) {
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        gl.bufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_STREAM_COPY);
    }

    /** By name of the program, since with a pipeline bound glUniform goes to the active one of its stage programs. */
    private void uniform(ProgramBase<?> p, String name, int value) {
        gl.stageUniform(p.id, name, value);
    }

    /** The vertices of each primitive, in the order a geometry shader sees them. False when the mode cannot feed what the shader takes, which GL refuses. */
    private static boolean assemble(GlState st, int mode, int verticesIn, int[] v, int count, int base, long restart, IntArrayList out) {
        if (verticesIn != verticesPerInput(mode)) return false;
        int start = 0;
        while (start < count) {
            int end = start;
            while (end < count && (restart == -1L || Integer.toUnsignedLong(v[end]) != restart)) end++;
            assembleRun(st.provokingFirst, mode, v, start, end - start, base, out);
            start = end + 1;
        }
        return true;
    }

    static int verticesPerInput(int mode) {
        return switch (mode) {
            case GL11.GL_POINTS -> 1;
            case GL11.GL_LINES, GL11.GL_LINE_STRIP, GL11.GL_LINE_LOOP -> 2;
            case GL11.GL_TRIANGLES, GL11.GL_TRIANGLE_STRIP, GL11.GL_TRIANGLE_FAN -> 3;
            case GL32.GL_LINES_ADJACENCY, GL32.GL_LINE_STRIP_ADJACENCY -> 4;
            case GL32.GL_TRIANGLES_ADJACENCY, GL32.GL_TRIANGLE_STRIP_ADJACENCY -> 6;
            default -> 0;
        };
    }

    private static void put(int[] v, int at, int base, IntArrayList out, int... offsets) {
        for (int offset : offsets) out.add(v[at + offset] - base);
    }

    static void assembleRun(boolean first, int mode, int[] v, int at, int n, int base, IntArrayList out) {
        switch (mode) {
            case GL11.GL_POINTS -> {
                for (int i = 0; i < n; i++) put(v, at, base, out, i);
            }
            case GL11.GL_LINES -> {
                for (int i = 0; i + 1 < n; i += 2) put(v, at, base, out, i, i + 1);
            }
            case GL11.GL_LINE_STRIP -> {
                for (int i = 0; i + 1 < n; i++) put(v, at, base, out, i, i + 1);
            }
            case GL11.GL_LINE_LOOP -> {
                for (int i = 0; i + 1 < n; i++) put(v, at, base, out, i, i + 1);
                if (n > 1) put(v, at, base, out, n - 1, 0);
            }
            case GL11.GL_TRIANGLES -> {
                for (int i = 0; i + 2 < n; i += 3) put(v, at, base, out, i, i + 1, i + 2);
            }
            case GL11.GL_TRIANGLE_STRIP -> {
                for (int i = 0; i + 2 < n; i++) {
                    if (i % 2 == 0) put(v, at, base, out, i, i + 1, i + 2);
                    else if (first) put(v, at, base, out, i, i + 2, i + 1);
                    else put(v, at, base, out, i + 1, i, i + 2);
                }
            }
            case GL11.GL_TRIANGLE_FAN -> {
                for (int i = 1; i + 1 < n; i++) {
                    if (first) put(v, at, base, out, i, i + 1, 0);
                    else put(v, at, base, out, 0, i, i + 1);
                }
            }
            case GL32.GL_LINES_ADJACENCY -> {
                for (int i = 0; i + 3 < n; i += 4) put(v, at, base, out, i, i + 1, i + 2, i + 3);
            }
            case GL32.GL_LINE_STRIP_ADJACENCY -> {
                for (int i = 0; i + 3 < n; i++) put(v, at, base, out, i, i + 1, i + 2, i + 3);
            }
            case GL32.GL_TRIANGLES_ADJACENCY -> {
                for (int i = 0; i + 5 < n; i += 6) put(v, at, base, out, i, i + 1, i + 2, i + 3, i + 4, i + 5);
            }
            case GL32.GL_TRIANGLE_STRIP_ADJACENCY -> stripAdjacency(v, at, n, base, out);
            default -> {}
        }
    }

    /** GL's table for this mode, as vertices 0 to 5 of what the shader takes: three corners, each followed by the vertex adjacent to its edge. */
    private static void stripAdjacency(int[] v, int at, int n, int base, IntArrayList out) {
        final int triangles = n < 6 ? 0 : (n - 4) / 2;
        for (int i = 0; i < triangles; i++) {
            final boolean last = i == triangles - 1;
            if (i == 0) {
                put(v, at, base, out, 0, 1, 2, last ? 5 : 6, 4, 3);
            } else if (i % 2 == 1) {
                put(v, at, base, out, 2 * i + 2, 2 * i - 2, 2 * i, 2 * i + 3, 2 * i + 4, last ? 2 * i + 5 : 2 * i + 6);
            } else {
                put(v, at, base, out, 2 * i, 2 * i - 2, 2 * i + 2, last ? 2 * i + 5 : 2 * i + 6, 2 * i + 4, 2 * i + 3);
            }
        }
    }
}
