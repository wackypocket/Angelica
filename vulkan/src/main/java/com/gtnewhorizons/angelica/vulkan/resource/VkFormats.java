package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.glemu.resource.PixelFormats;

import static org.lwjgl.vulkan.VK10.*;

public final class VkFormats {
    private VkFormats() {}

    private static final int[] TO_VK = new int[272];

    private static void map(int pf, int vk) {
        TO_VK[pf] = vk;
    }

    static {
        map(PixelFormats.PF_A8_UNORM, VK_FORMAT_R8_UNORM);
        map(PixelFormats.PF_R8_UNORM, VK_FORMAT_R8_UNORM);
        map(PixelFormats.PF_R8_SNORM, VK_FORMAT_R8_SNORM);
        map(PixelFormats.PF_R8_UINT, VK_FORMAT_R8_UINT);
        map(PixelFormats.PF_R8_SINT, VK_FORMAT_R8_SINT);
        map(PixelFormats.PF_R16_UNORM, VK_FORMAT_R16_UNORM);
        map(PixelFormats.PF_R16_SNORM, VK_FORMAT_R16_SNORM);
        map(PixelFormats.PF_R16_UINT, VK_FORMAT_R16_UINT);
        map(PixelFormats.PF_R16_SINT, VK_FORMAT_R16_SINT);
        map(PixelFormats.PF_R16_FLOAT, VK_FORMAT_R16_SFLOAT);
        map(PixelFormats.PF_RG8_UNORM, VK_FORMAT_R8G8_UNORM);
        map(PixelFormats.PF_RG8_SNORM, VK_FORMAT_R8G8_SNORM);
        map(PixelFormats.PF_RG8_UINT, VK_FORMAT_R8G8_UINT);
        map(PixelFormats.PF_RG8_SINT, VK_FORMAT_R8G8_SINT);
        map(PixelFormats.PF_R32_UINT, VK_FORMAT_R32_UINT);
        map(PixelFormats.PF_R32_SINT, VK_FORMAT_R32_SINT);
        map(PixelFormats.PF_R32_FLOAT, VK_FORMAT_R32_SFLOAT);
        map(PixelFormats.PF_RG16_UNORM, VK_FORMAT_R16G16_UNORM);
        map(PixelFormats.PF_RG16_SNORM, VK_FORMAT_R16G16_SNORM);
        map(PixelFormats.PF_RG16_UINT, VK_FORMAT_R16G16_UINT);
        map(PixelFormats.PF_RG16_SINT, VK_FORMAT_R16G16_SINT);
        map(PixelFormats.PF_RG16_FLOAT, VK_FORMAT_R16G16_SFLOAT);
        map(PixelFormats.PF_RGBA8_UNORM, VK_FORMAT_R8G8B8A8_UNORM);
        map(PixelFormats.PF_RGBA8_SRGB, VK_FORMAT_R8G8B8A8_SRGB);
        map(PixelFormats.PF_RGBA8_SNORM, VK_FORMAT_R8G8B8A8_SNORM);
        map(PixelFormats.PF_RGBA8_UINT, VK_FORMAT_R8G8B8A8_UINT);
        map(PixelFormats.PF_RGBA8_SINT, VK_FORMAT_R8G8B8A8_SINT);
        map(PixelFormats.PF_BGRA8_UNORM, VK_FORMAT_B8G8R8A8_UNORM);
        map(PixelFormats.PF_BGRA8_SRGB, VK_FORMAT_B8G8R8A8_SRGB);
        map(PixelFormats.PF_RGB10A2_UNORM, VK_FORMAT_A2B10G10R10_UNORM_PACK32);
        map(PixelFormats.PF_RGB10A2_UINT, VK_FORMAT_A2B10G10R10_UINT_PACK32);
        map(PixelFormats.PF_RG11B10_FLOAT, VK_FORMAT_B10G11R11_UFLOAT_PACK32);
        map(PixelFormats.PF_RGB9E5_FLOAT, VK_FORMAT_E5B9G9R9_UFLOAT_PACK32);
        map(PixelFormats.PF_RG32_UINT, VK_FORMAT_R32G32_UINT);
        map(PixelFormats.PF_RG32_SINT, VK_FORMAT_R32G32_SINT);
        map(PixelFormats.PF_RG32_FLOAT, VK_FORMAT_R32G32_SFLOAT);
        map(PixelFormats.PF_RGBA16_UNORM, VK_FORMAT_R16G16B16A16_UNORM);
        map(PixelFormats.PF_RGBA16_SNORM, VK_FORMAT_R16G16B16A16_SNORM);
        map(PixelFormats.PF_RGBA16_UINT, VK_FORMAT_R16G16B16A16_UINT);
        map(PixelFormats.PF_RGBA16_SINT, VK_FORMAT_R16G16B16A16_SINT);
        map(PixelFormats.PF_RGBA16_FLOAT, VK_FORMAT_R16G16B16A16_SFLOAT);
        map(PixelFormats.PF_RGBA32_UINT, VK_FORMAT_R32G32B32A32_UINT);
        map(PixelFormats.PF_RGBA32_SINT, VK_FORMAT_R32G32B32A32_SINT);
        map(PixelFormats.PF_RGBA32_FLOAT, VK_FORMAT_R32G32B32A32_SFLOAT);
        map(PixelFormats.PF_BC1_RGBA, VK_FORMAT_BC1_RGBA_UNORM_BLOCK);
        map(PixelFormats.PF_BC1_RGBA_SRGB, VK_FORMAT_BC1_RGBA_SRGB_BLOCK);
        map(PixelFormats.PF_BC2_RGBA, VK_FORMAT_BC2_UNORM_BLOCK);
        map(PixelFormats.PF_BC2_RGBA_SRGB, VK_FORMAT_BC2_SRGB_BLOCK);
        map(PixelFormats.PF_BC3_RGBA, VK_FORMAT_BC3_UNORM_BLOCK);
        map(PixelFormats.PF_BC3_RGBA_SRGB, VK_FORMAT_BC3_SRGB_BLOCK);
        map(PixelFormats.PF_BC4_R_UNORM, VK_FORMAT_BC4_UNORM_BLOCK);
        map(PixelFormats.PF_BC4_R_SNORM, VK_FORMAT_BC4_SNORM_BLOCK);
        map(PixelFormats.PF_BC5_RG_UNORM, VK_FORMAT_BC5_UNORM_BLOCK);
        map(PixelFormats.PF_BC5_RG_SNORM, VK_FORMAT_BC5_SNORM_BLOCK);
        map(PixelFormats.PF_BC7_RGBA_UNORM, VK_FORMAT_BC7_UNORM_BLOCK);
        map(PixelFormats.PF_BC7_RGBA_SRGB, VK_FORMAT_BC7_SRGB_BLOCK);
        map(PixelFormats.PF_DEPTH16_UNORM, VK_FORMAT_D16_UNORM);
        map(PixelFormats.PF_DEPTH32_FLOAT, VK_FORMAT_D32_SFLOAT);
        map(PixelFormats.PF_STENCIL8, VK_FORMAT_S8_UINT);
        map(PixelFormats.PF_DEPTH24_UNORM_STENCIL8, VK_FORMAT_D24_UNORM_S8_UINT);
        map(PixelFormats.PF_DEPTH32_FLOAT_STENCIL8, VK_FORMAT_D32_SFLOAT_S8_UINT);
        map(PixelFormats.PF_X32_STENCIL8, VK_FORMAT_D32_SFLOAT_S8_UINT);
        map(PixelFormats.PF_X24_STENCIL8, VK_FORMAT_D24_UNORM_S8_UINT);
        map(PixelFormats.PF_X8_DEPTH24_UNORM, VK_FORMAT_X8_D24_UNORM_PACK32);
    }

    public static int of(int pf) {
        return pf >= 0 && pf < TO_VK.length ? TO_VK[pf] : VK_FORMAT_UNDEFINED;
    }

    public static int swizzle(int neutral) {
        return neutral + 1;
    }

    public static int texelBytes(int vkFormat) {
        return switch (vkFormat) {
            case VK_FORMAT_R8_UNORM, VK_FORMAT_R8_SNORM, VK_FORMAT_R8_UINT, VK_FORMAT_R8_SINT, VK_FORMAT_S8_UINT -> 1;
            case VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8_UINT, VK_FORMAT_R16_UNORM, VK_FORMAT_R16_SNORM, VK_FORMAT_R16_SFLOAT, VK_FORMAT_R16_UINT, VK_FORMAT_R16_SINT, VK_FORMAT_D16_UNORM -> 2;
            case VK_FORMAT_R16G16B16A16_UNORM, VK_FORMAT_R16G16B16A16_SNORM, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_UINT, VK_FORMAT_R16G16B16A16_SINT,
                 VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_D32_SFLOAT_S8_UINT -> 8;
            case VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_R32G32B32A32_SINT -> 16;
            default -> 4;
        };
    }
}
