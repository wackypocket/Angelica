package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glemu.resource.Formats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/** What glGetInternalformativ answers. A query with no answer here reads as GL_NONE, which GL allows for what a format does not support. */
public final class InternalformatQuery {
    private InternalformatQuery() {}

    /** The answer's values, largest sample count first where there are several. */
    public static long[] of(GlBase<?, ?, ?> gl, int target, int internalFormat, int pname) {
        final Formats.Format f = gl.textures().knownInternalFormat(internalFormat) ? gl.textures().format(internalFormat) : null;
        final boolean multisample = target == GL30.GL_RENDERBUFFER || target == GL32.GL_TEXTURE_2D_MULTISAMPLE || target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
        return switch (pname) {
            case GL42.GL_NUM_SAMPLE_COUNTS -> new long[] {f != null && multisample && !f.isCompressed() ? sampleCounts(gl, f).length : 0};
            case GL13.GL_SAMPLES -> f != null && multisample && !f.isCompressed() ? sampleCounts(gl, f) : new long[0];
            case GL43.GL_INTERNALFORMAT_SUPPORTED -> new long[] {f != null ? 1 : 0};
            case GL43.GL_INTERNALFORMAT_PREFERRED -> new long[] {f != null ? internalFormat : 0};
            case GL43.GL_INTERNALFORMAT_RED_SIZE -> new long[] {f != null ? f.r() : 0};
            case GL43.GL_INTERNALFORMAT_GREEN_SIZE -> new long[] {f != null ? f.g() : 0};
            case GL43.GL_INTERNALFORMAT_BLUE_SIZE -> new long[] {f != null ? f.b() : 0};
            case GL43.GL_INTERNALFORMAT_ALPHA_SIZE -> new long[] {f != null ? f.a() : 0};
            case GL43.GL_MAX_WIDTH, GL43.GL_MAX_HEIGHT -> new long[] {f != null ? gl.getInteger(GL11.GL_MAX_TEXTURE_SIZE) : 0};
            case GL43.GL_MAX_DEPTH -> new long[] {f != null && target == GL12.GL_TEXTURE_3D ? gl.getInteger(GL12.GL_MAX_3D_TEXTURE_SIZE) : 0};
            case GL43.GL_MAX_LAYERS -> new long[] {f != null && (target == GL30.GL_TEXTURE_1D_ARRAY || target == GL30.GL_TEXTURE_2D_ARRAY || target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY || target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY) ? gl.getInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS) : 0};
            case GL43.GL_COLOR_RENDERABLE -> new long[] {f != null && !f.isDepthOrStencil() && !f.isCompressed() ? 1 : 0};
            case GL43.GL_DEPTH_RENDERABLE -> new long[] {f != null && f.isDepth() ? 1 : 0};
            case GL43.GL_STENCIL_RENDERABLE -> new long[] {f != null && f.isStencil() ? 1 : 0};
            case GL43.GL_FRAMEBUFFER_RENDERABLE -> new long[] {f != null && !f.isCompressed() ? GL43.GL_FULL_SUPPORT : GL11.GL_NONE};
            default -> new long[] {0};
        };
    }

    private static long[] sampleCounts(GlBase<?, ?, ?> gl, Formats.Format f) {
        final int most = f.isInteger() ? gl.getInteger(GL33.GL_MAX_INTEGER_SAMPLES) : gl.getInteger(GL30.GL_MAX_SAMPLES);
        final int count = Math.max(0, 31 - Integer.numberOfLeadingZeros(Math.max(1, most)));
        final long[] values = new long[count];
        for (int i = 0; i < count; i++) values[i] = 1L << (count - i);
        return values;
    }

    /** Writes as many values as fit in bufSize elements, each four or eight bytes. */
    public static void write(long[] values, int bufSize, long params, boolean wide) {
        for (int i = 0; i < Math.min(bufSize, values.length); i++) {
            if (wide) org.lwjgl.system.MemoryUtil.memPutLong(params + i * 8L, values[i]);
            else org.lwjgl.system.MemoryUtil.memPutInt(params + i * 4L, (int) values[i]);
        }
    }
}
