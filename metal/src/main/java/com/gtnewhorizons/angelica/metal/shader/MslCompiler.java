package com.gtnewhorizons.angelica.metal.shader;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding2;

import java.nio.IntBuffer;
import java.util.List;
import java.util.regex.Pattern;

import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memUTF8;

public final class MslCompiler {
    private MslCompiler() {}

    public record Binding(int binding, int count, int buffer, int texture, int sampler) {}

    public record Output(String source, String entry, boolean needsSizeBuffer) {}

    public static final int MSL_2_3 = 20300, MSL_3_1 = 30100;

    public static int executionModel(int glType) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> Spv.SpvExecutionModelVertex;
            case GL43.GL_COMPUTE_SHADER -> Spv.SpvExecutionModelGLCompute;
            default -> Spv.SpvExecutionModelFragment;
        };
    }

    public static Output compile(int[] words, int glType, List<Binding> bindings, int sizeBufferIndex, int mslVersion) {
        return compile(words, glType, bindings, sizeBufferIndex, mslVersion, true, false);
    }

    public static Output compile(int[] words, int glType, List<Binding> bindings, int sizeBufferIndex, int mslVersion, boolean fragDepth, boolean noRaster) {
        final int model = executionModel(glType);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final PointerBuffer pContext = stack.mallocPointer(1);
            if (Spvc.spvc_context_create(pContext) != Spvc.SPVC_SUCCESS) throw new IllegalStateException("spvc_context_create failed");
            final long ctx = pContext.get(0);
            try {
                final PointerBuffer pIr = stack.mallocPointer(1);
                final IntBuffer code = memAllocInt(words.length);
                try {
                    code.put(words).flip();
                    check(ctx, Spvc.spvc_context_parse_spirv(ctx, code, words.length, pIr), "parse_spirv");
                } finally {
                    memFree(code);
                }
                final PointerBuffer pCompiler = stack.mallocPointer(1);
                check(ctx, Spvc.spvc_context_create_compiler(ctx, Spvc.SPVC_BACKEND_MSL, pIr.get(0), Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pCompiler), "create_compiler");
                final long compiler = pCompiler.get(0);

                final PointerBuffer pOpts = stack.mallocPointer(1);
                check(ctx, Spvc.spvc_compiler_create_compiler_options(compiler, pOpts), "create_compiler_options");
                final long opts = pOpts.get(0);
                Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, mslVersion);
                Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_1D_AS_2D, true);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, true);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_FRAG_DEPTH_BUILTIN, fragDepth);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_FRAG_STENCIL_REF_BUILTIN, true);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_CLIP_DISTANCE_USER_VARYING, true);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, false);
                if (glType == GL20.GL_VERTEX_SHADER) Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
                if (noRaster) Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_DISABLE_RASTERIZATION, true);
                if (sizeBufferIndex >= 0) Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_MSL_BUFFER_SIZE_BUFFER_INDEX, sizeBufferIndex);
                check(ctx, Spvc.spvc_compiler_install_compiler_options(compiler, opts), "install_compiler_options");

                for (Binding b : bindings) {
                    final SpvcMslResourceBinding2 r = SpvcMslResourceBinding2.calloc(stack);
                    Spvc.spvc_msl_resource_binding_init_2(r);
                    r.stage(model).desc_set(0).binding(b.binding()).count(Math.max(1, b.count()));
                    if (b.buffer() >= 0) r.msl_buffer(b.buffer());
                    if (b.texture() >= 0) r.msl_texture(b.texture());
                    if (b.sampler() >= 0) r.msl_sampler(b.sampler());
                    check(ctx, Spvc.spvc_compiler_msl_add_resource_binding_2(compiler, r), "add_resource_binding");
                }

                final PointerBuffer pSource = stack.mallocPointer(1);
                check(ctx, Spvc.spvc_compiler_compile(compiler, pSource), "compile");
                final String source = memUTF8(pSource.get(0));
                final String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model);
                return new Output(source, entry == null ? "main0" : entry, Spvc.spvc_compiler_msl_needs_buffer_size_buffer(compiler));
            } finally {
                Spvc.spvc_context_destroy(ctx);
            }
        }
    }

    private static final Pattern NAN_INF_CALL = Pattern.compile("(?<![\\w:])(isnan|isinf|isfinite)\\(");
    private static final String NAN_INF_HELPERS = """
        inline bool angelica_isnan(float x) { return (as_type<uint>(x) & 0x7fffffffu) > 0x7f800000u; }
        inline bool2 angelica_isnan(float2 x) { return (as_type<uint2>(x) & 0x7fffffffu) > 0x7f800000u; }
        inline bool3 angelica_isnan(float3 x) { return (as_type<uint3>(x) & 0x7fffffffu) > 0x7f800000u; }
        inline bool4 angelica_isnan(float4 x) { return (as_type<uint4>(x) & 0x7fffffffu) > 0x7f800000u; }
        inline bool angelica_isinf(float x) { return (as_type<uint>(x) & 0x7fffffffu) == 0x7f800000u; }
        inline bool2 angelica_isinf(float2 x) { return (as_type<uint2>(x) & 0x7fffffffu) == 0x7f800000u; }
        inline bool3 angelica_isinf(float3 x) { return (as_type<uint3>(x) & 0x7fffffffu) == 0x7f800000u; }
        inline bool4 angelica_isinf(float4 x) { return (as_type<uint4>(x) & 0x7fffffffu) == 0x7f800000u; }
        inline bool angelica_isfinite(float x) { return (as_type<uint>(x) & 0x7fffffffu) < 0x7f800000u; }
        inline bool2 angelica_isfinite(float2 x) { return (as_type<uint2>(x) & 0x7fffffffu) < 0x7f800000u; }
        inline bool3 angelica_isfinite(float3 x) { return (as_type<uint3>(x) & 0x7fffffffu) < 0x7f800000u; }
        inline bool4 angelica_isfinite(float4 x) { return (as_type<uint4>(x) & 0x7fffffffu) < 0x7f800000u; }
        """;

    public static String bitTestNanInf(String msl) {
        if (!msl.contains("isnan(") && !msl.contains("isinf(") && !msl.contains("isfinite(")) return msl;
        final String rewritten = NAN_INF_CALL.matcher(msl).replaceAll("angelica_$1(");
        final int using = rewritten.indexOf("using namespace metal;");
        if (using < 0) return msl;
        final int at = using + "using namespace metal;".length();
        return rewritten.substring(0, at) + "\n" + NAN_INF_HELPERS + rewritten.substring(at);
    }

    private static void check(long ctx, int result, String what) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new IllegalStateException("SPIRV-Cross " + what + " failed: " + Spvc.spvc_context_get_last_error_string(ctx));
        }
    }
}
