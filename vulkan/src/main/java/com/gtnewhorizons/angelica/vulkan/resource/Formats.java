package com.gtnewhorizons.angelica.vulkan.resource;

import org.lwjgl.opengl.ARBTextureCompressionBPTC;
import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.EXTTextureSRGB;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;

import static org.lwjgl.vulkan.VK10.*;

public final class Formats {
    private Formats() {}

    public static final int ASPECT_COLOR = VK_IMAGE_ASPECT_COLOR_BIT;
    public static final int ASPECT_DEPTH = VK_IMAGE_ASPECT_DEPTH_BIT;
    public static final int ASPECT_DS = VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
    public static final int ASPECT_STENCIL = VK_IMAGE_ASPECT_STENCIL_BIT;

    public static final int K_UNORM = 0, K_SNORM = 1, K_UINT = 2, K_SINT = 3, K_FLOAT = 4, K_HALF = 5, K_DEPTH = 6, K_PACKED = 7, K_COMPRESSED = 8;

    public record Format(int glInternal, int vk, int aspect, int texelBytes, int channels, int kind, int baseFormat,
                         int r, int g, int b, int a, int depthBits, int stencilBits, int blockBytes, boolean srgb,
                         int[] swizzle) {
        public boolean isDepth() { return (aspect & ASPECT_DEPTH) != 0; }
        public boolean isStencil() { return (aspect & ASPECT_STENCIL) != 0; }
        public boolean isDepthOrStencil() { return aspect != ASPECT_COLOR; }
        public boolean isCompressed() { return kind == K_COMPRESSED; }
        public boolean isInteger() { return kind == K_UINT || kind == K_SINT; }
    }

    private static final int[] IDENTITY = null;
    private static final int R = VK_COMPONENT_SWIZZLE_R, G = VK_COMPONENT_SWIZZLE_G, B = VK_COMPONENT_SWIZZLE_B, A = VK_COMPONENT_SWIZZLE_A;
    private static final int ONE = VK_COMPONENT_SWIZZLE_ONE, ZERO = VK_COMPONENT_SWIZZLE_ZERO;

    private static Format c(int gl, int vk, int bytes, int channels, int kind, int base, int r, int g, int b, int a) {
        return new Format(gl, vk, ASPECT_COLOR, bytes, channels, kind, base, r, g, b, a, 0, 0, 0, false, IDENTITY);
    }

    private static Format srgb(int gl, int vk, int bytes, int base) {
        return new Format(gl, vk, ASPECT_COLOR, bytes, 4, K_UNORM, base, 8, 8, 8, 8, 0, 0, 0, true, IDENTITY);
    }

    private static Format legacy(int gl, int vk, int bytes, int base, int[] swizzle, int r, int g, int b, int a) {
        return new Format(gl, vk, ASPECT_COLOR, bytes, swizzle == null ? 4 : 1, K_UNORM, base, r, g, b, a, 0, 0, 0, false, swizzle);
    }

    private static Format compressed(int gl, int vk, int blockBytes, int base, boolean srgb) {
        return new Format(gl, vk, ASPECT_COLOR, 0, 4, K_COMPRESSED, base, 8, 8, 8, 8, 0, 0, blockBytes, srgb, IDENTITY);
    }

    public static final Format RGBA8 = c(GL11.GL_RGBA8, VK_FORMAT_R8G8B8A8_UNORM, 4, 4, K_UNORM, GL11.GL_RGBA, 8, 8, 8, 8);

    public static Format of(int glInternal, int depth24Stencil8Vk, int depth24Vk) {
        return switch (glInternal) {
            case GL11.GL_RGBA8 -> RGBA8;
            case GL11.GL_RGBA, 4, GL11.GL_RGBA4, GL11.GL_RGB5_A1, GL11.GL_RGBA2 -> withGl(RGBA8, glInternal, GL11.GL_RGBA);
            case GL11.GL_RGB, GL11.GL_RGB8, 3, GL11.GL_RGB4, GL11.GL_RGB5, GL11.GL_R3_G3_B2, GL41.GL_RGB565 ->
                c(glInternal, VK_FORMAT_R8G8B8A8_UNORM, 4, 4, K_UNORM, GL11.GL_RGB, 8, 8, 8, 0);
            case GL11.GL_RGB10_A2 -> c(glInternal, VK_FORMAT_A2B10G10R10_UNORM_PACK32, 4, 4, K_PACKED, GL11.GL_RGBA, 10, 10, 10, 2);
            case GL11.GL_RGB10, GL11.GL_RGB12, GL11.GL_RGB16 -> c(glInternal, VK_FORMAT_R16G16B16A16_UNORM, 8, 4, K_UNORM, GL11.GL_RGB, 16, 16, 16, 0);
            case GL11.GL_RGBA12, GL11.GL_RGBA16 -> c(glInternal, VK_FORMAT_R16G16B16A16_UNORM, 8, 4, K_UNORM, GL11.GL_RGBA, 16, 16, 16, 16);
            case GL31.GL_RGBA8_SNORM -> c(glInternal, VK_FORMAT_R8G8B8A8_SNORM, 4, 4, K_SNORM, GL11.GL_RGBA, 8, 8, 8, 8);
            case GL31.GL_RGB8_SNORM -> c(glInternal, VK_FORMAT_R8G8B8A8_SNORM, 4, 4, K_SNORM, GL11.GL_RGB, 8, 8, 8, 0);
            case GL31.GL_RGBA16_SNORM, GL31.GL_RGB16_SNORM -> c(glInternal, VK_FORMAT_R16G16B16A16_SNORM, 8, 4, K_SNORM, glInternal == GL31.GL_RGBA16_SNORM ? GL11.GL_RGBA : GL11.GL_RGB, 16, 16, 16, 16);
            case GL31.GL_R8_SNORM -> c(glInternal, VK_FORMAT_R8_SNORM, 1, 1, K_SNORM, GL30.GL_RED, 8, 0, 0, 0);
            case GL31.GL_RG8_SNORM -> c(glInternal, VK_FORMAT_R8G8_SNORM, 2, 2, K_SNORM, GL30.GL_RG, 8, 8, 0, 0);
            case GL31.GL_R16_SNORM -> c(glInternal, VK_FORMAT_R16_SNORM, 2, 1, K_SNORM, GL30.GL_RED, 16, 0, 0, 0);
            case GL31.GL_RG16_SNORM -> c(glInternal, VK_FORMAT_R16G16_SNORM, 4, 2, K_SNORM, GL30.GL_RG, 16, 16, 0, 0);
            case GL30.GL_RGBA16F -> c(glInternal, VK_FORMAT_R16G16B16A16_SFLOAT, 8, 4, K_HALF, GL11.GL_RGBA, 16, 16, 16, 16);
            case GL30.GL_RGB16F -> c(glInternal, VK_FORMAT_R16G16B16A16_SFLOAT, 8, 4, K_HALF, GL11.GL_RGB, 16, 16, 16, 0);
            case GL30.GL_RGBA32F -> c(glInternal, VK_FORMAT_R32G32B32A32_SFLOAT, 16, 4, K_FLOAT, GL11.GL_RGBA, 32, 32, 32, 32);
            case GL30.GL_RGB32F -> c(glInternal, VK_FORMAT_R32G32B32A32_SFLOAT, 16, 4, K_FLOAT, GL11.GL_RGB, 32, 32, 32, 0);
            case GL30.GL_R16F -> c(glInternal, VK_FORMAT_R16_SFLOAT, 2, 1, K_HALF, GL30.GL_RED, 16, 0, 0, 0);
            case GL30.GL_RG16F -> c(glInternal, VK_FORMAT_R16G16_SFLOAT, 4, 2, K_HALF, GL30.GL_RG, 16, 16, 0, 0);
            case GL30.GL_R32F -> c(glInternal, VK_FORMAT_R32_SFLOAT, 4, 1, K_FLOAT, GL30.GL_RED, 32, 0, 0, 0);
            case GL30.GL_RG32F -> c(glInternal, VK_FORMAT_R32G32_SFLOAT, 8, 2, K_FLOAT, GL30.GL_RG, 32, 32, 0, 0);
            case GL30.GL_R11F_G11F_B10F -> c(glInternal, VK_FORMAT_B10G11R11_UFLOAT_PACK32, 4, 3, K_PACKED, GL11.GL_RGB, 11, 11, 10, 0);
            case GL30.GL_RGB9_E5 -> c(glInternal, VK_FORMAT_E5B9G9R9_UFLOAT_PACK32, 4, 3, K_PACKED, GL11.GL_RGB, 9, 9, 9, 0);
            case GL30.GL_R8, GL30.GL_RED -> c(glInternal, VK_FORMAT_R8_UNORM, 1, 1, K_UNORM, GL30.GL_RED, 8, 0, 0, 0);
            case GL30.GL_RG8, GL30.GL_RG -> c(glInternal, VK_FORMAT_R8G8_UNORM, 2, 2, K_UNORM, GL30.GL_RG, 8, 8, 0, 0);
            case GL30.GL_R16 -> c(glInternal, VK_FORMAT_R16_UNORM, 2, 1, K_UNORM, GL30.GL_RED, 16, 0, 0, 0);
            case GL30.GL_RG16 -> c(glInternal, VK_FORMAT_R16G16_UNORM, 4, 2, K_UNORM, GL30.GL_RG, 16, 16, 0, 0);
            case GL30.GL_R8UI -> c(glInternal, VK_FORMAT_R8_UINT, 1, 1, K_UINT, GL30.GL_RED_INTEGER, 8, 0, 0, 0);
            case GL30.GL_R8I -> c(glInternal, VK_FORMAT_R8_SINT, 1, 1, K_SINT, GL30.GL_RED_INTEGER, 8, 0, 0, 0);
            case GL30.GL_R16UI -> c(glInternal, VK_FORMAT_R16_UINT, 2, 1, K_UINT, GL30.GL_RED_INTEGER, 16, 0, 0, 0);
            case GL30.GL_R16I -> c(glInternal, VK_FORMAT_R16_SINT, 2, 1, K_SINT, GL30.GL_RED_INTEGER, 16, 0, 0, 0);
            case GL30.GL_R32UI -> c(glInternal, VK_FORMAT_R32_UINT, 4, 1, K_UINT, GL30.GL_RED_INTEGER, 32, 0, 0, 0);
            case GL30.GL_R32I -> c(glInternal, VK_FORMAT_R32_SINT, 4, 1, K_SINT, GL30.GL_RED_INTEGER, 32, 0, 0, 0);
            case GL30.GL_RG8UI -> c(glInternal, VK_FORMAT_R8G8_UINT, 2, 2, K_UINT, GL30.GL_RG_INTEGER, 8, 8, 0, 0);
            case GL30.GL_RG16UI -> c(glInternal, VK_FORMAT_R16G16_UINT, 4, 2, K_UINT, GL30.GL_RG_INTEGER, 16, 16, 0, 0);
            case GL30.GL_RG32UI -> c(glInternal, VK_FORMAT_R32G32_UINT, 8, 2, K_UINT, GL30.GL_RG_INTEGER, 32, 32, 0, 0);
            case GL30.GL_RG32I -> c(glInternal, VK_FORMAT_R32G32_SINT, 8, 2, K_SINT, GL30.GL_RG_INTEGER, 32, 32, 0, 0);
            case GL30.GL_RGBA8UI -> c(glInternal, VK_FORMAT_R8G8B8A8_UINT, 4, 4, K_UINT, GL30.GL_RGBA_INTEGER, 8, 8, 8, 8);
            case GL30.GL_RGBA8I -> c(glInternal, VK_FORMAT_R8G8B8A8_SINT, 4, 4, K_SINT, GL30.GL_RGBA_INTEGER, 8, 8, 8, 8);
            case GL30.GL_RGBA16UI -> c(glInternal, VK_FORMAT_R16G16B16A16_UINT, 8, 4, K_UINT, GL30.GL_RGBA_INTEGER, 16, 16, 16, 16);
            case GL30.GL_RGBA16I -> c(glInternal, VK_FORMAT_R16G16B16A16_SINT, 8, 4, K_SINT, GL30.GL_RGBA_INTEGER, 16, 16, 16, 16);
            case GL30.GL_RGBA32UI -> c(glInternal, VK_FORMAT_R32G32B32A32_UINT, 16, 4, K_UINT, GL30.GL_RGBA_INTEGER, 32, 32, 32, 32);
            case GL30.GL_RGBA32I -> c(glInternal, VK_FORMAT_R32G32B32A32_SINT, 16, 4, K_SINT, GL30.GL_RGBA_INTEGER, 32, 32, 32, 32);
            case GL30.GL_RGB32UI -> c(glInternal, VK_FORMAT_R32G32B32A32_UINT, 16, 4, K_UINT, GL30.GL_RGB_INTEGER, 32, 32, 32, 0);
            case GL33.GL_RGB10_A2UI -> c(glInternal, VK_FORMAT_A2B10G10R10_UINT_PACK32, 4, 4, K_PACKED, GL30.GL_RGBA_INTEGER, 10, 10, 10, 2);
            case GL21.GL_SRGB8_ALPHA8, GL21.GL_SRGB_ALPHA -> srgb(glInternal, VK_FORMAT_R8G8B8A8_SRGB, 4, GL11.GL_RGBA);
            case GL21.GL_SRGB8, GL21.GL_SRGB -> srgb(glInternal, VK_FORMAT_R8G8B8A8_SRGB, 4, GL11.GL_RGB);
            case GL11.GL_ALPHA, GL11.GL_ALPHA8, GL11.GL_ALPHA4, GL11.GL_ALPHA12, GL11.GL_ALPHA16 ->
                legacy(glInternal, VK_FORMAT_R8_UNORM, 1, GL11.GL_ALPHA, new int[] {ZERO, ZERO, ZERO, R}, 0, 0, 0, 8);
            case GL11.GL_LUMINANCE, GL11.GL_LUMINANCE8, 1, GL11.GL_LUMINANCE4, GL11.GL_LUMINANCE12, GL11.GL_LUMINANCE16 ->
                legacy(glInternal, VK_FORMAT_R8_UNORM, 1, GL11.GL_LUMINANCE, new int[] {R, R, R, ONE}, 8, 8, 8, 0);
            case GL11.GL_INTENSITY, GL11.GL_INTENSITY8, GL11.GL_INTENSITY4, GL11.GL_INTENSITY12, GL11.GL_INTENSITY16 ->
                legacy(glInternal, VK_FORMAT_R8_UNORM, 1, GL11.GL_INTENSITY, new int[] {R, R, R, R}, 8, 8, 8, 8);
            case GL11.GL_LUMINANCE_ALPHA, GL11.GL_LUMINANCE8_ALPHA8, 2, GL11.GL_LUMINANCE4_ALPHA4, GL11.GL_LUMINANCE6_ALPHA2, GL11.GL_LUMINANCE12_ALPHA4, GL11.GL_LUMINANCE12_ALPHA12, GL11.GL_LUMINANCE16_ALPHA16 ->
                new Format(glInternal, VK_FORMAT_R8G8_UNORM, ASPECT_COLOR, 2, 2, K_UNORM, GL11.GL_LUMINANCE_ALPHA, 8, 8, 8, 8, 0, 0, 0, false, new int[] {R, R, R, G});
            case GL11.GL_DEPTH_COMPONENT, GL14.GL_DEPTH_COMPONENT24 ->
                new Format(glInternal, depth24Vk, ASPECT_DEPTH, depth24Vk == VK_FORMAT_X8_D24_UNORM_PACK32 ? 4 : 4, 1, K_DEPTH, GL11.GL_DEPTH_COMPONENT, 0, 0, 0, 0, 24, 0, 0, false, IDENTITY);
            case GL14.GL_DEPTH_COMPONENT16 -> new Format(glInternal, VK_FORMAT_D16_UNORM, ASPECT_DEPTH, 2, 1, K_DEPTH, GL11.GL_DEPTH_COMPONENT, 0, 0, 0, 0, 16, 0, 0, false, IDENTITY);
            case GL14.GL_DEPTH_COMPONENT32, GL30.GL_DEPTH_COMPONENT32F -> new Format(glInternal, VK_FORMAT_D32_SFLOAT, ASPECT_DEPTH, 4, 1, K_DEPTH, GL11.GL_DEPTH_COMPONENT, 0, 0, 0, 0, 32, 0, 0, false, IDENTITY);
            case GL30.GL_DEPTH24_STENCIL8, GL30.GL_DEPTH_STENCIL ->
                new Format(glInternal, depth24Stencil8Vk, ASPECT_DS, 4, 2, K_DEPTH, GL30.GL_DEPTH_STENCIL, 0, 0, 0, 0, 24, 8, 0, false, IDENTITY);
            case GL30.GL_DEPTH32F_STENCIL8 -> new Format(glInternal, VK_FORMAT_D32_SFLOAT_S8_UINT, ASPECT_DS, 8, 2, K_DEPTH, GL30.GL_DEPTH_STENCIL, 0, 0, 0, 0, 32, 8, 0, false, IDENTITY);
            case GL30.GL_STENCIL_INDEX8, GL11.GL_STENCIL_INDEX ->
                new Format(glInternal, depth24Stencil8Vk, ASPECT_DS, 4, 2, K_DEPTH, GL30.GL_DEPTH_STENCIL, 0, 0, 0, 0, 24, 8, 0, false, IDENTITY);
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT -> compressed(glInternal, VK_FORMAT_BC1_RGB_UNORM_BLOCK, 8, GL11.GL_RGB, false);
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT1_EXT -> compressed(glInternal, VK_FORMAT_BC1_RGBA_UNORM_BLOCK, 8, GL11.GL_RGBA, false);
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT3_EXT -> compressed(glInternal, VK_FORMAT_BC2_UNORM_BLOCK, 16, GL11.GL_RGBA, false);
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT -> compressed(glInternal, VK_FORMAT_BC3_UNORM_BLOCK, 16, GL11.GL_RGBA, false);
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_S3TC_DXT1_EXT -> compressed(glInternal, VK_FORMAT_BC1_RGB_SRGB_BLOCK, 8, GL11.GL_RGB, true);
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT -> compressed(glInternal, VK_FORMAT_BC1_RGBA_SRGB_BLOCK, 8, GL11.GL_RGBA, true);
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT3_EXT -> compressed(glInternal, VK_FORMAT_BC2_SRGB_BLOCK, 16, GL11.GL_RGBA, true);
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT -> compressed(glInternal, VK_FORMAT_BC3_SRGB_BLOCK, 16, GL11.GL_RGBA, true);
            case ARBTextureCompressionBPTC.GL_COMPRESSED_RGBA_BPTC_UNORM_ARB -> compressed(glInternal, VK_FORMAT_BC7_UNORM_BLOCK, 16, GL11.GL_RGBA, false);
            case ARBTextureCompressionBPTC.GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM_ARB -> compressed(glInternal, VK_FORMAT_BC7_SRGB_BLOCK, 16, GL11.GL_RGBA, true);
            case GL30.GL_COMPRESSED_RED_RGTC1 -> compressed(glInternal, VK_FORMAT_BC4_UNORM_BLOCK, 8, GL30.GL_RED, false);
            case GL30.GL_COMPRESSED_RG_RGTC2 -> compressed(glInternal, VK_FORMAT_BC5_UNORM_BLOCK, 16, GL30.GL_RG, false);
            case GL13.GL_COMPRESSED_RGBA, GL13.GL_COMPRESSED_RGB -> withGl(RGBA8, glInternal, glInternal == GL13.GL_COMPRESSED_RGB ? GL11.GL_RGB : GL11.GL_RGBA);
            case GL21.GL_COMPRESSED_SRGB_ALPHA, GL21.GL_COMPRESSED_SRGB -> srgb(glInternal, VK_FORMAT_R8G8B8A8_SRGB, 4, GL11.GL_RGBA);
            default -> null;
        };
    }

    private static Format withGl(Format f, int gl, int base) {
        return new Format(gl, f.vk, f.aspect, f.texelBytes, f.channels, f.kind, base, f.r, f.g, f.b, f.a, f.depthBits, f.stencilBits, f.blockBytes, f.srgb, f.swizzle);
    }

    public static int sizedFor(int glFormat, int glType) {
        return switch (glFormat) {
            case GL11.GL_RGBA, GL12.GL_BGRA -> glType == GL11.GL_FLOAT ? GL30.GL_RGBA32F : glType == GL30.GL_HALF_FLOAT ? GL30.GL_RGBA16F : GL11.GL_RGBA8;
            case GL11.GL_RGB, GL12.GL_BGR -> glType == GL11.GL_FLOAT ? GL30.GL_RGB32F : glType == GL30.GL_HALF_FLOAT ? GL30.GL_RGB16F : GL11.GL_RGB8;
            case GL11.GL_RED -> glType == GL11.GL_FLOAT ? GL30.GL_R32F : GL30.GL_R8;
            case GL30.GL_RG -> glType == GL11.GL_FLOAT ? GL30.GL_RG32F : GL30.GL_RG8;
            default -> glFormat;
        };
    }

    public static int blockDim(Format f) {
        return f.isCompressed() ? 4 : 1;
    }

    public static long imageBytes(Format f, int w, int h) {
        if (f.isCompressed()) return (long) ((w + 3) / 4) * ((h + 3) / 4) * f.blockBytes;
        return (long) w * h * f.texelBytes;
    }

    public static int vkTexelBytes(int vkFormat) {
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
