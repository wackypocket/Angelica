package com.gtnewhorizons.angelica.glemu.nativetest.glsm;

import com.gtnewhorizons.angelica.glsm.GlTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.util.Random;
import java.util.function.DoubleBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Doubles in a shader are the IEEE doubles Java has, to the bit, on devices that have to work them out in software. */
@Tag("glsm-native")
@ExtendWith(GlsmFrameExtension.class)
class GlsmDoubleArithmeticTest {
    private static final String[] OPERATIONS = {
        "x + y", "x - y", "x * y", "x / y", "sqrt(abs(x))", "floor(x)", "ceil(x)", "trunc(x)", "fract(x)", "min(x, y)", "max(x, y)", "mod(x, y)", "-x", "abs(x)", "sign(x)",
        "x < y ? 1.0lf : 0.0lf", "x <= y ? 1.0lf : 0.0lf", "x == y ? 1.0lf : 0.0lf", "double(float(x))", "ldexp(x, -1060)", "ldexp(x, 1000)", "x * 0.5lf",
    };
    private static final DoubleBinaryOperator[] EXPECTED = {
        (x, y) -> x + y, (x, y) -> x - y, (x, y) -> x * y, (x, y) -> x / y, (x, y) -> Math.sqrt(Math.abs(x)), (x, y) -> Math.floor(x), (x, y) -> Math.ceil(x),
        (x, y) -> x < 0 ? Math.ceil(x) : Math.floor(x), (x, y) -> x - Math.floor(x), (x, y) -> y < x ? y : x, (x, y) -> x < y ? y : x, (x, y) -> x - y * Math.floor(x / y), (x, y) -> -x, (x, y) -> Math.abs(x),
        (x, y) -> x == 0 ? 0 : Math.signum(x), (x, y) -> x < y ? 1 : 0, (x, y) -> x <= y ? 1 : 0, (x, y) -> x == y ? 1 : 0, (x, y) -> (double) (float) x,
        (x, y) -> Math.scalb(x, -1060), (x, y) -> Math.scalb(x, 1000), (x, y) -> x * 0.5,
    };
    private static final double[] EDGES = {0.0, -0.0, 1.0, -1.0, 0.5, 1.5, 2.5, -2.5, 3.0, 0.1, 1e300, -1e300, 1e-300, Double.MAX_VALUE, Double.MIN_NORMAL, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL / 3, -Double.MIN_NORMAL * 0.75, Double.MIN_NORMAL * (1 - 0x1p-52), Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 511.5, 512.5};

    private static int program(String source) {
        final int shader = GlTestSupport.compile(GL43C.GL_COMPUTE_SHADER, source);
        final int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), () -> GL20C.glGetProgramInfoLog(program));
        GL20C.glDeleteShader(shader);
        return program;
    }

    /**
     * What GLSL leaves to the device, which a device running doubles itself does differently: built-ins need not give NaN for
     * NaN (GLSL 4.60 section 4.7.1), which zero min and max give between 0 and -0 differs from driver to driver (NVIDIA +0,
     * Mesa -0), mod may fuse its multiply and subtract, as an expression not marked precise may, and Mesa folds a
     * double converted to float and back into the double it started as, which is equally allowed without precise.
     */
    private static boolean permitted(String operation, double x, double y, double expected, double got) {
        final boolean minMax = operation.startsWith("min") || operation.startsWith("max");
        if ((minMax || operation.startsWith("sign")) && (Double.isNaN(x) || Double.isNaN(y))) return true;
        if (minMax && expected == 0.0 && got == 0.0) return true;
        if (operation.startsWith("double(float") && Double.doubleToRawLongBits(got) == Double.doubleToRawLongBits(x)) return true;
        if (!operation.startsWith("mod")) return false;
        // x / y flushed to zero floors to zero, leaving x, where an unflushed negative quotient floors to -1.
        if (denormal(x / y) && got == x) return true;
        // Fused, y * floor(x / y) does not overflow, so the result stays the size of x where Java's is infinite.
        if (Double.isInfinite(expected) && Double.isFinite(got)) return Math.abs(got) <= Math.abs(x);
        return Math.abs(got - expected) <= Math.ulp(x);
    }

    private static boolean denormal(double value) {
        return value != 0.0 && Math.abs(value) < Double.MIN_NORMAL;
    }

    private static double flush(double value) {
        return denormal(value) ? Math.copySign(0.0, value) : value;
    }

    private static boolean same(double a, double b) {
        return a == b || Double.isNaN(a) && Double.isNaN(b);
    }

    /** GLSL 4.60 section 4.7.1 lets a shader flush denormalized values to zero, going in and coming out, as llvmpipe does. */
    private static boolean flushedDenormals(DoubleBinaryOperator operation, double x, double y, double expected, double got) {
        if (!denormal(x) && !denormal(y) && !denormal(expected)) return false;
        return same(got, flush(expected)) || same(got, flush(operation.applyAsDouble(flush(x), flush(y))));
    }

    @Test
    void arithmeticMatchesJavaToTheBit() {
        final Random random = new Random(1234);
        final int pairs = EDGES.length * EDGES.length + 400;
        final double[] xs = new double[pairs], ys = new double[pairs];
        int n = 0;
        for (double x : EDGES) {
            for (double y : EDGES) {
                xs[n] = x;
                ys[n++] = y;
            }
        }
        while (n < pairs) {
            // Spread over magnitudes, with some exact and some long mantissas.
            final double scale = Math.pow(2.0, random.nextInt(4) == 0 ? -1030 - random.nextInt(40) : random.nextInt(120) - 60);
            xs[n] = (random.nextDouble() - 0.5) * scale;
            ys[n++] = random.nextBoolean() ? (random.nextDouble() - 0.5) * scale : (random.nextInt(4096) - 2048) / 8.0;
        }
        final StringBuilder stores = new StringBuilder();
        for (int o = 0; o < OPERATIONS.length; o++) stores.append("    r[o + ").append(o).append("] = ").append(OPERATIONS[o]).append(";\n");
        final int program = program("#version 430 core\nlayout(local_size_x = 1) in;\n"
            + "layout(std430, binding = 0) buffer In { double a[" + pairs + "]; double b[" + pairs + "]; };\n"
            + "layout(std430, binding = 1) buffer Out { double r[" + pairs * OPERATIONS.length + "]; };\n"
            + "void main() {\n    uint i = gl_GlobalInvocationID.x, o = i * " + OPERATIONS.length + "u;\n    double x = a[i], y = b[i];\n" + stores + "}\n");
        final ByteBuffer input = BufferUtils.createByteBuffer(pairs * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < pairs; i++) input.putDouble(i * 8, xs[i]).putDouble((pairs + i) * 8, ys[i]);
        final int source = GL15C.glGenBuffers(), results = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, source);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, input, GL15C.GL_STATIC_DRAW);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, (long) pairs * OPERATIONS.length * 8, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, source);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, results);
        GL20C.glUseProgram(program);
        GL43C.glDispatchCompute(pairs, 1, 1);
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL20C.glUseProgram(0);
        final ByteBuffer output = BufferUtils.createByteBuffer(pairs * OPERATIONS.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, output);
        for (int i = 0; i < pairs; i++) {
            for (int o = 0; o < OPERATIONS.length; o++) {
                final double x = xs[i], y = ys[i];
                final double expected = EXPECTED[o].applyAsDouble(x, y), got = output.getDouble((i * OPERATIONS.length + o) * 8);
                final String what = OPERATIONS[o] + " with x=" + x + " y=" + y;
                if (permitted(OPERATIONS[o], x, y, expected, got) || flushedDenormals(EXPECTED[o], x, y, expected, got)) continue;
                assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(got), () -> what + " gave " + got + ", not " + expected);
            }
        }
        GL15C.glDeleteBuffers(source);
        GL15C.glDeleteBuffers(results);
        GL20C.glDeleteProgram(program);
    }

    /** A float can't hold this, and neither could a uniform that narrowed it. */
    @Test
    void aDoubleUniformKeepsEveryBit() {
        final int program = program("#version 430 core\nlayout(local_size_x = 1) in;\nuniform double u;\nuniform dmat3 m;\n"
            + "layout(std430, binding = 0) buffer Out { double r[2]; };\nvoid main() { r[0] = u; r[1] = m[2][1]; }\n");
        final double value = 1.0000000000000002;
        GL20C.glUseProgram(program);
        final int u = GL20C.glGetUniformLocation(program, "u"), m = GL20C.glGetUniformLocation(program, "m");
        GL40C.glUniform1d(u, value);
        final double[] matrix = new double[9];
        matrix[7] = value + 1.0;
        GL40C.glUniformMatrix3dv(m, false, matrix);
        final DoubleBuffer back = BufferUtils.createDoubleBuffer(16);
        GL40C.glGetUniformdv(program, u, back);
        assertEquals(value, back.get(0));
        GL40C.glGetUniformdv(program, m, back);
        assertEquals(value + 1.0, back.get(7));
        final int results = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, results);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, 16L, GL15C.GL_STATIC_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, results);
        GL43C.glDispatchCompute(1, 1, 1);
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL20C.glUseProgram(0);
        final ByteBuffer output = BufferUtils.createByteBuffer(16).order(ByteOrder.LITTLE_ENDIAN);
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, output);
        assertEquals(value, output.getDouble(0));
        assertEquals(value + 1.0, output.getDouble(8));
        GL15C.glDeleteBuffers(results);
        GL20C.glDeleteProgram(program);
    }
}
