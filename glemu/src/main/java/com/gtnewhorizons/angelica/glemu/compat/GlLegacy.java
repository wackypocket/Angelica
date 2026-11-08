package com.gtnewhorizons.angelica.glemu.compat;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;
import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTexEnvGen;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.recording.CommandRecorder;
import com.gtnewhorizons.angelica.glsm.recording.commands.DisplayListCommand;
import com.gtnewhorizons.angelica.glsm.states.ViewportState;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.LongConsumer;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.gl;
import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.rb;

/**
 * The legacy GL with no home in GLSM or the backends, drawn through what they do offer: images become a textured quad in window
 * coordinates, so depth test, blending and the rest of the fragment pipeline still apply as they do to glDrawPixels.
 */
public final class GlLegacy {

    private static final float[] WHITE = {1f, 1f, 1f, 1f};
    private static int scratchTexture, scratchFramebuffer;
    private static int depthProgram, stencilProgram;

    private static final String WINDOW_VERTEX = """
        #version 330 core
        uniform vec4 rect;
        out vec2 uv;
        void main() {
            uv = vec2(gl_VertexID & 1, gl_VertexID >> 1);
            gl_Position = vec4(mix(rect.xy, rect.zw, uv), 0.0, 1.0);
        }
        """;
    private static final String DEPTH_FRAGMENT = """
        #version 330 core
        uniform sampler2D image;
        uniform vec4 color;
        in vec2 uv;
        out vec4 fragColor;
        void main() {
            gl_FragDepth = texture(image, uv).r;
            fragColor = color;
        }
        """;

    private static final String STENCIL_FRAGMENT = """
        #version 330 core
        uniform sampler2D image;
        uniform float value;
        in vec2 uv;
        out vec4 fragColor;
        void main() {
            if (abs(texelFetch(image, ivec2(uv * vec2(textureSize(image, 0))), 0).r * 255.0 - value) > 0.5) discard;
            fragColor = vec4(0.0);
        }
        """;

    private static final int PIXEL_MAPS = 10;
    /** GL_CURRENT_RASTER_SECONDARY_COLOR, which LWJGL names nowhere. */
    private static final int CURRENT_RASTER_SECONDARY_COLOR = 0x845F;

    /** What GL keeps per context. Like GlState, each thread has its own. */
    private static final class State {
        float rasterX, rasterY, rasterZ;
        boolean rasterValid = true;
        final float[] rasterColor = {1f, 1f, 1f, 1f};
        float zoomX = 1f, zoomY = 1f;
        final float[] pixelScale = {1f, 1f, 1f, 1f};
        final float[] pixelBias = new float[4];
        final float[] accumClear = new float[4];
        float[] accumulated;
        int accumWidth, accumHeight;
        final byte[] stipple = new byte[128];
        int savedUnit, savedTexture;
        final float[][] pixelMaps = new float[PIXEL_MAPS][];
        boolean mapColor, mapStencil;
        int indexShift, indexOffset;
        float depthScale = 1f, depthBias;
        float fogCoord;

        State() {
            for (int i = 0; i < PIXEL_MAPS; i++) pixelMaps[i] = new float[1];
        }
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private GlLegacy() {}

    /** The current fog coordinate. Fog here comes from the fragment's depth, but GL still keeps the value to be queried. */
    public static void fogCoord(float value) {
        compiled(() -> STATE.get().fogCoord = value);
    }

    /** Whether a query answers a colour, which an integer query scales to the whole range rather than rounds. */
    public static boolean colorQuery(int pname) {
        return pname == GL11.GL_CURRENT_RASTER_COLOR || pname == CURRENT_RASTER_SECONDARY_COLOR || pname == GL11.GL_ACCUM_CLEAR_VALUE;
    }

    /**
     * The legacy state a compatibility profile can be asked for: how many values went into {@code out}, or 0 for a name that is
     * not one of these. This is a context of one RGBA visual, with no colour index, auxiliary buffer or index bits.
     */
    public static int query(int pname, double[] out) {
        final State state = STATE.get();
        final int grid = GlEvaluators.grid(pname, out);
        if (grid != 0) return grid;
        if (pname >= GL11.GL_PIXEL_MAP_I_TO_I_SIZE && pname < GL11.GL_PIXEL_MAP_I_TO_I_SIZE + PIXEL_MAPS) {
            out[0] = state.pixelMaps[pname - GL11.GL_PIXEL_MAP_I_TO_I_SIZE].length;
            return 1;
        }
        switch (pname) {
            case GL11.GL_CURRENT_RASTER_POSITION -> { out[0] = state.rasterX; out[1] = state.rasterY; out[2] = state.rasterZ; out[3] = 1; return 4; }
            case GL11.GL_CURRENT_RASTER_POSITION_VALID -> { out[0] = state.rasterValid ? 1 : 0; return 1; }
            case GL11.GL_CURRENT_RASTER_COLOR -> { for (int i = 0; i < 4; i++) out[i] = state.rasterColor[i]; return 4; }
            case CURRENT_RASTER_SECONDARY_COLOR -> { out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1; return 4; }
            case GL11.GL_CURRENT_RASTER_TEXTURE_COORDS -> { out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1; return 4; }
            case GL11.GL_CURRENT_RASTER_DISTANCE, GL11.GL_INDEX_CLEAR_VALUE, GL11.GL_AUX_BUFFERS, GL11.GL_INDEX_MODE, GL11.GL_INDEX_BITS -> { out[0] = 0; return 1; }
            case GL11.GL_CURRENT_INDEX, GL11.GL_CURRENT_RASTER_INDEX, GL11.GL_RGBA_MODE -> { out[0] = 1; return 1; }
            case GL11.GL_INDEX_WRITEMASK -> { out[0] = -1; return 1; }
            case GL11.GL_ACCUM_CLEAR_VALUE -> { for (int i = 0; i < 4; i++) out[i] = state.accumClear[i]; return 4; }
            // Accumulation is kept in floats, so any precision a mod asks about is there.
            case GL11.GL_ACCUM_RED_BITS, GL11.GL_ACCUM_GREEN_BITS, GL11.GL_ACCUM_BLUE_BITS, GL11.GL_ACCUM_ALPHA_BITS -> { out[0] = 16; return 1; }
            case GL11.GL_MAX_LIST_NESTING -> { out[0] = 64; return 1; }
            case GL11.GL_MAX_EVAL_ORDER -> { out[0] = 30; return 1; }
            case GL11.GL_MAX_PIXEL_MAP_TABLE -> { out[0] = 65536; return 1; }
            case GL11.GL_MAX_NAME_STACK_DEPTH -> { out[0] = com.gtnewhorizons.angelica.glsm.FeedbackManager.maxNameStackDepth(); return 1; }
            case GL11.GL_NAME_STACK_DEPTH -> { out[0] = com.gtnewhorizons.angelica.glsm.FeedbackManager.nameStackDepth(); return 1; }
            case GL11.GL_FEEDBACK_BUFFER_SIZE -> { out[0] = com.gtnewhorizons.angelica.glsm.FeedbackManager.feedbackBufferSize(); return 1; }
            case GL11.GL_FEEDBACK_BUFFER_TYPE -> { out[0] = com.gtnewhorizons.angelica.glsm.FeedbackManager.feedbackBufferType(); return 1; }
            case GL11.GL_SELECTION_BUFFER_SIZE -> { out[0] = com.gtnewhorizons.angelica.glsm.FeedbackManager.selectionBufferSize(); return 1; }
            case GL11.GL_MAP_COLOR -> { out[0] = state.mapColor ? 1 : 0; return 1; }
            case GL11.GL_MAP_STENCIL -> { out[0] = state.mapStencil ? 1 : 0; return 1; }
            case GL11.GL_INDEX_SHIFT -> { out[0] = state.indexShift; return 1; }
            case GL11.GL_INDEX_OFFSET -> { out[0] = state.indexOffset; return 1; }
            case GL11.GL_ZOOM_X -> { out[0] = state.zoomX; return 1; }
            case GL11.GL_ZOOM_Y -> { out[0] = state.zoomY; return 1; }
            case GL11.GL_RED_SCALE -> { out[0] = state.pixelScale[0]; return 1; }
            case GL11.GL_GREEN_SCALE -> { out[0] = state.pixelScale[1]; return 1; }
            case GL11.GL_BLUE_SCALE -> { out[0] = state.pixelScale[2]; return 1; }
            case GL11.GL_ALPHA_SCALE -> { out[0] = state.pixelScale[3]; return 1; }
            case GL11.GL_RED_BIAS -> { out[0] = state.pixelBias[0]; return 1; }
            case GL11.GL_GREEN_BIAS -> { out[0] = state.pixelBias[1]; return 1; }
            case GL11.GL_BLUE_BIAS -> { out[0] = state.pixelBias[2]; return 1; }
            case GL11.GL_ALPHA_BIAS -> { out[0] = state.pixelBias[3]; return 1; }
            case GL11.GL_DEPTH_SCALE -> { out[0] = state.depthScale; return 1; }
            case GL11.GL_DEPTH_BIAS -> { out[0] = state.depthBias; return 1; }
            case GL14.GL_CURRENT_FOG_COORDINATE -> { out[0] = state.fogCoord; return 1; }
            default -> { return 0; }
        }
    }

    /** Transforms through the current matrices to window coordinates, and is invalid outside the view volume, as in GL. */
    public static void rasterPos(float x, float y, float z, float w) {
        compiled(() -> setRasterPos(x, y, z, w));
    }

    private static void setRasterPos(float x, float y, float z, float w) {
        final State state = STATE.get();
        final Vector4f v = new Vector4f(x, y, z, w);
        GLSMMatrices.getModelViewMatrix().transform(v);
        GLSMMatrices.getProjectionMatrix().transform(v);
        state.rasterValid = Math.abs(v.x) <= v.w && Math.abs(v.y) <= v.w && Math.abs(v.z) <= v.w;
        final ViewportState vp = GLSMRasterizer.getViewportState();
        state.rasterX = vp.x + vp.width * (v.x / v.w + 1f) * 0.5f;
        state.rasterY = vp.y + vp.height * (v.y / v.w + 1f) * 0.5f;
        state.rasterZ = (v.z / v.w + 1f) * 0.5f;
        captureColor();
    }

    static void windowPos(float x, float y, float z) {
        final State state = STATE.get();
        compiled(() -> {
            state.rasterX = x;
            state.rasterY = y;
            state.rasterZ = z;
            state.rasterValid = true;
            captureColor();
        });
    }

    private static void captureColor() {
        final State state = STATE.get();
        final var color = GLSMCurrentAttribs.getColor();
        state.rasterColor[0] = color.getRed();
        state.rasterColor[1] = color.getGreen();
        state.rasterColor[2] = color.getBlue();
        state.rasterColor[3] = color.getAlpha();
    }

    static void pixelZoom(float x, float y) {
        final State state = STATE.get();
        compiled(() -> {
            state.zoomX = x;
            state.zoomY = y;
        });
    }

    /** Runs a call now, or as a display list being recorded says, as these calls have nowhere else to go in a list. */
    private static void compiled(DisplayListCommand call) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode == RecordMode.NONE) {
            call.execute();
            return;
        }
        DisplayListManager.recordComplexCommand(call);
        if (mode != RecordMode.COMPILE) {
            final CommandRecorder recorder = DisplayListManager.pauseRecording();
            call.execute();
            DisplayListManager.resumeRecording(recorder);
        }
    }

    /**
     * A call that takes client memory. GL reads the pixels when a list is compiled, and a bound unpack buffer makes the pointer an offset,
     * so either way the pixels are copied here and the call sees them with the unpack state they came under and no buffer.
     */
    private static void imageCall(long pixels, long bytes, LongConsumer draw) {
        final GlState st = gl().st();
        final boolean recording = DisplayListManager.getRecordMode() != RecordMode.NONE;
        if (!recording && st.pixelUnpack == 0) {
            if (pixels != 0L) draw.accept(pixels);
            return;
        }
        final ByteBuffer copy = BufferUtils.createByteBuffer((int) bytes);
        GlEntryHelpers.withUnpacked(pixels, bytes, at -> MemoryUtil.memCopy(at, MemoryUtil.memAddress(copy), bytes));
        final PixelConvert.Store store = new PixelConvert.Store();
        store.copyFrom(st.unpack);
        compiled(() -> withUnpackState(store, () -> draw.accept(MemoryUtil.memAddress(copy))));
    }

    private static void withUnpackState(PixelConvert.Store store, Runnable call) {
        final GlState st = gl().st();
        final PixelConvert.Store saved = new PixelConvert.Store();
        saved.copyFrom(st.unpack);
        final int buffer = st.pixelUnpack;
        st.unpack.copyFrom(store);
        st.pixelUnpack = 0;
        try {
            call.run();
        } finally {
            st.pixelUnpack = buffer;
            st.unpack.copyFrom(saved);
        }
    }

    public static void pixelTransfer(int pname, float value) {
        compiled(() -> setPixelTransfer(pname, value));
    }

    private static void setPixelTransfer(int pname, float value) {
        final State state = STATE.get();
        switch (pname) {
            case GL11.GL_RED_SCALE -> state.pixelScale[0] = value;
            case GL11.GL_GREEN_SCALE -> state.pixelScale[1] = value;
            case GL11.GL_BLUE_SCALE -> state.pixelScale[2] = value;
            case GL11.GL_ALPHA_SCALE -> state.pixelScale[3] = value;
            case GL11.GL_RED_BIAS -> state.pixelBias[0] = value;
            case GL11.GL_GREEN_BIAS -> state.pixelBias[1] = value;
            case GL11.GL_BLUE_BIAS -> state.pixelBias[2] = value;
            case GL11.GL_ALPHA_BIAS -> state.pixelBias[3] = value;
            case GL11.GL_MAP_COLOR -> state.mapColor = value != 0f;
            case GL11.GL_MAP_STENCIL -> state.mapStencil = value != 0f;
            case GL11.GL_INDEX_SHIFT -> state.indexShift = (int) value;
            case GL11.GL_INDEX_OFFSET -> state.indexOffset = (int) value;
            case GL11.GL_DEPTH_SCALE -> state.depthScale = value;
            case GL11.GL_DEPTH_BIAS -> state.depthBias = value;
            default -> { }
        }
    }

    /** kind: 0 float, 1 unsigned int, 2 unsigned short. The two indexed maps hold raw indices, the rest fractions of the type's range. */
    static void pixelMap(int map, int size, long values, int kind) {
        final State state = STATE.get();
        final int index = map - GL11.GL_PIXEL_MAP_I_TO_I;
        if (index < 0 || index >= PIXEL_MAPS || size < 1 || values == 0L) return;
        final boolean indices = map == GL11.GL_PIXEL_MAP_I_TO_I || map == GL11.GL_PIXEL_MAP_S_TO_S;
        final float[] table = new float[size];
        for (int i = 0; i < size; i++) {
            table[i] = switch (kind) {
                case 0 -> MemoryUtil.memGetFloat(values + i * 4L);
                case 1 -> (MemoryUtil.memGetInt(values + i * 4L) & 0xFFFFFFFFL) / (indices ? 1f : 4294967295f);
                default -> (MemoryUtil.memGetShort(values + i * 2L) & 0xFFFF) / (indices ? 1f : 65535f);
            };
        }
        compiled(() -> state.pixelMaps[index] = table);
    }

    static void getPixelMap(int map, long values, int kind) {
        final State state = STATE.get();
        final int index = map - GL11.GL_PIXEL_MAP_I_TO_I;
        if (index < 0 || index >= PIXEL_MAPS || values == 0L) return;
        final boolean indices = map == GL11.GL_PIXEL_MAP_I_TO_I || map == GL11.GL_PIXEL_MAP_S_TO_S;
        final float[] table = state.pixelMaps[index];
        for (int i = 0; i < table.length; i++) {
            switch (kind) {
                case 0 -> MemoryUtil.memPutFloat(values + i * 4L, table[i]);
                case 1 -> MemoryUtil.memPutInt(values + i * 4L, (int) (long) (table[i] * (indices ? 1.0 : 4294967295.0)));
                default -> MemoryUtil.memPutShort(values + i * 2L, (short) (int) (table[i] * (indices ? 1f : 65535f)));
            }
        }
    }

    public static void polygonStipple(long pattern) {
        gl().warnOnce("the polygon stipple pattern is stored but never rasterized");
        final State state = STATE.get();
        imageCall(pattern, state.stipple.length, at -> MemoryUtil.memByteBuffer(at, state.stipple.length).get(state.stipple));
    }

    static void getPolygonStipple(long out) {
        final State state = STATE.get();
        MemoryUtil.memByteBuffer(out, state.stipple.length).put(state.stipple);
    }

    static void drawPixels(int width, int height, int format, int type, long pixels) {
        if (width <= 0 || height <= 0) return;
        final long bytes = PixelConvert.clientBytes(width, height, 1, format, type, gl().st().unpack);
        imageCall(pixels, bytes, at -> drawClientPixels(width, height, format, type, at));
    }

    private static void drawClientPixels(int width, int height, int format, int type, long pixels) {
        final State state = STATE.get();
        if (!state.rasterValid) return;
        if (format == GL11.GL_DEPTH_COMPONENT) {
            drawDepth(width, height, type, pixels);
            return;
        }
        if (format == GL11.GL_STENCIL_INDEX || format == GL30.GL_DEPTH_STENCIL) {
            drawStencil(width, height, format, type, pixels);
            return;
        }
        scratchTexture();
        if (needsPixelTransfer()) {
            final GlImaging.Result image = transferred(width, height, format, type, pixels);
            if (image.sunk() || image.width() == 0 || image.height() == 0) {
                restoreTexture();
                return;
            }
            final FloatBuffer colors = BufferUtils.createFloatBuffer(image.rgba().length).put(0, image.rgba());
            uploadOwn(GL30.GL_RGBA32F, image.width(), image.height(), GL11.GL_RGBA, GL11.GL_FLOAT, MemoryUtil.memAddress(colors));
            drawWindowQuad(state.rasterX, state.rasterY, state.rasterX + image.width() * state.zoomX, state.rasterY + image.height() * state.zoomY, state.rasterZ, WHITE, false);
            return;
        }
        rb().texImage2D(GL11.GL_TEXTURE_2D, 0, floatingType(type) ? GL30.GL_RGBA32F : GL11.GL_RGBA8, width, height, 0, format, type, pixels);
        drawWindowQuad(state.rasterX, state.rasterY, state.rasterX + width * state.zoomX, state.rasterY + height * state.zoomY, state.rasterZ, state.pixelScale, false);
    }

    private static boolean needsPixelTransfer() {
        final State state = STATE.get();
        return state.mapColor || state.pixelBias[0] != 0f || state.pixelBias[1] != 0f || state.pixelBias[2] != 0f || state.pixelBias[3] != 0f || GlImaging.active();
    }

    /** Scale and bias, the color maps, then the imaging stages, in the order the GL pixel path applies them. */
    private static GlImaging.Result transferred(int width, int height, int format, int type, long pixels) {
        final State state = STATE.get();
        final float[] rgba = GlImaging.readClient(pixels, width, height, format, type);
        for (int i = 0; i < rgba.length; i++) {
            final int c = i % 4;
            float v = Math.max(0f, Math.min(1f, rgba[i] * state.pixelScale[c] + state.pixelBias[c]));
            if (state.mapColor) {
                final float[] table = state.pixelMaps[GL11.GL_PIXEL_MAP_R_TO_R - GL11.GL_PIXEL_MAP_I_TO_I + c];
                v = table[Math.round(v * (table.length - 1))];
            }
            rgba[i] = v;
        }
        return GlImaging.apply(rgba, width, height);
    }

    private static boolean floatingType(int type) {
        return type == GL11.GL_FLOAT || type == GL30.GL_HALF_FLOAT;
    }

    /** Stencil values skip the color pipeline. Only a fragment can write them on every backend, so each distinct value gets a draw that discards the rest. */
    private static void drawStencil(int width, int height, int format, int type, long pixels) {
        final State state = STATE.get();
        final PixelConvert.Store unpack = gl().st().unpack;
        final long stride = PixelConvert.rowStride(width, format, type, unpack);
        final int size = PixelConvert.pixelBytes(format, type), inPixel = type == GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV ? 4 : 0;
        final long first = pixels + unpack.skipRows * stride + (long) unpack.skipPixels * size;
        final ByteBuffer image = BufferUtils.createByteBuffer(width * height);
        final boolean[] present = new boolean[256];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final long at = first + y * stride + (long) x * size;
                final int value = type == GL11.GL_FLOAT ? (int) MemoryUtil.memGetFloat(at) & 0xFF : MemoryUtil.memGetByte(at + inPixel) & 0xFF;
                image.put(y * width + x, (byte) value);
                present[value] = true;
            }
        }
        scratchTexture();
        uploadOwn(GL30.GL_R8, width, height, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(image));
        if (stencilProgram == 0) stencilProgram = buildWindowProgram(STENCIL_FRAGMENT);
        final int program = GLStateManager.ctx().activeProgram;
        GLSMAttribStack.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        GLSMEnable.glDisable(GL11.GL_DEPTH_TEST);
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMEnable.glEnable(GL11.GL_STENCIL_TEST);
        GLSMBlend.glColorMask(false, false, false, false);
        GLSMDepthStencilAlpha.glDepthMask(false);
        GLSMDepthStencilAlpha.glStencilOp(GL11.GL_REPLACE, GL11.GL_REPLACE, GL11.GL_REPLACE);
        GLSMPrograms.glUseProgram(stencilProgram);
        setWindowRect(stencilProgram, state.rasterX, state.rasterY, state.rasterX + width * state.zoomX, state.rasterY + height * state.zoomY);
        final int valueLocation = GLSMPrograms.glGetUniformLocation(stencilProgram, "value");
        for (int value = 0; value < present.length; value++) {
            if (!present[value]) continue;
            GLSMDepthStencilAlpha.glStencilFunc(GL11.GL_ALWAYS, value, 0xFF);
            GLSMPrograms.glUniform1f(valueLocation, value);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        }
        GLSMPrograms.glUseProgram(program);
        GLSMAttribStack.glPopAttrib();
        restoreTexture();
    }

    /** Depth values are fragments like any others, so the depth test decides them and the raster color lands with them. */
    private static void drawDepth(int width, int height, int type, long pixels) {
        final State state = STATE.get();
        scratchTexture();
        rb().texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, width, height, 0, GL11.GL_RED, type, pixels);
        if (depthProgram == 0) depthProgram = buildWindowProgram(DEPTH_FRAGMENT);
        final int program = GLStateManager.ctx().activeProgram;
        GLSMAttribStack.glPushAttrib(GL11.GL_ENABLE_BIT);
        GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        GLSMPrograms.glUseProgram(depthProgram);
        setWindowRect(depthProgram, state.rasterX, state.rasterY, state.rasterX + width * state.zoomX, state.rasterY + height * state.zoomY);
        GLSMPrograms.glUniform4f(GLSMPrograms.glGetUniformLocation(depthProgram, "color"), state.rasterColor[0], state.rasterColor[1], state.rasterColor[2], state.rasterColor[3]);
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GLSMPrograms.glUseProgram(program);
        GLSMAttribStack.glPopAttrib();
        restoreTexture();
    }

    private static float ndc(float window, int origin, int size) {
        return (window - origin) / size * 2f - 1f;
    }

    private static void setWindowRect(int program, float x0, float y0, float x1, float y1) {
        final ViewportState vp = GLSMRasterizer.getViewportState();
        GLSMPrograms.glUniform4f(GLSMPrograms.glGetUniformLocation(program, "rect"), ndc(x0, vp.x, vp.width), ndc(y0, vp.y, vp.height), ndc(x1, vp.x, vp.width), ndc(y1, vp.y, vp.height));
    }

    private static int buildWindowProgram(String fragment) {
        final int program = GLSMPrograms.glCreateProgram();
        GlEntryHelpers.attachCompiled(program, GL20.GL_VERTEX_SHADER, WINDOW_VERTEX);
        GlEntryHelpers.attachCompiled(program, GL20.GL_FRAGMENT_SHADER, fragment);
        GLSMPrograms.glLinkProgram(program);
        return program;
    }

    /** A one-bit image paints the raster color where its bits are set, then always moves the raster position. */
    static void bitmap(int width, int height, float xorig, float yorig, float xmove, float ymove, long bits) {
        final State state = STATE.get();
        final PixelConvert.Store unpack = gl().st().unpack;
        final long stride = bitmapStride(width, unpack);
        final DisplayListCommand move = () -> {
            state.rasterX += xmove;
            state.rasterY += ymove;
        };
        if (width <= 0 || height <= 0 || bits == 0L && gl().st().pixelUnpack == 0) {
            compiled(move);
            return;
        }
        imageCall(bits, (unpack.skipRows + height) * stride, at -> {
            drawBitmap(width, height, xorig, yorig, at);
            move.execute();
        });
    }

    private static long bitmapStride(int width, PixelConvert.Store unpack) {
        final int alignment = Math.max(1, unpack.alignment);
        final int rowBytes = ((unpack.rowLength > 0 ? unpack.rowLength : width + unpack.skipPixels) + 7) / 8;
        return (rowBytes + alignment - 1L) / alignment * alignment;
    }

    private static void drawBitmap(int width, int height, float xorig, float yorig, long bits) {
        final State state = STATE.get();
        if (!state.rasterValid) return;
        final PixelConvert.Store unpack = gl().st().unpack;
        final long stride = bitmapStride(width, unpack);
        final long first = bits + unpack.skipRows * stride;
        final ByteBuffer image = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int bit = x + unpack.skipPixels;
                final boolean set = (MemoryUtil.memGetByte(first + y * stride + bit / 8) & 0x80 >>> bit % 8) != 0;
                final int at = (y * width + x) * 4;
                image.put(at, (byte) 255).put(at + 1, (byte) 255).put(at + 2, (byte) 255).put(at + 3, (byte) (set ? 255 : 0));
            }
        }
        scratchTexture();
        uploadOwn(GL11.GL_RGBA8, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(image));
        final float x0 = state.rasterX - xorig, y0 = state.rasterY - yorig;
        drawWindowQuad(x0, y0, x0 + width, y0 + height, state.rasterZ, state.rasterColor, true);
    }

    static void copyPixels(int x, int y, int width, int height, int type) {
        compiled(() -> copyFramebufferPixels(x, y, width, height, type));
    }

    private static void copyFramebufferPixels(int x, int y, int width, int height, int type) {
        final State state = STATE.get();
        if (!state.rasterValid || width <= 0 || height <= 0) return;
        if (type != GL11.GL_COLOR) {
            final int mask = type == GL11.GL_DEPTH ? GL11.GL_DEPTH_BUFFER_BIT : GL11.GL_STENCIL_BUFFER_BIT;
            final int rx = (int) state.rasterX, ry = (int) state.rasterY;
            rb().blitFramebuffer(x, y, x + width, y + height, rx, ry, rx + (int) (width * state.zoomX), ry + (int) (height * state.zoomY), mask, GL11.GL_NEAREST);
            return;
        }
        scratchTexture();
        rb().copyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, x, y, width, height, 0);
        drawWindowQuad(state.rasterX, state.rasterY, state.rasterX + width * state.zoomX, state.rasterY + height * state.zoomY, state.rasterZ, state.pixelScale, false);
    }

    static void clearAccum(float r, float g, float b, float a) {
        final State state = STATE.get();
        compiled(() -> {
            state.accumClear[0] = r;
            state.accumClear[1] = g;
            state.accumClear[2] = b;
            state.accumClear[3] = a;
        });
    }

    public static void clearAccum() {
        final State state = STATE.get();
        ensureAccum();
        for (int i = 0; i < state.accumulated.length; i++) state.accumulated[i] = state.accumClear[i % 4];
    }

    private static void ensureAccum() {
        final State state = STATE.get();
        final ViewportState vp = GLSMRasterizer.getViewportState();
        if (state.accumulated != null && state.accumWidth == vp.width && state.accumHeight == vp.height) return;
        state.accumWidth = vp.width;
        state.accumHeight = vp.height;
        state.accumulated = new float[Math.max(1, vp.width * vp.height * 4)];
    }

    /** The accumulation buffer lives in memory, since nothing renders to it directly: colors move through it on these calls alone. */
    public static void accum(int op, float value) {
        compiled(() -> applyAccum(op, value));
    }

    private static void applyAccum(int op, float value) {
        final State state = STATE.get();
        ensureAccum();
        switch (op) {
            case GL11.GL_ACCUM, GL11.GL_LOAD -> {
                final FloatBuffer color = BufferUtils.createFloatBuffer(state.accumulated.length);
                final ViewportState vp = GLSMRasterizer.getViewportState();
                rb().readPixels(vp.x, vp.y, state.accumWidth, state.accumHeight, GL11.GL_RGBA, GL11.GL_FLOAT, color);
                for (int i = 0; i < state.accumulated.length; i++) {
                    state.accumulated[i] = (op == GL11.GL_ACCUM ? state.accumulated[i] : 0f) + color.get(i) * value;
                }
            }
            case GL11.GL_ADD -> {
                for (int i = 0; i < state.accumulated.length; i++) state.accumulated[i] += value;
            }
            case GL11.GL_MULT -> {
                for (int i = 0; i < state.accumulated.length; i++) state.accumulated[i] *= value;
            }
            case GL11.GL_RETURN -> {
                final FloatBuffer color = BufferUtils.createFloatBuffer(state.accumulated.length);
                for (int i = 0; i < state.accumulated.length; i++) color.put(i, Math.max(0f, Math.min(1f, state.accumulated[i] * value)));
                scratchTexture();
                uploadOwn(GL30.GL_RGBA32F, state.accumWidth, state.accumHeight, GL11.GL_RGBA, GL11.GL_FLOAT, MemoryUtil.memAddress(color));
                final ViewportState vp = GLSMRasterizer.getViewportState();
                drawWindowQuad(vp.x, vp.y, vp.x + state.accumWidth, vp.y + state.accumHeight, 0f, WHITE, false, true);
            }
            default -> { }
        }
    }

    /** Uploads memory of ours, which the caller's unpack state and pixel buffer say nothing about. */
    private static void uploadOwn(int internal, int width, int height, int format, int type, long pixels) {
        final PixelConvert.Store tight = new PixelConvert.Store();
        tight.alignment = 1;
        withUnpackState(tight, () -> rb().texImage2D(GL11.GL_TEXTURE_2D, 0, internal, width, height, 0, format, type, pixels));
    }

    private static int scratchTexture() {
        final State state = STATE.get();
        if (scratchTexture == 0) {
            scratchTexture = GLSMTextures.glGenTextures();
        }
        state.savedUnit = rb().getInteger(GL13.GL_ACTIVE_TEXTURE);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        state.savedTexture = rb().getInteger(GL11.GL_TEXTURE_BINDING_2D);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, scratchTexture);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return scratchTexture;
    }

    private static void restoreTexture() {
        final State state = STATE.get();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, state.savedTexture);
        GLSMTextures.glActiveTexture(state.savedUnit);
    }

    private static void drawWindowQuad(float x0, float y0, float x1, float y1, float z, float[] color, boolean alphaKeyed) {
        drawWindowQuad(x0, y0, x1, y1, z, color, alphaKeyed, false);
    }

    /** {@code direct}: as for glAccum(GL_RETURN), the quad bypasses depth, blending and the other tests, and leaves depth as it was. */
    private static void drawWindowQuad(float x0, float y0, float x1, float y1, float z, float[] color, boolean alphaKeyed, boolean direct) {
        final ViewportState vp = GLSMRasterizer.getViewportState();
        final int program = GLStateManager.ctx().activeProgram;
        final int mode = GLSMMatrices.getMatrixMode().getMode();
        GLSMAttribStack.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        if (program != 0) GLSMPrograms.glUseProgram(0);
        GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
        GLSMMatrices.glPushMatrix();
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glPushMatrix();
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glOrtho(vp.x, vp.x + vp.width, vp.y, vp.y + vp.height, -1, 1);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glPushMatrix();
        GLSMMatrices.glLoadIdentity();
        GLSMEnable.glDisable(GL11.GL_LIGHTING);
        GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        if (direct) {
            GLSMEnable.glDisable(GL11.GL_DEPTH_TEST);
            GLSMEnable.glDisable(GL11.GL_BLEND);
            GLSMEnable.glDisable(GL11.GL_ALPHA_TEST);
            GLSMEnable.glDisable(GL11.GL_STENCIL_TEST);
            GLSMEnable.glDisable(GL11.GL_FOG);
        }
        GLSMEnable.glEnable(GL11.GL_TEXTURE_2D);
        GLSMTexEnvGen.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        if (alphaKeyed) {
            GLSMDepthStencilAlpha.enableAlphaTest();
            GLSMDepthStencilAlpha.glAlphaFunc(GL11.GL_GREATER, 0f);
        }
        GLSMCurrentAttribs.glColor4f(color[0], color[1], color[2], color[3]);
        final float depth = 1f - 2f * z;
        GLSMDraws.glBegin(GL11.GL_QUADS);
        vertex(0f, 0f, x0, y0, depth);
        vertex(1f, 0f, x1, y0, depth);
        vertex(1f, 1f, x1, y1, depth);
        vertex(0f, 1f, x0, y1, depth);
        GLSMDraws.glEnd();
        GLSMMatrices.glPopMatrix();
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glPopMatrix();
        GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
        GLSMMatrices.glPopMatrix();
        GLSMMatrices.glMatrixMode(mode);
        if (program != 0) GLSMPrograms.glUseProgram(program);
        GLSMAttribStack.glPopAttrib();
        restoreTexture();
    }

    private static void vertex(float s, float t, float x, float y, float z) {
        GLSMCurrentAttribs.glTexCoord2f(s, t);
        GLSMDraws.glVertex3f(x, y, z);
    }
}
