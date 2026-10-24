package com.gtnewhorizons.angelica.vulkan.shader;

import com.gtnewhorizons.angelica.glsm.shader.GlslVulkanPreprocess;
import com.gtnewhorizons.angelica.glsm.shader.ShaderCacheIO;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memUTF8;

public final class ShaderCompiler {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    public static final int AUTO_BINDING_BASE = 4096;

    private static final ThreadLocal<Long> COMPILER = ThreadLocal.withInitial(() -> {
        final long c = Shaderc.shaderc_compiler_initialize();
        if (c == 0L) throw new IllegalStateException("shaderc_compiler_initialize failed");
        return c;
    });

    private static final int CACHE_MAX = 512;
    private static final Object2ObjectLinkedOpenHashMap<CacheKey, Compiled> CACHE = new Object2ObjectLinkedOpenHashMap<>();

    private record CacheKey(String source, int glType, int depthFixup) {}

    public record Compiled(byte[] spirv, Set<String> boolUniforms, Set<String> explicitInputs, java.util.Map<String, Integer> bindings) {}

    public record Result(@Nullable Compiled compiled, String log) {}

    public static final int DEPTH_NONE = 0, DEPTH_AT_END_OF_MAIN = 1, DEPTH_AT_EMIT = 2;

    private ShaderCompiler() {}

    public static Result compile(String source, int glType, String debugName, int depthFixup) {
        final String clean = source.indexOf('\0') >= 0 ? source.replace("\0", "") : source;
        final CacheKey key = new CacheKey(clean, glType, depthFixup);
        synchronized (CACHE) {
            final Compiled hit = CACHE.getAndMoveToFirst(key);
            if (hit != null) return new Result(hit, "");
        }

        final GlslVulkanPreprocess.Result pre = GlslVulkanPreprocess.run(clean, glType, debugName, false);
        String text = pre != null ? pre.rewrittenSource() : clean;
        text = applyDepthFixup(text, depthFixup);
        text = applyPerVertexRedeclaration(text, glType);

        final ShaderDiskCache.Key diskKey = ShaderDiskCache.isEnabled() ? ShaderDiskCache.key("vkspirv").str(toolchainId()).str(text).i(glType) : null;
        byte[] spirv = diskKey != null ? ShaderDiskCache.get(diskKey) : null;
        String log = "";
        if (spirv == null) {
            final String[] err = new String[1];
            spirv = compileGlsl(text, glType, debugName, err);
            if (spirv == null) {
                return new Result(null, err[0] == null ? "shaderc failed" : err[0]);
            }
            spirv = Spirv.zeroInitLocals(spirv);
            if (diskKey != null) ShaderDiskCache.put(diskKey, spirv);
        }
        final Compiled out = new Compiled(spirv, pre != null ? pre.boolUniforms() : Collections.emptySet(), pre != null ? pre.explicitVsInputs() : Collections.emptySet(), explicitBindings(text));
        synchronized (CACHE) {
            CACHE.putAndMoveToFirst(key, out);
            while (CACHE.size() > CACHE_MAX) CACHE.removeLast();
        }
        return new Result(out, log);
    }

    private static final class Toolchain {
        static final String ID = ShaderCacheIO.libraryId(Shaderc.getLibrary()) + "/vk3";
    }

    private static String toolchainId() {
        return Toolchain.ID;
    }

    private static final Pattern LAYOUT_DECL = Pattern.compile("layout\\s*\\(([^)]*)\\)\\s*((?:\\w+\\s+)*?)(uniform|buffer)\\s+(\\w+)\\s*(\\{|\\w+)");
    private static final Pattern BINDING = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)");

    static java.util.Map<String, Integer> explicitBindings(String src) {
        final java.util.Map<String, Integer> out = new java.util.HashMap<>();
        final Matcher m = LAYOUT_DECL.matcher(src);
        while (m.find()) {
            final Matcher b = BINDING.matcher(m.group(1));
            if (!b.find()) continue;
            final String name = "{".equals(m.group(5)) ? m.group(4) : m.group(5);
            out.put(name, Integer.parseInt(b.group(1)));
        }
        return out;
    }

    private static final Pattern MAIN_DECL = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern VERSION = Pattern.compile("^[ \\t]*#[ \\t]*version[^\\n]*\\n?", Pattern.MULTILINE);

    static String applyDepthFixup(String src, int mode) {
        if (mode == DEPTH_NONE) return src;
        final int insertAt = headerEnd(src);
        final String head = src.substring(0, insertAt);
        final String body = src.substring(insertAt);
        if (mode == DEPTH_AT_END_OF_MAIN) {
            final Matcher m = MAIN_DECL.matcher(body);
            if (!m.find()) return src;
            final String renamed = body.substring(0, m.start()) + "void angelica_vk_main(" + body.substring(m.end());
            return head + renamed
                + "\nvoid main() {\n    angelica_vk_main();\n    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
        }
        return head + "void angelica_vk_emit() {\n    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n    EmitVertex();\n}\n#define EmitVertex() angelica_vk_emit()\n" + body;
    }

    static String applyPerVertexRedeclaration(String src, int glType) {
        if (glType != GL20.GL_VERTEX_SHADER || !src.contains("gl_ClipDistance") || src.contains("gl_CullDistance") || src.contains("gl_PerVertex")) return src;
        final int at = headerEnd(src);
        return src.substring(0, at)
            + "out gl_PerVertex { vec4 gl_Position; float gl_PointSize; float gl_ClipDistance[gl_MaxClipDistances]; };\n"
            + src.substring(at);
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

    private static byte[] compileGlsl(String text, int glType, String debugName, String[] error) {
        final long compiler = COMPILER.get();
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
