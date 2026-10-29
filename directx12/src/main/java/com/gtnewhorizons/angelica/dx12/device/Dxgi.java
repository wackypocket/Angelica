package com.gtnewhorizons.angelica.dx12.device;

public final class Dxgi {
    private Dxgi() {}

    public static final int UNKNOWN = 0;
    public static final int R32G32B32A32_TYPELESS = 1, R32G32B32A32_FLOAT = 2, R32G32B32A32_UINT = 3, R32G32B32A32_SINT = 4;
    public static final int R16G16B16A16_FLOAT = 10, R16G16B16A16_UNORM = 11, R16G16B16A16_UINT = 12, R16G16B16A16_SNORM = 13, R16G16B16A16_SINT = 14;
    public static final int R32G32_FLOAT = 16, R32G32_UINT = 17, R32G32_SINT = 18, R32G8X24_TYPELESS = 19, D32_FLOAT_S8X24_UINT = 20;
    public static final int R32_FLOAT_X8X24_TYPELESS = 21, X32_TYPELESS_G8X24_UINT = 22;
    public static final int R10G10B10A2_UNORM = 24, R10G10B10A2_UINT = 25, R11G11B10_FLOAT = 26;
    public static final int R8G8B8A8_TYPELESS = 27, R8G8B8A8_UNORM = 28, R8G8B8A8_UNORM_SRGB = 29, R8G8B8A8_UINT = 30, R8G8B8A8_SNORM = 31, R8G8B8A8_SINT = 32;
    public static final int R16G16_FLOAT = 34, R16G16_UNORM = 35, R16G16_UINT = 36, R16G16_SNORM = 37, R16G16_SINT = 38;
    public static final int R32_TYPELESS = 39, D32_FLOAT = 40, R32_FLOAT = 41, R32_UINT = 42, R32_SINT = 43;
    public static final int R24G8_TYPELESS = 44, D24_UNORM_S8_UINT = 45, R24_UNORM_X8_TYPELESS = 46, X24_TYPELESS_G8_UINT = 47;
    public static final int R8G8_UNORM = 49, R8G8_UINT = 50, R8G8_SNORM = 51, R8G8_SINT = 52;
    public static final int R16_TYPELESS = 53, R16_FLOAT = 54, D16_UNORM = 55, R16_UNORM = 56, R16_UINT = 57, R16_SNORM = 58, R16_SINT = 59;
    public static final int R8_UNORM = 61, R8_UINT = 62, R8_SNORM = 63, R8_SINT = 64, A8_UNORM = 65, R9G9B9E5_SHAREDEXP = 67;
    public static final int BC1_UNORM = 71, BC1_UNORM_SRGB = 72, BC2_UNORM = 74, BC2_UNORM_SRGB = 75, BC3_UNORM = 77, BC3_UNORM_SRGB = 78;
    public static final int BC4_UNORM = 80, BC4_SNORM = 81, BC5_UNORM = 83, BC5_SNORM = 84, B8G8R8A8_UNORM = 87, B8G8R8A8_UNORM_SRGB = 91;
    public static final int BC7_UNORM = 98, BC7_UNORM_SRGB = 99;

    public static int view(int pf) {
        return switch (pf) {
            case Gfx.PF_A8_UNORM -> A8_UNORM;
            case Gfx.PF_R8_UNORM -> R8_UNORM;
            case Gfx.PF_R8_SNORM -> R8_SNORM;
            case Gfx.PF_R8_UINT -> R8_UINT;
            case Gfx.PF_R8_SINT -> R8_SINT;
            case Gfx.PF_R16_UNORM -> R16_UNORM;
            case Gfx.PF_R16_SNORM -> R16_SNORM;
            case Gfx.PF_R16_UINT -> R16_UINT;
            case Gfx.PF_R16_SINT -> R16_SINT;
            case Gfx.PF_R16_FLOAT -> R16_FLOAT;
            case Gfx.PF_RG8_UNORM -> R8G8_UNORM;
            case Gfx.PF_RG8_SNORM -> R8G8_SNORM;
            case Gfx.PF_RG8_UINT -> R8G8_UINT;
            case Gfx.PF_RG8_SINT -> R8G8_SINT;
            case Gfx.PF_R32_UINT -> R32_UINT;
            case Gfx.PF_R32_SINT -> R32_SINT;
            case Gfx.PF_R32_FLOAT -> R32_FLOAT;
            case Gfx.PF_RG16_UNORM -> R16G16_UNORM;
            case Gfx.PF_RG16_SNORM -> R16G16_SNORM;
            case Gfx.PF_RG16_UINT -> R16G16_UINT;
            case Gfx.PF_RG16_SINT -> R16G16_SINT;
            case Gfx.PF_RG16_FLOAT -> R16G16_FLOAT;
            case Gfx.PF_RGBA8_UNORM -> R8G8B8A8_UNORM;
            case Gfx.PF_RGBA8_SRGB -> R8G8B8A8_UNORM_SRGB;
            case Gfx.PF_RGBA8_SNORM -> R8G8B8A8_SNORM;
            case Gfx.PF_RGBA8_UINT -> R8G8B8A8_UINT;
            case Gfx.PF_RGBA8_SINT -> R8G8B8A8_SINT;
            case Gfx.PF_BGRA8_UNORM -> B8G8R8A8_UNORM;
            case Gfx.PF_BGRA8_SRGB -> B8G8R8A8_UNORM_SRGB;
            case Gfx.PF_RGB10A2_UNORM -> R10G10B10A2_UNORM;
            case Gfx.PF_RGB10A2_UINT -> R10G10B10A2_UINT;
            case Gfx.PF_RG11B10_FLOAT -> R11G11B10_FLOAT;
            case Gfx.PF_RGB9E5_FLOAT -> R9G9B9E5_SHAREDEXP;
            case Gfx.PF_RG32_UINT -> R32G32_UINT;
            case Gfx.PF_RG32_SINT -> R32G32_SINT;
            case Gfx.PF_RG32_FLOAT -> R32G32_FLOAT;
            case Gfx.PF_RGBA16_UNORM -> R16G16B16A16_UNORM;
            case Gfx.PF_RGBA16_SNORM -> R16G16B16A16_SNORM;
            case Gfx.PF_RGBA16_UINT -> R16G16B16A16_UINT;
            case Gfx.PF_RGBA16_SINT -> R16G16B16A16_SINT;
            case Gfx.PF_RGBA16_FLOAT -> R16G16B16A16_FLOAT;
            case Gfx.PF_RGBA32_UINT -> R32G32B32A32_UINT;
            case Gfx.PF_RGBA32_SINT -> R32G32B32A32_SINT;
            case Gfx.PF_RGBA32_FLOAT -> R32G32B32A32_FLOAT;
            case Gfx.PF_BC1_RGBA -> BC1_UNORM;
            case Gfx.PF_BC1_RGBA_SRGB -> BC1_UNORM_SRGB;
            case Gfx.PF_BC2_RGBA -> BC2_UNORM;
            case Gfx.PF_BC2_RGBA_SRGB -> BC2_UNORM_SRGB;
            case Gfx.PF_BC3_RGBA -> BC3_UNORM;
            case Gfx.PF_BC3_RGBA_SRGB -> BC3_UNORM_SRGB;
            case Gfx.PF_BC4_R_UNORM -> BC4_UNORM;
            case Gfx.PF_BC4_R_SNORM -> BC4_SNORM;
            case Gfx.PF_BC5_RG_UNORM -> BC5_UNORM;
            case Gfx.PF_BC5_RG_SNORM -> BC5_SNORM;
            case Gfx.PF_BC7_RGBA_UNORM -> BC7_UNORM;
            case Gfx.PF_BC7_RGBA_SRGB -> BC7_UNORM_SRGB;
            case Gfx.PF_DEPTH16_UNORM -> R16_UNORM;
            case Gfx.PF_DEPTH32_FLOAT -> R32_FLOAT;
            default -> UNKNOWN;
        };
    }

    public static boolean isDepth(int pf) {
        return pf == Gfx.PF_DEPTH16_UNORM || pf == Gfx.PF_DEPTH32_FLOAT || pf == Gfx.PF_DEPTH24_UNORM_STENCIL8 || pf == Gfx.PF_DEPTH32_FLOAT_STENCIL8
            || pf == Gfx.PF_STENCIL8 || pf == Gfx.PF_X32_STENCIL8 || pf == Gfx.PF_X24_STENCIL8;
    }

    public static int resource(int pf) {
        return switch (pf) {
            case Gfx.PF_DEPTH16_UNORM -> R16_TYPELESS;
            case Gfx.PF_DEPTH32_FLOAT -> R32_TYPELESS;
            case Gfx.PF_DEPTH24_UNORM_STENCIL8 -> R24G8_TYPELESS;
            case Gfx.PF_DEPTH32_FLOAT_STENCIL8 -> R32G8X24_TYPELESS;
            default -> view(pf);
        };
    }

    public static int dsv(int pf) {
        return switch (pf) {
            case Gfx.PF_DEPTH16_UNORM -> D16_UNORM;
            case Gfx.PF_DEPTH32_FLOAT -> D32_FLOAT;
            case Gfx.PF_DEPTH24_UNORM_STENCIL8 -> D24_UNORM_S8_UINT;
            case Gfx.PF_DEPTH32_FLOAT_STENCIL8 -> D32_FLOAT_S8X24_UINT;
            default -> UNKNOWN;
        };
    }

    public static boolean hasStencil(int pf) {
        return pf == Gfx.PF_DEPTH24_UNORM_STENCIL8 || pf == Gfx.PF_DEPTH32_FLOAT_STENCIL8;
    }

    public static int srv(int pf, boolean stencil) {
        return switch (pf) {
            case Gfx.PF_DEPTH16_UNORM -> R16_UNORM;
            case Gfx.PF_DEPTH32_FLOAT -> R32_FLOAT;
            case Gfx.PF_DEPTH24_UNORM_STENCIL8 -> stencil ? X24_TYPELESS_G8_UINT : R24_UNORM_X8_TYPELESS;
            case Gfx.PF_DEPTH32_FLOAT_STENCIL8 -> stencil ? X32_TYPELESS_G8X24_UINT : R32_FLOAT_X8X24_TYPELESS;
            default -> view(pf);
        };
    }

    public static boolean isCompressed(int dxgi) {
        return dxgi >= BC1_UNORM && dxgi <= BC7_UNORM_SRGB && dxgi != B8G8R8A8_UNORM && dxgi != B8G8R8A8_UNORM_SRGB;
    }

    public static int vertex(int vf) {
        return switch (vf) {
            case Gfx.VF_UCHAR2 -> R8G8_UINT;
            case Gfx.VF_UCHAR4, Gfx.VF_UCHAR3 -> R8G8B8A8_UINT;
            case Gfx.VF_CHAR2 -> R8G8_SINT;
            case Gfx.VF_CHAR4, Gfx.VF_CHAR3 -> R8G8B8A8_SINT;
            case Gfx.VF_UCHAR2_NORM -> R8G8_UNORM;
            case Gfx.VF_UCHAR4_NORM, Gfx.VF_UCHAR3_NORM -> R8G8B8A8_UNORM;
            case Gfx.VF_CHAR2_NORM -> R8G8_SNORM;
            case Gfx.VF_CHAR4_NORM, Gfx.VF_CHAR3_NORM -> R8G8B8A8_SNORM;
            case Gfx.VF_USHORT2 -> R16G16_UINT;
            case Gfx.VF_USHORT4, Gfx.VF_USHORT3 -> R16G16B16A16_UINT;
            case Gfx.VF_SHORT2 -> R16G16_SINT;
            case Gfx.VF_SHORT4, Gfx.VF_SHORT3 -> R16G16B16A16_SINT;
            case Gfx.VF_USHORT2_NORM -> R16G16_UNORM;
            case Gfx.VF_USHORT4_NORM, Gfx.VF_USHORT3_NORM -> R16G16B16A16_UNORM;
            case Gfx.VF_SHORT2_NORM -> R16G16_SNORM;
            case Gfx.VF_SHORT4_NORM, Gfx.VF_SHORT3_NORM -> R16G16B16A16_SNORM;
            case Gfx.VF_HALF2 -> R16G16_FLOAT;
            case Gfx.VF_HALF4, Gfx.VF_HALF3 -> R16G16B16A16_FLOAT;
            case Gfx.VF_FLOAT -> R32_FLOAT;
            case Gfx.VF_FLOAT2 -> R32G32_FLOAT;
            case Gfx.VF_FLOAT3 -> 6;
            case Gfx.VF_FLOAT4 -> R32G32B32A32_FLOAT;
            case Gfx.VF_INT -> R32_SINT;
            case Gfx.VF_INT2 -> R32G32_SINT;
            case Gfx.VF_INT3 -> 8;
            case Gfx.VF_INT4 -> R32G32B32A32_SINT;
            case Gfx.VF_UINT -> R32_UINT;
            case Gfx.VF_UINT2 -> R32G32_UINT;
            case Gfx.VF_UINT3 -> 7;
            case Gfx.VF_UINT4 -> R32G32B32A32_UINT;
            case Gfx.VF_UINT1010102_NORM -> R10G10B10A2_UNORM;
            case Gfx.VF_INT1010102_NORM -> R10G10B10A2_UNORM;
            case Gfx.VF_UCHAR4_NORM_BGRA -> B8G8R8A8_UNORM;
            case Gfx.VF_UCHAR -> R8_UINT;
            case Gfx.VF_CHAR -> R8_SINT;
            case Gfx.VF_UCHAR_NORM -> R8_UNORM;
            case Gfx.VF_CHAR_NORM -> R8_SNORM;
            case Gfx.VF_USHORT -> R16_UINT;
            case Gfx.VF_SHORT -> R16_SINT;
            case Gfx.VF_USHORT_NORM -> R16_UNORM;
            case Gfx.VF_SHORT_NORM -> R16_SNORM;
            case Gfx.VF_HALF -> R16_FLOAT;
            case Gfx.VF_FLOAT_RG11B10 -> R11G11B10_FLOAT;
            default -> R32G32B32A32_FLOAT;
        };
    }
}
