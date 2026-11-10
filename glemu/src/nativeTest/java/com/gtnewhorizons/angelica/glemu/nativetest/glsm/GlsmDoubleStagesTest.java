package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Doubles keep every bit from a vertex array, a constant attribute, and through each stage on the way to the fragment shader. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmDoubleStagesTest {
    /** The last two are a denormal and the largest double, which a float could hold neither of. */
    private static final double[] ARRAY = {1.0000000000000002, -3.141592653589793, 123456789.12345679, 7.000000000000001, 2.5, -0.0, 1e-310, Double.MAX_VALUE};
    private static final double[] CONSTANT = {0.1, -0.2, 0.30000000000000004, 1e300};
    private static final int STRIDE = 64, RESULTS = 12;

    private static final String VERTEX = """
        #version 430 core
        layout(location = 0) in dvec3 a;
        layout(location = 1) in double b;
        layout(location = 2) in dvec4 c;
        layout(location = 3) in dmat2 m;
        flat out dvec3 va; flat out double vb; flat out dvec4 vc; flat out dmat2 vm;
        void main() {
            va = a; vb = b; vc = c; vm = m;
            gl_Position = vec4(float((gl_VertexID & 1) * 4 - 1), float((gl_VertexID >> 1) * 4 - 1), 0.0, 1.0);
        }
        """;

    private static final String GEOMETRY = """
        #version 430 core
        layout(triangles) in;
        layout(triangle_strip, max_vertices = 3) out;
        flat in dvec3 va[]; flat in double vb[]; flat in dvec4 vc[]; flat in dmat2 vm[];
        flat out dvec3 fa; flat out double fb; flat out dvec4 fc; flat out dmat2 fm;
        void main() {
            for (int i = 0; i < 3; i++) {
                fa = va[i]; fb = vb[i]; fc = vc[i]; fm = vm[i];
                gl_Position = gl_in[i].gl_Position;
                EmitVertex();
            }
            EndPrimitive();
        }
        """;

    private static final String CONTROL = """
        #version 430 core
        layout(vertices = 3) out;
        flat in dvec3 va[]; flat in double vb[]; flat in dvec4 vc[]; flat in dmat2 vm[];
        out dvec3 ta[]; out double tb[]; out dvec4 tc[]; out dmat2 tm[];
        void main() {
            ta[gl_InvocationID] = va[gl_InvocationID];
            tb[gl_InvocationID] = vb[gl_InvocationID];
            tc[gl_InvocationID] = vc[gl_InvocationID];
            tm[gl_InvocationID] = vm[gl_InvocationID];
            gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
            gl_TessLevelOuter[0] = 1.0; gl_TessLevelOuter[1] = 1.0; gl_TessLevelOuter[2] = 1.0; gl_TessLevelInner[0] = 1.0;
        }
        """;

    private static final String EVALUATION = """
        #version 430 core
        layout(triangles) in;
        in dvec3 ta[]; in double tb[]; in dvec4 tc[]; in dmat2 tm[];
        flat out dvec3 fa; flat out double fb; flat out dvec4 fc; flat out dmat2 fm;
        void main() {
            fa = ta[2]; fb = tb[2]; fc = tc[2]; fm = tm[2];
            gl_Position = gl_TessCoord.x * gl_in[0].gl_Position + gl_TessCoord.y * gl_in[1].gl_Position + gl_TessCoord.z * gl_in[2].gl_Position;
        }
        """;

    private static String fragment(String prefix) {
        return """
            #version 430 core
            flat in dvec3 Pa; flat in double Pb; flat in dvec4 Pc; flat in dmat2 Pm;
            layout(std430, binding = 0) buffer Out { double r[12]; };
            layout(location = 0) out vec4 color;
            void main() {
                r[0] = Pa.x; r[1] = Pa.y; r[2] = Pa.z; r[3] = Pb;
                r[4] = Pc.x; r[5] = Pc.y; r[6] = Pc.z; r[7] = Pc.w;
                r[8] = Pm[0][0]; r[9] = Pm[0][1]; r[10] = Pm[1][0]; r[11] = Pm[1][1];
                color = vec4(1.0);
            }
            """.replace("P", prefix);
    }

    private static int link(String... sources) {
        final int program = GL20C.glCreateProgram();
        final int[] types = sources.length == 2 ? new int[] {GL20C.GL_VERTEX_SHADER, GL20C.GL_FRAGMENT_SHADER}
            : sources.length == 3 ? new int[] {GL20C.GL_VERTEX_SHADER, GL32C.GL_GEOMETRY_SHADER, GL20C.GL_FRAGMENT_SHADER}
            : new int[] {GL20C.GL_VERTEX_SHADER, GL40C.GL_TESS_CONTROL_SHADER, GL40C.GL_TESS_EVALUATION_SHADER, GL20C.GL_FRAGMENT_SHADER};
        for (int i = 0; i < sources.length; i++) {
            final int shader = GlTestSupport.compile(types[i], sources[i]);
            GL20C.glAttachShader(program, shader);
            GL20C.glDeleteShader(shader);
        }
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), () -> GL20C.glGetProgramInfoLog(program));
        return program;
    }

    @ParameterizedTest
    @ValueSource(strings = {"vertex", "geometry", "tessellation"})
    void everyBitArrives(String through) {
        final int program = switch (through) {
            case "vertex" -> link(VERTEX, fragment("v"));
            case "geometry" -> link(VERTEX, GEOMETRY, fragment("f"));
            default -> link(VERTEX, CONTROL, EVALUATION, fragment("f"));
        };
        final ByteBuffer vertices = BufferUtils.createByteBuffer(3 * STRIDE).order(ByteOrder.nativeOrder());
        for (int v = 0; v < 3; v++) {
            for (int k = 0; k < 4; k++) vertices.putDouble(v * STRIDE + k * 8, ARRAY[k]);
            for (int k = 0; k < 4; k++) vertices.putDouble(v * STRIDE + 32 + k * 8, ARRAY[4 + k]);
        }
        final int vao = GL30C.glGenVertexArrays(), buffer = GL15C.glGenBuffers(), results = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);
        GL41C.glVertexAttribLPointer(0, 3, GL11.GL_DOUBLE, STRIDE, 0L);
        GL41C.glVertexAttribLPointer(1, 1, GL11.GL_DOUBLE, STRIDE, 24L);
        GL41C.glVertexAttribLPointer(3, 2, GL11.GL_DOUBLE, STRIDE, 32L);
        GL41C.glVertexAttribLPointer(4, 2, GL11.GL_DOUBLE, STRIDE, 48L);
        for (int index : new int[] {0, 1, 3, 4}) GL20C.glEnableVertexAttribArray(index);
        GL41C.glVertexAttribL4d(2, CONSTANT[0], CONSTANT[1], CONSTANT[2], CONSTANT[3]);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, RESULTS * 8L, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, results);
        GL20C.glUseProgram(program);
        if (through.equals("tessellation")) {
            GL40C.glPatchParameteri(GL40C.GL_PATCH_VERTICES, 3);
            GL11.glDrawArrays(GL40C.GL_PATCHES, 0, 3);
        } else {
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        }
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL20C.glUseProgram(0);
        final ByteBuffer out = BufferUtils.createByteBuffer(RESULTS * 8).order(ByteOrder.nativeOrder());
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, out);
        final double[] expected = {ARRAY[0], ARRAY[1], ARRAY[2], ARRAY[3], CONSTANT[0], CONSTANT[1], CONSTANT[2], CONSTANT[3], ARRAY[4], ARRAY[5], ARRAY[6], ARRAY[7]};
        for (int k = 0; k < RESULTS; k++) {
            final double got = out.getDouble(k * 8);
            final int at = k;
            assertEquals(Double.doubleToRawLongBits(expected[k]), Double.doubleToRawLongBits(got), () -> through + ": result " + at + " is " + got + ", not " + expected[at]);
        }
        for (int index : new int[] {0, 1, 3, 4}) GL20C.glDisableVertexAttribArray(index);
        GL30C.glBindVertexArray(0);
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(buffer);
        GL15C.glDeleteBuffers(results);
        GL20C.glDeleteProgram(program);
    }

    /** The last locations still have room for the second halves of wide vectors. */
    @org.junit.jupiter.api.Test
    void wideVectorsAtTheLastLocations() {
        final int program = link("""
            #version 430 core
            layout(location = 14) in dvec3 a;
            layout(location = 15) in dvec4 b;
            flat out dvec3 va; flat out double vb; flat out dvec4 vc; flat out dmat2 vm;
            void main() {
                va = a; vb = b.w; vc = b; vm = dmat2(a.x, a.y, a.z, b.x);
                gl_Position = vec4(float((gl_VertexID & 1) * 4 - 1), float((gl_VertexID >> 1) * 4 - 1), 0.0, 1.0);
            }
            """, fragment("v"));
        final ByteBuffer vertices = BufferUtils.createByteBuffer(3 * STRIDE).order(ByteOrder.nativeOrder());
        for (int v = 0; v < 3; v++) {
            for (int k = 0; k < 3; k++) vertices.putDouble(v * STRIDE + k * 8, ARRAY[k]);
            for (int k = 0; k < 4; k++) vertices.putDouble(v * STRIDE + 32 + k * 8, ARRAY[4 + k]);
        }
        final int vao = GL30C.glGenVertexArrays(), buffer = GL15C.glGenBuffers(), results = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);
        GL41C.glVertexAttribLPointer(14, 3, GL11.GL_DOUBLE, STRIDE, 0L);
        GL41C.glVertexAttribLPointer(15, 4, GL11.GL_DOUBLE, STRIDE, 32L);
        GL20C.glEnableVertexAttribArray(14);
        GL20C.glEnableVertexAttribArray(15);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, RESULTS * 8L, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, results);
        GL20C.glUseProgram(program);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL20C.glUseProgram(0);
        final ByteBuffer out = BufferUtils.createByteBuffer(RESULTS * 8).order(ByteOrder.nativeOrder());
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, out);
        final double[] expected = {ARRAY[0], ARRAY[1], ARRAY[2], ARRAY[7], ARRAY[4], ARRAY[5], ARRAY[6], ARRAY[7], ARRAY[0], ARRAY[1], ARRAY[2], ARRAY[4]};
        for (int k = 0; k < RESULTS; k++) {
            final double got = out.getDouble(k * 8);
            final int at = k;
            assertEquals(Double.doubleToRawLongBits(expected[k]), Double.doubleToRawLongBits(got), () -> "result " + at + " is " + got + ", not " + expected[at]);
        }
        GL20C.glDisableVertexAttribArray(14);
        GL20C.glDisableVertexAttribArray(15);
        GL30C.glBindVertexArray(0);
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(buffer);
        GL15C.glDeleteBuffers(results);
        GL20C.glDeleteProgram(program);
    }

    /** An array of matrices of dvec3 columns, each column a location, reads every bit. */
    @org.junit.jupiter.api.Test
    void anArrayOfWideMatrices() {
        final int program = link("""
            #version 430 core
            layout(location = 2) in dmat2x3 m[2];
            flat out dvec3 va; flat out double vb; flat out dvec4 vc; flat out dmat2 vm;
            void main() {
                va = m[0][0]; vb = m[0][1].z; vc = dvec4(m[1][0], m[1][1].x); vm = dmat2(m[1][1].y, m[1][1].z, m[0][1].x, m[0][1].y);
                gl_Position = vec4(float((gl_VertexID & 1) * 4 - 1), float((gl_VertexID >> 1) * 4 - 1), 0.0, 1.0);
            }
            """, fragment("v"));
        final int stride = 4 * 32;
        final ByteBuffer vertices = BufferUtils.createByteBuffer(3 * stride).order(ByteOrder.nativeOrder());
        // Column col of the four (two per matrix) holds 10 col + r plus a fraction a float can't keep.
        for (int v = 0; v < 3; v++) for (int col = 0; col < 4; col++) for (int r = 0; r < 3; r++) vertices.putDouble(v * stride + col * 32 + r * 8, 10 * col + r + 0x1p-40);
        final int vao = GL30C.glGenVertexArrays(), buffer = GL15C.glGenBuffers(), results = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);
        for (int col = 0; col < 4; col++) {
            GL41C.glVertexAttribLPointer(2 + col, 3, GL11.GL_DOUBLE, stride, col * 32L);
            GL20C.glEnableVertexAttribArray(2 + col);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, RESULTS * 8L, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, results);
        GL20C.glUseProgram(program);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL20C.glUseProgram(0);
        final ByteBuffer out = BufferUtils.createByteBuffer(RESULTS * 8).order(ByteOrder.nativeOrder());
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, out);
        final double f = 0x1p-40;
        final double[] expected = {f, 1 + f, 2 + f, 12 + f, 20 + f, 21 + f, 22 + f, 30 + f, 31 + f, 32 + f, 10 + f, 11 + f};
        final StringBuilder got = new StringBuilder();
        for (int k = 0; k < RESULTS; k++) got.append(out.getDouble(k * 8)).append(' ');
        for (int k = 0; k < RESULTS; k++) {
            final int at = k;
            assertEquals(Double.doubleToRawLongBits(expected[k]), Double.doubleToRawLongBits(out.getDouble(k * 8)), () -> "result " + at + " of " + got);
        }
        for (int col = 0; col < 4; col++) GL20C.glDisableVertexAttribArray(2 + col);
        GL30C.glBindVertexArray(0);
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(buffer);
        GL15C.glDeleteBuffers(results);
        GL20C.glDeleteProgram(program);
    }

    /** Two kinds of wide matrix side by side, eight locations of them, as the Khronos limits test has. */
    @org.junit.jupiter.api.Test
    void eightLocationsOfWideMatrices() {
        final int program = link("""
            #version 430 core
            layout(location = 6) in dmat2x3 a0;
            layout(location = 4) in dmat2x3 a1;
            layout(location = 2) in dmat2x4 b0;
            layout(location = 0) in dmat2x4 b1;
            flat out dvec3 va; flat out double vb; flat out dvec4 vc; flat out dmat2 vm;
            void main() {
                va = a0[1]; vb = a1[0].z; vc = b0[1]; vm = dmat2(b1[0].w, b1[1].w, a1[1].x, b0[0].w);
                gl_Position = vec4(float((gl_VertexID & 1) * 4 - 1), float((gl_VertexID >> 1) * 4 - 1), 0.0, 1.0);
            }
            """, fragment("v"));
        // Tightly packed doubles: a0, a1 (6 each), b0, b1 (8 each); element e of matrix n holds 100 n + e plus a fraction.
        final int[] counts = {6, 6, 8, 8}, locations = {6, 4, 2, 0}, rows = {3, 3, 4, 4};
        final int stride = 28 * 8;
        final double f = 0x1p-40;
        final ByteBuffer vertices = BufferUtils.createByteBuffer(3 * stride).order(ByteOrder.nativeOrder());
        for (int v = 0; v < 3; v++) {
            int at = 0;
            for (int n = 0; n < 4; n++) for (int e = 0; e < counts[n]; e++) vertices.putDouble(v * stride + 8 * at++, 100 * n + e + f);
        }
        final int vao = GL30C.glGenVertexArrays(), buffer = GL15C.glGenBuffers(), results = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);
        int offset = 0;
        for (int n = 0; n < 4; n++) {
            for (int col = 0; col < 2; col++) {
                GL41C.glVertexAttribLPointer(locations[n] + col, rows[n], GL11.GL_DOUBLE, stride, offset * 8L);
                GL20C.glEnableVertexAttribArray(locations[n] + col);
                offset += rows[n];
            }
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, RESULTS * 8L, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, results);
        GL20C.glUseProgram(program);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL20C.glUseProgram(0);
        final ByteBuffer out = BufferUtils.createByteBuffer(RESULTS * 8).order(ByteOrder.nativeOrder());
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, out);
        final double[] expected = {3 + f, 4 + f, 5 + f, 102 + f, 204 + f, 205 + f, 206 + f, 207 + f, 303 + f, 307 + f, 103 + f, 203 + f};
        final StringBuilder got = new StringBuilder();
        for (int k = 0; k < RESULTS; k++) got.append(out.getDouble(k * 8)).append(' ');
        for (int k = 0; k < RESULTS; k++) {
            final int at = k;
            assertEquals(Double.doubleToRawLongBits(expected[k]), Double.doubleToRawLongBits(out.getDouble(k * 8)), () -> "result " + at + " of " + got);
        }
        for (int n = 0; n < 8; n++) GL20C.glDisableVertexAttribArray(n);
        GL30C.glBindVertexArray(0);
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(buffer);
        GL15C.glDeleteBuffers(results);
        GL20C.glDeleteProgram(program);
    }

    @org.junit.jupiter.api.Test
    void wideMatricesCompareWithTheirConstructors() {
        final StringBuilder vs = new StringBuilder("#version 430 core\nlayout(location = 6) in dmat2x3 a0;\nlayout(location = 4) in dmat2x3 a1;\nlayout(location = 2) in dmat2x4 b0;\nlayout(location = 0) in dmat2x4 b1;\n"
            + "flat out dvec3 va; flat out double vb; flat out dvec4 vc; flat out dmat2 vm;\nvoid main() {\n    double ok = 1.0lf;\n");
        final String[] names = {"a0", "a1", "b0", "b1"};
        final int[] counts = {6, 6, 8, 8}, types = {0, 0, 1, 1}, ordinals = {0, 1, 2, 3};
        for (int n = 0; n < 4; n++) {
            vs.append(n == 0 ? "    if (" : "    else if (").append(names[n]).append(" != ").append(counts[n] == 6 ? "dmat2x3" : "dmat2x4").append('(');
            for (int e = 0; e < counts[n]; e++) vs.append(e > 0 ? ", " : "").append(types[n]).append(" + ").append(ordinals[n]).append(" + gl_VertexID * 16 + ").append(e);
            vs.append(")) ok = ").append(-n - 1).append(".0lf;\n");
        }
        vs.append("    va = dvec3(ok); vb = ok; vc = dvec4(a0[0], a0[1].x); vm = dmat2(b1[0].x, b1[1].w, a1[0].x, b0[1].w);\n"
            + "    gl_Position = vec4(float((gl_VertexID & 1) * 4 - 1), float((gl_VertexID >> 1) * 4 - 1), 0.0, 1.0);\n}\n");
        final int program = link(vs.toString(), fragment("v"));
        final int[] locations = {6, 4, 2, 0}, rows = {3, 3, 4, 4};
        final int stride = 28 * 8;
        final ByteBuffer vertices = BufferUtils.createByteBuffer(3 * stride).order(ByteOrder.nativeOrder());
        for (int v = 0; v < 3; v++) {
            int at = 0;
            for (int n = 0; n < 4; n++) for (int e = 0; e < counts[n]; e++) vertices.putDouble(v * stride + 8 * at++, types[n] + ordinals[n] + v * 16 + e);
        }
        final int vao = GL30C.glGenVertexArrays(), buffer = GL15C.glGenBuffers(), results = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);
        int offset = 0;
        for (int n = 0; n < 4; n++) {
            for (int col = 0; col < 2; col++) {
                GL41C.glVertexAttribLPointer(locations[n] + col, rows[n], GL11.GL_DOUBLE, stride, offset * 8L);
                GL20C.glEnableVertexAttribArray(locations[n] + col);
                offset += rows[n];
            }
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, RESULTS * 8L, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, results);
        GL20C.glUseProgram(program);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL20C.glUseProgram(0);
        final ByteBuffer out = BufferUtils.createByteBuffer(RESULTS * 8).order(ByteOrder.nativeOrder());
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, out);
        final StringBuilder got = new StringBuilder();
        for (int k = 0; k < RESULTS; k++) got.append(out.getDouble(k * 8)).append(' ');
        assertEquals(1.0, out.getDouble(0), () -> "every matrix as its constructor: " + got);
        for (int n = 0; n < 8; n++) GL20C.glDisableVertexAttribArray(n);
        GL30C.glBindVertexArray(0);
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(buffer);
        GL15C.glDeleteBuffers(results);
        GL20C.glDeleteProgram(program);
    }

    @org.junit.jupiter.api.Test
    void anArrayOfMatricesFillingEveryLocation() {
        final StringBuilder vs = new StringBuilder("#version 430 core\nin dmat2 attribute_0[8];\nflat out dvec3 va; flat out double vb; flat out dvec4 vc; flat out dmat2 vm;\nvoid main() {\n    double ok = 1.0lf;\n");
        for (int n = 0; n < 8; n++) {
            vs.append(n == 0 ? "    if (" : "    else if (").append("attribute_0[").append(n).append("] != dmat2(");
            for (int e = 0; e < 4; e++) vs.append(e > 0 ? ", " : "").append("0 + ").append(n).append(" + gl_VertexID * 16 + ").append(e);
            vs.append(")) ok = ").append(-n - 1).append(".0lf;\n");
        }
        vs.append("    va = dvec3(ok); vb = ok; vc = dvec4(attribute_0[0][0], attribute_0[7][1]); vm = attribute_0[3];\n"
            + "    gl_Position = vec4(float((gl_VertexID & 1) * 4 - 1), float((gl_VertexID >> 1) * 4 - 1), 0.0, 1.0);\n}\n");
        final int program = link(vs.toString(), fragment("v"));
        final int location = GL20C.glGetAttribLocation(program, "attribute_0");
        final int stride = 32 * 8;
        final ByteBuffer vertices = BufferUtils.createByteBuffer(3 * stride).order(ByteOrder.nativeOrder());
        for (int v = 0; v < 3; v++) for (int n = 0; n < 8; n++) for (int e = 0; e < 4; e++) vertices.putDouble(v * stride + (n * 4 + e) * 8, n + v * 16 + e);
        final int vao = GL30C.glGenVertexArrays(), buffer = GL15C.glGenBuffers(), results = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);
        for (int col = 0; col < 16; col++) {
            GL41C.glVertexAttribLPointer(location + col, 2, GL11.GL_DOUBLE, stride, col * 16L);
            GL20C.glEnableVertexAttribArray(location + col);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, RESULTS * 8L, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, results);
        GL20C.glUseProgram(program);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL20C.glUseProgram(0);
        final ByteBuffer out = BufferUtils.createByteBuffer(RESULTS * 8).order(ByteOrder.nativeOrder());
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, out);
        final StringBuilder got = new StringBuilder("location " + location + ": ");
        for (int k = 0; k < RESULTS; k++) got.append(out.getDouble(k * 8)).append(' ');
        assertEquals(1.0, out.getDouble(0), () -> "every matrix as its constructor: " + got);
        for (int n = 0; n < 16; n++) GL20C.glDisableVertexAttribArray(n);
        GL30C.glBindVertexArray(0);
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(buffer);
        GL15C.glDeleteBuffers(results);
        GL20C.glDeleteProgram(program);
    }

    @org.junit.jupiter.api.Test
    void anArrayOfMatricesCapturedByFeedback() {
        final StringBuilder vs = new StringBuilder("#version 400\n#extension GL_ARB_vertex_attrib_64bit : require\n\nprecision highp float;\n\nin dmat2 attribute_0[8];\nout int result;\nvoid main() {\n");
        for (int n = 0; n < 8; n++) {
            vs.append(n == 0 ? "    if (" : "    else if (").append("attribute_0[").append(n).append("] != dmat2(");
            for (int e = 0; e < 4; e++) vs.append(e > 0 ? ", " : "").append("0 + ").append(n).append(" + gl_VertexID * 16 + ").append(e);
            vs.append(")) result = ").append(-n - 1).append(";\n");
        }
        vs.append("    else result = 1;\n}\n");
        final int shader = GlTestSupport.compile(GL20C.GL_VERTEX_SHADER, vs.toString());
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        org.lwjgl.opengl.GL30C.glTransformFeedbackVaryings(program, new CharSequence[] {"result"}, org.lwjgl.opengl.GL30C.GL_INTERLEAVED_ATTRIBS);
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), () -> GL20C.glGetProgramInfoLog(program));
        final int location = GL20C.glGetAttribLocation(program, "attribute_0");
        final int stride = 32 * 8;
        final ByteBuffer vertices = BufferUtils.createByteBuffer(3 * stride).order(ByteOrder.nativeOrder());
        for (int v = 0; v < 3; v++) for (int n = 0; n < 8; n++) for (int e = 0; e < 4; e++) vertices.putDouble(v * stride + (n * 4 + e) * 8, n + v * 16 + e);
        final int vao = GL30C.glGenVertexArrays(), buffer = GL15C.glGenBuffers(), captured = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);
        for (int col = 0; col < 16; col++) {
            GL41C.glVertexAttribLPointer(location + col, 2, GL11.GL_DOUBLE, stride, col * 16L);
            GL20C.glEnableVertexAttribArray(location + col);
        }
        GL15C.glBindBuffer(org.lwjgl.opengl.GL30C.GL_TRANSFORM_FEEDBACK_BUFFER, captured);
        GL15C.glBufferData(org.lwjgl.opengl.GL30C.GL_TRANSFORM_FEEDBACK_BUFFER, 3 * 4L, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(org.lwjgl.opengl.GL30C.GL_TRANSFORM_FEEDBACK_BUFFER, 0, captured);
        GL20C.glUseProgram(program);
        GL11.glEnable(org.lwjgl.opengl.GL30C.GL_RASTERIZER_DISCARD);
        org.lwjgl.opengl.GL30C.glBeginTransformFeedback(GL11.GL_POINTS);
        GL11.glDrawArrays(GL11.GL_POINTS, 0, 3);
        org.lwjgl.opengl.GL30C.glEndTransformFeedback();
        GL11.glDisable(org.lwjgl.opengl.GL30C.GL_RASTERIZER_DISCARD);
        GL20C.glUseProgram(0);
        final ByteBuffer out = BufferUtils.createByteBuffer(3 * 4).order(ByteOrder.nativeOrder());
        GL15C.glGetBufferSubData(org.lwjgl.opengl.GL30C.GL_TRANSFORM_FEEDBACK_BUFFER, 0, out);
        final StringBuilder active = new StringBuilder();
        for (int a = 0; a < GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_ATTRIBUTES); a++) {
            final java.nio.IntBuffer size = BufferUtils.createIntBuffer(1), type = BufferUtils.createIntBuffer(1);
            final String name = GL20C.glGetActiveAttrib(program, a, size, type);
            active.append(name).append('@').append(GL20C.glGetAttribLocation(program, name)).append(" x").append(size.get(0)).append(" 0x").append(Integer.toHexString(type.get(0))).append("; ");
        }
        final String got = location + ": " + out.getInt(0) + " " + out.getInt(4) + " " + out.getInt(8) + " / " + active;
        for (int v = 0; v < 3; v++) assertEquals(1, out.getInt(v * 4), () -> "captured " + got);
        for (int n = 0; n < 16; n++) GL20C.glDisableVertexAttribArray(n);
        GL30C.glBindVertexArray(0);
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(buffer);
        GL15C.glDeleteBuffers(captured);
        GL20C.glDeleteProgram(program);
        GL20C.glDeleteShader(shader);
    }
}
