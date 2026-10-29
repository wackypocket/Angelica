package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.dx12.device.Dx;
import com.gtnewhorizons.angelica.glsm.backend.Dxc;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
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
import java.nio.file.Path;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memByteBufferNT1;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memUTF8;

public final class HlslCompiler {
    private HlslCompiler() {}

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

    private static String target(int glType, boolean dxil) {
        final String model = dxil ? "_6_0" : "_5_1";
        return switch (glType) {
            case GL20.GL_VERTEX_SHADER -> "vs" + model;
            case GL43.GL_COMPUTE_SHADER -> "cs" + model;
            default -> "ps" + model;
        };
    }

    private static final Logger LOG = LogManager.getLogger("Angelica/D3D12");

    private static volatile DxcCompiler dxc;
    private static volatile String dxcMissing = "the Direct3D 12 shader compiler has not been set up";

    public static void configure(int shaderModel) {
        dxc = null;
        if (shaderModel < Dx.SHADER_MODEL_6_0) {
            dxcMissing = "this GPU or driver does not support Shader Model 6.0";
        } else {
            final String path = Dxc.locate();
            if (path == null) {
                dxcMissing = "the DXC shader compiler is missing. Add " + Dxc.JAR_NAME + " to the mods folder";
            } else {
                try {
                    dxc = DxcCompiler.load(Path.of(path));
                    LOG.info("Shaders compile with DXC: {}", path);
                    return;
                } catch (RuntimeException | Error e) {
                    dxcMissing = "DXC at " + path + " could not be loaded: " + e;
                }
            }
        }
        LOG.warn("Shader packs are unavailable on Direct3D 12: {}. Built-in rendering still works.", dxcMissing);
    }

    public static String shaderPackBlocker() {
        return dxc != null ? null : "shader packs are unavailable on Direct3D 12: " + dxcMissing;
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

    private static final int FLAGS = D3DCompiler.ENABLE_STRICTNESS;
    private static final int MEMORY_CACHE = 256;
    private static final it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<String, byte[]> CACHE = new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<>();

    public static byte[] compile(int[] words, int glType, List<Binding> bindings) {
        final DxcCompiler dxc = HlslCompiler.dxc;
        final String hlsl = toHlsl(words, glType, bindings);
        final String target = target(glType, dxc != null);
        if (Boolean.getBoolean("angelica.dx12.dumpHlsl")) System.err.println("---- HLSL (" + target + ") ----\n" + hlsl);
        final String key = target + '\n' + hlsl;
        synchronized (CACHE) {
            final byte[] hit = CACHE.getAndMoveToFirst(key);
            if (hit != null) return hit;
        }
        final ShaderDiskCache.Key diskKey = !ShaderDiskCache.isEnabled() ? null
            : dxc != null ? ShaderDiskCache.key("dxil").str(dxc.id).str(key) : ShaderDiskCache.key("dxbc").str(D3DCompiler.LIBRARY_ID).i(FLAGS).str(key);
        byte[] dxbc = diskKey != null ? ShaderDiskCache.get(diskKey) : null;
        if (dxbc == null) {
            dxbc = dxc != null ? dxc.compile(hlsl, target) : D3DCompiler.compile(hlsl, "main", target, FLAGS);
            if (diskKey != null) ShaderDiskCache.put(diskKey, dxbc);
        }
        synchronized (CACHE) {
            CACHE.putAndMoveToFirst(key, dxbc);
            while (CACHE.size() > MEMORY_CACHE) CACHE.removeLast();
        }
        return dxbc;
    }

    private static void check(long ctx, int result, String what) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new IllegalStateException("SPIRV-Cross " + what + " failed: " + Spvc.spvc_context_get_last_error_string(ctx));
        }
    }
}
