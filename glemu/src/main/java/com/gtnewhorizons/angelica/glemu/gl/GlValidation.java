package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.compat.GlPipelines;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glemu.compat.GlTransformFeedback;
import com.gtnewhorizons.angelica.glemu.compat.ProgramInfo;
import com.gtnewhorizons.angelica.glemu.resource.BufBase;
import com.gtnewhorizons.angelica.glemu.resource.Fbo;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.PixelFormats;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;
import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glemu.shader.StageModes;
import com.gtnewhorizons.angelica.glemu.shader.Shader;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.backend.GlCallChecks;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

/**
 * The errors GL's specification requires of a call, checked before the call runs. Each method answers whether the call of the same name
 * goes ahead, and gen_backend.py runs it first, passing the arguments of the same names. Refusing leaves all state as it was.
 */
public final class GlValidation implements GlCallChecks {
    private static final int COLOR_BITS = GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT;
    private static final int ACCUM_BUFFER_BIT = 0x200;
    private static final int MAP_PERSISTENT_BIT = 0x40;

    private static final IntOpenHashSet CAPS = set(
        GL11.GL_ALPHA_TEST, GL11.GL_AUTO_NORMAL, GL11.GL_BLEND, GL11.GL_COLOR_LOGIC_OP, GL11.GL_COLOR_MATERIAL, GL14.GL_COLOR_SUM, GL11.GL_CULL_FACE,
        GL43.GL_DEBUG_OUTPUT, GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS, GL32.GL_DEPTH_CLAMP, GL11.GL_DEPTH_TEST, GL11.GL_DITHER, GL11.GL_FOG, GL30.GL_FRAMEBUFFER_SRGB,
        GL11.GL_INDEX_LOGIC_OP, GL11.GL_LIGHTING, GL11.GL_LINE_SMOOTH, GL11.GL_LINE_STIPPLE, GL13.GL_MULTISAMPLE, GL11.GL_NORMALIZE, GL11.GL_POINT_SMOOTH, GL20.GL_POINT_SPRITE,
        GL11.GL_POLYGON_OFFSET_FILL, GL11.GL_POLYGON_OFFSET_LINE, GL11.GL_POLYGON_OFFSET_POINT, GL11.GL_POLYGON_SMOOTH, GL11.GL_POLYGON_STIPPLE, GL31.GL_PRIMITIVE_RESTART,
        0x8D69, GL32.GL_PROGRAM_POINT_SIZE, GL30.GL_RASTERIZER_DISCARD, GL12.GL_RESCALE_NORMAL, GL13.GL_SAMPLE_ALPHA_TO_COVERAGE, GL13.GL_SAMPLE_ALPHA_TO_ONE, GL13.GL_SAMPLE_COVERAGE,
        0x8C36, GL32.GL_SAMPLE_MASK, GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST, GL11.GL_TEXTURE_1D, GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_3D, GL13.GL_TEXTURE_CUBE_MAP,
        GL31.GL_TEXTURE_RECTANGLE, GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS, GL11.GL_TEXTURE_GEN_Q, GL11.GL_TEXTURE_GEN_R, GL11.GL_TEXTURE_GEN_S, GL11.GL_TEXTURE_GEN_T, GL20.GL_VERTEX_PROGRAM_TWO_SIDE,
        GL11.GL_VERTEX_ARRAY, GL11.GL_NORMAL_ARRAY, GL11.GL_COLOR_ARRAY, GL11.GL_INDEX_ARRAY, GL11.GL_TEXTURE_COORD_ARRAY, GL11.GL_EDGE_FLAG_ARRAY, 0x8457, 0x845E,
        // Imaging, and the extensions a mod enables once it has seen their flags.
        0x80D0, 0x80D1, 0x80D2, 0x8010, 0x8011, 0x8012, 0x8024, 0x802E, 0x8620, 0x8804, 0x8890, 0x8910, 0x87FE, 0x8E1B, 0x8B85);
    private static final IntOpenHashSet BLEND_FACTORS = set(GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_SRC_COLOR, GL11.GL_ONE_MINUS_SRC_COLOR, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
        GL11.GL_DST_ALPHA, GL11.GL_ONE_MINUS_DST_ALPHA, GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_SRC_ALPHA_SATURATE, GL14.GL_CONSTANT_COLOR, GL14.GL_ONE_MINUS_CONSTANT_COLOR,
        GL14.GL_CONSTANT_ALPHA, GL14.GL_ONE_MINUS_CONSTANT_ALPHA, GL33.GL_SRC1_COLOR, GL33.GL_ONE_MINUS_SRC1_COLOR, GL33.GL_ONE_MINUS_SRC1_ALPHA, GL33.GL_SRC1_ALPHA);
    private static final IntOpenHashSet STENCIL_OPS = set(GL11.GL_KEEP, GL11.GL_ZERO, GL11.GL_REPLACE, GL11.GL_INCR, GL11.GL_DECR, GL11.GL_INVERT, GL14.GL_INCR_WRAP, GL14.GL_DECR_WRAP);
    private static final IntOpenHashSet HINTS = set(GL11.GL_FOG_HINT, GL11.GL_LINE_SMOOTH_HINT, GL11.GL_PERSPECTIVE_CORRECTION_HINT, GL11.GL_POINT_SMOOTH_HINT, GL11.GL_POLYGON_SMOOTH_HINT,
        GL14.GL_GENERATE_MIPMAP_HINT, GL13.GL_TEXTURE_COMPRESSION_HINT, GL20.GL_FRAGMENT_SHADER_DERIVATIVE_HINT);
    private static final IntOpenHashSet PIXEL_STORE = set(GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_ALIGNMENT,
        GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_ALIGNMENT,
        GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES, GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES,
        0x9127, 0x9128, 0x9129, 0x912A, 0x912B, 0x912C, 0x912D, 0x912E);
    private static final IntOpenHashSet BUFFER_TARGETS = set(GL15.GL_ARRAY_BUFFER, GL15.GL_ELEMENT_ARRAY_BUFFER, GL21.GL_PIXEL_PACK_BUFFER, GL21.GL_PIXEL_UNPACK_BUFFER, GL31.GL_UNIFORM_BUFFER, GL31.GL_TEXTURE_BUFFER,
        GL30.GL_TRANSFORM_FEEDBACK_BUFFER, GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, GL40.GL_DRAW_INDIRECT_BUFFER, GL42.GL_ATOMIC_COUNTER_BUFFER, GL43.GL_DISPATCH_INDIRECT_BUFFER, GL43.GL_SHADER_STORAGE_BUFFER, 0x8192, 0x9192, 0x80EE);
    private static final IntOpenHashSet BUFFER_USAGES = set(GL15.GL_STREAM_DRAW, GL15.GL_STREAM_READ, GL15.GL_STREAM_COPY, GL15.GL_STATIC_DRAW, GL15.GL_STATIC_READ, GL15.GL_STATIC_COPY, GL15.GL_DYNAMIC_DRAW, GL15.GL_DYNAMIC_READ, GL15.GL_DYNAMIC_COPY);
    private static final IntOpenHashSet SHADER_TYPES = set(GL20.GL_VERTEX_SHADER, GL20.GL_FRAGMENT_SHADER, GL32.GL_GEOMETRY_SHADER, GL40.GL_TESS_CONTROL_SHADER, GL40.GL_TESS_EVALUATION_SHADER, GL43.GL_COMPUTE_SHADER);
    private static final IntOpenHashSet QUERY_TARGETS = set(GL15.GL_SAMPLES_PASSED, GL33.GL_ANY_SAMPLES_PASSED, 0x8D6A, GL30.GL_PRIMITIVES_GENERATED, GL30.GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN, GL33.GL_TIME_ELAPSED, GlBase.TRANSFORM_FEEDBACK_OVERFLOW, GlBase.TRANSFORM_FEEDBACK_STREAM_OVERFLOW);
    private static final IntOpenHashSet ATTRIB_TYPES = set(GL11.GL_BYTE, GL11.GL_UNSIGNED_BYTE, GL11.GL_SHORT, GL11.GL_UNSIGNED_SHORT, GL11.GL_INT, GL11.GL_UNSIGNED_INT, GL30.GL_HALF_FLOAT, GL11.GL_FLOAT, GL11.GL_DOUBLE,
        0x140C, 0x8D9F, 0x8368, 0x8C3B);
    private static final IntOpenHashSet PIXEL_FORMATS = set(GL11.GL_RED, GL30.GL_RG, GL11.GL_RGB, GL12.GL_BGR, GL11.GL_RGBA, GL12.GL_BGRA, GL11.GL_GREEN, GL11.GL_BLUE, GL11.GL_ALPHA, GL11.GL_LUMINANCE, GL11.GL_LUMINANCE_ALPHA, GL11.GL_COLOR_INDEX,
        GL30.GL_RED_INTEGER, GL30.GL_RG_INTEGER, GL30.GL_RGB_INTEGER, GL30.GL_BGR_INTEGER, GL30.GL_RGBA_INTEGER, GL30.GL_BGRA_INTEGER, GL30.GL_GREEN_INTEGER, GL30.GL_BLUE_INTEGER,
        GL11.GL_DEPTH_COMPONENT, GL30.GL_DEPTH_STENCIL, GL11.GL_STENCIL_INDEX);
    private static final IntOpenHashSet PACKED_RGB = set(GL12.GL_UNSIGNED_BYTE_3_3_2, GL12.GL_UNSIGNED_BYTE_2_3_3_REV, GL12.GL_UNSIGNED_SHORT_5_6_5, GL12.GL_UNSIGNED_SHORT_5_6_5_REV, GL30.GL_UNSIGNED_INT_10F_11F_11F_REV, GL30.GL_UNSIGNED_INT_5_9_9_9_REV);
    private static final IntOpenHashSet PACKED_RGBA = set(GL12.GL_UNSIGNED_SHORT_4_4_4_4, GL12.GL_UNSIGNED_SHORT_4_4_4_4_REV, GL12.GL_UNSIGNED_SHORT_5_5_5_1, GL12.GL_UNSIGNED_SHORT_1_5_5_5_REV,
        GL12.GL_UNSIGNED_INT_8_8_8_8, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, GL12.GL_UNSIGNED_INT_10_10_10_2, GL12.GL_UNSIGNED_INT_2_10_10_10_REV);
    private static final IntOpenHashSet PACKED_DEPTH = set(GL30.GL_UNSIGNED_INT_24_8, GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV);
    private static final IntOpenHashSet PLAIN_TYPES = set(GL11.GL_UNSIGNED_BYTE, GL11.GL_BYTE, GL11.GL_UNSIGNED_SHORT, GL11.GL_SHORT, GL11.GL_UNSIGNED_INT, GL11.GL_INT, GL30.GL_HALF_FLOAT, GL11.GL_FLOAT, GL11.GL_BITMAP);
    private static final IntOpenHashSet TEX_PARAMETERS = set(GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T, GL12.GL_TEXTURE_WRAP_R, GL12.GL_TEXTURE_BASE_LEVEL, GL12.GL_TEXTURE_MAX_LEVEL,
        GL12.GL_TEXTURE_MIN_LOD, GL12.GL_TEXTURE_MAX_LOD, GL14.GL_TEXTURE_LOD_BIAS, GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_TEXTURE_COMPARE_FUNC, GL14.GL_DEPTH_TEXTURE_MODE, GL14.GL_GENERATE_MIPMAP, GL11.GL_TEXTURE_PRIORITY,
        GL33.GL_TEXTURE_SWIZZLE_R, GL33.GL_TEXTURE_SWIZZLE_G, GL33.GL_TEXTURE_SWIZZLE_B, GL33.GL_TEXTURE_SWIZZLE_A, GL33.GL_TEXTURE_SWIZZLE_RGBA, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11.GL_TEXTURE_BORDER_COLOR, 0x84FE);
    private static final IntOpenHashSet MIN_FILTERS = set(GL11.GL_NEAREST, GL11.GL_LINEAR, GL11.GL_NEAREST_MIPMAP_NEAREST, GL11.GL_LINEAR_MIPMAP_NEAREST, GL11.GL_NEAREST_MIPMAP_LINEAR, GL11.GL_LINEAR_MIPMAP_LINEAR);
    private static final IntOpenHashSet WRAPS = set(GL11.GL_CLAMP, GL11.GL_REPEAT, GL12.GL_CLAMP_TO_EDGE, GL13.GL_CLAMP_TO_BORDER, GL14.GL_MIRRORED_REPEAT, 0x8743);
    private static final IntOpenHashSet LEVEL_PARAMETERS = set(GL30.GL_TEXTURE_SHARED_SIZE, GL11.GL_TEXTURE_WIDTH, GL11.GL_TEXTURE_HEIGHT, GL12.GL_TEXTURE_DEPTH, GL11.GL_TEXTURE_INTERNAL_FORMAT, GL11.GL_TEXTURE_BORDER,
        GL30.GL_TEXTURE_RED_SIZE, GL30.GL_TEXTURE_GREEN_SIZE, GL30.GL_TEXTURE_BLUE_SIZE, GL30.GL_TEXTURE_ALPHA_SIZE, GL14.GL_TEXTURE_DEPTH_SIZE, GL30.GL_TEXTURE_STENCIL_SIZE, GL11.GL_TEXTURE_LUMINANCE_SIZE, GL11.GL_TEXTURE_INTENSITY_SIZE,
        GL30.GL_TEXTURE_RED_TYPE, GL30.GL_TEXTURE_GREEN_TYPE, GL30.GL_TEXTURE_BLUE_TYPE, GL30.GL_TEXTURE_ALPHA_TYPE, GL30.GL_TEXTURE_DEPTH_TYPE, GL13.GL_TEXTURE_COMPRESSED, GL13.GL_TEXTURE_COMPRESSED_IMAGE_SIZE, GL31.GL_TEXTURE_BUFFER_DATA_STORE_BINDING,
        GL32.GL_TEXTURE_SAMPLES, GL32.GL_TEXTURE_FIXED_SAMPLE_LOCATIONS, GL43.GL_TEXTURE_BUFFER_OFFSET, GL43.GL_TEXTURE_BUFFER_SIZE);
    private static final IntOpenHashSet IMAGE_2D_TARGETS = set(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_1D_ARRAY, GL31.GL_TEXTURE_RECTANGLE, GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_X, GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Y,
        GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y, GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Z, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z);
    private static final IntOpenHashSet PROXY_2D_TARGETS = set(GL11.GL_PROXY_TEXTURE_2D, GL30.GL_PROXY_TEXTURE_1D_ARRAY, GL31.GL_PROXY_TEXTURE_RECTANGLE, GL13.GL_PROXY_TEXTURE_CUBE_MAP);
    private static final IntOpenHashSet INTERNAL_FORMATS_WITHOUT_A_DEVICE_FORMAT = set(1, 2, 3, 4, GL11.GL_ALPHA, GL11.GL_LUMINANCE, GL11.GL_LUMINANCE_ALPHA, GL11.GL_INTENSITY, GL11.GL_RGB, GL11.GL_RGBA, GL11.GL_RED, GL30.GL_RG,
        GL11.GL_DEPTH_COMPONENT, GL30.GL_DEPTH_STENCIL, GL21.GL_SRGB, GL21.GL_SRGB_ALPHA, GL13.GL_COMPRESSED_RGB, GL13.GL_COMPRESSED_RGBA, GL13.GL_COMPRESSED_ALPHA, GL13.GL_COMPRESSED_LUMINANCE, GL13.GL_COMPRESSED_LUMINANCE_ALPHA, GL13.GL_COMPRESSED_INTENSITY,
        GL30.GL_COMPRESSED_RED, GL30.GL_COMPRESSED_RG, GL21.GL_COMPRESSED_SRGB, GL21.GL_COMPRESSED_SRGB_ALPHA, GL21.GL_SLUMINANCE, GL21.GL_SLUMINANCE_ALPHA, GL21.GL_SLUMINANCE8, GL21.GL_SLUMINANCE8_ALPHA8);

    private final GlBase<?, ?, ?> gl;

    GlValidation(GlBase<?, ?, ?> gl) {
        this.gl = gl;
    }

    private static IntOpenHashSet set(int... values) {
        return new IntOpenHashSet(values);
    }

    private boolean refuse(int error) {
        gl.recordError(error);
        return false;
    }

    private boolean anEnum(boolean valid) {
        return valid || refuse(GL11.GL_INVALID_ENUM);
    }

    private boolean aValue(boolean valid) {
        return valid || refuse(GL11.GL_INVALID_VALUE);
    }

    private boolean anOperation(boolean valid) {
        return valid || refuse(GL11.GL_INVALID_OPERATION);
    }

    private static boolean compareFunc(int func) {
        return func >= GL11.GL_NEVER && func <= GL11.GL_ALWAYS;
    }

    private static boolean blendEquationMode(int mode) {
        return mode == GL14.GL_FUNC_ADD || mode == GL14.GL_FUNC_SUBTRACT || mode == GL14.GL_FUNC_REVERSE_SUBTRACT || mode == GL14.GL_MIN || mode == GL14.GL_MAX;
    }

    private static boolean face(int face) {
        return face == GL11.GL_FRONT || face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK;
    }

    public boolean enable(int cap) {
        return anEnum(cap(cap));
    }

    public boolean disable(int cap) {
        return anEnum(cap(cap));
    }

    public boolean isEnabled(int cap) {
        return anEnum(cap(cap));
    }

    static boolean cap(int cap) {
        return CAPS.contains(cap)
            || cap >= GL11.GL_LIGHT0 && cap <= GL11.GL_LIGHT7
            || cap >= GL11.GL_CLIP_PLANE0 && cap <= GL11.GL_CLIP_PLANE5 + 2
            || cap >= GL11.GL_MAP1_COLOR_4 && cap <= GL11.GL_MAP1_VERTEX_4
            || cap >= GL11.GL_MAP2_COLOR_4 && cap <= GL11.GL_MAP2_VERTEX_4;
    }

    public boolean depthFunc(int func) {
        return anEnum(compareFunc(func));
    }

    public boolean blendFunc(int sfactor, int dfactor) {
        return anEnum(BLEND_FACTORS.contains(sfactor) && BLEND_FACTORS.contains(dfactor));
    }

    public boolean blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        return anEnum(BLEND_FACTORS.contains(srcRGB) && BLEND_FACTORS.contains(dstRGB) && BLEND_FACTORS.contains(srcAlpha) && BLEND_FACTORS.contains(dstAlpha));
    }

    public boolean blendEquation(int mode) {
        return anEnum(blendEquationMode(mode));
    }

    public boolean blendEquationSeparate(int modeRGB, int modeAlpha) {
        return anEnum(blendEquationMode(modeRGB) && blendEquationMode(modeAlpha));
    }

    public boolean cullFace(int mode) {
        return anEnum(face(mode));
    }

    public boolean frontFace(int mode) {
        return anEnum(mode == GL11.GL_CW || mode == GL11.GL_CCW);
    }

    public boolean polygonMode(int face, int mode) {
        return anEnum(face(face) && (mode == GL11.GL_POINT || mode == GL11.GL_LINE || mode == GL11.GL_FILL));
    }

    public boolean stencilFunc(int func) {
        return anEnum(compareFunc(func));
    }

    public boolean stencilFuncSeparate(int face, int func) {
        return anEnum(face(face) && compareFunc(func));
    }

    public boolean stencilOp(int sfail, int dpfail, int dppass) {
        return anEnum(STENCIL_OPS.contains(sfail) && STENCIL_OPS.contains(dpfail) && STENCIL_OPS.contains(dppass));
    }

    public boolean stencilOpSeparate(int face, int sfail, int dpfail, int dppass) {
        return anEnum(face(face) && STENCIL_OPS.contains(sfail) && STENCIL_OPS.contains(dpfail) && STENCIL_OPS.contains(dppass));
    }

    public boolean viewport(int width, int height) {
        return aValue(width >= 0 && height >= 0);
    }

    public boolean scissor(int width, int height) {
        return aValue(width >= 0 && height >= 0);
    }

    public boolean lineWidth(float width) {
        return aValue(width > 0f);
    }

    public boolean pointSize(float size) {
        return aValue(size > 0f);
    }

    public boolean clear(int mask) {
        return aValue((mask & ~(COLOR_BITS | ACCUM_BUFFER_BIT)) == 0);
    }

    public boolean hint(int target, int mode) {
        return anEnum(HINTS.contains(target) && (mode == GL11.GL_DONT_CARE || mode == GL11.GL_FASTEST || mode == GL11.GL_NICEST));
    }

    public boolean pixelStorei(int pname, int param) {
        if (!anEnum(PIXEL_STORE.contains(pname))) return false;
        if (pname == GL11.GL_UNPACK_ALIGNMENT || pname == GL11.GL_PACK_ALIGNMENT) return aValue(param == 1 || param == 2 || param == 4 || param == 8);
        if (pname == GL11.GL_UNPACK_SWAP_BYTES || pname == GL11.GL_UNPACK_LSB_FIRST || pname == GL11.GL_PACK_SWAP_BYTES || pname == GL11.GL_PACK_LSB_FIRST) return true;
        return aValue(param >= 0);
    }

    public boolean getString(int pname) {
        return anEnum(pname == GL11.GL_VENDOR || pname == GL11.GL_RENDERER || pname == GL11.GL_VERSION || pname == GL11.GL_EXTENSIONS || pname == GL20.GL_SHADING_LANGUAGE_VERSION);
    }

    public boolean activeTexture(int texture) {
        return anEnum(texture >= GL13.GL_TEXTURE0 && texture < GL13.GL_TEXTURE0 + gl.getInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
    }

    public boolean bindTexture(int target, int texture) {
        if (!anEnum(GlState.targetIndex(target) >= 0 && !(target >= GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z))) return false;
        final TexBase t = gl.textures().get(texture);
        return t == null || t.target == 0 || anOperation(t.target == com.gtnewhorizons.angelica.glemu.resource.TexturesBase.canonicalTarget(target));
    }

    private boolean texParameterValue(int pname, int value) {
        return switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> anEnum(MIN_FILTERS.contains(value));
            case GL11.GL_TEXTURE_MAG_FILTER -> anEnum(value == GL11.GL_NEAREST || value == GL11.GL_LINEAR);
            case GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T, GL12.GL_TEXTURE_WRAP_R -> anEnum(WRAPS.contains(value));
            case GL14.GL_TEXTURE_COMPARE_MODE -> anEnum(value == GL11.GL_NONE || value == GL14.GL_COMPARE_R_TO_TEXTURE);
            case GL14.GL_TEXTURE_COMPARE_FUNC -> anEnum(compareFunc(value));
            case GL12.GL_TEXTURE_BASE_LEVEL, GL12.GL_TEXTURE_MAX_LEVEL -> aValue(value >= 0);
            case GL43.GL_DEPTH_STENCIL_TEXTURE_MODE -> anEnum(value == GL11.GL_DEPTH_COMPONENT || value == GL11.GL_STENCIL_INDEX);
            case GL33.GL_TEXTURE_SWIZZLE_R, GL33.GL_TEXTURE_SWIZZLE_G, GL33.GL_TEXTURE_SWIZZLE_B, GL33.GL_TEXTURE_SWIZZLE_A ->
                anEnum(value == GL11.GL_RED || value == GL11.GL_GREEN || value == GL11.GL_BLUE || value == GL11.GL_ALPHA || value == GL11.GL_ZERO || value == GL11.GL_ONE);
            default -> true;
        };
    }

    /** Setting every swizzle at once or a whole border colour has no form taking one value. */
    private static boolean scalarTexParameter(int pname) {
        return TEX_PARAMETERS.contains(pname) && pname != GL33.GL_TEXTURE_SWIZZLE_RGBA && pname != GL11.GL_TEXTURE_BORDER_COLOR;
    }

    /** What has parameters of its own: not one face of a cube map, and not a buffer texture. */
    private static boolean parameterTarget(int target) {
        return GlState.targetIndex(target) >= 0 && target != GL31.GL_TEXTURE_BUFFER && (target < GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X || target > GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z);
    }

    private static final IntOpenHashSet SAMPLER_PARAMETERS = set(GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T, GL12.GL_TEXTURE_WRAP_R, GL12.GL_TEXTURE_MIN_LOD, GL12.GL_TEXTURE_MAX_LOD,
        GL14.GL_TEXTURE_LOD_BIAS, GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_TEXTURE_BORDER_COLOR, 0x84FE, 0x8A48);

    /** Multisampled images are only read sample by sample, so they have no sampling state to set. */
    private static boolean samplerStateOfMultisampled(int target, int pname) {
        return (target == GL32.GL_TEXTURE_2D_MULTISAMPLE || target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY)
            && pname != GL12.GL_TEXTURE_BASE_LEVEL && pname != GL12.GL_TEXTURE_MAX_LEVEL && pname != GL43.GL_DEPTH_STENCIL_TEXTURE_MODE
            && (pname < GL33.GL_TEXTURE_SWIZZLE_R || pname > GL33.GL_TEXTURE_SWIZZLE_RGBA);
    }

    public boolean texParameteri(int target, int pname, int param) {
        return anEnum(parameterTarget(target) && scalarTexParameter(pname) && !samplerStateOfMultisampled(target, pname)) && texParameterValue(pname, param);
    }

    public boolean texParameterf(int target, int pname, float param) {
        return anEnum(parameterTarget(target) && scalarTexParameter(pname) && !samplerStateOfMultisampled(target, pname)) && texParameterValue(pname, (int) param) && anisotropy(pname, param);
    }

    private boolean anisotropy(int pname, float value) {
        return pname != org.lwjgl.opengl.EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT || aValue(value >= 1f);
    }

    private boolean level(int level) {
        return aValue(level >= 0 && level <= 31 - Integer.numberOfLeadingZeros(Math.max(1, gl.getInteger(GL11.GL_MAX_TEXTURE_SIZE))));
    }

    public boolean getTexLevelParameteri(int target, int level, int pname) {
        return anEnum(LEVEL_PARAMETERS.contains(pname)) && level(level);
    }

    /** Whether client pixels of this format and type can exist, which every call taking them asks first. */
    private boolean pixels(int format, int type) {
        if (!anEnum(PIXEL_FORMATS.contains(format))) return false;
        if (!anEnum(PLAIN_TYPES.contains(type) || PACKED_RGB.contains(type) || PACKED_RGBA.contains(type) || PACKED_DEPTH.contains(type))) return false;
        final boolean smallFloats = type == GL30.GL_UNSIGNED_INT_10F_11F_11F_REV || type == GL30.GL_UNSIGNED_INT_5_9_9_9_REV;
        if (integerPixels(format) && !anEnum(type != GL30.GL_HALF_FLOAT && type != GL11.GL_FLOAT)) return false;
        final boolean rgb = format == GL11.GL_RGB || format == GL30.GL_RGB_INTEGER && !smallFloats;
        final boolean rgba = format == GL11.GL_RGBA || format == GL12.GL_BGRA || format == GL30.GL_RGBA_INTEGER || format == GL30.GL_BGRA_INTEGER;
        if (PACKED_RGB.contains(type) && !rgb) return refuse(GL11.GL_INVALID_OPERATION);
        if (PACKED_RGBA.contains(type) && !rgba) return refuse(GL11.GL_INVALID_OPERATION);
        if (PACKED_DEPTH.contains(type) != (format == GL30.GL_DEPTH_STENCIL)) return refuse(GL11.GL_INVALID_OPERATION);
        return true;
    }

    public boolean texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type) {
        final boolean proxy = PROXY_2D_TARGETS.contains(target);
        if (!anEnum(proxy || IMAGE_2D_TARGETS.contains(target)) || !pixels(format, type)) return false;
        // A proxy answers these by reporting no image, so only a real target is refused.
        if (proxy) return true;
        if (!level(level) || !aValue(width >= 0 && height >= 0 && (border == 0 || border == 1))) return false;
        if (!internalFormat(internalFormat, format)) return false;
        final int max = gl.getInteger(target >= GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z ? GL13.GL_MAX_CUBE_MAP_TEXTURE_SIZE : GL11.GL_MAX_TEXTURE_SIZE);
        return aValue((long) width << level <= max && (target == GL30.GL_TEXTURE_1D_ARRAY || (long) height << level <= max));
    }

    public boolean texSubImage2D(int target, int level, int width, int height, int format, int type) {
        return anEnum(IMAGE_2D_TARGETS.contains(target)) && subImage(target, level, width, height, 1, format, type);
    }

    public boolean bindBuffer(int target, int buffer) {
        return anEnum(BUFFER_TARGETS.contains(target)) && (!strictCore || buffer == 0 || anOperation(gl.buffers().get(buffer) != null));
    }

    /**
     * Holds the core profile to the rules mods rely on a compatibility profile relaxing: names that were never generated and drawing with no vertex array.
     * Only the Khronos run turns it on.
     */
    public static boolean strictCore;

    private boolean vertexArrayBound() {
        // The backend's binding, since a call naming its vertex array binds it there alone.
        final int bound = gl.st().vao.id;
        return !strictCore || anOperation(bound != 0 && bound != GLStateManager.ctx().defaultVAO);
    }

    private BufBase bound(int target) {
        return namedBuffer != null ? namedBuffer : gl.boundBuffer(target);
    }

    public boolean bufferData(int target, int usage) {
        if (!anEnum(BUFFER_TARGETS.contains(target) && BUFFER_USAGES.contains(usage))) return false;
        final BufBase b = bound(target);
        return anOperation(b != null && !b.immutable);
    }

    public boolean bufferSubData(int target, long offset, java.nio.Buffer data) {
        final int element = data instanceof java.nio.ByteBuffer ? 1 : data instanceof java.nio.ShortBuffer ? 2 : data instanceof java.nio.DoubleBuffer ? 8 : 4;
        return subData(target, offset, data == null ? 0L : (long) data.remaining() * element);
    }

    private boolean subData(int target, long offset, long size) {
        if (!anEnum(BUFFER_TARGETS.contains(target))) return false;
        final BufBase b = bound(target);
        if (!anOperation(b != null && (!b.immutable || (b.storageFlags & GL44.GL_DYNAMIC_STORAGE_BIT) != 0))) return false;
        // Only the part of a buffer that is mapped, and not to stay that way, is closed to it.
        return anOperation(!b.mapped || (b.mapAccess & MAP_PERSISTENT_BIT) != 0 || offset + size <= b.mapOffset || offset >= b.mapOffset + b.mapLength);
    }

    public boolean mapBuffer(int target, int access) {
        if (!anEnum(BUFFER_TARGETS.contains(target) && (access == GL15.GL_READ_ONLY || access == GL15.GL_WRITE_ONLY || access == GL15.GL_READ_WRITE))) return false;
        final BufBase b = bound(target);
        return anOperation(b != null && !b.mapped);
    }

    public boolean mapBufferRange(int target, long offset, long length, int access) {
        if (!anEnum(BUFFER_TARGETS.contains(target))) return false;
        final BufBase b = bound(target);
        if (!anOperation(b != null && !b.mapped)) return false;
        if (!aValue(offset >= 0 && length >= 0 && offset + length <= b.size && (access & ~ALL_MAP_BITS) == 0)) return false;
        // A length of zero is an operation GL refuses, as both drivers have it, rather than a value.
        if (!anOperation(length != 0)) return false;
        final boolean read = (access & GL30.GL_MAP_READ_BIT) != 0, write = (access & GL30.GL_MAP_WRITE_BIT) != 0;
        if (b.immutable && !anOperation((access & (GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT | MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT) & ~b.storageFlags) == 0)) return false;
        return anOperation(read || write)
            && anOperation(!read || (access & (GL30.GL_MAP_INVALIDATE_RANGE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT)) == 0)
            && anOperation(write || (access & GL30.GL_MAP_FLUSH_EXPLICIT_BIT) == 0);
    }

    public boolean unmapBuffer(int target) {
        if (!anEnum(BUFFER_TARGETS.contains(target))) return false;
        final BufBase b = bound(target);
        return anOperation(b != null && b.mapped);
    }

    private boolean bindingIndex(int target, int index) {
        final int limit = switch (target) {
            case GL31.GL_UNIFORM_BUFFER -> gl.getInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS);
            case GL43.GL_SHADER_STORAGE_BUFFER -> gl.getInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
            case GL42.GL_ATOMIC_COUNTER_BUFFER -> gl.getInteger(GL42.GL_MAX_ATOMIC_COUNTER_BUFFER_BINDINGS);
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER -> gl.getInteger(GL30.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS);
            default -> -1;
        };
        return anEnum(limit >= 0) && aValue(index >= 0 && index < limit);
    }

    public boolean bindBufferBase(int target, int index) {
        return anOperation(target != GL30.GL_TRANSFORM_FEEDBACK_BUFFER || !GlTransformFeedback.active()) && bindingIndex(target, index);
    }

    private boolean bindingRange(int target, int first, int count) {
        return bindingIndex(target, 0) && anOperation(first >= 0 && (long) first + count <= gl.getInteger(switch (target) {
            case GL31.GL_UNIFORM_BUFFER -> GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS;
            case GL43.GL_SHADER_STORAGE_BUFFER -> GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS;
            case GL42.GL_ATOMIC_COUNTER_BUFFER -> GL42.GL_MAX_ATOMIC_COUNTER_BUFFER_BINDINGS;
            default -> GL30.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS;
        }));
    }

    public boolean glBindBuffersBase(int target, int first, int count) {
        return aValue(count >= 0) && bindingRange(target, first, count);
    }

    public boolean glBindBuffersRange(int target, int first, int count, long buffers, long offsets, long sizes) {
        if (!aValue(count >= 0) || !bindingRange(target, first, count)) return false;
        // Drivers look at every range, whatever buffer it names.
        for (int i = 0; i < count && offsets != 0L && sizes != 0L; i++) {
            if (!aValue(MemoryUtil.memGetAddress(offsets + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE) >= 0 && MemoryUtil.memGetAddress(sizes + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE) > 0)) return false;
        }
        return true;
    }

    public boolean glBindTextures(int first, int count) {
        return aValue(count >= 0) && anOperation(first >= 0 && (long) first + count <= gl.getInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
    }

    public boolean glBindSamplers(int first, int count) {
        return aValue(count >= 0) && anOperation(first >= 0 && (long) first + count <= gl.getInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
    }

    public boolean glBindImageTextures(int first, int count) {
        return aValue(count >= 0) && anOperation(first >= 0 && (long) first + count <= gl.getInteger(GL42.GL_MAX_IMAGE_UNITS));
    }

    public boolean glBindVertexBuffers(int first, int count) {
        return aValue(count >= 0) && anOperation(first >= 0 && (long) first + count <= gl.getInteger(GL43.GL_MAX_VERTEX_ATTRIB_BINDINGS));
    }

    /** Table 8.26, the formats an image unit can read. */
    private static final IntOpenHashSet IMAGE_FORMATS = set(GL30.GL_RGBA32F, GL30.GL_RGBA16F, GL30.GL_RG32F, GL30.GL_RG16F, GL30.GL_R11F_G11F_B10F, GL30.GL_R32F, GL30.GL_R16F,
        GL30.GL_RGBA32UI, GL30.GL_RGBA16UI, GL33.GL_RGB10_A2UI, GL30.GL_RGBA8UI, GL30.GL_RG32UI, GL30.GL_RG16UI, GL30.GL_RG8UI, GL30.GL_R32UI, GL30.GL_R16UI, GL30.GL_R8UI,
        GL30.GL_RGBA32I, GL30.GL_RGBA16I, GL30.GL_RGBA8I, GL30.GL_RG32I, GL30.GL_RG16I, GL30.GL_RG8I, GL30.GL_R32I, GL30.GL_R16I, GL30.GL_R8I,
        GL11.GL_RGBA16, GL11.GL_RGB10_A2, GL11.GL_RGBA8, GL30.GL_RG16, GL30.GL_RG8, GL30.GL_R16, GL30.GL_R8,
        GL31.GL_RGBA16_SNORM, GL31.GL_RGBA8_SNORM, GL31.GL_RG16_SNORM, GL31.GL_RG8_SNORM, GL31.GL_R16_SNORM, GL31.GL_R8_SNORM);

    public static boolean imageFormat(int format) {
        return IMAGE_FORMATS.contains(format);
    }

    public boolean bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        return aValue(unit >= 0 && unit < gl.getInteger(GL42.GL_MAX_IMAGE_UNITS) && (texture == 0 || gl.textures().exists(texture)) && level >= 0 && layer >= 0 && IMAGE_FORMATS.contains(format))
            && aValue(access == GL15.GL_READ_ONLY || access == GL15.GL_WRITE_ONLY || access == GL15.GL_READ_WRITE);
    }

    private boolean computeProgramActive() {
        // After glUseProgram(0) the fixed function program may still be the device's until the next draw.
        if (GlPipelines.boundPipeline() == 0 && GLStateManager.ctx().activeProgram == 0) return anOperation(false);
        final ProgramBase<?> current = gl.programs().program(gl.dispatchProgram(gl.st()));
        return anOperation(current != null && current.linked && current.compute);
    }

    public boolean dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
        final int limit = gl.getIntegerIndexed(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0);
        return computeProgramActive() && aValue(numGroupsX >= 0 && numGroupsY >= 0 && numGroupsZ >= 0 && numGroupsX <= limit && numGroupsY <= limit && numGroupsZ <= limit);
    }

    public boolean dispatchComputeIndirect(long offset) {
        if (!aValue(offset >= 0 && offset % 4 == 0) || !computeProgramActive()) return false;
        final BufBase b = bound(GL43.GL_DISPATCH_INDIRECT_BUFFER);
        return anOperation(b != null && offset + 12 <= b.size);
    }

    public boolean glShaderStorageBlockBinding(int program, int blockIndex, int binding) {
        final ProgramBase<?> p = program(program);
        if (p == null) return refuse(program != 0 && gl.isShader(program) ? GL11.GL_INVALID_OPERATION : GL11.GL_INVALID_VALUE);
        int blocks = 0;
        for (ProgramBase.BufferBlock block : p.ssbos) if (!block.atomicCounters && !block.internal()) blocks++;
        return aValue(blockIndex >= 0 && blockIndex < blocks && binding >= 0 && binding < gl.getInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS));
    }

    public boolean glTextureView(int texture, int target, int origtexture, int internalformat, int minlevel, int numlevels, int minlayer, int numlayers) {
        if (!aValue(texture != 0) || !anOperation(gl.textures().exists(texture) && !gl.textures().get(texture).hasImage() && !gl.textures().get(texture).named) || !aValue(gl.textures().exists(origtexture))) return false;
        final TexBase from = gl.textures().get(origtexture);
        if (!anOperation(from.immutable)) return false;
        if (!anEnum(VIEW_TARGETS.contains(target))) return false;
        final int created = from.declaredTarget != 0 ? from.declaredTarget : from.target;
        if (!anOperation(viewTargetFits(created, target))) return false;
        if (!anOperation(gl.textures().knownInternalFormat(internalformat) && viewFormatFits(from.format, gl.textures().format(internalformat)))) return false;
        if (!aValue(minlevel >= 0 && minlevel < from.levels && minlayer >= 0 && minlayer < (from.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : Math.max(1, from.layers)) && numlevels > 0 && numlayers > 0)) return false;
        final boolean oneLayer = target == GL11.GL_TEXTURE_1D || target == GL11.GL_TEXTURE_2D || target == GL12.GL_TEXTURE_3D || target == GL31.GL_TEXTURE_RECTANGLE || target == GL32.GL_TEXTURE_2D_MULTISAMPLE;
        if (oneLayer && !aValue(numlayers == 1)) return false;
        if (target == GL13.GL_TEXTURE_CUBE_MAP && !aValue(numlayers == 6)) return false;
        if (target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY && !aValue(numlayers % 6 == 0)) return false;
        return (target != GL13.GL_TEXTURE_CUBE_MAP && target != GL40.GL_TEXTURE_CUBE_MAP_ARRAY) || anOperation(from.levelWidth(minlevel) == from.levelHeight(minlevel));
    }

    private static final IntOpenHashSet VIEW_TARGETS = set(GL11.GL_TEXTURE_1D, GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_3D, GL13.GL_TEXTURE_CUBE_MAP, GL31.GL_TEXTURE_RECTANGLE, GL30.GL_TEXTURE_1D_ARRAY,
        GL30.GL_TEXTURE_2D_ARRAY, GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL32.GL_TEXTURE_2D_MULTISAMPLE, GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY);

    private static boolean viewTargetFits(int original, int view) {
        return switch (original) {
            case GL11.GL_TEXTURE_1D, GL30.GL_TEXTURE_1D_ARRAY -> view == GL11.GL_TEXTURE_1D || view == GL30.GL_TEXTURE_1D_ARRAY;
            case GL11.GL_TEXTURE_2D -> view == GL11.GL_TEXTURE_2D || view == GL30.GL_TEXTURE_2D_ARRAY;
            case GL30.GL_TEXTURE_2D_ARRAY -> view == GL11.GL_TEXTURE_2D || view == GL30.GL_TEXTURE_2D_ARRAY || view == GL13.GL_TEXTURE_CUBE_MAP || view == GL40.GL_TEXTURE_CUBE_MAP_ARRAY;
            case GL13.GL_TEXTURE_CUBE_MAP, GL40.GL_TEXTURE_CUBE_MAP_ARRAY -> view == GL13.GL_TEXTURE_CUBE_MAP || view == GL11.GL_TEXTURE_2D || view == GL30.GL_TEXTURE_2D_ARRAY || view == GL40.GL_TEXTURE_CUBE_MAP_ARRAY;
            case GL32.GL_TEXTURE_2D_MULTISAMPLE, GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY -> view == GL32.GL_TEXTURE_2D_MULTISAMPLE || view == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
            default -> view == original;
        };
    }

    /** By what GL says a format holds, which a device can store in more than that. */
    private static int viewClassBits(Formats.Format f) {
        return f.baseFormat() == GL11.GL_RGB && f.r() == 9 ? 32 : f.r() + f.g() + f.b() + f.a();
    }

    /** Signed and unsigned forms of one block layout share a class. */
    private static int compressedClass(int pf) {
        return switch (pf) {
            case PixelFormats.PF_BC4_R_UNORM, PixelFormats.PF_BC4_R_SNORM -> 1;
            case PixelFormats.PF_BC5_RG_UNORM, PixelFormats.PF_BC5_RG_SNORM -> 2;
            case PixelFormats.PF_BC7_RGBA_UNORM, PixelFormats.PF_BC7_RGBA_SRGB -> 3;
            case PixelFormats.PF_BC6H_SFLOAT, PixelFormats.PF_BC6H_UFLOAT -> 4;
            default -> 0;
        };
    }

    /** The classes of GL's table of view formats are sizes of texel, and for a compressed format its family; depth and stencil only match themselves. */
    private static boolean viewFormatFits(Formats.Format original, Formats.Format view) {
        if (original == null || view == null) return false;
        if (original.isDepthOrStencil() || view.isDepthOrStencil()) return original.pf() == view.pf();
        if (original.isCompressed() != view.isCompressed()) return false;
        if (!original.isCompressed()) return viewClassBits(original) == viewClassBits(view);
        if (Formats.linearOf(original.pf()) == Formats.linearOf(view.pf())) return true;
        if (compressedClass(original.pf()) != 0 || compressedClass(view.pf()) != 0) return compressedClass(original.pf()) == compressedClass(view.pf());
        return original.blockBytes() == view.blockBytes() && original.channels() == view.channels();
    }

    public boolean glUniform1dv(int location, int count) {
        return doubleUniform(location, count, GL11.GL_DOUBLE);
    }

    public boolean glUniform2dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_VEC2);
    }

    public boolean glUniform3dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_VEC3);
    }

    public boolean glUniform4dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_VEC4);
    }

    public boolean glUniformMatrix2dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_MAT2);
    }

    public boolean glUniformMatrix3dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_MAT3);
    }

    public boolean glUniformMatrix4dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_MAT4);
    }

    public boolean glUniformMatrix2x3dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_MAT2x3);
    }

    public boolean glUniformMatrix3x2dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_MAT3x2);
    }

    public boolean glUniformMatrix2x4dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_MAT2x4);
    }

    public boolean glUniformMatrix4x2dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_MAT4x2);
    }

    public boolean glUniformMatrix3x4dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_MAT3x4);
    }

    public boolean glUniformMatrix4x3dv(int location, int count) {
        return doubleUniform(location, count, GL40.GL_DOUBLE_MAT4x3);
    }

    private boolean programForUniform(int program) {
        final ProgramBase<?> p = program(program);
        if (p == null) return refuse(program != 0 && gl.isShader(program) ? GL11.GL_INVALID_OPERATION : GL11.GL_INVALID_VALUE);
        return anOperation(p.linked);
    }

    /** glProgramUniform*v: a name that is no program is a value GL refuses, a shader's an operation, before the array is looked at. */
    private boolean programUniformArray(int program, int location, int count) {
        final ProgramBase<?> p = gl.programs().program(program);
        if (p == null) return refuse(program != 0 && gl.isShader(program) ? GL11.GL_INVALID_OPERATION : GL11.GL_INVALID_VALUE);
        return uniformArray(p, location, count);
    }

    public boolean glProgramUniform1i(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform1f(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform1d(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform2i(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform2f(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform2d(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform2ui(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform3i(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform3f(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform3d(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform3ui(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform4i(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform4f(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform4d(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform4ui(int program) {
        return programForUniform(program);
    }

    public boolean glProgramUniform1ui(int program, int location) {
        final ProgramBase<?> p = program(program);
        if (p == null || !anOperation(p.linked)) return false;
        if (location == -1) return true;
        final ProgramBase.Uniform u = uniformAt(p, location);
        return anOperation(u == null || !u.isOpaque());
    }

    public boolean glBufferStorage(int target, long size) {
        return anEnum(BUFFER_TARGETS.contains(target)) && anOperation(bound(target) != null) && aValue(size > 0);
    }

    public boolean glNamedBufferStorage(int buffer, long size) {
        return aValue(size > 0);
    }

    public boolean glTexBufferRange(int target, int internalFormat, int buffer, long offset, long size) {
        return glTexBuffer(target, internalFormat, buffer)
            && (buffer == 0 || aValue(offset >= 0 && size > 0 && offset + size <= gl.buffers().get(buffer).size && offset % gl.getInteger(GL43.GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT) == 0));
    }

    public boolean bindBufferRange(int target, int index, int buffer, long offset, long size) {
        if (!anOperation(target != GL30.GL_TRANSFORM_FEEDBACK_BUFFER || !GlTransformFeedback.active())) return false;
        if (!bindingIndex(target, index) || buffer != 0 && !aValue(offset >= 0 && size > 0)) return false;
        final int alignment = target == GL31.GL_UNIFORM_BUFFER ? gl.getInteger(GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT) : target == GL43.GL_SHADER_STORAGE_BUFFER ? gl.getInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT) : target == GL42.GL_ATOMIC_COUNTER_BUFFER || target == GL30.GL_TRANSFORM_FEEDBACK_BUFFER ? 4 : 1;
        return aValue((alignment <= 1 || offset % alignment == 0) && (target != GL30.GL_TRANSFORM_FEEDBACK_BUFFER || size % 4 == 0));
    }

    private boolean framebufferTarget(int target) {
        return anEnum(target == GL30.GL_FRAMEBUFFER || target == GL30.GL_DRAW_FRAMEBUFFER || target == GL30.GL_READ_FRAMEBUFFER);
    }

    public boolean bindFramebuffer(int target) {
        return framebufferTarget(target);
    }

    public boolean checkFramebufferStatus(int target) {
        return framebufferTarget(target);
    }

    private boolean attachable(int target) {
        final Fbo f = namedFramebuffer != null ? namedFramebuffer : gl.fboFor(target);
        return framebufferTarget(target) && anOperation(f != null && f.id != 0);
    }

    private boolean attachment(int target, int attachment, int texture) {
        return attachable(target) && namedAttachment(attachment) && (texture == 0 || anOperation(gl.textures().exists(texture)));
    }

    private static boolean attachmentPoint(int attachment) {
        return attachment >= GL30.GL_COLOR_ATTACHMENT0 && attachment <= GL30.GL_COLOR_ATTACHMENT0 + 31
            || attachment == GL30.GL_DEPTH_ATTACHMENT || attachment == GL30.GL_STENCIL_ATTACHMENT || attachment == GL30.GL_DEPTH_STENCIL_ATTACHMENT;
    }

    private boolean attachedLevel(int target, int attachment, int texture, int level) {
        return attachment(target, attachment, texture) && (texture == 0 || level(level));
    }

    public boolean glClipControl(int origin, int depth) {
        return anEnum((origin == GL45.GL_LOWER_LEFT || origin == GL45.GL_UPPER_LEFT) && (depth == GL45.GL_NEGATIVE_ONE_TO_ONE || depth == GL45.GL_ZERO_TO_ONE));
    }

    public boolean framebufferTexture(int target, int attachment, int texture, int level) {
        if (!attachable(target) || !namedAttachment(attachment)) return false;
        if (texture == 0) return true;
        if (!aValue(gl.textures().exists(texture))) return false;
        final TexBase t = gl.textures().get(texture);
        if (t == null) return level(level);
        if (!anOperation(t.target != GL31.GL_TEXTURE_BUFFER)) return false;
        return level(level) && aValue(!t.immutable || level < t.levels) && aValue(level == 0 || t.samples <= 1);
    }

    private boolean framebufferParameter(int pname, int param) {
        if (!anEnum(pname >= GL43.GL_FRAMEBUFFER_DEFAULT_WIDTH && pname <= GL43.GL_FRAMEBUFFER_DEFAULT_FIXED_SAMPLE_LOCATIONS)) return false;
        final int limit = switch (pname) {
            case GL43.GL_FRAMEBUFFER_DEFAULT_WIDTH -> gl.getInteger(GL43.GL_MAX_FRAMEBUFFER_WIDTH);
            case GL43.GL_FRAMEBUFFER_DEFAULT_HEIGHT -> gl.getInteger(GL43.GL_MAX_FRAMEBUFFER_HEIGHT);
            case GL43.GL_FRAMEBUFFER_DEFAULT_LAYERS -> gl.getInteger(GL43.GL_MAX_FRAMEBUFFER_LAYERS);
            case GL43.GL_FRAMEBUFFER_DEFAULT_SAMPLES -> gl.getInteger(GL43.GL_MAX_FRAMEBUFFER_SAMPLES);
            default -> Integer.MAX_VALUE;
        };
        return aValue(param >= 0 && param <= limit);
    }

    public boolean glFramebufferParameteri(int target, int pname, int param) {
        return attachable(target) && framebufferParameter(pname, param);
    }

    public boolean glGetFramebufferParameteriv(int target, int pname) {
        final Fbo f = gl.fboFor(target);
        if (!framebufferTarget(target)) return false;
        final boolean settable = pname >= GL43.GL_FRAMEBUFFER_DEFAULT_WIDTH && pname <= GL43.GL_FRAMEBUFFER_DEFAULT_FIXED_SAMPLE_LOCATIONS;
        final boolean state = pname == GL13.GL_SAMPLES || pname == GL13.GL_SAMPLE_BUFFERS || pname == GL11.GL_DOUBLEBUFFER || pname == GL11.GL_STEREO
            || pname == GL41.GL_IMPLEMENTATION_COLOR_READ_FORMAT || pname == GL41.GL_IMPLEMENTATION_COLOR_READ_TYPE;
        return anEnum(settable || state) && anOperation(!settable || f != null && f.id != 0);
    }

    public boolean framebufferTexture1D(int target, int attachment, int textarget, int texture, int level) {
        return attachedLevel(target, attachment, texture, level) && (texture == 0 || anEnum(textarget == GL11.GL_TEXTURE_1D));
    }

    public boolean framebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        return attachedLevel(target, attachment, texture, level) && (texture == 0 || anEnum(IMAGE_2D_TARGETS.contains(textarget) && textarget != GL30.GL_TEXTURE_1D_ARRAY || textarget == GL32.GL_TEXTURE_2D_MULTISAMPLE));
    }

    public boolean framebufferTexture3D(int target, int attachment, int textarget, int texture, int level, int zoffset) {
        return attachedLevel(target, attachment, texture, level) && (texture == 0 || anEnum(textarget == GL12.GL_TEXTURE_3D) && aValue(zoffset >= 0 && zoffset < gl.getInteger(GL12.GL_MAX_3D_TEXTURE_SIZE)));
    }

    public boolean framebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        if (!attachedLevel(target, attachment, texture, level)) return false;
        if (texture == 0) return true;
        final TexBase t = gl.textures().get(texture);
        final int layers = t != null && t.is3D() ? gl.getInteger(GL12.GL_MAX_3D_TEXTURE_SIZE) : gl.getInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS);
        return aValue(layer >= 0 && layer < layers);
    }

    public boolean framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        return attachable(target) && anEnum(attachmentPoint(attachment) && renderbuffertarget == GL30.GL_RENDERBUFFER)
            && (renderbuffer == 0 || anOperation(gl.textures().renderbufferExists(renderbuffer)));
    }

    private boolean readable() {
        final GlState st = gl.st();
        return st.readFbo == 0 || gl.framebuffers.status(gl.framebuffers.get(st.readFbo)) == GL30.GL_FRAMEBUFFER_COMPLETE
            || refuse(GL30.GL_INVALID_FRAMEBUFFER_OPERATION);
    }

    /** Samples can only be read after resolving them, which is what a blit is for. */
    private boolean readableOneSample() {
        return readable() && anOperation(gl.framebufferSamples(gl.framebuffers.get(gl.st().readFbo)) == 0);
    }

    /** The robust form refuses a client buffer too small for the pixels; a pack buffer has its own bounds. */
    public boolean glReadnPixels(int x, int y, int width, int height, int format, int type, int bufSize) {
        if (!readPixels(width, height, format, type)) return false;
        return anOperation(gl.st().pixelPack != 0 || bufSize >= com.gtnewhorizons.angelica.glemu.resource.PixelConvert.clientBytes(width, height, 1, format, type, gl.st().pack));
    }

    public boolean readPixels(int width, int height, int format, int type) {
        if (format == GL30.GL_DEPTH_STENCIL && !anEnum(PACKED_DEPTH.contains(type))) return false;
        return pixels(format, type) && aValue(width >= 0 && height >= 0) && readableOneSample() && anOperation(gl.readBufferOffers(format)) && packBufferUsable();
    }

    /** A pack buffer mapped without being persistent cannot also take pixels. */
    private boolean packBufferUsable() {
        final BufBase pack = gl.st().pixelPack == 0 ? null : gl.boundBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        return pack == null || anOperation(!pack.mapped || (pack.mapAccess & MAP_PERSISTENT_BIT) != 0);
    }

    public boolean blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        if (!anEnum(filter == GL11.GL_NEAREST || filter == GL11.GL_LINEAR)) return false;
        if (!aValue((mask & ~COLOR_BITS) == 0)) return false;
        if (!anOperation(filter == GL11.GL_NEAREST || (mask & (GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT)) == 0) || !readable()) return false;
        final GlState st = gl.st();
        // A multisampled source resolves to any rectangle and format of a single-sampled target; a multisampled target takes only a copy of the same rectangle and format.
        final boolean samples = gl.framebufferSamples(gl.framebuffers.get(st.drawFbo)) > 0;
        return !samples || anOperation(srcX0 == dstX0 && srcX1 == dstX1 && srcY0 == dstY0 && srcY1 == dstY1 && ((mask & GL11.GL_COLOR_BUFFER_BIT) == 0 || gl.blitFormatsResolve()));
    }

    private static boolean colorBuffer(int mode) {
        return mode == GL11.GL_NONE || mode >= GL11.GL_FRONT_LEFT && mode <= GL11.GL_AUX3 || mode >= GL30.GL_COLOR_ATTACHMENT0 && mode <= GL30.GL_COLOR_ATTACHMENT0 + 31;
    }

    private boolean colorBufferOfFramebuffer(int mode, Fbo f) {
        if (!anEnum(colorBuffer(mode))) return false;
        if (mode < GL30.GL_COLOR_ATTACHMENT0 || mode == GL11.GL_NONE) return anOperation(mode == GL11.GL_NONE || f == null || f.id == 0);
        return anOperation(f != null && f.id != 0) && aValue(mode < GL30.GL_COLOR_ATTACHMENT0 + gl.getInteger(GL30.GL_MAX_COLOR_ATTACHMENTS));
    }

    public boolean drawBuffer(int mode) {
        return colorBufferOfFramebuffer(mode, gl.framebuffers.get(gl.st().drawFbo));
    }

    public boolean readBuffer(int mode) {
        return colorBufferOfFramebuffer(mode, gl.framebuffers.get(gl.st().readFbo));
    }

    /** What glDrawBuffers names: GL_NONE, one of the default framebuffer's four colour buffers or its auxiliaries, or a colour attachment. */
    private static boolean drawBuffersName(int mode) {
        return mode == GL11.GL_NONE || mode >= GL11.GL_FRONT_LEFT && mode <= GL11.GL_BACK_RIGHT || mode >= GL11.GL_AUX0 && mode <= GL11.GL_AUX3
            || mode >= GL30.GL_COLOR_ATTACHMENT0 && mode <= GL30.GL_COLOR_ATTACHMENT0 + 31;
    }

    public boolean drawBuffers(IntBuffer bufs) {
        if (bufs == null) return true;
        if (!aValue(bufs.remaining() <= gl.getInteger(GL20.GL_MAX_DRAW_BUFFERS))) return false;
        final Fbo f = gl.framebuffers.get(gl.st().drawFbo);
        for (int i = bufs.position(); i < bufs.limit(); i++) {
            final int mode = bufs.get(i);
            // GL_BACK, GL_COLOR and the like name more than one buffer, which only glDrawBuffer takes.
            if (!anEnum(drawBuffersName(mode))) return false;
            if (mode >= GL30.GL_COLOR_ATTACHMENT0 && !anOperation(f != null && f.id != 0 && mode - GL30.GL_COLOR_ATTACHMENT0 < gl.getInteger(GL30.GL_MAX_COLOR_ATTACHMENTS))) return false;
        }
        return true;
    }

    private static boolean drawMode(int mode) {
        return mode >= GL11.GL_POINTS && mode <= GL40.GL_PATCHES;
    }

    private boolean draw(int mode, int count) {
        return primitive(mode) && aValue(count >= 0) && pipelineCanDraw();
    }

    /** A pipeline may lack a vertex stage or hold stages that do not fit together; drawing with it is then an error. */
    private boolean pipelineCanDraw() {
        if (GlPipelines.boundPipeline() == 0) return true;
        final int active = GLStateManager.ctx().activeProgram;
        return active != 0 && !gl.pipelineProgramIsCurrent() || anOperation(!GlPipelines.lacksVertexStage() && GlPipelines.boundStagesFit());
    }

    private boolean indexed(int mode, int count, int type) {
        return anEnum(type == GL11.GL_UNSIGNED_BYTE || type == GL11.GL_UNSIGNED_SHORT || type == GL11.GL_UNSIGNED_INT) && primitive(mode) && aValue(count >= 0) && pipelineCanDraw();
    }

    public boolean drawArrays(int mode, int count) {
        return draw(mode, count);
    }

    public boolean drawArraysInstanced(int mode, int first, int count, int primcount) {
        return draw(mode, count) && aValue(first >= 0 && primcount >= 0);
    }

    public boolean drawElements(int mode, int count, int type) {
        return indexed(mode, count, type);
    }

    public boolean drawElementsInstanced(int mode, int count, int type, int primcount) {
        return indexed(mode, count, type) && aValue(primcount >= 0);
    }

    public boolean drawElementsBaseVertex(int mode, int count, int type) {
        return indexed(mode, count, type);
    }

    private boolean attribIndex(int index) {
        return aValue(index >= 0 && index < gl.getInteger(GL20.GL_MAX_VERTEX_ATTRIBS));
    }

    public boolean enableVertexAttribArray(int index) {
        return attribIndex(index);
    }

    public boolean disableVertexAttribArray(int index) {
        return attribIndex(index);
    }

    public boolean vertexAttrib2f(int index) {
        return attribIndex(index);
    }

    public boolean vertexAttrib3f(int index) {
        return attribIndex(index);
    }

    public boolean vertexAttrib4f(int index) {
        return attribIndex(index);
    }

    public boolean vertexAttribDivisor(int index) {
        return attribIndex(index);
    }

    private boolean bindingIndex(int index) {
        return aValue(index >= 0 && index < gl.getInteger(GL43.GL_MAX_VERTEX_ATTRIB_BINDINGS));
    }

    public boolean bindVertexBuffer(int bindingindex, int buffer, long offset, int stride) {
        if (!vertexArrayBound()) return false;
        return bindingIndex(bindingindex) && aValue(offset >= 0L && stride >= 0 && stride <= gl.getInteger(GL44.GL_MAX_VERTEX_ATTRIB_STRIDE))
            && (!strictCore || anOperation(buffer == 0 || gl.isBuffer(buffer)));
    }

    public boolean vertexBindingDivisor(int binding) {
        if (!vertexArrayBound()) return false;
        return bindingIndex(binding);
    }

    public boolean vertexAttribBinding(int attribindex, int bindingindex) {
        if (!vertexArrayBound()) return false;
        return attribIndex(attribindex) && bindingIndex(bindingindex);
    }

    private boolean attribFormat(int attribindex, int size, int type, int relativeoffset, boolean allowsBgra, IntOpenHashSet types) {
        if (!attribIndex(attribindex) || !aValue(size >= 1 && size <= 4 || allowsBgra && size == GL12.GL_BGRA) || !anEnum(types.contains(type))) return false;
        return aValue(relativeoffset >= 0 && relativeoffset <= gl.getInteger(GL43.GL_MAX_VERTEX_ATTRIB_RELATIVE_OFFSET));
    }

    public boolean vertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset) {
        if (!vertexArrayBound()) return false;
        return attribFormat(attribindex, size, type, relativeoffset, true, ATTRIB_TYPES) && packedAttrib(size, type, normalized);
    }

    /** What sections 10.2.1 and 10.3.1 ask of the sizes the packed types and GL_BGRA come with. */
    private boolean packedAttrib(int size, int type, boolean normalized) {
        final boolean packed = type == GL12.GL_UNSIGNED_INT_2_10_10_10_REV || type == GL33.GL_INT_2_10_10_10_REV;
        if (size == GL12.GL_BGRA && !anOperation((packed || type == GL11.GL_UNSIGNED_BYTE) && normalized)) return false;
        if (packed && !anOperation(size == 4 || size == GL12.GL_BGRA)) return false;
        return anOperation(type != GL30.GL_UNSIGNED_INT_10F_11F_11F_REV || size == 3);
    }

    private static final IntOpenHashSet INTEGER_ATTRIB_TYPES = set(GL11.GL_BYTE, GL11.GL_UNSIGNED_BYTE, GL11.GL_SHORT, GL11.GL_UNSIGNED_SHORT, GL11.GL_INT, GL11.GL_UNSIGNED_INT);

    public boolean multiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) {
        return primitive(mode) && indirectCount(indirect, drawcount, maxdrawcount, stride, 16);
    }

    public boolean multiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount, int stride) {
        return anEnum(type == GL11.GL_UNSIGNED_BYTE || type == GL11.GL_UNSIGNED_SHORT || type == GL11.GL_UNSIGNED_INT) && primitive(mode)
            && indirectCount(indirect, drawcount, maxdrawcount, stride, 20);
    }

    /** The commands have to fit the indirect buffer before the count's offset is looked at, as both Mesa and NVIDIA have it. */
    private boolean indirectCount(long indirect, long drawcount, int maxdrawcount, int stride, int size) {
        if (!aValue(maxdrawcount >= 0 && stride >= 0 && stride % 4 == 0)) return false;
        final GlState st = gl.st();
        final BufBase counts = gl.buffers().get(st.parameterBuffer), commands = gl.buffers().get(st.drawIndirect);
        if (!anOperation(commands != null && (maxdrawcount == 0 || indirect + (maxdrawcount - 1L) * (stride == 0 ? size : stride) + size <= commands.size))) return false;
        if (!aValue(drawcount >= 0L && drawcount % 4L == 0L)) return false;
        return anOperation(counts != null && drawcount + Integer.BYTES <= counts.size);
    }

    public boolean glVertexAttribLFormat(int attribindex, int size, int type, int relativeoffset) {
        if (!vertexArrayBound()) return false;
        return attribFormat(attribindex, size, type, relativeoffset, false, DOUBLE_ONLY);
    }

    private boolean attribLFormat(int attribindex, int size, int type, int relativeoffset) {
        return attribFormat(attribindex, size, type, relativeoffset, false, DOUBLE_ONLY);
    }

    private static final IntOpenHashSet DOUBLE_ONLY = set(GL11.GL_DOUBLE);

    public boolean vertexAttribIFormat(int attribindex, int size, int type, int relativeoffset) {
        if (!vertexArrayBound()) return false;
        return attribFormat(attribindex, size, type, relativeoffset, false, INTEGER_ATTRIB_TYPES);
    }

    public boolean vertexAttribPointer(int index, int size, int type, boolean normalized, int stride) {
        return attribIndex(index) && aValue(size >= 1 && size <= 4 || size == GL12.GL_BGRA) && anEnum(ATTRIB_TYPES.contains(type)) && aValue(stride >= 0 && stride <= gl.getInteger(GL44.GL_MAX_VERTEX_ATTRIB_STRIDE)) && packedAttrib(size, type, normalized);
    }

    public boolean glVertexAttribLPointer(int index, int size, int type, int stride, long pointer) {
        return attribIndex(index) && aValue(size >= 1 && size <= 4) && anEnum(type == GL11.GL_DOUBLE) && aValue(stride >= 0 && stride <= gl.getInteger(GL44.GL_MAX_VERTEX_ATTRIB_STRIDE))
            && anOperation(pointer == 0L || bound(GL15.GL_ARRAY_BUFFER) != null);
    }

    public boolean vertexAttribIPointer(int index, int size, int type, int stride) {
        return attribIndex(index) && aValue(size >= 1 && size <= 4)
            && anEnum(type == GL11.GL_BYTE || type == GL11.GL_UNSIGNED_BYTE || type == GL11.GL_SHORT || type == GL11.GL_UNSIGNED_SHORT || type == GL11.GL_INT || type == GL11.GL_UNSIGNED_INT)
            && aValue(stride >= 0 && stride <= gl.getInteger(GL44.GL_MAX_VERTEX_ATTRIB_STRIDE));
    }

    public boolean bindVertexArray(int array) {
        return anOperation(array == 0 || gl.isVertexArrayName(array));
    }

    public boolean beginQuery(int target, int id) {
        return anEnum(QUERY_TARGETS.contains(target) || PipelineStatistics.target(target)) && anOperation(!gl.queryActive(target) && !gl.queryIdActive(id) && id != 0 && (gl.queryTarget(id) == 0 || gl.queryTarget(id) == target));
    }

    public boolean endQuery(int target) {
        return anEnum(QUERY_TARGETS.contains(target) || PipelineStatistics.target(target)) && anOperation(gl.queryActive(target));
    }

    public boolean queryCounter(int id, int target) {
        return anEnum(target == GL33.GL_TIMESTAMP) && anOperation(id != 0);
    }

    public boolean createShader(int type) {
        return anEnum(SHADER_TYPES.contains(type));
    }

    /** Whether a name is a program. GL tells a name that is nothing from one that is a shader. */
    private ProgramBase program(int id) {
        final ProgramBase p = gl.programs().program(id);
        if (p != null) return p;
        refuse(gl.programs().isShader(id) ? GL11.GL_INVALID_OPERATION : GL11.GL_INVALID_VALUE);
        return null;
    }

    private Shader shader(int id) {
        final Shader s = gl.programs().shader(id);
        if (s != null) return s;
        refuse(gl.programs().isProgram(id) ? GL11.GL_INVALID_OPERATION : GL11.GL_INVALID_VALUE);
        return null;
    }

    public boolean deleteProgram(int program) {
        return program == 0 || program(program) != null;
    }

    public boolean deleteShader(int shader) {
        return shader == 0 || shader(shader) != null;
    }

    public boolean linkProgram(int program) {
        return program(program) != null && anOperation(!GlTransformFeedback.capturedBy(program));
    }

    public boolean compileShader(int shader) {
        final Shader s = shader(shader);
        return s != null && anOperation(s.spirv == null);
    }

    public boolean useProgram(int program) {
        if (!anOperation(!GlTransformFeedback.active() || GlTransformFeedback.paused())) return false;
        if (program == 0) return true;
        final ProgramBase p = program(program);
        if (p == null || !anOperation(p.linked)) return false;
        if (p.usesSubroutines && program != GLSMPrograms.getActiveProgram()) ProgramInfo.resetSubroutines(program);
        return true;
    }

    public boolean attachShader(int program, int shader) {
        final ProgramBase p = program(program);
        if (p == null) return false;
        final Shader s = shader(shader);
        return s != null && anOperation(!p.attached.contains(shader));
    }

    public boolean detachShader(int program, int shader) {
        final ProgramBase p = program(program);
        if (p == null) return false;
        final Shader s = shader(shader);
        return s != null && anOperation(p.attached.contains(shader));
    }

    public boolean getUniformLocation(int program) {
        final ProgramBase p = program(program);
        return p != null && anOperation(p.linked);
    }

    private static final int KIND_FLOAT = 0, KIND_INT = 1;

    /** Uniform calls must agree with what the shader declared: size and type, except that a bool takes either. */
    private boolean uniform(int location, int components, int kind) {
        final ProgramBase p = gl.currentProgram();
        if (!anOperation(p != null && p.linked)) return false;
        if (location == -1) return false;
        final ProgramBase.Uniform u = uniformAt(p, location);
        if (!anOperation(u != null)) return false;
        final int type = u.glType;
        final boolean ok = switch (type) {
            case GL11.GL_FLOAT, GL11.GL_DOUBLE -> kind == KIND_FLOAT && components == 1;
            case GL20.GL_FLOAT_VEC2, GL40.GL_DOUBLE_VEC2 -> kind == KIND_FLOAT && components == 2;
            case GL20.GL_FLOAT_VEC3, GL40.GL_DOUBLE_VEC3 -> kind == KIND_FLOAT && components == 3;
            case GL20.GL_FLOAT_VEC4, GL40.GL_DOUBLE_VEC4 -> kind == KIND_FLOAT && components == 4;
            case GL11.GL_INT -> kind == KIND_INT && components == 1;
            case GL20.GL_INT_VEC2 -> kind == KIND_INT && components == 2;
            case GL20.GL_INT_VEC3 -> kind == KIND_INT && components == 3;
            case GL20.GL_INT_VEC4 -> kind == KIND_INT && components == 4;
            case GL11.GL_UNSIGNED_INT -> kind == KIND_INT && components == 1;
            case GL30.GL_UNSIGNED_INT_VEC2 -> kind == KIND_INT && components == 2;
            case GL30.GL_UNSIGNED_INT_VEC3 -> kind == KIND_INT && components == 3;
            case GL30.GL_UNSIGNED_INT_VEC4 -> kind == KIND_INT && components == 4;
            case GL20.GL_BOOL -> components == 1;
            case GL20.GL_BOOL_VEC2 -> components == 2;
            case GL20.GL_BOOL_VEC3 -> components == 3;
            case GL20.GL_BOOL_VEC4 -> components == 4;
            case GL20.GL_FLOAT_MAT2, GL20.GL_FLOAT_MAT3, GL20.GL_FLOAT_MAT4, GL21.GL_FLOAT_MAT2x3, GL21.GL_FLOAT_MAT2x4, GL21.GL_FLOAT_MAT3x2, GL21.GL_FLOAT_MAT3x4, GL21.GL_FLOAT_MAT4x2, GL21.GL_FLOAT_MAT4x3 -> false;
            // Everything else a uniform can be is a sampler or an image, which take one int.
            default -> kind == KIND_INT && components == 1;
        };
        return anOperation(ok);
    }

    private static ProgramBase.Uniform uniformAt(ProgramBase p, int location) {
        if (location < 0 || location >= p.locationUniform.length) return null;
        final int index = p.locationUniform[location];
        return index < 0 ? null : (ProgramBase.Uniform) p.uniforms.get(index);
    }

    private boolean matrix(int location, int type) {
        final ProgramBase p = gl.currentProgram();
        if (!anOperation(p != null && p.linked)) return false;
        if (location == -1) return false;
        final ProgramBase.Uniform u = uniformAt(p, location);
        return anOperation(u != null && u.glType == type);
    }

    public boolean uniform1f(int location) {
        return uniform(location, 1, KIND_FLOAT);
    }

    public boolean uniform2f(int location) {
        return uniform(location, 2, KIND_FLOAT);
    }

    public boolean uniform3f(int location) {
        return uniform(location, 3, KIND_FLOAT);
    }

    public boolean uniform4f(int location) {
        return uniform(location, 4, KIND_FLOAT);
    }

    public boolean uniform2(int location) {
        return uniform(location, 2, KIND_FLOAT);
    }

    public boolean uniform3(int location) {
        return uniform(location, 3, KIND_FLOAT);
    }

    public boolean uniform4(int location) {
        return uniform(location, 4, KIND_FLOAT);
    }

    public boolean uniform1i(int location, int v0) {
        if (!uniform(location, 1, KIND_INT)) return false;
        final ProgramBase.Uniform u = uniformAt(gl.currentProgram(), location);
        if (u.glType >= GL42.GL_IMAGE_1D && u.glType <= GL42.GL_UNSIGNED_INT_IMAGE_2D_MULTISAMPLE_ARRAY) return aValue(v0 >= 0 && v0 < gl.getInteger(GL42.GL_MAX_IMAGE_UNITS));
        return !isSamplerLike(u.glType) || aValue(v0 >= 0 && v0 < gl.getInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
    }

    private static boolean isSamplerLike(int type) {
        return switch (type) {
            case GL11.GL_INT, GL11.GL_UNSIGNED_INT, GL20.GL_BOOL -> false;
            default -> true;
        };
    }

    public boolean uniform2i(int location) {
        return uniform(location, 2, KIND_INT);
    }

    public boolean uniform3i(int location) {
        return uniform(location, 3, KIND_INT);
    }

    public boolean uniform4i(int location) {
        return uniform(location, 4, KIND_INT);
    }

    public boolean uniform1iv(int location) {
        return uniform(location, 1, KIND_INT);
    }

    public boolean uniform2iv(int location) {
        return uniform(location, 2, KIND_INT);
    }

    public boolean uniform3iv(int location) {
        return uniform(location, 3, KIND_INT);
    }

    public boolean uniform4iv(int location) {
        return uniform(location, 4, KIND_INT);
    }

    public boolean uniformMatrix3(int location) {
        return matrix(location, GL20.GL_FLOAT_MAT3);
    }

    public boolean uniformMatrix4(int location) {
        return matrix(location, GL20.GL_FLOAT_MAT4);
    }

    public boolean uniformProgramBound(int activeProgram) {
        return anOperation(activeProgram != 0 || GlPipelines.boundPipeline() != 0);
    }

    private boolean activeProgramForUniform() {
        return uniformProgramBound(GLSMPrograms.getActiveProgram());
    }

    public boolean glUniform1d(int location) {
        return doubleUniform(location, 1, GL11.GL_DOUBLE);
    }

    public boolean glUniform2d(int location) {
        return doubleUniform(location, 1, GL40.GL_DOUBLE_VEC2);
    }

    public boolean glUniform3d(int location) {
        return doubleUniform(location, 1, GL40.GL_DOUBLE_VEC3);
    }

    public boolean glUniform4d(int location) {
        return doubleUniform(location, 1, GL40.GL_DOUBLE_VEC4);
    }

    public boolean glUniform1ui(int location) {
        if (!activeProgramForUniform()) return false;
        if (location == -1) return true;
        final ProgramBase.Uniform u = uniformAt(gl.currentProgram(), location);
        return anOperation(u == null || !u.isOpaque());
    }

    public boolean glUniform2ui(int location) {
        return activeProgramForUniform();
    }

    public boolean glUniform3ui(int location) {
        return activeProgramForUniform();
    }

    public boolean glUniform4ui(int location) {
        return activeProgramForUniform();
    }

    public boolean uniformBlockBinding(int program, int blockIndex, int binding) {
        final ProgramBase p = program(program);
        return p != null && aValue(blockIndex >= 0 && blockIndex < p.ubos.size()) && aValue(binding >= 0 && binding < gl.getInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS));
    }

    public boolean validateProgram(int program) {
        return program(program) != null;
    }

    public boolean glProgramParameteri(int program, int pname, int value) {
        if (program(program) == null) return false;
        if (!anEnum(pname == GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT || pname == GL41.GL_PROGRAM_SEPARABLE
            || pname == 0x8DDA || pname == 0x8DDB || pname == 0x8DDC)) return false;
        return pname >= 0x8DDA || aValue(value == GL11.GL_FALSE || value == GL11.GL_TRUE);
    }

    public boolean bindRenderbuffer(int target) {
        return anEnum(target == GL30.GL_RENDERBUFFER);
    }

    public boolean renderbufferStorage(int target, int internalformat) {
        return anEnum(target == GL30.GL_RENDERBUFFER) && renderable(internalformat);
    }

    public boolean renderbufferStorageMultisample(int target, int samples, int internalformat) {
        return anEnum(target == GL30.GL_RENDERBUFFER) && renderable(internalformat) && aValue(samples >= 0) && samplesAllowed(samples) && integerSamplesAllowed(samples, internalformat);
    }

    public boolean generateMipmap(int target) {
        return anEnum(target == GL11.GL_TEXTURE_1D || target == GL11.GL_TEXTURE_2D || target == GL12.GL_TEXTURE_3D || target == GL30.GL_TEXTURE_1D_ARRAY
            || target == GL30.GL_TEXTURE_2D_ARRAY || target == GL13.GL_TEXTURE_CUBE_MAP || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
    }

    public boolean copyTexImage1D(int target, int level, int internalFormat, int width, int border) {
        return anEnum(target == GL11.GL_TEXTURE_1D && copyable(internalFormat)) && level(level) && aValue(width >= 0 && (border == 0 || border == 1)) && readableOneSample();
    }

    public boolean copyTexImage2D(int target, int level, int internalFormat, int width, int height, int border) {
        return anEnum(IMAGE_2D_TARGETS.contains(target) && copyable(internalFormat)) && level(level) && aValue(width >= 0 && height >= 0 && (border == 0 || border == 1)) && readableOneSample() && readableAs(internalFormat);
    }

    /** Copying into a depth or depth and stencil format reads those buffers, so the read framebuffer has to have them. */
    private boolean readableAs(int internalFormat) {
        if (!gl.textures().knownInternalFormat(internalFormat)) return true;
        final Formats.Format f = gl.textures().format(internalFormat);
        if (!f.isDepthOrStencil()) return true;
        final Fbo read = gl.fboFor(GL30.GL_READ_FRAMEBUFFER);
        if (read == null || read.id == 0) return true;
        return anOperation((!f.isDepth() || read.depthTex != 0) && (!f.isStencil() || read.stencilTex != 0));
    }

    private boolean namedCopy(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, java.util.function.IntPredicate targets) {
        return onTexture(texture, () -> {
            final TexBase t = namedTexture;
            if (!anOperation(targets.test(t.target)) || !aValue(level >= 0 && level < Math.max(1, t.levels) && width >= 0 && height >= 0)) return false;
            final int layers = t.is3D() ? t.levelDepth(level) : t.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : Math.max(1, t.layers);
            if (!aValue(xoffset >= 0 && yoffset >= 0 && zoffset >= 0 && (long) xoffset + width <= t.levelWidth(level) && (long) yoffset + height <= t.levelHeight(level) && zoffset < layers)) return false;
            return readableOneSample() && anOperation(gl.getInteger(GL11.GL_READ_BUFFER) != GL11.GL_NONE);
        });
    }

    public boolean glVertexArrayAttribLFormat(int vaobj, int attribindex, int size, int type, int relativeoffset) {
        return onVertexArray(vaobj, () -> attribLFormat(attribindex, size, type, relativeoffset));
    }

    /** Generic compressed formats hold plain texels here, but they were asked for as compressed ones. */
    private static boolean genericCompressed(int internalFormat) {
        return internalFormat == GL13.GL_COMPRESSED_RGBA || internalFormat == GL13.GL_COMPRESSED_RGB || internalFormat == GL30.GL_COMPRESSED_RED
            || internalFormat == GL30.GL_COMPRESSED_RG || internalFormat == GL21.GL_COMPRESSED_SRGB || internalFormat == GL21.GL_COMPRESSED_SRGB_ALPHA;
    }

    private boolean clearTexture(int texture, int level, int format, int type, java.util.function.Function<TexBase, Boolean> region) {
        if (!anOperation(texture != 0 && gl.textures().exists(texture))) return false;
        final TexBase t = gl.textures().get(texture);
        if (!anOperation(t != null && t.target != GL31.GL_TEXTURE_BUFFER && t.declaredTarget != GL31.GL_TEXTURE_BUFFER)) return false;
        if (!pixels(format, type) || !aValue(level >= 0)) return false;
        if (!anOperation(t.format != null && level < t.levels && !t.format.isCompressed() && !genericCompressed(t.format.glInternal()))) return false;
        final Formats.Format f = t.format;
        final boolean ok = switch (format) {
            case GL11.GL_DEPTH_COMPONENT -> f.isDepth();
            case GL30.GL_DEPTH_STENCIL -> f.isDepth() && f.isStencil();
            case GL11.GL_STENCIL_INDEX -> f.isStencil();
            default -> !f.isDepthOrStencil() && integerPixels(format) == f.isInteger();
        };
        return anOperation(ok) && region.apply(t);
    }

    /** GLSM's overloads reach the backend without the entry point's checks. */
    public boolean clearTexImage(int texture, int level, int format, int type) {
        return glClearTexImage(texture, level, format, type);
    }

    public boolean createTextures(int target) {
        return glCreateTextures(target, 1);
    }

    public boolean glClearTexImage(int texture, int level, int format, int type) {
        return clearTexture(texture, level, format, type, t -> true);
    }

    public boolean glClearTexSubImage(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type) {
        return clearTexture(texture, level, format, type, t -> {
            final int layers = t.is3D() ? t.levelDepth(level) : t.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : Math.max(1, t.layers);
            // A negative size is a value GL refuses; a region outside the image, an operation (GL 4.6 section 8.21).
            return aValue(width >= 0 && height >= 0 && depth >= 0) && anOperation(xoffset >= 0 && yoffset >= 0 && zoffset >= 0 && (long) xoffset + width <= t.levelWidth(level)
                && (long) yoffset + height <= t.levelHeight(level) && (long) zoffset + depth <= layers);
        });
    }

    public boolean glCopyTextureSubImage1D(int texture, int level, int xoffset, int x, int y, int width) {
        return namedCopy(texture, level, xoffset, 0, 0, width, 1, target -> target == GL11.GL_TEXTURE_1D);
    }

    public boolean glCopyTextureSubImage2D(int texture, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        return namedCopy(texture, level, xoffset, yoffset, 0, width, height, target -> target == GL11.GL_TEXTURE_2D || target == GL30.GL_TEXTURE_1D_ARRAY || target == GL31.GL_TEXTURE_RECTANGLE);
    }

    public boolean glCopyTextureSubImage3D(int texture, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height) {
        return namedCopy(texture, level, xoffset, yoffset, zoffset, width, height, target -> target == GL12.GL_TEXTURE_3D || target == GL30.GL_TEXTURE_2D_ARRAY || target == GL13.GL_TEXTURE_CUBE_MAP || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
    }

    /** The target GL was told for the named texture, which its parameters are checked against. */
    private int namedTarget() {
        return namedTexture.declaredTarget != 0 ? namedTexture.declaredTarget : namedTexture.target;
    }

    private boolean namedParameter(int pname, int value) {
        final int target = namedTarget();
        if (!anOperation(target != GL31.GL_TEXTURE_BUFFER)) return false;
        final boolean multisampled = target == GL32.GL_TEXTURE_2D_MULTISAMPLE || target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
        if (multisampled && !anOperation(!samplerStateOfMultisampled(target, pname) && (pname != GL12.GL_TEXTURE_BASE_LEVEL || value == 0))) return false;
        if (target != GL31.GL_TEXTURE_RECTANGLE) return true;
        if (pname == GL12.GL_TEXTURE_BASE_LEVEL) return anOperation(value == 0);
        if (pname == GL11.GL_TEXTURE_WRAP_S || pname == GL11.GL_TEXTURE_WRAP_T) return anEnum(value == GL12.GL_CLAMP_TO_EDGE || value == GL13.GL_CLAMP_TO_BORDER);
        return pname != GL11.GL_TEXTURE_MIN_FILTER || anEnum(value == GL11.GL_NEAREST || value == GL11.GL_LINEAR);
    }


    /** A format a copy can make: one the device has, or an unsized or generic one GL maps onto one. */
    private boolean copyable(int internalFormat) {
        return gl.textures().knownInternalFormat(internalFormat) || INTERNAL_FORMATS_WITHOUT_A_DEVICE_FORMAT.contains(internalFormat);
    }

    public boolean bindSampler(int unit, int sampler) {
        return aValue(unit >= 0 && unit < gl.getInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)) && (sampler == 0 || anOperation(gl.isSampler(sampler)));
    }

    public boolean samplerParameteri(int sampler, int pname, int param) {
        return aValue(gl.isSampler(sampler)) && anEnum(SAMPLER_PARAMETERS.contains(pname) && pname != GL11.GL_TEXTURE_BORDER_COLOR) && texParameterValue(pname, param);
    }

    public boolean samplerParameterf(int sampler, int pname, float param) {
        return aValue(gl.isSampler(sampler)) && anEnum(SAMPLER_PARAMETERS.contains(pname) && pname != GL11.GL_TEXTURE_BORDER_COLOR) && texParameterValue(pname, (int) param) && anisotropy(pname, param);
    }

    public boolean glSamplerParameterIiv(int sampler, int pname, long params) {
        return anOperation(gl.isSampler(sampler)) && anEnum(SAMPLER_PARAMETERS.contains(pname)) && texParameterValue(pname, MemoryUtil.memGetInt(params));
    }

    public boolean glSamplerParameterIuiv(int sampler, int pname, long params) {
        return glSamplerParameterIiv(sampler, pname, params);
    }

    public boolean enablei(int cap, int index) {
        return indexedCap(cap, index);
    }

    public boolean disablei(int cap, int index) {
        return indexedCap(cap, index);
    }

    public boolean isEnabledi(int cap, int index) {
        return indexedCap(cap, index);
    }

    private boolean indexedCap(int cap, int index) {
        return anEnum(cap(cap)) && (cap != GL11.GL_BLEND || aValue(index >= 0 && index < gl.getInteger(GL20.GL_MAX_DRAW_BUFFERS)))
            && (cap != GL11.GL_SCISSOR_TEST || aValue(index >= 0 && index < gl.getInteger(GL41.GL_MAX_VIEWPORTS)));
    }

    public boolean fenceSync(int condition, int flags) {
        return anEnum(condition == GL32.GL_SYNC_GPU_COMMANDS_COMPLETE) && aValue(flags == 0);
    }

    public boolean logicOp(int opcode) {
        return anEnum(opcode >= GL11.GL_CLEAR && opcode <= GL11.GL_SET);
    }

    public boolean provokingVertex(int provokeMode) {
        return anEnum(provokeMode == GL32.GL_FIRST_VERTEX_CONVENTION || provokeMode == GL32.GL_LAST_VERTEX_CONVENTION);
    }

    private boolean pointParameter(int pname, float param) {
        if (!anEnum(pname == GL14.GL_POINT_FADE_THRESHOLD_SIZE || pname == GL20.GL_POINT_SPRITE_COORD_ORIGIN || pname == GL14.GL_POINT_SIZE_MIN
            || pname == GL14.GL_POINT_SIZE_MAX || pname == GL14.GL_POINT_DISTANCE_ATTENUATION)) return false;
        if (pname == GL20.GL_POINT_SPRITE_COORD_ORIGIN) return anEnum(param == GL20.GL_LOWER_LEFT || param == GL20.GL_UPPER_LEFT);
        return aValue(param >= 0f);
    }

    public boolean pointParameteri(int pname, int param) {
        return pointParameter(pname, param);
    }

    public boolean pointParameterf(int pname, float param) {
        return pointParameter(pname, param);
    }

    public boolean stencilMaskSeparate(int face) {
        return anEnum(face(face));
    }

    private boolean drawBufferIndex(int index) {
        return aValue(index >= 0 && index < gl.getInteger(GL20.GL_MAX_DRAW_BUFFERS));
    }

    public boolean blendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        return drawBufferIndex(buf) && blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    public boolean glBlendFunci(int buf, int src, int dst) {
        return drawBufferIndex(buf) && blendFunc(src, dst);
    }

    public boolean glBlendEquationi(int buf, int mode) {
        return drawBufferIndex(buf) && blendEquation(mode);
    }

    public boolean glBlendEquationSeparatei(int buf, int modeRGB, int modeAlpha) {
        return drawBufferIndex(buf) && blendEquationSeparate(modeRGB, modeAlpha);
    }

    public boolean glColorMaski(int buf) {
        return drawBufferIndex(buf);
    }

    public boolean glClearBufferfi(int buffer, int drawbuffer) {
        return anEnum(buffer == GL30.GL_DEPTH_STENCIL) && aValue(drawbuffer == 0);
    }

    public boolean glClampColor(int target, int clamp) {
        return anEnum(target == GL30.GL_CLAMP_READ_COLOR || target == 0x891A || target == 0x891B)
            && anEnum(clamp == GL11.GL_TRUE || clamp == GL11.GL_FALSE || clamp == GL30.GL_FIXED_ONLY);
    }

    public boolean glDepthRangeIndexed(int index) {
        return viewportIndex(index);
    }

    public boolean glScissorIndexed(int index, int x, int y, int width, int height) {
        return viewportIndex(index) && aValue(width >= 0 && height >= 0);
    }

    public boolean glViewportIndexedf(int index, float x, float y, float width, float height) {
        return viewportIndex(index) && aValue(width >= 0f && height >= 0f);
    }

    private boolean viewportIndex(int index) {
        return aValue(index >= 0 && index < gl.getInteger(GL41.GL_MAX_VIEWPORTS));
    }

    public boolean glSampleMaski(int maskNumber) {
        return aValue(maskNumber >= 0 && maskNumber < gl.getInteger(GL32.GL_MAX_SAMPLE_MASK_WORDS));
    }

    public boolean glPatchParameteri(int pname, int value) {
        // 32 is the least GL_MAX_PATCH_VERTICES GL allows.
        return anEnum(pname == GL40.GL_PATCH_VERTICES) && aValue(value > 0 && value <= Math.max(32, gl.getInteger(GL40.GL_MAX_PATCH_VERTICES)));
    }

    public boolean glTexBuffer(int target, int internalFormat, int buffer) {
        return anEnum(target == GL31.GL_TEXTURE_BUFFER && gl.textures().knownInternalFormat(internalFormat)) && (buffer == 0 || anOperation(gl.buffers().exists(buffer)));
    }

    public boolean glTexImage2DMultisample(int target, int samples, int internalFormat, int width, int height) {
        return anEnum(target == GL32.GL_TEXTURE_2D_MULTISAMPLE || target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE) && renderable(internalFormat) && multisampleSize(samples, width, height, 1) && integerSamplesAllowed(samples, internalFormat);
    }

    private boolean samplesAllowed(int samples) {
        return anOperation(samples <= gl.getInteger(GL30.GL_MAX_SAMPLES));
    }

    private boolean integerSamplesAllowed(int samples, int internalFormat) {
        final Formats.Format f = gl.textures().knownInternalFormat(internalFormat) ? gl.textures().format(internalFormat) : null;
        return f == null || !f.isInteger() || anOperation(samples <= gl.getInteger(GL33.GL_MAX_INTEGER_SAMPLES));
    }

    private boolean multisampleSize(int samples, int width, int height, int depth) {
        final int max = gl.getInteger(GL11.GL_MAX_TEXTURE_SIZE);
        return aValue(samples >= 1 && width >= 0 && height >= 0 && depth >= 0 && width <= max && height <= max && depth <= gl.getInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS)) && samplesAllowed(samples);
    }

    public boolean glTransformFeedbackVaryings(int program, int count, long varyings, int bufferMode) {
        if (!(aValue(count >= 0) && program(program) != null && anEnum(bufferMode == GL30.GL_INTERLEAVED_ATTRIBS || bufferMode == GL30.GL_SEPARATE_ATTRIBS))) return false;
        int nextBuffers = 0;
        for (int i = 0; i < count; i++) {
            final String name = MemoryUtil.memUTF8(MemoryUtil.memGetAddress(varyings + i * (long) org.lwjgl.system.Pointer.POINTER_SIZE));
            final boolean special = name.equals("gl_NextBuffer") || name.startsWith("gl_SkipComponents");
            if (!anOperation(!special || bufferMode == GL30.GL_INTERLEAVED_ATTRIBS)) return false;
            if (name.equals("gl_NextBuffer")) nextBuffers++;
        }
        if (!anOperation(nextBuffers < gl.getInteger(GL40.GL_MAX_TRANSFORM_FEEDBACK_BUFFERS))) return false;
        return aValue(bufferMode != GL30.GL_SEPARATE_ATTRIBS || count <= gl.getInteger(GL30.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS));
    }

    public boolean glTexImage3DMultisample(int target, int samples, int internalFormat, int width, int height, int depth) {
        return anEnum(target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY || target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE_ARRAY) && renderable(internalFormat) && multisampleSize(samples, width, height, depth) && integerSamplesAllowed(samples, internalFormat);
    }

    private boolean packedAttribute(int index, int type) {
        return attribIndex(index) && anEnum(type == GL33.GL_INT_2_10_10_10_REV || type == GL12.GL_UNSIGNED_INT_2_10_10_10_REV || type == GL30.GL_UNSIGNED_INT_10F_11F_11F_REV);
    }

    /** The only packed layout with three components can only fill three. */
    private boolean packedAttributeOfFour(int index, int type) {
        return anEnum(type != GL30.GL_UNSIGNED_INT_10F_11F_11F_REV) && packedAttribute(index, type);
    }

    public boolean glVertexAttribP1ui(int index, int type) {
        // Drivers take the three-component layout for fewer components too, and refuse it only for four.
        return packedAttribute(index, type);
    }

    public boolean glVertexAttribP2ui(int index, int type) {
        // Drivers take the three-component layout for fewer components too, and refuse it only for four.
        return packedAttribute(index, type);
    }

    public boolean glVertexAttribP3ui(int index, int type) {
        return packedAttribute(index, type);
    }

    public boolean glVertexAttribP4ui(int index, int type) {
        return packedAttributeOfFour(index, type);
    }

    public boolean glEndQueryIndexed(int target, int index) {
        return anEnum(QUERY_TARGETS.contains(target)) && queryIndex(target, index) && anOperation(gl.queryActive(GlBase.queryKey(target, index)));
    }

    public boolean glBeginConditionalRender(int id, int mode) {
        // Zero names no query object either.
        if (id == 0 || !gl.queryResults.containsKey(id)) return refuse(GL11.GL_INVALID_VALUE);
        return anOperation(!gl.st().conditionalRender) && anEnum(mode >= GL30.GL_QUERY_WAIT && mode <= GL30.GL_QUERY_BY_REGION_NO_WAIT || mode >= 0x8E17 && mode <= 0x8E1A);
    }

    public boolean glEndConditionalRender() {
        return anOperation(gl.st().conditionalRender);
    }

    public boolean glBeginTransformFeedback(int primitiveMode) {
        if (!anEnum(primitiveMode == GL11.GL_POINTS || primitiveMode == GL11.GL_LINES || primitiveMode == GL11.GL_TRIANGLES)) return false;
        return anOperation(!GlTransformFeedback.active() && (GLSMPrograms.getActiveProgram() != 0 || GlPipelines.boundPipeline() != 0)
            && GlTransformFeedback.canCapture(GLSMPrograms.getActiveProgram()));
    }

    public boolean glEndTransformFeedback() {
        return anOperation(GlTransformFeedback.active());
    }

    public boolean glPauseTransformFeedback() {
        return anOperation(GlTransformFeedback.active() && !GlTransformFeedback.paused());
    }

    public boolean glResumeTransformFeedback() {
        return anOperation(GlTransformFeedback.active() && GlTransformFeedback.paused());
    }

    public boolean glBindTransformFeedback(int target, int id) {
        return anEnum(target == GL40.GL_TRANSFORM_FEEDBACK) && anOperation(id == 0 || GlTransformFeedback.known(id))
            && anOperation(!GlTransformFeedback.active() || GlTransformFeedback.paused());
    }

    public boolean glDrawTransformFeedback(int mode, int id) {
        // Zero names the default transform feedback object, which a draw can take its count from too.
        return primitive(mode) && aValue(id == 0 || GlTransformFeedback.exists(id)) && anOperation(GlTransformFeedback.hasEnded(id));
    }

    public boolean glDrawTransformFeedbackStream(int mode, int id, int stream) {
        return primitive(mode) && aValue(id == 0 || GlTransformFeedback.exists(id)) && aValue(stream >= 0 && stream < gl.getInteger(GL40.GL_MAX_VERTEX_STREAMS)) && anOperation(GlTransformFeedback.hasEnded(id));
    }

    public boolean glDrawTransformFeedbackInstanced(int mode, int id) {
        return anEnum(mode != GL11.GL_QUAD_STRIP && mode != GL11.GL_POLYGON) && glDrawTransformFeedback(mode, id);
    }

    public boolean glDrawTransformFeedbackStreamInstanced(int mode, int id, int stream) {
        return anEnum(mode != GL11.GL_QUAD_STRIP && mode != GL11.GL_POLYGON) && glDrawTransformFeedbackStream(mode, id, stream);
    }

    public boolean glUseProgramStages(int pipeline, int stages, int program) {
        if (!anOperation(GlPipelines.known(pipeline))) return false;
        if (!aValue(stages == GL41.GL_ALL_SHADER_BITS || (stages & ~(GL41.GL_VERTEX_SHADER_BIT | GL41.GL_FRAGMENT_SHADER_BIT | GL41.GL_GEOMETRY_SHADER_BIT | GL41.GL_TESS_CONTROL_SHADER_BIT | GL41.GL_TESS_EVALUATION_SHADER_BIT | GL43.GL_COMPUTE_SHADER_BIT)) == 0)) return false;
        if (program == 0) return true;
        final ProgramBase<?> p = program(program);
        return p != null && anOperation(p.linked && p.linkedSeparable);
    }

    public boolean glActiveShaderProgram(int pipeline, int program) {
        return anOperation(GlPipelines.known(pipeline)) && (program == 0 || program(program) != null);
    }

    public boolean glBindProgramPipeline(int pipeline) {
        return anOperation(pipeline == 0 || GlPipelines.known(pipeline));
    }

    public boolean glValidateProgramPipeline(int pipeline) {
        return anOperation(GlPipelines.known(pipeline));
    }

    private static final IntOpenHashSet BUFFER_QUERIES = set(GL46C.GL_BUFFER_ACCESS, GL46C.GL_BUFFER_ACCESS_FLAGS, GL46C.GL_BUFFER_IMMUTABLE_STORAGE, GL46C.GL_BUFFER_MAPPED, GL46C.GL_BUFFER_MAP_LENGTH, GL46C.GL_BUFFER_MAP_OFFSET,
        GL46C.GL_BUFFER_SIZE, GL46C.GL_BUFFER_STORAGE_FLAGS, GL46C.GL_BUFFER_USAGE);
    private static final IntOpenHashSet ATTRIB_QUERIES = set(GL46C.GL_VERTEX_ATTRIB_ARRAY_ENABLED, GL46C.GL_VERTEX_ATTRIB_ARRAY_SIZE, GL46C.GL_VERTEX_ATTRIB_ARRAY_STRIDE, GL46C.GL_VERTEX_ATTRIB_ARRAY_TYPE, GL46C.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED,
        GL46C.GL_VERTEX_ATTRIB_ARRAY_INTEGER, GL46C.GL_VERTEX_ATTRIB_ARRAY_LONG, GL46C.GL_VERTEX_ATTRIB_ARRAY_DIVISOR, GL46C.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING, GL46C.GL_CURRENT_VERTEX_ATTRIB, GL46C.GL_VERTEX_ATTRIB_BINDING, GL46C.GL_VERTEX_ATTRIB_RELATIVE_OFFSET);
    private static final IntOpenHashSet PROGRAM_QUERIES = set(ProgramsBase.COMPLETION_STATUS, GL46C.GL_DELETE_STATUS, GL46C.GL_LINK_STATUS, GL46C.GL_VALIDATE_STATUS, GL46C.GL_INFO_LOG_LENGTH, GL46C.GL_ATTACHED_SHADERS, GL46C.GL_ACTIVE_ATOMIC_COUNTER_BUFFERS, GL46C.GL_ACTIVE_ATTRIBUTES,
        GL46C.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH, GL46C.GL_ACTIVE_UNIFORMS, GL46C.GL_ACTIVE_UNIFORM_BLOCKS, GL46C.GL_ACTIVE_UNIFORM_BLOCK_MAX_NAME_LENGTH, GL46C.GL_ACTIVE_UNIFORM_MAX_LENGTH, GL46C.GL_COMPUTE_WORK_GROUP_SIZE, GL46C.GL_PROGRAM_BINARY_LENGTH,
        GL46C.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL46C.GL_PROGRAM_SEPARABLE, GL46C.GL_TRANSFORM_FEEDBACK_BUFFER_MODE, GL46C.GL_TRANSFORM_FEEDBACK_VARYINGS, GL46C.GL_TRANSFORM_FEEDBACK_VARYING_MAX_LENGTH, GL46C.GL_GEOMETRY_VERTICES_OUT, GL46C.GL_GEOMETRY_INPUT_TYPE,
        GL46C.GL_GEOMETRY_OUTPUT_TYPE, ProgramsBase.GEOMETRY_LINKED_VERTICES_OUT, ProgramsBase.GEOMETRY_LINKED_INPUT_TYPE, ProgramsBase.GEOMETRY_LINKED_OUTPUT_TYPE, GL46C.GL_GEOMETRY_SHADER_INVOCATIONS, GL46C.GL_TESS_CONTROL_OUTPUT_VERTICES, GL46C.GL_TESS_GEN_MODE, GL46C.GL_TESS_GEN_SPACING, GL46C.GL_TESS_GEN_VERTEX_ORDER, GL46C.GL_TESS_GEN_POINT_MODE);
    private static final IntOpenHashSet SHADER_QUERIES = set(ProgramsBase.COMPLETION_STATUS, GL46C.GL_SHADER_TYPE, GL46C.GL_DELETE_STATUS, GL46C.GL_COMPILE_STATUS, GL46C.GL_INFO_LOG_LENGTH, GL46C.GL_SHADER_SOURCE_LENGTH, GL46C.GL_SPIR_V_BINARY);
    private static final IntOpenHashSet ACTIVE_UNIFORM_QUERIES = set(GL46C.GL_UNIFORM_TYPE, GL46C.GL_UNIFORM_SIZE, GL46C.GL_UNIFORM_NAME_LENGTH, GL46C.GL_UNIFORM_BLOCK_INDEX, GL46C.GL_UNIFORM_OFFSET, GL46C.GL_UNIFORM_ARRAY_STRIDE, GL46C.GL_UNIFORM_MATRIX_STRIDE,
        GL46C.GL_UNIFORM_IS_ROW_MAJOR, GL46C.GL_UNIFORM_ATOMIC_COUNTER_BUFFER_INDEX);
    private static final IntOpenHashSet BLOCK_QUERIES = set(GL46C.GL_UNIFORM_BLOCK_BINDING, GL46C.GL_UNIFORM_BLOCK_DATA_SIZE, GL46C.GL_UNIFORM_BLOCK_NAME_LENGTH, GL46C.GL_UNIFORM_BLOCK_ACTIVE_UNIFORMS, GL46C.GL_UNIFORM_BLOCK_ACTIVE_UNIFORM_INDICES,
        GL46C.GL_UNIFORM_BLOCK_REFERENCED_BY_VERTEX_SHADER, GL46C.GL_UNIFORM_BLOCK_REFERENCED_BY_TESS_CONTROL_SHADER, GL46C.GL_UNIFORM_BLOCK_REFERENCED_BY_TESS_EVALUATION_SHADER, GL46C.GL_UNIFORM_BLOCK_REFERENCED_BY_GEOMETRY_SHADER,
        GL46C.GL_UNIFORM_BLOCK_REFERENCED_BY_FRAGMENT_SHADER, GL46C.GL_UNIFORM_BLOCK_REFERENCED_BY_COMPUTE_SHADER);
    private static final IntOpenHashSet STAGE_QUERIES = set(GL46C.GL_ACTIVE_SUBROUTINE_UNIFORMS, GL46C.GL_ACTIVE_SUBROUTINE_UNIFORM_LOCATIONS, GL46C.GL_ACTIVE_SUBROUTINES, GL46C.GL_ACTIVE_SUBROUTINE_MAX_LENGTH, GL46C.GL_ACTIVE_SUBROUTINE_UNIFORM_MAX_LENGTH);
    private static final IntOpenHashSet SUBROUTINE_QUERIES = set(GL46C.GL_NUM_COMPATIBLE_SUBROUTINES, GL46C.GL_COMPATIBLE_SUBROUTINES, GL46C.GL_UNIFORM_SIZE, GL46C.GL_UNIFORM_NAME_LENGTH);
    private static final IntOpenHashSet PIPELINE_QUERIES = set(GL46C.GL_ACTIVE_PROGRAM, GL46C.GL_VALIDATE_STATUS, GL46C.GL_VERTEX_SHADER, GL46C.GL_TESS_CONTROL_SHADER, GL46C.GL_TESS_EVALUATION_SHADER, GL46C.GL_GEOMETRY_SHADER, GL46C.GL_FRAGMENT_SHADER,
        GL46C.GL_COMPUTE_SHADER, GL46C.GL_INFO_LOG_LENGTH);
    private static final IntOpenHashSet SYNC_QUERIES = set(GL46C.GL_OBJECT_TYPE, GL46C.GL_SYNC_STATUS, GL46C.GL_SYNC_CONDITION, GL46C.GL_SYNC_FLAGS);
    private static final IntOpenHashSet ATTACHMENT_QUERIES = set(GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, GL46C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, GL46C.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE, GL46C.GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE,
        GL46C.GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE, GL46C.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE, GL46C.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE, GL46C.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE, GL46C.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE,
        GL46C.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE, GL46C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER, GL46C.GL_FRAMEBUFFER_ATTACHMENT_LAYERED);
    private static final IntOpenHashSet RENDERBUFFER_QUERIES = set(GL46C.GL_RENDERBUFFER_WIDTH, GL46C.GL_RENDERBUFFER_HEIGHT, GL46C.GL_RENDERBUFFER_INTERNAL_FORMAT, GL46C.GL_RENDERBUFFER_RED_SIZE, GL46C.GL_RENDERBUFFER_GREEN_SIZE, GL46C.GL_RENDERBUFFER_BLUE_SIZE,
        GL46C.GL_RENDERBUFFER_ALPHA_SIZE, GL46C.GL_RENDERBUFFER_DEPTH_SIZE, GL46C.GL_RENDERBUFFER_STENCIL_SIZE, GL46C.GL_RENDERBUFFER_SAMPLES);
    private static final IntOpenHashSet TEX_QUERIES_ONLY = set(GL46C.GL_TEXTURE_IMMUTABLE_FORMAT, GL46C.GL_TEXTURE_IMMUTABLE_LEVELS, GL46C.GL_IMAGE_FORMAT_COMPATIBILITY_TYPE, GL46C.GL_TEXTURE_VIEW_MIN_LEVEL, GL46C.GL_TEXTURE_VIEW_NUM_LEVELS,
        GL46C.GL_TEXTURE_VIEW_MIN_LAYER, GL46C.GL_TEXTURE_VIEW_NUM_LAYERS, GL46C.GL_TEXTURE_TARGET, GL11.GL_TEXTURE_RESIDENT);
    private static final IntOpenHashSet COMPRESSED_2D_TARGETS = set(GL11.GL_TEXTURE_2D, GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_X, GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Y, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
        GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Z, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z);
    private static final int ALL_MAP_BITS = GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_RANGE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT | GL30.GL_MAP_FLUSH_EXPLICIT_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT | MAP_PERSISTENT_BIT | 0x80;

    /** What the source declared has to be the double type a call names, though the shader holds it as floats. */
    private boolean doubleUniform(int location, int count, int type) {
        if (!activeProgramForUniform()) return false;
        final ProgramBase<?> p = gl.currentProgram();
        if (!uniformArray(p, location, count) || location == -1) return false;
        final ProgramBase.Uniform u = uniformAt(p, location);
        return anOperation(u != null && u.declaredDouble == type);
    }

    private boolean uniformArray(ProgramBase<?> p, int location, int count) {
        if (!aValue(count >= 0) || !anOperation(p != null && p.linked) || location == -1) return false;
        final ProgramBase.Uniform u = uniformAt(p, location);
        return anOperation(u != null && (count <= 1 || u.arraySize > 1));
    }

    private boolean uniformRead(int program, int location) {
        final ProgramBase<?> p = program(program);
        if (p == null) return refuse(program != 0 && gl.isShader(program) ? GL11.GL_INVALID_OPERATION : GL11.GL_INVALID_VALUE);
        return anOperation(p.linked && uniformAt(p, location) != null);
    }

    private boolean linkedProgram(int program) {
        final ProgramBase<?> p = program(program);
        return p != null && anOperation(p.linked);
    }

    private boolean tessellating() {
        if (GlPipelines.boundPipeline() != 0) return true;
        final ProgramBase<?> p = gl.currentProgram();
        return p != null && (p.stageModes.tessEvaluation || p.stageModes.tessControl);
    }

    private boolean primitive(int mode) {
        return anEnum(drawMode(mode)) && anOperation(mode != GL40.GL_PATCHES || tessellating()) && anOperation(fitsTheStages(mode)) && anOperation(fitsTheCapture(mode));
    }

    /** With nothing after the vertex stage to change them, what is drawn has to be what the capture began with. */
    private boolean fitsTheCapture(int mode) {
        if (!GlTransformFeedback.capturing()) return true;
        final ProgramBase<?> p = gl.currentProgram();
        if (p == null || p.geometry != null) return true;
        return switch (GlTransformFeedback.capturedMode()) {
            case GL11.GL_POINTS -> mode == GL11.GL_POINTS;
            case GL11.GL_LINES -> mode == GL11.GL_LINES || mode == GL11.GL_LINE_LOOP || mode == GL11.GL_LINE_STRIP;
            case GL11.GL_TRIANGLES -> mode >= GL11.GL_TRIANGLES && mode <= GL11.GL_POLYGON;
            default -> true;
        };
    }

    /** What is drawn has to be what the first of tessellation or geometry shading takes. */
    private boolean fitsTheStages(int mode) {
        if (GlPipelines.boundPipeline() != 0) return true;
        final ProgramBase<?> p = gl.currentProgram();
        if (p == null || !p.linked) return true;
        final StageModes stages = p.stageModes;
        if (stages.tessEvaluation) return mode == GL40.GL_PATCHES;
        if (!stages.geometry) return true;
        return switch (stages.geometryInput) {
            case GL11.GL_POINTS -> mode == GL11.GL_POINTS;
            case GL11.GL_LINES -> mode == GL11.GL_LINES || mode == GL11.GL_LINE_STRIP || mode == GL11.GL_LINE_LOOP;
            case GL32.GL_LINES_ADJACENCY -> mode == GL32.GL_LINES_ADJACENCY || mode == GL32.GL_LINE_STRIP_ADJACENCY;
            case GL32.GL_TRIANGLES_ADJACENCY -> mode == GL32.GL_TRIANGLES_ADJACENCY || mode == GL32.GL_TRIANGLE_STRIP_ADJACENCY;
            case GL11.GL_TRIANGLES -> mode == GL11.GL_TRIANGLES || mode == GL11.GL_TRIANGLE_STRIP || mode == GL11.GL_TRIANGLE_FAN;
            default -> true;
        };
    }

    private TexBase boundTo(int target) {
        return namedTexture != null ? namedTexture : gl.textures().get(gl.st().boundTexture(target));
    }

    private static boolean integerPixels(int format) {
        return format == GL30.GL_RED_INTEGER || format == GL30.GL_GREEN_INTEGER || format == GL30.GL_BLUE_INTEGER || format == GL30.GL_RG_INTEGER || format == GL30.GL_RGB_INTEGER
            || format == GL30.GL_BGR_INTEGER || format == GL30.GL_RGBA_INTEGER || format == GL30.GL_BGRA_INTEGER;
    }

    /** Uploading stencil indices takes an image of stencil alone, where reading them also works from a packed image. */
    private boolean pixelsUpload(Formats.Format f, int format) {
        return pixelsFit(f, format) && anOperation(f == null || format != GL11.GL_STENCIL_INDEX || f.glInternal() == GL30.GL_STENCIL_INDEX8 || f.glInternal() == GL11.GL_STENCIL_INDEX);
    }

    private boolean pixelsFit(Formats.Format f, int format) {
        if (f == null) return true;
        final boolean depthPixels = format == GL11.GL_DEPTH_COMPONENT || format == GL30.GL_DEPTH_STENCIL, stencilPixels = format == GL11.GL_STENCIL_INDEX;
        if (stencilPixels) return anOperation(f.isStencil());
        return anOperation(f.isInteger() == integerPixels(format) && f.isDepth() == depthPixels);
    }

    private boolean internalFormat(int internalFormat, int format) {
        return aValue(gl.textures().knownInternalFormat(internalFormat) || INTERNAL_FORMATS_WITHOUT_A_DEVICE_FORMAT.contains(internalFormat)) && pixelsUpload(gl.textures().format(internalFormat), format);
    }

    private boolean renderable(int internalFormat) {
        return anEnum(gl.textures().knownInternalFormat(internalFormat) || INTERNAL_FORMATS_WITHOUT_A_DEVICE_FORMAT.contains(internalFormat));
    }

    public boolean texImage1D(int target, int level, int internalFormat, int width, int border, int format, int type) {
        final boolean proxy = target == GL11.GL_PROXY_TEXTURE_1D;
        if (!anEnum(proxy || target == GL11.GL_TEXTURE_1D) || !pixels(format, type)) return false;
        return proxy || level(level) && aValue(width >= 0 && (border == 0 || border == 1)) && internalFormat(internalFormat, format);
    }

    public boolean texImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type) {
        final boolean proxy = target == GL12.GL_PROXY_TEXTURE_3D || target == GL30.GL_PROXY_TEXTURE_2D_ARRAY || target == GL40.GL_PROXY_TEXTURE_CUBE_MAP_ARRAY;
        if (!anEnum(proxy || target == GL12.GL_TEXTURE_3D || target == GL30.GL_TEXTURE_2D_ARRAY || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY) || !pixels(format, type)) return false;
        // Drivers refuse a border on a 3D image even in a compatibility profile.
        return proxy || level(level) && aValue(width >= 0 && height >= 0 && depth >= 0 && (border == 0 || border == 1)) && cubeArrayShape(target, width, height, depth) && internalFormat(internalFormat, format)
            && fitsThreeDimensions(target, internalFormat) && aValue(border == 0);
    }

    private boolean cubeArrayShape(int target, int width, int height, int depth) {
        return aValue(target != GL40.GL_TEXTURE_CUBE_MAP_ARRAY || width == height && depth % 6 == 0);
    }

    /** A 3D texture holds neither depth nor stencil, and of the compressed formats only BPTC, whose blocks are defined for it. */
    private boolean fitsThreeDimensions(int target, int internalFormat) {
        if (target != GL12.GL_TEXTURE_3D && target != GL12.GL_PROXY_TEXTURE_3D || !gl.textures().knownInternalFormat(internalFormat)) return true;
        final Formats.Format f = gl.textures().format(internalFormat);
        return anOperation(!f.isDepthOrStencil() && (!f.isCompressed() || hasVolumeBlocks(f)));
    }

    /** BPTC, BC6H and BC7 alike. */
    private static boolean hasVolumeBlocks(Formats.Format f) {
        return f.pf() >= PixelFormats.PF_BC6H_SFLOAT && f.pf() <= PixelFormats.PF_BC7_RGBA_SRGB;
    }

    private boolean subImage(int target, int level, int width, int height, int depth, int format, int type) {
        if (!pixels(format, type) || !level(level) || !aValue(width >= 0 && height >= 0 && depth >= 0)) return false;
        final TexBase t = boundTo(target);
        return t == null || pixelsUpload(t.format, format);
    }

    public boolean texSubImage1D(int target, int level, int xoffset, int width, int format, int type) {
        return anEnum(target == GL11.GL_TEXTURE_1D) && subImage(target, level, width, 1, 1, format, type);
    }

    public boolean texSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type) {
        return anEnum(target == GL12.GL_TEXTURE_3D || target == GL30.GL_TEXTURE_2D_ARRAY || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY) && subImage(target, level, width, height, depth, format, type);
    }

    private boolean copyRange(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height) {
        if (!level(level) || !aValue(xoffset >= 0 && yoffset >= 0 && zoffset >= 0 && width >= 0 && height >= 0)) return false;
        final TexBase t = boundTo(target);
        if (!anOperation(t != null && t.hasImage() && level < t.levels)) return false;
        final int slices = t.is3D() ? t.levelDepth(level) : t.layers;
        return aValue((long) xoffset + width <= t.levelWidth(level) && (long) yoffset + height <= (target == GL30.GL_TEXTURE_1D_ARRAY ? t.layers : t.levelHeight(level)) && zoffset <= slices);
    }

    public boolean copyTexSubImage1D(int target, int level, int xoffset, int width) {
        return anEnum(target == GL11.GL_TEXTURE_1D) && copyRange(target, level, xoffset, 0, 0, width, 1) && readableOneSample();
    }

    public boolean copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height) {
        return anEnum(IMAGE_2D_TARGETS.contains(target)) && copyRange(target, level, xoffset, yoffset, 0, width, height) && readableOneSample();
    }

    public boolean copyTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height) {
        return anEnum(target == GL12.GL_TEXTURE_3D || target == GL30.GL_TEXTURE_2D_ARRAY || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY) && copyRange(target, level, xoffset, yoffset, zoffset, width, height) && readableOneSample();
    }

    public boolean glCompressedTexImage1D(int target) {
        // No compressed format has a one dimensional form.
        return anEnum(false);
    }

    public boolean glCompressedTexSubImage1D(int target) {
        return anEnum(false);
    }

    private boolean compressedImage(Formats.Format f, int level, int width, int height, int depth, int border, int imageSize) {
        return anEnum(f != null && f.isCompressed()) && level(level) && aValue(width >= 0 && height >= 0 && depth >= 0 && border == 0)
            && aValue(imageSize == Formats.imageBytes(f, width, height) * depth);
    }

    public boolean glCompressedTexImage2D(int target, int level, int internalformat, int width, int height, int border, int imageSize) {
        return anEnum(COMPRESSED_2D_TARGETS.contains(target) || target == GL11.GL_PROXY_TEXTURE_2D || target == GL13.GL_PROXY_TEXTURE_CUBE_MAP)
            && compressedImage(gl.textures().format(internalformat), level, width, height, 1, border, imageSize);
    }

    public boolean glCompressedTexImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int imageSize) {
        final boolean volume = target == GL12.GL_TEXTURE_3D || target == GL12.GL_PROXY_TEXTURE_3D;
        final Formats.Format f = gl.textures().format(internalformat);
        // A volume target is one GL takes; only BPTC has a form for volumes, and another format there is an operation GL refuses.
        return anEnum(volume || target == GL30.GL_TEXTURE_2D_ARRAY || target == GL30.GL_PROXY_TEXTURE_2D_ARRAY || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY || target == GL40.GL_PROXY_TEXTURE_CUBE_MAP_ARRAY)
            && anEnum(f != null && f.isCompressed())
            && anOperation(!volume || hasVolumeBlocks(f))
            && compressedImage(f, level, width, height, depth, border, imageSize);
    }

    private static boolean oneDimensional(int target) {
        return target == GL11.GL_TEXTURE_1D;
    }

    /** A target whose images have one layer, which a region has to stay in. */
    private static boolean flat(int target) {
        return target == GL11.GL_TEXTURE_1D || target == GL11.GL_TEXTURE_2D || target == GL30.GL_TEXTURE_1D_ARRAY || target == GL31.GL_TEXTURE_RECTANGLE;
    }

    private boolean compressedSubImage(int target, int level, int xoffset, int yoffset, int width, int height, int depth, int format, int imageSize) {
        final Formats.Format f = gl.textures().format(format);
        // A format that is not the image's own is an operation GL refuses, as drivers have it, whether or not it names a format.
        if (!level(level) || !anOperation(f != null && f.isCompressed())) return false;
        final TexBase t = boundTo(target);
        if (!anOperation(t != null && t.hasImage() && level < t.levels && t.format != null && t.format.pf() == f.pf())) return false;
        if (!aValue(width >= 0 && height >= 0 && depth >= 0 && imageSize == Formats.imageBytes(f, width, height) * depth)) return false;
        // Blocks are written whole, so a region either covers whole blocks or runs to the image's edge.
        return anOperation((width % 4 == 0 || (long) xoffset + width == t.levelWidth(level)) && (height % 4 == 0 || (long) yoffset + height == t.levelHeight(level)));
    }

    public boolean glCompressedTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int imageSize) {
        return anEnum(COMPRESSED_2D_TARGETS.contains(target)) && compressedSubImage(target, level, xoffset, yoffset, width, height, 1, format, imageSize);
    }

    public boolean glCompressedTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int imageSize) {
        return anEnum(target == GL12.GL_TEXTURE_3D || target == GL30.GL_TEXTURE_2D_ARRAY || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY) && compressedSubImage(target, level, xoffset, yoffset, width, height, depth, format, imageSize);
    }

    public boolean glGetCompressedTexImage(int target, int level) {
        if (!anEnum(GlState.targetIndex(target) >= 0) || !level(level)) return false;
        final TexBase t = boundTo(target);
        return anOperation(t != null && t.format != null && t.format.isCompressed() && level < Math.max(1, t.levels));
    }

    public boolean glGetTexImage(int target, int level, int format, int type) {
        if (!anEnum(GlState.targetIndex(target) >= 0 && !multisampleTarget(target)) || !pixels(format, type) || !level(level)) return false;
        if (!anOperation(format != GL30.GL_DEPTH_STENCIL || PACKED_DEPTH.contains(type))) return false;
        final TexBase t = boundTo(target);
        return t == null || pixelsFit(t.format, format) && anOperation(format != GL30.GL_DEPTH_STENCIL || t.format == null || t.format.isStencil());
    }

    private static boolean multisampleTarget(int target) {
        return target == GL32.GL_TEXTURE_2D_MULTISAMPLE || target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
    }

    private boolean texLevelQuery(int target, int level, int pname) {
        final boolean proxy = target == GL11.GL_PROXY_TEXTURE_1D || target == GL12.GL_PROXY_TEXTURE_3D || target == GL30.GL_PROXY_TEXTURE_2D_ARRAY || target == GL40.GL_PROXY_TEXTURE_CUBE_MAP_ARRAY
            || target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE || target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE_ARRAY || PROXY_2D_TARGETS.contains(target);
        if (!anEnum(proxy || multisampleTarget(target) || GlState.targetIndex(target) >= 0)) return false;
        if (target == GL31.GL_TEXTURE_BUFFER && !aValue(level == 0)) return false;
        if (proxy || multisampleTarget(target) || pname != GL13.GL_TEXTURE_COMPRESSED_IMAGE_SIZE) return true;
        final TexBase t = boundTo(target);
        return anOperation(t != null && t.format != null && t.format.isCompressed());
    }

    private boolean texParameterQuery(int target, int pname) {
        return anEnum(parameterTarget(target) && (TEX_PARAMETERS.contains(pname) || TEX_QUERIES_ONLY.contains(pname)));
    }

    private boolean texParameterVector(int target, int pname, int first) {
        return anEnum(parameterTarget(target) && TEX_PARAMETERS.contains(pname) && !samplerStateOfMultisampled(target, pname)) && texParameterValue(pname, first);
    }

    private boolean indexedQuery(int pname, int index) {
        final int limit = switch (pname) {
            case GL31.GL_UNIFORM_BUFFER_BINDING, GL31.GL_UNIFORM_BUFFER_START, GL31.GL_UNIFORM_BUFFER_SIZE -> gl.getInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS);
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING, GL30.GL_TRANSFORM_FEEDBACK_BUFFER_START, GL30.GL_TRANSFORM_FEEDBACK_BUFFER_SIZE -> gl.getInteger(GL30.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS);
            case GL43.GL_SHADER_STORAGE_BUFFER_BINDING, GL43.GL_SHADER_STORAGE_BUFFER_START, GL43.GL_SHADER_STORAGE_BUFFER_SIZE -> gl.getInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
            case GL30.GL_COLOR_WRITEMASK, GL11.GL_BLEND, GL14.GL_BLEND_SRC_RGB, GL14.GL_BLEND_DST_RGB, GL14.GL_BLEND_SRC_ALPHA, GL14.GL_BLEND_DST_ALPHA, GL20.GL_BLEND_EQUATION_RGB, GL20.GL_BLEND_EQUATION_ALPHA -> gl.getInteger(GL20.GL_MAX_DRAW_BUFFERS);
            case GL11.GL_VIEWPORT, GL11.GL_SCISSOR_BOX, GL11.GL_DEPTH_RANGE, GL11.GL_SCISSOR_TEST -> gl.getInteger(GL41.GL_MAX_VIEWPORTS);
            case GL32.GL_SAMPLE_MASK_VALUE -> gl.getInteger(GL32.GL_MAX_SAMPLE_MASK_WORDS);
            case GL43.GL_VERTEX_BINDING_BUFFER, GL43.GL_VERTEX_BINDING_OFFSET, GL43.GL_VERTEX_BINDING_STRIDE, GL43.GL_VERTEX_BINDING_DIVISOR -> gl.getInteger(GL43.GL_MAX_VERTEX_ATTRIB_BINDINGS);
            default -> Integer.MAX_VALUE;
        };
        return aValue(index >= 0 && index < limit);
    }

    /** What writing a query's result into a buffer asks: an object that is not still counting, and room in the buffer. */
    private boolean queryToBuffer(int id, int buffer, int pname, long offset, int bytes) {
        if (!queryObject(id, pname) || !anOperation(buffer != 0 && gl.buffers().get(buffer) != null)) return false;
        if (!anOperation(!gl.activeQueries.containsValue(id))) return false;
        if (!aValue(offset >= 0L)) return false;
        return anOperation(offset + bytes <= gl.buffers().get(buffer).size);
    }

    public boolean glGetQueryBufferObjectiv(int id, int buffer, int pname, long offset) {
        return queryToBuffer(id, buffer, pname, offset, Integer.BYTES);
    }

    public boolean glGetQueryBufferObjectuiv(int id, int buffer, int pname, long offset) {
        return queryToBuffer(id, buffer, pname, offset, Integer.BYTES);
    }

    public boolean glGetQueryBufferObjecti64v(int id, int buffer, int pname, long offset) {
        return queryToBuffer(id, buffer, pname, offset, Long.BYTES);
    }

    public boolean glGetQueryBufferObjectui64v(int id, int buffer, int pname, long offset) {
        return queryToBuffer(id, buffer, pname, offset, Long.BYTES);
    }

    private boolean queryObject(int id, int pname) {
        return anOperation(gl.queryResults.containsKey(id)) && anEnum(pname == GL15.GL_QUERY_RESULT || pname == GL15.GL_QUERY_RESULT_AVAILABLE || pname == GL44.GL_QUERY_RESULT_NO_WAIT || pname == GL45.GL_QUERY_TARGET);
    }

    public boolean getQueryObjecti(int id, int pname) {
        return queryObject(id, pname);
    }

    public boolean getQueryObjectui(int id, int pname) {
        return queryObject(id, pname);
    }

    public boolean getQueryObjectui64(int id, int pname) {
        return queryObject(id, pname);
    }

    public boolean getActiveAttrib(int program, int index) {
        return glGetActiveAttrib(program, index, 0);
    }

    public boolean getActiveUniform(int program, int index) {
        return glGetActiveUniform(program, index, 0);
    }

    private static final IntOpenHashSet UNSIZED_FORMATS = set(1, 2, 3, 4, GL11.GL_RED, GL30.GL_RG, GL11.GL_RGB, GL11.GL_RGBA, GL11.GL_DEPTH_COMPONENT, GL30.GL_DEPTH_STENCIL, GL11.GL_STENCIL_INDEX, GL11.GL_ALPHA, GL11.GL_LUMINANCE, GL11.GL_LUMINANCE_ALPHA,
        GL30.GL_COMPRESSED_RED, GL30.GL_COMPRESSED_RG, GL13.GL_COMPRESSED_RGB, GL13.GL_COMPRESSED_RGBA, GL21.GL_COMPRESSED_SRGB, GL21.GL_COMPRESSED_SRGB_ALPHA);

    /** Storage has to know its exact format from the start, which only a sized one says. */
    private boolean sizedInternalFormat(int internalFormat) {
        return anEnum(gl.textures().knownInternalFormat(internalFormat) && !UNSIZED_FORMATS.contains(internalFormat));
    }

    private boolean textureStorage(int levels, int internalFormat, int width, int height, int depth) {
        if (!sizedInternalFormat(internalFormat)) return false;
        if (!aValue(levels >= 1 && width >= 1 && height >= 1 && depth >= 1)) return false;
        return anOperation(levels <= 32 - Integer.numberOfLeadingZeros(Math.max(width, Math.max(height, depth))));
    }

    public boolean glTexStorage1D(int target, int levels, int internalFormat, int width) {
        return anEnum(target == GL11.GL_TEXTURE_1D || target == GL11.GL_PROXY_TEXTURE_1D) && textureStorage(levels, internalFormat, width, 1, 1);
    }

    public boolean texStorage2D(int target, int levels, int internalFormat, int width, int height) {
        return anEnum(target == GL11.GL_TEXTURE_2D || target == GL30.GL_TEXTURE_1D_ARRAY || target == GL31.GL_TEXTURE_RECTANGLE || target == GL13.GL_TEXTURE_CUBE_MAP || PROXY_2D_TARGETS.contains(target))
            && textureStorage(levels, internalFormat, width, target == GL30.GL_TEXTURE_1D_ARRAY ? 1 : height, 1) && aValue(height >= 1);
    }

    public boolean glTexStorage3D(int target, int levels, int internalFormat, int width, int height, int depth) {
        final boolean volume = target == GL12.GL_TEXTURE_3D || target == GL12.GL_PROXY_TEXTURE_3D;
        if (!fitsThreeDimensions(target, internalFormat)) return false;
        return anEnum(volume || target == GL30.GL_TEXTURE_2D_ARRAY || target == GL30.GL_PROXY_TEXTURE_2D_ARRAY || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY || target == GL40.GL_PROXY_TEXTURE_CUBE_MAP_ARRAY)
            // The Khronos suite holds this to GL_INVALID_VALUE, which NVIDIA and Mesa both answer as an operation.
            && textureStorage(levels, internalFormat, width, height, volume ? depth : 1) && aValue(depth >= 1) && cubeArrayShape(target, width, height, depth);
    }

    public boolean glGetQueryiv(int target, int pname) {
        return anEnum((QUERY_TARGETS.contains(target) || PipelineStatistics.target(target) || target == GL33.GL_TIMESTAMP) && (pname == GL15.GL_CURRENT_QUERY || pname == GL15.GL_QUERY_COUNTER_BITS));
    }

    private boolean queryIndex(int target, int index) {
        final boolean streams = target == GL30.GL_PRIMITIVES_GENERATED || target == GL30.GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN || target == GlBase.TRANSFORM_FEEDBACK_STREAM_OVERFLOW;
        return aValue(index >= 0 && index < (streams ? gl.getInteger(GL40.GL_MAX_VERTEX_STREAMS) : 1));
    }

    public boolean glGetQueryIndexediv(int target, int index, int pname) {
        return glGetQueryiv(target, pname) && queryIndex(target, index);
    }

    public boolean glBeginQueryIndexed(int target, int index, int id) {
        return anEnum(QUERY_TARGETS.contains(target)) && queryIndex(target, index)
            && anOperation(!gl.queryActive(GlBase.queryKey(target, index)) && !gl.queryIdActive(id) && id != 0 && (gl.queryTarget(id) == 0 || gl.queryTarget(id) == target));
    }

    public boolean glBindAttribLocation(int program, int index) {
        return program(program) != null && attribIndex(index);
    }

    public boolean glBindFragDataLocation(int program, int colorNumber) {
        return program(program) != null && aValue(colorNumber >= 0 && colorNumber < gl.getInteger(GL20.GL_MAX_DRAW_BUFFERS));
    }

    public boolean glBindFragDataLocationIndexed(int program, int colorNumber, int index) {
        return program(program) != null && aValue(colorNumber >= 0 && index >= 0 && index <= 1 && colorNumber < (index == 0 ? gl.getInteger(GL20.GL_MAX_DRAW_BUFFERS) : gl.getInteger(GL33.GL_MAX_DUAL_SOURCE_DRAW_BUFFERS)));
    }

    private boolean clearBuffer(int buffer, int drawbuffer, boolean depth, boolean stencil) {
        if (!anEnum(buffer == GL11.GL_COLOR || depth && buffer == GL11.GL_DEPTH || stencil && buffer == GL11.GL_STENCIL)) return false;
        return aValue(buffer == GL11.GL_COLOR ? drawbuffer >= 0 && drawbuffer < gl.getInteger(GL20.GL_MAX_DRAW_BUFFERS) : drawbuffer == 0);
    }

    public boolean glClearBufferiv(int buffer, int drawbuffer) {
        return clearBuffer(buffer, drawbuffer, false, true);
    }

    public boolean glClearBufferuiv(int buffer, int drawbuffer) {
        return clearBuffer(buffer, drawbuffer, false, false);
    }

    public boolean glClearBufferfv(int buffer, int drawbuffer) {
        return clearBuffer(buffer, drawbuffer, true, false);
    }

    public boolean glClientWaitSync(long sync, int flags) {
        return aValue(gl.isSync(sync) && (flags & ~GL32.GL_SYNC_FLUSH_COMMANDS_BIT) == 0);
    }

    public boolean glWaitSync(long sync, int flags, long timeout) {
        return aValue(gl.isSync(sync) && flags == 0 && timeout == GL32.GL_TIMEOUT_IGNORED);
    }

    /** Zero is ignored, as GL says; anything else has to be a fence. */
    public boolean glDeleteSync(long sync) {
        return sync == 0L || aValue(gl.isSync(sync));
    }

    public boolean glGetSynciv(long sync, int pname, int bufSize) {
        return aValue(gl.isSync(sync) && bufSize >= 0) && anEnum(SYNC_QUERIES.contains(pname));
    }

    public boolean copyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        if (!anEnum(BUFFER_TARGETS.contains(readTarget) && BUFFER_TARGETS.contains(writeTarget))) return false;
        final BufBase read = bound(readTarget), write = bound(writeTarget);
        if (!anOperation(read != null && write != null)) return false;
        return aValue(readOffset >= 0 && writeOffset >= 0 && size >= 0 && readOffset + size <= read.size && writeOffset + size <= write.size);
    }

    public boolean flushMappedBufferRange(int target, long offset, long length) {
        if (!anEnum(BUFFER_TARGETS.contains(target))) return false;
        final BufBase b = bound(target);
        if (!anOperation(b != null && b.mapped && (b.mapAccess & GL30.GL_MAP_FLUSH_EXPLICIT_BIT) != 0)) return false;
        return aValue(offset >= 0 && length >= 0 && offset + length <= b.mapLength);
    }

    public boolean glGetBufferPointerv(int target, int pname) {
        return anEnum(BUFFER_TARGETS.contains(target) && pname == GL15.GL_BUFFER_MAP_POINTER);
    }

    public boolean glGetBufferSubData(int target) {
        return anEnum(BUFFER_TARGETS.contains(target));
    }

    public boolean glGetActiveAttrib(int program, int index, int bufSize) {
        final ProgramBase<?> p = program(program);
        return p != null && aValue(index >= 0 && index < p.attribs.size() && bufSize >= 0);
    }

    public boolean glGetActiveUniform(int program, int index, int bufSize) {
        final ProgramBase<?> p = program(program);
        return p != null && aValue(index >= 0 && index < p.activeUniformCount() && bufSize >= 0);
    }

    public boolean glGetActiveUniformName(int program, int uniformIndex, int bufSize) {
        return glGetActiveUniform(program, uniformIndex, bufSize);
    }

    public boolean glGetActiveUniformBlockName(int program, int uniformBlockIndex, int bufSize) {
        final ProgramBase<?> p = program(program);
        return p != null && aValue(uniformBlockIndex >= 0 && uniformBlockIndex < p.ubos.size() && bufSize >= 0);
    }

    public boolean glGetActiveUniformBlockiv(int program, int uniformBlockIndex, int pname) {
        return glGetActiveUniformBlockName(program, uniformBlockIndex, 0) && anEnum(BLOCK_QUERIES.contains(pname));
    }

    public boolean glGetActiveUniformsiv(int program, int count, long indices, int pname) {
        final ProgramBase<?> p = program(program);
        if (p == null || !aValue(count >= 0) || !anEnum(ACTIVE_UNIFORM_QUERIES.contains(pname))) return false;
        for (int i = 0; i < count; i++) {
            final int index = MemoryUtil.memGetInt(indices + i * 4L);
            if (!aValue(index >= 0 && index < p.activeUniformCount())) return false;
        }
        return true;
    }

    public boolean glGetAttachedShaders(int program, int maxCount) {
        return program(program) != null && aValue(maxCount >= 0);
    }

    public boolean glGetAttribLocation(int program) {
        return linkedProgram(program);
    }

    public boolean glGetFragDataLocation(int program) {
        return linkedProgram(program);
    }

    public boolean glGetFragDataIndex(int program) {
        return linkedProgram(program);
    }

    public boolean glGetProgramiv(int program, int pname) {
        final ProgramBase p = program(program);
        return p != null && anEnum(PROGRAM_QUERIES.contains(pname)) && anOperation(hasStageQueried(p, pname));
    }

    public boolean getProgrami(int program, int pname) {
        return glGetProgramiv(program, pname);
    }

    public boolean getProgramiv(int program, int pname) {
        return glGetProgramiv(program, pname);
    }

    /** A query about one stage needs a linked program that has that stage. */
    private static boolean hasStageQueried(ProgramBase p, int pname) {
        return switch (pname) {
            case GL46C.GL_TESS_CONTROL_OUTPUT_VERTICES -> p.linked && p.stageModes.tessControl;
            case GL46C.GL_TESS_GEN_MODE, GL46C.GL_TESS_GEN_SPACING, GL46C.GL_TESS_GEN_VERTEX_ORDER, GL46C.GL_TESS_GEN_POINT_MODE -> p.linked && p.stageModes.tessEvaluation;
            case GL46C.GL_GEOMETRY_VERTICES_OUT, GL46C.GL_GEOMETRY_INPUT_TYPE, GL46C.GL_GEOMETRY_OUTPUT_TYPE, GL46C.GL_GEOMETRY_SHADER_INVOCATIONS,
                 ProgramsBase.GEOMETRY_LINKED_VERTICES_OUT, ProgramsBase.GEOMETRY_LINKED_INPUT_TYPE, ProgramsBase.GEOMETRY_LINKED_OUTPUT_TYPE -> p.linked && p.stageModes.geometry;
            case GL46C.GL_COMPUTE_WORK_GROUP_SIZE -> p.linked && p.compute;
            default -> true;
        };
    }

    public boolean glGetProgramBinary(int program, int bufSize) {
        // No binary format is reported, so a program has no binary to return, which GL treats as a buffer too small for it.
        return program(program) != null && anOperation(false);
    }

    public boolean glGetProgramInfoLog(int program, int maxLength) {
        return program(program) != null && aValue(maxLength >= 0);
    }

    public boolean glGetProgramPipelineiv(int pipeline, int pname) {
        return anOperation(GlPipelines.known(pipeline)) && anEnum(PIPELINE_QUERIES.contains(pname));
    }

    public boolean glGetProgramPipelineInfoLog(int pipeline, int bufSize) {
        return aValue(GlPipelines.known(pipeline) && bufSize >= 0);
    }

    public boolean glGetProgramStageiv(int program, int shadertype, int pname) {
        return program(program) != null && anEnum(SHADER_TYPES.contains(shadertype) && STAGE_QUERIES.contains(pname));
    }

    public boolean glGetSubroutineIndex(int program, int shadertype) {
        return program(program) != null && anEnum(SHADER_TYPES.contains(shadertype));
    }

    public boolean glGetSubroutineUniformLocation(int program, int shadertype) {
        return glGetSubroutineIndex(program, shadertype) && linkedProgram(program);
    }

    public boolean glGetActiveSubroutineName(int program, int shadertype, int index, int bufSize) {
        return glGetSubroutineIndex(program, shadertype) && aValue(index >= 0 && index < ProgramInfo.subroutineCount(program, shadertype) && bufSize >= 0);
    }

    public boolean glGetActiveSubroutineUniformName(int program, int shadertype, int index, int bufSize) {
        return glGetSubroutineIndex(program, shadertype) && aValue(index >= 0 && index < ProgramInfo.subroutineUniformCount(program, shadertype) && bufSize >= 0);
    }

    public boolean glGetActiveSubroutineUniformiv(int program, int shadertype, int index, int pname) {
        return glGetActiveSubroutineUniformName(program, shadertype, index, 0) && anEnum(SUBROUTINE_QUERIES.contains(pname));
    }

    public boolean glGetUniformSubroutineuiv(int shadertype, int location) {
        if (!anEnum(SHADER_TYPES.contains(shadertype))) return false;
        final int program = ProgramInfo.subroutineProgram(shadertype);
        return anOperation(ProgramInfo.hasStage(program, shadertype)) && aValue(location >= 0 && location < ProgramInfo.subroutineLocationCount(program, shadertype));
    }

    public boolean glUniformSubroutinesuiv(int shadertype, int count, long indices) {
        if (!anEnum(SHADER_TYPES.contains(shadertype))) return false;
        final int program = ProgramInfo.subroutineProgram(shadertype);
        if (!anOperation(ProgramInfo.hasStage(program, shadertype))) return false;
        if (!aValue(count == ProgramInfo.subroutineLocationCount(program, shadertype))) return false;
        final int functions = ProgramInfo.programStage(program, shadertype, GL40.GL_ACTIVE_SUBROUTINES);
        for (int at = 0; at < count; at++) {
            final int index = org.lwjgl.system.MemoryUtil.memGetInt(indices + at * 4L);
            if (!aValue(index >= 0 && index < functions)) return false;
        }
        for (int at = 0; at < count; at++) {
            if (!anOperation(ProgramInfo.subroutineFits(program, shadertype, at, org.lwjgl.system.MemoryUtil.memGetInt(indices + at * 4L)))) return false;
        }
        return true;
    }

    public boolean glGetTransformFeedbackVarying(int program, int index, int bufSize) {
        return program(program) != null && aValue(index >= 0 && index < GlTransformFeedback.varyingCount(program) && bufSize >= 0);
    }

    public boolean glGetUniformBlockIndex(int program) {
        return linkedProgram(program);
    }

    public boolean glGetUniformIndices(int program, int uniformCount) {
        return program(program) != null && aValue(uniformCount >= 0);
    }

    public boolean glGetShaderiv(int shader, int pname) {
        return shader(shader) != null && anEnum(SHADER_QUERIES.contains(pname));
    }

    public boolean glGetShaderInfoLog(int shader, int maxLength) {
        return shader(shader) != null && aValue(maxLength >= 0);
    }

    public boolean glGetShaderSource(int shader, int bufSize) {
        return shader(shader) != null && aValue(bufSize >= 0);
    }

    public boolean glGetShaderPrecisionFormat(int shaderType, int precisionType) {
        return anEnum((shaderType == GL20.GL_VERTEX_SHADER || shaderType == GL20.GL_FRAGMENT_SHADER) && precisionType >= GL41.GL_LOW_FLOAT && precisionType <= GL41.GL_HIGH_INT);
    }

    public boolean glCreateShaderProgramv(int type, int count) {
        return anEnum(SHADER_TYPES.contains(type)) && aValue(count >= 0);
    }

    public boolean shaderSource(int shader) {
        return shader(shader) != null;
    }

    public boolean glShaderSource(int shader, int count) {
        return aValue(count >= 0);
    }

    public boolean glProgramBinary(int program) {
        // No binary format is reported, so none is known.
        return program(program) != null && anEnum(false);
    }

    public boolean glShaderBinary(int count, long shaders, int binaryformat, long binary, int length) {
        if (!aValue(count >= 0 && length >= 0) || !anEnum(binaryformat == GL46C.GL_SHADER_BINARY_FORMAT_SPIR_V)) return false;
        for (int i = 0; i < count; i++) {
            if (shader(MemoryUtil.memGetInt(shaders + i * 4L)) == null) return false;
        }
        return aValue(length % 4 == 0);
    }

    public boolean glSpecializeShader(int shader) {
        final Shader s = shader(shader);
        if (s == null) return refuse(shader != 0 && gl.isProgram(shader) ? GL11.GL_INVALID_OPERATION : GL11.GL_INVALID_VALUE);
        return anOperation(s.spirv != null && !s.specialized);
    }

    /** GLSM's overload reaches the backend without the entry point's check, and a missing shader would fail there. */
    public boolean specializeShader(int shader) {
        return glSpecializeShader(shader);
    }

    public boolean glGetFramebufferAttachmentParameteriv(int target, int attachment, int pname) {
        if (!framebufferTarget(target)) return false;
        final Fbo f = gl.fboFor(target);
        return glGetNamedFramebufferAttachmentParameteriv(f == null ? 0 : f.id, attachment, pname);
    }

    public boolean glGetRenderbufferParameteriv(int target, int pname) {
        return anEnum(target == GL30.GL_RENDERBUFFER && RENDERBUFFER_QUERIES.contains(pname));
    }

    public boolean glGetMultisamplefv(int pname, int index) {
        return anEnum(pname == GL32.GL_SAMPLE_POSITION) && aValue(index >= 0 && index < gl.getInteger(GL13.GL_SAMPLES));
    }

    public boolean glGetStringi(int name, int index) {
        if (name == GL46C.GL_SPIR_V_EXTENSIONS) return aValue(false);
        if (name == GL20.GL_SHADING_LANGUAGE_VERSION) return aValue(index >= 0 && index < GlBase.SHADING_LANGUAGE_VERSIONS.length);
        return anEnum(name == GL11.GL_EXTENSIONS) && aValue(index >= 0 && index < gl.getInteger(GL30.GL_NUM_EXTENSIONS));
    }

    public boolean glGetBooleanv(int pname) {
        // Asking for anything else as an integer refuses a name that is no query at all.
        if (pname != GL11.GL_DEPTH_WRITEMASK && pname != GL11.GL_COLOR_WRITEMASK && pname != GL13.GL_SAMPLE_COVERAGE_INVERT && pname != GL11.GL_UNPACK_SWAP_BYTES && pname != GL11.GL_PACK_SWAP_BYTES) gl.getInteger(pname);
        return true;
    }

    public boolean glGetPointerv(int pname) {
        return anEnum(pname == GL11.GL_VERTEX_ARRAY_POINTER || pname == GL11.GL_NORMAL_ARRAY_POINTER || pname == GL11.GL_COLOR_ARRAY_POINTER || pname == GL11.GL_INDEX_ARRAY_POINTER
            || pname == GL11.GL_TEXTURE_COORD_ARRAY_POINTER || pname == GL11.GL_EDGE_FLAG_ARRAY_POINTER || pname == 0x8456 || pname == 0x845D
            || pname == GL11.GL_FEEDBACK_BUFFER_POINTER || pname == GL11.GL_SELECTION_BUFFER_POINTER || pname == GL46C.GL_DEBUG_CALLBACK_FUNCTION || pname == GL46C.GL_DEBUG_CALLBACK_USER_PARAM);
    }

    public boolean glGetVertexAttribPointerv(int index, int pname) {
        return attribIndex(index) && anEnum(pname == GL20.GL_VERTEX_ATTRIB_ARRAY_POINTER);
    }

    private boolean viewportRange(int first, int count) {
        return aValue(count >= 0 && first >= 0 && (long) first + count <= gl.getInteger(GL41.GL_MAX_VIEWPORTS));
    }

    public boolean glScissorArrayv(int first, int count, long v) {
        if (!viewportRange(first, count)) return false;
        for (int i = 0; i < count; i++) {
            if (!aValue(MemoryUtil.memGetInt(v + i * 16L + 8L) >= 0 && MemoryUtil.memGetInt(v + i * 16L + 12L) >= 0)) return false;
        }
        return true;
    }

    public boolean glViewportArrayv(int first, int count, long v) {
        if (!viewportRange(first, count)) return false;
        for (int i = 0; i < count; i++) {
            if (!aValue(MemoryUtil.memGetFloat(v + i * 16L + 8L) >= 0f && MemoryUtil.memGetFloat(v + i * 16L + 12L) >= 0f)) return false;
        }
        return true;
    }

    public boolean glDepthRangeArrayv(int first, int count) {
        return viewportRange(first, count);
    }

    public boolean glScissorIndexedv(int index, long v) {
        return viewportIndex(index) && aValue(MemoryUtil.memGetInt(v + 8L) >= 0 && MemoryUtil.memGetInt(v + 12L) >= 0);
    }

    public boolean glViewportIndexedfv(int index, long v) {
        return viewportIndex(index) && aValue(MemoryUtil.memGetFloat(v + 8L) >= 0f && MemoryUtil.memGetFloat(v + 12L) >= 0f);
    }

    public boolean glPatchParameterfv(int pname) {
        return anEnum(pname == GL40.GL_PATCH_DEFAULT_OUTER_LEVEL || pname == GL40.GL_PATCH_DEFAULT_INNER_LEVEL);
    }

    public boolean glDrawArraysIndirect(int mode, long indirect) {
        if (!vertexArrayBound()) return false;
        return primitive(mode) && aValue(indirect >= 0 && indirect % 4 == 0);
    }

    public boolean glDrawElementsIndirect(int mode, int type, long indirect) {
        if (!vertexArrayBound()) return false;
        return indexed(mode, 0, type) && aValue(indirect >= 0 && indirect % 4 == 0);
    }

    public boolean glDrawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int instancecount) {
        return indexed(mode, count, type) && aValue(instancecount >= 0);
    }

    public boolean glMultiDrawArrays(int mode, long first, long count, int drawcount) {
        return primitive(mode) && aValue(drawcount >= 0);
    }

    public boolean glMultiDrawElements(int mode, long count, int type, long indices, int drawcount) {
        return indexed(mode, 0, type) && aValue(drawcount >= 0);
    }

    public boolean glMultiDrawElementsBaseVertex(int mode, long count, int type, long indices, int drawcount) {
        if (!indexed(mode, 0, type) || !aValue(drawcount >= 0)) return false;
        for (int i = 0; i < drawcount; i++) {
            if (!aValue(org.lwjgl.system.MemoryUtil.memGetInt(count + i * 4L) >= 0)) return false;
        }
        return true;
    }

    public boolean drawPrimitive(int mode) {
        return primitive(mode);
    }

    public boolean drawRange(int start, int end) {
        return aValue(Integer.compareUnsigned(end, start) >= 0);
    }

    public boolean glGenBuffers(int n) {
        return aValue(n >= 0);
    }

    public boolean glDeleteBuffers(int n) {
        return aValue(n >= 0);
    }

    public boolean glGenFramebuffers(int n) {
        return aValue(n >= 0);
    }

    public boolean glDeleteFramebuffers(int n) {
        return aValue(n >= 0);
    }

    public boolean glGenProgramPipelines(int n) {
        return aValue(n >= 0);
    }

    public boolean glDeleteProgramPipelines(int n) {
        return aValue(n >= 0);
    }

    public boolean glGenQueries(int n) {
        return aValue(n >= 0);
    }

    public boolean glDeleteQueries(int n) {
        return aValue(n >= 0);
    }

    public boolean glGenRenderbuffers(int n) {
        return aValue(n >= 0);
    }

    public boolean glDeleteRenderbuffers(int n) {
        return aValue(n >= 0);
    }

    public boolean glGenSamplers(int n) {
        return aValue(n >= 0);
    }

    public boolean glDeleteSamplers(int n) {
        return aValue(n >= 0);
    }

    public boolean glGenTextures(int n) {
        return aValue(n >= 0);
    }

    public boolean glDeleteTextures(int n) {
        return aValue(n >= 0);
    }

    public boolean glGenTransformFeedbacks(int n) {
        return aValue(n >= 0);
    }

    public boolean glDeleteTransformFeedbacks(int n, long ids) {
        if (!aValue(n >= 0)) return false;
        for (int i = 0; i < n; i++) {
            if (!anOperation(!GlTransformFeedback.isActive(MemoryUtil.memGetInt(ids + i * 4L)))) return false;
        }
        return true;
    }

    public boolean glGenVertexArrays(int n) {
        return aValue(n >= 0);
    }

    public boolean glDeleteVertexArrays(int n) {
        return aValue(n >= 0);
    }

    public boolean glDrawBuffers(int n) {
        return aValue(n >= 0);
    }

    public boolean glUniform1fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform1fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniform1iv(int location, int count, long value) {
        if (!uniformArray(gl.currentProgram(), location, count)) return false;
        if (value == 0L || location < 0) return true;
        final ProgramBase.Uniform u = uniformAt(gl.currentProgram(), location);
        if (u == null || !u.isOpaque()) return true;
        final boolean image = u.glType >= GL42.GL_IMAGE_1D && u.glType <= GL42.GL_UNSIGNED_INT_IMAGE_2D_MULTISAMPLE_ARRAY;
        final int limit = gl.getInteger(image ? GL42.GL_MAX_IMAGE_UNITS : GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        for (int i = 0; i < count; i++) {
            final int unit = org.lwjgl.system.MemoryUtil.memGetInt(value + i * 4L);
            if (!aValue(unit >= 0 && unit < limit)) return false;
        }
        return true;
    }

    public boolean glProgramUniform1iv(int program, int location, int count, long value) {
        if (!programUniformArray(program, location, count)) return false;
        final ProgramBase<?> p = gl.programs().program(program);
        if (value == 0L || location < 0) return true;
        final ProgramBase.Uniform u = uniformAt(p, location);
        if (u == null || !u.isOpaque()) return true;
        final boolean image = u.glType >= GL42.GL_IMAGE_1D && u.glType <= GL42.GL_UNSIGNED_INT_IMAGE_2D_MULTISAMPLE_ARRAY;
        final int limit = gl.getInteger(image ? GL42.GL_MAX_IMAGE_UNITS : GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        for (int i = 0; i < count; i++) {
            final int unit = org.lwjgl.system.MemoryUtil.memGetInt(value + i * 4L);
            if (!aValue(unit >= 0 && unit < limit)) return false;
        }
        return true;
    }

    public boolean glUniform1uiv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform1uiv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniform2fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform2fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniform2iv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform2iv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniform2uiv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform2uiv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniform3fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform3fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniform3iv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform3iv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniform3uiv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform3uiv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniform4fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform4fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniform4iv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform4iv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniform4uiv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniform4uiv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniformMatrix2fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniformMatrix2fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniformMatrix3fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniformMatrix3fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniformMatrix4fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniformMatrix4fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniformMatrix2x3fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniformMatrix2x3fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniformMatrix3x2fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniformMatrix3x2fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniformMatrix2x4fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniformMatrix2x4fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniformMatrix4x2fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniformMatrix4x2fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniformMatrix3x4fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniformMatrix3x4fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glUniformMatrix4x3fv(int location, int count) {
        return uniformArray(gl.currentProgram(), location, count);
    }

    public boolean glProgramUniformMatrix4x3fv(int program, int location, int count) {
        return programUniformArray(program, location, count);
    }

    public boolean glGetUniformfv(int program, int location) {
        return uniformRead(program, location);
    }

    public boolean glGetUniformiv(int program, int location) {
        return uniformRead(program, location);
    }

    public boolean glGetUniformuiv(int program, int location) {
        return uniformRead(program, location);
    }

    public boolean glGetUniformdv(int program, int location) {
        return uniformRead(program, location);
    }

    public boolean glGetTexParameterfv(int target, int pname) {
        return texParameterQuery(target, pname);
    }

    public boolean glGetSamplerParameterfv(int sampler, int pname) {
        return anOperation(gl.isSampler(sampler)) && anEnum(SAMPLER_PARAMETERS.contains(pname));
    }

    public boolean glGetTexParameteriv(int target, int pname) {
        return texParameterQuery(target, pname);
    }

    public boolean glGetSamplerParameteriv(int sampler, int pname) {
        return anOperation(gl.isSampler(sampler)) && anEnum(SAMPLER_PARAMETERS.contains(pname));
    }

    public boolean glGetTexParameterIiv(int target, int pname) {
        return texParameterQuery(target, pname);
    }

    public boolean glGetSamplerParameterIiv(int sampler, int pname) {
        return anOperation(gl.isSampler(sampler)) && anEnum(SAMPLER_PARAMETERS.contains(pname));
    }

    public boolean glGetTexParameterIuiv(int target, int pname) {
        return texParameterQuery(target, pname);
    }

    public boolean glGetSamplerParameterIuiv(int sampler, int pname) {
        return anOperation(gl.isSampler(sampler)) && anEnum(SAMPLER_PARAMETERS.contains(pname));
    }

    public boolean glGetQueryObjectiv(int id, int pname) {
        return queryObject(id, pname);
    }

    public boolean glGetQueryObjectuiv(int id, int pname) {
        return queryObject(id, pname);
    }

    public boolean glGetQueryObjecti64v(int id, int pname) {
        return queryObject(id, pname);
    }

    public boolean glGetQueryObjectui64v(int id, int pname) {
        return queryObject(id, pname);
    }

    public boolean glGetVertexAttribdv(int index, int pname) {
        return attribIndex(index) && anEnum(ATTRIB_QUERIES.contains(pname));
    }

    public boolean glGetVertexAttribfv(int index, int pname) {
        return attribIndex(index) && anEnum(ATTRIB_QUERIES.contains(pname));
    }

    public boolean glGetVertexAttribiv(int index, int pname) {
        return attribIndex(index) && anEnum(ATTRIB_QUERIES.contains(pname));
    }

    public boolean glGetVertexAttribIiv(int index, int pname) {
        return attribIndex(index) && anEnum(ATTRIB_QUERIES.contains(pname));
    }

    public boolean glGetVertexAttribIuiv(int index, int pname) {
        return attribIndex(index) && anEnum(ATTRIB_QUERIES.contains(pname));
    }

    public boolean glGetVertexAttribLdv(int index, int pname) {
        return attribIndex(index) && anEnum(ATTRIB_QUERIES.contains(pname));
    }

    public boolean glGetTexLevelParameterfv(int target, int level, int pname) {
        return texLevelQuery(target, level, pname);
    }

    public boolean glGetTexLevelParameteriv(int target, int level, int pname) {
        return texLevelQuery(target, level, pname);
    }

    private static boolean copyImageTarget(int target) {
        return switch (target) {
            case GL11.GL_TEXTURE_1D, GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_3D, GL30.GL_TEXTURE_1D_ARRAY, GL30.GL_TEXTURE_2D_ARRAY, GL31.GL_TEXTURE_RECTANGLE, GL13.GL_TEXTURE_CUBE_MAP,
                 GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL32.GL_TEXTURE_2D_MULTISAMPLE, GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY, GL30.GL_RENDERBUFFER -> true;
            default -> false;
        };
    }

    private static int createdAs(TexBase t) {
        return t.declaredTarget != 0 ? t.declaredTarget : t.target;
    }

    private TexBase copyImageObject(int name, int target) {
        if (target == GL30.GL_RENDERBUFFER) return gl.textures().renderbufferExists(name) ? gl.textures().attachment(name, true) : null;
        return gl.textures().get(name);
    }

    /** What a texel takes up by the format's own definition, for telling which formats can copy to each other. */
    private static int copyClass(Formats.Format f) {
        return f.kind() == Formats.K_PACKED ? f.texelBytes() * 8 : f.r() + f.g() + f.b() + f.a();
    }

    private static boolean copyCompatible(Formats.Format a, Formats.Format b) {
        if (a.glInternal() == b.glInternal()) return true;
        if (a.isDepthOrStencil() || b.isDepthOrStencil()) return false;
        if (a.isCompressed() && b.isCompressed()) return a.blockBytes() == b.blockBytes();
        if (a.isCompressed()) return a.blockBytes() * 8 == copyClass(b);
        if (b.isCompressed()) return b.blockBytes() * 8 == copyClass(a);
        return copyClass(a) == copyClass(b);
    }

    private static boolean copyRegionFits(TexBase t, int level, int x, int y, int z, int width, int height, int depth) {
        final int layers = t.isCube() ? t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY ? t.layers : 6 : t.is3D() ? t.levelDepth(level) : t.layers;
        // A one-dimensional array's layers count along z here, as any other array's do.
        final int levelHeight = t.target == GL30.GL_TEXTURE_1D_ARRAY ? 1 : t.levelHeight(level);
        return x >= 0 && y >= 0 && z >= 0 && width >= 0 && height >= 0 && depth >= 0 && (long) x + width <= t.levelWidth(level) && (long) y + height <= levelHeight && (long) z + depth <= layers;
    }

    /** A compressed image is copied a block at a time, unless the region ends at the image's edge. */
    private static boolean copyBlocksAligned(TexBase t, int level, int x, int y, int width, int height) {
        if (t.format == null || !t.format.isCompressed()) return true;
        return x % 4 == 0 && y % 4 == 0 && (width % 4 == 0 || x + width == t.levelWidth(level)) && (height % 4 == 0 || y + height == t.levelHeight(level));
    }

    public boolean copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ, int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                    int srcWidth, int srcHeight, int srcDepth) {
        // Drivers take each side in turn, a name of zero first and then its target.
        if (!aValue(srcName != 0) || !anEnum(copyImageTarget(srcTarget)) || !aValue(dstName != 0) || !anEnum(copyImageTarget(dstTarget))) return false;
        final TexBase src = copyImageObject(srcName, srcTarget), dst = copyImageObject(dstName, dstTarget);
        if (!aValue(src != null && dst != null)) return false;
        if (!anEnum((srcTarget == GL30.GL_RENDERBUFFER || createdAs(src) == srcTarget) && (dstTarget == GL30.GL_RENDERBUFFER || createdAs(dst) == dstTarget))) return false;
        if (!anOperation(src.format != null && dst.format != null)) return false;
        if (!anOperation((srcTarget == GL30.GL_RENDERBUFFER || src.immutable || src.completeFor(src.minFilter)) && (dstTarget == GL30.GL_RENDERBUFFER || dst.immutable || dst.completeFor(dst.minFilter)))) return false;
        if (!aValue(srcLevel >= 0 && dstLevel >= 0 && srcLevel < Math.max(1, src.levels) && dstLevel < Math.max(1, dst.levels))) return false;
        if (!anOperation(src.samples == dst.samples && copyCompatible(src.format, dst.format))) return false;
        final boolean shrinks = src.format.isCompressed() && !dst.format.isCompressed(), grows = !src.format.isCompressed() && dst.format.isCompressed();
        final int dstWidth = shrinks ? (srcWidth + 3) / 4 : grows ? srcWidth * 4 : srcWidth, dstHeight = shrinks ? (srcHeight + 3) / 4 : grows ? srcHeight * 4 : srcHeight;
        return aValue(copyRegionFits(src, srcLevel, srcX, srcY, srcZ, srcWidth, srcHeight, srcDepth) && copyRegionFits(dst, dstLevel, dstX, dstY, dstZ, dstWidth, dstHeight, srcDepth)
            && copyBlocksAligned(src, srcLevel, srcX, srcY, srcWidth, srcHeight) && copyBlocksAligned(dst, dstLevel, dstX, dstY, dstWidth, dstHeight));
    }

    public boolean glTexParameteriv(int target, int pname, long params) {
        return texParameterVector(target, pname, MemoryUtil.memGetInt(params)) && allSwizzles(pname, params);
    }

    public boolean glTexParameterIiv(int target, int pname, long params) {
        return texParameterVector(target, pname, MemoryUtil.memGetInt(params)) && allSwizzles(pname, params);
    }

    public boolean glTexParameterIuiv(int target, int pname, long params) {
        return texParameterVector(target, pname, MemoryUtil.memGetInt(params)) && allSwizzles(pname, params);
    }

    private boolean allSwizzles(int pname, long params) {
        if (pname != GL33.GL_TEXTURE_SWIZZLE_RGBA) return true;
        for (int i = 0; i < 4; i++) {
            if (!texParameterValue(GL33.GL_TEXTURE_SWIZZLE_R, MemoryUtil.memGetInt(params + i * 4L))) return false;
        }
        return true;
    }

    public boolean glTexParameterfv(int target, int pname, long params) {
        return texParameterVector(target, pname, (int) MemoryUtil.memGetFloat(params)) && anisotropy(pname, MemoryUtil.memGetFloat(params));
    }

    public boolean glGetBooleani_v(int target, int index) {
        return indexedQuery(target, index);
    }

    public boolean glGetIntegeri_v(int target, int index) {
        return indexedQuery(target, index);
    }

    public boolean glGetInteger64i_v(int target, int index) {
        return indexedQuery(target, index);
    }

    public boolean glGetBufferParameteriv(int target, int value) {
        return anEnum(BUFFER_TARGETS.contains(target) && BUFFER_QUERIES.contains(value));
    }

    public boolean glGetBufferParameteri64v(int target, int value) {
        return anEnum(BUFFER_TARGETS.contains(target) && BUFFER_QUERIES.contains(value));
    }

    // Calls naming their object run the same checks as the calls using a binding, with the named object standing in for the bound one.
    private BufBase namedBuffer;
    private TexBase namedTexture;
    private Fbo namedFramebuffer;

    private boolean onBuffer(int buffer, java.util.function.BooleanSupplier check) {
        final BufBase b = buffer != 0 && gl.buffers().exists(buffer) ? gl.buffers().get(buffer) : null;
        if (!anOperation(b != null)) return false;
        namedBuffer = b;
        try {
            return check.getAsBoolean();
        } finally {
            namedBuffer = null;
        }
    }

    private boolean onTexture(int texture, java.util.function.BooleanSupplier check) {
        // A generated name passes as well as an object: the EXT forms make one on first use, and share these checks.
        if (!anOperation(texture != 0 && gl.textures().exists(texture))) return false;
        final TexBase t = gl.textures().get(texture);
        if (t == null || t.target == 0) return true;
        namedTexture = t;
        try {
            return check.getAsBoolean();
        } finally {
            namedTexture = null;
        }
    }

    private boolean onFramebuffer(int framebuffer, boolean defaultAllowed, java.util.function.BooleanSupplier check) {
        final Fbo f = gl.framebuffers.get(framebuffer);
        if (!anOperation(framebuffer == 0 ? defaultAllowed : gl.framebuffers.exists(framebuffer) && f != null)) return false;
        namedFramebuffer = f;
        try {
            return check.getAsBoolean();
        } finally {
            namedFramebuffer = null;
        }
    }

    /** Zero names the default vertex array, as a compatibility profile has it and drivers take it. */
    private boolean onVertexArray(int array, java.util.function.BooleanSupplier check) {
        return anOperation(array == 0 && !strictCore || gl.isVertexArrayName(array)) && check.getAsBoolean();
    }

    public boolean glCreateBuffers(int n) {
        return aValue(n >= 0);
    }

    public boolean glCreateFramebuffers(int n) {
        return aValue(n >= 0);
    }

    public boolean glCreateRenderbuffers(int n) {
        return aValue(n >= 0);
    }

    public boolean glCreateVertexArrays(int n) {
        return aValue(n >= 0);
    }

    public boolean glCreateSamplers(int n) {
        return aValue(n >= 0);
    }

    public boolean glCreateProgramPipelines(int n) {
        return aValue(n >= 0);
    }

    public boolean glCreateTransformFeedbacks(int n) {
        return aValue(n >= 0);
    }

    public boolean glCreateTextures(int target, int n) {
        return anEnum(GlState.targetIndex(target) >= 0 && !(target >= GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z)) && aValue(n >= 0);
    }

    public boolean glCreateQueries(int target, int n) {
        return anEnum(QUERY_TARGETS.contains(target) || target == GL33.GL_TIMESTAMP) && aValue(n >= 0);
    }

    private static final int STORAGE_FLAGS = GL44.GL_DYNAMIC_STORAGE_BIT | GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT | MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT | GL44.GL_CLIENT_STORAGE_BIT;

    public boolean glNamedBufferData(int buffer, long size, long data, int usage) {
        return onBuffer(buffer, () -> anEnum(BUFFER_USAGES.contains(usage)) && aValue(size >= 0) && anOperation(!namedBuffer.immutable));
    }

    public boolean glNamedBufferStorage(int buffer, long size, long data, int flags) {
        return onBuffer(buffer, () -> aValue(size > 0 && (flags & ~STORAGE_FLAGS) == 0 && ((flags & MAP_PERSISTENT_BIT) == 0 || (flags & (GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT)) != 0)
            && ((flags & GL44.GL_MAP_COHERENT_BIT) == 0 || (flags & MAP_PERSISTENT_BIT) != 0)) && anOperation(!namedBuffer.immutable));
    }

    // KHR_debug, as GL 4.3 took it in.
    private static final int DEBUG_SOURCE_API = 0x8246, DEBUG_SOURCE_APPLICATION = 0x824A, DEBUG_SOURCE_THIRD_PARTY = 0x8249, DEBUG_SOURCE_OTHER = 0x824B;
    private static final int DEBUG_TYPE_ERROR = 0x824C, DEBUG_TYPE_OTHER = 0x8251, DEBUG_TYPE_MARKER = 0x8268, DEBUG_TYPE_POP_GROUP = 0x826A;
    private static final int DEBUG_SEVERITY_HIGH = 0x9146, DEBUG_SEVERITY_LOW = 0x9148, DEBUG_SEVERITY_NOTIFICATION = 0x826B;

    private static boolean debugSource(int source) {
        return source >= DEBUG_SOURCE_API && source <= DEBUG_SOURCE_OTHER;
    }

    private static boolean debugType(int type) {
        return type >= DEBUG_TYPE_ERROR && type <= DEBUG_TYPE_OTHER || type >= DEBUG_TYPE_MARKER && type <= DEBUG_TYPE_POP_GROUP;
    }

    private static boolean debugSeverity(int severity) {
        return severity >= DEBUG_SEVERITY_HIGH && severity <= DEBUG_SEVERITY_LOW || severity == DEBUG_SEVERITY_NOTIFICATION;
    }

    /** A message's length: the one given, else up to its terminator. */
    private static long messageLength(int length, long message) {
        return length >= 0 || message == 0L ? length : MemoryUtil.memLengthNT1(MemoryUtil.memByteBuffer(message, Integer.MAX_VALUE));
    }

    public boolean glDebugMessageInsert(int source, int type, int id, int severity, int length, long message) {
        if (!anEnum((source == DEBUG_SOURCE_APPLICATION || source == DEBUG_SOURCE_THIRD_PARTY) && debugType(type) && debugSeverity(severity))) return false;
        return aValue(messageLength(length, message) < gl.getInteger(GL43.GL_MAX_DEBUG_MESSAGE_LENGTH));
    }

    public boolean glDebugMessageControl(int source, int type, int severity, int count) {
        // Drivers look at the count before the enums.
        if (!aValue(count >= 0)) return false;
        if (!anEnum((source == GL11.GL_DONT_CARE || debugSource(source)) && (type == GL11.GL_DONT_CARE || debugType(type)) && (severity == GL11.GL_DONT_CARE || debugSeverity(severity)))) return false;
        // Messages named by id belong to one source and type, at any severity.
        return anOperation(count == 0 || source != GL11.GL_DONT_CARE && type != GL11.GL_DONT_CARE && severity == GL11.GL_DONT_CARE);
    }

    public boolean glPushDebugGroup(int source, int id, int length, long message) {
        if (!anEnum(source == DEBUG_SOURCE_APPLICATION || source == DEBUG_SOURCE_THIRD_PARTY)) return false;
        if (!aValue(messageLength(length, message) < gl.getInteger(GL43.GL_MAX_DEBUG_MESSAGE_LENGTH))) return false;
        return gl.getInteger(GL43.GL_DEBUG_GROUP_STACK_DEPTH) < gl.getInteger(GL43.GL_MAX_DEBUG_GROUP_STACK_DEPTH) || refuse(GL11.GL_STACK_OVERFLOW);
    }

    /** Whether name is an object of the kind identifier says, for labels. */
    private boolean labelled(int identifier, int name) {
        return switch (identifier) {
            case GL43.GL_BUFFER -> gl.isBuffer(name);
            case GL43.GL_SHADER -> gl.isShader(name);
            case GL43.GL_PROGRAM -> gl.isProgram(name);
            case GL11.GL_VERTEX_ARRAY -> gl.isVertexArray(name);
            case GL43.GL_QUERY -> gl.isQuery(name);
            case GL43.GL_PROGRAM_PIPELINE -> GlPipelines.known(name);
            case GL40.GL_TRANSFORM_FEEDBACK -> GlTransformFeedback.exists(name);
            case GL43.GL_SAMPLER -> gl.isSampler(name);
            case GL11.GL_TEXTURE -> gl.isTexture(name);
            case GL30.GL_RENDERBUFFER -> gl.isRenderbuffer(name);
            case GL30.GL_FRAMEBUFFER -> gl.isFramebuffer(name);
            default -> false;
        };
    }

    private static boolean labelKind(int identifier) {
        return switch (identifier) {
            case GL43.GL_BUFFER, GL43.GL_SHADER, GL43.GL_PROGRAM, GL11.GL_VERTEX_ARRAY, GL43.GL_QUERY, GL43.GL_PROGRAM_PIPELINE, GL40.GL_TRANSFORM_FEEDBACK,
                 GL43.GL_SAMPLER, GL11.GL_TEXTURE, GL30.GL_RENDERBUFFER, GL30.GL_FRAMEBUFFER -> true;
            default -> false;
        };
    }

    public boolean glObjectLabel(int identifier, int name, int length, long label) {
        return anEnum(labelKind(identifier)) && aValue(labelled(identifier, name)) && aValue(messageLength(length, label) < gl.getInteger(GL43.GL_MAX_LABEL_LENGTH));
    }

    public boolean glGetObjectLabel(int identifier, int name, int bufSize) {
        return aValue(bufSize >= 0) && anEnum(labelKind(identifier)) && aValue(labelled(identifier, name));
    }

    public boolean glObjectPtrLabel(long ptr, int length, long label) {
        return aValue(gl.isSync(ptr)) && aValue(messageLength(length, label) < gl.getInteger(GL43.GL_MAX_LABEL_LENGTH));
    }

    public boolean glGetObjectPtrLabel(long ptr, int bufSize) {
        return aValue(gl.isSync(ptr)) && aValue(bufSize >= 0);
    }

    public boolean glGetDebugMessageLog(int count, int bufSize, long sources, long types, long ids, long severities, long lengths, long messageLog) {
        return aValue(bufSize >= 0 || messageLog == 0L);
    }

    private static final java.util.Set<Integer> INTERNALFORMAT_TARGETS = java.util.Set.of(GL11.GL_TEXTURE_1D, GL30.GL_TEXTURE_1D_ARRAY, GL11.GL_TEXTURE_2D,
        GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_3D, GL13.GL_TEXTURE_CUBE_MAP, GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL31.GL_TEXTURE_RECTANGLE, GL31.GL_TEXTURE_BUFFER,
        GL30.GL_RENDERBUFFER, GL32.GL_TEXTURE_2D_MULTISAMPLE, GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY);

    /** ARB_internalformat_query2's names, from GL_INTERNALFORMAT_SUPPORTED to GL_VIEW_COMPATIBILITY_CLASS, and the few outside that run. */
    private static boolean internalformatQuery(int pname) {
        return pname >= 0x826F && pname <= 0x82B6 || pname == 0x9380 || pname == GL13.GL_SAMPLES || pname == GL13.GL_TEXTURE_COMPRESSED || pname == 0x90C7 || pname == 0x9365;
    }

    public boolean glGetInternalformativ(int target, int internalformat, int pname, int bufSize) {
        return aValue(bufSize >= 0) && anEnum(INTERNALFORMAT_TARGETS.contains(target) && internalformatQuery(pname));
    }

    public boolean glGetInternalformati64v(int target, int internalformat, int pname, int bufSize) {
        return glGetInternalformativ(target, internalformat, pname, bufSize);
    }

    private boolean storageMultisample(int samples, int internalformat, int width, int height, int depth) {
        return sizedInternalFormat(internalformat) && aValue(samples >= 1 && width >= 1 && height >= 1 && depth >= 1) && multisampleSize(samples, width, height, depth)
            && integerSamplesAllowed(samples, internalformat);
    }

    public boolean glTexStorage2DMultisample(int target, int samples, int internalformat, int width, int height) {
        if (!anEnum(target == GL32.GL_TEXTURE_2D_MULTISAMPLE || target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE)) return false;
        return aValue(samples >= 1 && width >= 1 && height >= 1) && boundMutable(target) && storageMultisample(samples, internalformat, width, height, 1);
    }

    public boolean glTexStorage3DMultisample(int target, int samples, int internalformat, int width, int height, int depth) {
        if (!anEnum(target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY || target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE_ARRAY)) return false;
        return aValue(samples >= 1 && width >= 1 && height >= 1 && depth >= 1) && boundMutable(target) && storageMultisample(samples, internalformat, width, height, depth);
    }

    /** Storage goes to a texture bound at the target, once. */
    private boolean boundMutable(int target) {
        if (target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE || target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE_ARRAY) return true;
        final int name = gl.st().boundTexture(target);
        final TexBase t = name == 0 ? null : gl.textures().get(name);
        return anOperation(name != 0 && (t == null || !t.immutable));
    }

    public boolean glInvalidateTexImage(int texture, int level) {
        if (!aValue(texture != 0 && gl.textures().exists(texture))) return false;
        final TexBase t = gl.textures().get(texture);
        return aValue(level >= 0 && (t == null || t.levels == 0 || level < Math.max(1, t.levels)));
    }

    public boolean glInvalidateTexSubImage(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth) {
        if (!glInvalidateTexImage(texture, level)) return false;
        final TexBase t = gl.textures().get(texture);
        if (t == null || !t.hasImage()) return aValue(width >= 0 && height >= 0 && depth >= 0);
        final int w = t.levelWidth(level), h = t.levelHeight(level), d = t.is3D() ? t.levelDepth(level) : Math.max(1, t.layers);
        return aValue(width >= 0 && height >= 0 && depth >= 0 && xoffset >= 0 && yoffset >= 0 && zoffset >= 0
            && (long) xoffset + width <= w && (long) yoffset + height <= h && (long) zoffset + depth <= d);
    }

    public boolean glInvalidateBufferData(int buffer) {
        if (!aValue(buffer != 0 && gl.buffers().exists(buffer))) return false;
        final BufBase b = gl.buffers().get(buffer);
        return anOperation(!b.mapped || (b.mapAccess & MAP_PERSISTENT_BIT) != 0);
    }

    public boolean glInvalidateBufferSubData(int buffer, long offset, long length) {
        if (!aValue(buffer != 0 && gl.buffers().exists(buffer))) return false;
        final BufBase b = gl.buffers().get(buffer);
        return aValue(offset >= 0 && length >= 0 && offset + length <= b.size) && anOperation(!b.mapped || (b.mapAccess & MAP_PERSISTENT_BIT) != 0);
    }

    public boolean glBlitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        if (!anOperation((readFramebuffer == 0 || gl.isFramebuffer(readFramebuffer)) && (drawFramebuffer == 0 || gl.isFramebuffer(drawFramebuffer)))) return false;
        if (!aValue((mask & ~(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT)) == 0)) return false;
        if (!anEnum(filter == GL11.GL_NEAREST || filter == GL11.GL_LINEAR)) return false;
        return anOperation(filter == GL11.GL_NEAREST || (mask & (GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT)) == 0);
    }

    public boolean glMultiDrawArraysIndirect(int mode, long indirect, int drawcount, int stride) {
        return primitive(mode) && aValue(drawcount >= 0 && (stride == 0 || stride >= 16 && stride % 4 == 0));
    }

    public boolean multiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
        return anEnum(drawMode(mode) && (type == GL11.GL_UNSIGNED_BYTE || type == GL11.GL_UNSIGNED_SHORT || type == GL11.GL_UNSIGNED_INT))
            && aValue(drawcount >= 0 && (stride == 0 || stride >= 20 && stride % 4 == 0));
    }

    public boolean glDrawArraysInstancedBaseInstance(int mode, int first, int count, int instancecount) {
        return draw(mode, count) && aValue(first >= 0 && instancecount >= 0);
    }

    public boolean glDrawElementsInstancedBaseVertexBaseInstance(int mode, int count, int type, long indices, int instancecount) {
        return indexed(mode, count, type) && aValue(instancecount >= 0);
    }

    public boolean glMemoryBarrierByRegion(int barriers) {
        final int allowed = GL42.GL_ATOMIC_COUNTER_BARRIER_BIT | GL42.GL_FRAMEBUFFER_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
            | GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_UNIFORM_BARRIER_BIT;
        return aValue(barriers == GL42.GL_ALL_BARRIER_BITS || (barriers & ~allowed) == 0);
    }

    /** A robust read refuses a buffer too small for the uniform, as GL 4.5 says. */
    private boolean robustUniformRead(int program, int location, int bufSize, int bytes) {
        if (!uniformRead(program, location)) return false;
        final ProgramBase.Uniform u = uniformAt(program(program), location);
        return anOperation((long) com.gtnewhorizons.angelica.glemu.shader.ProgramsBase.components(u.glType) * bytes <= bufSize);
    }

    public boolean glGetnUniformfv(int program, int location, int bufSize) {
        return robustUniformRead(program, location, bufSize, 4);
    }

    public boolean glGetnUniformiv(int program, int location, int bufSize) {
        return robustUniformRead(program, location, bufSize, 4);
    }

    public boolean glGetnUniformuiv(int program, int location, int bufSize) {
        return robustUniformRead(program, location, bufSize, 4);
    }

    public boolean glGetnUniformdv(int program, int location, int bufSize) {
        return robustUniformRead(program, location, bufSize, 8);
    }

    /**
     * The level and size, then the name and whether the texture holds this compressed format, come before the region. A format decoded here
     * keeps the pixel format its compressed name maps to, so the comparison holds for it too.
     */
    private boolean compressedTextureSub(int texture, int level, int imageSize, int format) {
        if (!aValue(level >= 0 && level < 32 && imageSize >= 0) || !anOperation(texture != 0 && gl.textures().exists(texture))) return false;
        final Formats.Format f = gl.textures().format(format);
        final TexBase t = gl.textures().get(texture);
        return anOperation(f != null && f.isCompressed() && t != null && t.format != null && t.format.pf() == f.pf());
    }

    public boolean glCompressedTextureSubImage1D(int texture, int level, int xoffset, int width, int format, int imageSize) {
        // Here the drivers part ways, and NVIDIA looks at the texture first.
        return compressedTextureSub(texture, 0, 0, format) && aValue(level >= 0 && level < 32 && imageSize >= 0);
    }

    public boolean glCompressedTextureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int imageSize) {
        return compressedTextureSub(texture, level, imageSize, format);
    }

    public boolean glCompressedTextureSubImage3D(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int imageSize) {
        // Here the drivers part ways, and NVIDIA looks at the texture first.
        return compressedTextureSub(texture, 0, 0, format) && aValue(level >= 0 && level < 32 && imageSize >= 0);
    }

    public boolean pixelTransfer(int pname) {
        return anEnum(pname >= GL11.GL_MAP_COLOR && pname <= GL11.GL_DEPTH_BIAS && pname != GL11.GL_ZOOM_X && pname != GL11.GL_ZOOM_Y);
    }

    public boolean accum(int op) {
        return anEnum(op == GL11.GL_ACCUM || op == GL11.GL_LOAD || op == GL11.GL_RETURN || op == GL11.GL_MULT || op == GL11.GL_ADD);
    }

    /** The same checks for GLSM's overloads, which reach the backend without the entry point's. */
    public boolean namedBufferData(int buffer, int usage) {
        return onBuffer(buffer, () -> anEnum(BUFFER_USAGES.contains(usage)) && anOperation(!namedBuffer.immutable));
    }

    public boolean namedBufferSubData(int buffer, long offset, java.nio.ByteBuffer data) {
        return glNamedBufferSubData(buffer, offset, data == null ? 0 : data.remaining());
    }

    public boolean glNamedBufferSubData(int buffer, long offset, long size) {
        return onBuffer(buffer, () -> aValue(offset >= 0 && size >= 0 && offset + size <= namedBuffer.size) && subData(GL31.GL_COPY_WRITE_BUFFER, offset, size));
    }

    /** Table 8.2's sized formats a buffer texture can have, which are all a buffer can be cleared to. */
    private static final IntOpenHashSet BUFFER_CLEAR_FORMATS = set(GL30.GL_R8, GL30.GL_R16, GL30.GL_R16F, GL30.GL_R32F, GL30.GL_R8I, GL30.GL_R16I, GL30.GL_R32I, GL30.GL_R8UI, GL30.GL_R16UI, GL30.GL_R32UI,
        GL30.GL_RG8, GL30.GL_RG16, GL30.GL_RG16F, GL30.GL_RG32F, GL30.GL_RG8I, GL30.GL_RG16I, GL30.GL_RG32I, GL30.GL_RG8UI, GL30.GL_RG16UI, GL30.GL_RG32UI,
        GL30.GL_RGB32F, GL30.GL_RGB32I, GL30.GL_RGB32UI, GL11.GL_RGBA8, GL11.GL_RGBA16, GL30.GL_RGBA16F, GL30.GL_RGBA32F, GL30.GL_RGBA8I, GL30.GL_RGBA16I, GL30.GL_RGBA32I, GL30.GL_RGBA8UI, GL30.GL_RGBA16UI, GL30.GL_RGBA32UI);

    private boolean clearable(BufBase b, int internalFormat, long offset, long size, int format, int type) {
        if (!anEnum(BUFFER_CLEAR_FORMATS.contains(internalFormat))) return false;
        if (!aValue(PIXEL_FORMATS.contains(format) && !depthOrStencilPixels(format) && (PLAIN_TYPES.contains(type) || PACKED_RGB.contains(type) || PACKED_RGBA.contains(type)))) return false;
        final int texel = internalFormat == GL30.GL_RGB32F || internalFormat == GL30.GL_RGB32I || internalFormat == GL30.GL_RGB32UI ? 12 : gl.textures().format(internalFormat).texelBytes();
        if (!aValue(offset >= 0 && size >= 0 && offset + size <= b.size && (texel <= 1 || offset % texel == 0 && size % texel == 0))) return false;
        return anOperation(!b.mapped || (b.mapAccess & MAP_PERSISTENT_BIT) != 0);
    }

    private static boolean depthOrStencilPixels(int format) {
        return format == GL11.GL_DEPTH_COMPONENT || format == GL11.GL_STENCIL_INDEX || format == GL30.GL_DEPTH_STENCIL;
    }

    /** A target names a buffer binding, and the buffer bound there is the one cleared. */
    public boolean clearBufferData(int target, int internalformat, int format, int type) {
        if (!anEnum(BUFFER_TARGETS.contains(target))) return false;
        final BufBase b = gl.boundBuffer(target);
        return anOperation(b != null) && clearable(b, internalformat, 0L, b.size, format, type);
    }

    public boolean clearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type) {
        if (!anEnum(BUFFER_TARGETS.contains(target))) return false;
        final BufBase b = gl.boundBuffer(target);
        return anOperation(b != null) && clearable(b, internalFormat, offset, size, format, type);
    }

    public boolean glClearNamedBufferData(int buffer, int internalFormat, int format, int type) {
        return onBuffer(buffer, () -> clearable(namedBuffer, internalFormat, 0L, namedBuffer.size, format, type));
    }

    public boolean glClearNamedBufferSubData(int buffer, int internalFormat, long offset, long size, int format, int type) {
        return onBuffer(buffer, () -> clearable(namedBuffer, internalFormat, offset, size, format, type));
    }

    public boolean glCopyNamedBufferSubData(int readBuffer, int writeBuffer, long readOffset, long writeOffset, long size) {
        final BufBase read = readBuffer != 0 && gl.buffers().exists(readBuffer) ? gl.buffers().get(readBuffer) : null;
        final BufBase write = writeBuffer != 0 && gl.buffers().exists(writeBuffer) ? gl.buffers().get(writeBuffer) : null;
        if (!anOperation(read != null && write != null)) return false;
        if (!aValue(readOffset >= 0 && writeOffset >= 0 && size >= 0 && readOffset + size <= read.size && writeOffset + size <= write.size)) return false;
        if (!aValue(read != write || readOffset + size <= writeOffset || writeOffset + size <= readOffset)) return false;
        return anOperation((!read.mapped || (read.mapAccess & MAP_PERSISTENT_BIT) != 0) && (!write.mapped || (write.mapAccess & MAP_PERSISTENT_BIT) != 0));
    }

    public boolean glMapNamedBuffer(int buffer, int access) {
        return onBuffer(buffer, () -> mapBuffer(GL31.GL_COPY_WRITE_BUFFER, access));
    }

    public boolean glMapNamedBufferRange(int buffer, long offset, long length, int access) {
        return onBuffer(buffer, () -> anOperation(length != 0) && mapBufferRange(GL31.GL_COPY_WRITE_BUFFER, offset, length, access));
    }

    public boolean glUnmapNamedBuffer(int buffer) {
        return onBuffer(buffer, () -> unmapBuffer(GL31.GL_COPY_WRITE_BUFFER));
    }

    public boolean glFlushMappedNamedBufferRange(int buffer, long offset, long length) {
        return onBuffer(buffer, () -> flushMappedBufferRange(GL31.GL_COPY_WRITE_BUFFER, offset, length));
    }

    public boolean glGetNamedBufferParameteriv(int buffer, int pname) {
        return onBuffer(buffer, () -> anEnum(BUFFER_QUERIES.contains(pname)));
    }

    public boolean glGetNamedBufferParameteri64v(int buffer, int pname) {
        return onBuffer(buffer, () -> anEnum(BUFFER_QUERIES.contains(pname)));
    }

    public boolean glGetNamedBufferPointerv(int buffer, int pname) {
        return onBuffer(buffer, () -> anEnum(pname == GL15.GL_BUFFER_MAP_POINTER));
    }

    public boolean glGetNamedBufferSubData(int buffer, long offset, long size) {
        return onBuffer(buffer, () -> aValue(offset >= 0 && size >= 0 && offset + size <= namedBuffer.size) && anOperation(!namedBuffer.mapped || (namedBuffer.mapAccess & MAP_PERSISTENT_BIT) != 0));
    }

    public boolean glCheckNamedFramebufferStatus(int framebuffer, int target) {
        return onFramebuffer(framebuffer, true, () -> framebufferTarget(target));
    }

    public boolean glClearNamedFramebufferiv(int framebuffer, int buffer, int drawbuffer) {
        return onFramebuffer(framebuffer, true, () -> clearBuffer(buffer, drawbuffer, false, true));
    }

    public boolean glClearNamedFramebufferuiv(int framebuffer, int buffer, int drawbuffer) {
        return onFramebuffer(framebuffer, true, () -> clearBuffer(buffer, drawbuffer, false, false));
    }

    public boolean glClearNamedFramebufferfv(int framebuffer, int buffer, int drawbuffer) {
        return onFramebuffer(framebuffer, true, () -> clearBuffer(buffer, drawbuffer, true, false));
    }

    public boolean glClearNamedFramebufferfi(int framebuffer, int buffer, int drawbuffer) {
        return onFramebuffer(framebuffer, true, () -> glClearBufferfi(buffer, drawbuffer));
    }

    private static boolean defaultDrawBuffer(int mode) {
        return mode == GL11.GL_NONE || mode == GL11.GL_BACK || mode == GL11.GL_FRONT || mode == GL11.GL_LEFT || mode == GL11.GL_RIGHT || mode == GL11.GL_FRONT_AND_BACK
            || mode == GL11.GL_FRONT_LEFT || mode == GL11.GL_FRONT_RIGHT || mode == GL11.GL_BACK_LEFT || mode == GL11.GL_BACK_RIGHT;
    }

    private boolean drawBufferOf(Fbo f, int mode) {
        if (f == null || f.id == 0) return anEnum(defaultDrawBuffer(mode) || mode >= GL30.GL_COLOR_ATTACHMENT0 && mode <= GL30.GL_COLOR_ATTACHMENT0 + 31 || mode >= GL11.GL_AUX0 && mode <= GL11.GL_AUX3) && anOperation(defaultDrawBuffer(mode));
        return anEnum(mode == GL11.GL_NONE || mode >= GL30.GL_COLOR_ATTACHMENT0 && mode <= GL30.GL_COLOR_ATTACHMENT0 + 31 || defaultDrawBuffer(mode)) && anOperation(!defaultDrawBuffer(mode) || mode == GL11.GL_NONE)
            && (mode == GL11.GL_NONE || aValue(mode - GL30.GL_COLOR_ATTACHMENT0 < gl.getInteger(GL30.GL_MAX_COLOR_ATTACHMENTS)));
    }

    public boolean glNamedFramebufferDrawBuffer(int framebuffer, int mode) {
        return onFramebuffer(framebuffer, true, () -> drawBufferOf(namedFramebuffer, mode));
    }

    public boolean glNamedFramebufferDrawBuffers(int framebuffer, int n, long bufs) {
        return onFramebuffer(framebuffer, true, () -> {
            if (!aValue(n >= 0 && n <= gl.getInteger(GL20.GL_MAX_DRAW_BUFFERS))) return false;
            final boolean fbo = namedFramebuffer != null && namedFramebuffer.id != 0;
            final java.util.BitSet seen = new java.util.BitSet();
            for (int i = 0; i < n; i++) {
                final int mode = org.lwjgl.system.MemoryUtil.memGetInt(bufs + i * 4L);
                final boolean attachmentName = mode >= GL30.GL_COLOR_ATTACHMENT0 && mode <= GL30.GL_COLOR_ATTACHMENT0 + 31;
                final boolean single = mode == GL11.GL_FRONT_LEFT || mode == GL11.GL_FRONT_RIGHT || mode == GL11.GL_BACK_LEFT || mode == GL11.GL_BACK_RIGHT;
                if (!anEnum(mode == GL11.GL_NONE || attachmentName || single || mode == GL11.GL_BACK && !fbo)) return false;
                if (mode == GL11.GL_BACK && !anOperation(n == 1)) return false;
                if (!anOperation(fbo ? !single : !attachmentName)) return false;
                if (attachmentName && !anOperation(mode - GL30.GL_COLOR_ATTACHMENT0 < gl.getInteger(GL30.GL_MAX_COLOR_ATTACHMENTS))) return false;
                if (mode != GL11.GL_NONE && !anOperation(!seen.get(mode))) return false;
                seen.set(mode);
            }
            return true;
        });
    }

    public boolean glNamedFramebufferReadBuffer(int framebuffer, int src) {
        return onFramebuffer(framebuffer, true, () -> {
            final boolean fbo = namedFramebuffer != null && namedFramebuffer.id != 0;
            final boolean attachmentName = src >= GL30.GL_COLOR_ATTACHMENT0 && src <= GL30.GL_COLOR_ATTACHMENT0 + 31;
            if (!anEnum(src == GL11.GL_NONE || attachmentName || defaultDrawBuffer(src))) return false;
            if (!anOperation(src == GL11.GL_NONE || fbo == attachmentName)) return false;
            return !attachmentName || anOperation(src - GL30.GL_COLOR_ATTACHMENT0 < gl.getInteger(GL30.GL_MAX_COLOR_ATTACHMENTS));
        });
    }

    public boolean glGetNamedFramebufferAttachmentParameteriv(int framebuffer, int attachment, int pname) {
        return onFramebuffer(framebuffer, true, () -> {
            final boolean fbo = namedFramebuffer != null && namedFramebuffer.id != 0;
            if (fbo) {
                if (!anEnum(attachmentPoint(attachment))) return false;
                if (attachment >= GL30.GL_COLOR_ATTACHMENT0 && attachment <= GL30.GL_COLOR_ATTACHMENT0 + 31 && !anOperation(attachment - GL30.GL_COLOR_ATTACHMENT0 < gl.getInteger(GL30.GL_MAX_COLOR_ATTACHMENTS))) return false;
            } else if (!anEnum(attachment == GL11.GL_FRONT_LEFT || attachment == GL11.GL_FRONT_RIGHT || attachment == GL11.GL_BACK_LEFT || attachment == GL11.GL_BACK_RIGHT
                || attachment == GL11.GL_DEPTH || attachment == GL11.GL_STENCIL)) {
                return false;
            }
            if (!anEnum(pname >= GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING && pname <= GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE || pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
                || pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME || pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL || pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE
                || pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER || pname == GL32.GL_FRAMEBUFFER_ATTACHMENT_LAYERED)) {
                return false;
            }
            if (attachment == GL30.GL_DEPTH_STENCIL_ATTACHMENT && !anOperation(pname != GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE)) return false;
            if (!fbo) return true;
            if (attachment == GL30.GL_DEPTH_STENCIL_ATTACHMENT && !anOperation(namedFramebuffer.depthTex == namedFramebuffer.stencilTex && namedFramebuffer.depthRbo == namedFramebuffer.stencilRbo)) return false;
            final boolean nothing = attachedNothing(namedFramebuffer, attachment);
            if (!nothing) {
                final boolean textureQuery = pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL || pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE
                    || pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER || pname == GL32.GL_FRAMEBUFFER_ATTACHMENT_LAYERED;
                return anEnum(!textureQuery || !attachedRenderbuffer(namedFramebuffer, attachment));
            }
            // With nothing attached only the type and name can be asked, and the rest is an error whose kind depends on the question.
            if (pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE || pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) return true;
            return anOperation(false);
        });
    }

    private static boolean attachedRenderbuffer(Fbo f, int attachment) {
        if (attachment >= GL30.GL_COLOR_ATTACHMENT0 && attachment < GL30.GL_COLOR_ATTACHMENT0 + Fbo.MAX_COLOR) return f.colorRbo[attachment - GL30.GL_COLOR_ATTACHMENT0];
        if (attachment == GL30.GL_STENCIL_ATTACHMENT) return f.stencilRbo;
        return f.depthRbo;
    }

    private static boolean attachedNothing(Fbo f, int attachment) {
        if (attachment >= GL30.GL_COLOR_ATTACHMENT0 && attachment <= GL30.GL_COLOR_ATTACHMENT0 + 31) return attachment - GL30.GL_COLOR_ATTACHMENT0 >= Fbo.MAX_COLOR || f.colorTex[attachment - GL30.GL_COLOR_ATTACHMENT0] == 0;
        if (attachment == GL30.GL_DEPTH_ATTACHMENT) return f.depthTex == 0;
        if (attachment == GL30.GL_STENCIL_ATTACHMENT) return f.stencilTex == 0;
        return f.depthTex == 0 && f.stencilTex == 0;
    }

    public boolean glGetNamedFramebufferParameteriv(int framebuffer, int pname) {
        return onFramebuffer(framebuffer, true, () -> {
            final boolean fbo = namedFramebuffer != null && namedFramebuffer.id != 0;
            final boolean settable = pname >= GL43.GL_FRAMEBUFFER_DEFAULT_WIDTH && pname <= GL43.GL_FRAMEBUFFER_DEFAULT_FIXED_SAMPLE_LOCATIONS;
            final boolean state = pname == GL13.GL_SAMPLES || pname == GL13.GL_SAMPLE_BUFFERS || pname == GL11.GL_DOUBLEBUFFER || pname == GL11.GL_STEREO
                || pname == GL41.GL_IMPLEMENTATION_COLOR_READ_FORMAT || pname == GL41.GL_IMPLEMENTATION_COLOR_READ_TYPE;
            return anEnum(settable || state) && anOperation(fbo || !settable);
        });
    }

    public boolean glNamedFramebufferParameteri(int framebuffer, int pname, int param) {
        return onFramebuffer(framebuffer, false, () -> framebufferParameter(pname, param));
    }

    private boolean namedAttachment(int attachment) {
        if (!anEnum(attachmentPoint(attachment))) return false;
        return attachment < GL30.GL_COLOR_ATTACHMENT0 || attachment > GL30.GL_COLOR_ATTACHMENT0 + 31 || anOperation(attachment - GL30.GL_COLOR_ATTACHMENT0 < gl.getInteger(GL30.GL_MAX_COLOR_ATTACHMENTS));
    }

    public boolean namedFramebufferRenderbuffer(int framebuffer, int attachment, int renderbuffertarget, int renderbuffer) {
        return onFramebuffer(framebuffer, false, () -> namedAttachment(attachment) && anEnum(renderbuffertarget == GL30.GL_RENDERBUFFER)
            && (renderbuffer == 0 || anOperation(gl.textures().renderbufferExists(renderbuffer))));
    }

    public boolean namedFramebufferTexture(int framebuffer, int attachment, int texture, int level) {
        return onFramebuffer(framebuffer, false, () -> {
            if (!namedAttachment(attachment)) return false;
            if (texture == 0) return true;
            final TexBase t = gl.textures().get(texture);
            return aValue(gl.textures().exists(texture)) && aValue(level >= 0 && level(level)) && anOperation(t == null || t.target != GL31.GL_TEXTURE_BUFFER);
        });
    }

    public boolean namedFramebufferTextureLayer(int framebuffer, int attachment, int texture, int level, int layer) {
        return onFramebuffer(framebuffer, false, () -> {
            if (!namedAttachment(attachment)) return false;
            if (texture == 0) return true;
            final TexBase t = gl.textures().get(texture);
            if (!anOperation(gl.textures().exists(texture) && t != null)) return false;
            if (!anOperation(t.target == GL12.GL_TEXTURE_3D || t.target == GL30.GL_TEXTURE_2D_ARRAY || t.target == GL30.GL_TEXTURE_1D_ARRAY || t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY
                || t.target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY || t.target == GL13.GL_TEXTURE_CUBE_MAP)) return false;
            if (!aValue(level >= 0 && level(level))) return false;
            final int limit = t.target == GL12.GL_TEXTURE_3D ? gl.getInteger(GL12.GL_MAX_3D_TEXTURE_SIZE) : t.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : gl.getInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS);
            return aValue(layer >= 0 && layer < limit);
        });
    }

    private boolean invalidating(int numAttachments, long attachments) {
        if (!aValue(numAttachments >= 0)) return false;
        final boolean fbo = namedFramebuffer != null && namedFramebuffer.id != 0;
        for (int i = 0; i < numAttachments; i++) {
            final int a = org.lwjgl.system.MemoryUtil.memGetInt(attachments + i * 4L);
            if (fbo) {
                if (!anEnum(attachmentPoint(a))) return false;
                if (a >= GL30.GL_COLOR_ATTACHMENT0 && a <= GL30.GL_COLOR_ATTACHMENT0 + 31 && !anOperation(a - GL30.GL_COLOR_ATTACHMENT0 < gl.getInteger(GL30.GL_MAX_COLOR_ATTACHMENTS))) return false;
            } else if (!anEnum(a == GL11.GL_COLOR || a == GL11.GL_DEPTH || a == GL11.GL_STENCIL)) {
                return false;
            }
        }
        return true;
    }

    public boolean glInvalidateNamedFramebufferData(int framebuffer, int numAttachments, long attachments) {
        return onFramebuffer(framebuffer, true, () -> invalidating(numAttachments, attachments));
    }

    public boolean glInvalidateNamedFramebufferSubData(int framebuffer, int numAttachments, long attachments, int x, int y, int width, int height) {
        return aValue(width >= 0 && height >= 0) && onFramebuffer(framebuffer, true, () -> invalidating(numAttachments, attachments));
    }

    public boolean glInvalidateFramebuffer(int target, int numAttachments, long attachments) {
        if (!framebufferTarget(target)) return false;
        namedFramebuffer = gl.fboFor(target);
        try {
            return invalidating(numAttachments, attachments);
        } finally {
            namedFramebuffer = null;
        }
    }

    public boolean glInvalidateSubFramebuffer(int target, int numAttachments, long attachments, int x, int y, int width, int height) {
        return aValue(width >= 0 && height >= 0) && glInvalidateFramebuffer(target, numAttachments, attachments);
    }

    private boolean namedRenderbuffer(int renderbuffer) {
        return anOperation(renderbuffer != 0 && gl.textures().renderbufferExists(renderbuffer));
    }

    public boolean glNamedRenderbufferStorage(int renderbuffer, int internalformat, int width, int height) {
        return namedRenderbuffer(renderbuffer) && renderable(internalformat) && aValue(width >= 0 && height >= 0 && width <= gl.getInteger(GL30.GL_MAX_RENDERBUFFER_SIZE) && height <= gl.getInteger(GL30.GL_MAX_RENDERBUFFER_SIZE));
    }

    public boolean glNamedRenderbufferStorageMultisample(int renderbuffer, int samples, int internalformat, int width, int height) {
        return namedRenderbuffer(renderbuffer) && renderable(internalformat) && aValue(samples >= 0 && width >= 0 && height >= 0 && width <= gl.getInteger(GL30.GL_MAX_RENDERBUFFER_SIZE) && height <= gl.getInteger(GL30.GL_MAX_RENDERBUFFER_SIZE))
            && samplesAllowed(samples) && integerSamplesAllowed(samples, internalformat);
    }

    public boolean glGetNamedRenderbufferParameteriv(int renderbuffer, int pname) {
        return namedRenderbuffer(renderbuffer) && anEnum(RENDERBUFFER_QUERIES.contains(pname));
    }

    public boolean bindTextureUnit(int unit, int texture) {
        com.gtnewhorizons.angelica.glsm.GLSMTextureLifetime.flushDeferredTextureDeletes();
        return aValue(unit >= 0 && unit < gl.getInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)) && (texture == 0 || anOperation(gl.textures().exists(texture)));
    }

    /** A cube map being built one face at a time has every face of its first level, or it is not cube complete. */
    private boolean cubeComplete(TexBase t) {
        return t.target != GL13.GL_TEXTURE_CUBE_MAP || t.immutable || t.definedFaces == 0 || t.definedFaces == 63;
    }

    public boolean glGenerateTextureMipmap(int texture) {
        return onTexture(texture, () -> anOperation(generateMipmap(namedTexture.target) && cubeComplete(namedTexture)));
    }

    public boolean glTextureParameteri(int texture, int pname, int param) {
        return onTexture(texture, () -> namedParameter(pname, (int) param) && texParameteri(namedTexture.target, pname, param));
    }

    public boolean glTextureParameterf(int texture, int pname, float param) {
        return onTexture(texture, () -> namedParameter(pname, (int) param) && texParameterf(namedTexture.target, pname, param));
    }

    public boolean glTextureParameteriv(int texture, int pname, long params) {
        return onTexture(texture, () -> namedParameter(pname, MemoryUtil.memGetInt(params)) && glTexParameteriv(namedTexture.target, pname, params));
    }

    public boolean glTextureParameterIiv(int texture, int pname, long params) {
        return onTexture(texture, () -> namedParameter(pname, MemoryUtil.memGetInt(params)) && glTexParameterIiv(namedTexture.target, pname, params));
    }

    public boolean glTextureParameterIuiv(int texture, int pname, long params) {
        return onTexture(texture, () -> namedParameter(pname, MemoryUtil.memGetInt(params)) && glTexParameterIuiv(namedTexture.target, pname, params));
    }

    public boolean glTextureParameterfv(int texture, int pname, long params) {
        return onTexture(texture, () -> namedParameter(pname, (int) MemoryUtil.memGetFloat(params)) && glTexParameterfv(namedTexture.target, pname, params));
    }

    public boolean glGetTextureParameteriv(int texture, int pname) {
        return onTexture(texture, () -> anOperation(namedTexture.target != GL31.GL_TEXTURE_BUFFER) && glGetTexParameteriv(namedTexture.target, pname));
    }

    public boolean glGetTextureParameterfv(int texture, int pname) {
        return onTexture(texture, () -> anOperation(namedTexture.target != GL31.GL_TEXTURE_BUFFER) && glGetTexParameterfv(namedTexture.target, pname));
    }

    public boolean glGetTextureParameterIiv(int texture, int pname) {
        return onTexture(texture, () -> anOperation(namedTexture.target != GL31.GL_TEXTURE_BUFFER) && glGetTexParameterIiv(namedTexture.target, pname));
    }

    public boolean glGetTextureParameterIuiv(int texture, int pname) {
        return onTexture(texture, () -> anOperation(namedTexture.target != GL31.GL_TEXTURE_BUFFER) && glGetTexParameterIuiv(namedTexture.target, pname));
    }

    public boolean glGetTextureLevelParameteriv(int texture, int level, int pname) {
        return onTexture(texture, () -> anEnum(LEVEL_PARAMETERS.contains(pname)) && level(level) && (pname != GL13.GL_TEXTURE_COMPRESSED_IMAGE_SIZE || anOperation(namedTexture.format != null && namedTexture.format.isCompressed())));
    }

    public boolean glGetTextureLevelParameterfv(int texture, int level, int pname) {
        return onTexture(texture, () -> anEnum(LEVEL_PARAMETERS.contains(pname)) && level(level) && (pname != GL13.GL_TEXTURE_COMPRESSED_IMAGE_SIZE || anOperation(namedTexture.format != null && namedTexture.format.isCompressed())));
    }

    public boolean glTextureBuffer(int texture, int internalFormat, int buffer) {
        return onTexture(texture, () -> anOperation(namedTarget() == GL31.GL_TEXTURE_BUFFER) && anEnum(gl.textures().knownInternalFormat(internalFormat)) && (buffer == 0 || anOperation(gl.buffers().exists(buffer))));
    }

    public boolean glTextureBufferRange(int texture, int internalFormat, int buffer, long offset, long size) {
        return glTextureBuffer(texture, internalFormat, buffer) && (buffer == 0 || aValue(offset >= 0 && size > 0 && offset + size <= gl.buffers().get(buffer).size && offset % Math.max(1, gl.getInteger(0x919F)) == 0));
    }

    public boolean glTextureStorage1D(int texture, int levels, int internalFormat, int width) {
        return onTexture(texture, () -> anOperation(namedTexture.target == GL11.GL_TEXTURE_1D) && textureStorage(levels, internalFormat, width, 1, 1) && anOperation(!namedTexture.immutable));
    }

    public boolean glTextureStorage2D(int texture, int levels, int internalFormat, int width, int height) {
        return onTexture(texture, () -> anOperation(namedTexture.target == GL11.GL_TEXTURE_2D || namedTexture.target == GL30.GL_TEXTURE_1D_ARRAY || namedTexture.target == GL31.GL_TEXTURE_RECTANGLE || namedTexture.target == GL13.GL_TEXTURE_CUBE_MAP)
            && textureStorage(levels, internalFormat, width, namedTexture.target == GL30.GL_TEXTURE_1D_ARRAY ? 1 : height, 1) && aValue(height >= 1) && anOperation(!namedTexture.immutable));
    }

    public boolean glTextureStorage3D(int texture, int levels, int internalFormat, int width, int height, int depth) {
        return onTexture(texture, () -> fitsThreeDimensions(namedTexture.target, internalFormat) && anOperation(namedTexture.target == GL12.GL_TEXTURE_3D || namedTexture.target == GL30.GL_TEXTURE_2D_ARRAY || namedTexture.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY)
            && textureStorage(levels, internalFormat, width, height, namedTexture.target == GL12.GL_TEXTURE_3D ? depth : 1) && aValue(depth >= 1) && anOperation(!namedTexture.immutable));
    }

    public boolean glTextureStorage2DMultisample(int texture, int samples, int internalFormat, int width, int height) {
        return onTexture(texture, () -> anOperation(namedTexture.target == GL32.GL_TEXTURE_2D_MULTISAMPLE) && sizedInternalFormat(internalFormat) && aValue(width >= 1 && height >= 1) && multisampleSize(samples, width, height, 1)
            && anOperation(!namedTexture.immutable) && integerSamplesAllowed(samples, internalFormat));
    }

    public boolean glTextureStorage3DMultisample(int texture, int samples, int internalFormat, int width, int height, int depth) {
        return onTexture(texture, () -> anOperation(namedTexture.target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY) && sizedInternalFormat(internalFormat) && aValue(width >= 1 && height >= 1 && depth >= 1) && multisampleSize(samples, width, height, depth)
            && anOperation(!namedTexture.immutable) && integerSamplesAllowed(samples, internalFormat));
    }

    private boolean unpackBufferUsable(int type, long pixels) {
        final BufBase unpack = gl.st().pixelUnpack == 0 ? null : gl.boundBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
        if (unpack == null) return true;
        return anOperation((!unpack.mapped || (unpack.mapAccess & MAP_PERSISTENT_BIT) != 0) && pixels % Math.max(1, com.gtnewhorizons.angelica.glemu.resource.PixelConvert.typeBytes(type)) == 0);
    }

    private boolean namedSubImage(int level, int x, int y, int z, int width, int height, int depth, int format, int type, long pixels) {
        if (!subImage(namedTexture.target, level, width, height, depth, format, type) || !unpackBufferUsable(type, pixels)) return false;
        return aValue(x >= 0 && y >= 0 && z >= 0 && (long) x + width <= namedTexture.levelWidth(level) && (long) y + height <= namedTexture.levelHeight(level)
            && (long) z + depth <= (namedTexture.is3D() ? namedTexture.levelDepth(level) : Math.max(1, namedTexture.layers)));
    }

    public boolean glTextureSubImage1D(int texture, int level, int xoffset, int width, int format, int type, long pixels) {
        return onTexture(texture, () -> anOperation(namedTexture.target == GL11.GL_TEXTURE_1D) && namedSubImage(level, xoffset, 0, 0, width, 1, 1, format, type, pixels));
    }

    public boolean glTextureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels) {
        return onTexture(texture, () -> anOperation(namedTexture.target != GL11.GL_TEXTURE_1D && namedTexture.target != GL12.GL_TEXTURE_3D && namedTexture.target != GL30.GL_TEXTURE_2D_ARRAY) && namedSubImage(level, xoffset, yoffset, 0, width, height, 1, format, type, pixels));
    }

    public boolean glTextureSubImage3D(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, long pixels) {
        return onTexture(texture, () -> anOperation(namedTexture.target == GL12.GL_TEXTURE_3D || namedTexture.target == GL30.GL_TEXTURE_2D_ARRAY || namedTexture.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY || namedTexture.target == GL13.GL_TEXTURE_CUBE_MAP)
            && namedSubImage(level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels));
    }

    private boolean attribOf(int vaobj, int index) {
        return onVertexArray(vaobj, () -> attribIndex(index));
    }

    public boolean glEnableVertexArrayAttrib(int vaobj, int index) {
        return attribOf(vaobj, index);
    }

    public boolean glDisableVertexArrayAttrib(int vaobj, int index) {
        return attribOf(vaobj, index);
    }

    private boolean bindingOf(int vaobj, int bindingindex) {
        return onVertexArray(vaobj, () -> aValue(bindingindex >= 0 && bindingindex < gl.getInteger(GL43.GL_MAX_VERTEX_ATTRIB_BINDINGS)));
    }

    public boolean glVertexArrayAttribFormat(int vaobj, int attribindex, int size, int type, boolean normalized, int relativeoffset) {
        return onVertexArray(vaobj, () -> true);
    }

    public boolean glVertexArrayAttribIFormat(int vaobj, int attribindex, int size, int type, int relativeoffset) {
        return onVertexArray(vaobj, () -> true);
    }

    public boolean glVertexArrayBindingDivisor(int vaobj, int bindingindex) {
        return bindingOf(vaobj, bindingindex);
    }

    public boolean glVertexArrayAttribBinding(int vaobj, int attribindex, int bindingindex) {
        return attribOf(vaobj, attribindex) && bindingOf(vaobj, bindingindex);
    }

    public boolean glVertexArrayElementBuffer(int vaobj, int buffer) {
        return onVertexArray(vaobj, () -> buffer == 0 || anOperation(gl.buffers().exists(buffer)));
    }

    public boolean glVertexArrayVertexBuffer(int vaobj, int bindingindex, int buffer, long offset, int stride) {
        return bindingOf(vaobj, bindingindex) && (!strictCore || buffer == 0 || anOperation(gl.buffers().exists(buffer))) && aValue(offset >= 0 && stride >= 0 && stride <= gl.getInteger(GL44.GL_MAX_VERTEX_ATTRIB_STRIDE));
    }

    public boolean glVertexArrayVertexBuffers(int vaobj, int first, int count) {
        return onVertexArray(vaobj, () -> aValue(count >= 0) && anOperation(first >= 0 && (long) first + count <= gl.getInteger(GL43.GL_MAX_VERTEX_ATTRIB_BINDINGS)));
    }

    public boolean glGetVertexArrayiv(int vaobj, int pname) {
        return onVertexArray(vaobj, () -> anEnum(pname == GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));
    }

    private static final IntOpenHashSet INDEXED_ARRAY_QUERIES = set(GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE, GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE,
        GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED, GL30.GL_VERTEX_ATTRIB_ARRAY_INTEGER, GL46C.GL_VERTEX_ATTRIB_ARRAY_LONG, GL33.GL_VERTEX_ATTRIB_ARRAY_DIVISOR, GL43.GL_VERTEX_ATTRIB_RELATIVE_OFFSET);

    public boolean glGetVertexArrayIndexediv(int vaobj, int index, int pname) {
        // Drivers answer the binding's offset here too, as an integer.
        if (!INDEXED_ARRAY_QUERIES.contains(pname) && pname != GL43.GL_VERTEX_BINDING_OFFSET) return anEnum(false);
        return attribOf(vaobj, index);
    }

    public boolean glGetVertexArrayIndexed64iv(int vaobj, int index, int pname) {
        return anOperation(vaobj == 0 && !strictCore || gl.isVertexArrayName(vaobj)) && anEnum(pname == GL43.GL_VERTEX_BINDING_OFFSET) && attribOf(vaobj, index);
    }

    public boolean glTransformFeedbackBufferBase(int xfb, int index, int buffer) {
        return aValue(index >= 0 && index < gl.getInteger(GL30.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS)) && anOperation(xfb == 0 || GlTransformFeedback.exists(xfb)) && (buffer == 0 || anOperation(gl.buffers().exists(buffer)));
    }

    public boolean glTransformFeedbackBufferRange(int xfb, int index, int buffer, long offset, long size) {
        return glTransformFeedbackBufferBase(xfb, index, buffer) && aValue(buffer == 0 || offset >= 0 && size > 0 && offset % 4 == 0 && size % 4 == 0 && offset + size <= gl.buffers().get(buffer).size);
    }

    public boolean glGetTransformFeedbackiv(int xfb, int pname) {
        return anOperation(xfb == 0 || GlTransformFeedback.exists(xfb)) && anEnum(pname == 0x8E23 || pname == 0x8E24);
    }

    public boolean glGetTransformFeedbacki_v(int xfb, int pname, int index) {
        return aValue(index >= 0 && index < gl.getInteger(GL30.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS))
            && anOperation(xfb == 0 || GlTransformFeedback.exists(xfb)) && anEnum(pname == GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);
    }

    public boolean glGetTransformFeedbacki64_v(int xfb, int pname, int index) {
        return aValue(index >= 0 && index < gl.getInteger(GL30.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS))
            && anOperation(xfb == 0 || GlTransformFeedback.exists(xfb)) && anEnum(pname == GL30.GL_TRANSFORM_FEEDBACK_BUFFER_START || pname == GL30.GL_TRANSFORM_FEEDBACK_BUFFER_SIZE);
    }

    public boolean bufferStorage(int target, int flags) {
        if (!anEnum(BUFFER_TARGETS.contains(target))) return false;
        final BufBase b = bound(target);
        if (!anOperation(b != null && !b.immutable)) return false;
        return aValue((flags & ~STORAGE_FLAGS) == 0 && ((flags & MAP_PERSISTENT_BIT) == 0 || (flags & (GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT)) != 0)
            && ((flags & GL44.GL_MAP_COHERENT_BIT) == 0 || (flags & MAP_PERSISTENT_BIT) != 0));
    }

    private boolean fitsBufSize(TexBase t, int level, int format, int type, int bufSize) {
        if (!aValue(level >= 0 && level(level))) return false;
        if (t.format == null || level >= t.levels) return true;
        if (!anOperation(gl.st().pixelPack != 0 || bufSize >= com.gtnewhorizons.angelica.glemu.resource.PixelConvert.clientBytes(t.levelWidth(level), t.levelHeight(level), t.is3D() ? t.levelDepth(level) : Math.max(1, t.layers), format, type, gl.st().pack))) return false;
        return packBufferUsable();
    }

    /** A pack buffer offset has to be a multiple of the type's size. */
    private boolean packOffsetAligned(int type, long offset) {
        return gl.st().pixelPack == 0 || anOperation(offset % com.gtnewhorizons.angelica.glemu.resource.PixelConvert.typeBytes(type) == 0);
    }

    public boolean glGetTextureImage(int texture, int level, int format, int type, int bufSize, long pixels) {
        return onTexture(texture, () -> anOperation(cubeComplete(namedTexture)) && (namedTarget() != GL31.GL_TEXTURE_RECTANGLE || aValue(level == 0)) && pixels(format, type) && packOffsetAligned(type, pixels) && anOperation(!multisampleTarget(namedTexture.target) && namedTexture.target != GL31.GL_TEXTURE_BUFFER) && (namedTexture.format == null || pixelsFit(namedTexture.format, format)) && fitsBufSize(namedTexture, level, format, type, bufSize));
    }

    public boolean glGetCompressedTextureImage(int texture, int level, int bufSize) {
        return onTexture(texture, () -> {
            if (!aValue(level >= 0 && level(level))) return false;
            if (!anOperation(namedTexture.format != null && namedTexture.format.isCompressed() && level < namedTexture.levels)) return false;
            final int faces = namedTexture.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : namedTexture.is3D() ? namedTexture.levelDepth(level) : Math.max(1, namedTexture.layers);
            return packBufferUsable() && anOperation(gl.st().pixelPack != 0 || bufSize >= Formats.imageBytes(namedTexture.format, namedTexture.levelWidth(level), namedTexture.levelHeight(level)) * faces);
        });
    }

    /** A 1D array's layers are its rows, and every target without depth has only slice 0 unless it has layers. */
    private static boolean regionFits(TexBase t, int level, int x, int y, int z, int width, int height, int depth) {
        final boolean rowsAreLayers = t.target == GL30.GL_TEXTURE_1D_ARRAY;
        final int rows = rowsAreLayers ? Math.max(1, t.layers) : t.levelHeight(level);
        final int slices = rowsAreLayers ? 1 : t.is3D() ? t.levelDepth(level) : t.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : Math.max(1, t.layers);
        return (long) x + width <= t.levelWidth(level) && (long) y + height <= rows && (long) z + depth <= slices;
    }

    public boolean glGetTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth, int format, int type, int bufSize, long pixels) {
        return onTexture(texture, () -> anOperation(!multisampleTarget(namedTexture.target)) && pixels(format, type) && packOffsetAligned(type, pixels) && aValue(x >= 0 && y >= 0 && z >= 0 && width >= 0 && height >= 0 && depth >= 0) && level(level)
            && aValue(namedTexture.format == null || level < Math.max(1, namedTexture.levels))
            && aValue(!oneDimensional(namedTexture.target) || y == 0 && height == 1) && aValue(!flat(namedTexture.target) || z == 0 && depth == 1)
            && anOperation(!depthOrStencilPixels(format) || namedTexture.format == null || namedTexture.format.isDepthOrStencil())
            && aValue(namedTexture.format == null || level >= namedTexture.levels || regionFits(namedTexture, level, x, y, z, width, height, depth))
            && anOperation(gl.st().pixelPack != 0 || bufSize >= com.gtnewhorizons.angelica.glemu.resource.PixelConvert.clientBytes(width, height, depth, format, type, gl.st().pack)));
    }

    public boolean glGetCompressedTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth, int bufSize) {
        return onTexture(texture, () -> aValue(x >= 0 && y >= 0 && z >= 0 && width >= 0 && height >= 0 && depth >= 0) && level(level)
            && aValue(namedTexture.format == null || level < Math.max(1, namedTexture.levels))
            && aValue(namedTexture.format == null || level >= namedTexture.levels || regionFits(namedTexture, level, x, y, z, width, height, depth))
            && aValue(namedTexture.target != GL11.GL_TEXTURE_2D || z == 0 && depth == 1) && anOperation(namedTexture.format != null && namedTexture.format.isCompressed() && level < namedTexture.levels)
            && anOperation(gl.st().pixelPack != 0 || bufSize >= Formats.imageBytes(namedTexture.format, width, height) * depth));
    }
}
