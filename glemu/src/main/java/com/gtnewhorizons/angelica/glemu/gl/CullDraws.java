package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.shader.CullDistanceLowering;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws a program whose cull distances CullDistanceLowering rewrote. The draw first runs with rasterization off to store each vertex's
 * distances. Where the device can flag culled primitives itself it then draws them all flagged; otherwise the distances are waited for
 * and read here, and the draw runs again as lists without the primitives GL would cull. A primitive goes when one distance is negative
 * at all of its vertices.
 */
final class CullDraws {
    private final GlBase<?, ?, ?> gl;
    private boolean busy;
    private int buffer;

    CullDraws(GlBase<?, ?, ?> gl) {
        this.gl = gl;
    }

    boolean applies(GlState st) {
        if (busy) return false;
        final ProgramBase<?> p = gl.programs().current(st);
        return p != null && p.linked && p.cullDistances > 0;
    }

    void arrays(GlState st, int mode, int first, int count, int instances) {
        if (count <= 0 || instances <= 0) return;
        final int[] vertices = new int[count];
        for (int i = 0; i < count; i++) vertices[i] = first + i;
        if (onDevice(st)) {
            storeDistances(st, first, count, instances, () -> gl.drawArraysInstanced(mode, first, count, instances));
            drawFlagged(st, mode, vertices, count, first, count, instances, -1);
            return;
        }
        final FloatBuffer distances = stored(st, first, count, instances, () -> gl.drawArraysInstanced(mode, first, count, instances));
        draw(st, mode, vertices, count, first, count, instances, distances, -1);
    }

    void elements(GlState st, int mode, int count, int type, long indices, int instances, int baseVertex) {
        if (count <= 0 || instances <= 0) return;
        final int[] vertices = gl.readIndices(st, count, type, indices);
        final long restart = st.primitiveRestart ? restartIndex(st, type) : -1L;
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            if (Integer.toUnsignedLong(vertices[i]) == restart) continue;
            vertices[i] += baseVertex;
            lowest = Math.min(lowest, vertices[i]);
            highest = Math.max(highest, vertices[i]);
        }
        if (lowest > highest) return;
        final int base = lowest, span = highest - lowest + 1;
        final int restartAt = restart == -1L ? -1 : (int) restart + baseVertex;
        if (onDevice(st)) {
            storeDistances(st, base, span, instances, () -> gl.drawElementsInstancedBaseVertex(mode, count, type, indices, instances, baseVertex));
            drawFlagged(st, mode, vertices, count, base, span, instances, restartAt);
            return;
        }
        final FloatBuffer distances = stored(st, base, span, instances, () -> gl.drawElementsInstancedBaseVertex(mode, count, type, indices, instances, baseVertex));
        draw(st, mode, vertices, count, base, span, instances, distances, restartAt);
    }

    private static long restartIndex(GlState st, int type) {
        if (!st.primitiveRestartFixed) return Integer.toUnsignedLong(st.primitiveRestartIndex);
        return type == GL11.GL_UNSIGNED_BYTE ? 0xFFL : type == GL11.GL_UNSIGNED_SHORT ? 0xFFFFL : 0xFFFFFFFFL;
    }

    private boolean onDevice(GlState st) {
        final ProgramBase<?> p = gl.programs().current(st);
        return p.cullsOnDevice && flagsProgram() != 0;
    }

    private FloatBuffer stored(GlState st, int base, int span, int instances, Runnable draw) {
        storeDistances(st, base, span, instances, draw);
        final ProgramBase<?> p = gl.programs().current(st);
        final long bytes = (long) span * instances * p.cullDistances * Float.BYTES;
        gl.finish();
        final ByteBuffer read = ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
        final int generic = gl.getInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        gl.getBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, read);
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, generic);
        return read.asFloatBuffer();
    }

    /** Runs the draw with rasterization off, each vertex storing its distances. */
    private void storeDistances(GlState st, int base, int span, int instances, Runnable draw) {
        final ProgramBase<?> p = gl.programs().current(st);
        final int count = p.cullDistances;
        final long bytes = (long) span * instances * count * Float.BYTES;
        if (buffer == 0) buffer = gl.genBuffers();
        final int generic = gl.getInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        gl.bufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_DYNAMIC_READ);
        gl.bindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, CullDistanceLowering.STORAGE_BINDING, buffer, 0L, bytes);
        final boolean discarding = st.rasterizerDiscard;
        uniform(p, CullDistanceLowering.ENABLED, 1);
        uniform(p, CullDistanceLowering.BASE, base);
        uniform(p, CullDistanceLowering.SPAN, span);
        uniform(p, CullDistanceLowering.FIRST_INSTANCE, st.baseInstance);
        if (!discarding) gl.enable(GL30.GL_RASTERIZER_DISCARD);
        busy = true;
        try {
            draw.run();
        } finally {
            busy = false;
            if (!discarding) gl.disable(GL30.GL_RASTERIZER_DISCARD);
            uniform(p, CullDistanceLowering.ENABLED, 0);
        }
        gl.memoryBarrier(GL42.GL_ALL_BARRIER_BITS);
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, generic);
    }

    private int flagsProgram = -1, cornersBuffer, flagsBuffer;

    private int flagsProgram() {
        if (flagsProgram >= 0) return flagsProgram;
        final int shader = gl.createShader(GL43.GL_COMPUTE_SHADER);
        gl.shaderSource(shader, String.format(CullDistanceLowering.FLAGS, CullDistanceLowering.STORAGE_BINDING, CORNERS_BINDING, CullDistanceLowering.CULLED_BINDING));
        gl.compileShader(shader);
        final int program = gl.createProgram();
        gl.attachShader(program, shader);
        gl.linkProgram(program);
        gl.deleteShader(shader);
        if (gl.getProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            gl.warnOnce("the cull distance pass failed to link, so culled primitives wait to be read back: " + gl.getProgramInfoLog(program, 4096));
            return flagsProgram = 0;
        }
        return flagsProgram = program;
    }

    private static final int CORNERS_BINDING = GlState.MAX_BUFFER_BINDINGS - 15;

    /**
     * Draws every primitive, those GL culls marked by a compute pass for the fragment shader to drop, so nothing waits for the distances. Each
     * primitive keeps its place, and what is captured or counted before rasterization sees them all, as GL has it.
     */
    private void drawFlagged(GlState st, int mode, int[] vertices, int count, int base, int span, int instances, int restart) {
        final ProgramBase<?> p = gl.programs().current(st);
        final int[] kept = new int[Math.max(6, count * 6)];
        final it.unimi.dsi.fastutil.ints.IntArrayList corners = new it.unimi.dsi.fastutil.ints.IntArrayList();
        assembling = corners;
        final int length;
        try {
            length = kept(st, mode, vertices, count, base, 0, 0, null, restart, kept);
        } finally {
            assembling = null;
        }
        final int primitives = corners.size() / 4;
        if (length == 0 || primitives == 0) return;
        final int generic = gl.getInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        if (cornersBuffer == 0) cornersBuffer = gl.genBuffers();
        if (flagsBuffer == 0) flagsBuffer = gl.genBuffers();
        final ByteBuffer cornerBytes = ByteBuffer.allocateDirect(corners.size() * Integer.BYTES).order(ByteOrder.nativeOrder());
        cornerBytes.asIntBuffer().put(corners.elements(), 0, corners.size());
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, cornersBuffer);
        gl.bufferData(GL43.GL_SHADER_STORAGE_BUFFER, cornerBytes, GL15.GL_STREAM_DRAW);
        gl.bindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, CORNERS_BINDING, cornersBuffer, 0L, cornerBytes.remaining());
        final long flagBytes = (long) primitives * instances * Integer.BYTES;
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, flagsBuffer);
        gl.bufferData(GL43.GL_SHADER_STORAGE_BUFFER, flagBytes, GL15.GL_STREAM_DRAW);
        gl.bindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, CullDistanceLowering.CULLED_BINDING, flagsBuffer, 0L, flagBytes);
        gl.bindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, generic);
        final int program = flagsProgram();
        gl.useProgram(program);
        computeUniform(program, "angelica_cull_primitives", primitives);
        computeUniform(program, "angelica_cull_instances", instances);
        computeUniform(program, "angelica_cull_span", span);
        computeUniform(program, "angelica_cull_count", p.cullDistances);
        gl.dispatchStage((primitives * instances + 63) / 64);
        gl.useProgram(p.id);
        gl.memoryBarrier(GL42.GL_ALL_BARRIER_BITS);
        final int firstInstance = st.baseInstance;
        uniform(p, CullDistanceLowering.CULLED_ENABLED, 1);
        try {
            for (int instance = 0; instance < instances; instance++) {
                st.baseInstance = firstInstance + instance;
                uniform(p, CullDistanceLowering.CULLED_FIRST, instance * primitives);
                gl.drawKeptIndices(st, listMode(mode), kept, length);
            }
        } finally {
            st.baseInstance = firstInstance;
            uniform(p, CullDistanceLowering.CULLED_ENABLED, 0);
        }
    }

    private void computeUniform(int program, String name, int value) {
        final int location = gl.getUniformLocation(program, name);
        if (location >= 0) gl.uniform1i(location, value);
    }

    /** While a list is assembled for the device to cull, every primitive is kept and its corners noted here. */
    private it.unimi.dsi.fastutil.ints.IntArrayList assembling;

    private void corners(int[] v, int base, int... at) {
        for (int c = 0; c < 4; c++) assembling.add(c < at.length ? v[at[c]] - base : 0xFFFFFFFF);
    }

    private void uniform(ProgramBase<?> p, String name, int value) {
        final int location = gl.getUniformLocation(p.id, name);
        if (location >= 0) gl.uniform1i(location, value);
    }

    private void draw(GlState st, int mode, int[] vertices, int count, int base, int span, int instances, FloatBuffer distances, int restart) {
        final int cullCount = gl.programs().current(st).cullDistances;
        final int firstInstance = st.baseInstance;
        final int[] kept = new int[Math.max(6, count * 6)];
        try {
            for (int instance = 0; instance < instances; instance++) {
                st.baseInstance = firstInstance + instance;
                final int length = kept(st, mode, vertices, count, base, span * instance, cullCount, distances, restart, kept);
                if (length > 0) gl.drawKeptIndices(st, listMode(mode), kept, length);
            }
        } finally {
            st.baseInstance = firstInstance;
        }
    }

    private static int listMode(int mode) {
        return switch (mode) {
            case GL11.GL_POINTS -> GL11.GL_POINTS;
            case GL11.GL_LINES, GL11.GL_LINE_STRIP, GL11.GL_LINE_LOOP, GL32.GL_LINES_ADJACENCY, GL32.GL_LINE_STRIP_ADJACENCY -> GL11.GL_LINES;
            default -> GL11.GL_TRIANGLES;
        };
    }

    /** Assembles the primitives of the mode, run by run between restarts, and returns how many indices of them remain. */
    private int kept(GlState st, int mode, int[] v, int count, int base, int instanceOffset, int cullCount, FloatBuffer d, int restart, int[] out) {
        int length = 0, start = 0;
        while (start < count) {
            int end = start;
            while (end < count && (restart == -1 || v[end] != restart)) end++;
            length = run(st, mode, v, start, end - start, base, instanceOffset, cullCount, d, out, length);
            start = end + 1;
        }
        return length;
    }

    private int run(GlState st, int mode, int[] v, int at, int n, int base, int offset, int cullCount, FloatBuffer d, int[] out, int length) {
        final boolean first = st.provokingFirst;
        switch (mode) {
            case GL11.GL_POINTS -> {
                for (int i = 0; i < n; i++) length = keep(v, base, offset, cullCount, d, out, length, at + i);
            }
            case GL11.GL_LINES -> {
                for (int i = 0; i + 1 < n; i += 2) length = keep(v, base, offset, cullCount, d, out, length, at + i, at + i + 1);
            }
            case GL11.GL_LINE_STRIP -> {
                for (int i = 0; i + 1 < n; i++) length = keep(v, base, offset, cullCount, d, out, length, at + i, at + i + 1);
            }
            case GL11.GL_LINE_LOOP -> {
                for (int i = 0; i + 1 < n; i++) length = keep(v, base, offset, cullCount, d, out, length, at + i, at + i + 1);
                if (n > 1) length = keep(v, base, offset, cullCount, d, out, length, at + n - 1, at);
            }
            case GL11.GL_TRIANGLES -> {
                for (int i = 0; i + 2 < n; i += 3) length = keep(v, base, offset, cullCount, d, out, length, at + i, at + i + 1, at + i + 2);
            }
            case GL11.GL_TRIANGLE_STRIP -> {
                for (int i = 0; i + 2 < n; i++) {
                    final int a = at + i, b = a + 1, c = a + 2;
                    // Even triangles keep their order; odd ones swap two so their winding matches, keeping the provoking vertex where the convention puts it.
                    length = i % 2 == 0 ? keep(v, base, offset, cullCount, d, out, length, a, b, c)
                        : first ? keep(v, base, offset, cullCount, d, out, length, a, c, b) : keep(v, base, offset, cullCount, d, out, length, b, a, c);
                }
            }
            case GL11.GL_TRIANGLE_FAN -> {
                for (int i = 1; i + 1 < n; i++) {
                    length = first ? keep(v, base, offset, cullCount, d, out, length, at + i, at + i + 1, at)
                        : keep(v, base, offset, cullCount, d, out, length, at, at + i, at + i + 1);
                }
            }
            case GL11.GL_QUADS -> {
                for (int i = 0; i + 3 < n; i += 4) {
                    if (assembling != null) {
                        corners(v, base, at + i, at + i + 1, at + i + 2, at + i + 3);
                        corners(v, base, at + i, at + i + 1, at + i + 2, at + i + 3);
                    }
                    if (assembling != null || !culled(v, base, offset, cullCount, d, at + i, at + i + 1, at + i + 2, at + i + 3)) {
                        length = put(v, out, length, at + i, at + i + 1, at + i + 3);
                        length = put(v, out, length, at + i + 1, at + i + 2, at + i + 3);
                    }
                }
            }
            default -> {
                // Anything else, such as GL_POLYGON, is assembled as a fan.
                for (int i = 1; i + 1 < n; i++) length = keep(v, base, offset, cullCount, d, out, length, at + i, at + i + 1, at);
            }
        }
        return length;
    }

    private int keep(int[] v, int base, int offset, int cullCount, FloatBuffer d, int[] out, int length, int... at) {
        if (assembling != null) {
            corners(v, base, at);
            return put(v, out, length, at);
        }
        return culled(v, base, offset, cullCount, d, at) ? length : put(v, out, length, at);
    }

    private static int put(int[] v, int[] out, int length, int... at) {
        for (int i : at) out[length++] = v[i];
        return length;
    }

    private static boolean culled(int[] v, int base, int offset, int cullCount, FloatBuffer d, int... at) {
        for (int plane = 0; plane < cullCount; plane++) {
            boolean outside = true;
            for (int i : at) {
                if (d.get((offset + v[i] - base) * cullCount + plane) >= 0.0f) {
                    outside = false;
                    break;
                }
            }
            if (outside) return true;
        }
        return false;
    }
}
