package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.shader.StageReflection;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL44;

/** Maps GL state to the {@link NativeTokens} vocabulary. */
public final class TokenMaps {
    public static final int KIND_FLOAT = 0, KIND_UINT = 1, KIND_SINT = 2, KIND_DEPTH = 3;

    private TokenMaps() {}

    public static int blendFactor(int f) {
        return switch (f) {
            case GL11.GL_ZERO -> NativeTokens.BLEND_ZERO;
            case GL11.GL_ONE -> NativeTokens.BLEND_ONE;
            case GL11.GL_SRC_COLOR -> NativeTokens.BLEND_SRC_COLOR;
            case GL11.GL_ONE_MINUS_SRC_COLOR -> NativeTokens.BLEND_ONE_MINUS_SRC_COLOR;
            case GL11.GL_DST_COLOR -> NativeTokens.BLEND_DST_COLOR;
            case GL11.GL_ONE_MINUS_DST_COLOR -> NativeTokens.BLEND_ONE_MINUS_DST_COLOR;
            case GL11.GL_SRC_ALPHA -> NativeTokens.BLEND_SRC_ALPHA;
            case GL11.GL_ONE_MINUS_SRC_ALPHA -> NativeTokens.BLEND_ONE_MINUS_SRC_ALPHA;
            case GL11.GL_DST_ALPHA -> NativeTokens.BLEND_DST_ALPHA;
            case GL11.GL_ONE_MINUS_DST_ALPHA -> NativeTokens.BLEND_ONE_MINUS_DST_ALPHA;
            case GL14.GL_CONSTANT_COLOR -> NativeTokens.BLEND_BLEND_COLOR;
            case GL14.GL_ONE_MINUS_CONSTANT_COLOR -> NativeTokens.BLEND_ONE_MINUS_BLEND_COLOR;
            case GL14.GL_CONSTANT_ALPHA -> NativeTokens.BLEND_BLEND_ALPHA;
            case GL14.GL_ONE_MINUS_CONSTANT_ALPHA -> NativeTokens.BLEND_ONE_MINUS_BLEND_ALPHA;
            case GL11.GL_SRC_ALPHA_SATURATE -> NativeTokens.BLEND_SRC_ALPHA_SATURATED;
            case GL33.GL_SRC1_COLOR -> NativeTokens.BLEND_SRC1_COLOR;
            case GL33.GL_ONE_MINUS_SRC1_COLOR -> NativeTokens.BLEND_ONE_MINUS_SRC1_COLOR;
            case GL15.GL_SRC1_ALPHA -> NativeTokens.BLEND_SRC1_ALPHA;
            case GL33.GL_ONE_MINUS_SRC1_ALPHA -> NativeTokens.BLEND_ONE_MINUS_SRC1_ALPHA;
            default -> NativeTokens.BLEND_ONE;
        };
    }

    public static int blendOp(int eq) {
        return switch (eq) {
            case GL14.GL_FUNC_SUBTRACT -> NativeTokens.BLEND_OP_SUBTRACT;
            case GL14.GL_FUNC_REVERSE_SUBTRACT -> NativeTokens.BLEND_OP_REVERSE_SUBTRACT;
            case GL14.GL_MIN -> NativeTokens.BLEND_OP_MIN;
            case GL14.GL_MAX -> NativeTokens.BLEND_OP_MAX;
            default -> NativeTokens.BLEND_OP_ADD;
        };
    }

    public static int stencilOp(int op) {
        return switch (op) {
            case GL11.GL_ZERO -> NativeTokens.STENCIL_ZERO;
            case GL11.GL_REPLACE -> NativeTokens.STENCIL_REPLACE;
            case GL11.GL_INCR -> NativeTokens.STENCIL_INCREMENT_CLAMP;
            case GL11.GL_DECR -> NativeTokens.STENCIL_DECREMENT_CLAMP;
            case GL11.GL_INVERT -> NativeTokens.STENCIL_INVERT;
            case GL14.GL_INCR_WRAP -> NativeTokens.STENCIL_INCREMENT_WRAP;
            case GL14.GL_DECR_WRAP -> NativeTokens.STENCIL_DECREMENT_WRAP;
            default -> NativeTokens.STENCIL_KEEP;
        };
    }

    public static int compareOp(int f) {
        return switch (f) {
            case GL11.GL_NEVER -> NativeTokens.COMPARE_NEVER;
            case GL11.GL_LESS -> NativeTokens.COMPARE_LESS;
            case GL11.GL_EQUAL -> NativeTokens.COMPARE_EQUAL;
            case GL11.GL_LEQUAL -> NativeTokens.COMPARE_LESS_EQUAL;
            case GL11.GL_GREATER -> NativeTokens.COMPARE_GREATER;
            case GL11.GL_NOTEQUAL -> NativeTokens.COMPARE_NOT_EQUAL;
            case GL11.GL_GEQUAL -> NativeTokens.COMPARE_GREATER_EQUAL;
            default -> NativeTokens.COMPARE_ALWAYS;
        };
    }

    public static int wrap(int gl) {
        return switch (gl) {
            case GL12.GL_CLAMP_TO_EDGE, GL11.GL_CLAMP -> NativeTokens.ADDRESS_CLAMP_TO_EDGE;
            case GL13.GL_CLAMP_TO_BORDER -> NativeTokens.ADDRESS_CLAMP_TO_BORDER;
            case GL14.GL_MIRRORED_REPEAT -> NativeTokens.ADDRESS_MIRROR_REPEAT;
            case GL44.GL_MIRROR_CLAMP_TO_EDGE -> NativeTokens.ADDRESS_MIRROR_CLAMP_TO_EDGE;
            default -> NativeTokens.ADDRESS_REPEAT;
        };
    }

    public static int primitive(int mode) {
        return switch (mode) {
            case GL11.GL_POINTS -> NativeTokens.PRIM_POINT;
            case GL11.GL_LINES -> NativeTokens.PRIM_LINE;
            case GL11.GL_LINE_STRIP, GL11.GL_LINE_LOOP -> NativeTokens.PRIM_LINE_STRIP;
            case GL11.GL_TRIANGLE_STRIP -> NativeTokens.PRIM_TRIANGLE_STRIP;
            default -> NativeTokens.PRIM_TRIANGLE;
        };
    }

    public static int topologyClass(int primitive) {
        return switch (primitive) {
            case NativeTokens.PRIM_POINT -> NativeTokens.TOPOLOGY_CLASS_POINT;
            case NativeTokens.PRIM_LINE, NativeTokens.PRIM_LINE_STRIP -> NativeTokens.TOPOLOGY_CLASS_LINE;
            default -> NativeTokens.TOPOLOGY_CLASS_TRIANGLE;
        };
    }

    public static boolean isIntegerFormat(int fmt) {
        return switch (fmt) {
            case NativeTokens.PF_R8_UINT, NativeTokens.PF_R8_SINT, NativeTokens.PF_R16_UINT, NativeTokens.PF_R16_SINT, NativeTokens.PF_RG8_UINT, NativeTokens.PF_RG8_SINT, NativeTokens.PF_R32_UINT, NativeTokens.PF_R32_SINT,
                 NativeTokens.PF_RG16_UINT, NativeTokens.PF_RG16_SINT, NativeTokens.PF_RGBA8_UINT, NativeTokens.PF_RGBA8_SINT, NativeTokens.PF_RGB10A2_UINT, NativeTokens.PF_RG32_UINT, NativeTokens.PF_RG32_SINT,
                 NativeTokens.PF_RGBA16_UINT, NativeTokens.PF_RGBA16_SINT, NativeTokens.PF_RGBA32_UINT, NativeTokens.PF_RGBA32_SINT -> true;
            default -> false;
        };
    }

    public static final int SWIZZLE_TO_ZERO = -1, SWIZZLE_TO_ONE = -2;

    /** The channel a GL swizzle value reads, or one of the constants above; anything else keeps the channel itself. */
    public static int swizzleSource(int glSwizzle, int channel) {
        return switch (glSwizzle) {
            case GL11.GL_RED -> 0;
            case GL11.GL_GREEN -> 1;
            case GL11.GL_BLUE -> 2;
            case GL11.GL_ALPHA -> 3;
            case GL11.GL_ZERO -> SWIZZLE_TO_ZERO;
            case GL11.GL_ONE -> SWIZZLE_TO_ONE;
            default -> channel;
        };
    }

    public static int swizzle(int glSwizzle, int[] base, int channel) {
        final int requested = swizzleSource(glSwizzle, channel);
        if (requested == SWIZZLE_TO_ZERO) return NativeTokens.SWIZZLE_ZERO;
        if (requested == SWIZZLE_TO_ONE) return NativeTokens.SWIZZLE_ONE;
        if (base != null) return base[requested];
        return NativeTokens.SWIZZLE_RED + requested;
    }

    public static int textureTypeFor(TexBase t) {
        if (t.samples > 1) return t.layers > 1 || t.target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY ? NativeTokens.TEX_2D_MS_ARRAY : NativeTokens.TEX_2D_MS;
        if (t.is3D()) return NativeTokens.TEX_3D;
        return switch (t.target) {
            case GL30.GL_TEXTURE_1D_ARRAY, GL30.GL_TEXTURE_2D_ARRAY -> NativeTokens.TEX_2D_ARRAY;
            case GL13.GL_TEXTURE_CUBE_MAP -> NativeTokens.TEX_CUBE;
            case GL40.GL_TEXTURE_CUBE_MAP_ARRAY -> NativeTokens.TEX_CUBE_ARRAY;
            default -> NativeTokens.TEX_2D;
        };
    }

    public static int glTargetForViewType(int viewType) {
        return switch (viewType) {
            case NativeTokens.TEX_2D_ARRAY -> GL30.GL_TEXTURE_2D_ARRAY;
            case NativeTokens.TEX_2D_MS -> GL32.GL_TEXTURE_2D_MULTISAMPLE;
            case NativeTokens.TEX_2D_MS_ARRAY -> GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
            case NativeTokens.TEX_3D -> GL12.GL_TEXTURE_3D;
            case NativeTokens.TEX_CUBE -> GL13.GL_TEXTURE_CUBE_MAP;
            case NativeTokens.TEX_CUBE_ARRAY -> GL40.GL_TEXTURE_CUBE_MAP_ARRAY;
            default -> GL11.GL_TEXTURE_2D;
        };
    }

    public static int viewTypeFor(int dim, boolean arrayed) {
        if (dim == StageReflection.DIM_MULTISAMPLE) return arrayed ? NativeTokens.TEX_2D_MS_ARRAY : NativeTokens.TEX_2D_MS;
        return switch (dim) {
            case 0 -> arrayed ? NativeTokens.TEX_2D_ARRAY : NativeTokens.TEX_2D;
            case 2 -> NativeTokens.TEX_3D;
            case 3 -> arrayed ? NativeTokens.TEX_CUBE_ARRAY : NativeTokens.TEX_CUBE;
            default -> arrayed ? NativeTokens.TEX_2D_ARRAY : NativeTokens.TEX_2D;
        };
    }

    public static int targetSlot(int dim, boolean arrayed) {
        return switch (dim) {
            case 0 -> arrayed ? GlState.T_1D_ARRAY : GlState.T_1D;
            case 2 -> GlState.T_3D;
            case 3 -> arrayed ? GlState.T_CUBE_ARRAY : GlState.T_CUBE;
            case 4 -> GlState.T_RECT;
            case StageReflection.DIM_BUFFER_TEXTURE -> GlState.T_BUFFER;
            case StageReflection.DIM_MULTISAMPLE -> arrayed ? GlState.T_2D_MS_ARRAY : GlState.T_2D_MS;
            default -> arrayed ? GlState.T_2D_ARRAY : GlState.T_2D;
        };
    }

    public static boolean compatibleView(TexBase tex, int viewType) {
        final int own = textureTypeFor(tex);
        if (own == viewType) return true;
        return switch (viewType) {
            case NativeTokens.TEX_2D_ARRAY -> own == NativeTokens.TEX_2D || own == NativeTokens.TEX_CUBE || own == NativeTokens.TEX_CUBE_ARRAY;
            case NativeTokens.TEX_2D -> own == NativeTokens.TEX_2D_ARRAY && tex.layers == 1;
            default -> false;
        };
    }

    public static DepthStencilKey depthStencilKey(GlState st, TargetBase<?> t) {
        final boolean hasDepth = t.depth != null && t.hasDepth;
        final boolean depthTest = st.depthTest && hasDepth;
        final boolean stencil = st.stencilTest && t.depth != null && t.hasStencil;
        int front = 0, back = 0, masks = 0;
        if (stencil) {
            front = stencilOp(st.stencilFail[0]) | stencilOp(st.stencilPass[0]) << 4 | stencilOp(st.stencilDepthFail[0]) << 8 | compareOp(st.stencilFunc[0]) << 12;
            back = stencilOp(st.stencilFail[1]) | stencilOp(st.stencilPass[1]) << 4 | stencilOp(st.stencilDepthFail[1]) << 8 | compareOp(st.stencilFunc[1]) << 12;
            masks = (st.stencilValueMask[0] & 0xFF) | (st.stencilWriteMask[0] & 0xFF) << 8 | (st.stencilValueMask[1] & 0xFF) << 16 | (st.stencilWriteMask[1] & 0xFF) << 24;
        }
        return new DepthStencilKey(depthTest ? compareOp(st.depthFunc) : NativeTokens.COMPARE_ALWAYS, depthTest && st.depthMask, stencil, front, back, masks);
    }

    public static int depthBits(int fmt) {
        return switch (fmt) {
            case NativeTokens.PF_DEPTH16_UNORM -> 16;
            case NativeTokens.PF_DEPTH24_UNORM_STENCIL8 -> 24;
            case NativeTokens.PF_DEPTH32_FLOAT, NativeTokens.PF_DEPTH32_FLOAT_STENCIL8 -> 32;
            default -> 0;
        };
    }

    /** Applies a swizzle to a depth or stencil texture, whose texel reads as (value, 0, 0, 1). */
    public static int[] depthViewSwizzle(int[] swizzle) {
        final int[] view = new int[4];
        for (int i = 0; i < 4; i++) {
            view[i] = switch (swizzle[i]) {
                case GL11.GL_RED -> NativeTokens.SWIZZLE_RED;
                case GL11.GL_ALPHA, GL11.GL_ONE -> NativeTokens.SWIZZLE_ONE;
                default -> NativeTokens.SWIZZLE_ZERO;
            };
        }
        return view;
    }

    public static int[] viewSwizzle(Formats.Format f, int[] swizzle) {
        final int[] base = f.swizzle();
        final int[] view = {swizzle(swizzle[0], base, 0), swizzle(swizzle[1], base, 1), swizzle(swizzle[2], base, 2), swizzle(swizzle[3], base, 3)};
        if ((f.baseFormat() == GL11.GL_RGB || f.baseFormat() == GL30.GL_RGB_INTEGER) && !f.isCompressed() && base == null) {
            for (int i = 0; i < 4; i++) {
                if (swizzle[i] == GL11.GL_ALPHA) view[i] = NativeTokens.SWIZZLE_ONE;
            }
        }
        return view;
    }

    public static int sliceCount(TexBase t) {
        return switch (textureTypeFor(t)) {
            case NativeTokens.TEX_CUBE -> 6;
            case NativeTokens.TEX_2D_ARRAY, NativeTokens.TEX_2D_MS_ARRAY, NativeTokens.TEX_CUBE_ARRAY -> Math.max(1, t.layers);
            default -> 1;
        };
    }

    public static int kindOf(int format, boolean depth) {
        if (depth) return KIND_DEPTH;
        return switch (format) {
            case NativeTokens.PF_R8_UINT, NativeTokens.PF_R16_UINT, NativeTokens.PF_RG8_UINT, NativeTokens.PF_R32_UINT, NativeTokens.PF_RG16_UINT, NativeTokens.PF_RGBA8_UINT, NativeTokens.PF_RGB10A2_UINT,
                 NativeTokens.PF_RG32_UINT, NativeTokens.PF_RGBA16_UINT, NativeTokens.PF_RGBA32_UINT -> KIND_UINT;
            case NativeTokens.PF_R8_SINT, NativeTokens.PF_R16_SINT, NativeTokens.PF_RG8_SINT, NativeTokens.PF_R32_SINT, NativeTokens.PF_RG16_SINT, NativeTokens.PF_RGBA8_SINT, NativeTokens.PF_RG32_SINT,
                 NativeTokens.PF_RGBA16_SINT, NativeTokens.PF_RGBA32_SINT -> KIND_SINT;
            default -> KIND_FLOAT;
        };
    }
}
