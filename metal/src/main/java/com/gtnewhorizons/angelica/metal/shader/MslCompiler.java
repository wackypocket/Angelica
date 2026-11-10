package com.gtnewhorizons.angelica.metal.shader;

import com.gtnewhorizons.angelica.glemu.shader.SpvcSupport;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding2;

import static org.lwjgl.system.MemoryUtil.memUTF8;

public final class MslCompiler {
    private MslCompiler() {}

    public record Binding(int binding, int count, int buffer, int texture, int sampler) {}

    public record Output(String source, String entry, boolean needsSizeBuffer) {}

    public static final int MSL_2_3 = 20300, MSL_3_1 = 30100;

    private static final Pattern WRITTEN_MASK = Pattern.compile("uint (\\w+) \\[\\[sample_mask\\]\\]");
    private static final Pattern TEXTURE_ATOMIC = Pattern.compile("\\b(\\w+)\\.atomic_\\w+\\(");

    /** A plain read of a texture need not see an atomic on it, even past a fence and from the same thread, so reads of a texture used atomically become atomic loads. */
    private static String atomicReads(String source) {
        String out = source;
        final Matcher atomic = TEXTURE_ATOMIC.matcher(source);
        final java.util.Set<String> done = new java.util.HashSet<>();
        while (atomic.find()) {
            final String name = atomic.group(1);
            if (done.add(name)) out = out.replaceAll("\\b" + Pattern.quote(name) + "\\.read\\(", Matcher.quoteReplacement(name) + ".atomic_load(");
        }
        return out;
    }

    private static final Pattern WRITTEN_MULTISAMPLED = Pattern.compile("(texture2d_ms(?:_array)?<\\w+, access::)(?:read_)?write(> (\\w+))");

    /** Metal can't write multisampled textures. With GL_MAX_IMAGE_SAMPLES at zero no image can be bound to such a uniform, and GL ignores writes to an unbound image, so the writes are dropped. */
    private static String multisampledImages(String source) {
        final Matcher image = WRITTEN_MULTISAMPLED.matcher(source);
        if (!image.find()) return source;
        String out = source;
        do {
            out = out.replaceAll("\\b" + image.group(3) + "\\.write\\([^;]*;", ";");
        } while (image.find());
        return WRITTEN_MULTISAMPLED.matcher(out).replaceAll("$1read$2");
    }

    private static final String OUTPUT_STRUCT = "struct main0_out\n{\n";

    /** Metal has no sample mask state, so the fragment writes the mask, ANDed into any mask the shader writes itself. */
    private static String withCoverage(String source, int mask) {
        final String word = Integer.toUnsignedString(mask) + "u";
        final Matcher written = WRITTEN_MASK.matcher(source);
        if (written.find()) return source.replace("return out;", "out." + written.group(1) + " &= " + word + ";\n    return out;");
        if (!source.contains(OUTPUT_STRUCT)) return source;
        return source.replace(OUTPUT_STRUCT, OUTPUT_STRUCT + "    uint coverageMask [[sample_mask]];\n")
            .replace("return out;", "out.coverageMask = " + word + ";\n    return out;");
    }

    public static Output compile(int[] words, int glType, List<Binding> bindings, int sizeBufferIndex, int mslVersion) {
        return compile(words, glType, bindings, sizeBufferIndex, mslVersion, true, false);
    }

    public static Output compile(int[] words, int glType, List<Binding> bindings, int sizeBufferIndex, int mslVersion, boolean fragDepth, boolean noRaster) {
        return compile(words, glType, bindings, sizeBufferIndex, mslVersion, fragDepth, noRaster, -1);
    }

    private static boolean writesLayer(int[] words) {
        for (int i = 5; i < words.length && (words[i] >>> 16) > 0; i += words[i] >>> 16) {
            final int op = words[i] & 0xFFFF;
            if (op == 71 && words[i + 2] == 11 && words[i + 3] == 9) return true;
            if (op == 72 && words[i + 3] == 11 && words[i + 4] == 9) return true;
        }
        return false;
    }

    /** A coverageMask other than -1 is written by the fragment shader (see withCoverage). */
    public static Output compile(int[] words, int glType, List<Binding> bindings, int sizeBufferIndex, int mslVersion, boolean fragDepth, boolean noRaster, int coverageMask) {
        final int model = SpvcSupport.executionModel(glType);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final long ctx = SpvcSupport.createContext(stack);
            try {
                final long compiler = SpvcSupport.createCompiler(ctx, Spvc.SPVC_BACKEND_MSL, words, stack);

                final PointerBuffer pOpts = stack.mallocPointer(1);
                SpvcSupport.check(ctx, Spvc.spvc_compiler_create_compiler_options(compiler, pOpts), "create_compiler_options");
                final long opts = pOpts.get(0);
                Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, mslVersion);
                Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_1D_AS_2D, true);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
                // A vertex shader writing gl_Layer needs a declared topology class, and Metal only accepts points for one writing a point size, so it gets none.
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, glType != GL20.GL_VERTEX_SHADER || !writesLayer(words));
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_FRAG_DEPTH_BUILTIN, fragDepth);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_FRAG_STENCIL_REF_BUILTIN, true);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_CLIP_DISTANCE_USER_VARYING, true);
                Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, false);
                // Metal's framebuffer origin is top-left, so the flip keeps row 0 as GL's bottom row; the present blit flips it back.
                if (glType == GL20.GL_VERTEX_SHADER && !noRaster) Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
                if (noRaster) Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_DISABLE_RASTERIZATION, true);
                if (sizeBufferIndex >= 0) Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_MSL_BUFFER_SIZE_BUFFER_INDEX, sizeBufferIndex);
                SpvcSupport.check(ctx, Spvc.spvc_compiler_install_compiler_options(compiler, opts), "install_compiler_options");

                for (Binding b : bindings) {
                    final SpvcMslResourceBinding2 r = SpvcMslResourceBinding2.calloc(stack);
                    Spvc.spvc_msl_resource_binding_init_2(r);
                    r.stage(model).desc_set(0).binding(b.binding()).count(Math.max(1, b.count()));
                    if (b.buffer() >= 0) r.msl_buffer(b.buffer());
                    if (b.texture() >= 0) r.msl_texture(b.texture());
                    if (b.sampler() >= 0) r.msl_sampler(b.sampler());
                    SpvcSupport.check(ctx, Spvc.spvc_compiler_msl_add_resource_binding_2(compiler, r), "add_resource_binding");
                }

                final PointerBuffer pSource = stack.mallocPointer(1);
                SpvcSupport.check(ctx, Spvc.spvc_compiler_compile(compiler, pSource), "compile");
                final String translated = memUTF8(pSource.get(0));
                // With rasterization off the translator leaves the position out of the output but not out of the code that writes it.
                final String positioned = noRaster && translated.contains("out.gl_Position") && !translated.contains("[[position")
                    ? translated.replace("struct main0_out\n{\n", "struct main0_out\n{\n    float4 gl_Position [[position]];\n")
                    : translated;
                // Metal's own functions take no volatile reference, and its buffers are as coherent as GL asks without one.
                final String source = multisampledImages(atomicReads(positioned.replaceAll("volatile (const )?device (?!atomic)", "$1device ")));
                final String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model);
                return new Output(coverageMask == -1 ? source : withCoverage(source, coverageMask), entry == null ? "main0" : entry, Spvc.spvc_compiler_msl_needs_buffer_size_buffer(compiler));
            } finally {
                Spvc.spvc_context_destroy(ctx);
            }
        }
    }

    private static final Pattern NAN_INF_CALL = Pattern.compile("(?<![\\w:])(isnan|isinf)\\(");
    private static final String NAN_INF_HELPERS = """
        inline bool angelica_isnan(float x) { return (as_type<uint>(x) & 0x7fffffffu) > 0x7f800000u; }
        inline bool2 angelica_isnan(float2 x) { return (as_type<uint2>(x) & 0x7fffffffu) > 0x7f800000u; }
        inline bool3 angelica_isnan(float3 x) { return (as_type<uint3>(x) & 0x7fffffffu) > 0x7f800000u; }
        inline bool4 angelica_isnan(float4 x) { return (as_type<uint4>(x) & 0x7fffffffu) > 0x7f800000u; }
        inline bool angelica_isinf(float x) { return (as_type<uint>(x) & 0x7fffffffu) == 0x7f800000u; }
        inline bool2 angelica_isinf(float2 x) { return (as_type<uint2>(x) & 0x7fffffffu) == 0x7f800000u; }
        inline bool3 angelica_isinf(float3 x) { return (as_type<uint3>(x) & 0x7fffffffu) == 0x7f800000u; }
        inline bool4 angelica_isinf(float4 x) { return (as_type<uint4>(x) & 0x7fffffffu) == 0x7f800000u; }
        """;

    public static String bitTestNanInf(String msl) {
        if (!msl.contains("isnan(") && !msl.contains("isinf(")) return msl;
        final String rewritten = NAN_INF_CALL.matcher(msl).replaceAll("angelica_$1(");
        final int using = rewritten.indexOf("using namespace metal;");
        if (using < 0) return msl;
        final int at = using + "using namespace metal;".length();
        return rewritten.substring(0, at) + "\n" + NAN_INF_HELPERS + rewritten.substring(at);
    }

    private static final String[] PRECISE_MARKERS = {"isunordered(", "isordered(", "precise::", "fma("};

    static boolean needsPreciseMath(String msl) {
        for (String marker : PRECISE_MARKERS) if (msl.contains(marker)) return true;
        return false;
    }
}
