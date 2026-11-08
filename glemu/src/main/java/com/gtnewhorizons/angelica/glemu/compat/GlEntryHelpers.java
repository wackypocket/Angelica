package com.gtnewhorizons.angelica.glemu.compat;

import com.gtnewhorizons.angelica.glemu.gl.GlValidation;
import com.gtnewhorizons.angelica.glemu.NativeBackendBase;
import com.gtnewhorizons.angelica.glemu.gl.GlBase;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;
import com.gtnewhorizons.angelica.glemu.resource.SamplerObj;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

/** What {@link GlEntryPoints} needs to turn C arguments into the calls GLSM and the backends take. */
final class GlEntryHelpers {

    private static final Map<String, Long> STRINGS = new ConcurrentHashMap<>();

    private GlEntryHelpers() {}

    static RenderBackend rb() {
        return BackendManager.RENDER_BACKEND;
    }

    static boolean isClientMemory(long pointer) {
        return pointer != 0L && GLSMBuffers.getBoundVBO() == 0;
    }

    /** Client memory has no length to hand over: the draw reads only what its count and stride reach. */
    static ByteBuffer clientMemory(long pointer) {
        return MemoryUtil.memByteBuffer(pointer, Integer.MAX_VALUE);
    }

    static GlBase<?, ?, ?> gl() {
        if (BackendManager.RENDER_BACKEND instanceof NativeBackendBase<?> nativeBackend) return nativeBackend.glForDirectCalls();
        throw GlFunctions.NEEDS_NATIVE_BACKEND;
    }

    static IntBuffer ints(long p, int n) {
        return MemoryUtil.memIntBufferSafe(p, n);
    }

    static FloatBuffer floats(long p, int n) {
        return MemoryUtil.memFloatBufferSafe(p, n);
    }

    static DoubleBuffer doubles(long p, int n) {
        return MemoryUtil.memDoubleBufferSafe(p, n);
    }

    static ByteBuffer bytes(long p, int n) {
        return MemoryUtil.memByteBufferSafe(p, n);
    }

    /** A pointer here is an offset into the pack buffer when one is bound, and otherwise the caller's memory. */
    static void getTexImageAt(int target, int level, int format, int type, long pointer) {
        if (rb().getInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING) != 0) rb().getTexImage(target, level, format, type, pointer);
        else rb().getTexImage(target, level, format, type, pix(pointer));
    }

    static boolean unpackBufferBound() {
        return rb().getInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) != 0;
    }

    /** Only the native backends take a base instance; elsewhere instances keep starting at 0. */
    static void setBaseInstance(int instance) {
        if (BackendManager.RENDER_BACKEND instanceof NativeBackendBase<?> nativeBackend) nativeBackend.glForDirectCalls().st().baseInstance = instance;
    }

    static void samplerParameterv(int sampler, int pname, long values, boolean isFloat) {
        if (pname == GL11.GL_TEXTURE_BORDER_COLOR) gl().samplerBorder(sampler, isFloat ? null : ints(values, 4), isFloat ? floats(values, 4) : null);
        else if (isFloat) rb().samplerParameterf(sampler, pname, MemoryUtil.memGetFloat(values));
        else rb().samplerParameteri(sampler, pname, MemoryUtil.memGetInt(values));
    }

    /** The I forms take a border color as integers as they are, where the plain integer form normalizes them. */
    static void samplerParameterI(int sampler, int pname, long values) {
        if (pname != GL11.GL_TEXTURE_BORDER_COLOR) {
            rb().samplerParameteri(sampler, pname, MemoryUtil.memGetInt(values));
            return;
        }
        final FloatBuffer border = BufferUtils.createFloatBuffer(4);
        for (int i = 0; i < 4; i++) border.put(i, MemoryUtil.memGetInt(values + i * 4L));
        gl().samplerBorder(sampler, null, border);
    }

    static void getSamplerParameterI(int sampler, int pname, long out) {
        if (pname != GL11.GL_TEXTURE_BORDER_COLOR) {
            MemoryUtil.memPutInt(out, (int) samplerParameter(sampler, pname));
            return;
        }
        final SamplerObj s = gl().samplerObjects.get(sampler);
        if (s == null) return;
        for (int i = 0; i < 4; i++) MemoryUtil.memPutInt(out + i * 4L, (int) s.border[i]);
    }

    static void getSamplerParameterv(int sampler, int pname, long out, boolean isFloat) {
        if (pname == GL11.GL_TEXTURE_BORDER_COLOR) {
            final SamplerObj s = gl().samplerObjects.get(sampler);
            if (s == null) return;
            for (int i = 0; i < 4; i++) {
                if (isFloat) MemoryUtil.memPutFloat(out + i * 4L, s.border[i]);
                else MemoryUtil.memPutInt(out + i * 4L, (int) (s.border[i] * Integer.MAX_VALUE));
            }
        } else if (isFloat) {
            MemoryUtil.memPutFloat(out, samplerParameter(sampler, pname));
        } else {
            MemoryUtil.memPutInt(out, (int) samplerParameter(sampler, pname));
        }
    }

    static void textureParameterfv(int texture, int pname, long values) {
        final int target = textureTarget(texture);
        if (pname == GL11.GL_TEXTURE_BORDER_COLOR) gl().textureParameterfv(texture, target, pname, floats(values, 4));
        else rb().textureParameterf(texture, target, pname, MemoryUtil.memGetFloat(values));
    }

    /** The one texel a buffer clear repeats. */
    static ByteBuffer clearTexel(long p, int format, int type) {
        return bytes(p, PixelConvert.pixelBytes(format, type));
    }

    /** A view for callers that take the address and work out the size from the image themselves. */
    static ByteBuffer pix(long p) {
        return bytes(p, Integer.MAX_VALUE);
    }

    static String str(long p) {
        return MemoryUtil.memUTF8Safe(p);
    }

    /** A GL string argument, whose length is negative when it is null-terminated. */
    static String text(long p, int length) {
        return length < 0 ? MemoryUtil.memUTF8(p) : MemoryUtil.memUTF8(p, length);
    }

    /** GL owns the strings it returns, so each stays allocated for the life of the game. */
    static long cstr(String s) {
        return s == null ? 0L : STRINGS.computeIfAbsent(s, v -> MemoryUtil.memAddress(MemoryUtil.memUTF8(v)));
    }

    static void rect(float x1, float y1, float x2, float y2) {
        GLSMDraws.glBegin(GL11.GL_QUADS);
        GLSMDraws.glVertex2f(x1, y1);
        GLSMDraws.glVertex2f(x2, y1);
        GLSMDraws.glVertex2f(x2, y2);
        GLSMDraws.glVertex2f(x1, y2);
        GLSMDraws.glEnd();
    }

    static FloatBuffer widen(long p, int n) {
        final FloatBuffer out = BufferUtils.createFloatBuffer(n);
        for (int i = 0; i < n; i++) out.put(i, MemoryUtil.memGetInt(p + i * 4L) / 2147483647f);
        return out;
    }

    static FloatBuffer narrowed(long p, int n) {
        final FloatBuffer out = BufferUtils.createFloatBuffer(n);
        for (int i = 0; i < n; i++) out.put(i, (float) MemoryUtil.memGetDouble(p + i * 8L));
        return out;
    }

    static FloatBuffer transposed(long p) {
        final FloatBuffer out = BufferUtils.createFloatBuffer(16);
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) out.put(c * 4 + r, MemoryUtil.memGetFloat(p + (r * 4L + c) * 4L));
        }
        return out;
    }

    static DoubleBuffer transposedD(long p) {
        final DoubleBuffer out = BufferUtils.createDoubleBuffer(16);
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) out.put(c * 4 + r, MemoryUtil.memGetDouble(p + (r * 4L + c) * 8L));
        }
        return out;
    }

    static void callLists(int n, int type, long lists) {
        switch (type) {
            case GL11.GL_UNSIGNED_BYTE -> GLSMDraws.glCallLists(bytes(lists, n));
            case GL11.GL_UNSIGNED_SHORT -> GLSMDraws.glCallLists(MemoryUtil.memShortBuffer(lists, n));
            case GL11.GL_UNSIGNED_INT -> GLSMDraws.glCallLists(ints(lists, n));
            default -> { }
        }
    }

    static String joined(int count, long strings, long lengths) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            final long s = MemoryUtil.memGetAddress(strings + i * (long) Pointer.POINTER_SIZE);
            final int length = lengths == 0L ? -1 : MemoryUtil.memGetInt(lengths + i * 4L);
            out.append(length < 0 ? MemoryUtil.memUTF8(s) : MemoryUtil.memUTF8(s, length));
        }
        return out.toString();
    }

    /** One attachment of the draw framebuffer, with everything else about clearing put back afterwards. */
    static void clearBuffer(int buffer, int drawBuffer, float r, float g, float b, float a) {
        final GlState st = gl().st();
        if (st.conditionalSkip) return;
        if (buffer == GL11.GL_COLOR) {
            final float[] color = st.clearColor.clone();
            final boolean[][] masks = new boolean[GlState.MAX_DRAW_BUFFERS][];
            for (int i = 0; i < masks.length; i++) {
                masks[i] = st.colorMask[i].clone();
                if (i != drawBuffer) gl().colorMaski(i, false, false, false, false);
            }
            rb().clearColor(r, g, b, a);
            rb().clear(GL11.GL_COLOR_BUFFER_BIT);
            rb().clearColor(color[0], color[1], color[2], color[3]);
            for (int i = 0; i < masks.length; i++) gl().colorMaski(i, masks[i][0], masks[i][1], masks[i][2], masks[i][3]);
        } else if (buffer == GL11.GL_DEPTH) {
            final double depth = st.clearDepth;
            rb().clearDepth(r);
            rb().clear(GL11.GL_DEPTH_BUFFER_BIT);
            rb().clearDepth(depth);
        } else if (buffer == GL11.GL_STENCIL) {
            final int stencil = st.clearStencil;
            rb().clearStencil((int) r);
            rb().clear(GL11.GL_STENCIL_BUFFER_BIT);
            rb().clearStencil(stencil);
        }
    }

    private static boolean attribInRange(Vao vao, int index) {
        final boolean inRange = index >= 0 && index < vao.enabled.length;
        if (!inRange) gl().recordError(GL11.GL_INVALID_VALUE);
        return inRange;
    }

    /** A shader reads floats here, and GL still has to hand back what was set. */
    static void attribL(int index, double x, double y, double z, double w) {
        final double[] held = gl().st().attribDoubles;
        if (index >= 0 && index * 4 < held.length) {
            held[index * 4] = x;
            held[index * 4 + 1] = y;
            held[index * 4 + 2] = z;
            held[index * 4 + 3] = w;
            gl().st().attribVersion++;
        }
        com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs.glVertexAttrib4f(index, (float) x, (float) y, (float) z, (float) w);
    }

    /** The doubles glVertexAttribL last set, while no float setter has replaced them since. */
    static void vertexAttribDoubles(int index, int pname, long out) {
        final com.gtnewhorizons.angelica.glemu.gl.GlState st = gl().st();
        if (pname == GL20.GL_CURRENT_VERTEX_ATTRIB && index >= 0 && index * 4 < st.attribDoubles.length && heldAsDoubles(st, index)) {
            for (int i = 0; i < 4; i++) org.lwjgl.system.MemoryUtil.memPutDouble(out + i * 8L, st.attribDoubles[index * 4 + i]);
            return;
        }
        org.lwjgl.system.MemoryUtil.memPutDouble(out, vertexAttrib(index, pname));
    }

    private static boolean heldAsDoubles(com.gtnewhorizons.angelica.glemu.gl.GlState st, int index) {
        for (int i = 0; i < 4; i++) {
            if ((float) st.attribDoubles[index * 4 + i] != st.attribValue[index * 4 + i]) return false;
        }
        return true;
    }

    static int vertexAttrib(int index, int pname) {
        final Vao vao = gl().st().vao;
        if (!attribInRange(vao, index)) return 0;
        return switch (pname) {
            case GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED -> vao.enabled[index] ? 1 : 0;
            case GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE -> vao.size[index];
            case GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE -> vao.type[index];
            case GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE -> vao.pointerStride[index];
            case GL43.GL_VERTEX_ATTRIB_BINDING -> vao.bindingOf[index];
            case GL43.GL_VERTEX_ATTRIB_RELATIVE_OFFSET -> vao.relativeOffset[index];
            // Here index is a binding point, as glGetVertexArrayIndexed64iv has it.
            case GL43.GL_VERTEX_BINDING_OFFSET -> index < vao.bindingOffset.length ? (int) vao.bindingOffset[index] : 0;
            case GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED -> vao.normalized[index] ? 1 : 0;
            case GL30.GL_VERTEX_ATTRIB_ARRAY_INTEGER -> vao.integer[index] ? 1 : 0;
            case org.lwjgl.opengl.GL46C.GL_VERTEX_ATTRIB_ARRAY_LONG -> vao.isLong[index] ? 1 : 0;
            case GL15.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING -> vao.bindingBuffer[vao.bindingOf[index]];
            case GL33.GL_VERTEX_ATTRIB_ARRAY_DIVISOR -> vao.bindingDivisor[vao.bindingOf[index]];
            default -> 0;
        };
    }

    static long vertexAttribOffset(int index) {
        final Vao vao = gl().st().vao;
        if (!attribInRange(vao, index)) return 0L;
        return vao.pointerOffset[index];
    }

    static void bindImageTextures(int first, int count, long textures) {
        for (int i = 0; i < count; i++) {
            final int texture = textures == 0L ? 0 : MemoryUtil.memGetInt(textures + i * 4L);
            if (texture != 0 && !gl().textures().exists(texture)) {
                gl().recordError(GL11.GL_INVALID_OPERATION);
                continue;
            }
            final int format = texture == 0 ? GL11.GL_RGBA8 : rb().getTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (texture != 0 && (!GlValidation.imageFormat(format) || rb().getTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_WIDTH) == 0)) {
                gl().recordError(GL11.GL_INVALID_OPERATION);
                continue;
            }
            rb().bindImageTexture(first + i, texture, 0, texture != 0, 0, GL15.GL_READ_WRITE, format);
        }
    }

    /** With a query buffer bound, the pointer is an offset into it and the result goes there. */
    static void queryObject(int id, int pname, long params, boolean wide) {
        queryObject(id, pname, params, wide, Integer.MAX_VALUE);
    }

    /** The unsigned 32-bit form, which GL clamps at 2^32-1 rather than at the signed limit. */
    static void queryObjectUnsigned(int id, int pname, long params) {
        queryObject(id, pname, params, false, 0xFFFFFFFFL);
    }

    private static void queryObject(int id, int pname, long params, boolean wide, long narrowMax) {
        final long value = rb().getQueryObjectui64(id, pname);
        if (gl().st().queryBuffer != 0) {
            final java.nio.ByteBuffer result = MemoryUtil.memAlloc(wide ? 8 : 4);
            if (wide) result.putLong(0, value);
            else result.putInt(0, (int) Math.min(narrowMax, value));
            rb().bufferSubData(GL44.GL_QUERY_BUFFER, params, result);
            MemoryUtil.memFree(result);
        } else if (wide) {
            MemoryUtil.memPutLong(params, value);
        } else {
            MemoryUtil.memPutInt(params, (int) Math.min(narrowMax, value));
        }
    }

    /** A bad entry raises its error and leaves its binding alone, while the entries around it still take effect. */
    static void bindBuffersBase(int target, int first, int count, long buffers) {
        for (int i = 0; i < count; i++) {
            final int buffer = buffers == 0L ? 0 : MemoryUtil.memGetInt(buffers + i * 4L);
            if (buffer != 0 && !gl().buffers().exists(buffer)) gl().recordError(GL11.GL_INVALID_OPERATION);
            else rb().bindBufferBase(target, first + i, buffer);
        }
    }

    static void bindBuffersRange(int target, int first, int count, long buffers, long offsets, long sizes) {
        for (int i = 0; i < count; i++) {
            final int buffer = buffers == 0L ? 0 : MemoryUtil.memGetInt(buffers + i * 4L);
            if (buffer == 0) {
                rb().bindBufferBase(target, first + i, 0);
            } else if (!gl().buffers().exists(buffer)) {
                gl().recordError(GL11.GL_INVALID_OPERATION);
            } else {
                final long offset = MemoryUtil.memGetAddress(offsets + (long) i * Long.BYTES), size = MemoryUtil.memGetAddress(sizes + (long) i * Long.BYTES);
                if (offset < 0 || size <= 0 || offset + size > gl().buffers().get(buffer).size) gl().recordError(GL11.GL_INVALID_VALUE);
                else rb().bindBufferRange(target, first + i, buffer, offset, size);
            }
        }
    }

    static float samplerParameter(int sampler, int pname) {
        final SamplerObj s = gl().samplerObjects.get(sampler);
        if (s == null) return 0f;
        return switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> s.minFilter;
            case GL11.GL_TEXTURE_MAG_FILTER -> s.magFilter;
            case GL11.GL_TEXTURE_WRAP_S -> s.wrapS;
            case GL11.GL_TEXTURE_WRAP_T -> s.wrapT;
            case GL12.GL_TEXTURE_WRAP_R -> s.wrapR;
            case GL12.GL_TEXTURE_MIN_LOD -> s.minLod;
            case GL12.GL_TEXTURE_MAX_LOD -> s.maxLod;
            case GL14.GL_TEXTURE_LOD_BIAS -> s.lodBias;
            case GL14.GL_TEXTURE_COMPARE_MODE -> s.compareMode;
            case GL14.GL_TEXTURE_COMPARE_FUNC -> s.compareFunc;
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> s.anisotropy;
            default -> 0f;
        };
    }

    /** glDrawArraysIndirect has no backend path of its own, so the command is read and drawn directly. */
    static void drawArraysIndirect(int mode, long offset) {
        if (offset < 0 || offset + 16 > rb().getBufferParameteri(GL40.GL_DRAW_INDIRECT_BUFFER, GL15.GL_BUFFER_SIZE)) {
            gl().recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        final IntBuffer command = BufferUtils.createIntBuffer(4);
        rb().getBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, offset, command);
        setBaseInstance(command.get(3));
        GLSMDraws.glDrawArraysInstanced(mode, command.get(2), command.get(0), command.get(1));
        setBaseInstance(0);
    }

    private static int uniformComponents(int program, int location) {
        final com.gtnewhorizons.angelica.glemu.shader.ProgramBase<?> p = gl().programBase(program);
        if (p == null || location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) return 0;
        return com.gtnewhorizons.angelica.glemu.shader.ProgramsBase.components(p.uniforms.get(p.locationUniform[location]).glType);
    }

    /** glGetUniformdv: uniforms hold floats here, and a double one gives back all its components. */
    static void uniformDoubleValues(int location, double... values) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final long at = stack.nmalloc(8, values.length * 8);
            for (int i = 0; i < values.length; i++) MemoryUtil.memPutDouble(at + i * 8L, values[i]);
            gl().uniformDoubles(location, 1, values.length, at);
        }
    }

    static void uniformDoubles(int program, int location, long out) {
        if (gl().getUniformDoubles(program, location, out)) return;
        final FloatBuffer value = BufferUtils.createFloatBuffer(16);
        rb().getUniformfv(program, location, value);
        final int components = Math.max(1, uniformComponents(program, location));
        for (int c = 0; c < components; c++) MemoryUtil.memPutDouble(out + c * 8L, value.get(c));
    }

    /** glGetUniformuiv: what a floating-point uniform holds below zero comes back as zero. */
    static void uniformUnsigned(int program, int location, long out) {
        rb().getUniformiv(program, location, ints(out, 16));
        final com.gtnewhorizons.angelica.glemu.shader.ProgramBase<?> p = gl().programBase(program);
        if (p == null || location < 0 || location >= p.locationUniform.length || p.locationUniform[location] < 0) return;
        if (com.gtnewhorizons.angelica.glemu.shader.ProgramsBase.scalarKind(p.uniforms.get(p.locationUniform[location]).glType) != 0) return;
        final int components = uniformComponents(program, location);
        for (int c = 0; c < components; c++) MemoryUtil.memPutInt(out + c * 4L, Math.max(0, MemoryUtil.memGetInt(out + c * 4L)));
    }

    static double uniformValue(int program, int location) {
        final FloatBuffer value = BufferUtils.createFloatBuffer(16);
        rb().getUniformfv(program, location, value);
        return value.get(0);
    }

    /** glTextureParameterI: a border colour is kept as the integers given, which reading it back has to return exactly. */
    static void textureParameterIntegers(int texture, int pname, long params) {
        rb().textureParameteriv(texture, textureTarget(texture), pname, ints(params, 4));
        if (pname != GL11.GL_TEXTURE_BORDER_COLOR) return;
        gl().keepIntegerBorder(gl().textures().get(texture), params);
    }

    static void texParameterIntegers(int target, int pname, long params) {
        GLSMTextures.glTexParameter(target, pname, ints(params, 4));
        if (pname == GL11.GL_TEXTURE_BORDER_COLOR) gl().boundIntegerBorder(target, params);
    }

    static int textureTarget(int texture) {
        return gl().textureTarget(texture);
    }

    /** Makes a texture unit active for one call, returning the one to restore. */
    static int useUnit(int unit) {
        final int was = rb().getInteger(GL13.GL_ACTIVE_TEXTURE);
        GLSMTextures.glActiveTexture(unit);
        return was;
    }

    static int useClientUnit(int unit) {
        final int was = GLSMTextures.getClientActiveTextureUnit() + GL13.GL_TEXTURE0;
        GLSMTextures.glClientActiveTexture(unit);
        return was;
    }

    /** Binds a texture to a target, or to the cube map for one of its faces, returning the texture to restore. */
    static int useTexture(int target, int texture) {
        final int bindTarget = target >= GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z ? GL13.GL_TEXTURE_CUBE_MAP : target;
        final int was = gl().st().boundTexture(bindTarget);
        GLSMTextures.glBindTexture(bindTarget, texture);
        return was;
    }

    /** Selects a matrix stack for one call, a texture unit's for GL_TEXTUREn, returning the mode and unit to restore in one int. */
    static int useMatrix(int mode) {
        final int was = GLSMMatrices.getMatrixMode().getMode() | rb().getInteger(GL13.GL_ACTIVE_TEXTURE) << 16;
        if (mode >>> 16 != 0) {
            GLSMTextures.glActiveTexture(mode >>> 16);
            GLSMMatrices.glMatrixMode(mode & 0xFFFF);
        } else if (mode >= GL13.GL_TEXTURE0 && mode < GL13.GL_TEXTURE0 + 32) {
            GLSMTextures.glActiveTexture(mode);
            GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
        } else {
            GLSMMatrices.glMatrixMode(mode);
        }
        return was;
    }

    static int useArrayBuffer(int buffer) {
        final int was = GLSMBuffers.getBoundVBO();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        return was;
    }

    static String markerText(int length, long marker) {
        return marker == 0L ? "" : length > 0 ? text(marker, length) : str(marker);
    }

    static void uniform64(int program, int location, long... values) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final long at = stack.nmalloc(8, values.length * 8);
            for (int i = 0; i < values.length; i++) MemoryUtil.memPutLong(at + i * 8L, values[i]);
            gl().uniform64(program, location, 1, values.length, at);
        }
    }

    /** A pixel pointer as memory: the bound unpack buffer's contents copied out for the call if there is one, or the pointer itself. */
    static void withUnpacked(long pointer, long bytes, LongConsumer use) {
        final int buffer = rb().getInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        if (buffer == 0) {
            if (pointer != 0L) use.accept(pointer);
            return;
        }
        final ByteBuffer copy = MemoryUtil.memAlloc((int) bytes);
        try {
            rb().getBufferSubData(GL21.GL_PIXEL_UNPACK_BUFFER, pointer, copy);
            use.accept(MemoryUtil.memAddress(copy));
        } finally {
            MemoryUtil.memFree(copy);
        }
    }

    static void indexedBooleans(int pname, int index, long out) {
        if (pname == GL30.GL_COLOR_WRITEMASK && index >= 0 && index < GlState.MAX_DRAW_BUFFERS) {
            final boolean[] mask = gl().st().colorMask[index];
            for (int i = 0; i < 4; i++) MemoryUtil.memPutByte(out + i, (byte) (mask[i] ? 1 : 0));
        } else {
            MemoryUtil.memPutByte(out, (byte) (rb().getIntegerIndexed(pname, index) != 0 ? 1 : 0));
        }
    }

    /** How many integers a query answers with where the single value forms of the query have no room for them, or 0 if it answers with one. */
    private static int integerVector(int pname) {
        return switch (pname) {
            case GL11.GL_VIEWPORT, GL11.GL_SCISSOR_BOX -> 4;
            case GL11.GL_MAX_VIEWPORT_DIMS, GL41.GL_VIEWPORT_BOUNDS_RANGE -> 2;
            default -> 0;
        };
    }

    static void integers64(int pname, long out) {
        if (pname == GL43.GL_MAX_ELEMENT_INDEX) {
            MemoryUtil.memPutLong(out, 0xFFFFFFFFL);
            return;
        }
        final int count = integerVector(pname);
        if (count == 0) {
            MemoryUtil.memPutLong(out, GLSMQueries.glGetInteger(pname));
            return;
        }
        final IntBuffer values = BufferUtils.createIntBuffer(16);
        GLSMQueries.glGetInteger(pname, values);
        for (int i = 0; i < count; i++) MemoryUtil.memPutLong(out + i * 8L, values.get(i));
    }

    static void doubleValues(int pname, long out) {
        final int count = integerVector(pname);
        if (count == 0) {
            GLSMQueries.glGetDouble(pname, doubles(out, 16));
            return;
        }
        final IntBuffer values = BufferUtils.createIntBuffer(16);
        GLSMQueries.glGetInteger(pname, values);
        for (int i = 0; i < count; i++) MemoryUtil.memPutDouble(out + i * 8L, values.get(i));
    }

    /** Setting the first viewport goes through the call that sets them all, so the others are put back after it. */
    static void setViewport(int index, float x, float y, float width, float height) {
        final GlState st = gl().st();
        final float size = gl().gpuCaps().maxImageDimension2D;
        final float[] value = {x, y, Math.min(width, size), Math.min(height, size)};
        if (index == 0) {
            final float[][] others = st.indexedViewports.clone();
            rb().viewport((int) value[0], (int) value[1], (int) value[2], (int) value[3]);
            System.arraycopy(others, 0, st.indexedViewports, 0, others.length);
        }
        st.indexedViewports[index] = value;
        st.viewportsVary = true;
        st.dynamicGen++;
    }

    static void setScissor(int index, int x, int y, int width, int height) {
        final GlState st = gl().st();
        if (index == 0) {
            final float[][] others = st.indexedScissors.clone();
            rb().scissor(x, y, width, height);
            System.arraycopy(others, 0, st.indexedScissors, 0, others.length);
        }
        st.indexedScissors[index] = new float[] {x, y, width, height};
        st.scissorsVary = true;
        st.dynamicGen++;
    }

    static void setDepthRange(int index, double near, double far) {
        final GlState st = gl().st();
        final double clampedNear = Math.max(0.0, Math.min(1.0, near)), clampedFar = Math.max(0.0, Math.min(1.0, far));
        if (index == 0) {
            final float[][] others = st.indexedDepthRanges.clone();
            rb().depthRange(clampedNear, clampedFar);
            System.arraycopy(others, 0, st.indexedDepthRanges, 0, others.length);
        }
        st.indexedDepthRanges[index] = new float[] {(float) clampedNear, (float) clampedFar};
        st.depthRangesVary = true;
        st.dynamicGen++;
    }

    /** One viewport's values, or null if the query is not a viewport's. A viewport no call has set holds what the first one started with. */
    private static float[] viewportValues(int pname, int index) {
        final boolean depth = pname == GL11.GL_DEPTH_RANGE;
        if (!depth && pname != GL11.GL_VIEWPORT && pname != GL11.GL_SCISSOR_BOX) return null;
        if (index < 0 || index >= GlState.MAX_VIEWPORTS) {
            gl().recordError(GL11.GL_INVALID_VALUE);
            return new float[0];
        }
        final GlState st = gl().st();
        final float[] set = (depth ? st.indexedDepthRanges : pname == GL11.GL_VIEWPORT ? st.indexedViewports : st.indexedScissors)[index];
        if (set != null) return set;
        final FloatBuffer values = BufferUtils.createFloatBuffer(16);
        if (depth) {
            GLSMQueries.glGetFloat(pname, values);
            return new float[] {values.get(0), values.get(1)};
        }
        final IntBuffer ints = BufferUtils.createIntBuffer(16);
        GLSMQueries.glGetInteger(pname, ints);
        return new float[] {ints.get(0), ints.get(1), ints.get(2), ints.get(3)};
    }

    static void indexedFloats(int pname, int index, long out) {
        final float[] viewport = viewportValues(pname, index);
        if (viewport == null) {
            MemoryUtil.memPutFloat(out, rb().getIntegerIndexed(pname, index));
            return;
        }
        for (int i = 0; i < viewport.length; i++) MemoryUtil.memPutFloat(out + i * 4L, viewport[i]);
    }

    static void indexedDoubles(int pname, int index, long out) {
        final float[] viewport = viewportValues(pname, index);
        if (viewport == null) {
            MemoryUtil.memPutDouble(out, rb().getIntegerIndexed(pname, index));
            return;
        }
        for (int i = 0; i < viewport.length; i++) MemoryUtil.memPutDouble(out + i * 8L, viewport[i]);
    }

    static void indexedIntegers(int pname, int index, long out) {
        if (pname == GL11.GL_SCISSOR_TEST) {
            MemoryUtil.memPutInt(out, rb().isEnabledi(pname, index) ? 1 : 0);
            return;
        }
        final float[] viewport = viewportValues(pname, index);
        if (viewport != null) {
            for (int i = 0; i < viewport.length; i++) MemoryUtil.memPutInt(out + i * 4L, pname == GL11.GL_DEPTH_RANGE ? (int) Math.round(viewport[i] * (double) Integer.MAX_VALUE) : Math.round(viewport[i]));
            return;
        }
        if (pname == GL30.GL_COLOR_WRITEMASK && index >= 0 && index < GlState.MAX_DRAW_BUFFERS) {
            final boolean[] mask = gl().st().colorMask[index];
            for (int i = 0; i < 4; i++) MemoryUtil.memPutInt(out + i * 4L, mask[i] ? 1 : 0);
        } else {
            MemoryUtil.memPutInt(out, rb().getIntegerIndexed(pname, index));
        }
    }

    /** Compiles source into a shader attached to the program, which keeps what it needs once the shader is deleted. */
    static void attachCompiled(int program, int type, String source) {
        final int shader = GLSMPrograms.glCreateShader(type);
        GLSMPrograms.glShaderSource(shader, source);
        GLSMPrograms.glCompileShader(shader);
        GLSMPrograms.glAttachShader(program, shader);
        GLSMPrograms.glDeleteShader(shader);
    }

    static int bindBuffer(int buffer) {
        final int was = rb().getInteger(GL31.GL_COPY_WRITE_BUFFER);
        rb().bindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
        return was;
    }

    static int bindFramebuffer(int framebuffer) {
        final int was = rb().getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        rb().bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        return was;
    }

    static int bindRenderbuffer(int renderbuffer) {
        final int was = rb().getInteger(GL30.GL_RENDERBUFFER_BINDING);
        rb().bindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
        return was;
    }

    /** Zero is the default vertex array, which GLSM keeps as one of its own. */
    static int bindVertexArray(int vao) {
        final int was = rb().getInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        rb().bindVertexArray(vao == 0 ? com.gtnewhorizons.angelica.glsm.GLStateManager.ctx().defaultVAO : vao);
        return was;
    }

    /** One component of a 2_10_10_10 word, whose alpha is the top two bits. */
    static float packed(int type, int value, int component, boolean normalized) {
        final int bits = component == 3 ? 2 : 10;
        final int raw = value >>> component * 10 & (1 << bits) - 1;
        if (type == GL33.GL_INT_2_10_10_10_REV) {
            final int signed = raw << 32 - bits >> 32 - bits;
            return normalized ? Math.max(signed / (float) ((1 << bits - 1) - 1), -1f) : signed;
        }
        return normalized ? raw / (float) ((1 << bits) - 1) : raw;
    }

    static void attribI(int index, int x, int y, int z, int w) {
        rb().vertexAttrib4f(index, Float.intBitsToFloat(x), Float.intBitsToFloat(y), Float.intBitsToFloat(z), Float.intBitsToFloat(w));
    }

    /** Copies a name the way glGet*Name returns one: cut to the buffer, null-terminated, with the written length. */
    static void putName(String name, int bufSize, long length, long out) {
        final byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        final int n = Math.max(0, Math.min(bytes.length, bufSize - 1));
        if (out != 0L && bufSize > 0) {
            for (int i = 0; i < n; i++) MemoryUtil.memPutByte(out + i, bytes[i]);
            MemoryUtil.memPutByte(out + n, (byte) 0);
        }
        if (length != 0L) MemoryUtil.memPutInt(length, n);
    }

    static void queryToBuffer(int query, int buffer, int pname, long offset, boolean wide) {
        queryToBuffer(query, buffer, pname, offset, wide, Integer.MAX_VALUE);
    }

    /** The unsigned 32-bit form, clamped at 2^32-1. */
    static void queryToBufferUnsigned(int query, int buffer, int pname, long offset) {
        queryToBuffer(query, buffer, pname, offset, false, 0xFFFFFFFFL);
    }

    /** A 32-bit form takes the result clamped to what it holds, as GL gives it, not its low bits. */
    private static void queryToBuffer(int query, int buffer, int pname, long offset, boolean wide, long narrowMax) {
        final int was = bindBuffer(buffer);
        final ByteBuffer value = BufferUtils.createByteBuffer(8).order(ByteOrder.nativeOrder());
        final long result = rb().getQueryObjectui64(query, pname);
        if (wide) value.putLong(0, result);
        else value.putInt(0, (int) Math.min(narrowMax, result));
        value.limit(wide ? 8 : 4);
        rb().bufferSubData(GL31.GL_COPY_WRITE_BUFFER, offset, value);
        bindBuffer(was);
    }

    /** The mod's callback is C-shaped already, so messages are handed straight to it. */
    static void debugMessageCallback(long callback, long userParam) {
        if (callback == 0L) {
            rb().debugMessageCallback(null, 0L);
            return;
        }
        final GLDebugMessageCallback wrapped = GLDebugMessageCallback.create(callback);
        rb().debugMessageCallback((source, type, id, severity, length, message, user) -> wrapped.invoke(source, type, id, severity, length, message, user), userParam);
    }
}
