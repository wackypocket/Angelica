package com.gtnewhorizons.angelica.glemu.compat;

import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.FloatBuffer;
import java.util.Arrays;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBImaging;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.gl;
import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.rb;

/**
 * The ARB_imaging tables and filters, as data applied in memory on the glDrawPixels path: nothing here reaches texture uploads or
 * readbacks, which GL also runs through them, because those go straight to the backend.
 */
public final class GlImaging {

    private static final class Table {
        int internal = GL11.GL_RGBA8;
        int width;
        float[] rgba = new float[0];
        final float[] scale = {1f, 1f, 1f, 1f}, bias = new float[4];
    }

    private static final class Filter {
        int internal = GL11.GL_RGBA8;
        int width, height;
        float[] rgba = new float[0];
        float[] row, column;
        int border = ARBImaging.GL_REDUCE;
        final float[] borderColor = new float[4];
        final float[] scale = {1f, 1f, 1f, 1f}, bias = new float[4];
    }

    /** What GL keeps per context. Like GlState, each thread has its own. */
    private static final class State {
        final Int2ObjectOpenHashMap<Table> tables = new Int2ObjectOpenHashMap<>();
        final Int2ObjectOpenHashMap<Filter> filters = new Int2ObjectOpenHashMap<>();
        boolean colorTable, postConvolutionTable, postMatrixTable, convolution1D, convolution2D, separable2D, histogram, minmax;
        int histogramWidth, histogramInternal = GL11.GL_RGBA8;
        boolean histogramSink, minmaxSink;
        int minmaxInternal = GL11.GL_RGBA8;
        long[] histogramCounts = new long[0];
        final float[] minimum = {1f, 1f, 1f, 1f}, maximum = new float[4];
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private GlImaging() {}

    /** Takes the capabilities that belong here, which the backends never hear of. */
    public static boolean setCapability(int cap, boolean on) {
        final State state = STATE.get();
        switch (cap) {
            case ARBImaging.GL_COLOR_TABLE -> state.colorTable = on;
            case ARBImaging.GL_POST_CONVOLUTION_COLOR_TABLE -> state.postConvolutionTable = on;
            case ARBImaging.GL_POST_COLOR_MATRIX_COLOR_TABLE -> state.postMatrixTable = on;
            case ARBImaging.GL_CONVOLUTION_1D -> state.convolution1D = on;
            case ARBImaging.GL_CONVOLUTION_2D -> state.convolution2D = on;
            case ARBImaging.GL_SEPARABLE_2D -> state.separable2D = on;
            case ARBImaging.GL_HISTOGRAM -> state.histogram = on;
            case ARBImaging.GL_MINMAX -> state.minmax = on;
            default -> {
                return false;
            }
        }
        return true;
    }

    public static boolean isEnabled(int cap) {
        final State state = STATE.get();
        return switch (cap) {
            case ARBImaging.GL_COLOR_TABLE -> state.colorTable;
            case ARBImaging.GL_POST_CONVOLUTION_COLOR_TABLE -> state.postConvolutionTable;
            case ARBImaging.GL_POST_COLOR_MATRIX_COLOR_TABLE -> state.postMatrixTable;
            case ARBImaging.GL_CONVOLUTION_1D -> state.convolution1D;
            case ARBImaging.GL_CONVOLUTION_2D -> state.convolution2D;
            case ARBImaging.GL_SEPARABLE_2D -> state.separable2D;
            case ARBImaging.GL_HISTOGRAM -> state.histogram;
            case ARBImaging.GL_MINMAX -> state.minmax;
            default -> false;
        };
    }

    static boolean active() {
        final State state = STATE.get();
        return state.colorTable || state.postConvolutionTable || state.postMatrixTable || state.convolution1D || state.convolution2D || state.separable2D || state.histogram || state.minmax;
    }

    /** Client pixels of any color format as RGBA floats, tightly packed, by the current unpack state. */
    static float[] readClient(long pixels, int width, int height, int format, int type) {
        final float[] out = new float[width * height * 4];
        if (isPackedType(type)) {
            gl().warnOnce("packed pixel type 0x" + Integer.toHexString(type) + " is not supported through the imaging stages");
            return out;
        }
        final int[] channels = channels(format);
        final int size = typeSize(type);
        final PixelConvert.Store unpack = gl().st().unpack;
        final long stride = PixelConvert.rowStride(width, format, type, unpack);
        final long bytes = (unpack.skipRows + height) * stride;
        GlEntryHelpers.withUnpacked(pixels, bytes, at -> {
            final long first = at + unpack.skipRows * stride + (long) unpack.skipPixels * channels.length * size;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final long texel = first + y * stride + (long) x * channels.length * size;
                    final int to = (y * width + x) * 4;
                    out[to + 3] = 1f;
                    if (format == GL11.GL_LUMINANCE || format == GL11.GL_LUMINANCE_ALPHA) out[to] = out[to + 1] = out[to + 2] = read(type, texel);
                    for (int c = 0; c < channels.length; c++) out[to + channels[c]] = read(type, texel + (long) c * size);
                }
            }
        });
        return out;
    }

    /** The RGBA channel each client component holds, in order. */
    private static int[] channels(int format) {
        return switch (format) {
            case GL11.GL_RED, GL11.GL_LUMINANCE -> new int[] {0};
            case GL11.GL_GREEN -> new int[] {1};
            case GL11.GL_BLUE -> new int[] {2};
            case GL11.GL_ALPHA -> new int[] {3};
            case GL11.GL_LUMINANCE_ALPHA -> new int[] {0, 3};
            case GL11.GL_RGB -> new int[] {0, 1, 2};
            case GL12.GL_BGR -> new int[] {2, 1, 0};
            case GL12.GL_BGRA -> new int[] {2, 1, 0, 3};
            default -> new int[] {0, 1, 2, 3};
        };
    }

    private static boolean isPackedType(int type) {
        return switch (type) {
            case GL11.GL_BYTE, GL11.GL_UNSIGNED_BYTE, GL11.GL_SHORT, GL11.GL_UNSIGNED_SHORT, GL11.GL_INT, GL11.GL_UNSIGNED_INT, GL11.GL_FLOAT, GL30.GL_HALF_FLOAT -> false;
            default -> true;
        };
    }

    private static int typeSize(int type) {
        return switch (type) {
            case GL11.GL_BYTE, GL11.GL_UNSIGNED_BYTE -> 1;
            case GL11.GL_SHORT, GL11.GL_UNSIGNED_SHORT, GL30.GL_HALF_FLOAT -> 2;
            default -> 4;
        };
    }

    private static float read(int type, long at) {
        return switch (type) {
            case GL11.GL_UNSIGNED_BYTE -> (MemoryUtil.memGetByte(at) & 0xFF) / 255f;
            case GL11.GL_BYTE -> MemoryUtil.memGetByte(at) / 127f;
            case GL11.GL_UNSIGNED_SHORT -> (MemoryUtil.memGetShort(at) & 0xFFFF) / 65535f;
            case GL11.GL_SHORT -> MemoryUtil.memGetShort(at) / 32767f;
            case GL11.GL_UNSIGNED_INT -> (MemoryUtil.memGetInt(at) & 0xFFFFFFFFL) / 4294967295f;
            case GL11.GL_INT -> MemoryUtil.memGetInt(at) / 2147483647f;
            case GL30.GL_HALF_FLOAT -> Float.float16ToFloat(MemoryUtil.memGetShort(at));
            default -> MemoryUtil.memGetFloat(at);
        };
    }

    private static void write(int type, long at, float v) {
        final float clamped = Math.max(0f, Math.min(1f, v));
        switch (type) {
            case GL11.GL_UNSIGNED_BYTE -> MemoryUtil.memPutByte(at, (byte) Math.round(clamped * 255f));
            case GL11.GL_BYTE -> MemoryUtil.memPutByte(at, (byte) Math.round(v * 127f));
            case GL11.GL_UNSIGNED_SHORT -> MemoryUtil.memPutShort(at, (short) Math.round(clamped * 65535f));
            case GL11.GL_SHORT -> MemoryUtil.memPutShort(at, (short) Math.round(v * 32767f));
            case GL11.GL_UNSIGNED_INT -> MemoryUtil.memPutInt(at, (int) (long) (clamped * 4294967295.0));
            case GL11.GL_INT -> MemoryUtil.memPutInt(at, (int) (v * 2147483647.0));
            case GL30.GL_HALF_FLOAT -> MemoryUtil.memPutShort(at, Float.floatToFloat16(v));
            default -> MemoryUtil.memPutFloat(at, v);
        }
    }

    /** RGBA floats out to client memory in a format and type, tightly packed but for the pack alignment. */
    private static void writeClient(float[] rgba, int width, int height, int format, int type, long out) {
        if (isPackedType(type)) {
            gl().warnOnce("packed pixel type 0x" + Integer.toHexString(type) + " is not supported through the imaging stages");
            return;
        }
        final int[] channels = channels(format);
        final int size = typeSize(type);
        final long stride = packStride(width, channels.length, size);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int src = (y * width + x) * 4;
                for (int c = 0; c < channels.length; c++) write(type, out + y * stride + (long) (x * channels.length + c) * size, rgba[src + channels[c]]);
            }
        }
    }

    private static long packStride(int width, int components, int size) {
        final int alignment = Math.max(1, gl().st().pack.alignment);
        return ((long) width * components * size + alignment - 1) / alignment * alignment;
    }

    private static Table table(int target) {
        final State state = STATE.get();
        return state.tables.computeIfAbsent(target, k -> new Table());
    }

    private static Filter filter(int target) {
        final State state = STATE.get();
        return state.filters.computeIfAbsent(target, k -> new Filter());
    }

    static void colorTable(int target, int internal, int width, int format, int type, long data) {
        final Table t = table(target);
        t.internal = internal;
        t.width = width;
        t.rgba = data == 0L ? new float[width * 4] : scaled(readClient(data, width, 1, format, type), t.scale, t.bias);
    }

    static void colorSubTable(int target, int start, int count, int format, int type, long data) {
        final Table t = table(target);
        final float[] rows = scaled(readClient(data, count, 1, format, type), t.scale, t.bias);
        final int copied = Math.min(count, t.width - start);
        if (copied > 0) System.arraycopy(rows, 0, t.rgba, start * 4, copied * 4);
    }

    private static float[] framebuffer(int x, int y, int width, int height) {
        final FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height * 4);
        rb().readPixels(x, y, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        final float[] out = new float[width * height * 4];
        pixels.get(0, out);
        return out;
    }

    static void copyColorTable(int target, int internal, int x, int y, int width) {
        final Table t = table(target);
        t.internal = internal;
        t.width = width;
        t.rgba = scaled(framebuffer(x, y, width, 1), t.scale, t.bias);
    }

    static void copyColorSubTable(int target, int start, int x, int y, int width) {
        final Table t = table(target);
        final float[] rows = scaled(framebuffer(x, y, width, 1), t.scale, t.bias);
        final int copied = Math.min(width, t.width - start);
        if (copied > 0) System.arraycopy(rows, 0, t.rgba, start * 4, copied * 4);
    }

    static void getColorTable(int target, int format, int type, long out) {
        final Table t = table(target);
        writeClient(t.rgba, t.width, 1, format, type, out);
    }

    static void colorTableParameter(int target, int pname, long values, boolean floats) {
        final Table t = table(target);
        final float[] into = pname == ARBImaging.GL_COLOR_TABLE_SCALE ? t.scale : pname == ARBImaging.GL_COLOR_TABLE_BIAS ? t.bias : null;
        if (into != null) readFour(values, floats, into);
    }

    static void getColorTableParameter(int target, int pname, long out, boolean floats) {
        final Table t = table(target);
        switch (pname) {
            case ARBImaging.GL_COLOR_TABLE_SCALE -> putFour(t.scale, out, floats);
            case ARBImaging.GL_COLOR_TABLE_BIAS -> putFour(t.bias, out, floats);
            case ARBImaging.GL_COLOR_TABLE_FORMAT -> putOne(t.internal, out, floats);
            case ARBImaging.GL_COLOR_TABLE_WIDTH -> putOne(t.width, out, floats);
            case ARBImaging.GL_COLOR_TABLE_RED_SIZE, ARBImaging.GL_COLOR_TABLE_GREEN_SIZE, ARBImaging.GL_COLOR_TABLE_BLUE_SIZE,
                 ARBImaging.GL_COLOR_TABLE_ALPHA_SIZE -> putOne(8, out, floats);
            default -> putOne(0, out, floats);
        }
    }

    private static void readFour(long values, boolean floats, float[] into) {
        for (int i = 0; i < 4; i++) into[i] = floats ? MemoryUtil.memGetFloat(values + i * 4L) : MemoryUtil.memGetInt(values + i * 4L);
    }

    private static void putFour(float[] from, long out, boolean floats) {
        for (int i = 0; i < 4; i++) putOne(from[i], out + i * 4L, floats);
    }

    private static void putOne(float value, long out, boolean floats) {
        if (floats) MemoryUtil.memPutFloat(out, value);
        else MemoryUtil.memPutInt(out, (int) value);
    }

    private static float[] scaled(float[] rgba, float[] scale, float[] bias) {
        for (int i = 0; i < rgba.length; i++) rgba[i] = rgba[i] * scale[i % 4] + bias[i % 4];
        return rgba;
    }

    static void convolutionFilter(int target, int internal, int width, int height, int format, int type, long image) {
        final Filter f = filter(target);
        f.internal = internal;
        f.width = width;
        f.height = height;
        f.rgba = scaled(readClient(image, width, height, format, type), f.scale, f.bias);
        f.row = f.column = null;
    }

    static void separableFilter(int target, int internal, int width, int height, int format, int type, long row, long column) {
        final Filter f = filter(target);
        f.internal = internal;
        f.width = width;
        f.height = height;
        f.row = scaled(readClient(row, width, 1, format, type), f.scale, f.bias);
        f.column = scaled(readClient(column, height, 1, format, type), f.scale, f.bias);
        f.rgba = new float[0];
    }

    static void copyConvolutionFilter(int target, int internal, int x, int y, int width, int height) {
        final Filter f = filter(target);
        f.internal = internal;
        f.width = width;
        f.height = height;
        f.rgba = scaled(framebuffer(x, y, width, height), f.scale, f.bias);
        f.row = f.column = null;
    }

    static void getConvolutionFilter(int target, int format, int type, long out) {
        final Filter f = filter(target);
        writeClient(f.rgba, f.width, f.height, format, type, out);
    }

    static void getSeparableFilter(int target, int format, int type, long row, long column) {
        final Filter f = filter(target);
        if (f.row != null) writeClient(f.row, f.width, 1, format, type, row);
        if (f.column != null) writeClient(f.column, f.height, 1, format, type, column);
    }

    static void convolutionParameter(int target, int pname, float value) {
        if (pname == ARBImaging.GL_CONVOLUTION_BORDER_MODE) filter(target).border = (int) value;
    }

    static void convolutionParameter(int target, int pname, long values, boolean floats) {
        final Filter f = filter(target);
        switch (pname) {
            case ARBImaging.GL_CONVOLUTION_BORDER_MODE -> f.border = floats ? (int) MemoryUtil.memGetFloat(values) : MemoryUtil.memGetInt(values);
            case ARBImaging.GL_CONVOLUTION_BORDER_COLOR -> readFour(values, floats, f.borderColor);
            case ARBImaging.GL_CONVOLUTION_FILTER_SCALE -> readFour(values, floats, f.scale);
            case ARBImaging.GL_CONVOLUTION_FILTER_BIAS -> readFour(values, floats, f.bias);
            default -> { }
        }
    }

    static void getConvolutionParameter(int target, int pname, long out, boolean floats) {
        final Filter f = filter(target);
        switch (pname) {
            case ARBImaging.GL_CONVOLUTION_BORDER_MODE -> putOne(f.border, out, floats);
            case ARBImaging.GL_CONVOLUTION_BORDER_COLOR -> putFour(f.borderColor, out, floats);
            case ARBImaging.GL_CONVOLUTION_FILTER_SCALE -> putFour(f.scale, out, floats);
            case ARBImaging.GL_CONVOLUTION_FILTER_BIAS -> putFour(f.bias, out, floats);
            case ARBImaging.GL_CONVOLUTION_FORMAT -> putOne(f.internal, out, floats);
            case ARBImaging.GL_CONVOLUTION_WIDTH -> putOne(f.width, out, floats);
            case ARBImaging.GL_CONVOLUTION_HEIGHT -> putOne(f.height, out, floats);
            default -> putOne(0, out, floats);
        }
    }

    static void histogram(int width, int internal, boolean sink) {
        final State state = STATE.get();
        state.histogramWidth = width;
        state.histogramInternal = internal;
        state.histogramSink = sink;
        state.histogramCounts = new long[width * 4];
    }

    static void resetHistogram() {
        final State state = STATE.get();
        state.histogramCounts = new long[state.histogramWidth * 4];
    }

    static void getHistogram(boolean reset, int format, int type, long out) {
        final State state = STATE.get();
        if (isPackedType(type)) {
            gl().warnOnce("packed pixel type 0x" + Integer.toHexString(type) + " is not supported through the imaging stages");
            return;
        }
        final int[] channels = channels(format);
        final int size = typeSize(type);
        for (int i = 0; i < state.histogramWidth; i++) {
            for (int c = 0; c < channels.length; c++) putCount(type, out + (long) (i * channels.length + c) * size, state.histogramCounts[i * 4 + channels[c]]);
        }
        if (reset) resetHistogram();
    }

    /** Counts are not colors: integer types carry them as they are, saturating at what the type holds. */
    private static void putCount(int type, long at, long count) {
        switch (type) {
            case GL11.GL_UNSIGNED_BYTE -> MemoryUtil.memPutByte(at, (byte) Math.min(count, 0xFF));
            case GL11.GL_BYTE -> MemoryUtil.memPutByte(at, (byte) Math.min(count, Byte.MAX_VALUE));
            case GL11.GL_UNSIGNED_SHORT -> MemoryUtil.memPutShort(at, (short) Math.min(count, 0xFFFF));
            case GL11.GL_SHORT -> MemoryUtil.memPutShort(at, (short) Math.min(count, Short.MAX_VALUE));
            case GL11.GL_FLOAT -> MemoryUtil.memPutFloat(at, count);
            case GL30.GL_HALF_FLOAT -> MemoryUtil.memPutShort(at, Float.floatToFloat16(count));
            default -> MemoryUtil.memPutInt(at, (int) Math.min(count, Integer.MAX_VALUE));
        }
    }

    static void getHistogramParameter(int pname, long out, boolean floats) {
        final State state = STATE.get();
        switch (pname) {
            case ARBImaging.GL_HISTOGRAM_WIDTH -> putOne(state.histogramWidth, out, floats);
            case ARBImaging.GL_HISTOGRAM_FORMAT -> putOne(state.histogramInternal, out, floats);
            case ARBImaging.GL_HISTOGRAM_SINK -> putOne(state.histogramSink ? 1 : 0, out, floats);
            case ARBImaging.GL_HISTOGRAM_RED_SIZE, ARBImaging.GL_HISTOGRAM_GREEN_SIZE, ARBImaging.GL_HISTOGRAM_BLUE_SIZE, ARBImaging.GL_HISTOGRAM_ALPHA_SIZE -> putOne(32, out, floats);
            default -> putOne(0, out, floats);
        }
    }

    static void minmax(int internal, boolean sink) {
        final State state = STATE.get();
        state.minmaxInternal = internal;
        state.minmaxSink = sink;
        resetMinmax();
    }

    static void resetMinmax() {
        final State state = STATE.get();
        Arrays.fill(state.minimum, 1f);
        Arrays.fill(state.maximum, 0f);
    }

    static void getMinmax(boolean reset, int format, int type, long out) {
        final State state = STATE.get();
        final float[] both = new float[8];
        System.arraycopy(state.minimum, 0, both, 0, 4);
        System.arraycopy(state.maximum, 0, both, 4, 4);
        writeClient(both, 2, 1, format, type, out);
        if (reset) resetMinmax();
    }

    static void getMinmaxParameter(int pname, long out, boolean floats) {
        final State state = STATE.get();
        switch (pname) {
            case ARBImaging.GL_MINMAX_FORMAT -> putOne(state.minmaxInternal, out, floats);
            case ARBImaging.GL_MINMAX_SINK -> putOne(state.minmaxSink ? 1 : 0, out, floats);
            default -> putOne(0, out, floats);
        }
    }

    /** An image after the imaging stages: its pixels, which convolution may leave smaller, and whether it goes on to the framebuffer. */
    record Result(float[] rgba, int width, int height, boolean sunk) {}

    static Result apply(float[] rgba, int width, int height) {
        final State state = STATE.get();
        float[] pixels = rgba;
        int w = width, h = height;
        if (state.colorTable) lookup(pixels, ARBImaging.GL_COLOR_TABLE);
        if (state.convolution2D || state.separable2D || state.convolution1D) {
            final int target = state.convolution2D ? ARBImaging.GL_CONVOLUTION_2D : state.separable2D ? ARBImaging.GL_SEPARABLE_2D : ARBImaging.GL_CONVOLUTION_1D;
            final Filter f = filter(target);
            if (f.width > 0) {
                final int filterHeight = target == ARBImaging.GL_CONVOLUTION_1D ? 1 : f.height;
                final Result convolved = convolve(pixels, w, h, f, filterHeight);
                pixels = convolved.rgba();
                w = convolved.width();
                h = convolved.height();
            }
        }
        if (state.postConvolutionTable) lookup(pixels, ARBImaging.GL_POST_CONVOLUTION_COLOR_TABLE);
        if (state.postMatrixTable) lookup(pixels, ARBImaging.GL_POST_COLOR_MATRIX_COLOR_TABLE);
        boolean sunk = false;
        if (state.histogram && state.histogramWidth > 0) {
            for (int i = 0; i < w * h * 4; i++) {
                final float v = Math.max(0f, Math.min(1f, pixels[i]));
                state.histogramCounts[Math.round(v * (state.histogramWidth - 1)) * 4 + i % 4]++;
            }
            sunk = state.histogramSink;
        }
        if (state.minmax) {
            for (int i = 0; i < w * h * 4; i++) {
                state.minimum[i % 4] = Math.min(state.minimum[i % 4], pixels[i]);
                state.maximum[i % 4] = Math.max(state.maximum[i % 4], pixels[i]);
            }
            sunk |= state.minmaxSink;
        }
        return new Result(pixels, w, h, sunk);
    }

    private static void lookup(float[] pixels, int target) {
        final Table t = table(target);
        if (t.width == 0) return;
        for (int i = 0; i < pixels.length; i++) {
            final int entry = Math.round(Math.max(0f, Math.min(1f, pixels[i])) * (t.width - 1));
            pixels[i] = t.rgba[entry * 4 + i % 4];
        }
    }

    private static Result convolve(float[] in, int width, int height, Filter f, int filterHeight) {
        final boolean reduce = f.border == ARBImaging.GL_REDUCE;
        final int outW = reduce ? Math.max(0, width - f.width + 1) : width, outH = reduce ? Math.max(0, height - filterHeight + 1) : height;
        final float[] out = new float[outW * outH * 4];
        final int cx = reduce ? 0 : f.width / 2, cy = reduce ? 0 : filterHeight / 2;
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                for (int c = 0; c < 4; c++) {
                    float sum = 0f;
                    for (int j = 0; j < filterHeight; j++) {
                        for (int i = 0; i < f.width; i++) {
                            final float weight = f.row != null ? f.row[i * 4 + c] * f.column[j * 4 + c] : f.rgba[(j * f.width + i) * 4 + c];
                            sum += weight * sample(in, width, height, x + i - cx, y + j - cy, c, f);
                        }
                    }
                    out[(y * outW + x) * 4 + c] = sum;
                }
            }
        }
        return new Result(out, outW, outH, false);
    }

    private static float sample(float[] in, int width, int height, int x, int y, int c, Filter f) {
        if (x >= 0 && y >= 0 && x < width && y < height) return in[(y * width + x) * 4 + c];
        if (f.border == ARBImaging.GL_REPLICATE_BORDER) {
            return in[(Math.max(0, Math.min(height - 1, y)) * width + Math.max(0, Math.min(width - 1, x))) * 4 + c];
        }
        return f.borderColor[c];
    }
}
