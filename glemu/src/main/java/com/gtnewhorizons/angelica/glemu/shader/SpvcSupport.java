package com.gtnewhorizons.angelica.glemu.shader;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;

import java.nio.IntBuffer;
import java.util.regex.Pattern;

import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memFree;

/** The SPIRV-Cross setup every shader translation starts with. */
public final class SpvcSupport {
    private SpvcSupport() {}

    private static final Pattern PADDED_ELEMENT = Pattern.compile("template\\s*<\\s*typename T,\\s*\\w+ stride\\s*>\\s*struct spvPaddedArrayElement \\{ T data; char padding\\[stride - sizeof\\(T\\)\\]; \\};");
    private static final String PADDED_ELEMENT_OR_NOT = "template<typename T, uint stride, uint padded = stride - sizeof(T)>\nstruct spvPaddedArrayElement { T data; char padding[padded]; };\n"
        + "template<typename T, uint stride>\nstruct spvPaddedArrayElement<T, stride, 0> { T data; };";

    /** SPIRV-Cross pads an element it wrongly thinks is short of its stride, and a padding array of no length does not compile. */
    public static String withEmptyPadding(String msl) {
        return PADDED_ELEMENT.matcher(msl).replaceFirst(PADDED_ELEMENT_OR_NOT);
    }

    /** Whether the source pads array elements in a way withEmptyPadding doesn't know, and so can't correct. */
    public static boolean padsUnknownly(String msl) {
        return msl.contains("struct spvPaddedArrayElement") && !msl.contains("uint padded = stride - sizeof(T)") && !PADDED_ELEMENT.matcher(msl).find();
    }

    public static int executionModel(int glType) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> Spv.SpvExecutionModelVertex;
            case GL43.GL_COMPUTE_SHADER -> Spv.SpvExecutionModelGLCompute;
            default -> Spv.SpvExecutionModelFragment;
        };
    }

    public static long createContext(MemoryStack stack) {
        final PointerBuffer pContext = stack.mallocPointer(1);
        if (Spvc.spvc_context_create(pContext) != Spvc.SPVC_SUCCESS) throw new IllegalStateException("spvc_context_create failed");
        return pContext.get(0);
    }

    public static long createCompiler(long ctx, int backend, int[] words, MemoryStack stack) {
        final PointerBuffer pIr = stack.mallocPointer(1);
        final IntBuffer code = memAllocInt(words.length);
        try {
            code.put(words).flip();
            check(ctx, Spvc.spvc_context_parse_spirv(ctx, code, words.length, pIr), "parse_spirv");
        } finally {
            memFree(code);
        }
        final PointerBuffer pCompiler = stack.mallocPointer(1);
        check(ctx, Spvc.spvc_context_create_compiler(ctx, backend, pIr.get(0), Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pCompiler), "create_compiler");
        return pCompiler.get(0);
    }

    public static void check(long ctx, int result, String what) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new IllegalStateException("SPIRV-Cross " + what + " failed: " + Spvc.spvc_context_get_last_error_string(ctx));
        }
    }
}
