package com.gtnewhorizons.angelica.glemu.shader;

import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import com.gtnewhorizons.angelica.glsm.shader.GlslVulkanPreprocess;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glsm.shader.ShaderCacheIO;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import com.gtnewhorizons.angelica.glsm.shader.ShaderIndex;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.taumc.glsl.grammar.GLSLParser;

import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memUTF8;

public final class ShaderCompiler {
    private static final Logger LOG = LogManager.getLogger("Angelica/GPU");

    public static final int AUTO_BINDING_BASE = 4096;

    /** Compiling only reads the compiler, so one instance serves every thread for the life of the game. */
    private static final class Compiler {
        static final long HANDLE = Shaderc.shaderc_compiler_initialize();
    }

    private static final int CACHE_MAX = 512;
    private static final Object2ObjectLinkedOpenHashMap<CacheKey, Compiled> CACHE = new Object2ObjectLinkedOpenHashMap<>();
    /** Results by the source as given; guarded by {@link #CACHE}. */
    private static final Object2ObjectLinkedOpenHashMap<RawKey, Compiled> RAW_CACHE = new Object2ObjectLinkedOpenHashMap<>();

    private record CacheKey(String source, int glType, int depthFixup) {}

    /** A source as given, with the device switches that change how it is rewritten. */
    private record RawKey(String source, int glType, int depthFixup, int switches, String limits) {}

    public record Compiled(byte[] spirv, Set<String> boolUniforms, Set<String> explicitInputs, Map<String, Integer> bindings, Map<String, Integer> explicitVaryings, Map<String, Integer> explicitUniforms) {}

    public record Result(@Nullable Compiled compiled, String log) {}

    public static final int DEPTH_NONE = 0, DEPTH_AT_END_OF_MAIN = 1, DEPTH_AT_EMIT = 2;

    private ShaderCompiler() {}

    /** The offset of the sample being shaded from the pixel's middle is that sample, where a device interpolating an offset may land a rounding error away. */
    private static final Pattern OFFSET_TO_SAMPLE = Pattern.compile("\\binterpolateAtOffset\\s*\\(\\s*([\\w.\\[\\]]+)\\s*,\\s*gl_SamplePosition\\s*-\\s*(?:vec2\\s*\\(\\s*)?0\\.5\\s*\\)?\\s*\\)");

    public static String lowerLanguage(String source, int glType) {
        final String lowered = OFFSET_TO_SAMPLE.matcher(lowerLanguageParts(source, glType)).replaceAll("interpolateAtSample($1, gl_SampleID)");
        final String biased = lodBiasInShader ? LodBiasLowering.lower(lowered, glType == GL20.GL_FRAGMENT_SHADER) : lowered;
        final String atomics = atomicLoadsInShader ? withAtomicLoads(biased) : biased;
        return cubeImagesAsArrays ? withGatherOffsetsMoved(withCubeImagesAsArrays(atomics)) : atomics;
    }

    /** On MoltenVK a plain load need not see an atomic on the same image, even from the thread that made it. */
    public static volatile boolean atomicLoadsInShader;
    private static final Pattern ATOMIC_IMAGE = Pattern.compile("\\bimageAtomic\\w+\\s*\\(\\s*(\\w+)");

    /** Each imageLoad of an image that takes atomics becomes an atomic add of zero, which cannot miss an earlier atomic. */
    static String withAtomicLoads(String source) {
        final Matcher atomic = ATOMIC_IMAGE.matcher(source);
        final java.util.Set<String> images = new java.util.HashSet<>();
        while (atomic.find()) images.add(atomic.group(1));
        String out = source;
        for (String image : images) {
            final Matcher declared = Pattern.compile("\\b([iu])image\\w+\\s+" + image + "\\b").matcher(out);
            if (!declared.find()) continue;
            final boolean unsigned = declared.group(1).equals("u");
            final String vector = unsigned ? "uvec4(" : "ivec4(", zero = unsigned ? "0u" : "0", one = unsigned ? "1u" : "1";
            final Pattern load = Pattern.compile("\\bimageLoad\\s*\\(\\s*" + image + "\\s*,");
            for (int from = 0; ; ) {
                final Matcher at = load.matcher(out);
                if (!at.find(from)) break;
                int depth = 1, close = at.end();
                while (close < out.length() && depth > 0) {
                    final char c = out.charAt(close++);
                    if (c == '(') depth++;
                    else if (c == ')') depth--;
                }
                final String replaced = vector + "imageAtomicAdd(" + image + ", " + out.substring(at.end(), close - 1).trim() + ", " + zero + "), " + zero + ", " + zero + ", " + one + ")";
                out = out.substring(0, at.start()) + replaced + out.substring(close);
                from = at.start() + replaced.length();
            }
        }
        return out;
    }

    /** Apple's compilers refuse atomics on cube images but not on arrays of the same faces, which an image's coordinates already address. */
    public static volatile boolean cubeImagesAsArrays;
    /** Metal's compiler has no arrays of matrices between stages, which MatrixArrayLowering turns into arrays of columns. */
    public static volatile boolean matrixArraysFlattened;
    /** Whether a device's gather reads past the edge of a clamped image as zero. */
    public static volatile boolean gatherEdgesInShader;
    /** Whether a device's samplers leave a texture's bias out of explicit levels and clamp it before a shader's is added. */
    public static volatile boolean lodBiasInShader;
    private static final Pattern CUBE_IMAGE = Pattern.compile("\\b([iu]?)image(Cube(?:Array)?)\\b(\\s+(\\w+))?");

    private static final Pattern GATHER_OFFSET = Pattern.compile("\\btextureGatherOffset\\s*\\(");
    private static final Pattern PLAIN_SAMPLER_2D = Pattern.compile("\\buniform\\s+(?:\\w+\\s+)*?[iu]?sampler2D\\s+(\\w+)\\s*;");

    /** Apple's offsets don't clamp each texel as GL says, but a gather from coordinates moved that far does. */
    static String withGatherOffsetsMoved(String source) {
        if (!source.contains("textureGatherOffset(") && !GATHER_OFFSET.matcher(source).find()) return source;
        final java.util.Set<String> samplers = new java.util.HashSet<>();
        final Matcher declared = PLAIN_SAMPLER_2D.matcher(source);
        while (declared.find()) samplers.add(declared.group(1));
        final StringBuilder out = new StringBuilder();
        final Matcher call = GATHER_OFFSET.matcher(source);
        int copied = 0;
        while (call.find()) {
            int depth = 1, at = call.end(), start = call.end();
            final java.util.List<String> arguments = new java.util.ArrayList<>();
            while (at < source.length() && depth > 0) {
                final char c = source.charAt(at);
                if (c == '(' || c == '[') depth++;
                else if (c == ')' || c == ']') depth--;
                if (depth == 0 || c == ',' && depth == 1) {
                    arguments.add(source.substring(start, at).trim());
                    start = at + 1;
                }
                at++;
            }
            if (arguments.size() < 3 || !samplers.contains(arguments.get(0))) continue;
            final String sampler = arguments.get(0);
            out.append(source, copied, call.start()).append("textureGather(").append(sampler).append(", (").append(arguments.get(1))
                .append(") + vec2(").append(arguments.get(2)).append(") / vec2(textureSize(").append(sampler).append(", 0))");
            if (arguments.size() > 3) out.append(", ").append(arguments.get(3));
            out.append(')');
            copied = at;
        }
        return out.append(source, copied, source.length()).toString();
    }

    static String withCubeImagesAsArrays(String source) {
        if (!source.contains("imageCube") && !source.contains("ImageCube") && !source.contains("imageCubeArray")) return source;
        final Matcher type = CUBE_IMAGE.matcher(source);
        final java.util.Map<String, Boolean> names = new java.util.HashMap<>();
        while (type.find()) {
            if (type.group(4) != null) names.put(type.group(4), type.group(2).equals("CubeArray"));
        }
        String out = CUBE_IMAGE.matcher(source).replaceAll("$1image2DArray$3");
        for (Map.Entry<String, Boolean> name : names.entrySet()) {
            final Pattern size = Pattern.compile("\\bimageSize\\s*\\(\\s*(" + Pattern.quote(name.getKey()) + "\\s*(?:\\[[^\\]]*\\])?)\\s*\\)");
            out = size.matcher(out).replaceAll(name.getValue() ? "ivec3(imageSize($1).xy, imageSize($1).z / 6)" : "imageSize($1).xy");
        }
        return out;
    }

    /** The cube image types a source gives its uniforms, by name, which the shader itself holds as arrays. */
    static java.util.Map<String, Integer> declaredCubeImages(String source) {
        final java.util.Map<String, Integer> out = new java.util.HashMap<>();
        final Matcher type = CUBE_IMAGE.matcher(source);
        while (type.find()) {
            if (type.group(4) == null) continue;
            final boolean array = type.group(2).equals("CubeArray");
            out.put(type.group(4), switch (type.group(1)) {
                case "i" -> array ? org.lwjgl.opengl.GL42.GL_INT_IMAGE_CUBE_MAP_ARRAY : org.lwjgl.opengl.GL42.GL_INT_IMAGE_CUBE;
                case "u" -> array ? org.lwjgl.opengl.GL42.GL_UNSIGNED_INT_IMAGE_CUBE_MAP_ARRAY : org.lwjgl.opengl.GL42.GL_UNSIGNED_INT_IMAGE_CUBE;
                default -> array ? org.lwjgl.opengl.GL42.GL_IMAGE_CUBE_MAP_ARRAY : org.lwjgl.opengl.GL42.GL_IMAGE_CUBE;
            });
        }
        return out;
    }

    private static String lowerLanguageParts(String source, int glType) {
        return withSizedArrays(withCounterOffsets(GlslIncludes.lower(GlslSubroutines.lower(GlslIncludes.expand(source.indexOf('\0') >= 0 ? source.replace("\0", "") : source), glType))));
    }

    private static final Pattern UNSIZED_INITIALIZED = Pattern.compile("\\buniform\\s+([^;=\\[\\]]*?\\s\\w+)\\s*\\[\\s*\\]\\s*=\\s*([^;]*);");
    private static final Pattern BLOCK_BODY = Pattern.compile("\\b(uniform|buffer)(\\s+\\w+\\s*\\{)([^}]*)\\}");
    private static final Pattern UNSIZED_MEMBER = Pattern.compile("(\\w+)\\s*\\[\\s*\\]\\s*$");

    /** The compiler takes the size of an unsized array from its use only after a block is laid out, and not at all from a uniform's initializer. */
    private static String withSizedArrays(String source) {
        if (!source.contains("[]")) return source;
        final String sizedUniforms = UNSIZED_INITIALIZED.matcher(source).replaceAll(found -> {
            final int elements = topLevelElements(found.group(2));
            if (elements == 0) return Matcher.quoteReplacement(found.group());
            return Matcher.quoteReplacement("uniform " + found.group(1) + "[" + elements + "] = " + found.group(2) + ";");
        });
        return BLOCK_BODY.matcher(sizedUniforms).replaceAll(block -> {
            final String[] members = block.group(3).split(";", -1);
            int last = members.length - 1;
            while (last > 0 && members[last].isBlank()) last--;
            final StringBuilder body = new StringBuilder();
            for (int i = 0; i < members.length; i++) {
                final Matcher unsized = UNSIZED_MEMBER.matcher(members[i]);
                final boolean implicit = unsized.find() && (i != last || block.group(1).equals("uniform"));
                if (implicit) {
                    int size = 0;
                    final Matcher use = Pattern.compile("\\b" + unsized.group(1) + "\\s*\\[\\s*(\\d+)\\s*\\]").matcher(sizedUniforms);
                    while (use.find()) size = Math.max(size, Integer.parseInt(use.group(1)) + 1);
                    body.append(members[i], 0, unsized.start()).append(unsized.group(1)).append('[').append(Math.max(1, size)).append(']');
                } else {
                    body.append(members[i]);
                }
                if (i < members.length - 1) body.append(';');
            }
            return Matcher.quoteReplacement(block.group(1) + block.group(2) + body + "}");
        });
    }

    /** How many values an initializer gives: what is between the outermost braces or a constructor's parentheses. */
    private static int topLevelElements(String initializer) {
        final String text = initializer.strip();
        final int open = text.startsWith("{") ? 0 : text.indexOf('(');
        if (open < 0) return 0;
        int depth = 0, elements = 1;
        for (int i = open + 1; i < text.length() - 1; i++) {
            final char c = text.charAt(i);
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) elements++;
        }
        return elements;
    }

    private static final Pattern COUNTER_DECLARATION = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)\\s*uniform\\s+atomic_uint\\s*([^;]*);");
    private static final Pattern COUNTER_BINDING = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)\\s*(?:,|$)"), COUNTER_OFFSET = Pattern.compile("\\boffset\\s*=\\s*(\\d+)");
    private static final Pattern UNFLAT_DOUBLE_INPUT = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?!flat)\\w+\\s+)*?in\\s+(?:highp\\s+)?(?:double|dvec[234]|dmat[234](?:x[234])?)\\b");
    private static final Pattern DOUBLE_OUTPUT = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?out\\s+(?:double|dvec[234]|dmat[234](?:x[234])?)\\b");
    private static final Pattern NAMED_COUNTER = Pattern.compile("\\buniform\\s+atomic_uint\\s+\\w");

    private static boolean counterWithoutBinding(String code) {
        final Matcher counter = NAMED_COUNTER.matcher(code);
        while (counter.find()) {
            final String before = code.substring(0, counter.start()).stripTrailing();
            if (!before.endsWith(")") || !before.substring(Math.max(0, before.lastIndexOf("layout"))).contains("binding")) return true;
        }
        return false;
    }
    private static final Pattern COUNTER_ARRAY = Pattern.compile("\\[\\s*(\\d+)\\s*\\]");

    /** Where a counter sits can follow from a declaration before it, which the compiler does not carry over, so each is told. */
    private static String withCounterOffsets(String source) {
        if (!source.contains("atomic_uint")) return source;
        final Map<Integer, Integer> next = new HashMap<>();
        return COUNTER_DECLARATION.matcher(source).replaceAll(found -> {
            final Matcher binding = COUNTER_BINDING.matcher(found.group(1));
            if (!binding.find() || found.group(1).contains("location")) return Matcher.quoteReplacement(found.group());
            final int unit = Integer.parseInt(binding.group(1));
            final Matcher offset = COUNTER_OFFSET.matcher(found.group(1));
            int at = offset.find() ? Integer.parseInt(offset.group(1)) : next.getOrDefault(unit, 0);
            if (found.group(2).isBlank()) {
                next.put(unit, at);
                return "";
            }
            final StringBuilder out = new StringBuilder();
            for (String declarator : found.group(2).split(",")) {
                out.append("layout(binding = ").append(unit).append(", offset = ").append(at).append(") uniform atomic_uint ").append(declarator.trim()).append("; ");
                final Matcher size = COUNTER_ARRAY.matcher(declarator);
                at += 4 * (size.find() ? Integer.parseInt(size.group(1)) : 1);
            }
            next.put(unit, at);
            return Matcher.quoteReplacement(out.toString());
        });
    }

    /** Part of the version everything compiles at, some of which the compiler refuses by name. These need no directive to be used. */
    private static final String CORE_NAMES = "texture_query_levels|cull_distance|ES2_compatibility|ES3_compatibility|ES3_1_compatibility|get_program_binary|shader_precision|viewport_array|internalformat_query|internalformat_query2|map_buffer_alignment|arrays_of_arrays|fragment_layer_viewport|framebuffer_no_attachments|robust_buffer_access_behavior|shader_image_size|texture_storage_multisample|texture_multisample|enhanced_layouts|query_buffer_object|texture_mirror_clamp_to_edge|texture_stencil8|vertex_type_10f_11f_11f_rev|vertex_type_2_10_10_10_rev|conditional_render_inverted|texture_barrier|pipeline_statistics_query|transform_feedback_overflow_query|texture_buffer_object_rgb32|sample_shading|transform_feedback3|occlusion_query2|parallel_shader_compile|spirv_extensions|program_interface_query|multi_bind|multi_draw_indirect|clear_texture|clear_buffer_object|buffer_storage|copy_image|direct_state_access|invalidate_subdata|texture_view|texture_storage|vertex_attrib_binding|texture_buffer_range|stencil_texturing|get_texture_sub_image|clip_control|polygon_offset_clamp|texture_filter_anisotropic|indirect_parameters";
    private static final Pattern CORE_ONLY_DIRECTIVE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+GL_ARB_(?:" + CORE_NAMES + ")\\b.*$");
    private static final Pattern CORE_ONLY_NAME = Pattern.compile("\\bGL_ARB_(" + CORE_NAMES + ")\\b");

    private static String withCoreExtensions(String given) {
        // Part of the desktop language, so the directive has nothing to enable and the name it would define can't be defined: see below.
        final String source = given.contains("GL_OES_sample_variables")
            ? OES_SAMPLE_VARIABLES_DIRECTIVE.matcher(given).replaceAll("").replace("GL_OES_sample_variables", "1") : given;
        if (!source.contains("GL_ARB_") || !CORE_ONLY_NAME.matcher(source).find()) return source;
        // The compiler refuses to define a name starting with GL_, so what a shader tests is defined under another name.
        final String without = CORE_ONLY_DIRECTIVE.matcher(source).replaceAll("");
        final Set<String> named = new java.util.LinkedHashSet<>();
        final Matcher name = CORE_ONLY_NAME.matcher(without);
        final String renamed = name.replaceAll(found -> {
            named.add(found.group(1));
            return "ANGELICA_ARB_" + found.group(1);
        });
        final StringBuilder defines = new StringBuilder();
        for (String extension : named) defines.append("#define ANGELICA_ARB_").append(extension).append(" 1\n");
        final int at = headerEnd(renamed);
        return renamed.substring(0, at) + defines + renamed.substring(at);
    }

    private static final Pattern DECLARED_VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+(\\d+)");
    private static final java.util.Set<Integer> GLSL_VERSIONS = java.util.Set.of(100, 110, 120, 130, 140, 150, 300, 310, 320, 330, 400, 410, 420, 430, 440, 450, 460);
    private static final Pattern STRINGIFYING_MACRO = Pattern.compile("(?m)^[ \\t]*#[ \\t]*define[ \\t]+\\w+\\([^)\\n]*\\)[^\\n#]*(?<!#)#(?!#)");
    /** GLSL only has arrays of arrays from 4.30 or with their extension. */
    private static final Pattern ARRAY_OF_ARRAYS = Pattern.compile("\\b(?:bool|int|uint|float|double|[biud]?vec[234]|d?mat[234](?:x[234])?)\\s*(?:\\[[^\\]]*\\]\\s*(?:\\w+\\s*)?\\[|\\w+\\s*\\[[^\\]]*\\]\\s*\\[)");
    /** The compiler refuses a macro for defined() in a condition, which GL compilers take as what it stands for. */
    private static final Pattern DEFINED_MACRO = Pattern.compile("(?m)^[ \\t]*#[ \\t]*define[ \\t]+(\\w+)[ \\t]+(defined[ \\t]*(?:\\([ \\t]*\\w+[ \\t]*\\)|[ \\t]\\w+))[ \\t]*$");
    private static final Pattern CONDITION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*(?:el)?if\\b[^\\n]*$");
    private static final Pattern INPUT_BLOCK = Pattern.compile("\\bin\\s+\\w+\\s*\\{[^}]*\\}");
    private static final Pattern OPAQUE_BINDING = Pattern.compile("\\blayout\\s*\\([^)]*\\bbinding\\s*=\\s*(\\w+)[^)]*\\)\\s*(?:\\w+\\s+)*?uniform\\s+(?:\\w+\\s+)*?([iu]?(?:sampler|image)\\w*|atomic_uint)\\s+(\\w+)\\s*(?:\\[\\s*(\\d+)\\s*\\])?");
    private static final Pattern BLOCK_BINDING = Pattern.compile("\\blayout\\s*\\([^)]*\\bbinding\\s*=\\s*(\\w+)[^)]*\\)\\s*(uniform|buffer)\\s+(?!Angelica)\\w+\\s*\\{[^}]*\\}\\s*\\w*\\s*(?:\\[\\s*(\\d+)\\s*\\])?");
    private static final Pattern UNSIZED_ATOMIC = Pattern.compile("\\batomic_uint\\s+\\w+\\s*\\[\\s*\\]");
    private static final Pattern ATOMIC_OFFSET = Pattern.compile("\\boffset\\s*=\\s*(0[xX][0-9a-fA-F]+|\\d+)[^)]*\\)\\s*uniform\\s+atomic_uint");
    private static final int MAX_ATOMIC_COUNTER_BUFFER_SIZE = 16384, MAX_ATOMIC_COUNTER_BINDINGS = 8, MAX_STORAGE_BLOCK_BINDINGS = 16;
    private static final Pattern PER_VERTEX_BLOCK = Pattern.compile("\\bgl_PerVertex\\s*\\{[^}]*\\}[^;]*;\\s*");
    private static final Pattern DEFINED_VERSION = Pattern.compile("\\bdefined\\s*(?:\\(\\s*__VERSION__\\s*\\)|\\s__VERSION__)");
    private static final Pattern UNDEFINED_VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*undef[ \\t]+__VERSION__");
    /** Allowed on an input by the GLSL that test suites still hold compilers to, and without effect there. */
    private static final Pattern INVARIANT_DECLARATION = Pattern.compile("[^;{}]*\\binvariant\\b[^;{}]*"), INPUT_WORD = Pattern.compile("\\bin\\b"), INVARIANT_WORD = Pattern.compile("\\binvariant\\s+"),
        INVARIANT_WORD_ALONE = Pattern.compile("\\binvariant\\b");
    private static final Pattern LINE_DIRECTIVE = Pattern.compile("^[ \\t]*#[ \\t]*line[ \\t]+(\\d+)"), DEFINE_DIRECTIVE = Pattern.compile("^[ \\t]*#[ \\t]*define\\b");

    /** Joins what ends in a backslash with the next line, and keeps later lines at their numbers. */
    private static String joinedLines(String source) {
        if (!source.contains("\\\n") && !source.contains("\\\r\n")) return source;
        final StringBuilder out = new StringBuilder(source.length());
        int owed = 0;
        for (String line : source.split("\n", -1)) {
            final String text = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (text.endsWith("\\")) {
                out.append(text, 0, text.length() - 1);
                owed++;
                continue;
            }
            out.append(text).append('\n');
            for (; owed > 0; owed--) out.append('\n');
        }
        return out.toString();
    }

    /** Rewriting a shader moves its lines, so __LINE__ becomes the number it has in what the application gave. */
    private static String numberedLines(String source) {
        if (!source.contains("__LINE__")) return source;
        final StringBuilder out = new StringBuilder(source.length());
        int number = 1;
        for (String line : source.split("\n", -1)) {
            final Matcher directive = LINE_DIRECTIVE.matcher(line);
            final boolean defines = DEFINE_DIRECTIVE.matcher(line).find();
            out.append(defines ? line : line.replace("__LINE__", Integer.toString(number))).append('\n');
            number = directive.find() ? Integer.parseInt(directive.group(1)) : number + 1;
        }
        return out.toString();
    }

    /** Characters past the 16 bits of a Java char would shift offsets taken in code points. No GLSL token holds one, so another that is no token stands in. */
    private static String withoutSupplementaryCharacters(String source) {
        for (int i = 0; i < source.length(); i++) {
            if (Character.isSurrogate(source.charAt(i))) {
                final StringBuilder out = new StringBuilder(source.length());
                source.codePoints().forEach(c -> out.appendCodePoint(c >= 0x10000 ? '@' : c));
                return out.toString();
            }
        }
        return source;
    }

    private static final Pattern IMAGE_SIZE = Pattern.compile("\\bimageSize\\s*\\(");
    private static final Pattern INTEGER_MIX = Pattern.compile("\\bmix\\s*\\(\\s*[iub]vec[234]\\s*\\(");
    private static final Pattern NAMED_LOCATION = Pattern.compile("\\blayout\\s*\\([^)]*\\blocation\\s*=\\s*([A-Za-z_]\\w*)");
    private static final Pattern COUNTER_LOCATION = Pattern.compile("\\blayout\\s*\\([^)]*\\blocation\\b[^)]*\\)\\s*uniform\\s+atomic_uint\\b");
    private static final Pattern STRUCT_LAYOUT = Pattern.compile("\\bstruct\\s+\\w*\\s*\\{[^}]*\\b(?:layout\\s*\\(|(?:precise|coherent|volatile|restrict|readonly|writeonly)\\s+\\w)");

    /** A macro is a literal by the time a compiler reads it. */
    private static boolean hasNamedLocation(String code) {
        final Matcher location = NAMED_LOCATION.matcher(code);
        while (location.find()) {
            if (!Pattern.compile("#\\s*define\\s+" + location.group(1) + "\\b").matcher(code).find()) return true;
        }
        return false;
    }

    private static final Pattern ES_VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+\\d+[ \\t]+es\\b");
    private static final Pattern BUFFER_BLOCK_ARRAY = Pattern.compile("\\bbuffer\\s+\\w+\\s*\\{[^}]*\\}\\s*(\\w+)\\s*\\[");
    private static final Pattern OES_SAMPLE_VARIABLES_DIRECTIVE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+GL_OES_sample_variables[ \\t]*:[ \\t]*\\w+[ \\t]*$");

    /**
     * GLSL extensions the shader compiler knows but glemu does not offer, whose code would need device features left off. GLSL refuses a shader
     * that requires one and only warns of one it enables. GL_ARB_gpu_shader_int64 is not offered either but stays usable, as the double emulation uses it.
     */
    static final Set<String> UNOFFERED_EXTENSIONS = Set.of("GL_ARB_sparse_texture2", "GL_ARB_sparse_texture_clamp", "GL_ARB_shader_ballot",
        "GL_ARB_post_depth_coverage", "GL_KHR_blend_equation_advanced", "GL_EXT_texture_shadow_lod");
    private static final Pattern EXTENSION_DIRECTIVE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+(\\w+)[ \\t]*:[ \\t]*(\\w+)[^\\n]*");

    private static String unofferedRequired(String code) {
        final Matcher m = EXTENSION_DIRECTIVE.matcher(code);
        while (m.find()) {
            if (UNOFFERED_EXTENSIONS.contains(m.group(1)) && m.group(2).equals("require")) return "error: '#extension' : extension not supported: " + m.group(1) + "\n";
        }
        return null;
    }

    /** Drops an enable or warn of an extension glemu does not offer, so the compiler does not take its features. */
    private static String withoutUnofferedExtensions(String source) {
        if (!source.contains("#")) return source;
        final Matcher m = EXTENSION_DIRECTIVE.matcher(source);
        final StringBuilder out = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            final boolean drop = UNOFFERED_EXTENSIONS.contains(m.group(1));
            m.appendReplacement(out, drop ? "" : Matcher.quoteReplacement(m.group()));
            changed |= drop;
        }
        if (!changed) return source;
        m.appendTail(out);
        return out.toString();
    }

    private static String refusedByLanguage(String source, int glType) {
        final Matcher version = DECLARED_VERSION.matcher(source);
        if (version.find() && !GLSL_VERSIONS.contains(Integer.parseInt(version.group(1)))) return "error: version " + version.group(1) + " is not a GLSL version\n";
        final int declared = version.reset().find() ? Integer.parseInt(version.group(1)) : 110;
        final String code = COMMENT.matcher(source).replaceAll("");
        final String unoffered = unofferedRequired(code);
        if (unoffered != null) return unoffered;
        if (ES_VERSION.matcher(source).find()) {
            final Matcher array = BUFFER_BLOCK_ARRAY.matcher(code);
            while (array.find()) {
                final Matcher use = Pattern.compile("\\b" + array.group(1) + "\\s*\\[\\s*([^\\]]*)\\]").matcher(code.substring(array.end()));
                while (use.find()) {
                    if (!use.group(1).trim().matches("\\d+u?")) return "error: this language version cannot index an array of storage blocks with anything but a constant\n";
                }
            }
        }
        if (STRINGIFYING_MACRO.matcher(code).find()) return "error: '#' : GLSL has no stringizing operator\n";
        final Matcher bound = OPAQUE_BINDING.matcher(code);
        while (bound.find()) {
            final int binding = constantValue(bound.group(1), code);
            final int elements = bound.group(4) == null ? 1 : Integer.parseInt(bound.group(4));
            final int limit = bound.group(2).contains("sampler") ? GlState.MAX_UNITS : bound.group(2).equals("atomic_uint") ? MAX_ATOMIC_COUNTER_BINDINGS : GlState.MAX_IMAGE_UNITS;
            if (binding + elements > limit) return "error: '" + bound.group(3) + "' : binding is over the limit of its kind\n";
        }
        final Matcher block = BLOCK_BINDING.matcher(code);
        while (block.find()) {
            final int elements = block.group(3) == null ? 1 : Integer.parseInt(block.group(3));
            final int limit = block.group(2).equals("buffer") ? MAX_STORAGE_BLOCK_BINDINGS : GlState.MAX_BUFFER_BINDINGS;
            if (constantValue(block.group(1), code) + elements > limit) return "error: block binding is over the limit\n";
        }
        if (UNSIZED_ATOMIC.matcher(code).find()) return "error: an atomic counter array needs a size\n";
        final Matcher counter = ATOMIC_OFFSET.matcher(code);
        while (counter.find()) {
            if (Integer.decode(counter.group(1)) + Integer.BYTES > MAX_ATOMIC_COUNTER_BUFFER_SIZE) return "error: atomic counter offset over GL_MAX_ATOMIC_COUNTER_BUFFER_SIZE\n";
        }
        if (declared < 430 && !code.contains("GL_ARB_arrays_of_arrays") && ARRAY_OF_ARRAYS.matcher(code).find()) return "error: arrays of arrays need GLSL 4.30\n";
        if (!ES_VERSION.matcher(code).find()) {
            if (declared < 430 && glType == GL43.GL_COMPUTE_SHADER && !code.contains("GL_ARB_compute_shader")) return "error: compute shaders need GLSL 4.30\n";
            if (declared < 430 && !code.contains("GL_ARB_shader_image_size") && IMAGE_SIZE.matcher(code).find()) return "error: imageSize needs GLSL 4.30\n";
            if (declared < 450 && !code.contains("GL_EXT_shader_integer_mix") && INTEGER_MIX.matcher(code).find()) return "error: mix of integers or booleans needs GLSL 4.50\n";
            if (declared < 440 && !code.contains("GL_ARB_enhanced_layouts") && hasNamedLocation(code)) return "error: a location needs a literal before GLSL 4.40\n";
        }
        if (glType == GL20.GL_FRAGMENT_SHADER && UNFLAT_DOUBLE_INPUT.matcher(code).find()) return "error: a fragment shader's double inputs must be flat\n";
        if (glType == GL20.GL_FRAGMENT_SHADER && DOUBLE_OUTPUT.matcher(code).find()) return "error: a fragment shader cannot output doubles\n";
        if (COUNTER_LOCATION.matcher(code).find()) return "error: an atomic counter has no location\n";
        if (counterWithoutBinding(code)) return "error: an atomic counter needs a binding\n";
        if (STRUCT_LAYOUT.matcher(code).find()) return "error: a struct member takes no layout\n";
        return null;
    }

    /** What the language leaves to a compiler holding to the version declared, where everything here compiles at one version. */
    public static String withDeclaredLanguage(String source) {
        String text = numberedLines(joinedLines(withoutSupplementaryCharacters(source)));
        if (text.contains("__VERSION__") && !UNDEFINED_VERSION.matcher(text).find()) {
            final Matcher version = DECLARED_VERSION.matcher(text);
            final String declared = version.find() ? version.group(1) : "110";
            text = DEFINED_VERSION.matcher(text).replaceAll("1").replace("__VERSION__", declared);
        }
        if (text.contains("defined")) {
            final Matcher macro = DEFINED_MACRO.matcher(text);
            while (macro.find()) {
                final Pattern name = Pattern.compile("\\b" + macro.group(1) + "\\b");
                final String replacement = Matcher.quoteReplacement(macro.group(2));
                text = CONDITION.matcher(text).replaceAll(line -> Matcher.quoteReplacement(name.matcher(line.group()).replaceAll(replacement)));
            }
        }
        if (text.contains("invariant")) {
            text = INPUT_BLOCK.matcher(text).replaceAll(found -> Matcher.quoteReplacement(INVARIANT_WORD.matcher(found.group()).replaceAll("")));
            text = withoutInputInvariance(text);
        }
        return text;
    }

    /**
     * Drops {@code invariant} from each declaration of an input, a run of text between {@code ;}, <code>{</code> and <code>}</code>. Runs are found
     * from each occurrence of the word, which keeps this linear where {@link #INVARIANT_DECLARATION} alone backtracks over every long run.
     */
    static String withoutInputInvariance(String text) {
        StringBuilder out = null;
        int copied = 0, searchFrom = 0;
        while (true) {
            final int at = text.indexOf("invariant", searchFrom);
            if (at < 0) break;
            int start = at;
            while (start > 0 && !isDeclarationEnd(text.charAt(start - 1))) start--;
            int end = at;
            while (end < text.length() && !isDeclarationEnd(text.charAt(end))) end++;
            searchFrom = end;
            final String run = text.substring(start, end);
            if (!INVARIANT_WORD_ALONE.matcher(run).find() || !INPUT_WORD.matcher(run).find()) continue;
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, copied, start).append(INVARIANT_WORD.matcher(run).replaceAll(""));
            copied = end;
        }
        if (out == null) return text;
        return out.append(text, copied, text.length()).toString();
    }

    private static boolean isDeclarationEnd(char c) {
        return c == ';' || c == '{' || c == '}';
    }

    private static volatile int[] builtinLimits = new int[0];
    private static volatile String builtinLimitsId = "";

    /** What gl_Max* constants read, as pairs of a shaderc limit and its value, to agree with the queries of the same names. */
    public static void setBuiltinLimits(int[] limits) {
        builtinLimits = limits.clone();
        builtinLimitsId = java.util.Arrays.toString(limits);
    }

    public static Result compile(String source, int glType, String debugName, int depthFixup) {
        // The steps ahead of the cache key below are fixed text rewrites, so a source seen before skips them too.
        final int switches = (atomicLoadsInShader ? 1 : 0) | (cubeImagesAsArrays ? 2 : 0) | (matrixArraysFlattened ? 4 : 0)
            | (gatherEdgesInShader ? 8 : 0) | (lodBiasInShader ? 16 : 0);
        final RawKey rawKey = new RawKey(source, glType, depthFixup, switches, builtinLimitsId);
        synchronized (CACHE) {
            final Compiled hit = RAW_CACHE.getAndMoveToFirst(rawKey);
            if (hit != null) return new Result(hit, "");
        }
        // On disk too, so a later launch skips the parse and rewrites as well as the compile the SPIR-V layer saves.
        final ShaderDiskCache.Key diskKey = ShaderDiskCache.isEnabled()
            ? ShaderDiskCache.key("vkcompiled").str(toolchainId()).str(builtinLimitsId).i(switches).i(glType).i(depthFixup).str(source) : null;
        Compiled compiled = diskKey != null ? CompiledCodec.decode(ShaderDiskCache.get(diskKey)) : null;
        final Result result;
        if (compiled != null) {
            result = new Result(compiled, "");
        } else {
            result = compileUncached(source, glType, debugName, depthFixup);
            compiled = result.compiled();
            if (compiled != null && diskKey != null) ShaderDiskCache.put(diskKey, CompiledCodec.encode(compiled));
        }
        if (compiled != null) {
            synchronized (CACHE) {
                RAW_CACHE.putAndMoveToFirst(rawKey, compiled);
                while (RAW_CACHE.size() > CACHE_MAX) RAW_CACHE.removeLast();
            }
        }
        return result;
    }

    private static Result compileUncached(String source, int glType, String debugName, int depthFixup) {
        final String lowered = lowerLanguage(source, glType);
        final String refused = refusedByLanguage(lowered, glType);
        if (refused != null) return new Result(null, refused);
        final String clean = withDeclaredLanguage(withCoreExtensions(withoutUnofferedExtensions(lowered)));
        final CacheKey key = new CacheKey(clean, glType, depthFixup);
        synchronized (CACHE) {
            final Compiled hit = CACHE.getAndMoveToFirst(key);
            if (hit != null) return new Result(hit, "");
        }

        // The rewriting parses GLSL only, so what a directive decides has to be settled first.
        final String settled = settled(clean, glType);
        final GlslVulkanPreprocess.Result pre = GlslVulkanPreprocess.run(settled, glType, debugName, false);
        String text = pre != null ? pre.rewrittenSource() : settled;
        text = hoistDerivatives(text, glType);
        text = applyDepthFixup(text, depthFixup, glType);
        text = applyPerVertexRedeclaration(text, glType);
        text = applyClipMask(text, glType);
        text = applyPointCoordOrigin(text, glType);
        text = applyDrawParameters(text, glType);
        text = ignoreSampleMaskWithoutSamples(text);
        text = declareSampleCount(text);

        final ShaderDiskCache.Key diskKey = ShaderDiskCache.isEnabled() ? ShaderDiskCache.key("vkspirv").str(toolchainId()).str(text.contains("gl_Max") ? builtinLimitsId : "").str(text).i(glType) : null;
        byte[] spirv = diskKey != null ? ShaderDiskCache.get(diskKey) : null;
        if (spirv == null) {
            final String[] err = new String[1];
            spirv = compileGlsl(text, glType, debugName, err);
            if (spirv == null) {
                return new Result(null, err[0] == null ? "shaderc failed" : err[0]);
            }
            spirv = postProcess(spirv);
            if (diskKey != null) ShaderDiskCache.put(diskKey, spirv);
        }
        // After the disk cache, so what it already holds gets it too.
        if (glType == GL32.GL_GEOMETRY_SHADER) spirv = Spirv.holdGeometryOutputs(spirv);
        final Compiled out = new Compiled(spirv, withBoolMembers(text, pre != null ? pre.boolUniforms() : Collections.emptySet()), pre != null ? pre.explicitVsInputs() : Collections.emptySet(), explicitBindings(text),
            pre != null ? pre.explicitVaryings() : Map.of(), pre != null ? pre.explicitUniforms() : Map.of());
        synchronized (CACHE) {
            CACHE.putAndMoveToFirst(key, out);
            while (CACHE.size() > CACHE_MAX) CACHE.removeLast();
        }
        return new Result(out, "");
    }

    public static Result compilePreprocessed(String preprocessed, GlslVulkanPreprocess.Result pre, String debugName) {
        final String text = hoistDerivatives(preprocessed, GL20.GL_FRAGMENT_SHADER);
        final String[] err = new String[1];
        byte[] spirv = compileGlsl(text, GL20.GL_FRAGMENT_SHADER, debugName, err);
        if (spirv == null) return new Result(null, err[0] == null ? "shaderc failed" : err[0]);
        spirv = postProcess(spirv);
        return new Result(new Compiled(spirv, withBoolMembers(text, pre.boolUniforms()), Collections.emptySet(), explicitBindings(text), pre.explicitVaryings(), pre.explicitUniforms()), "");
    }

    private static final java.util.regex.Pattern MEMBER_BODY = java.util.regex.Pattern.compile("(?:uniform|struct)\\s+(\\w+)\\s*\\{([^}]*)\\}");
    private static final java.util.regex.Pattern BOOL_UNIFORM = java.util.regex.Pattern.compile("\\buniform\\s+(?:(?:highp|mediump|lowp)\\s+)?(?:bool|bvec[234])\\s+(\\w+)");
    private static final java.util.regex.Pattern BOOL_MEMBER = java.util.regex.Pattern.compile("\\b(?:bool|bvec[234])\\s+(\\w+)");

    /** SPIR-V has no bool in a block, so reflection can only learn which members were declared as one from the source. */
    private static Set<String> withBoolMembers(String text, Set<String> bools) {
        final Set<String> all = new java.util.HashSet<>(bools);
        final java.util.regex.Matcher global = BOOL_UNIFORM.matcher(text);
        while (global.find()) all.add(global.group(1));
        final java.util.regex.Matcher body = MEMBER_BODY.matcher(text);
        while (body.find()) {
            final java.util.regex.Matcher member = BOOL_MEMBER.matcher(body.group(2));
            while (member.find()) all.add(body.group(1) + "." + member.group(1));
        }
        return all;
    }

    private static byte[] postProcess(byte[] spirv) {
        return Spirv.zeroInitLocals(Spirv.stripUnusedOpaque(Spirv.lowerBufferImages(Spirv.lowerRectImages(Spirv.splitBlockArrays(Spirv.copyAtomicOperands(spirv))))));
    }

    private static final class Toolchain {
        static final String ID = ShaderCacheIO.libraryId(Shaderc.getLibrary()) + "/vk";
    }

    private static String toolchainId() {
        return Toolchain.ID;
    }

    private static final Pattern LAYOUT_DECL = Pattern.compile("layout\\s*\\(([^)]*)\\)\\s*((?:\\w+\\s+)*?)(uniform|buffer)\\s+(?:(?:highp|mediump|lowp|readonly|writeonly|coherent|volatile|restrict|precise)\\s+)*(\\w+)\\s*(\\{|\\w+)");
    private static final Pattern BINDING = Pattern.compile("\\bbinding\\s*=\\s*(\\w+)");

    /** A literal of any base, or a name the source defines as one. */
    private static int constantValue(String text, String src) {
        try {
            return Integer.decode(text.endsWith("u") || text.endsWith("U") ? text.substring(0, text.length() - 1) : text);
        } catch (NumberFormatException e) {
            final Matcher defined = Pattern.compile("#[ \\t]*define[ \\t]+" + text + "[ \\t]+(\\w+)|\\bconst\\s+u?int\\s+" + text + "\\s*=\\s*(\\w+)").matcher(src);
            if (!defined.find()) return -1;
            final String value = defined.group(1) != null ? defined.group(1) : defined.group(2);
            return value.equals(text) ? -1 : constantValue(value, src);
        }
    }

    static Map<String, Integer> explicitBindings(String src) {
        final Map<String, Integer> out = new HashMap<>();
        final Matcher m = LAYOUT_DECL.matcher(src);
        while (m.find()) {
            final Matcher b = BINDING.matcher(m.group(1));
            if (!b.find()) continue;
            final String name = "{".equals(m.group(5)) ? m.group(4) : m.group(5);
            final int binding = constantValue(b.group(1), src);
            if (binding >= 0) out.put(name, binding);
        }
        return out;
    }


    static String hoistDerivatives(String src, int glType) {
        if (glType != GL20.GL_FRAGMENT_SHADER || !src.contains("dFd") && !src.contains("fwidth")) return src;
        final GLSLParser.Translation_unitContext root;
        try {
            root = GlslTransformUtils.parseFullQuiet(src);
        } catch (Exception e) {
            return src;
        }
        final List<GlslVulkanPreprocess.Edit> edits = new ArrayList<>();
        DerivativeHoisting.collectEdits(new ShaderIndex(root, src), edits);
        return edits.isEmpty() ? src : GlslVulkanPreprocess.applyEdits(src, edits);
    }

    private static final Pattern MAIN_DECL = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern VERSION = Pattern.compile("^[ \\t]*#[ \\t]*version[^\\n]*\\n?", Pattern.MULTILINE);

    private static boolean takesPointSize(String code) {
        return !code.contains("gl_PerVertex") && !code.contains("gl_Layer");
    }

    private static final Pattern OUT_PER_VERTEX = Pattern.compile("\\bout\\s+gl_PerVertex\\s*\\{([^}]*)\\}");

    /** A shader that redeclares gl_PerVertex without gl_Position has no position to write. */
    private static boolean leavesOutPosition(String src) {
        final Matcher block = OUT_PER_VERTEX.matcher(src);
        return block.find() && !block.group(1).contains("gl_Position");
    }

    static String applyDepthFixup(String src, int mode, int glType) {
        if (leavesOutPosition(src)) return src;
        // GL clip z spans -1..1 while Vulkan and Metal use 0..1: remap after the user's main or at every EmitVertex.
        if (mode == DEPTH_NONE) return src;
        int insertAt = headerEnd(src);
        if (mode != DEPTH_AT_END_OF_MAIN) {
            // A redeclared gl_PerVertex has to come before the first use of gl_Position, which the code added here is.
            final Matcher redeclared = PER_VERTEX_BLOCK.matcher(src);
            while (redeclared.find()) insertAt = Math.max(insertAt, redeclared.end());
        }
        final String head = src.substring(0, insertAt);
        final String body = src.substring(insertAt);
        if (mode == DEPTH_AT_END_OF_MAIN) {
            final Matcher m = MAIN_DECL.matcher(body);
            if (!m.find()) return src;
            // Every one of them: a comment or a preprocessor branch can hold a main, and which is real is not known yet.
            final String renamed = m.replaceAll("void angelica_main(");
            // Nothing but a shader sets a point's size in Vulkan and Metal, so glPointSize is written first and a shader's own write wins.
            // Metal needs a declared topology class for gl_Layer but only allows points for a shader writing a size, so those two exclude each other.
            final boolean pointSize = glType == GL20.GL_VERTEX_SHADER && takesPointSize(COMMENT.matcher(src).replaceAll(""));
            return head + renamed
                + "\nuniform vec2 " + ProgramBase.CLIP_UNIFORM + ";"
                + (pointSize ? "\nuniform float " + ProgramBase.POINT_SIZE_UNIFORM + ";" : "")
                + "\nvoid main() {\n"
                + (pointSize ? "    gl_PointSize = max(" + ProgramBase.POINT_SIZE_UNIFORM + ", 1.0);\n" : "")
                + "    angelica_main();\n    gl_Position.y *= 1.0 - 2.0 * " + ProgramBase.CLIP_UNIFORM + ".x;\n    gl_Position.z = mix((gl_Position.z + gl_Position.w) * 0.5, gl_Position.z, " + ProgramBase.CLIP_UNIFORM + ".y);\n}\n";
        }
        return head + "uniform vec2 " + ProgramBase.CLIP_UNIFORM + ";\nvoid angelica_emit() {\n    gl_Position.y *= 1.0 - 2.0 * " + ProgramBase.CLIP_UNIFORM + ".x;\n    gl_Position.z = mix((gl_Position.z + gl_Position.w) * 0.5, gl_Position.z, " + ProgramBase.CLIP_UNIFORM + ".y);\n    EmitVertex();\n}\n#define EmitVertex() angelica_emit()\n" + body;
    }

    /** GLSL lets a shader size the array itself, which cannot stand beside the block this adds for it. */
    private static final Pattern CLIP_DECLARATION = Pattern.compile("(?:\\bout\\s+)?\\bfloat\\s+gl_ClipDistance\\s*\\[\\s*(?:\\d*|gl_MaxClipDistances)\\s*\\]\\s*;");

    static String applyPerVertexRedeclaration(String given, int glType) {
        if (glType != GL20.GL_VERTEX_SHADER || !given.contains("gl_ClipDistance") || given.contains("gl_CullDistance") || given.contains("gl_PerVertex")) return given;
        final String src = CLIP_DECLARATION.matcher(given).replaceAll("");
        final int at = headerEnd(src);
        return src.substring(0, at)
            + PER_VERTEX_REDECLARATION + clipDistanceCount(src) + "]; };\n"
            + src.substring(at);
    }

    private static final String PER_VERTEX_REDECLARATION = "out gl_PerVertex { vec4 gl_Position; float gl_PointSize; float gl_ClipDistance[";
    public static final int CLIP_MASK_SPEC_ID = 7731;
    private static final Pattern CLIP_SIZED = Pattern.compile("\\bfloat\\s+gl_ClipDistance\\s*\\[\\s*(\\d+|gl_MaxClipDistances)\\s*\\]");
    private static final Pattern CLIP_INDEX = Pattern.compile("\\bgl_ClipDistance\\s*\\[([^\\]]*)\\]");
    private static final Pattern INTEGER = Pattern.compile("\\s*(\\d+)\\s*");

    static int clipDistanceCount(String src) {
        final Matcher sized = CLIP_SIZED.matcher(src);
        if (sized.find()) return sized.group(1).equals("gl_MaxClipDistances") ? 8 : Math.min(8, Integer.parseInt(sized.group(1)));
        final Matcher m = CLIP_INDEX.matcher(src);
        int max = -1;
        while (m.find()) {
            final Matcher i = INTEGER.matcher(m.group(1));
            if (!i.matches()) return 8;
            max = Math.max(max, Integer.parseInt(i.group(1)));
        }
        return max < 0 ? 8 : Math.min(8, max + 1);
    }

    static String applyClipMask(String src, int glType) {
        if (glType != GL20.GL_VERTEX_SHADER && glType != GL32.GL_GEOMETRY_SHADER) return src;
        if (!src.contains("gl_ClipDistance") || src.contains("gl_PerVertex") && !src.contains(PER_VERTEX_REDECLARATION)) return src;
        final int redeclared = src.indexOf(PER_VERTEX_REDECLARATION);
        final int n = clipDistanceCount(redeclared < 0 ? src : src.substring(0, redeclared) + src.substring(src.indexOf('\n', redeclared) + 1));
        // Clip planes GL has disabled must not clip: a specialization constant mask forces their distances positive.
        final StringBuilder mask = new StringBuilder();
        for (int i = 0; i < n; i++) {
            mask.append("    if ((angelica_clipMask & ").append(1 << i).append(") == 0) gl_ClipDistance[").append(i).append("] = 1.0;\n");
        }
        final String decl = "layout(constant_id = " + CLIP_MASK_SPEC_ID + ") const int angelica_clipMask = 255;\n";
        if (glType == GL20.GL_VERTEX_SHADER) {
            final int at = headerEnd(src);
            final String head = src.substring(0, at);
            String body = src.substring(at);
            final int redecl = body.startsWith("out gl_PerVertex") ? body.indexOf('\n') + 1 : 0;
            final Matcher m = MAIN_DECL.matcher(body);
            if (!m.find()) return src;
            body = m.replaceAll("void angelica_clip_main(");
            return head + body.substring(0, redecl) + decl + body.substring(redecl)
                + "\nvoid main() {\n    angelica_clip_main();\n" + mask + "}\n";
        }
        final String depthEmit = "#define EmitVertex() angelica_emit()\n";
        final int depthAt = src.indexOf(depthEmit);
        final int at = depthAt >= 0 ? depthAt + depthEmit.length() : headerEnd(src);
        return src.substring(0, at) + decl
            + "void angelica_clip_emit() {\n" + mask + "    " + (depthAt >= 0 ? "angelica_emit()" : "EmitVertex()") + ";\n}\n"
            + (depthAt >= 0 ? "#undef EmitVertex\n" : "") + "#define EmitVertex() angelica_clip_emit()\n"
            + src.substring(at);
    }

    private static final Pattern DRAW_PARAMETER = Pattern.compile("\\bgl_(BaseVertex|BaseInstance|DrawID)(?:ARB)?\\b");

    /** Not every device has these, and one that has them counts a non-indexed draw's first vertex as its base. */
    static String applyDrawParameters(String src, int glType) {
        if (glType != GL20.GL_VERTEX_SHADER || !DRAW_PARAMETER.matcher(src).find()) return src;
        final int at = headerEnd(src);
        final String renamed = DRAW_PARAMETER.matcher(src.substring(at)).replaceAll(m -> switch (m.group(1)) {
            case "BaseVertex" -> ProgramBase.BASE_VERTEX_UNIFORM;
            case "BaseInstance" -> ProgramBase.BASE_INSTANCE_UNIFORM;
            default -> ProgramBase.DRAW_ID_UNIFORM;
        });
        return src.substring(0, at) + "uniform int " + ProgramBase.BASE_VERTEX_UNIFORM + ";\nuniform int " + ProgramBase.BASE_INSTANCE_UNIFORM + ";\nuniform int " + ProgramBase.DRAW_ID_UNIFORM + ";\n" + renamed;
    }

    /** Rendering is flipped to keep GL's orientation, which leaves the device's point coordinate counting from the other end to the one GL asked for. */
    static String applyPointCoordOrigin(String src, int glType) {
        if (glType != GL20.GL_FRAGMENT_SHADER || !src.contains("gl_PointCoord")) return src;
        final int at = headerEnd(src);
        return src.substring(0, at) + "uniform float " + ProgramBase.POINT_ORIGIN_UNIFORM + ";\n#define gl_PointCoord vec2(gl_PointCoord.x, abs(" + ProgramBase.POINT_ORIGIN_UNIFORM + " - gl_PointCoord.y))\n" + src.substring(at);
    }

    private static final Pattern LINK_ERROR = Pattern.compile("error: Linking ");

    /** A shader only has to make sense once linked: one on its own may have no main, or call what another shader of its stage defines. */
    public static boolean failsOnlyAtLink(String log) {
        if (log == null || !LINK_ERROR.matcher(log).find()) return false;
        for (String line : log.split("\n")) {
            if (line.contains("error:") && !LINK_ERROR.matcher(line).find()) return false;
        }
        return true;
    }

    private static final Pattern GLOBAL_STATEMENT = Pattern.compile("[^;{}]*;");

    /** Several shaders of one stage as the one this compiler takes: at the newest version declared, with what they share said once. */
    public static String joinUnits(List<String> sources) {
        int version = 0;
        final StringBuilder extensions = new StringBuilder(), body = new StringBuilder();
        final java.util.Set<String> said = new java.util.HashSet<>();
        for (String source : sources) {
            final Matcher declared = DECLARED_VERSION.matcher(source);
            if (declared.find()) version = Math.max(version, Integer.parseInt(declared.group(1)));
            final StringBuilder kept = new StringBuilder();
            for (String line : COMMENT.matcher(VERSION.matcher(source).replaceAll("")).replaceAll("").split("\n", -1)) {
                if (line.trim().startsWith("#extension")) {
                    if (said.add(line.trim())) extensions.append(line.trim()).append('\n');
                } else {
                    kept.append(line).append('\n');
                }
            }
            int depth = 0, from = 0;
            final String text = kept.toString();
            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                else if (c == ';' && depth == 0) {
                    final String statement = text.substring(from, i + 1);
                    if (statement.trim().startsWith("#") || said.add(statement.replaceAll("\\s+", " ").trim())) body.append(statement);
                    from = i + 1;
                }
                if (c == '}' && depth == 0) {
                    // A block's declaration runs on to its semicolon, and a function's body ends here.
                    final int next = text.indexOf(';', i);
                    final int brace = text.indexOf('{', i);
                    if (next >= 0 && (brace < 0 || next < brace) && text.substring(i + 1, next).trim().matches("\\w*(\\s*\\[[^\\]]*\\])?")) continue;
                    body.append(text, from, i + 1);
                    from = i + 1;
                }
            }
            body.append(text.substring(from));
        }
        return (version == 0 ? "" : "#version " + version + "\n") + extensions + body;
    }

    private static final Pattern WRITES_SAMPLE_MASK = Pattern.compile("\\bgl_SampleMask\\b");

    /** A framebuffer without samples takes no notice of gl_SampleMask, where the device would drop what a clear bit covers. */
    static String ignoreSampleMaskWithoutSamples(String src) {
        final Matcher main = MAIN_DECL.matcher(src);
        if (!WRITES_SAMPLE_MASK.matcher(src).find() || !main.find()) return src;
        int at = src.indexOf('{', main.end()), depth = 0;
        for (; at >= 0 && at < src.length(); at++) {
            if (src.charAt(at) == '{') depth++;
            else if (src.charAt(at) == '}' && --depth == 0) break;
        }
        if (at < 0 || at >= src.length()) return src;
        // At the end of main itself, as the device's translation only keeps a mask main wrote.
        return src.substring(0, at) + "    if (gl_NumSamples <= 1) gl_SampleMask[0] = -1;\n" + src.substring(at);
    }

    static String declareSampleCount(String src) {
        if (!src.contains("gl_NumSamples")) return src;
        final int at = headerEnd(src);
        return src.substring(0, at) + "uniform int " + ProgramBase.NUM_SAMPLES_UNIFORM + ";\n#define gl_NumSamples " + ProgramBase.NUM_SAMPLES_UNIFORM + "\n" + src.substring(at);
    }

    static int headerEnd(String src) {
        final Matcher v = VERSION.matcher(src);
        int end = v.find() ? v.end() : 0;
        while (end < src.length()) {
            int nl = src.indexOf('\n', end);
            if (nl < 0) nl = src.length() - 1;
            final String line = src.substring(end, nl + 1).trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#extension") || line.startsWith("#pragma")) {
                end = nl + 1;
                continue;
            }
            break;
        }
        return end;
    }

    public static int shadercKind(int glType) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> Shaderc.shaderc_vertex_shader;
            case GL20.GL_FRAGMENT_SHADER -> Shaderc.shaderc_fragment_shader;
            case GL32.GL_GEOMETRY_SHADER -> Shaderc.shaderc_geometry_shader;
            case GL40.GL_TESS_CONTROL_SHADER -> Shaderc.shaderc_tess_control_shader;
            case GL40.GL_TESS_EVALUATION_SHADER -> Shaderc.shaderc_tess_evaluation_shader;
            case GL43.GL_COMPUTE_SHADER -> Shaderc.shaderc_compute_shader;
            default -> throw new IllegalArgumentException("shader type 0x" + Integer.toHexString(glType));
        };
    }

    /** The source with what its directives decide already decided. */
    public static String settled(String source, int glType) {
        return DIRECTIVE.matcher(source).find() ? preprocessed(source, glType) : source;
    }

    private static final Pattern DIRECTIVE = Pattern.compile("(?m)^\\s*#\\s*(?:if|ifdef|ifndef|elif|define)\\b");

    /** The source as the compiler's preprocessor leaves it, for rewriting that has to see what is really there. The source itself if that fails. */
    public static String preprocessed(String text, int glType) {
        if (text.indexOf("#define") < 0 && text.indexOf("#if") < 0) return text;
        if (Compiler.HANDLE == 0L) return text;
        final long opts = Shaderc.shaderc_compile_options_initialize();
        if (opts == 0L) return text;
        ByteBuffer src = null, name = null, entry = null;
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_target_env(opts, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_1);
            Shaderc.shaderc_compile_options_set_source_language(opts, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_vulkan_rules_relaxed(opts, true);
            Shaderc.shaderc_compile_options_set_forced_version_profile(opts, 460, Shaderc.shaderc_profile_core);
            src = memUTF8(text, false);
            name = memUTF8("shader");
            entry = memUTF8("main");
            result = Shaderc.shaderc_compile_into_preprocessed_text(Compiler.HANDLE, src, shadercKind(glType), name, entry, opts);
            if (result == 0L || Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) return text;
            final ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            if (bytes == null) return text;
            return PREPROCESSOR_NOISE.matcher(memUTF8(bytes)).replaceAll("");
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            if (entry != null) memFree(entry);
            if (name != null) memFree(name);
            if (src != null) memFree(src);
            Shaderc.shaderc_compile_options_release(opts);
        }
    }

    private static final Pattern PREPROCESSOR_NOISE = Pattern.compile("(?m)^\\s*#\\s*(?:line\\b.*|extension\\s+GL_GOOGLE_\\w+.*)$");

    static byte[] compileLibrary(String text, String[] error) {
        return compileGlsl(text, GL43.GL_COMPUTE_SHADER, "doubles", error);
    }

    private static byte[] compileGlsl(String text, int glType, String debugName, String[] error) {
        final long compiler = Compiler.HANDLE;
        if (compiler == 0L) {
            error[0] = "shaderc_compiler_initialize failed";
            return null;
        }
        final long opts = Shaderc.shaderc_compile_options_initialize();
        if (opts == 0L) {
            error[0] = "shaderc_compile_options_initialize failed";
            return null;
        }
        ByteBuffer src = null, name = null, entry = null;
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_target_env(opts, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_1);
            Shaderc.shaderc_compile_options_set_source_language(opts, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_vulkan_rules_relaxed(opts, true);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(opts, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(opts, true);
            Shaderc.shaderc_compile_options_set_forced_version_profile(opts, 460, Shaderc.shaderc_profile_core);
            Shaderc.shaderc_compile_options_set_optimization_level(opts, Shaderc.shaderc_optimization_level_zero);
            final int[] limits = builtinLimits;
            for (int i = 0; i < limits.length; i += 2) Shaderc.shaderc_compile_options_set_limit(opts, limits[i], limits[i + 1]);
            for (int kind = Shaderc.shaderc_uniform_kind_image; kind <= Shaderc.shaderc_uniform_kind_unordered_access_view; kind++) {
                Shaderc.shaderc_compile_options_set_binding_base(opts, kind, AUTO_BINDING_BASE);
            }
            src = memUTF8(text, false);
            name = memUTF8(debugName == null ? "shader" : debugName);
            entry = memUTF8("main");
            result = Shaderc.shaderc_compile_into_spv(compiler, src, shadercKind(glType), name, entry, opts);
            if (result == 0L) {
                error[0] = "shaderc_compile_into_spv returned null";
                return null;
            }
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                final String msg = Shaderc.shaderc_result_get_error_message(result);
                error[0] = msg == null ? "(no error message)" : msg;
                LOG.debug("shaderc rejected '{}': {}", debugName, error[0]);
                return null;
            }
            final ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            if (bytes == null || bytes.remaining() == 0) {
                error[0] = "shaderc returned empty SPIR-V";
                return null;
            }
            final byte[] out = new byte[bytes.remaining()];
            bytes.get(out);
            return out;
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            if (entry != null) memFree(entry);
            if (name != null) memFree(name);
            if (src != null) memFree(src);
            Shaderc.shaderc_compile_options_release(opts);
        }
    }

    public static ByteBuffer toNative(byte[] spirv) {
        final ByteBuffer b = MemoryUtil.memAlloc(spirv.length);
        b.put(spirv).flip();
        return b;
    }
}
