package com.gtnewhorizons.angelica.glemu.resource;

import org.lwjgl.opengl.ARBTextureCompressionBPTC;
import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.EXTTextureSRGB;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;

import static com.gtnewhorizons.angelica.glemu.resource.PixelFormats.*;

public final class Formats {
    private Formats() {}

    public static final int ASPECT_COLOR = 1;
    public static final int ASPECT_DEPTH = 2;
    public static final int ASPECT_STENCIL = 4;
    public static final int ASPECT_DS = ASPECT_DEPTH | ASPECT_STENCIL;

    public static final int K_UNORM = 0, K_SNORM = 1, K_UINT = 2, K_SINT = 3, K_FLOAT = 4, K_HALF = 5, K_DEPTH = 6, K_PACKED = 7, K_COMPRESSED = 8;

    public record Format(int glInternal, int pf, int aspect, int texelBytes, int channels, int kind, int baseFormat,
                         int r, int g, int b, int a, int depthBits, int stencilBits, int blockBytes, boolean srgb,
                         int[] swizzle) {
        public boolean isDepth() { return (aspect & ASPECT_DEPTH) != 0; }
        public boolean isStencil() { return (aspect & ASPECT_STENCIL) != 0; }
        public boolean isDepthOrStencil() { return aspect != ASPECT_COLOR; }
        public boolean isCompressed() { return kind == K_COMPRESSED; }
        public boolean isInteger() { return kind == K_UINT || kind == K_SINT; }
    }

    private static final int[] IDENTITY = null;
    private static final int R = SWIZZLE_RED, G = SWIZZLE_GREEN, B = SWIZZLE_BLUE, A = SWIZZLE_ALPHA;
    private static final int ONE = SWIZZLE_ONE, ZERO = SWIZZLE_ZERO;
    private static final int[] RGB_ONE = {R, G, B, ONE};

    private static Format c(int gl, int pf, int bytes, int channels, int kind, int base, int r, int g, int b, int a) {
        return new Format(gl, pf, ASPECT_COLOR, bytes, channels, kind, base, r, g, b, a, 0, 0, 0, false, IDENTITY);
    }

    private static Format srgb(int gl, int pf, int bytes, int base) {
        return new Format(gl, pf, ASPECT_COLOR, bytes, 4, K_UNORM, base, 8, 8, 8, 8, 0, 0, 0, true, IDENTITY);
    }

    private static Format legacy(int gl, int pf, int bytes, int base, int[] swizzle, int r, int g, int b, int a) {
        return new Format(gl, pf, ASPECT_COLOR, bytes, swizzle == null ? 4 : 1, K_UNORM, base, r, g, b, a, 0, 0, 0, false, swizzle);
    }

    private static Format compressed(int gl, int pf, int blockBytes, int base, boolean srgb) {
        return new Format(gl, pf, ASPECT_COLOR, 0, 4, K_COMPRESSED, base, 8, 8, 8, 8, 0, 0, blockBytes, srgb, base == GL11.GL_RGB ? RGB_ONE : IDENTITY);
    }

    public static final Format RGBA8 = c(GL11.GL_RGBA8, PF_RGBA8_UNORM, 4, 4, K_UNORM, GL11.GL_RGBA, 8, 8, 8, 8);

    public static Format of(int glInternal, int depth24Stencil8Format, int depth24Format) {
        return switch (glInternal) {
            case GL11.GL_RGBA8 -> RGBA8;
            case GL11.GL_RGBA, 4, GL11.GL_RGBA4, GL11.GL_RGB5_A1, GL11.GL_RGBA2 -> withGl(RGBA8, glInternal, GL11.GL_RGBA);
            case GL11.GL_RGB, GL11.GL_RGB8, 3, GL11.GL_RGB4, GL11.GL_RGB5, GL11.GL_R3_G3_B2, GL41.GL_RGB565 ->
                c(glInternal, PF_RGBA8_UNORM, 4, 4, K_UNORM, GL11.GL_RGB, 8, 8, 8, 0);
            case GL11.GL_RGB10_A2 -> c(glInternal, PF_RGB10A2_UNORM, 4, 4, K_PACKED, GL11.GL_RGBA, 10, 10, 10, 2);
            case GL11.GL_RGB10, GL11.GL_RGB12, GL11.GL_RGB16 -> c(glInternal, PF_RGBA16_UNORM, 8, 4, K_UNORM, GL11.GL_RGB, 16, 16, 16, 0);
            case GL11.GL_RGBA12, GL11.GL_RGBA16 -> c(glInternal, PF_RGBA16_UNORM, 8, 4, K_UNORM, GL11.GL_RGBA, 16, 16, 16, 16);
            case GL31.GL_RGBA8_SNORM -> c(glInternal, PF_RGBA8_SNORM, 4, 4, K_SNORM, GL11.GL_RGBA, 8, 8, 8, 8);
            case GL31.GL_RGB8_SNORM -> c(glInternal, PF_RGBA8_SNORM, 4, 4, K_SNORM, GL11.GL_RGB, 8, 8, 8, 0);
            case GL31.GL_RGBA16_SNORM, GL31.GL_RGB16_SNORM -> c(glInternal, PF_RGBA16_SNORM, 8, 4, K_SNORM, glInternal == GL31.GL_RGBA16_SNORM ? GL11.GL_RGBA : GL11.GL_RGB, 16, 16, 16, 16);
            case GL31.GL_R8_SNORM -> c(glInternal, PF_R8_SNORM, 1, 1, K_SNORM, GL30.GL_RED, 8, 0, 0, 0);
            case GL31.GL_RG8_SNORM -> c(glInternal, PF_RG8_SNORM, 2, 2, K_SNORM, GL30.GL_RG, 8, 8, 0, 0);
            case GL31.GL_R16_SNORM -> c(glInternal, PF_R16_SNORM, 2, 1, K_SNORM, GL30.GL_RED, 16, 0, 0, 0);
            case GL31.GL_RG16_SNORM -> c(glInternal, PF_RG16_SNORM, 4, 2, K_SNORM, GL30.GL_RG, 16, 16, 0, 0);
            case GL30.GL_RGBA16F -> c(glInternal, PF_RGBA16_FLOAT, 8, 4, K_HALF, GL11.GL_RGBA, 16, 16, 16, 16);
            case GL30.GL_RGB16F -> c(glInternal, PF_RGBA16_FLOAT, 8, 4, K_HALF, GL11.GL_RGB, 16, 16, 16, 0);
            case GL30.GL_RGBA32F -> c(glInternal, PF_RGBA32_FLOAT, 16, 4, K_FLOAT, GL11.GL_RGBA, 32, 32, 32, 32);
            case GL30.GL_RGB32F -> c(glInternal, PF_RGBA32_FLOAT, 16, 4, K_FLOAT, GL11.GL_RGB, 32, 32, 32, 0);
            case GL30.GL_R16F -> c(glInternal, PF_R16_FLOAT, 2, 1, K_HALF, GL30.GL_RED, 16, 0, 0, 0);
            case GL30.GL_RG16F -> c(glInternal, PF_RG16_FLOAT, 4, 2, K_HALF, GL30.GL_RG, 16, 16, 0, 0);
            case GL30.GL_R32F -> c(glInternal, PF_R32_FLOAT, 4, 1, K_FLOAT, GL30.GL_RED, 32, 0, 0, 0);
            case GL30.GL_RG32F -> c(glInternal, PF_RG32_FLOAT, 8, 2, K_FLOAT, GL30.GL_RG, 32, 32, 0, 0);
            case GL30.GL_R11F_G11F_B10F -> c(glInternal, PF_RG11B10_FLOAT, 4, 3, K_PACKED, GL11.GL_RGB, 11, 11, 10, 0);
            case GL30.GL_RGB9_E5 -> c(glInternal, PF_RGB9E5_FLOAT, 4, 3, K_PACKED, GL11.GL_RGB, 9, 9, 9, 0);
            case GL30.GL_R8, GL30.GL_RED -> c(glInternal, PF_R8_UNORM, 1, 1, K_UNORM, GL30.GL_RED, 8, 0, 0, 0);
            case GL30.GL_RG8, GL30.GL_RG -> c(glInternal, PF_RG8_UNORM, 2, 2, K_UNORM, GL30.GL_RG, 8, 8, 0, 0);
            case GL30.GL_R16 -> c(glInternal, PF_R16_UNORM, 2, 1, K_UNORM, GL30.GL_RED, 16, 0, 0, 0);
            case GL30.GL_RG16 -> c(glInternal, PF_RG16_UNORM, 4, 2, K_UNORM, GL30.GL_RG, 16, 16, 0, 0);
            case GL30.GL_R8UI -> c(glInternal, PF_R8_UINT, 1, 1, K_UINT, GL30.GL_RED_INTEGER, 8, 0, 0, 0);
            case GL30.GL_R8I -> c(glInternal, PF_R8_SINT, 1, 1, K_SINT, GL30.GL_RED_INTEGER, 8, 0, 0, 0);
            case GL30.GL_R16UI -> c(glInternal, PF_R16_UINT, 2, 1, K_UINT, GL30.GL_RED_INTEGER, 16, 0, 0, 0);
            case GL30.GL_R16I -> c(glInternal, PF_R16_SINT, 2, 1, K_SINT, GL30.GL_RED_INTEGER, 16, 0, 0, 0);
            case GL30.GL_R32UI -> c(glInternal, PF_R32_UINT, 4, 1, K_UINT, GL30.GL_RED_INTEGER, 32, 0, 0, 0);
            case GL30.GL_R32I -> c(glInternal, PF_R32_SINT, 4, 1, K_SINT, GL30.GL_RED_INTEGER, 32, 0, 0, 0);
            case GL30.GL_RG8UI -> c(glInternal, PF_RG8_UINT, 2, 2, K_UINT, GL30.GL_RG_INTEGER, 8, 8, 0, 0);
            case GL30.GL_RG16UI -> c(glInternal, PF_RG16_UINT, 4, 2, K_UINT, GL30.GL_RG_INTEGER, 16, 16, 0, 0);
            case GL30.GL_RG32UI -> c(glInternal, PF_RG32_UINT, 8, 2, K_UINT, GL30.GL_RG_INTEGER, 32, 32, 0, 0);
            case GL30.GL_RG32I -> c(glInternal, PF_RG32_SINT, 8, 2, K_SINT, GL30.GL_RG_INTEGER, 32, 32, 0, 0);
            case GL30.GL_RGBA8UI -> c(glInternal, PF_RGBA8_UINT, 4, 4, K_UINT, GL30.GL_RGBA_INTEGER, 8, 8, 8, 8);
            case GL30.GL_RGBA8I -> c(glInternal, PF_RGBA8_SINT, 4, 4, K_SINT, GL30.GL_RGBA_INTEGER, 8, 8, 8, 8);
            case GL30.GL_RGBA16UI -> c(glInternal, PF_RGBA16_UINT, 8, 4, K_UINT, GL30.GL_RGBA_INTEGER, 16, 16, 16, 16);
            case GL30.GL_RGBA16I -> c(glInternal, PF_RGBA16_SINT, 8, 4, K_SINT, GL30.GL_RGBA_INTEGER, 16, 16, 16, 16);
            case GL30.GL_RGBA32UI -> c(glInternal, PF_RGBA32_UINT, 16, 4, K_UINT, GL30.GL_RGBA_INTEGER, 32, 32, 32, 32);
            case GL30.GL_RGBA32I -> c(glInternal, PF_RGBA32_SINT, 16, 4, K_SINT, GL30.GL_RGBA_INTEGER, 32, 32, 32, 32);
            case GL30.GL_RGB32UI -> c(glInternal, PF_RGBA32_UINT, 16, 4, K_UINT, GL30.GL_RGB_INTEGER, 32, 32, 32, 0);
            case GL33.GL_RGB10_A2UI -> c(glInternal, PF_RGB10A2_UINT, 4, 4, K_PACKED, GL30.GL_RGBA_INTEGER, 10, 10, 10, 2);
            case GL21.GL_SRGB8_ALPHA8, GL21.GL_SRGB_ALPHA -> srgb(glInternal, PF_RGBA8_SRGB, 4, GL11.GL_RGBA);
            case GL21.GL_SRGB8, GL21.GL_SRGB -> srgb(glInternal, PF_RGBA8_SRGB, 4, GL11.GL_RGB);
            case GL11.GL_ALPHA, GL11.GL_ALPHA8, GL11.GL_ALPHA4, GL11.GL_ALPHA12, GL11.GL_ALPHA16 ->
                legacy(glInternal, PF_R8_UNORM, 1, GL11.GL_ALPHA, new int[] {ZERO, ZERO, ZERO, R}, 0, 0, 0, 8);
            case GL11.GL_LUMINANCE, GL11.GL_LUMINANCE8, 1, GL11.GL_LUMINANCE4, GL11.GL_LUMINANCE12, GL11.GL_LUMINANCE16 ->
                legacy(glInternal, PF_R8_UNORM, 1, GL11.GL_LUMINANCE, new int[] {R, R, R, ONE}, 8, 8, 8, 0);
            case GL11.GL_INTENSITY, GL11.GL_INTENSITY8, GL11.GL_INTENSITY4, GL11.GL_INTENSITY12, GL11.GL_INTENSITY16 ->
                legacy(glInternal, PF_R8_UNORM, 1, GL11.GL_INTENSITY, new int[] {R, R, R, R}, 8, 8, 8, 8);
            case GL11.GL_LUMINANCE_ALPHA, GL11.GL_LUMINANCE8_ALPHA8, 2, GL11.GL_LUMINANCE4_ALPHA4, GL11.GL_LUMINANCE6_ALPHA2, GL11.GL_LUMINANCE12_ALPHA4, GL11.GL_LUMINANCE12_ALPHA12, GL11.GL_LUMINANCE16_ALPHA16 ->
                new Format(glInternal, PF_RG8_UNORM, ASPECT_COLOR, 2, 2, K_UNORM, GL11.GL_LUMINANCE_ALPHA, 8, 8, 8, 8, 0, 0, 0, false, new int[] {R, R, R, G});
            case GL11.GL_DEPTH_COMPONENT, GL14.GL_DEPTH_COMPONENT24 ->
                new Format(glInternal, depth24Format, ASPECT_DEPTH, 4, 1, K_DEPTH, GL11.GL_DEPTH_COMPONENT, 0, 0, 0, 0, 24, 0, 0, false, IDENTITY);
            case GL14.GL_DEPTH_COMPONENT16 -> new Format(glInternal, PF_DEPTH16_UNORM, ASPECT_DEPTH, 2, 1, K_DEPTH, GL11.GL_DEPTH_COMPONENT, 0, 0, 0, 0, 16, 0, 0, false, IDENTITY);
            case GL14.GL_DEPTH_COMPONENT32, GL30.GL_DEPTH_COMPONENT32F -> new Format(glInternal, PF_DEPTH32_FLOAT, ASPECT_DEPTH, 4, 1, K_DEPTH, GL11.GL_DEPTH_COMPONENT, 0, 0, 0, 0, 32, 0, 0, false, IDENTITY);
            case GL30.GL_DEPTH24_STENCIL8, GL30.GL_DEPTH_STENCIL ->
                new Format(glInternal, depth24Stencil8Format, ASPECT_DS, 4, 2, K_DEPTH, GL30.GL_DEPTH_STENCIL, 0, 0, 0, 0, 24, 8, 0, false, IDENTITY);
            case GL30.GL_DEPTH32F_STENCIL8 -> new Format(glInternal, PF_DEPTH32_FLOAT_STENCIL8, ASPECT_DS, 8, 2, K_DEPTH, GL30.GL_DEPTH_STENCIL, 0, 0, 0, 0, 32, 8, 0, false, IDENTITY);
            case GL30.GL_STENCIL_INDEX8, GL11.GL_STENCIL_INDEX ->
                new Format(glInternal, depth24Stencil8Format, ASPECT_DS, 4, 2, K_DEPTH, GL30.GL_DEPTH_STENCIL, 0, 0, 0, 0, 24, 8, 0, false, IDENTITY);
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT -> compressed(glInternal, PF_BC1_RGBA, 8, GL11.GL_RGB, false);
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT1_EXT -> compressed(glInternal, PF_BC1_RGBA, 8, GL11.GL_RGBA, false);
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT3_EXT -> compressed(glInternal, PF_BC2_RGBA, 16, GL11.GL_RGBA, false);
            case EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT -> compressed(glInternal, PF_BC3_RGBA, 16, GL11.GL_RGBA, false);
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_S3TC_DXT1_EXT -> compressed(glInternal, PF_BC1_RGBA_SRGB, 8, GL11.GL_RGB, true);
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT -> compressed(glInternal, PF_BC1_RGBA_SRGB, 8, GL11.GL_RGBA, true);
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT3_EXT -> compressed(glInternal, PF_BC2_RGBA_SRGB, 16, GL11.GL_RGBA, true);
            case EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT -> compressed(glInternal, PF_BC3_RGBA_SRGB, 16, GL11.GL_RGBA, true);
            case ARBTextureCompressionBPTC.GL_COMPRESSED_RGBA_BPTC_UNORM_ARB -> compressed(glInternal, PF_BC7_RGBA_UNORM, 16, GL11.GL_RGBA, false);
            case ARBTextureCompressionBPTC.GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM_ARB -> compressed(glInternal, PF_BC7_RGBA_SRGB, 16, GL11.GL_RGBA, true);
            case GL30.GL_COMPRESSED_RED_RGTC1 -> compressed(glInternal, PF_BC4_R_UNORM, 8, GL30.GL_RED, false);
            case GL30.GL_COMPRESSED_RG_RGTC2 -> compressed(glInternal, PF_BC5_RG_UNORM, 16, GL30.GL_RG, false);
            case GL13.GL_COMPRESSED_RGBA, GL13.GL_COMPRESSED_RGB -> withGl(RGBA8, glInternal, glInternal == GL13.GL_COMPRESSED_RGB ? GL11.GL_RGB : GL11.GL_RGBA);
            case GL21.GL_COMPRESSED_SRGB_ALPHA, GL21.GL_COMPRESSED_SRGB -> srgb(glInternal, PF_RGBA8_SRGB, 4, GL11.GL_RGBA);
            default -> null;
        };
    }

    private static Format withGl(Format f, int gl, int base) {
        return new Format(gl, f.pf, f.aspect, f.texelBytes, f.channels, f.kind, base, f.r, f.g, f.b, f.a, f.depthBits, f.stencilBits, f.blockBytes, f.srgb, f.swizzle);
    }

    public static int texelBytesOf(int pixelFormat) {
        return switch (pixelFormat) {
            case PF_R8_UNORM, PF_R8_SNORM, PF_R8_UINT, PF_R8_SINT, PF_STENCIL8 -> 1;
            case PF_RG8_UNORM, PF_RG8_SNORM, PF_RG8_UINT, PF_R16_UNORM, PF_R16_SNORM, PF_R16_FLOAT, PF_R16_UINT, PF_R16_SINT, PF_DEPTH16_UNORM -> 2;
            case PF_RGBA16_UNORM, PF_RGBA16_SNORM, PF_RGBA16_FLOAT, PF_RGBA16_UINT, PF_RGBA16_SINT,
                 PF_RG32_FLOAT, PF_RG32_UINT, PF_RG32_SINT, PF_DEPTH32_FLOAT_STENCIL8 -> 8;
            case PF_RGBA32_FLOAT, PF_RGBA32_UINT, PF_RGBA32_SINT -> 16;
            default -> 4;
        };
    }
}
