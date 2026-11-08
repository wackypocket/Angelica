package com.gtnewhorizons.angelica.glemu.shader;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcSpecializationConstant;

/** A SPIR-V shader takes the road GLSL does: every stage rewrite here works on source, so a binary is turned back into some. */
final class SpirvSource {
    record Result(String source, int error) {}

    private SpirvSource() {}

    private static int model(int glType) {
        return switch (glType) {
            case GL40.GL_TESS_CONTROL_SHADER -> Spv.SpvExecutionModelTessellationControl;
            case GL40.GL_TESS_EVALUATION_SHADER -> Spv.SpvExecutionModelTessellationEvaluation;
            case GL32.GL_GEOMETRY_SHADER -> Spv.SpvExecutionModelGeometry;
            case GL43.GL_COMPUTE_SHADER -> Spv.SpvExecutionModelGLCompute;
            case GL20.GL_VERTEX_SHADER -> Spv.SpvExecutionModelVertex;
            default -> Spv.SpvExecutionModelFragment;
        };
    }

    /** The GLSL of one entry point with these constants set, or the GL error the request is worth. */
    static Result specialized(int[] words, int glType, String entry, int[] constantIds, int[] values) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final long ctx = SpvcSupport.createContext(stack);
            try {
                final long compiler = SpvcSupport.createCompiler(ctx, Spvc.SPVC_BACKEND_GLSL, words, stack);
                if (Spvc.spvc_compiler_set_entry_point(compiler, entry, model(glType)) != Spvc.SPVC_SUCCESS) return new Result(null, GL11.GL_INVALID_VALUE);

                final PointerBuffer pConstants = stack.mallocPointer(1), pCount = stack.mallocPointer(1);
                SpvcSupport.check(ctx, Spvc.spvc_compiler_get_specialization_constants(compiler, pConstants, pCount), "get_specialization_constants");
                final SpvcSpecializationConstant.Buffer constants = SpvcSpecializationConstant.create(pConstants.get(0), (int) pCount.get(0));
                for (int i = 0; i < constantIds.length; i++) {
                    long handle = 0L;
                    for (SpvcSpecializationConstant constant : constants) {
                        if (constant.constant_id() == constantIds[i]) handle = Spvc.spvc_compiler_get_constant_handle(compiler, constant.id());
                    }
                    if (handle == 0L) return new Result(null, GL11.GL_INVALID_VALUE);
                    Spvc.spvc_constant_set_scalar_u32(handle, 0, 0, values[i]);
                }

                final PointerBuffer pOptions = stack.mallocPointer(1);
                SpvcSupport.check(ctx, Spvc.spvc_compiler_create_compiler_options(compiler, pOptions), "create_compiler_options");
                Spvc.spvc_compiler_options_set_uint(pOptions.get(0), Spvc.SPVC_COMPILER_OPTION_GLSL_VERSION, 460);
                Spvc.spvc_compiler_options_set_uint(pOptions.get(0), Spvc.SPVC_COMPILER_OPTION_GLSL_ES, 0);
                Spvc.spvc_compiler_options_set_uint(pOptions.get(0), Spvc.SPVC_COMPILER_OPTION_GLSL_VULKAN_SEMANTICS, 0);
                SpvcSupport.check(ctx, Spvc.spvc_compiler_install_compiler_options(compiler, pOptions.get(0)), "install_compiler_options");

                final PointerBuffer pSource = stack.mallocPointer(1);
                SpvcSupport.check(ctx, Spvc.spvc_compiler_compile(compiler, pSource), "compile");
                return new Result(MemoryUtil.memUTF8(pSource.get(0)), GL11.GL_NO_ERROR);
            } finally {
                Spvc.spvc_context_destroy(ctx);
            }
        }
    }
}
