package com.gtnewhorizons.angelica.d3d12.shader;

import com.gtnewhorizons.angelica.glsm.shader.ShaderCacheIO;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcHLSLResourceBinding;

import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memByteBufferNT1;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memUTF8;

public final class HlslCompiler {
    private HlslCompiler() {}

    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    public record Spaces(int srv, int cbv, int uav) {}

    public static final Spaces VERTEX = new Spaces(0, 1, 4), FRAGMENT = new Spaces(2, 3, 5), COMPUTE = new Spaces(0, 1, 2);

    public static Spaces spacesFor(int glType) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> VERTEX;
            case GL43.GL_COMPUTE_SHADER -> COMPUTE;
            default -> FRAGMENT;
        };
    }

    public record Binding(int binding, Program.BindingKind kind, int count, int buffer, int texture, int sampler, boolean uav) {}

    public static int executionModel(int glType) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> Spv.SpvExecutionModelVertex;
            case GL43.GL_COMPUTE_SHADER -> Spv.SpvExecutionModelGLCompute;
            default -> Spv.SpvExecutionModelFragment;
        };
    }

    private static String target(int glType) {
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> "vs_5_1";
            case GL43.GL_COMPUTE_SHADER -> "cs_5_1";
            default -> "ps_5_1";
        };
    }

    public static String toHlsl(int[] words, int glType, List<Binding> bindings) {
        final int model = executionModel(glType);
        final Spaces spaces = spacesFor(glType);
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
                check(ctx, Spvc.spvc_context_create_compiler(ctx, Spvc.SPVC_BACKEND_HLSL, pIr.get(0), Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pCompiler), "create_compiler");
                final long compiler = pCompiler.get(0);

                final PointerBuffer pOpts = stack.mallocPointer(1);
                check(ctx, Spvc.spvc_compiler_create_compiler_options(compiler, pOpts), "create_compiler_options");
                final long opts = pOpts.get(0);
                Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_HLSL_SHADER_MODEL, 51);
                Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_HLSL_POINT_COORD_COMPAT, 1);
                Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_HLSL_POINT_SIZE_COMPAT, 1);
                Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_HLSL_USE_ENTRY_POINT_NAME, 1);
                Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_HLSL_NONWRITABLE_UAV_TEXTURE_AS_SRV, 1);
                if (glType == GL20.GL_VERTEX_SHADER) Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
                check(ctx, Spvc.spvc_compiler_install_compiler_options(compiler, opts), "install_compiler_options");

                for (Binding b : bindings) {
                    final SpvcHLSLResourceBinding r = SpvcHLSLResourceBinding.calloc(stack);
                    Spvc.spvc_hlsl_resource_binding_init(r);
                    r.stage(model).desc_set(0).binding(b.binding());
                    switch (b.kind()) {
                        case DEFAULT_BLOCK, UNIFORM_BUFFER -> r.cbv().set(spaces.cbv(), b.buffer());
                        case STORAGE_BUFFER -> {
                            if (b.uav()) r.uav().set(spaces.uav(), b.buffer());
                            else r.srv().set(spaces.srv(), b.buffer());
                        }
                        case COMBINED_SAMPLER -> {
                            r.srv().set(spaces.srv(), b.texture());
                            r.sampler().set(spaces.srv(), b.sampler());
                        }
                        case SAMPLED_IMAGE -> r.srv().set(spaces.srv(), b.texture());
                        case SAMPLER -> r.sampler().set(spaces.srv(), b.sampler());
                        case STORAGE_IMAGE -> {
                            if (b.uav()) r.uav().set(spaces.uav(), b.texture());
                            else r.srv().set(spaces.srv(), b.texture());
                        }
                    }
                    check(ctx, Spvc.spvc_compiler_hlsl_add_resource_binding(compiler, r), "add_resource_binding");
                }

                final PointerBuffer pSource = stack.mallocPointer(1);
                check(ctx, Spvc.spvc_compiler_compile(compiler, pSource), "compile");
                return memUTF8(memByteBufferNT1(pSource.get(0)));
            } finally {
                Spvc.spvc_context_destroy(ctx);
            }
        }
    }

    private static final int FLAGS = D3DCompiler.ENABLE_STRICTNESS | D3DCompiler.PREFER_FLOW_CONTROL;
    private static final String FXC_VARIANT = "fxc-2";

    private static final class ToolchainId {
        static final String VALUE = ShaderCacheIO.libraryId(Spvc.getLibrary()) + '|' + D3DCompiler.LIBRARY_ID + '|' + FXC_VARIANT + '|' + FLAGS;
    }

    private static volatile boolean loopHintFallbackLogged;
    private static final int MEMORY_CACHE = 256;
    private static final long MEMORY_CACHE_BYTES = 32L << 20;
    private static long cacheBytes;
    private static final Object2ObjectLinkedOpenHashMap<String, byte[]> CACHE = new Object2ObjectLinkedOpenHashMap<>();

    public static byte[] compile(int[] words, int glType, List<Binding> bindings) {
        final String target = target(glType);
        final ShaderDiskCache.Key key = ShaderDiskCache.key("dxbc").str(ToolchainId.VALUE).str(target).i(bindings.size());
        for (Binding b : bindings) key.i(b.binding()).i(b.kind().ordinal()).i(b.count()).i(b.buffer()).i(b.texture()).i(b.sampler()).b(b.uav());
        key.ints(words);
        final String hex = key.hex();
        synchronized (CACHE) {
            final byte[] hit = CACHE.getAndMoveToFirst(hex);
            if (hit != null) return hit;
        }
        final boolean disk = ShaderDiskCache.isEnabled();
        byte[] dxbc = disk ? ShaderDiskCache.get(key) : null;
        if (dxbc == null) {
            dxbc = compileFxc(toHlsl(words, glType, bindings), target);
            if (disk) ShaderDiskCache.put(key, dxbc);
        }
        remember(hex, dxbc);
        return dxbc;
    }

    private static void remember(String key, byte[] dxbc) {
        synchronized (CACHE) {
            final byte[] previous = CACHE.putAndMoveToFirst(key, dxbc);
            cacheBytes += dxbc.length - (previous == null ? 0 : previous.length);
            while (CACHE.size() > MEMORY_CACHE || cacheBytes > MEMORY_CACHE_BYTES && CACHE.size() > 1) cacheBytes -= CACHE.removeLast().length;
        }
    }

    private static byte[] compileFxc(String source, String target) {
        // FXC unrolls every bounded loop and force-unrolls loops that sample with implicit gradients, so loops are
        // hinted [loop] and sampling inside them is made explicit-LOD before it sees the source.
        final String hlsl = FxcLoops.levelZeroSamplingInLoops(source);
        final String hinted = FxcLoops.addLoopHints(hlsl);
        if (hinted.equals(hlsl)) return D3DCompiler.compile(hlsl, "main", target, FLAGS);
        try {
            return D3DCompiler.compile(hinted, "main", target, FLAGS);
        } catch (IllegalStateException e) {
            if (!loopHintFallbackLogged) {
                loopHintFallbackLogged = true;
                LOG.warn("d3dcompiler_47 rejected a shader with [loop] hints, compiling it unhinted: {}", firstLine(e.getMessage()));
            }
            return D3DCompiler.compile(hlsl, "main", target, FLAGS);
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        final int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static void check(long ctx, int result, String what) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new IllegalStateException("SPIRV-Cross " + what + " failed: " + Spvc.spvc_context_get_last_error_string(ctx));
        }
    }
}
