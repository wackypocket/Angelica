package com.gtnewhorizons.angelica.glemu.gl;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.GLDebugMessageListener;
import com.gtnewhorizons.angelica.glsm.backend.DebugMessageHandler;
import com.gtnewhorizons.angelica.glemu.compat.GlPipelines;
import com.gtnewhorizons.angelica.glemu.GlOps;
import com.gtnewhorizons.angelica.glemu.compat.GlExtensions;
import com.gtnewhorizons.angelica.glemu.compat.GlImaging;
import com.gtnewhorizons.angelica.glemu.compat.GlLegacy;
import com.gtnewhorizons.angelica.glemu.compat.GlTransformFeedback;
import com.gtnewhorizons.angelica.glemu.frame.HostRingBase;
import com.gtnewhorizons.angelica.glemu.frame.SerialTracker;
import com.gtnewhorizons.angelica.glemu.resource.BlockCodec;
import com.gtnewhorizons.angelica.glemu.resource.BufBase;
import com.gtnewhorizons.angelica.glemu.resource.BuffersBase;
import com.gtnewhorizons.angelica.glemu.resource.Fbo;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.Framebuffers;
import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;
import com.gtnewhorizons.angelica.glemu.resource.PixelFormats;
import com.gtnewhorizons.angelica.glemu.resource.SamplerObj;
import com.gtnewhorizons.angelica.glemu.resource.TargetBase;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;
import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;
import com.gtnewhorizons.angelica.glemu.resource.AttribConvert;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import com.gtnewhorizons.angelica.glemu.shader.GlslIncludes;
import com.gtnewhorizons.angelica.glemu.shader.LodBiasLowering;
import com.gtnewhorizons.angelica.glemu.shader.ProgramBase;
import com.gtnewhorizons.angelica.glemu.shader.ProgramsBase;
import com.gtnewhorizons.angelica.glemu.shader.Shader;
import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;
import com.gtnewhorizons.angelica.glemu.shader.Spirv;
import com.gtnewhorizons.angelica.glemu.util.IdTable;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import com.gtnewhorizons.angelica.glsm.profiling.Tracy;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.BufferUtils;
import com.gtnewhorizons.angelica.glsm.backend.GlCallLog;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL46;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.opengl.NVXGPUMemoryInfo;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import static org.lwjgl.system.MemoryUtil.memAddress;

public abstract class GlBase<T extends TexBase, P extends ProgramBase<P>, B extends BufBase> implements GlOps {
    /** The least Vulkan lets a device support on each axis. */
    private static final int MAX_COMPUTE_WORK_GROUP_COUNT = 65535;

    private static final int NOT_A_QUERY = Integer.MIN_VALUE;

    private final GlValidation validation = new GlValidation(this);
    private Logger logger;

    public final Logger log() {
        if (logger == null) logger = LogManager.getLogger("Angelica/" + apiLabel());
        return logger;
    }

    public interface PaceSink {
        void gpuWait(long nanos);

        void gate(long durationNanos, long endNanos);
    }

    protected int nextVao = 1, nextSampler = 1;
    protected long window;
    protected final LongArrayFIFOQueue presentSerials = new LongArrayFIFOQueue();
    protected volatile VSyncMode pendingVSync;
    protected volatile Thread mainThread;
    protected GlState mainState;
    protected final ThreadLocal<GlState> threadStates = new ThreadLocal<>();
    protected final Set<GlState> otherStates = ConcurrentHashMap.newKeySet();
    protected final ObjectOpenHashSet<String> warned = new ObjectOpenHashSet<>();
    public long frames, presented, presentSkips, lastStatsNanos, presentWaitNanos;
    public PaceSink paceSink;
    private final ByteBuffer scratchBuffer = ByteBuffer.allocateDirect(64);
    protected final long scratch = MemoryUtil.memAddress(scratchBuffer);
    protected String[] cachedExtensions;
    protected final Int2LongOpenHashMap queryResults = new Int2LongOpenHashMap();
    /** A texture's size is an int, so no chain of mipmaps reaches past this. */
    private static final int MAX_TEXTURE_LEVEL = 31;
    protected final Int2IntOpenHashMap activeQueries = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap queryTargets = new Int2IntOpenHashMap();
    protected final Int2LongOpenHashMap queryStart = new Int2LongOpenHashMap();
    protected int nextQuery = 1;

    protected static final String[] EXTENSIONS = {
        "GL_ARB_framebuffer_object", "GL_ARB_vertex_array_object", "GL_ARB_vertex_buffer_object", "GL_ARB_vertex_shader", "GL_ARB_fragment_shader",
        "GL_ARB_shader_objects", "GL_ARB_shading_language_100", "GL_ARB_texture_non_power_of_two", "GL_ARB_multitexture", "GL_ARB_sampler_objects",
        "GL_ARB_uniform_buffer_object", "GL_ARB_map_buffer_range", "GL_ARB_copy_buffer", "GL_ARB_copy_image", "GL_ARB_explicit_attrib_location",
        "GL_ARB_texture_storage", "GL_ARB_texture_swizzle", "GL_ARB_depth_clamp", "GL_ARB_seamless_cube_map", "GL_ARB_sync",
        "GL_ARB_cull_distance", "GL_ARB_draw_elements_base_vertex", "GL_ARB_instanced_arrays", "GL_ARB_draw_instanced", "GL_ARB_provoking_vertex", "GL_ARB_compute_shader",
        "GL_ARB_shader_storage_buffer_object", "GL_ARB_shader_image_load_store", "GL_ARB_buffer_storage", "GL_ARB_clear_texture", "GL_ARB_clip_control",
        "GL_ARB_clear_buffer_object", "GL_ARB_multi_draw_indirect", "GL_ARB_draw_indirect", "GL_ARB_vertex_attrib_binding", "GL_ARB_texture_rg",
        "GL_ARB_texture_float", "GL_ARB_half_float_vertex", "GL_ARB_depth_buffer_float", "GL_ARB_texture_cube_map_array", "GL_ARB_stencil_texturing", "GL_ARB_direct_state_access", "GL_ARB_separate_shader_objects", "GL_ARB_shader_subroutine",
        "GL_ARB_texture_buffer_object", "GL_ARB_timer_query", "GL_ARB_transform_feedback2", "GL_ARB_draw_buffers_blend", "GL_ARB_program_interface_query", "GL_ARB_texture_view",
        "GL_EXT_framebuffer_object", "GL_EXT_framebuffer_blit", "GL_EXT_framebuffer_sRGB",
        "GL_EXT_texture_filter_anisotropic", "GL_EXT_blend_func_separate", "GL_EXT_blend_equation_separate",
        "GL_EXT_texture_sRGB", "GL_EXT_shader_image_load_store", "GL_EXT_gpu_shader4", "GL_KHR_debug", "GL_ARB_base_instance",
        "GL_ARB_shader_draw_parameters", "GL_ARB_indirect_parameters",
        "GL_ARB_debug_output", "GL_ARB_depth_texture", "GL_ARB_draw_buffers", "GL_ARB_framebuffer_sRGB", "GL_ARB_get_texture_sub_image",
        "GL_ARB_half_float_pixel", "GL_ARB_imaging", "GL_ARB_invalidate_subdata", "GL_ARB_multi_bind", "GL_ARB_pixel_buffer_object", "GL_ARB_polygon_offset_clamp", "GL_ARB_robustness", "GL_ARB_shadow", "GL_ARB_texture_border_clamp",
        "GL_ARB_texture_buffer_range", "GL_ARB_texture_compression", "GL_ARB_texture_cube_map", "GL_ARB_texture_env_add", "GL_ARB_texture_env_combine",
        "GL_ARB_texture_filter_anisotropic", "GL_ARB_texture_mirrored_repeat", "GL_ARB_texture_rectangle", "GL_ARB_texture_rgb10_a2ui",
        "GL_ARB_transform_feedback_instanced", "GL_ARB_transpose_matrix", "GL_ARB_window_pos", "GL_ARB_compute_variable_group_size",
        "GL_ARB_shading_language_include",
        "GL_ARB_conservative_depth", "GL_ARB_derivative_control", "GL_ARB_explicit_uniform_location", "GL_ARB_fragment_coord_conventions",
        "GL_ARB_gpu_shader5", "GL_ARB_shader_bit_encoding", "GL_ARB_shader_texture_lod", "GL_ARB_shading_language_420pack",
        "GL_ARB_shading_language_packing", "GL_ARB_texture_gather", "GL_ARB_texture_query_lod", "GL_EXT_shader_integer_mix",
        "GL_EXT_bgra", "GL_EXT_bindable_uniform", "GL_EXT_blend_color", "GL_EXT_blend_minmax", "GL_EXT_blend_subtract", "GL_EXT_compiled_vertex_array",
        "GL_EXT_debug_label", "GL_EXT_debug_marker", "GL_EXT_draw_buffers2", "GL_EXT_draw_instanced", "GL_EXT_framebuffer_blit_layers",
        "GL_EXT_packed_depth_stencil", "GL_EXT_packed_float", "GL_EXT_pixel_buffer_object", "GL_EXT_polygon_offset_clamp", "GL_EXT_provoking_vertex",
        "GL_EXT_secondary_color", "GL_EXT_separate_shader_objects", "GL_EXT_shadow_funcs", "GL_EXT_stencil_two_side", "GL_EXT_stencil_wrap",
        "GL_EXT_texture_array", "GL_EXT_texture_buffer_object", "GL_EXT_texture_integer", "GL_EXT_texture_shared_exponent", "GL_EXT_texture_snorm",
        "GL_EXT_texture_storage", "GL_EXT_texture_swizzle", "GL_EXT_timer_query", "GL_EXT_transform_feedback", "GL_KHR_robustness", "GL_KHR_context_flush_control", "GL_ARB_gl_spirv",
        "GL_ARB_ES2_compatibility", "GL_ARB_ES3_compatibility", "GL_ARB_ES3_1_compatibility", "GL_ARB_get_program_binary", "GL_ARB_shader_precision",
        "GL_ARB_viewport_array", "GL_ARB_compressed_texture_pixel_storage", "GL_ARB_internalformat_query", "GL_ARB_internalformat_query2", "GL_ARB_map_buffer_alignment",
        "GL_ARB_shader_atomic_counters", "GL_ARB_arrays_of_arrays", "GL_ARB_fragment_layer_viewport", "GL_ARB_framebuffer_no_attachments", "GL_ARB_robust_buffer_access_behavior",
        "GL_ARB_shader_image_size", "GL_ARB_texture_query_levels", "GL_ARB_texture_multisample", "GL_ARB_texture_storage_multisample", "GL_ARB_enhanced_layouts",
        "GL_ARB_query_buffer_object", "GL_ARB_texture_mirror_clamp_to_edge", "GL_ARB_texture_stencil8", "GL_ARB_vertex_type_10f_11f_11f_rev", "GL_ARB_vertex_type_2_10_10_10_rev",
        "GL_ARB_conditional_render_inverted", "GL_ARB_shader_texture_image_samples", "GL_ARB_texture_barrier", "GL_ARB_pipeline_statistics_query", "GL_ARB_shader_atomic_counter_ops",
        "GL_ARB_shader_group_vote", "GL_ARB_spirv_extensions", "GL_ARB_transform_feedback_overflow_query", "GL_ARB_texture_buffer_object_rgb32", "GL_ARB_sample_shading",
        "GL_ARB_transform_feedback3", "GL_ARB_occlusion_query", "GL_ARB_occlusion_query2", "GL_ARB_multisample", "GL_ARB_gpu_shader_fp64",
        "GL_ARB_vertex_attrib_64bit", "GL_ARB_parallel_shader_compile", "GL_ARB_shader_viewport_layer_array",
    };

    protected final IdTable<Vao> vaos = new IdTable<>();
    public final IdTable<SamplerObj> samplerObjects = new IdTable<>();
    /** Bumped whenever a texture's or sampler's sampling state changes or one is deleted, so choices cached from them hold until then. */
    public int samplingVersion;

    /** The stream of the context this thread holds, or else the main one: a thread with no context has no stream of its own. */
    private DebugOutput debugOutputHere() {
        return hasStateOnThread() ? st().debug : mainState.debug;
    }

    /** A message from the driver or its validation layer, which may arrive on any thread. */
    public final void postDriverMessage(int type, int severity, String message) {
        debugOutputHere().post(KHRDebug.GL_DEBUG_SOURCE_API, type, 0, severity, message);
    }

    public abstract boolean statsEnabled();

    /** True once every ten seconds while statistics are on. */
    protected final boolean statsDue(long now) {
        if (!statsEnabled() || now - lastStatsNanos <= 10_000_000_000L) return false;
        lastStatsNanos = now;
        return true;
    }

    /** Runs a stage emulated as a compute shader through the program just made current, whatever the bound pipeline would dispatch. */
    final void dispatchStage(int groups) {
        dispatchingStage = true;
        try {
            dispatchCompute(groups, 1, 1);
        } finally {
            dispatchingStage = false;
        }
    }

    private boolean dispatchingStage;

    /** A bound pipeline's compute stage dispatches unless a program of glUseProgram outranks the pipeline. */
    final int dispatchProgram(GlState st) {
        if (dispatchingStage) return st.program;
        if (GlPipelines.boundPipeline() == 0) return st.program;
        final int current = GLStateManager.ctx().activeProgram;
        return current == 0 || current == pipelineProgram ? GlPipelines.boundComputeProgram() : st.program;
    }

    @Override public final void dispatchCompute(int x, int y, int z) {
        syncTextureViews();
        final GlState st = st();
        if (st.conditionalSkip && !dispatchingStage) return;
        final int program = st.program;
        st.program = dispatchProgram(st);
        try {
            statistics.dispatch(programs().program(st.program), (long) x * y * z);
            dispatch(x, y, z);
        } finally {
            st.program = program;
        }
    }

    @Override public final void dispatchComputeIndirect(long offset) {
        syncTextureViews();
        final GlState st = st();
        if (st.conditionalSkip) return;
        final int program = st.program;
        st.program = dispatchProgram(st);
        try {
            if (queryActive(GL46C.GL_COMPUTE_SHADER_INVOCATIONS)) {
                final IntBuffer groups = BufferUtils.createIntBuffer(3);
                getBufferSubData(GL43.GL_DISPATCH_INDIRECT_BUFFER, offset, groups);
                statistics.dispatch(programs().program(st.program), (long) groups.get(0) * groups.get(1) * groups.get(2));
            }
            dispatchIndirect(offset);
        } finally {
            st.program = program;
        }
    }

    protected abstract void dispatch(int x, int y, int z);

    protected abstract void dispatchIndirect(long offset);

    /** GL answers a dispatch over its limit with an error, where a device would run it until the process is killed. */
    protected final boolean dispatchable(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0) return false;
        if (Math.max(x, Math.max(y, z)) <= MAX_COMPUTE_WORK_GROUP_COUNT) return true;
        warnOnce("ignoring a dispatch of " + x + "x" + y + "x" + z + ", over GL_MAX_COMPUTE_WORK_GROUP_COUNT");
        recordError(GL11.GL_INVALID_VALUE);
        return false;
    }

    /** GL refuses an indirect command outside its buffer, which a device would read out of bounds. */
    protected final boolean indirectInRange(BufBase buffer, long offset, long bytes) {
        if (offset >= 0 && offset + bytes <= buffer.size) return true;
        warnOnce("ignoring an indirect command at offset " + offset + " outside its buffer");
        recordError(GL11.GL_INVALID_OPERATION);
        return false;
    }

    public void warnOnce(String message) {
        synchronized (warned) {
            if (!warned.add(message)) return;
        }
        log().warn("{} GL: {}", apiName(), message);
        debugOutputHere().postWarning(message);
    }

    public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                 int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                 int srcWidth, int srcHeight, int srcDepth) {
        final T src = srcTarget == GL30.GL_RENDERBUFFER ? textures().attachment(srcName, true) : textures().get(srcName);
        final T dst = dstTarget == GL30.GL_RENDERBUFFER ? textures().attachment(dstName, true) : textures().get(dstName);
        textures().copyImage(src, srcLevel, srcX, srcY, srcZ, dst, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, Math.max(1, srcDepth));
    }

    public abstract SerialTracker stream();

    public void flushAndWait(long serial) {
        endPass();
        stream().waitFor(serial);
    }

    public abstract RendererBase<T, P, ?> rendererBase();

    public abstract GpuCaps gpuCaps();

    public long currentSerial() {
        return stream().currentSerial();
    }

    public boolean isIdle(long serial) {
        return stream().isIdle(serial);
    }

    protected void pollStream() {
        stream().poll();
    }

    protected void clearTargets(GlState st, int mask) {
        if (occlusion != null) occlusion.pause();
        rendererBase().clear(st, mask);
        if (occlusion != null) occlusion.resume();
    }

    protected void computeBarrierPending() {
        rendererBase().pendingComputeBarrier = true;
    }

    protected void ensureDefaultFramebuffer(int width, int height) {
        rendererBase().defaultFramebuffer().ensure(width, height);
    }

    protected int defaultFramebufferWidth() {
        return rendererBase().defaultFramebuffer().width;
    }

    protected int defaultFramebufferHeight() {
        return rendererBase().defaultFramebuffer().height;
    }

    protected abstract void debugPush(String message);

    protected abstract void debugPop();

    protected abstract T proxyDefaults();

    public abstract void drawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int primcount, int baseVertex);

    protected abstract String apiName();

    protected abstract String apiLabel();

    protected abstract String apiVersion();

    protected abstract long deviceLocalBytes();

    protected abstract long allocatedBytes();

    protected abstract void submitNow();

    protected abstract void debugNameImage(long image, String label);

    protected abstract void clearImage(T t);

    protected abstract String vendorString();

    public abstract long awaitPresent();

    protected abstract int framesInFlight();

    protected abstract Tracy.ZoneId frameSlotZone();

    /** Caps the submitted but unfinished frames; the wait is where CPU-bound frames spend time on a busy GPU. */
    public long awaitFrameSlot() {
        if (presentSerials.size() < framesInFlight()) return 0L;
        final long t0 = System.nanoTime();
        Tracy.beginZone(frameSlotZone());
        try {
            while (presentSerials.size() >= framesInFlight()) stream().waitFor(presentSerials.dequeueLong());
        } finally {
            Tracy.endZone();
        }
        final long waited = System.nanoTime() - t0;
        presentWaitNanos += waited;
        return waited;
    }

    public abstract boolean hasPresentWait();

    public abstract boolean hasWindow();

    public abstract boolean holdsToVblank();

    protected abstract Tracy.ZoneId presentZone();

    protected abstract void presentFrame();

    public final void present() {
        Tracy.beginZone(presentZone());
        try {
            presentFrame();
        } finally {
            Tracy.endZone();
        }
    }

    public abstract VSyncMode setVSync(VSyncMode preferred);

    public abstract void shutdown();

    /** Finishes what the GPU has been given, carrying on if a lost device makes that impossible. */
    protected final void finishForShutdown() {
        try {
            endPass();
            stream().finish();
        } catch (Throwable t) {
            log().warn("{} shutdown wait failed", apiName(), t);
        }
    }

    protected abstract void destroyRings();

    /** Destroys what every backend has, leaving what is specific to it. */
    protected final void destroySharedResources() {
        rendererBase().destroy();
        destroyPasses();
        programs().destroy();
        textures().destroy();
        buffers().destroy();
        rendererBase().defaultFramebuffer().destroy();
        stream().destroy();
        destroyRings();
    }

    /** Destroys passes built on top of the renderer, before the programs they may use. */
    protected void destroyPasses() {
    }

    public abstract boolean supportsVSync(VSyncMode mode);

    public abstract void quiesceForSwapchainChange();

    protected abstract boolean needsIndexConversion(int mode);

    /** Which vertex of a triangle the device flat-shades from, given the convention GL asked for. */
    protected abstract boolean flatShadesFromFirstVertex(GlState st);

    /** Gets the pass and pipeline ready for a draw. Returns false when nothing can be drawn. */
    protected abstract boolean prepareDrawCall(GlState st, int glMode, int primitive, boolean indexed);

    protected abstract void emitDraw(int primitive, int count, int instances, int first);

    /** Draws with the indices last bound. */
    protected abstract void emitDrawIndexed(int primitive, int count, int instances, int baseVertex);

    /** Binds indices just written to the ring's current allocation. */
    protected abstract void bindRingIndices(long bytes, int indexType);

    /** Binds 32 bit indices from the start of a buffer of glemu's own, for the next indexed draw. */
    protected abstract void bindPatternIndices(B buffer);

    public abstract HostRingBase<?> uniformRing();

    protected abstract int topology(int mode);

    /** Whether this backend has any way to draw the mode; one it lacks is dropped with a warning. */
    protected boolean drawableMode(int mode) {
        return true;
    }

    @Override public void drawArraysInstanced(int mode, int first, int count, int primcount) {
        st().drawBaseVertex = 0;
        noteStatistics(mode, count, primcount);
        if (cullDraws.applies(st())) {
            if (drawableMode(mode)) cullDraws.arrays(st(), mode, first, count, primcount);
            return;
        }
        if (geometryDraws.applies(st())) {
            geometryDraws.arrays(st(), mode, first, count, primcount);
            return;
        }
        if (feedbackDraws.applies(st(), mode, false)) {
            feedbackDraws.arrays(st(), mode, first, count, primcount);
            return;
        }
        GlTransformFeedback.noteDraw(mode, first, count, primcount, st().baseInstance, st().conditionalSkip);
        if (count <= 0 || primcount <= 0 || !drawableMode(mode)) return;
        final GlState st = st();
        if (needsIndexConversion(mode)) {
            drawConvertedArrays(st, mode, first, count, primcount);
            return;
        }
        if (!prepareDrawCall(st, mode, topology(mode), false)) return;
        emitDraw(topology(mode), count, primcount, first);
    }

    @Override public void multiDrawArrays(int mode, IntBuffer firsts, IntBuffer counts) {
        final int n = Math.min(firsts.remaining(), counts.remaining());
        if (n == 0) return;
        final GlState st = st();
        if (GlTransformFeedback.capturing() || culling(st)) {
            for (int i = 0; i < n; i++) {
                st.drawId = i;
                drawArraysInstanced(mode, firsts.get(firsts.position() + i), counts.get(counts.position() + i), 1);
            }
            st.drawId = 0;
            return;
        }
        if (!drawableMode(mode)) return;
        if (needsIndexConversion(mode)) {
            for (int i = 0; i < n; i++) drawConvertedArrays(st, mode, firsts.get(firsts.position() + i), counts.get(counts.position() + i), 1);
            return;
        }
        if (!prepareDrawCall(st, mode, topology(mode), false)) return;
        for (int i = 0; i < n; i++) {
            final int c = counts.get(counts.position() + i);
            if (c > 0) emitDraw(topology(mode), c, 1, firsts.get(firsts.position() + i));
        }
    }

    private final CullDraws cullDraws = new CullDraws(this);
    private final GeometryDraws geometryDraws = new GeometryDraws(this);
    private final FeedbackDraws feedbackDraws = new FeedbackDraws(this);

    /** Whether tessellation and geometry shaders run on the device itself. Without either, both run as compute shaders, whose interfaces have no such limits. */
    private boolean nativeStages() {
        return gpuCaps().geometryShader && gpuCaps().tessellationShader;
    }

    final boolean countingGeneratedPrimitives() {
        for (int stream = 0; stream < GlTransformFeedback.STREAMS; stream++) {
            if (activeQueries.containsKey(queryKey(GL30.GL_PRIMITIVES_GENERATED, stream))) return true;
        }
        return false;
    }

    final void programsCopyUniforms(ProgramBase<?> from, ProgramBase<?> to) {
        programs().copyUniforms(from, to);
    }

    /** For a backend's indexed draws to hand over to geometry shader, transform feedback or cull distance emulation. Returns whether it did. */
    protected final boolean culledElements(GlState st, int mode, int count, int type, long indices, int instances, int baseVertex) {
        if (geometryDraws.applies(st)) {
            geometryDraws.elements(st, mode, indicesInBuffer(st, count, type, indices), type, indices, instances, baseVertex);
            return true;
        }
        if (feedbackDraws.applies(st, mode, true)) {
            feedbackDraws.elements(st, mode, indicesInBuffer(st, count, type, indices), type, indices, instances, baseVertex);
            return true;
        }
        if (!cullDraws.applies(st)) return false;
        if (drawableMode(mode)) cullDraws.elements(st, mode, indicesInBuffer(st, count, type, indices), type, indices, instances, baseVertex);
        return true;
    }

    /** Whether draws must go one at a time: for emulated cull distances or geometry shaders, per-draw uniforms, or transform feedback. */
    protected final boolean culling(GlState st) {
        return cullDraws.applies(st) || geometryDraws.applies(st) || readsDrawParameters(st) || GlTransformFeedback.capturing();
    }

    /** Whether the program reads uniforms set per draw, so no draw can be left to a multi-draw command. */
    private boolean readsDrawParameters(GlState st) {
        final P p = programs().current(st);
        return p != null && p.linked && p.locations.containsKey(ProgramBase.DRAW_ID_UNIFORM);
    }

    public final void clipControl(int origin, int depth) {
        final GlState st = st();
        st.clipState = (origin == GL45.GL_UPPER_LEFT ? 1 : 0) | (depth == GL45.GL_ZERO_TO_ONE ? 2 : 0);
        st.pipelineGen++;
    }

    private void sendClipControl(GlState st) {
        final P p = programs().current(st);
        if (p == null || !p.linked || p.clipState == st.clipState) return;
        p.clipState = st.clipState;
        final int location = p.locations.getOrDefault(ProgramBase.CLIP_UNIFORM, -1);
        if (location < 0) return;
        MemoryUtil.memPutFloat(scratch, st.clipState & 1);
        MemoryUtil.memPutFloat(scratch + Float.BYTES, st.clipState >> 1);
        programs().uniform(p, location, 1, 2, true, scratch);
    }

    private void sendDrawParameters(GlState st) {
        final P p = programs().current(st);
        if (p == null || !p.linked || !p.locations.containsKey(ProgramBase.DRAW_ID_UNIFORM)) return;
        sendDrawParameter(p, ProgramBase.BASE_VERTEX_UNIFORM, st.drawBaseVertex);
        sendDrawParameter(p, ProgramBase.BASE_INSTANCE_UNIFORM, st.baseInstance);
        sendDrawParameter(p, ProgramBase.DRAW_ID_UNIFORM, st.drawId);
    }

    private void sendDrawParameter(P p, String name, int value) {
        final int location = p.locations.getInt(name);
        if (location < 0) return;
        MemoryUtil.memPutInt(scratch, value);
        programs().uniform(p, location, 1, 1, false, scratch);
    }

    /** The draws a count in the parameter buffer asks for, capped as GL caps them. */
    protected final int parameterCount(long offset, int max) {
        final B counts = buffers().get(st().parameterBuffer);
        if (counts == null) return 0;
        buffers().read(counts, offset, Integer.BYTES, scratch);
        return Integer.compareUnsigned(MemoryUtil.memGetInt(scratch), max) < 0 ? MemoryUtil.memGetInt(scratch) : max;
    }

    protected final void drawArraysIndirectByReading(int mode, long indirect, int drawcount, int stride) {
        final GlState st = st();
        final B commands = buffers().get(st.drawIndirect);
        if (commands == null) return;
        final int s = stride == 0 ? 16 : stride, savedBaseInstance = st.baseInstance;
        for (int i = 0; i < drawcount; i++) {
            buffers().read(commands, indirect + (long) i * s, 16, scratch);
            st.baseInstance = MemoryUtil.memGetInt(scratch + 12L);
            st.drawId = i;
            drawArraysInstanced(mode, MemoryUtil.memGetInt(scratch + 8L), MemoryUtil.memGetInt(scratch), MemoryUtil.memGetInt(scratch + 4L));
        }
        st.baseInstance = savedBaseInstance;
        st.drawId = 0;
    }

    final int[] readIndices(GlState st, int count, int type, long indices) {
        final B ebo = buffers().get(st.vao.elementBuffer);
        final int bytes = indexBytes(type);
        final int[] out = new int[count];
        // A bound element buffer without storage has no indices to read; the offset is not a client pointer.
        if (st.vao.elementBuffer != 0 && (ebo == null || !ebo.hasStorage())) return out;
        long src = indices, copied = 0L;
        if (ebo != null && ebo.hasStorage()) {
            copied = MemoryUtil.nmemAlloc(Math.max(1L, (long) count * bytes));
            buffers().read(ebo, indices, (long) count * bytes, copied);
            src = copied;
        }
        for (int i = 0; i < count; i++) {
            final long at = src + (long) i * bytes;
            out[i] = bytes == 1 ? MemoryUtil.memGetByte(at) & 0xFF : bytes == 2 ? MemoryUtil.memGetShort(at) & 0xFFFF : MemoryUtil.memGetInt(at);
        }
        if (copied != 0L) MemoryUtil.nmemFree(copied);
        return out;
    }

    final void drawKeptIndices(GlState st, int listMode, int[] indices, int length) {
        final HostRingBase<?> ring = uniformRing();
        ring.alloc(length * 4L, 4);
        for (int i = 0; i < length; i++) MemoryUtil.memPutInt(ring.address + i * 4L, indices[i]);
        bindRingIndices(length * 4L, NativeTokens.INDEX_UINT32);
        if (prepareDrawCall(st, listMode, topology(listMode), true)) emitDrawIndexed(topology(listMode), length, 1, 0);
    }

    private void drawConvertedArrays(GlState st, int mode, int first, int count, int instances) {
        if (mode != GL11.GL_LINE_LOOP && drawStoredPattern(st, mode, first, count, instances)) return;
        final int length = convertIndices(mode, count);
        if (length == 0) return;
        final HostRingBase<?> ring = uniformRing();
        ring.alloc(length * 4L, 4);
        putConvertedIndices(ring, length);
        drawRingIndices(st, mode, length, instances, first);
    }

    /** A converted mode's indices over a run of vertices from 0, which only grow with it, kept on the device for each way they can be ordered. */
    private static final class StoredPattern {
        int buffer, vertices;
    }

    private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<StoredPattern> storedPatterns = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

    /**
     * Draws a run of vertices through the stored indices of its mode, built anew only when a longer run comes. A line loop closes onto its first
     * vertex, which a longer run's list has elsewhere, so it is written each time instead.
     */
    private boolean drawStoredPattern(GlState st, int mode, int first, int count, int instances) {
        final boolean deviceFirst = flatShadesFromFirstVertex(st);
        final int key = mode << 3 | (st.provokingFirst ? 1 : 0) | (deviceFirst ? 2 : 0) | (lineTurned(st) ? 4 : 0);
        StoredPattern stored = storedPatterns.get(key);
        if (stored == null) {
            stored = new StoredPattern();
            storedPatterns.put(key, stored);
        }
        if (stored.vertices < count) {
            final int vertices = Math.max(count, Math.max(1024, stored.vertices * 2));
            final int built = convertIndices(mode, vertices);
            if (built == 0) return false;
            if (stored.buffer == 0) stored.buffer = buffers().gen();
            final long bytes = built * 4L, data = MemoryUtil.nmemAlloc(bytes);
            try {
                for (int i = 0; i < built; i++) MemoryUtil.memPutInt(data + i * 4L, indexPattern[i]);
                buffers().data(buffers().getOrCreate(stored.buffer), bytes, data, GL15.GL_STATIC_DRAW);
            } finally {
                MemoryUtil.nmemFree(data);
            }
            stored.vertices = vertices;
        }
        final int length = convertedLength(mode, count);
        if (length == 0) return true;
        final B buffer = buffers().get(stored.buffer);
        if (buffer == null) return false;
        final int convMode = convertedMode(st, mode);
        bindPatternIndices(buffer);
        if (prepareDrawCall(st, convMode, topology(convMode), true)) emitDrawIndexed(topology(convMode), length, instances, first);
        else bindPatternIndices(null);
        return true;
    }

    private void drawRingIndices(GlState st, int mode, int indexCount, int instances, int baseVertex) {
        final int convMode = convertedMode(st, mode);
        bindRingIndices(indexCount * 4L, NativeTokens.INDEX_UINT32);
        if (!prepareDrawCall(st, convMode, topology(convMode), true)) return;
        emitDrawIndexed(topology(convMode), indexCount, instances, baseVertex);
    }

    protected final void drawEachConverted(GlState st, int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) {
        for (int i = 0; i < drawcount; i++) {
            drawConvertedElements(st, mode, MemoryUtil.memGetInt(pCount + i * 4L), type, MemoryUtil.memGetAddress(pIndices + (long) i * Pointer.POINTER_SIZE), 1,
                pBaseVertex == 0L ? 0 : MemoryUtil.memGetInt(pBaseVertex + i * 4L));
        }
    }

    /** GL leaves indices past the element buffer's end undefined, and a device may never finish fetching them, so a draw stops at the end. */
    protected final int indicesInBuffer(GlState st, int count, int type, long indices) {
        final B ebo = buffers().get(st.vao.elementBuffer);
        if (ebo == null || !ebo.hasStorage()) return st.vao.elementBuffer != 0 ? 0 : count;
        return indices < 0 ? 0 : (int) Math.min(count, Math.max(0L, (ebo.size - indices) / indexBytes(type)));
    }

    protected final void drawConvertedElements(GlState st, int mode, int count, int type, long indices, int instances, int baseVertex) {
        final int indexCount = writeConvertedIndices(st, mode, count, type, indices, uniformRing());
        if (indexCount != 0) drawRingIndices(st, mode, indexCount, instances, baseVertex);
    }

    /** A device restarts strips, and only at the index it reserves for the index type. */
    protected final boolean restartNeedsReading(GlState st, int mode, int type) {
        if (!st.primitiveRestart && !st.primitiveRestartFixed) return false;
        final boolean strip = mode == GL11.GL_TRIANGLE_STRIP || mode == GL11.GL_LINE_STRIP || mode == GL32.GL_TRIANGLE_STRIP_ADJACENCY || mode == GL32.GL_LINE_STRIP_ADJACENCY;
        if (!strip || adjacencyToDrop(st, mode)) return true;
        return !st.primitiveRestartFixed && st.primitiveRestartIndex != (type == GL11.GL_UNSIGNED_BYTE ? 0xFF : type == GL11.GL_UNSIGNED_SHORT ? 0xFFFF : -1);
    }

    /** Runs each stretch between restart indices as a draw of its own, leaving out a last primitive it has too few vertices for. */
    protected final void drawElementsRestarting(GlState st, int mode, int count, int type, long indices, int instances, int baseVertex) {
        final int readable = indicesInBuffer(st, count, type, indices);
        if (readable <= 0 || instances <= 0) return;
        final int[] read = readIndices(st, readable, type, indices);
        final int restart = st.primitiveRestartFixed ? (type == GL11.GL_UNSIGNED_BYTE ? 0xFF : type == GL11.GL_UNSIGNED_SHORT ? 0xFFFF : -1) : st.primitiveRestartIndex;
        final it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList(readable);
        int listMode = mode;
        int start = 0;
        for (int i = 0; i <= readable; i++) {
            if (i < readable && read[i] != restart) continue;
            listMode = adjacencyToDrop(st, mode) ? appendWithoutAdjacency(mode, read, start, i - start, out) : appendRestartedStretch(mode, read, start, i - start, out, i < readable);
            start = i + 1;
        }
        if (out.isEmpty()) return;
        final HostRingBase<?> ring = uniformRing();
        ring.alloc(out.size() * 4L, 4);
        for (int i = 0; i < out.size(); i++) MemoryUtil.memPutInt(ring.address + i * 4L, out.getInt(i));
        bindRingIndices(out.size() * 4L, NativeTokens.INDEX_UINT32);
        if (prepareDrawCall(st, listMode, topology(listMode), true)) emitDrawIndexed(topology(listMode), out.size(), instances, baseVertex);
    }

    private static int appendWithoutAdjacency(int mode, int[] read, int at, int n, it.unimi.dsi.fastutil.ints.IntArrayList out) {
        switch (mode) {
            case GL32.GL_LINES_ADJACENCY -> {
                for (int i = 0; i + 3 < n; i += 4) out.addElements(out.size(), new int[] {read[at + i + 1], read[at + i + 2]});
                return GL11.GL_LINES;
            }
            case GL32.GL_LINE_STRIP_ADJACENCY -> {
                for (int i = 1; i + 2 < n; i++) out.addElements(out.size(), new int[] {read[at + i], read[at + i + 1]});
                return GL11.GL_LINES;
            }
            case GL32.GL_TRIANGLES_ADJACENCY -> {
                for (int i = 0; i + 5 < n; i += 6) out.addElements(out.size(), new int[] {read[at + i], read[at + i + 2], read[at + i + 4]});
                return GL11.GL_TRIANGLES;
            }
            default -> {
                for (int t = 0; 2 * t + 5 < n; t++) {
                    final int a = read[at + 2 * t], b = read[at + 2 * t + 2], c = read[at + 2 * t + 4];
                    out.addElements(out.size(), t % 2 == 0 ? new int[] {a, b, c} : new int[] {b, a, c});
                }
                return GL11.GL_TRIANGLES;
            }
        }
    }

    /** Answers the mode the appended indices are in. */
    private static int appendRestartedStretch(int mode, int[] read, int at, int n, it.unimi.dsi.fastutil.ints.IntArrayList out, boolean restartFollows) {
        switch (mode) {
            case GL11.GL_POINTS, GL11.GL_LINES, GL11.GL_TRIANGLES, GL32.GL_LINES_ADJACENCY, GL32.GL_TRIANGLES_ADJACENCY -> {
                final int size = mode == GL11.GL_POINTS ? 1 : mode == GL11.GL_LINES ? 2 : mode == GL11.GL_TRIANGLES ? 3 : mode == GL32.GL_LINES_ADJACENCY ? 4 : 6;
                out.addElements(out.size(), read, at, n - n % size);
                return mode;
            }
            case GL11.GL_LINE_LOOP -> {
                for (int i = 0; n > 1 && i < n; i++) {
                    out.add(read[at + i]);
                    out.add(read[at + (i + 1) % n]);
                }
                return GL11.GL_LINES;
            }
            case GL11.GL_TRIANGLE_FAN, GL11.GL_POLYGON -> {
                for (int i = 1; i + 1 < n; i++) {
                    out.add(read[at]);
                    out.add(read[at + i]);
                    out.add(read[at + i + 1]);
                }
                return GL11.GL_TRIANGLES;
            }
            case GL11.GL_QUADS -> {
                for (int i = 0; i + 3 < n; i += 4) {
                    out.addElements(out.size(), new int[] {read[at + i], read[at + i + 1], read[at + i + 2], read[at + i], read[at + i + 2], read[at + i + 3]});
                }
                return GL11.GL_TRIANGLES;
            }
            case GL11.GL_QUAD_STRIP -> {
                for (int i = 0; i + 3 < n; i += 2) {
                    out.addElements(out.size(), new int[] {read[at + i], read[at + i + 1], read[at + i + 3], read[at + i], read[at + i + 3], read[at + i + 2]});
                }
                return GL11.GL_TRIANGLES;
            }
            default -> {
                out.addElements(out.size(), read, at, n);
                if (restartFollows) out.add(-1);
                return mode;
            }
        }
    }

    /** Whether commands in an indirect buffer have to be read here, as modes and index types the device lacks or stages run as compute shaders need. */
    protected final boolean readsIndirectCommands(int mode, int type) {
        final GlState st = st();
        return countingStatistics() || readsDrawParameters(st) || needsIndexConversion(mode) || restartNeedsReading(st, mode, type) || type == GL11.GL_UNSIGNED_BYTE && !gpuCaps().indexTypeUint8 || cullDraws.applies(st) || geometryDraws.applies(st) || GlTransformFeedback.capturing();
    }

    protected final void drawElementsIndirectByReading(int mode, int type, long indirect, int drawcount, int stride) {
        final GlState st = st();
        final int s = stride == 0 ? 20 : stride, indexBytes = type == GL11.GL_UNSIGNED_BYTE ? 1 : type == GL11.GL_UNSIGNED_SHORT ? 2 : 4;
        final int savedBaseInstance = st.baseInstance;
        final ByteBuffer command = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
        for (int i = 0; i < drawcount; i++) {
            command.clear();
            getBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, indirect + (long) i * s, command);
            st.baseInstance = command.getInt(16);
            st.drawId = i;
            drawElementsInstancedBaseVertex(mode, command.getInt(0), type, Integer.toUnsignedLong(command.getInt(8)) * indexBytes, command.getInt(4), command.getInt(12));
        }
        st.baseInstance = savedBaseInstance;
        st.drawId = 0;
    }

    /** Whether commands in an indirect buffer could be drawn as they are, warning when they cannot. */
    protected final boolean canDrawIndirect(int mode, int type) {
        if (!drawableMode(mode)) return false;
        if (type != 0) checkRestartIndex(st(), type);
        if (!needsIndexConversion(mode) && (type != GL11.GL_UNSIGNED_BYTE || gpuCaps().indexTypeUint8)) return true;
        warnOnce("indirect draws of mode 0x" + Integer.toHexString(mode) + " with index type 0x" + Integer.toHexString(type) + " are not supported on " + apiName());
        return false;
    }

    /** A blit already clipped to both images, with the source's pending clears run. */
    protected abstract void blitClipped(T src, int srcLevel, int srcLayer, T dst, int dstLevel, int dstLayer, int aspect, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, boolean linear);

    /** Writes a multisampled image's samples, averaged, into a single sampled one. */
    protected abstract void resolveImage(T s, int sLayer, T d, int dLevel, int dLayer, int x, int y, int dx, int dy, int width, int height);

    /** Writes each pixel's first sample of depth and stencil into a single sampled image. */
    protected abstract void resolveDepthStencil(T s, int sLayer, T d, int dLevel, int dLayer, int aspect, int x, int y, int dx, int dy, int width, int height);

    /** An image of both only offers what the framebuffer attached it as. */
    private boolean readHasDepth(TargetBase<T> src) {
        final Fbo f = framebuffers.get(st().readFbo);
        return f == null || f.id == 0 ? src.hasDepth : f.depthTex != 0;
    }

    private boolean readHasStencil(TargetBase<T> src) {
        final Fbo f = framebuffers.get(st().readFbo);
        return f == null || f.id == 0 ? src.hasStencil : f.stencilTex != 0;
    }

    public final boolean readBufferOffers(int format) {
        final TargetBase<T> src = readTarget(st());
        return switch (format) {
            case GL11.GL_DEPTH_COMPONENT -> readHasDepth(src);
            case GL11.GL_STENCIL_INDEX -> readHasStencil(src);
            case GL30.GL_DEPTH_STENCIL -> readHasDepth(src) && readHasStencil(src);
            default -> {
                final boolean integers = format == GL30.GL_RED_INTEGER || format == GL30.GL_GREEN_INTEGER || format == GL30.GL_BLUE_INTEGER || format == GL30.GL_RG_INTEGER
                    || format == GL30.GL_RGB_INTEGER || format == GL30.GL_BGR_INTEGER || format == GL30.GL_RGBA_INTEGER || format == GL30.GL_BGRA_INTEGER;
                yield src.color[0] != null && src.color[0].format.isInteger() == integers;
            }
        };
    }

    /** A resolve keeps what is stored, so the read buffer and every draw buffer must have one format. */
    public final boolean blitFormatsResolve() {
        final GlState st = st();
        final TargetBase<T> src = readTarget(st);
        if (src.color[0] == null) return true;
        blitTarget().fboId = Integer.MIN_VALUE;
        resolveTarget(blitTarget(), framebuffers.get(st.drawFbo), st);
        final TargetBase<T> dst = blitTarget();
        for (int i = 0; i < dst.colorCount; i++) {
            if (dst.color[i] != null && dst.color[i].format.glInternal() != src.color[0].format.glInternal()) return false;
        }
        return true;
    }

    private void blitImage(T s, int sLevel, int sLayer, T d, int dLevel, int dLayer, int aspect, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, boolean linear) {
        if (s == null || d == null || !s.hasImage() || !d.hasImage()) return;
        final int sw = s.levelWidth(sLevel), sh = s.levelHeight(sLevel), dw = d.levelWidth(dLevel), dh = d.levelHeight(dLevel);
        final int[] x = {sx0, sx1, dx0, dx1}, y = {sy0, sy1, dy0, dy1};
        if (!clipBlitAxis(x, sw, dw) || !clipBlitAxis(y, sh, dh)) return;
        sx0 = x[0]; sx1 = x[1]; dx0 = x[2]; dx1 = x[3];
        sy0 = y[0]; sy1 = y[1]; dy0 = y[2]; dy1 = y[3];
        rendererBase().materialize(s);
        final int x0 = Math.min(sx0, sx1), y0 = Math.min(sy0, sy1), dx = Math.min(dx0, dx1), dy = Math.min(dy0, dy1), w = Math.abs(sx1 - sx0), h = Math.abs(sy1 - sy0);
        if (s.samples != d.samples) {
            rendererBase().materialize(d);
            if (s.samples < d.samples) {
                if (com.gtnewhorizons.angelica.glemu.pipeline.ImagePass.reads(s)) {
                    endPass();
                    if ((aspect & Formats.ASPECT_COLOR) != 0 || (aspect & Formats.ASPECT_DEPTH) != 0) imagePass().copy(st(), s, sLevel, d, dLevel, dLayer, (aspect & Formats.ASPECT_DEPTH) != 0, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
                    if ((aspect & Formats.ASPECT_STENCIL) != 0) imagePass().copyStencil(st(), s, sLevel, sLayer, d, dLevel, dLayer, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
                    endPass();
                } else {
                    warnOnce("a blit into a multisampled image from this kind of source is not implemented");
                }
            }
            else if (aspect == Formats.ASPECT_COLOR) {
                if (s.format.isInteger() && !gpuCaps().integerResolve && com.gtnewhorizons.angelica.glemu.pipeline.ImagePass.reads(s)) {
                    endPass();
                    imagePass().copy(st(), s, sLevel, d, dLevel, dLayer, false, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
                    endPass();
                }
                else if (s.format.isInteger() && !gpuCaps().integerResolve) warnOnce("this device cannot resolve multisampled integer images; a blit from one leaves its target unchanged");
                else if (s.format.glInternal() == d.format.glInternal() && w == Math.abs(dx1 - dx0) && h == Math.abs(dy1 - dy0) && sx1 - sx0 == dx1 - dx0 && sy1 - sy0 == dy1 - dy0) resolveImage(s, sLayer, d, dLevel, dLayer, x0, y0, dx, dy, w, h);
                else {
                    final T resolved = textures().internalTexture(GL11.GL_TEXTURE_2D, s.format, sw, sh, 1, 1, "blitResolve");
                    resolveImage(s, sLayer, resolved, 0, 0, x0, y0, x0, y0, w, h);
                    blitClipped(resolved, 0, 0, d, dLevel, dLayer, aspect, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, linear);
                    endPass();
                    releaseImage(resolved);
                }
            }
            else resolveDepthStencil(s, sLayer, d, dLevel, dLayer, aspect, x0, y0, dx, dy, w, h);
            return;
        }
        if (s.samples > 1) {
            rendererBase().materialize(d);
            textures().copyImage(s, 0, x0, y0, sLayer, d, 0, dx, dy, dLayer, w, h, 1);
            return;
        }
        final boolean scaled = sx1 - sx0 != dx1 - dx0 || sy1 - sy0 != dy1 - dy0;
        if (scaled && aspect != Formats.ASPECT_COLOR && !gpuCaps().depthBlitScales && com.gtnewhorizons.angelica.glemu.pipeline.ImagePass.reads(s)) {
            rendererBase().materialize(d);
            endPass();
            if ((aspect & Formats.ASPECT_DEPTH) != 0) imagePass().copy(st(), s, sLevel, d, dLevel, dLayer, true, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
            if ((aspect & Formats.ASPECT_STENCIL) != 0) imagePass().copyStencil(st(), s, sLevel, sLayer, d, dLevel, dLayer, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
            endPass();
            return;
        }
        blitClipped(s, sLevel, sLayer, d, dLevel, dLayer, aspect, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, linear);
    }

    private com.gtnewhorizons.angelica.glemu.pipeline.ImagePass<T> imagePass;

    protected final com.gtnewhorizons.angelica.glemu.pipeline.ImagePass<T> imagePass() {
        if (imagePass == null) imagePass = new com.gtnewhorizons.angelica.glemu.pipeline.ImagePass<>(this);
        return imagePass;
    }

    public abstract String stats();

    public abstract void releaseImage(T t);

    public abstract void createImage(T t, boolean storage);

    protected void endPass() {
        rendererBase().endPass();
    }

    protected TargetBase<T> readTarget(GlState st) {
        return rendererBase().readTarget(st);
    }

    protected void resolveTarget(TargetBase<T> into, Fbo draw, GlState st) {
        rendererBase().resolveInto(into, draw, st);
    }

    protected abstract TargetBase<T> blitTarget();

    protected boolean waitForSerial(long serial, long timeout) {
        return stream().waitFor(serial, timeout);
    }

    public abstract TexturesBase<T> textures();

    protected abstract ProgramsBase<P> programs();

    public abstract BuffersBase<B> buffers();

    public void targetsChanged() {
        rendererBase().targetGen++;
    }

    public final Framebuffers framebuffers = new Framebuffers(new Framebuffers.Host() {
        @Override public TexBase attachment(int id, boolean renderbuffer) {
            return textures().attachment(id, renderbuffer);
        }

        @Override public boolean renderable(int width, int height) {
            return width <= gpuCaps().maxFramebufferWidth && height <= gpuCaps().maxFramebufferHeight;
        }

        @Override public void targetsChanged() {
            GlBase.this.targetsChanged();
        }
    });

    public GlState st() {
        final Thread t = Thread.currentThread();
        if (t == mainThread) return mainState;
        if (mainThread == null) {
            mainThread = t;
            return mainState;
        }
        GlState s = threadStates.get();
        if (s == null) {
            s = new GlState();
            threadStates.set(s);
            otherStates.add(s);
        }
        return s;
    }

    public void setMainThread(Thread t) {
        mainThread = t;
    }

    public Thread mainThread() {
        return mainThread;
    }

    public boolean hasStateOnThread() {
        return Thread.currentThread() == mainThread || threadStates.get() != null;
    }

    public void releaseThreadState() {
        final GlState s = threadStates.get();
        if (s != null) {
            releaseHeld(s);
            threadStates.remove();
            otherStates.remove(s);
        }
    }

    public boolean otherThreadsHaveState() {
        return !otherStates.isEmpty();
    }

    public boolean anyStateUsesProgram(int id) {
        if (mainState.program == id) return true;
        for (GlState s : otherStates) if (s.program == id) return true;
        return GlPipelines.usesProgram(id);
    }

    /** A program flagged for deletion goes when the last thing using it lets go. */
    public void releaseProgram(int id) {
        final P p = programs().program(id);
        if (p != null && p.deletePending && !anyStateUsesProgram(id)) programs().deleteProgram(id);
    }

    public void attachOffscreen(int width, int height) {
        ensureDefaultFramebuffer(width, height);
    }

    protected long windowPixels() {
        if (window == 0L) return (long) Math.max(1, defaultFramebufferWidth()) << 32 | Math.max(1, defaultFramebufferHeight());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            SDLVideo.SDL_GetWindowSizeInPixels(window, w, h);
            return ((long) Math.max(0, w.get(0)) << 32) | (Math.max(0, h.get(0)) & 0xFFFFFFFFL);
        }
    }

    /** Keeps the vblank source on the window's display. Runs outside the backend lock because it waits for the main thread. */
    public void followWindowDisplay() {
    }

    public void flush() {
    }

    public void finish() {
        flushAndWait(currentSerial());
    }

    public void enable(int cap) {
        setCap(cap, true);
    }

    public void disable(int cap) {
        setCap(cap, false);
    }

    public void enablei(int cap, int index) {
        final GlState st = st();
        if (cap == GL11.GL_BLEND && index >= 0 && index < GlState.MAX_DRAW_BUFFERS) {
            if (!st.blend[index]) {
                st.blend[index] = true;
                st.pipelineGen++;
            }
            return;
        }
        if (cap == GL11.GL_SCISSOR_TEST && index >= 0 && index < GlState.MAX_VIEWPORTS) {
            scissorTestAt(st, index, true);
            return;
        }
        setCap(cap, true);
    }

    private static void scissorTestAt(GlState st, int index, boolean on) {
        st.indexedScissorTest[index] = on;
        if (index == 0) st.scissorTest = on;
        st.dynamicGen++;
    }

    /** A viewport's x, y, width, height, near and far, as the device takes them. */
    public final void viewportAt(GlState st, int index, double[] out) {
        final float[] set = index == 0 ? null : st.indexedViewports[index], range = index == 0 ? null : st.indexedDepthRanges[index];
        out[0] = set != null ? set[0] : st.vpX;
        out[1] = set != null ? set[1] : st.vpY;
        out[2] = set != null ? set[2] : st.vpW;
        out[3] = set != null ? set[3] : st.vpH;
        out[4] = Math.max(0, Math.min(1, range != null ? range[0] : st.depthNear));
        out[5] = Math.max(0, Math.min(1, range != null ? range[1] : st.depthFar));
    }

    /** A scissor rectangle within the target, as the device takes it. */
    public final void scissorAt(GlState st, int index, int width, int height, int[] out) {
        final float[] set = index == 0 ? null : st.indexedScissors[index];
        final int x = set != null ? (int) set[0] : st.scX, y = set != null ? (int) set[1] : st.scY, w = set != null ? (int) set[2] : st.scW, h = set != null ? (int) set[3] : st.scH;
        if (!st.indexedScissorTest[index]) {
            out[0] = out[1] = 0;
            out[2] = width;
            out[3] = height;
            return;
        }
        out[0] = Math.max(0, x);
        out[1] = Math.max(0, y);
        out[2] = Math.max(out[0], Math.min(width, x + w)) - out[0];
        out[3] = Math.max(out[1], Math.min(height, y + h)) - out[1];
    }

    public void disablei(int cap, int index) {
        final GlState st = st();
        if (cap == GL11.GL_BLEND && index >= 0 && index < GlState.MAX_DRAW_BUFFERS) {
            if (st.blend[index]) {
                st.blend[index] = false;
                st.pipelineGen++;
            }
            return;
        }
        if (cap == GL11.GL_SCISSOR_TEST && index >= 0 && index < GlState.MAX_VIEWPORTS) {
            scissorTestAt(st, index, false);
            return;
        }
        setCap(cap, false);
    }

    protected void setCap(int cap, boolean on) {
        final GlState st = st();
        switch (cap) {
            case KHRDebug.GL_DEBUG_OUTPUT -> st.debug.setEnabled(on);
            case KHRDebug.GL_DEBUG_OUTPUT_SYNCHRONOUS -> st.debug.setSynchronous(on);
            case GL11.GL_BLEND -> {
                boolean changed = false;
                for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) {
                    if (st.blend[i] != on) changed = true;
                    st.blend[i] = on;
                }
                if (changed) st.pipelineGen++;
            }
            case GL11.GL_DEPTH_TEST -> { if (st.depthTest != on) { st.depthTest = on; st.pipelineGen++; } }
            case GL11.GL_CULL_FACE -> { if (st.cull != on) { st.cull = on; st.pipelineGen++; } }
            case GL11.GL_STENCIL_TEST -> { if (st.stencilTest != on) { st.stencilTest = on; st.pipelineGen++; } }
            case GL11.GL_SCISSOR_TEST -> {
                Arrays.fill(st.indexedScissorTest, on);
                if (st.scissorTest != on) {
                    st.scissorTest = on;
                    st.dynamicGen++;
                }
            }
            case GL11.GL_POLYGON_OFFSET_FILL -> { if (st.polyOffsetFill != on) { st.polyOffsetFill = on; st.pipelineGen++; } }
            case GL11.GL_POLYGON_OFFSET_LINE -> { if (st.polyOffsetLine != on) { st.polyOffsetLine = on; st.pipelineGen++; } }
            case GL11.GL_POLYGON_OFFSET_POINT -> { if (st.polyOffsetPoint != on) { st.polyOffsetPoint = on; st.pipelineGen++; } }
            case GL32.GL_DEPTH_CLAMP -> { if (st.depthClamp != on) { st.depthClamp = on; st.pipelineGen++; } }
            case GL30.GL_RASTERIZER_DISCARD -> { if (st.rasterizerDiscard != on) { st.rasterizerDiscard = on; st.dynamicGen++; } }
            case GL11.GL_COLOR_LOGIC_OP -> { if (st.logicOpEnabled != on) { st.logicOpEnabled = on; st.pipelineGen++; } }
            case GL31.GL_PRIMITIVE_RESTART -> { if (st.primitiveRestart != on) { st.primitiveRestart = on; st.pipelineGen++; } }
            case GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX -> { if (st.primitiveRestartFixed != on) { st.primitiveRestartFixed = on; st.pipelineGen++; } }
            case GL32.GL_PROGRAM_POINT_SIZE -> st.programPointSize = on;
            case GL13.GL_SAMPLE_ALPHA_TO_COVERAGE -> { if (st.sampleAlphaToCoverage != on) { st.sampleAlphaToCoverage = on; st.pipelineGen++; } }
            case GL13.GL_SAMPLE_ALPHA_TO_ONE -> { if (st.sampleAlphaToOne != on) { st.sampleAlphaToOne = on; st.pipelineGen++; } }
            case GL13.GL_SAMPLE_COVERAGE -> { if (st.sampleCoverage != on) { st.sampleCoverage = on; st.pipelineGen++; } }
            case GL32.GL_SAMPLE_MASK -> { if (st.sampleMaskEnabled != on) { st.sampleMaskEnabled = on; st.pipelineGen++; } }
            case GL40.GL_SAMPLE_SHADING -> { if (st.sampleShading != on) { st.sampleShading = on; st.pipelineGen++; } }
            case GL30.GL_FRAMEBUFFER_SRGB -> {
                if (st.framebufferSrgb != on) {
                    endPass();
                    st.framebufferSrgb = on;
                    targetsChanged();
                }
            }
            case GL13.GL_MULTISAMPLE -> { if (st.multisample != on) { st.multisample = on; st.pipelineGen++; } }
            case GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS -> st.seamlessCubes = on;
            default -> {
                if (GlImaging.setCapability(cap, on) || GlExtensions.setCapability(cap, on)) return;
                if (cap >= GL30.GL_CLIP_DISTANCE0 && cap < GL30.GL_CLIP_DISTANCE0 + 8) {
                    final int bit = 1 << (cap - GL30.GL_CLIP_DISTANCE0);
                    final int mask = on ? st.clipDistances | bit : st.clipDistances & ~bit;
                    if (mask != st.clipDistances) {
                        st.clipDistances = mask;
                        st.pipelineGen++;
                    }
                }
            }
        }
    }

    public boolean isEnabled(int cap) {
        final GlState st = st();
        return switch (cap) {
            case GL11.GL_BLEND -> st.blend[0];
            case GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS -> st.seamlessCubes;
            case GL11.GL_DEPTH_TEST -> st.depthTest;
            case GL11.GL_CULL_FACE -> st.cull;
            case GL11.GL_STENCIL_TEST -> st.stencilTest;
            case GL11.GL_SCISSOR_TEST -> st.scissorTest;
            case GL11.GL_POLYGON_OFFSET_FILL -> st.polyOffsetFill;
            case GL11.GL_POLYGON_OFFSET_LINE -> st.polyOffsetLine;
            case GL11.GL_POLYGON_OFFSET_POINT -> st.polyOffsetPoint;
            case GL32.GL_DEPTH_CLAMP -> st.depthClamp;
            case GL30.GL_RASTERIZER_DISCARD -> st.rasterizerDiscard;
            case GL11.GL_COLOR_LOGIC_OP -> st.logicOpEnabled;
            case GL31.GL_PRIMITIVE_RESTART -> st.primitiveRestart;
            case GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX -> st.primitiveRestartFixed;
            case GL32.GL_PROGRAM_POINT_SIZE -> st.programPointSize;
            case GL30.GL_FRAMEBUFFER_SRGB -> st.framebufferSrgb;
            case KHRDebug.GL_DEBUG_OUTPUT -> st.debug.isEnabled();
            case KHRDebug.GL_DEBUG_OUTPUT_SYNCHRONOUS -> st.debug.isSynchronous();
            case GL13.GL_MULTISAMPLE -> st.multisample;
            case GL13.GL_SAMPLE_ALPHA_TO_COVERAGE -> st.sampleAlphaToCoverage;
            case GL13.GL_SAMPLE_ALPHA_TO_ONE -> st.sampleAlphaToOne;
            case GL13.GL_SAMPLE_COVERAGE -> st.sampleCoverage;
            case GL32.GL_SAMPLE_MASK -> st.sampleMaskEnabled;
            case GL40.GL_SAMPLE_SHADING -> st.sampleShading;
            case GL11.GL_DEPTH_WRITEMASK -> st.depthMask;
            default -> GlImaging.isEnabled(cap) || GlExtensions.isEnabled(cap) || cap >= GL30.GL_CLIP_DISTANCE0 && cap < GL30.GL_CLIP_DISTANCE0 + 8 && (st.clipDistances & (1 << (cap - GL30.GL_CLIP_DISTANCE0))) != 0;
        };
    }

    public final void sampleMaski(int word, int mask) {
        if (word != 0) return;
        final GlState st = st();
        st.sampleMaskWord = mask;
        st.pipelineGen++;
    }

    public final void minSampleShading(float value) {
        final GlState st = st();
        st.minSampleShading = Math.max(0f, Math.min(1f, value));
        st.pipelineGen++;
    }

    /** The samples a draw may touch, from the sample mask and sample coverage. */
    public static int coverageMask(GlState st, int samples) {
        final int all = (1 << samples) - 1;
        if (samples <= 1 || !st.multisample) return all;
        int mask = all;
        if (st.sampleMaskEnabled) mask &= st.sampleMaskWord;
        if (st.sampleCoverage) {
            final int bits = (1 << Math.round(st.sampleCoverageValue * samples)) - 1;
            mask &= st.sampleCoverageInvert ? ~bits : bits;
        }
        return mask;
    }

    public void blendFunc(int sfactor, int dfactor) {
        blendFuncSeparate(sfactor, dfactor, sfactor, dfactor);
    }

    public void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        final GlState st = st();
        boolean changed = false;
        for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) {
            if (st.blendSrcRgb[i] != srcRGB || st.blendDstRgb[i] != dstRGB || st.blendSrcA[i] != srcAlpha || st.blendDstA[i] != dstAlpha) changed = true;
            st.blendSrcRgb[i] = srcRGB;
            st.blendDstRgb[i] = dstRGB;
            st.blendSrcA[i] = srcAlpha;
            st.blendDstA[i] = dstAlpha;
        }
        if (changed) st.pipelineGen++;
    }

    public void blendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (buf < 0 || buf >= GlState.MAX_DRAW_BUFFERS) return;
        final GlState st = st();
        if (st.blendSrcRgb[buf] == srcRGB && st.blendDstRgb[buf] == dstRGB && st.blendSrcA[buf] == srcAlpha && st.blendDstA[buf] == dstAlpha) return;
        st.blendSrcRgb[buf] = srcRGB;
        st.blendDstRgb[buf] = dstRGB;
        st.blendSrcA[buf] = srcAlpha;
        st.blendDstA[buf] = dstAlpha;
        st.pipelineGen++;
    }

    public void blendEquation(int mode) {
        blendEquationSeparate(mode, mode);
    }

    public void blendEquationSeparatei(int buf, int modeRGB, int modeAlpha) {
        if (buf < 0 || buf >= GlState.MAX_DRAW_BUFFERS) return;
        final GlState st = st();
        if (st.blendEqRgb[buf] == modeRGB && st.blendEqA[buf] == modeAlpha) return;
        st.blendEqRgb[buf] = modeRGB;
        st.blendEqA[buf] = modeAlpha;
        st.pipelineGen++;
    }

    public boolean isEnabledi(int cap, int index) {
        if (cap == GL11.GL_SCISSOR_TEST && index >= 0 && index < GlState.MAX_VIEWPORTS) return st().indexedScissorTest[index];
        return cap == GL11.GL_BLEND && index >= 0 && index < GlState.MAX_DRAW_BUFFERS ? st().blend[index] : isEnabled(cap);
    }

    public int renderbufferParameter(int renderbuffer, int pname) {
        final T t = textures().renderbuffer(renderbuffer);
        if (t == null || !t.hasImage()) return pname == GL30.GL_RENDERBUFFER_INTERNAL_FORMAT ? GL11.GL_RGBA4 : 0;
        return switch (pname) {
            case GL30.GL_RENDERBUFFER_WIDTH -> t.width;
            case GL30.GL_RENDERBUFFER_HEIGHT -> t.height;
            case GL30.GL_RENDERBUFFER_INTERNAL_FORMAT -> t.format.glInternal();
            case GL30.GL_RENDERBUFFER_SAMPLES -> t.samples > 1 ? t.samples : 0;
            case GL30.GL_RENDERBUFFER_RED_SIZE -> t.format.r();
            case GL30.GL_RENDERBUFFER_GREEN_SIZE -> t.format.g();
            case GL30.GL_RENDERBUFFER_BLUE_SIZE -> t.format.b();
            case GL30.GL_RENDERBUFFER_ALPHA_SIZE -> t.format.a();
            case GL30.GL_RENDERBUFFER_DEPTH_SIZE -> t.format.depthBits();
            case GL30.GL_RENDERBUFFER_STENCIL_SIZE -> t.format.stencilBits();
            default -> 0;
        };
    }

    public void blendEquationSeparate(int modeRGB, int modeAlpha) {
        final GlState st = st();
        boolean changed = false;
        for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) {
            if (st.blendEqRgb[i] != modeRGB || st.blendEqA[i] != modeAlpha) changed = true;
            st.blendEqRgb[i] = modeRGB;
            st.blendEqA[i] = modeAlpha;
        }
        if (changed) st.pipelineGen++;
    }

    public void blendColor(float red, float green, float blue, float alpha) {
        final GlState st = st();
        if (st.blendColor[0] == red && st.blendColor[1] == green && st.blendColor[2] == blue && st.blendColor[3] == alpha) return;
        st.blendColor[0] = red;
        st.blendColor[1] = green;
        st.blendColor[2] = blue;
        st.blendColor[3] = alpha;
        st.dynamicGen++;
    }

    public void depthFunc(int func) {
        final GlState st = st();
        if (st.depthFunc != func) {
            st.depthFunc = func;
            st.pipelineGen++;
        }
    }

    public void depthMask(boolean flag) {
        final GlState st = st();
        if (st.depthMask != flag) {
            st.depthMask = flag;
            st.pipelineGen++;
        }
    }

    public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        final GlState st = st();
        boolean changed = false;
        for (int i = 0; i < GlState.MAX_DRAW_BUFFERS; i++) {
            final boolean[] m = st.colorMask[i];
            if (m[0] != red || m[1] != green || m[2] != blue || m[3] != alpha) changed = true;
            m[0] = red;
            m[1] = green;
            m[2] = blue;
            m[3] = alpha;
        }
        if (changed) st.pipelineGen++;
    }

    public void colorMaski(int buf, boolean red, boolean green, boolean blue, boolean alpha) {
        if (buf < 0 || buf >= GlState.MAX_DRAW_BUFFERS) return;
        final GlState st = st();
        final boolean[] m = st.colorMask[buf];
        if (m[0] == red && m[1] == green && m[2] == blue && m[3] == alpha) return;
        m[0] = red;
        m[1] = green;
        m[2] = blue;
        m[3] = alpha;
        st.pipelineGen++;
    }

    public void cullFace(int mode) {
        final GlState st = st();
        if (st.cullFace != mode) {
            st.cullFace = mode;
            st.pipelineGen++;
        }
    }

    public void frontFace(int mode) {
        final GlState st = st();
        if (st.frontFace != mode) {
            st.frontFace = mode;
            st.pipelineGen++;
        }
    }

    public void polygonMode(int face, int mode) {
        final GlState st = st();
        final int front = face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK ? mode : st.polygonModeFront;
        final int back = face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK ? mode : st.polygonModeBack;
        if (front == st.polygonModeFront && back == st.polygonModeBack) return;
        st.polygonModeFront = front;
        st.polygonModeBack = back;
        st.pipelineGen++;
    }

    public void polygonOffset(float factor, float units) {
        final GlState st = st();
        if (st.polyFactor == factor && st.polyUnits == units && st.polyClamp == 0f) return;
        st.polyFactor = factor;
        st.polyUnits = units;
        st.polyClamp = 0f;
        st.dynamicGen++;
    }

    public void polygonOffsetClamp(float factor, float units, float clamp) {
        final GlState st = st();
        if (st.polyFactor == factor && st.polyUnits == units && st.polyClamp == clamp) return;
        st.polyFactor = factor;
        st.polyUnits = units;
        st.polyClamp = clamp;
        st.dynamicGen++;
    }

    public void stencilFunc(int func, int ref, int mask) {
        stencilFuncSeparate(GL11.GL_FRONT_AND_BACK, func, ref, mask);
    }

    public void stencilOp(int sfail, int dpfail, int dppass) {
        stencilOpSeparate(GL11.GL_FRONT_AND_BACK, sfail, dpfail, dppass);
    }

    public void stencilMask(int mask) {
        stencilMaskSeparate(GL11.GL_FRONT_AND_BACK, mask);
    }

    public void stencilFuncSeparate(int face, int func, int ref, int mask) {
        final GlState st = st();
        boolean dynamicChanged = false;
        for (int f = 0; f < 2; f++) {
            if (!faceMatches(face, f)) continue;
            if (st.stencilFunc[f] != func) st.pipelineGen++;
            if (st.stencilRef[f] != ref || st.stencilValueMask[f] != mask) dynamicChanged = true;
            st.stencilFunc[f] = func;
            st.stencilRef[f] = ref;
            st.stencilValueMask[f] = mask;
        }
        if (dynamicChanged) st.dynamicGen++;
    }

    public void stencilOpSeparate(int face, int sfail, int dpfail, int dppass) {
        final GlState st = st();
        boolean changed = false;
        for (int f = 0; f < 2; f++) {
            if (!faceMatches(face, f)) continue;
            if (st.stencilFail[f] != sfail || st.stencilDepthFail[f] != dpfail || st.stencilPass[f] != dppass) changed = true;
            st.stencilFail[f] = sfail;
            st.stencilDepthFail[f] = dpfail;
            st.stencilPass[f] = dppass;
        }
        if (changed) st.pipelineGen++;
    }

    public void stencilMaskSeparate(int face, int mask) {
        final GlState st = st();
        boolean changed = false;
        for (int f = 0; f < 2; f++) {
            if (!faceMatches(face, f) || st.stencilWriteMask[f] == mask) continue;
            st.stencilWriteMask[f] = mask;
            changed = true;
        }
        if (changed) st.dynamicGen++;
    }

    protected static boolean faceMatches(int face, int f) {
        return face == GL11.GL_FRONT_AND_BACK || face == GL11.GL_FRONT && f == 0 || face == GL11.GL_BACK && f == 1;
    }

    public void viewport(int x, int y, int width, int height) {
        final GlState st = st();
        if (st.viewportsVary) {
            Arrays.fill(st.indexedViewports, null);
            st.viewportsVary = false;
            st.dynamicGen++;
        }
        if (st.vpX == x && st.vpY == y && st.vpW == width && st.vpH == height) return;
        st.vpX = x;
        st.vpY = y;
        st.vpW = width;
        st.vpH = height;
        st.dynamicGen++;
    }

    public void depthRange(double nearVal, double farVal) {
        final GlState st = st();
        if (st.depthRangesVary) {
            Arrays.fill(st.indexedDepthRanges, null);
            st.depthRangesVary = false;
            st.dynamicGen++;
        }
        if (st.depthNear == nearVal && st.depthFar == farVal) return;
        st.depthNear = nearVal;
        st.depthFar = farVal;
        st.dynamicGen++;
    }

    public void scissor(int x, int y, int width, int height) {
        final GlState st = st();
        if (st.scissorsVary) {
            Arrays.fill(st.indexedScissors, null);
            st.scissorsVary = false;
            st.dynamicGen++;
        }
        if (st.scX == x && st.scY == y && st.scW == width && st.scH == height) return;
        st.scX = x;
        st.scY = y;
        st.scW = width;
        st.scH = height;
        st.dynamicGen++;
    }

    public void clearColor(float red, float green, float blue, float alpha) {
        final GlState st = st();
        st.clearColor[0] = red;
        st.clearColor[1] = green;
        st.clearColor[2] = blue;
        st.clearColor[3] = alpha;
    }

    public void clearDepth(double depth) {
        st().clearDepth = depth;
    }

    public void clearStencil(int s) {
        st().clearStencil = s;
    }

    public void clear(int mask) {
        if (st().conditionalSkip) return;
        if ((mask & GL11.GL_ACCUM_BUFFER_BIT) != 0) GlLegacy.clearAccum();
        clearTargets(st(), mask & ~GL11.GL_ACCUM_BUFFER_BIT);
    }

    public void lineWidth(float width) {
        final GlState st = st();
        if (st.lineWidth == width) return;
        st.lineWidth = width;
        st.dynamicGen++;
    }

    public void pointSize(float size) {
        st().pointSize = size;
    }

    /** Vulkan's GLSL has no gl_NumSamples, so shaders read it from a uniform of their own. */
    public final void applySampleCount(GlState st) {
        final P drawing = programs().current(st);
        if (drawing == null || !drawing.linked || !drawing.locations.containsKey(ProgramBase.NUM_SAMPLES_UNIFORM)) return;
        MemoryUtil.memPutInt(scratch, Math.max(1, framebufferSamples(framebuffers.get(st.drawFbo))));
        programs().uniform(drawing, drawing.locations.getInt(ProgramBase.NUM_SAMPLES_UNIFORM), 1, 1, false, scratch);
    }

    /** Shaders read the size from a uniform of their own, set ahead of whatever can rasterize as points and after a pipeline's uniforms arrive. */
    public final void applyPointSize(GlState st, int glMode) {
        if (glMode != GL11.GL_POINTS && st.polygonModeFront != GL11.GL_POINT) return;
        final P drawing = programs().current(st);
        if (drawing != null && drawing.linked && drawing.locations.containsKey(ProgramBase.POINT_ORIGIN_UNIFORM)) {
            MemoryUtil.memPutFloat(scratch, st.pointOriginLower ? 0f : 1f);
            programs().uniform(drawing, drawing.locations.getInt(ProgramBase.POINT_ORIGIN_UNIFORM), 1, 1, true, scratch);
        }
        final float limit = gpuCaps().pointSizeMax;
        if (st.pointSize > limit || limit <= 1f) warnOnce("point sizes above " + limit + " are not available on " + apiName());
        final P p = programs().current(st);
        if (p == null || !p.linked) return;
        final int location = p.locations.getInt(ProgramBase.POINT_SIZE_UNIFORM);
        if (location < 0) return;
        MemoryUtil.memPutFloat(scratch, Math.min(st.pointSize, limit));
        programs().uniform(p, location, 1, 1, true, scratch);
    }

    /** Only the index each index type reserves can restart a primitive on the native APIs. */
    protected final void checkRestartIndex(GlState st, int type) {
        if (!st.primitiveRestart || st.primitiveRestartFixed) return;
        final int reserved = type == GL11.GL_UNSIGNED_BYTE ? 0xFF : type == GL11.GL_UNSIGNED_SHORT ? 0xFFFF : -1;
        if (st.primitiveRestartIndex != reserved) warnOnce("primitive restart index " + Integer.toUnsignedString(st.primitiveRestartIndex) + " is not the one " + apiName() + " restarts on");
    }

    public void logicOp(int opcode) {
        final GlState st = st();
        if (st.logicOp == opcode) return;
        st.logicOp = opcode;
        st.pipelineGen++;
    }

    public void hint(int target, int mode) {
    }

    public void provokingVertex(int provokeMode) {
        final GlState st = st();
        final boolean first = provokeMode == GL32.GL_FIRST_VERTEX_CONVENTION;
        if (st.provokingFirst == first) return;
        st.provokingFirst = first;
        st.pipelineGen++;
    }

    public void primitiveRestartIndex(int index) {
        st().primitiveRestartIndex = index;
    }

    public void pointParameterf(int pname, float param) {
        if (pname == GL20.GL_POINT_SPRITE_COORD_ORIGIN) st().pointOriginLower = param == GL20.GL_LOWER_LEFT;
    }

    public void pointParameteri(int pname, int param) {
        if (pname == GL20.GL_POINT_SPRITE_COORD_ORIGIN) st().pointOriginLower = param == GL20.GL_LOWER_LEFT;
    }

    public void sampleCoverage(float value, boolean invert) {
        final GlState st = st();
        st.sampleCoverageValue = Math.max(0f, Math.min(1f, value));
        st.sampleCoverageInvert = invert;
        st.pipelineGen++;
    }

    public void drawArrays(int mode, int first, int count) {
        drawArraysInstanced(mode, first, count, 1);
    }

    /** A loop's closing line has its provoking vertex at the start, which a strip cannot say, so one with flat inputs is drawn as separate lines. */
    private boolean loopAsLines(GlState st, int mode) {
        if (mode != GL11.GL_LINE_LOOP || flatShadesFromFirstVertex(st) == st.provokingFirst) return false;
        final P p = programs().current(st);
        return p != null && p.flatInputs;
    }

    /** Which end of a line a device reads a flat input from only matters to a program that has one, and turning a line round can move which end pixel it covers. */
    private boolean lineTurned(GlState st) {
        final P p = programs().current(st);
        return p != null && p.flatInputs && flatShadesFromFirstVertex(st) != st.provokingFirst;
    }

    protected final int convertedMode(GlState st, int mode) {
        return switch (mode) {
            case GL11.GL_LINE_LOOP -> loopAsLines(st, mode) ? GL11.GL_LINES : GL11.GL_LINE_STRIP;
            case GL11.GL_LINES, GL11.GL_LINE_STRIP, GL32.GL_LINES_ADJACENCY, GL32.GL_LINE_STRIP_ADJACENCY -> GL11.GL_LINES;
            default -> GL11.GL_TRIANGLES;
        };
    }

    public final boolean sampledComplete(GlState st, int unit, TexBase t) {
        final SamplerObj sampler = samplerObjects.get(st.samplers[unit]);
        return t.completeFor(sampler != null ? sampler.minFilter : t.minFilter);
    }

    /** With no geometry shader to read them, a primitive's adjacent vertices are skipped, so those modes draw as what is left. */
    protected final boolean adjacencyToDrop(GlState st, int mode) {
        if (mode < GL32.GL_LINES_ADJACENCY || mode > GL32.GL_TRIANGLE_STRIP_ADJACENCY) return false;
        final P p = programs().current(st);
        return p == null || !hasStage(p, GL32.GL_GEOMETRY_SHADER);
    }

    private boolean hasStage(P p, int type) {
        for (Shader s : p.linkedShaders) {
            if (s.type == type) return true;
        }
        return false;
    }

    /** A device has one flat shading convention, so a program taking a flat input has its primitives reordered to bring GL's vertex to where the device looks. */
    protected final boolean flatOrderNeedsTurning(GlState st, int mode) {
        if (mode != GL11.GL_TRIANGLES && mode != GL11.GL_TRIANGLE_STRIP && mode != GL11.GL_LINES && mode != GL11.GL_LINE_STRIP) return false;
        if (flatShadesFromFirstVertex(st) == st.provokingFirst) return false;
        final P p = programs().current(st);
        return p != null && p.flatInputs;
    }

    private int[] indexPattern = new int[1024];

    /** How many indices {@link #convertIndices} makes of a run of this many vertices. */
    private int convertedLength(int mode, int count) {
        return switch (mode) {
            case GL11.GL_LINE_LOOP -> count < 2 ? 0 : loopAsLines(st(), mode) ? count * 2 : count + 1;
            case GL11.GL_TRIANGLE_FAN, GL11.GL_POLYGON -> count < 3 ? 0 : (count - 2) * 3;
            case GL11.GL_TRIANGLES -> count / 3 * 3;
            case GL11.GL_TRIANGLE_STRIP -> count < 3 ? 0 : (count - 2) * 3;
            case GL11.GL_LINES -> count / 2 * 2;
            case GL11.GL_LINE_STRIP -> count < 2 ? 0 : (count - 1) * 2;
            case GL32.GL_LINES_ADJACENCY -> count / 4 * 2;
            case GL32.GL_LINE_STRIP_ADJACENCY -> count < 4 ? 0 : (count - 3) * 2;
            case GL32.GL_TRIANGLES_ADJACENCY -> count / 6 * 3;
            case GL32.GL_TRIANGLE_STRIP_ADJACENCY -> count < 6 ? 0 : (count / 2 - 2) * 3;
            case GL11.GL_QUADS -> count / 4 * 6;
            case GL11.GL_QUAD_STRIP -> count < 4 ? 0 : (count - 2) / 2 * 6;
            default -> 0;
        };
    }

    /**
     * Primitives the native APIs lack are drawn as index lists over the original vertices. Writes the list for this mode
     * into {@link #indexPattern}, which is reused because immediate-mode quads come through here constantly, and returns its length.
     */
    protected final int convertIndices(int mode, int count) {
        final int length = convertedLength(mode, count);
        if (length > indexPattern.length) indexPattern = new int[Integer.highestOneBit(length) * 2];
        final int[] out = indexPattern;
        final GlState st = st();
        final boolean glFirst = st.provokingFirst, deviceFirst = flatShadesFromFirstVertex(st);
        switch (mode) {
            case GL11.GL_LINE_LOOP -> {
                if (loopAsLines(st, mode)) {
                    for (int i = 0; i < count; i++) putLine(out, i * 2, i, (i + 1) % count, true);
                } else {
                    for (int i = 0; i < length - 1; i++) out[i] = i;
                    if (length > 0) out[length - 1] = 0;
                }
            }
            case GL11.GL_TRIANGLES -> {
                for (int i = 0; i < length / 3; i++) putTriangle(out, i * 3, i * 3, i * 3 + 1, i * 3 + 2, glFirst ? i * 3 : i * 3 + 2, deviceFirst);
            }
            case GL11.GL_TRIANGLE_STRIP -> {
                for (int i = 0; i < length / 3; i++) {
                    final boolean odd = (i & 1) != 0;
                    putTriangle(out, i * 3, odd ? i + 1 : i, odd ? i : i + 1, i + 2, glFirst ? i : i + 2, deviceFirst);
                }
            }
            case GL11.GL_LINES -> {
                for (int i = 0; i < length / 2; i++) putLine(out, i * 2, i * 2, i * 2 + 1, lineTurned(st));
            }
            case GL11.GL_LINE_STRIP -> {
                for (int i = 0; i < length / 2; i++) putLine(out, i * 2, i, i + 1, lineTurned(st));
            }
            case GL32.GL_LINES_ADJACENCY -> {
                for (int i = 0; i < length / 2; i++) putLine(out, i * 2, i * 4 + 1, i * 4 + 2, lineTurned(st));
            }
            case GL32.GL_LINE_STRIP_ADJACENCY -> {
                for (int i = 0; i < length / 2; i++) putLine(out, i * 2, i + 1, i + 2, lineTurned(st));
            }
            case GL32.GL_TRIANGLES_ADJACENCY -> {
                for (int i = 0; i < length / 3; i++) putTriangle(out, i * 3, i * 6, i * 6 + 2, i * 6 + 4, glFirst ? i * 6 : i * 6 + 4, deviceFirst);
            }
            case GL32.GL_TRIANGLE_STRIP_ADJACENCY -> {
                for (int i = 0; i < length / 3; i++) {
                    final boolean odd = (i & 1) != 0;
                    final int a = i * 2, b = i * 2 + 2, c = i * 2 + 4;
                    putTriangle(out, i * 3, odd ? b : a, odd ? a : b, c, glFirst ? (odd ? b : a) : c, deviceFirst);
                }
            }
            case GL11.GL_TRIANGLE_FAN -> {
                for (int i = 0; i < length / 3; i++) putTriangle(out, i * 3, 0, i + 1, i + 2, glFirst ? i + 1 : i + 2, deviceFirst);
            }
            case GL11.GL_POLYGON -> {
                for (int i = 0; i < length / 3; i++) putTriangle(out, i * 3, 0, i + 1, i + 2, 0, deviceFirst);
            }
            case GL11.GL_QUADS -> {
                for (int q = 0; q < length / 6; q++) {
                    final int b = q * 4;
                    // Split along a diagonal through the vertex that colors the whole quad.
                    if (glFirst) {
                        putTriangle(out, q * 6, b, b + 1, b + 2, b, deviceFirst);
                        putTriangle(out, q * 6 + 3, b, b + 2, b + 3, b, deviceFirst);
                    } else {
                        putTriangle(out, q * 6, b, b + 1, b + 3, b + 3, deviceFirst);
                        putTriangle(out, q * 6 + 3, b + 1, b + 2, b + 3, b + 3, deviceFirst);
                    }
                }
            }
            case GL11.GL_QUAD_STRIP -> {
                for (int q = 0; q < length / 6; q++) {
                    final int b = q * 2, provoking = glFirst ? b : b + 3;
                    putTriangle(out, q * 6, b, b + 1, b + 3, provoking, deviceFirst);
                    putTriangle(out, q * 6 + 3, b, b + 3, b + 2, provoking, deviceFirst);
                }
            }
            default -> { }
        }
        return length;
    }

    private static void putLine(int[] out, int at, int a, int b, boolean swap) {
        out[at] = swap ? b : a;
        out[at + 1] = swap ? a : b;
    }

    /** Writes a triangle turned, keeping its winding, so the vertex flat shading should use is where the device takes it from. */
    private static void putTriangle(int[] out, int at, int a, int b, int c, int provoking, boolean deviceFirst) {
        final boolean inPlace = provoking == (deviceFirst ? a : c), turnOnce = provoking == (deviceFirst ? b : a);
        out[at] = inPlace ? a : turnOnce ? b : c;
        out[at + 1] = inPlace ? b : turnOnce ? c : a;
        out[at + 2] = inPlace ? c : turnOnce ? a : b;
    }

    /** Copies the list {@link #convertIndices} just made into the ring at its current allocation. */
    protected final void putConvertedIndices(HostRingBase<?> ring, int length) {
        for (int i = 0; i < length; i++) MemoryUtil.memPutInt(ring.address + i * 4L, indexPattern[i]);
    }

    /** Largest buffer given a copy of its contents; a draw from anything bigger waits out queued writes instead. */
    private static final long MAX_INDEX_SHADOW = 16L * 1024L * 1024L;

    /**
     * Remembers the contents of an index buffer that a draw just had to wait for. Staged uploads into it then update this copy too, so the next
     * conversion reads what the API has written without draining every frame in flight for a copy the device has not run yet.
     */
    private void seedIndexShadow(B ebo) {
        if (ebo.size > MAX_INDEX_SHADOW || ebo.mapped || ebo.persistentlyMapped) return;
        if (ebo.indexShadowSize != ebo.size) {
            ebo.freeIndexShadow();
            ebo.indexShadow = MemoryUtil.nmemAlloc(Math.max(1L, ebo.size));
            ebo.indexShadowSize = ebo.size;
        }
        MemoryUtil.memCopy(ebo.address(), ebo.indexShadow, ebo.size);
        ebo.indexShadowVersion = ebo.contentVersion;
        ebo.indexShadowGpuWrites = ebo.gpuWrites;
    }

    /** Whether a copy can be applied to the destination's index shadow now: the source's bytes are already what the device will copy. */
    private boolean copyFollowsIntoIndexShadow(B read, B write, long readOffset, long writeOffset, long size) {
        return write != null && read != null && read != write && write.indexShadowCurrent() && read.hasStorage() && write.hasStorage() && read.hostVisible() && isIdle(read.lastGpuWrite)
            && size > 0 && readOffset >= 0 && writeOffset >= 0 && readOffset + size <= read.size && writeOffset + size <= write.size;
    }

    /** Writes a converted primitive mode's index list into the ring. Returns its length, or 0 when there is nothing to draw. */
    protected final int writeConvertedIndices(GlState st, int mode, int count, int type, long indices, HostRingBase<?> ring) {
        if (st.primitiveRestart) warnOnce("primitive restart is not applied to quads, polygons, line loops or converted fans");
        final B ebo = buffers().get(st.vao.elementBuffer);
        final int bytes = indexBytes(type);
        final int length = convertIndices(mode, count);
        if (length == 0) return 0;
        if (st.vao.elementBuffer != 0 && (ebo == null || !ebo.hasStorage())) return 0;
        long src = indices;
        long copied = 0L;
        // GL leaves indices past the buffer's end undefined, so they draw as vertex 0 rather than reading memory the buffer does not own.
        long readable = Long.MAX_VALUE;
        if (ebo != null && ebo.hasStorage()) {
            readable = indices < 0 ? 0L : Math.max(0L, (ebo.size - indices) / bytes);
            if (ebo.hostVisible()) {
                if (ebo.indexShadowCurrent()) {
                    src = ebo.indexShadow + indices;
                } else {
                    if (!isIdle(ebo.lastGpuWrite)) {
                        flushAndWait(ebo.lastGpuWrite);
                        seedIndexShadow(ebo);
                    }
                    src = ebo.address() + indices;
                }
            } else {
                final long size = Math.min(count, readable) * bytes;
                copied = MemoryUtil.nmemAlloc(Math.max(1L, size));
                if (size > 0L) buffers().read(ebo, indices, size, copied);
                src = copied;
            }
        }
        if (src == 0L) return 0;
        ring.alloc(length * 4L, 4);
        for (int i = 0; i < length; i++) {
            if (indexPattern[i] >= readable) {
                MemoryUtil.memPutInt(ring.address + i * 4L, 0);
                continue;
            }
            final long at = src + (long) indexPattern[i] * bytes;
            final int v = switch (bytes) {
                case 1 -> MemoryUtil.memGetByte(at) & 0xFF;
                case 2 -> MemoryUtil.memGetShort(at) & 0xFFFF;
                default -> MemoryUtil.memGetInt(at);
            };
            MemoryUtil.memPutInt(ring.address + i * 4L, v);
        }
        if (copied != 0L) MemoryUtil.nmemFree(copied);
        return length;
    }

    protected static int indexBytes(int type) {
        return switch (type) {
            case GL11.GL_UNSIGNED_BYTE -> 1;
            case GL11.GL_UNSIGNED_SHORT -> 2;
            default -> 4;
        };
    }

    public void drawElements(int mode, int count, int type, long indices) {
        drawElementsInstancedBaseVertex(mode, count, type, indices, 1, 0);
    }

    public void drawElements(int mode, ByteBuffer indices) {
        drawElements(mode, indices.remaining(), GL11.GL_UNSIGNED_BYTE, memAddress(indices));
    }

    public void drawElements(int mode, IntBuffer indices) {
        drawElements(mode, indices.remaining(), GL11.GL_UNSIGNED_INT, memAddress(indices));
    }

    public void drawElements(int mode, ShortBuffer indices) {
        drawElements(mode, indices.remaining(), GL11.GL_UNSIGNED_SHORT, memAddress(indices));
    }

    public void drawElements(int mode, int count, int type, ByteBuffer indices) {
        drawElements(mode, count, type, memAddress(indices));
    }

    public void drawElementsInstanced(int mode, int count, int type, long indices, int primcount) {
        drawElementsInstancedBaseVertex(mode, count, type, indices, primcount, 0);
    }

    public void drawElementsBaseVertex(int mode, int count, int type, long indices, int baseVertex) {
        drawElementsInstancedBaseVertex(mode, count, type, indices, 1, baseVertex);
    }

    public void drawBuffer(int mode) {
        final GlState st = st();
        final Fbo f = framebuffers.get(st.drawFbo);
        if (f == null) return;
        if (f.id == 0) {
            st.defaultDrawNone = mode == GL11.GL_NONE;
            targetsChanged();
            return;
        }
        f.drawBuffers = new int[] {mode == GL11.GL_NONE ? -1 : Fbo.attachmentIndex(mode)};
        f.gen++;
        targetsChanged();
    }

    public void drawBuffers(int buffer) {
        drawBuffer(buffer);
    }

    public void drawBuffers(IntBuffer bufs) {
        final GlState st = st();
        namedDrawBuffers(framebuffers.get(st.drawFbo), bufs, st);
    }

    protected void namedDrawBuffers(Fbo f, IntBuffer bufs, GlState st) {
        if (f == null) return;
        final int n = bufs.remaining();
        if (n > getInteger(GL20.GL_MAX_DRAW_BUFFERS)) {
            warnOnce("glDrawBuffers with " + n + " buffers exceeds GL_MAX_DRAW_BUFFERS");
            recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        if (f.id == 0) {
            st.defaultDrawNone = n == 0 || bufs.get(bufs.position()) == GL11.GL_NONE;
            targetsChanged();
            return;
        }
        final int[] map = new int[Math.min(n, Fbo.MAX_COLOR)];
        for (int i = 0; i < map.length; i++) {
            final int b = bufs.get(bufs.position() + i);
            map[i] = b == GL11.GL_NONE ? -1 : Fbo.attachmentIndex(b);
        }
        f.drawBuffers = map;
        f.gen++;
        targetsChanged();
    }

    public void readBuffer(int mode) {
        final Fbo f = framebuffers.get(st().readFbo);
        if (f != null) setReadBuffer(f, mode);
    }

    private void setReadBuffer(Fbo f, int mode) {
        if (f.id == 0) st().defaultReadNone = mode == GL11.GL_NONE;
        else f.readBuffer = mode == GL11.GL_NONE ? -1 : Fbo.attachmentIndex(mode);
    }

    public void memoryBarrier(int barriers) {
        computeBarrierPending();
        writeBackBufferTextures();
        textures().writeBackSliceImages();
    }

    public int genTextures() {
        return textures().gen();
    }

    public void genTextures(IntBuffer out) {
        for (int i = out.position(); i < out.limit(); i++) out.put(i, textures().gen());
    }

    public int createTextures(int target) {
        final int id = textures().gen();
        final T t = textures().getOrCreate(id, target);
        if (t != null) t.named = true;
        return id;
    }

    public int createRenderbuffer() {
        final int id = genRenderbuffers();
        textures().renderbuffer(id).named = true;
        return id;
    }

    public int createVertexArray() {
        final int id = genVertexArrays();
        vaos.get(id).named = true;
        return id;
    }

    /** glCreateQueries makes the object at once, keeping the target it was created for. */
    public int createQuery(int target) {
        final int id = genQueries();
        queryResults.putIfAbsent(id, 0L);
        queryTargets.put(id, target);
        return id;
    }

    public void textureMultisample(int texture, int samples, int internalFormat, int width, int height, int layers) {
        final T t = textures().get(texture);
        if (t == null) return;
        if (t.immutable) {
            recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        textures().setSamples(t, multisampledTargetSamples(samples));
        if (textures().specify(t, textures().format(internalFormat), 0, width, height, 1, layers)) t.immutable = true;
    }

    public void deleteTextures(int texture) {
        samplingVersion++;
        if (texture == 0) return;
        textures().delete(texture);
        bufferTextures.remove(texture);
        unbindTextureEverywhere(texture);
    }

    protected void unbindTextureEverywhere(int id) {
        unbindTexture(mainState, id);
        for (GlState s : otherStates) unbindTexture(s, id);
    }

    protected static void unbindTexture(GlState s, int id) {
        for (int u = 0; u < GlState.MAX_UNITS; u++) {
            for (int t = 0; t < GlState.T_COUNT; t++) if (s.textures[u][t] == id) s.textures[u][t] = 0;
        }
        for (int i = 0; i < GlState.MAX_IMAGE_UNITS; i++) if (s.imageTex[i] == id) s.imageTex[i] = 0;
    }

    public void shaderBinary(int count, long shaders, long binary, int length) {
        final int[] words = new int[length / 4];
        for (int i = 0; i < words.length; i++) words[i] = MemoryUtil.memGetInt(binary + i * 4L);
        for (int i = 0; i < count; i++) programs().shaderBinary(programs().shader(MemoryUtil.memGetInt(shaders + i * 4L)), words);
    }

    public void specializeShader(int shader, CharSequence entryPoint, IntBuffer constantIndex, IntBuffer constantValue) {
        final int count = constantIndex == null ? 0 : constantIndex.remaining();
        final int[] ids = new int[count], values = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = constantIndex.get(constantIndex.position() + i);
            values[i] = constantValue.get(constantValue.position() + i);
        }
        final int error = programs().specialize(programs().shader(shader), entryPoint.toString(), ids, values);
        if (error != GL11.GL_NO_ERROR) recordError(error);
    }

    public boolean isTexture(int texture) {
        final T t = textures().get(texture);
        return t != null && t.named;
    }

    public void bindTexture(int target, int texture) {
        final GlState st = st();
        final int slot = GlState.targetIndex(target);
        if (slot < 0) return;
        if (texture != 0) textures().getOrCreate(texture, target).named = true;
        st.textures[st.activeUnit][slot] = texture;
    }

    public void bindTextureUnit(int unit, int texture) {
        final GlState st = st();
        if (unit < 0 || unit >= GlState.MAX_UNITS) return;
        final T t = textures().get(texture);
        if (texture == 0) {
            for (int i = 0; i < GlState.T_COUNT; i++) st.textures[unit][i] = 0;
            return;
        }
        if (t == null) return;
        final int slot = GlState.targetIndex(t.target == 0 ? GL11.GL_TEXTURE_2D : t.target);
        if (slot >= 0) st.textures[unit][slot] = texture;
    }

    public void activeTexture(int texture) {
        final int unit = texture - GL13.GL_TEXTURE0;
        if (unit >= 0 && unit < GlState.MAX_UNITS) st().activeUnit = unit;
    }

    /** A null client pointer means the bound pixel unpack buffer, if there is one. */
    private void texImage2DFrom(int target, int level, int internalFormat, int width, int height, int format, int type, long address) {
        final GlState st = st();
        textures().texImage(st, target, level, internalFormat, width, height, 1, format, type, address, address == 0L && st.pixelUnpack != 0);
    }

    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        texImage2DFrom(target, level, internalFormat, width, height, format, type, pixels == null ? 0L : memAddress(pixels));
    }

    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, DoubleBuffer pixels) {
        texImage2DFrom(target, level, internalFormat, width, height, format, type, pixels == null ? 0L : memAddress(pixels));
    }

    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, FloatBuffer pixels) {
        texImage2DFrom(target, level, internalFormat, width, height, format, type, pixels == null ? 0L : memAddress(pixels));
    }

    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        texImage2DFrom(target, level, internalFormat, width, height, format, type, pixels == null ? 0L : memAddress(pixels));
    }

    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixelBufferOffset) {
        final GlState st = st();
        textures().texImage(st, target, level, internalFormat, width, height, 1, format, type, pixelBufferOffset, st.pixelUnpack != 0);
    }

    public void texImage1D(int target, int level, int internalFormat, int width, int border, int format, int type, ByteBuffer pixels) {
        final GlState st = st();
        textures().texImage(st, target, level, internalFormat, width, 1, 1, format, type, pixels == null ? 0L : memAddress(pixels), pixels == null && st.pixelUnpack != 0);
    }

    public void texImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type, ByteBuffer pixels) {
        final GlState st = st();
        textures().texImage(st, target, level, internalFormat, width, height, depth, format, type, pixels == null ? 0L : memAddress(pixels), pixels == null && st.pixelUnpack != 0);
    }

    public void texImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type, IntBuffer pixels) {
        final GlState st = st();
        textures().texImage(st, target, level, internalFormat, width, height, depth, format, type, pixels == null ? 0L : memAddress(pixels), pixels == null && st.pixelUnpack != 0);
    }

    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        if (pixels == null) return;
        textures().texSubImage(st(), target, level, xoffset, yoffset, 0, width, height, 1, format, type, memAddress(pixels), false);
    }

    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
        if (pixels == null) return;
        textures().texSubImage(st(), target, level, xoffset, yoffset, 0, width, height, 1, format, type, memAddress(pixels), false);
    }

    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pboOffset) {
        final GlState st = st();
        textures().texSubImage(st, target, level, xoffset, yoffset, 0, width, height, 1, format, type, pboOffset, st.pixelUnpack != 0);
    }

    public void texImageFromBuffer(int target, int level, int internalFormat, int width, int height, int depth, int format, int type, long offset) {
        textures().texImage(st(), target, level, internalFormat, width, height, depth, format, type, offset, true);
    }

    public void texSubImageFromBuffer(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, long offset) {
        textures().texSubImage(st(), target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, offset, true);
    }

    public void copyTexImage1D(int target, int level, int internalFormat, int x, int y, int width, int border) {
        copyTexImage2D(target, level, internalFormat, x, y, width, 1, border);
    }

    public void copyTexSubImage1D(int target, int level, int xoffset, int x, int y, int width) {
        copyTexSubImage2D(target, level, xoffset, 0, x, y, width, 1);
    }

    public void texSubImage1D(int target, int level, int xoffset, int width, int format, int type, ByteBuffer pixels) {
        if (pixels == null) return;
        textures().texSubImage(st(), target, level, xoffset, 0, 0, width, 1, 1, format, type, memAddress(pixels), false);
    }

    public void texSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer pixels) {
        if (pixels == null) return;
        textures().texSubImage(st(), target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, memAddress(pixels), false);
    }

    public void texStorage(int target, int levels, int internalFormat, int width, int height, int depth) {
        textures().texStorage(st(), target, levels, internalFormat, width, height, depth);
    }

    /**
     * The unpack state only reaches compressed data once a block's size and shape are set, and then says which blocks to take.
     * Gathers them into one tight run, which is what the rest of the path expects, and runs the upload on it.
     */
    private boolean uploadGatheredBlocks(Formats.Format f, int width, int height, int depth, long data, LongConsumer upload) {
        final GlState st = st();
        final PixelConvert.Store s = st.unpack;
        if (f == null || !f.isCompressed() || s.blockSize == 0 || s.blockWidth == 0 || s.blockHeight == 0 || data == 0L && st.pixelUnpack == 0) return false;
        final B pbo = st.pixelUnpack == 0 ? null : buffers().get(st.pixelUnpack);
        if (st.pixelUnpack != 0 && (pbo == null || !pbo.hostVisible())) return false;
        final int rowBlocks = (width + s.blockWidth - 1) / s.blockWidth, rows = (height + s.blockHeight - 1) / s.blockHeight;
        final int blockDepth = Math.max(1, s.blockDepth), images = (depth + blockDepth - 1) / blockDepth;
        final long rowBytes = (long) rowBlocks * s.blockSize;
        final long stride = s.rowLength > 0 ? (long) ((s.rowLength + s.blockWidth - 1) / s.blockWidth) * s.blockSize : rowBytes;
        final long imageRows = s.blockDepth > 0 && s.imageHeight > 0 ? (s.imageHeight + s.blockHeight - 1) / s.blockHeight : rows;
        final long source = pbo == null ? data : pbo.address() + data;
        final long first = source + (s.blockDepth > 0 ? (long) (s.skipImages / blockDepth) * imageRows * stride : 0L) + (long) (s.skipRows / s.blockHeight) * stride + (long) (s.skipPixels / s.blockWidth) * s.blockSize;
        if (pbo != null && rows > 0 && images > 0) {
            final long end = data + (first - source) + ((images - 1L) * imageRows + rows - 1L) * stride + rowBytes;
            if (data < 0L || end > pbo.size) {
                warnOnce("unpack PBO range out of bounds");
                recordError(GL11.GL_INVALID_OPERATION);
                return true;
            }
            if (!isIdle(pbo.lastGpuWrite)) flushAndWait(pbo.lastGpuWrite);
        }
        final long tight = MemoryUtil.nmemAlloc(rowBytes * rows * images);
        final int unpackBuffer = st.pixelUnpack, blockSize = s.blockSize;
        try {
            for (int image = 0; image < images; image++) {
                for (int row = 0; row < rows; row++) MemoryUtil.memCopy(first + (image * imageRows + row) * stride, tight + (image * (long) rows + row) * rowBytes, rowBytes);
            }
            st.pixelUnpack = 0;
            s.blockSize = 0;
            upload.accept(tight);
        } finally {
            st.pixelUnpack = unpackBuffer;
            s.blockSize = blockSize;
            MemoryUtil.nmemFree(tight);
        }
        return true;
    }

    public void compressedTexImage(int target, int level, int internalFormat, int width, int height, int depth, long data) {
        final GlState st = st();
        if (textures().knownInternalFormat(internalFormat) && uploadGatheredBlocks(textures().format(internalFormat), width, height, depth, data, tight -> compressedTexImage(target, level, internalFormat, width, height, depth, tight))) return;
        if (!textures().texImage(st, target, level, internalFormat, width, height, depth, 0, 0, data, st.pixelUnpack != 0)) return;
        final T t = textures().get(st.boundTexture(target));
        if (t != null && t.format != null && t.format.isCompressed() && st.pixelUnpack == 0) {
            retainedBlocks(t, retainedIndex(target, level), width, height, depth, true);
            if (data != 0L) writeRetained(t, target, level, 0, 0, 0, width, height, depth, width, height, data);
        }
    }

    public void compressedTexSubImage(int target, int level, int x, int y, int z, int width, int height, int depth, int format, long data) {
        compressedSubImageOf(st(), textures().get(st().boundTexture(target)), target, level, x, y, z, width, height, depth, format, data);
    }

    public void compressedTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth, int format, long data) {
        final T t = textures().get(texture);
        if (t != null) compressedSubImageOf(st(), t, t.target, level, x, y, z, width, height, depth, format, data);
    }

    private void compressedSubImageOf(GlState st, T t, int target, int level, int x, int y, int z, int width, int height, int depth, int format, long data) {
        if (t == null) return;
        if (level < 0 || level > MAX_TEXTURE_LEVEL) {
            recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        if (level >= t.levels) {
            recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (x < 0 || y < 0 || z < 0 || width < 0 || height < 0 || depth < 0 || (long) x + width > t.levelWidth(level) || (long) y + height > t.levelHeight(level)
            || (long) z + depth > retainedSlices(t, level)) {
            recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        if (uploadGatheredBlocks(t.format, width, height, depth, data, tight -> compressedTexSubImage(target, level, x, y, z, width, height, depth, format, tight))) return;
        textures().subImage(st, t, target, level, x, y, z, width, height, depth, format, 0, data, st.pixelUnpack != 0);
        if (t.format != null && t.format.isCompressed() && st.pixelUnpack == 0 && data != 0L) {
            writeRetained(t, target, level, x, y, z, width, height, depth, t.levelWidth(level), t.levelHeight(level), data);
        }
    }

    private static int retainedIndex(int target, int level) {
        return level * 6 + TexturesBase.cubeFace(target);
    }

    private byte[] retainedBlocks(TexBase t, int index, int width, int height, int depth, boolean create) {
        if (t.compressedLevels == null || t.compressedLevels.length <= index) {
            if (!create) return null;
            t.compressedLevels = t.compressedLevels == null ? new byte[index + 1][] : Arrays.copyOf(t.compressedLevels, index + 1);
        }
        final int size = (width + 3) / 4 * ((height + 3) / 4) * depth * t.format.blockBytes();
        if (create && (t.compressedLevels[index] == null || t.compressedLevels[index].length != size)) t.compressedLevels[index] = new byte[size];
        return t.compressedLevels[index];
    }

    /** How many slices one retained array holds: a cube map keeps each face in its own. */
    private int retainedSlices(TexBase t, int level) {
        return t.target == GL13.GL_TEXTURE_CUBE_MAP ? 1 : t.is3D() ? t.levelDepth(level) : t.layers;
    }

    private void writeRetained(T t, int target, int level, int x, int y, int z, int width, int height, int depth, int levelWidth, int levelHeight, long data) {
        retainAt(t, retainedIndex(target, level), level, x, y, z, width, height, depth, levelWidth, levelHeight, data);
    }

    /** Keeps blocks compressed from client pixels, which a later read of them returns. */
    public final void retainCompressed(TexBase t, int face, int level, int x, int y, int z, int width, int height, int depth, long data) {
        retainAt(t, level * 6 + face, level, x, y, z, width, height, depth, t.levelWidth(level), t.levelHeight(level), data);
    }

    private void retainAt(TexBase t, int index, int level, int x, int y, int z, int width, int height, int depth, int levelWidth, int levelHeight, long data) {
        final byte[] blocks = retainedBlocks(t, index, levelWidth, levelHeight, retainedSlices(t, level), true);
        final int block = t.format.blockBytes(), rowBlocks = (levelWidth + 3) / 4, columnBlocks = (levelHeight + 3) / 4;
        final int copyBlocks = (width + 3) / 4, copyRows = (height + 3) / 4;
        long from = data;
        for (int slice = z; slice < z + depth; slice++) {
            for (int row = y / 4; row < y / 4 + copyRows; row++) {
                final int to = (slice * columnBlocks * rowBlocks + row * rowBlocks + x / 4) * block;
                MemoryUtil.memByteBuffer(from, copyBlocks * block).get(blocks, to, copyBlocks * block);
                from += (long) copyBlocks * block;
            }
        }
    }

    public int textureTarget(int texture) {
        final T t = textures().get(texture);
        return t == null ? GL11.GL_TEXTURE_2D : t.target;
    }

    public void textureStorage(int texture, int levels, int internalFormat, int width, int height, int depth) {
        final T t = textures().get(texture);
        if (t != null) textures().storage(t, levels, internalFormat, width, height, depth);
    }

    public void textureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth, int format, int type, long data) {
        final GlState st = st();
        final T t = textures().get(texture);
        if (t != null) textures().subImage(st, t, t.target, level, x, y, z, width, height, depth, format, type, data, st.pixelUnpack != 0);
    }

    /** What the pass that clears by drawing cannot write: an integer attachment, and every layer of a layered one. */
    public final void clearAttachmentRegion(T t, int level, int layer, int layers, GlState st) {
        final int x0 = st.scissorTest ? Math.max(0, st.scX) : 0, y0 = st.scissorTest ? Math.max(0, st.scY) : 0;
        final int x1 = Math.min(t.levelWidth(level), st.scissorTest ? st.scX + st.scW : Integer.MAX_VALUE);
        final int y1 = Math.min(t.levelHeight(level), st.scissorTest ? st.scY + st.scH : Integer.MAX_VALUE);
        endPass();
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            final long value = stack.nmalloc(16);
            final boolean integer = t.format.isInteger();
            for (int c = 0; c < 4; c++) {
                if (integer) MemoryUtil.memPutInt(value + c * 4L, (int) st.clearColor[c]);
                else MemoryUtil.memPutFloat(value + c * 4L, st.clearColor[c]);
            }
            clearTexSubImage(t.id, level, x0, y0, layer, x1 - x0, y1 - y0, layers, integer ? GL30.GL_RGBA_INTEGER : GL11.GL_RGBA, integer ? GL11.GL_INT : GL11.GL_FLOAT, value);
        }
    }

    public void clearTexSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth, int format, int type, long data) {
        final T t = textures().get(texture);
        if (t == null || width <= 0 || height <= 0 || depth <= 0) return;
        final GlState st = st();
        final PixelConvert.Store saved = new PixelConvert.Store();
        saved.copyFrom(st.unpack);
        final int unpackBuffer = st.pixelUnpack;
        st.unpack.copyFrom(new PixelConvert.Store());
        st.unpack.alignment = 1;
        st.pixelUnpack = 0;
        final int texel = (int) PixelConvert.clientBytes(1, 1, 1, format, type, st.unpack);
        final long texels = (long) width * height * depth;
        final long fill = MemoryUtil.nmemCalloc(texels, texel);
        try {
            if (data != 0L) {
                for (long i = 0; i < texels; i++) MemoryUtil.memCopy(data, fill + i * texel, texel);
            }
            textures().subImage(st, t, t.target, level, x, y, z, width, height, depth, format, type, fill, false);
        } finally {
            st.pixelUnpack = unpackBuffer;
            st.unpack.copyFrom(saved);
            MemoryUtil.nmemFree(fill);
        }
    }

    public void clearTexImageWith(int texture, int level, int format, int type, long data) {
        final T t = textures().get(texture);
        if (t == null || !t.hasImage() || level >= t.levels) return;
        final int depth = t.is3D() ? t.levelDepth(level) : t.layers;
        clearTexSubImage(texture, level, 0, 0, 0, t.levelWidth(level), t.levelHeight(level), depth, format, type, data);
    }

    public void copyTextureSubImage3D(int texture, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height) {
        final T t = textures().get(texture);
        if (t == null) return;
        withTexture(texture, t.target, () -> copyTexSubImage3D(t.target, level, xoffset, yoffset, zoffset, x, y, width, height));
    }

    public void getCompressedTextureImage(int texture, int level, ByteBuffer pixels) {
        final T t = textures().get(texture);
        if (t == null) return;
        if (t.target != GL13.GL_TEXTURE_CUBE_MAP) {
            withTexture(texture, t.target, () -> getTexImage(t.target, level, 0, 0, pixels));
            return;
        }
        final int faceBytes = t.hasImage() && t.format.isCompressed() && level < t.levels ? (int) Formats.imageBytes(t.format, t.levelWidth(level), t.levelHeight(level)) : 0;
        withTexture(texture, t.target, () -> {
            final ByteBuffer rest = pixels.duplicate();
            for (int face = 0; face < 6; face++) {
                getTexImage(GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, level, 0, 0, rest);
                rest.position(rest.position() + faceBytes);
            }
        });
    }

    /** With a pack buffer bound, the pointer is an offset into it. */
    public void getCompressedTextureImage(int texture, int level, long pointer) {
        final T t = textures().get(texture);
        if (t == null) return;
        if (st().pixelPack == 0) {
            getCompressedTextureImage(texture, level, MemoryUtil.memByteBuffer(pointer, Integer.MAX_VALUE));
            return;
        }
        if (t.target != GL13.GL_TEXTURE_CUBE_MAP) {
            withTexture(texture, t.target, () -> getTexImage(t.target, level, 0, 0, pointer));
            return;
        }
        final long faceBytes = t.hasImage() && t.format.isCompressed() && level < t.levels ? Formats.imageBytes(t.format, t.levelWidth(level), t.levelHeight(level)) : 0;
        withTexture(texture, t.target, () -> {
            for (int face = 0; face < 6; face++) getTexImage(GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, level, 0, 0, pointer + face * faceBytes);
        });
    }

    public void getCompressedTextureSubImage(int texture, int level, int x, int y, int z, int width, int height, int depth, long out) {
        final T t = textures().get(texture);
        if (t == null || !t.hasImage() || !t.format.isCompressed() || level >= t.levels) return;
        final B pbo = buffers().get(st().pixelPack);
        if (pbo != null) {
            final long bytes = (long) ((width + 3) / 4) * ((height + 3) / 4) * depth * t.format.blockBytes();
            readIntoPackBuffer(pbo, out, bytes, dst -> copyCompressedRegion(texture, t, level, x, y, z, width, height, depth, dst));
            return;
        }
        copyCompressedRegion(texture, t, level, x, y, z, width, height, depth, out);
    }

    /** Reads the whole level and copies out the blocks the region covers; compressed offsets are whole blocks by the GL's rules. */
    private void copyCompressedRegion(int texture, T t, int level, int x, int y, int z, int width, int height, int depth, long out) {
        final int block = t.format.blockBytes(), levelWidth = t.levelWidth(level), levelHeight = t.levelHeight(level);
        final int rowBlocks = (levelWidth + 3) / 4, columnBlocks = (levelHeight + 3) / 4;
        final int slices = t.is3D() ? t.levelDepth(level) : t.layers;
        final long sliceBytes = (long) rowBlocks * columnBlocks * block;
        if (z < 0 || depth < 0 || (long) z + depth > slices || sliceBytes * slices > Integer.MAX_VALUE) {
            recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        final ByteBuffer all = MemoryUtil.memAlloc((int) (sliceBytes * slices));
        try {
            getCompressedTextureImage(texture, level, all);
            final int x0 = x / 4, y0 = y / 4, copyBlocks = (width + 3) / 4, copyRows = (height + 3) / 4;
            long at = out;
            for (int slice = z; slice < z + depth; slice++) {
                for (int row = y0; row < y0 + copyRows; row++) {
                    final long from = memAddress(all) + slice * sliceBytes + (long) (row * rowBlocks + x0) * block;
                    MemoryUtil.memCopy(from, at, (long) copyBlocks * block);
                    at += (long) copyBlocks * block;
                }
            }
        } finally {
            MemoryUtil.memFree(all);
        }
    }

    /** One image's worth of textures sharing data as views, as a place in it: each has an image of its own, kept in step between uses. */
    private final class ViewMember {
        final T texture;
        final int firstLevel, firstLayer;
        long seen;

        ViewMember(T texture, int firstLevel, int firstLayer) {
            this.texture = texture;
            this.firstLevel = firstLevel;
            this.firstLayer = firstLayer;
            seen = texture.writes;
        }
    }

    private final List<List<ViewMember>> viewFamilies = new ArrayList<>();
    private boolean syncingViews;

    /** How many layers a shader can choose among in the draw framebuffer; a framebuffer without layers ignores the choice. */
    int drawLayerLimit() {
        final Fbo f = framebuffers.get(st().drawFbo);
        if (f == null || f.id == 0) return Integer.MAX_VALUE;
        int limit = Integer.MAX_VALUE;
        for (final int a : f.drawBuffers) {
            if (a < 0 || f.colorTex[a] == 0 || !f.colorLayered[a]) continue;
            final T tex = textures().attachment(f.colorTex[a], f.colorRbo[a]);
            if (tex == null || !tex.hasImage()) continue;
            limit = Math.min(limit, tex.is3D() ? tex.levelDepth(Math.min(f.colorLevel[a], tex.levels - 1)) : tex.layers);
        }
        return limit;
    }

    private void addToViewFamily(T original, T view, int minLevel, int minLayer) {
        for (List<ViewMember> family : viewFamilies) {
            for (ViewMember member : family) {
                if (member.texture == original) {
                    family.add(new ViewMember(view, member.firstLevel + minLevel, member.firstLayer + minLayer));
                    return;
                }
            }
        }
        final List<ViewMember> family = new ArrayList<>();
        family.add(new ViewMember(original, 0, 0));
        family.add(new ViewMember(view, minLevel, minLayer));
        viewFamilies.add(family);
    }

    /** Copies what was written through one view to the rest. The last to be written wins, which GL leaves undefined anyway without a barrier between. */
    protected final void syncTextureViews() {
        if (viewFamilies.isEmpty() || syncingViews) return;
        syncingViews = true;
        try {
            for (final java.util.Iterator<List<ViewMember>> families = viewFamilies.iterator(); families.hasNext(); ) {
                final List<ViewMember> family = families.next();
                family.removeIf(member -> textures().get(member.texture.id) != member.texture);
                if (family.size() < 2) {
                    families.remove();
                    continue;
                }
                ViewMember written = null;
                for (ViewMember member : family) {
                    if (member.texture.writes != member.seen) written = member;
                }
                if (written == null) continue;
                endPass();
                for (ViewMember member : family) {
                    if (member != written) copyBetweenViews(written, member);
                    member.seen = member.texture.writes;
                }
            }
        } finally {
            syncingViews = false;
        }
    }

    private void copyBetweenViews(ViewMember from, ViewMember to) {
        final T s = from.texture, d = to.texture;
        final int firstLevel = Math.max(from.firstLevel, to.firstLevel);
        final int lastLevel = Math.min(from.firstLevel + s.levels, to.firstLevel + d.levels);
        for (int level = firstLevel; level < lastLevel; level++) {
            final int sLevel = level - from.firstLevel, dLevel = level - to.firstLevel;
            final int sw = s.levelWidth(sLevel), sh = s.levelHeight(sLevel);
            if (s.is3D()) {
                copyImageSubData(s.id, s.target, sLevel, 0, 0, 0, d.id, d.target, dLevel, 0, 0, 0, sw, sh, s.levelDepth(sLevel));
                continue;
            }
            final int firstLayer = Math.max(from.firstLayer, to.firstLayer);
            final int lastLayer = Math.min(from.firstLayer + Math.max(1, s.layers), to.firstLayer + Math.max(1, d.layers));
            if (lastLayer > firstLayer) {
                copyImageSubData(s.id, s.target, sLevel, 0, 0, firstLayer - from.firstLayer, d.id, d.target, dLevel, 0, 0, firstLayer - to.firstLayer, sw, sh, lastLayer - firstLayer);
            }
        }
    }

    public void textureView(int view, int target, int original, int internalFormat, int minLevel, int wantedLevels, int minLayer, int wantedLayers) {
        final T from = textures().get(original);
        if (from == null || !from.hasImage() || wantedLevels <= 0) return;
        // A view takes as much of the range as the parent has, as GL words it.
        final int levels = Math.min(wantedLevels, from.levels - minLevel);
        final int layers = from.is3D() ? wantedLayers : Math.min(wantedLayers, (from.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : Math.max(1, from.layers)) - minLayer);
        textures().getOrCreate(view, target);
        final int width = from.levelWidth(minLevel), height = target == GL30.GL_TEXTURE_1D_ARRAY ? layers : from.levelHeight(minLevel);
        final int depth = from.is3D() ? from.levelDepth(minLevel) : layers;
        if (from.samples > 1) textureMultisample(view, from.samples, internalFormat, width, height, target == GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY ? layers : 1);
        else withTexture(view, target, () -> texStorage(target, levels, internalFormat, width, height, depth));
        for (int i = 0; i < levels; i++) {
            final int level = minLevel + i;
            final int slices = from.is3D() ? from.levelDepth(level) : layers;
            copyImageSubData(original, from.target, level, 0, 0, from.is3D() ? 0 : minLayer, view, target, i, 0, 0, 0,
                from.levelWidth(level), from.levelHeight(level), slices);
        }
        final T created = textures().get(view);
        created.viewMinLevel = minLevel + from.viewMinLevel;
        created.viewNumLevels = levels;
        created.immutableLevels = from.immutableLevels != 0 ? from.immutableLevels : from.levels;
        created.viewMinLayer = minLayer + from.viewMinLayer;
        created.viewNumLayers = layers;
        addToViewFamily(from, created, minLevel, from.is3D() ? 0 : minLayer);
    }

    private int clampReadColor = GL30.GL_FIXED_ONLY;
    private int maxShaderCompilerThreads = -1;

    /** Compiling never runs apart from the calling thread, which any number here allows. */
    public void maxShaderCompilerThreads(int count) {
        maxShaderCompilerThreads = count;
    }

    /** Vertex and fragment colours are clamped by the shaders here; GL keeps what was asked for these to be queried. */
    private int clampVertexColor = GL11.GL_TRUE, clampFragmentColor = GL30.GL_FIXED_ONLY;

    public void clampColor(int target, int clamp) {
        if (target == GL30.GL_CLAMP_READ_COLOR) clampReadColor = clamp;
        else if (target == GL30.GL_CLAMP_VERTEX_COLOR) clampVertexColor = clamp;
        else if (target == GL30.GL_CLAMP_FRAGMENT_COLOR) clampFragmentColor = clamp;
    }

    /** By default only a fixed-point buffer is clamped, and of those only a signed one holds what is outside 0 to 1. */
    private void clampRead(Formats.Format source, int w, int h, int format, int type, PixelConvert.Store pack, long dst) {
        if (clampReadColor == GL11.GL_FALSE || source.isInteger() || format == GL11.GL_DEPTH_COMPONENT || format == GL30.GL_DEPTH_STENCIL || format == GL11.GL_STENCIL_INDEX) return;
        final boolean signed = switch (source.pf()) {
            case PixelFormats.PF_R8_SNORM, PixelFormats.PF_RG8_SNORM, PixelFormats.PF_RGBA8_SNORM, PixelFormats.PF_R16_SNORM, PixelFormats.PF_RG16_SNORM, PixelFormats.PF_RGBA16_SNORM -> true;
            default -> false;
        };
        final boolean clamps = signed || clampReadColor == GL11.GL_TRUE;
        if (!clamps || type != GL11.GL_FLOAT && type != GL11.GL_BYTE && type != GL11.GL_SHORT) return;
        final int size = type == GL11.GL_FLOAT ? 4 : type == GL11.GL_SHORT ? 2 : 1;
        final long pixel = PixelConvert.clientBytes(1, 1, 1, format, type, new PixelConvert.Store());
        final long stride = PixelConvert.rowStride(pack.rowLength, format, type, pack);
        final long start = dst + pack.skipRows * stride + pack.skipPixels * pixel;
        for (int row = 0; row < h; row++) {
            for (long at = start + row * stride, end = at + w * pixel; at < end; at += size) {
                if (type == GL11.GL_FLOAT) {
                    final int bits = MemoryUtil.memGetInt(at);
                    final float value = Float.intBitsToFloat(pack.swapBytes ? Integer.reverseBytes(bits) : bits);
                    final int clamped = Float.floatToRawIntBits(Math.min(1f, Math.max(0f, value)));
                    MemoryUtil.memPutInt(at, pack.swapBytes ? Integer.reverseBytes(clamped) : clamped);
                } else if (type == GL11.GL_SHORT) {
                    final short value = MemoryUtil.memGetShort(at);
                    if ((pack.swapBytes ? Short.reverseBytes(value) : value) < 0) MemoryUtil.memPutShort(at, (short) 0);
                } else {
                    MemoryUtil.memPutByte(at, (byte) Math.max(0, MemoryUtil.memGetByte(at)));
                }
            }
        }
    }

    public ProgramBase<?> programBase(int id) {
        return programs().program(id);
    }

    public long mappedPointer(int target) {
        final B buffer = boundBuffer(target);
        return buffer == null || !buffer.mapped ? 0L : buffer.mapView != null ? memAddress(buffer.mapView) : buffer.address() + buffer.mapOffset;
    }

    /** glGetTextureImage and glGetTextureSubImage; a negative width means the whole level. */
    public void getTextureImage(int texture, int level, int x, int y, int z, int width, int height, int depth, int format, int type, long out) {
        final GlState st = st();
        final T t = textures().get(texture);
        if (t == null || !t.hasImage() || level >= t.levels) return;
        if (refuseCompressedRead(t)) return;
        final int aspect = format == GL11.GL_STENCIL_INDEX ? Formats.ASPECT_STENCIL : t.format.isDepth() ? Formats.ASPECT_DEPTH : Formats.ASPECT_COLOR;
        final int w = width < 0 ? t.levelWidth(level) : width, h = height < 0 ? t.levelHeight(level) : height;
        final int slices = depth >= 0 ? depth : t.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : t.is3D() ? t.levelDepth(level) : t.layers;
        final boolean layersInHeight = t.target == GL30.GL_TEXTURE_1D_ARRAY;
        final long availableSlices = t.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : t.is3D() ? t.levelDepth(level) : layersInHeight ? 1 : t.layers;
        // Reading slice by slice waits on the device each time, so a size past the image must be refused before it starts.
        if (level < 0 || x < 0 || y < 0 || z < 0 || (long) x + w > t.levelWidth(level) || (long) y + h > (layersInHeight ? t.layers : t.levelHeight(level)) || (long) z + slices > availableSlices) {
            recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        final long sliceBytes = PixelConvert.rowStride(w, format, type, st.pack) * (st.pack.imageHeight > 0 ? st.pack.imageHeight : h);
        final long rowBytes = PixelConvert.rowStride(w, format, type, st.pack);
        final LongConsumer read = dst -> {
            if (layersInHeight) {
                for (int row = 0; row < h; row++) textures().readImage(t, aspect, level, y + row, x, 0, w, 1, format, type, st.pack, dst + row * rowBytes);
                return;
            }
            for (int slice = 0; slice < slices; slice++) textures().readImage(t, aspect, level, z + slice, x, y, w, h, format, type, st.pack, dst + (st.pack.skipImages + slice) * sliceBytes);
        };
        final B pbo = st.pixelPack == 0 ? null : buffers().get(st.pixelPack);
        if (pbo != null) readIntoPackBuffer(pbo, out, PixelConvert.clientBytes(w, h, slices, format, type, st.pack), read);
        else read.accept(out);
    }

    private boolean refuseCompressedRead(T t) {
        if (!t.format.isCompressed()) return false;
        warnOnce("reading a compressed texture as pixels is not supported; glGetCompressedTexImage returns its blocks");
        return true;
    }

    /** Runs a read that lands in the pack buffer, through a temporary when the buffer cannot be written from here. */
    private void readIntoPackBuffer(B pbo, long offset, long size, LongConsumer read) {
        if (offset < 0 || offset + size > pbo.size) {
            warnOnce("image read out of bounds of the pack buffer");
            recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        pbo.contentVersion++;
        if (pbo.hostVisible()) {
            if (!isIdle(pbo.lastUse)) flushAndWait(pbo.lastUse);
            read.accept(pbo.address() + offset);
            return;
        }
        final long temporary = MemoryUtil.nmemAlloc(size);
        try {
            read.accept(temporary);
            buffers().write(pbo, offset, size, temporary);
        } finally {
            MemoryUtil.nmemFree(temporary);
        }
    }

    /** Makes a program the one uniform calls reach, returning the previous one to put back, for glProgramUniform. */
    public int useForUniform(int program) {
        final GlState st = st();
        final int was = st.program;
        st.program = program;
        return was;
    }

    public void uniformMatrixAt(int location, int count, int cols, int rows, boolean transpose, long value) {
        programs().uniformMatrix(currentProgram(), location, count, cols, rows, transpose, value);
    }

    public void texStorage2D(int target, int levels, int internalFormat, int width, int height) {
        textures().texStorage(st(), target, levels, internalFormat, width, height, 1);
    }

    public void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        final GlState st = st();
        copyFromFramebuffer(st, textures().get(st.boundTexture(target)), target, level, xoffset, yoffset, 0, x, y, width, height);
    }

    public void copyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
        final GlState st = st();
        final T t = textures().getOrCreate(st.boundTexture(target), target);
        if (t == null) return;
        if (!textures().specify(t, textures().format(internalFormat), level, width, height, 1, t.isCube() ? 6 : 1)) return;
        if (t.format.pf() == PixelFormats.PF_RGB9E5_FLOAT) {
            copySharedExponent(st, t, target, level, x, y, width, height);
            return;
        }
        copyFromFramebuffer(st, t, target, level, 0, 0, 0, x, y, width, height);
    }

    /**
     * Copies from the read framebuffer into a texture. Depth into another depth format goes through the host, as GL defines it: read as values,
     * then stored in the new format, which no device copy does between sizes or between fixed and floating point.
     */
    private void copyFromFramebuffer(GlState st, T t, int target, int level, int dx, int dy, int dz, int x, int y, int w, int h) {
        if (t != null && t.hasImage() && t.format.isDepthOrStencil() && w > 0 && h > 0) {
            final TargetBase<T> src = readTarget(st);
            final T s = src == null ? null : src.depth;
            // A stencil-only image takes the stencil of whatever holds it, as indices, which no device copy between those formats does.
            final boolean stencilOnly = !t.format.isDepth();
            if (s != null && (s.format.pf() != t.format.pf() || stencilOnly)) {
                if (stencilOnly && !s.format.isStencil()) {
                    recordError(GL11.GL_INVALID_OPERATION);
                    return;
                }
                final boolean stencil = s.format.isStencil() && t.format.isStencil();
                final int format = stencilOnly ? GL11.GL_STENCIL_INDEX : stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
                final int type = stencilOnly ? GL11.GL_UNSIGNED_BYTE : stencil ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL11.GL_FLOAT;
                final long pixels = MemoryUtil.nmemAlloc(w * (long) h * (stencilOnly ? 1 : stencil ? 8 : 4));
                try {
                    withPlainPixelStores(st, () -> {
                        readPixelsImpl(x, y, w, h, format, type, pixels);
                        textures().subImage(st, t, target, level, dx, dy, dz, w, h, 1, format, type, pixels, false);
                    });
                } finally {
                    MemoryUtil.nmemFree(pixels);
                }
                return;
            }
        }
        textures().copyFromFramebuffer(st, t, target, level, dx, dy, dz, x, y, w, h);
    }

    /** Runs with tight pixel stores and no pixel buffers, putting back what the application had. */
    private void withPlainPixelStores(GlState st, Runnable run) {
        final PixelConvert.Store pack = new PixelConvert.Store(), unpack = new PixelConvert.Store();
        pack.copyFrom(st.pack);
        unpack.copyFrom(st.unpack);
        final int packBuffer = st.pixelPack, unpackBuffer = st.pixelUnpack;
        try {
            st.pack.copyFrom(new PixelConvert.Store());
            st.unpack.copyFrom(new PixelConvert.Store());
            st.pack.alignment = st.unpack.alignment = 1;
            st.pixelPack = st.pixelUnpack = 0;
            run.run();
        } finally {
            st.pack.copyFrom(pack);
            st.unpack.copyFrom(unpack);
            st.pixelPack = packBuffer;
            st.pixelUnpack = unpackBuffer;
        }
    }

    /** No device draws or blits into this format, so what is read goes through the host and its conversion. */
    private void copySharedExponent(GlState st, T t, int target, int level, int x, int y, int width, int height) {
        final long pixels = MemoryUtil.nmemAlloc(width * (long) height * 3 * Float.BYTES);
        final PixelConvert.Store pack = new PixelConvert.Store(), unpack = new PixelConvert.Store();
        pack.copyFrom(st.pack);
        unpack.copyFrom(st.unpack);
        final int packBuffer = st.pixelPack, unpackBuffer = st.pixelUnpack;
        try {
            st.pack.copyFrom(new PixelConvert.Store());
            st.unpack.copyFrom(new PixelConvert.Store());
            st.pack.alignment = st.unpack.alignment = 1;
            st.pixelPack = st.pixelUnpack = 0;
            readPixelsImpl(x, y, width, height, GL11.GL_RGB, GL11.GL_FLOAT, pixels);
            textures().upload(st, t, level, 0, 0, 0, width, height, 1, Math.max(0, TexturesBase.cubeFace(target)), 1, GL11.GL_RGB, GL11.GL_FLOAT, pixels, false);
        } finally {
            st.pack.copyFrom(pack);
            st.unpack.copyFrom(unpack);
            st.pixelPack = packBuffer;
            st.pixelUnpack = unpackBuffer;
            MemoryUtil.nmemFree(pixels);
        }
    }

    public void copyTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height) {
        final GlState st = st();
        copyFromFramebuffer(st, textures().get(st.boundTexture(target)), target, level, xoffset, yoffset, zoffset, x, y, width, height);
    }

    public void copyTextureSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        copyFromFramebuffer(st(), textures().get(texture), target, level, xoffset, yoffset, 0, x, y, width, height);
    }

    public void texParameteri(int target, int pname, int param) {
        texParam(writableParamTexture(target), pname, param, param);
    }

    public void texParameterf(int target, int pname, float param) {
        texParam(writableParamTexture(target), pname, (int) param, param);
    }

    public void texParameteriv(int target, int pname, IntBuffer params) {
        texParamv(writableParamTexture(target), pname, params, null);
    }

    public void texParameterfv(int target, int pname, FloatBuffer params) {
        texParamv(writableParamTexture(target), pname, null, params);
    }

    private T writableParamTexture(int target) {
        return TexturesBase.isProxy(target) ? null : paramTexture(target);
    }

    public void textureParameteri(int texture, int target, int pname, int param) {
        texParam(textures().getOrCreate(texture, target), pname, param, param);
    }

    public void textureParameterf(int texture, int target, int pname, float param) {
        texParam(textures().getOrCreate(texture, target), pname, (int) param, param);
    }

    public void textureParameterfv(int texture, int target, int pname, FloatBuffer params) {
        texParamv(textures().getOrCreate(texture, target), pname, null, params);
    }

    public void textureParameteriv(int texture, int target, int pname, IntBuffer params) {
        texParamv(textures().getOrCreate(texture, target), pname, params, null);
    }

    protected void texParamv(T t, int pname, IntBuffer ip, FloatBuffer fp) {
        samplingVersion++;
        if (t == null) return;
        if (pname == GL11.GL_TEXTURE_BORDER_COLOR) {
            for (int i = 0; i < 4; i++) t.border[i] = fp != null ? fp.get(fp.position() + i) : ip.get(ip.position() + i) / (float) Integer.MAX_VALUE;
            com.gtnewhorizons.angelica.glemu.shader.BorderLowering.noteColor(com.gtnewhorizons.angelica.glemu.shader.BorderLowering.isPreset(t.border[0], t.border[1], t.border[2], t.border[3]));
            t.borderInteger = null;
            t.samplerDirty = true;
            return;
        }
        if (pname == GL33.GL_TEXTURE_SWIZZLE_RGBA) {
            for (int i = 0; i < 4; i++) t.swizzle[i] = ip != null ? ip.get(ip.position() + i) : (int) fp.get(fp.position() + i);
            t.viewDirty = true;
            return;
        }
        final int iv = ip != null ? ip.get(ip.position()) : (int) fp.get(fp.position());
        final float fv = fp != null ? fp.get(fp.position()) : ip.get(ip.position());
        texParam(t, pname, iv, fv);
    }

    protected void texParam(T t, int pname, int iv, float fv) {
        samplingVersion++;
        if (t == null) return;
        switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> {
                final boolean mipsBefore = t.usesMipmaps();
                t.minFilter = iv;
                t.samplerDirty = true;
                if (mipsBefore != t.usesMipmaps()) t.viewDirty = true;
            }
            case GL11.GL_TEXTURE_MAG_FILTER -> { t.magFilter = iv; t.samplerDirty = true; }
            case GL11.GL_TEXTURE_WRAP_S -> { t.wrapS = iv; t.samplerDirty = true; }
            case GL11.GL_TEXTURE_WRAP_T -> { t.wrapT = iv; t.samplerDirty = true; }
            case GL12.GL_TEXTURE_WRAP_R -> { t.wrapR = iv; t.samplerDirty = true; }
            case GL12.GL_TEXTURE_MIN_LOD -> { t.minLod = fv; t.samplerDirty = true; }
            case GL12.GL_TEXTURE_MAX_LOD -> { t.maxLod = fv; t.samplerDirty = true; }
            case GL14.GL_TEXTURE_LOD_BIAS -> { t.lodBias = fv; t.samplerDirty = true; }
            case GL12.GL_TEXTURE_BASE_LEVEL -> { t.baseLevel = iv; t.viewDirty = true; }
            case GL12.GL_TEXTURE_MAX_LEVEL -> { t.maxLevel = iv; t.viewDirty = true; }
            case GL14.GL_TEXTURE_COMPARE_MODE -> { t.compareMode = iv; t.samplerDirty = true; }
            case GL14.GL_TEXTURE_COMPARE_FUNC -> { t.compareFunc = iv; t.samplerDirty = true; }
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> { t.anisotropy = fv; t.samplerDirty = true; }
            case GL33.GL_TEXTURE_SWIZZLE_R -> { t.swizzle[0] = iv; t.viewDirty = true; }
            case GL33.GL_TEXTURE_SWIZZLE_G -> { t.swizzle[1] = iv; t.viewDirty = true; }
            case GL33.GL_TEXTURE_SWIZZLE_B -> { t.swizzle[2] = iv; t.viewDirty = true; }
            case GL33.GL_TEXTURE_SWIZZLE_A -> { t.swizzle[3] = iv; t.viewDirty = true; }
            case GL43.GL_DEPTH_STENCIL_TEXTURE_MODE -> { t.sampleStencil = iv == GL11.GL_STENCIL_INDEX; t.viewDirty = true; }
            default -> { }
        }
    }

    protected T paramTexture(int target) {
        if (TexturesBase.isProxy(target)) return proxyDefaults();
        final int bound = st().boundTexture(target);
        return bound != 0 ? textures().get(bound) : textures().defaultObject(target);
    }

    /** glTexParameterI: a border colour is kept as the integers given, which reading it back has to return exactly. */
    public final void keepIntegerBorder(TexBase t, long params) {
        samplingVersion++;
        if (t == null) return;
        t.borderInteger = new int[4];
        for (int i = 0; i < 4; i++) t.borderInteger[i] = MemoryUtil.memGetInt(params + i * 4L);
        com.gtnewhorizons.angelica.glemu.shader.BorderLowering.noteColor(com.gtnewhorizons.angelica.glemu.shader.BorderLowering.isPreset(t.borderInteger[0], t.borderInteger[1], t.borderInteger[2], t.borderInteger[3]));
    }

    public final void boundIntegerBorder(int target, long params) {
        keepIntegerBorder(paramTexture(target), params);
    }

    public int getTexParameteri(int target, int pname) {
        return texParamGet(paramTexture(target), pname);
    }

    public float getTexParameterf(int target, int pname) {
        return texParamGetf(paramTexture(target), pname);
    }

    private float texParamGetf(T t, int pname) {
        if (t == null) return defaultTextureParameter(pname);
        return switch (pname) {
            case GL12.GL_TEXTURE_MIN_LOD -> t.minLod;
            case GL12.GL_TEXTURE_MAX_LOD -> t.maxLod;
            case GL14.GL_TEXTURE_LOD_BIAS -> t.lodBias;
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> Math.max(1f, t.anisotropy);
            default -> texParamGet(t, pname);
        };
    }

    /** A parameter of one or more values: as floats, as integers GL scales a colour to, or as an integer texture's border holds them. */
    public final void textureParameterOut(int texture, int pname, long out, int form) {
        final T t = texture == 0 ? null : textures().get(texture);
        if (t != null) parameterOut(t, pname, out, form);
    }

    /** The border colour of whatever a target has bound, which for the default object is all zeroes. */
    public final void boundBorderColorOut(int target, long out, int form) {
        final T t = paramTexture(target);
        if (t != null) parameterOut(t, GL11.GL_TEXTURE_BORDER_COLOR, out, form);
        else MemoryUtil.memSet(out, 0, 16);
    }

    private void parameterOut(T t, int pname, long out, int form) {
        if (pname == GL11.GL_TEXTURE_BORDER_COLOR) {
            for (int i = 0; i < 4; i++) {
                final float value = t.border[i];
                if (form == 1) MemoryUtil.memPutFloat(out + i * 4L, value);
                else if (form == 2) MemoryUtil.memPutInt(out + i * 4L, t.borderInteger != null ? t.borderInteger[i] : (int) value);
                else MemoryUtil.memPutInt(out + i * 4L, (int) Math.round((double) value * Integer.MAX_VALUE));
            }
        } else if (pname == GL33.GL_TEXTURE_SWIZZLE_RGBA) {
            for (int i = 0; i < 4; i++) {
                if (form == 1) MemoryUtil.memPutFloat(out + i * 4L, t.swizzle[i]);
                else MemoryUtil.memPutInt(out + i * 4L, t.swizzle[i]);
            }
        } else if (form == 1) {
            MemoryUtil.memPutFloat(out, texParamGetf(t, pname));
        } else {
            MemoryUtil.memPutInt(out, texParamGet(t, pname));
        }
    }

    public int getTextureParameteri(int texture, int target, int pname) {
        return texParamGet(textures().get(texture), pname);
    }

    public float getTextureParameterf(int texture, int target, int pname) {
        return texParamGetf(textures().get(texture), pname);
    }

    /** The object that name zero stands for, which keeps GL's initial values for what is asked of it. */
    private static int defaultTextureParameter(int pname) {
        return switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> GL11.GL_NEAREST_MIPMAP_LINEAR;
            case GL11.GL_TEXTURE_MAG_FILTER -> GL11.GL_LINEAR;
            case GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T, GL12.GL_TEXTURE_WRAP_R -> GL11.GL_REPEAT;
            case GL12.GL_TEXTURE_MAX_LEVEL -> 1000;
            case GL12.GL_TEXTURE_MAX_LOD -> 1000;
            case GL12.GL_TEXTURE_MIN_LOD -> -1000;
            case GL14.GL_TEXTURE_COMPARE_FUNC -> GL11.GL_LEQUAL;
            case GL33.GL_TEXTURE_SWIZZLE_R -> GL11.GL_RED;
            case GL33.GL_TEXTURE_SWIZZLE_G -> GL11.GL_GREEN;
            case GL33.GL_TEXTURE_SWIZZLE_B -> GL11.GL_BLUE;
            case GL33.GL_TEXTURE_SWIZZLE_A -> GL11.GL_ALPHA;
            case GL43.GL_DEPTH_STENCIL_TEXTURE_MODE -> GL11.GL_DEPTH_COMPONENT;
            default -> 0;
        };
    }

    protected int texParamGet(T t, int pname) {
        if (t == null) return defaultTextureParameter(pname);
        return switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> t.minFilter;
            case GL11.GL_TEXTURE_MAG_FILTER -> t.magFilter;
            case GL11.GL_TEXTURE_WRAP_S -> t.wrapS;
            case GL11.GL_TEXTURE_WRAP_T -> t.wrapT;
            case GL12.GL_TEXTURE_WRAP_R -> t.wrapR;
            case GL12.GL_TEXTURE_BASE_LEVEL -> t.baseLevel;
            case GL12.GL_TEXTURE_MAX_LEVEL -> t.maxLevel;
            case GL14.GL_TEXTURE_COMPARE_MODE -> t.compareMode;
            case GL14.GL_TEXTURE_COMPARE_FUNC -> t.compareFunc;
            case GL42.GL_TEXTURE_IMMUTABLE_FORMAT -> t.immutable ? 1 : 0;
            case GL43.GL_TEXTURE_IMMUTABLE_LEVELS -> t.immutableLevels != 0 ? t.immutableLevels : t.immutable ? t.levels : 0;
            case GL33.GL_TEXTURE_SWIZZLE_R -> t.swizzle[0];
            case GL33.GL_TEXTURE_SWIZZLE_G -> t.swizzle[1];
            case GL33.GL_TEXTURE_SWIZZLE_B -> t.swizzle[2];
            case GL33.GL_TEXTURE_SWIZZLE_A -> t.swizzle[3];
            case GL43.GL_DEPTH_STENCIL_TEXTURE_MODE -> t.sampleStencil ? GL11.GL_STENCIL_INDEX : GL11.GL_DEPTH_COMPONENT;
            case GL45.GL_TEXTURE_TARGET -> t.declaredTarget != 0 ? t.declaredTarget : t.target;
            case GL12.GL_TEXTURE_MIN_LOD -> Math.round(t.minLod);
            case GL12.GL_TEXTURE_MAX_LOD -> Math.round(t.maxLod);
            case GL14.GL_TEXTURE_LOD_BIAS -> Math.round(t.lodBias);
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> Math.round(Math.max(1f, t.anisotropy));
            case GL42.GL_IMAGE_FORMAT_COMPATIBILITY_TYPE -> GL42.GL_IMAGE_FORMAT_COMPATIBILITY_BY_SIZE;
            case GL43.GL_TEXTURE_VIEW_MIN_LEVEL -> t.viewMinLevel;
            case GL43.GL_TEXTURE_VIEW_NUM_LEVELS -> t.viewNumLevels != 0 ? t.viewNumLevels : t.immutable ? t.levels : 0;
            case GL43.GL_TEXTURE_VIEW_MIN_LAYER -> t.viewMinLayer;
            case GL43.GL_TEXTURE_VIEW_NUM_LAYERS -> t.viewNumLayers != 0 ? t.viewNumLayers : t.immutable ? t.layers : 0;
            default -> 0;
        };
    }

    public int getTexLevelParameteri(int target, int level, int pname) {
        if (TexturesBase.isProxy(target)) return TexturesBase.proxyParam(st(), target, level, pname);
        return levelParam(textures().get(st().boundTexture(target)), level, pname);
    }

    public int getTextureLevelParameteri(int texture, int level, int pname) {
        return levelParam(textures().get(texture), level, pname);
    }

    private static int componentType(Formats.Format f, int bits) {
        if (bits == 0) return GL11.GL_NONE;
        return switch (f.kind()) {
            case Formats.K_SNORM -> GL31.GL_SIGNED_NORMALIZED;
            case Formats.K_UINT -> GL11.GL_UNSIGNED_INT;
            case Formats.K_SINT -> GL11.GL_INT;
            case Formats.K_FLOAT, Formats.K_HALF -> GL11.GL_FLOAT;
            default -> f.isInteger() ? GL11.GL_UNSIGNED_INT : f.pf() == PixelFormats.PF_RG11B10_FLOAT || f.pf() == PixelFormats.PF_RGB9E5_FLOAT ? GL11.GL_FLOAT : GL30.GL_UNSIGNED_NORMALIZED;
        };
    }

    protected int levelParam(T t, int level, int pname) {
        final int bufferParam = t != null && t.target == GL31.GL_TEXTURE_BUFFER && level == 0 ? bufferTextureParam(t.id, pname) : -1;
        if (bufferParam >= 0) return bufferParam;
        final boolean missing = t != null && level < 31 && (t.missingLevels >>> level & 1) != 0;
        final int[] replaced = missing && t.replacedLevels != null && level < t.replacedLevels.length ? t.replacedLevels[level] : null;
        if (t == null || !t.hasImage() || level >= t.levels || missing && replaced == null) return pname == GL11.GL_TEXTURE_INTERNAL_FORMAT ? GL11.GL_RGBA : 0;
        if (replaced != null && pname == GL11.GL_TEXTURE_WIDTH) return replaced[0];
        if (replaced != null && pname == GL11.GL_TEXTURE_HEIGHT) return replaced[1];
        if (replaced != null && pname == GL12.GL_TEXTURE_DEPTH) return replaced[2];
        final Formats.Format f = t.format;
        return switch (pname) {
            case GL32.GL_TEXTURE_SAMPLES -> t.samples > 1 ? t.samples : 0;
            case GL32.GL_TEXTURE_FIXED_SAMPLE_LOCATIONS -> GL11.GL_TRUE;
            case GL11.GL_TEXTURE_WIDTH -> t.levelWidth(level);
            case GL11.GL_TEXTURE_HEIGHT -> t.target == GL30.GL_TEXTURE_1D_ARRAY ? t.layers : t.levelHeight(level);
            case GL12.GL_TEXTURE_DEPTH -> t.is3D() ? t.levelDepth(level) : t.layers;
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> f.glInternal();
            case GL30.GL_TEXTURE_SHARED_SIZE -> f.pf() == com.gtnewhorizons.angelica.glemu.resource.PixelFormats.PF_RGB9E5_FLOAT ? 5 : 0;
            case GL30.GL_TEXTURE_RED_SIZE -> f.r();
            case GL30.GL_TEXTURE_GREEN_SIZE -> f.g();
            case GL30.GL_TEXTURE_BLUE_SIZE -> f.b();
            case GL30.GL_TEXTURE_ALPHA_SIZE -> f.a();
            case GL30.GL_TEXTURE_RED_TYPE -> componentType(f, f.r());
            case GL30.GL_TEXTURE_GREEN_TYPE -> componentType(f, f.g());
            case GL30.GL_TEXTURE_BLUE_TYPE -> componentType(f, f.b());
            case GL30.GL_TEXTURE_ALPHA_TYPE -> componentType(f, f.a());
            case GL30.GL_TEXTURE_DEPTH_TYPE -> f.depthBits() == 0 ? GL11.GL_NONE : f.glInternal() == GL30.GL_DEPTH_COMPONENT32F || f.glInternal() == GL30.GL_DEPTH32F_STENCIL8 ? GL11.GL_FLOAT : f.kind() == Formats.K_DEPTH ? GL30.GL_UNSIGNED_NORMALIZED : GL11.GL_FLOAT;
            case GL14.GL_TEXTURE_DEPTH_SIZE -> f.depthBits();
            case GL30.GL_TEXTURE_STENCIL_SIZE -> f.stencilBits();
            case GL13.GL_TEXTURE_COMPRESSED -> f.isCompressed() ? 1 : 0;
            case GL13.GL_TEXTURE_COMPRESSED_IMAGE_SIZE -> (int) Formats.imageBytes(f, t.levelWidth(level), t.levelHeight(level)) * retainedSlices(t, level);
            default -> 0;
        };
    }

    public void generateMipmap(int target) {
        textures().generateMipmap(textures().get(st().boundTexture(target)));
    }

    public void generateTextureMipmap(int texture) {
        textures().generateMipmap(textures().get(texture));
    }

    public void pixelStorei(int pname, int param) {
        final GlState st = st();
        switch (pname) {
            case GL11.GL_UNPACK_ROW_LENGTH -> st.unpack.rowLength = param;
            case GL11.GL_UNPACK_SKIP_PIXELS -> st.unpack.skipPixels = param;
            case GL11.GL_UNPACK_SKIP_ROWS -> st.unpack.skipRows = param;
            case GL11.GL_UNPACK_ALIGNMENT -> st.unpack.alignment = param;
            case GL12.GL_UNPACK_IMAGE_HEIGHT -> st.unpack.imageHeight = param;
            case GL12.GL_UNPACK_SKIP_IMAGES -> st.unpack.skipImages = param;
            case GL11.GL_UNPACK_SWAP_BYTES -> st.unpack.swapBytes = param != 0;
            case GL11.GL_PACK_ROW_LENGTH -> st.pack.rowLength = param;
            case GL11.GL_PACK_SKIP_PIXELS -> st.pack.skipPixels = param;
            case GL11.GL_PACK_SKIP_ROWS -> st.pack.skipRows = param;
            case GL11.GL_PACK_ALIGNMENT -> st.pack.alignment = param;
            case GL12.GL_PACK_IMAGE_HEIGHT -> st.pack.imageHeight = param;
            case GL12.GL_PACK_SKIP_IMAGES -> st.pack.skipImages = param;
            case GL11.GL_PACK_SWAP_BYTES -> st.pack.swapBytes = param != 0;
            case GL42.GL_UNPACK_COMPRESSED_BLOCK_WIDTH -> st.unpack.blockWidth = param;
            case GL42.GL_UNPACK_COMPRESSED_BLOCK_HEIGHT -> st.unpack.blockHeight = param;
            case GL42.GL_UNPACK_COMPRESSED_BLOCK_DEPTH -> st.unpack.blockDepth = param;
            case GL42.GL_UNPACK_COMPRESSED_BLOCK_SIZE -> st.unpack.blockSize = param;
            case GL42.GL_PACK_COMPRESSED_BLOCK_WIDTH -> st.pack.blockWidth = param;
            case GL42.GL_PACK_COMPRESSED_BLOCK_HEIGHT -> st.pack.blockHeight = param;
            case GL42.GL_PACK_COMPRESSED_BLOCK_DEPTH -> st.pack.blockDepth = param;
            case GL42.GL_PACK_COMPRESSED_BLOCK_SIZE -> st.pack.blockSize = param;
            default -> { }
        }
    }

    public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        withTexture(texture, target, () -> texImage2D(target, level, internalformat, width, height, border, format, type, pixels));
    }

    public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        withTexture(texture, target, () -> texImage2D(target, level, internalformat, width, height, border, format, type, pixels));
    }

    public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        final T t = textures().get(texture);
        if (t == null || !t.hasImage() || pixels == null) return;
        textures().subImage(st(), t, t.target, level, xoffset, yoffset, 0, width, height, 1, format, type, memAddress(pixels), false);
    }

    public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
        final T t = textures().get(texture);
        if (t == null || !t.hasImage() || pixels == null) return;
        textures().subImage(st(), t, t.target, level, xoffset, yoffset, 0, width, height, 1, format, type, memAddress(pixels), false);
    }

    protected void withTexture(int texture, int target, Runnable r) {
        final GlState st = st();
        final int slot = GlState.targetIndex(target);
        if (slot < 0) return;
        final int prev = st.textures[st.activeUnit][slot];
        textures().getOrCreate(texture, target);
        st.textures[st.activeUnit][slot] = texture;
        try {
            r.run();
        } finally {
            st.textures[st.activeUnit][slot] = prev;
        }
    }

    public void getTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
        syncTextureViews();
        if (pixels == null) return;
        getTexImageImpl(target, level, format, type, memAddress(pixels));
    }

    public void getTexImage(int target, int level, int format, int type, IntBuffer pixels) {
        syncTextureViews();
        if (pixels == null) return;
        getTexImageImpl(target, level, format, type, memAddress(pixels));
    }

    public void getTexImage(int target, int level, int format, int type, long pixelBufferOffset) {
        syncTextureViews();
        final GlState st = st();
        final B pbo = buffers().get(st.pixelPack);
        final T t = textures().get(st.boundTexture(target));
        if (pbo == null || t == null || !t.hasImage() || level >= t.levels) return;
        final long size;
        if (format == 0 && t.format.isCompressed()) {
            final byte[] blocks = retainedBlocks(t, target, level);
            if (blocks == null) return;
            size = blocks.length;
        } else {
            final int slices = t.target == GL13.GL_TEXTURE_CUBE_MAP ? 1 : t.is3D() ? t.levelDepth(level) : t.layers;
            size = PixelConvert.clientBytes(t.levelWidth(level), t.levelHeight(level), slices, format, type, st.pack);
        }
        readIntoPackBuffer(pbo, pixelBufferOffset, size, dst -> getTexImageImpl(target, level, format, type, dst));
    }

    public void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        if (unit < 0 || unit >= GlState.MAX_IMAGE_UNITS) return;
        final GlState st = st();
        st.imageTex[unit] = texture;
        st.imageLevel[unit] = level;
        st.imageLayered[unit] = layered;
        st.imageLayer[unit] = layer;
        st.imageAccess[unit] = access;
        st.imageFormat[unit] = format;
    }

    public int genSamplers() {
        int id = nextSampler++;
        while (samplerObjects.containsKey(id)) id = nextSampler++;
        samplerObjects.put(id, new SamplerObj(id));
        return id;
    }

    public void deleteSamplers(int sampler) {
        samplingVersion++;
        samplerObjects.remove(sampler);
        forgetSampler(mainState, sampler);
        for (GlState s : otherStates) forgetSampler(s, sampler);
    }

    private static void forgetSampler(GlState st, int sampler) {
        for (int u = 0; u < GlState.MAX_UNITS; u++) if (st.samplers[u] == sampler) st.samplers[u] = 0;
    }

    public boolean isSampler(int sampler) {
        return samplerObjects.containsKey(sampler);
    }

    public void bindSampler(int unit, int sampler) {
        if (unit < 0 || unit >= GlState.MAX_UNITS) return;
        final GlState st = st();
        st.samplers[unit] = sampler;
    }

    public void samplerBorder(int sampler, IntBuffer ip, FloatBuffer fp) {
        samplingVersion++;
        final SamplerObj s = samplerObjects.get(sampler);
        if (s == null) return;
        for (int i = 0; i < 4; i++) s.border[i] = fp != null ? fp.get(fp.position() + i) : ip.get(ip.position() + i) / (float) Integer.MAX_VALUE;
        com.gtnewhorizons.angelica.glemu.shader.BorderLowering.noteColor(com.gtnewhorizons.angelica.glemu.shader.BorderLowering.isPreset(s.border[0], s.border[1], s.border[2], s.border[3]));
        s.forgetSamplers();
    }

    public void samplerParameteri(int sampler, int pname, int param) {
        samplerParam(samplerObjects.get(sampler), pname, param, param);
    }

    public void samplerParameterf(int sampler, int pname, float param) {
        samplerParam(samplerObjects.get(sampler), pname, (int) param, param);
    }

    protected void samplerParam(SamplerObj s, int pname, int iv, float fv) {
        samplingVersion++;
        if (s == null) return;
        switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> s.minFilter = iv;
            case GL11.GL_TEXTURE_MAG_FILTER -> s.magFilter = iv;
            case GL11.GL_TEXTURE_WRAP_S -> s.wrapS = iv;
            case GL11.GL_TEXTURE_WRAP_T -> s.wrapT = iv;
            case GL12.GL_TEXTURE_WRAP_R -> s.wrapR = iv;
            case GL12.GL_TEXTURE_MIN_LOD -> s.minLod = fv;
            case GL12.GL_TEXTURE_MAX_LOD -> s.maxLod = fv;
            case GL14.GL_TEXTURE_LOD_BIAS -> s.lodBias = fv;
            case GL14.GL_TEXTURE_COMPARE_MODE -> s.compareMode = iv;
            case GL14.GL_TEXTURE_COMPARE_FUNC -> s.compareFunc = iv;
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> s.anisotropy = fv;
            default -> { }
        }
        s.forgetSamplers();
    }

    public int genFramebuffers() {
        return framebuffers.gen();
    }

    public int createFramebuffers() {
        final int id = framebuffers.gen();
        framebuffers.get(id).named = true;
        return id;
    }

    public void deleteFramebuffers(int framebuffer) {
        if (framebuffer == 0) return;
        framebuffers.delete(framebuffer);
        if (mainState.drawFbo == framebuffer) mainState.drawFbo = 0;
        if (mainState.readFbo == framebuffer) mainState.readFbo = 0;
        for (GlState s : otherStates) {
            if (s.drawFbo == framebuffer) s.drawFbo = 0;
            if (s.readFbo == framebuffer) s.readFbo = 0;
        }
    }

    public boolean isFramebuffer(int framebuffer) {
        return framebuffers.exists(framebuffer) && framebuffers.get(framebuffer).named;
    }

    public void bindFramebuffer(int target, int framebuffer) {
        final GlState st = st();
        if (framebuffer != 0) framebuffers.getOrCreate(framebuffer).named = true;
        if (target == GL30.GL_FRAMEBUFFER || target == GL30.GL_DRAW_FRAMEBUFFER) st.drawFbo = framebuffer;
        if (target == GL30.GL_FRAMEBUFFER || target == GL30.GL_READ_FRAMEBUFFER) st.readFbo = framebuffer;
    }

    public Fbo fboFor(int target) {
        final GlState st = st();
        return framebuffers.get(target == GL30.GL_READ_FRAMEBUFFER ? st.readFbo : st.drawFbo);
    }

    public void framebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        if (texture != 0) textures().getOrCreate(texture, textarget);
        final int layer = TexturesBase.cubeFace(textarget);
        framebuffers.attach(fboFor(target), attachment, texture, false, level, layer, false);
    }

    public void framebufferTexture1D(int target, int attachment, int textarget, int texture, int level) {
        framebufferTexture2D(target, attachment, textarget, texture, level);
    }

    public void framebufferTexture3D(int target, int attachment, int textarget, int texture, int level, int zoffset) {
        framebuffers.attach(fboFor(target), attachment, texture, false, level, zoffset, false);
    }

    public void framebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        framebuffers.attach(fboFor(target), attachment, texture, false, level, layer, false);
    }

    private boolean isLayered(int texture) {
        final T t = textures().get(texture);
        if (t == null) return false;
        return switch (t.target) {
            case GL12.GL_TEXTURE_3D, GL30.GL_TEXTURE_1D_ARRAY, GL30.GL_TEXTURE_2D_ARRAY, GL13.GL_TEXTURE_CUBE_MAP, GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY -> true;
            default -> false;
        };
    }

    public void framebufferTexture(int target, int attachment, int texture, int level) {
        framebuffers.attach(fboFor(target), attachment, texture, false, level, 0, isLayered(texture));
    }

    public void namedFramebufferTexture(int framebuffer, int attachment, int texture, int level) {
        framebuffers.attach(framebuffers.getOrCreate(framebuffer), attachment, texture, false, level, 0, isLayered(texture));
    }

    public void namedFramebufferReadBuffer(int framebuffer, int mode) {
        setReadBuffer(framebuffers.getOrCreate(framebuffer), mode);
    }

    public void namedFramebufferDrawBuffers(int framebuffer, IntBuffer bufs) {
        namedDrawBuffers(framebuffers.getOrCreate(framebuffer), bufs, st());
    }

    public int genRenderbuffers() {
        return textures().genRenderbuffer();
    }

    public void deleteRenderbuffers(int renderbuffer) {
        textures().deleteRenderbuffer(renderbuffer);
        if (mainState.renderbuffer == renderbuffer) mainState.renderbuffer = 0;
        for (GlState s : otherStates) if (s.renderbuffer == renderbuffer) s.renderbuffer = 0;
    }

    public boolean isRenderbuffer(int renderbuffer) {
        return textures().renderbufferExists(renderbuffer) && textures().attachment(renderbuffer, true).named;
    }

    public void bindRenderbuffer(int target, int renderbuffer) {
        if (renderbuffer != 0) textures().renderbuffer(renderbuffer).named = true;
        st().renderbuffer = renderbuffer;
    }

    public void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (renderbuffer != 0) textures().renderbuffer(renderbuffer);
        framebuffers.attach(fboFor(target), attachment, renderbuffer, true, 0, 0, false);
    }

    public void framebufferParameter(Fbo f, int pname, int value) {
        if (f == null) return;
        switch (pname) {
            case GL43.GL_FRAMEBUFFER_DEFAULT_WIDTH -> f.defaultWidth = value;
            case GL43.GL_FRAMEBUFFER_DEFAULT_HEIGHT -> f.defaultHeight = value;
            case GL43.GL_FRAMEBUFFER_DEFAULT_LAYERS -> f.defaultLayers = value;
            case GL43.GL_FRAMEBUFFER_DEFAULT_SAMPLES -> f.defaultSamples = value;
            case GL43.GL_FRAMEBUFFER_DEFAULT_FIXED_SAMPLE_LOCATIONS -> f.defaultFixedSampleLocations = value == 0 ? 0 : 1;
            default -> { return; }
        }
        f.gen++;
        targetsChanged();
    }

    public int framebufferParameter(Fbo f, int pname) {
        if (f == null) return 0;
        return switch (pname) {
            case GL43.GL_FRAMEBUFFER_DEFAULT_WIDTH -> f.defaultWidth;
            case GL43.GL_FRAMEBUFFER_DEFAULT_HEIGHT -> f.defaultHeight;
            case GL43.GL_FRAMEBUFFER_DEFAULT_LAYERS -> f.defaultLayers;
            case GL43.GL_FRAMEBUFFER_DEFAULT_SAMPLES -> f.defaultSamples;
            case GL43.GL_FRAMEBUFFER_DEFAULT_FIXED_SAMPLE_LOCATIONS -> f.defaultFixedSampleLocations;
            case GL13.GL_SAMPLES -> f.hasAttachments() ? framebufferSamples(f) : f.defaultSamples;
            case GL13.GL_SAMPLE_BUFFERS -> (f.hasAttachments() ? framebufferSamples(f) : f.defaultSamples) > 1 ? 1 : 0;
            case GL11.GL_DOUBLEBUFFER -> f.id == 0 ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL11.GL_STEREO -> GL11.GL_FALSE;
            case GL41.GL_IMPLEMENTATION_COLOR_READ_FORMAT -> GL11.GL_RGBA;
            case GL41.GL_IMPLEMENTATION_COLOR_READ_TYPE -> GL11.GL_UNSIGNED_BYTE;
            default -> 0;
        };
    }

    public int checkFramebufferStatus(int target) {
        return framebuffers.status(fboFor(target));
    }

    public int getFramebufferAttachmentParameteri(int target, int attachment, int pname) {
        final Fbo f = fboFor(target);
        if (f == null) return 0;
        if (f.id == 0) {
            return switch (pname) {
                case GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE -> GL30.GL_FRAMEBUFFER_DEFAULT;
                case GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE -> attachment == GL11.GL_DEPTH || attachment == GL30.GL_DEPTH_ATTACHMENT ? 24 : 0;
                case GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE -> attachment == GL11.GL_STENCIL || attachment == GL30.GL_STENCIL_ATTACHMENT ? 8 : 0;
                case GL30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE, GL30.GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE, GL30.GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE, GL30.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE -> 8;
                case GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE -> GL31.GL_UNSIGNED_NORMALIZED;
                default -> 0;
            };
        }
        int tex;
        boolean rbo;
        int level;
        final int idx = attachment - GL30.GL_COLOR_ATTACHMENT0;
        if (idx >= 0 && idx < Fbo.MAX_COLOR) {
            tex = f.colorTex[idx];
            rbo = f.colorRbo[idx];
            level = f.colorLevel[idx];
        } else if (attachment == GL30.GL_DEPTH_ATTACHMENT || attachment == GL30.GL_DEPTH_STENCIL_ATTACHMENT) {
            tex = f.depthTex;
            rbo = f.depthRbo;
            level = f.depthLevel;
        } else if (attachment == GL30.GL_STENCIL_ATTACHMENT) {
            tex = f.stencilTex;
            rbo = f.stencilRbo;
            level = f.stencilLevel;
        } else {
            return 0;
        }
        if (pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) return tex == 0 ? GL11.GL_NONE : rbo ? GL30.GL_RENDERBUFFER : GL11.GL_TEXTURE;
        if (pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) return tex;
        if (pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) return level;
        if (pname == GL32.GL_FRAMEBUFFER_ATTACHMENT_LAYERED) {
            final boolean layered = idx >= 0 && idx < Fbo.MAX_COLOR ? f.colorLayered[idx] : f.depthLayered;
            return layered ? GL11.GL_TRUE : GL11.GL_FALSE;
        }
        if (pname == GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER) return idx >= 0 && idx < Fbo.MAX_COLOR ? f.colorLayer[idx] : attachment == GL30.GL_STENCIL_ATTACHMENT ? f.stencilLayer : f.depthLayer;
        final T t = textures().attachment(tex, rbo);
        if (t == null || t.format == null) return 0;
        return switch (pname) {
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE -> t.format.r();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE -> t.format.g();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE -> t.format.b();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE -> t.format.a();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE -> t.format.depthBits();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE -> t.format.stencilBits();
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE -> switch (t.format.kind()) {
                case Formats.K_FLOAT, Formats.K_HALF -> GL11.GL_FLOAT;
                case Formats.K_PACKED -> t.format.isInteger() ? GL11.GL_UNSIGNED_INT : t.format.glInternal() == GL11.GL_RGB10_A2 ? GL31.GL_UNSIGNED_NORMALIZED : GL11.GL_FLOAT;
                case Formats.K_UINT -> GL11.GL_UNSIGNED_INT;
                case Formats.K_SINT -> GL11.GL_INT;
                case Formats.K_SNORM -> GL31.GL_SIGNED_NORMALIZED;
                default -> GL31.GL_UNSIGNED_NORMALIZED;
            };
            case GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING -> t.format.srgb() ? GL21.GL_SRGB : GL11.GL_LINEAR;
            default -> 0;
        };
    }

    public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        final GlState st = st();
        blit(st, framebuffers.get(st.drawFbo), srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    public void blitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        final GlState st = st();
        final int prevRead = st.readFbo, prevDraw = st.drawFbo;
        st.readFbo = readFramebuffer;
        st.drawFbo = drawFramebuffer;
        try {
            blit(st, framebuffers.get(drawFramebuffer), srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        } finally {
            st.readFbo = prevRead;
            st.drawFbo = prevDraw;
        }
    }

    public static boolean clipBlitAxis(int[] e, int srcSize, int dstSize) {
        // Clips a (possibly flipped) blit span to both surfaces along one axis, scaling the other end to keep the mapping.
        final double s0 = e[0], s1 = e[1], d0 = e[2], d1 = e[3];
        if (s0 == s1 || d0 == d1) return false;
        double lo = 0, hi = 1;
        for (double[] c : new double[][] {{s0, s1, srcSize}, {d0, d1, dstSize}}) {
            final double a = (0 - c[0]) / (c[1] - c[0]), b = (c[2] - c[0]) / (c[1] - c[0]);
            lo = Math.max(lo, Math.min(a, b));
            hi = Math.min(hi, Math.max(a, b));
        }
        if (hi <= lo) return false;
        final int nd0 = (int) Math.round(d0 + lo * (d1 - d0)), nd1 = (int) Math.round(d0 + hi * (d1 - d0));
        if (nd0 == nd1) return false;
        e[0] = clamp((int) Math.round(s0 + (nd0 - d0) / (d1 - d0) * (s1 - s0)), 0, srcSize);
        e[1] = clamp((int) Math.round(s0 + (nd1 - d0) / (d1 - d0) * (s1 - s0)), 0, srcSize);
        e[2] = nd0;
        e[3] = nd1;
        return e[0] != e[1];
    }

    protected static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void readPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        if (pixels != null) readPixelsImpl(x, y, width, height, format, type, memAddress(pixels));
    }

    public void readPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        if (pixels != null) readPixelsImpl(x, y, width, height, format, type, memAddress(pixels));
    }

    public void readPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        if (pixels != null) readPixelsImpl(x, y, width, height, format, type, memAddress(pixels));
    }

    public void readPixels(int x, int y, int width, int height, int format, int type, long pixelBufferOffset) {
        final GlState st = st();
        final B pbo = buffers().get(st.pixelPack);
        if (pbo == null) {
            if (pixelBufferOffset != 0L) readPixelsImpl(x, y, width, height, format, type, pixelBufferOffset);
            return;
        }
        readIntoPackBuffer(pbo, pixelBufferOffset, PixelConvert.clientBytes(width, height, 1, format, type, st.pack),
            dst -> readPixelsImpl(x, y, width, height, format, type, dst));
    }

    private boolean builtinLimitsSent;

    /** Each shaderc limit with the query whose answer a shader's gl_Max* constant must equal. */
    private static final int[][] BUILTIN_LIMITS = {
        {Shaderc.shaderc_limit_max_vertex_attribs, GL20.GL_MAX_VERTEX_ATTRIBS},
        {Shaderc.shaderc_limit_max_vertex_uniform_components, GL20.GL_MAX_VERTEX_UNIFORM_COMPONENTS},
        {Shaderc.shaderc_limit_max_varying_floats, GL20.GL_MAX_VARYING_FLOATS},
        {Shaderc.shaderc_limit_max_varying_components, GL20.GL_MAX_VARYING_FLOATS},
        {Shaderc.shaderc_limit_max_vertex_texture_image_units, GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS},
        {Shaderc.shaderc_limit_max_combined_texture_image_units, GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS},
        {Shaderc.shaderc_limit_max_texture_image_units, GL20.GL_MAX_TEXTURE_IMAGE_UNITS},
        {Shaderc.shaderc_limit_max_fragment_uniform_components, GL20.GL_MAX_FRAGMENT_UNIFORM_COMPONENTS},
        {Shaderc.shaderc_limit_max_draw_buffers, GL20.GL_MAX_DRAW_BUFFERS},
        {Shaderc.shaderc_limit_max_vertex_uniform_vectors, GL41.GL_MAX_VERTEX_UNIFORM_VECTORS},
        {Shaderc.shaderc_limit_max_varying_vectors, GL41.GL_MAX_VARYING_VECTORS},
        {Shaderc.shaderc_limit_max_fragment_uniform_vectors, GL41.GL_MAX_FRAGMENT_UNIFORM_VECTORS},
        {Shaderc.shaderc_limit_max_vertex_output_components, GL32.GL_MAX_VERTEX_OUTPUT_COMPONENTS},
        {Shaderc.shaderc_limit_max_fragment_input_components, GL32.GL_MAX_FRAGMENT_INPUT_COMPONENTS},
        {Shaderc.shaderc_limit_max_compute_uniform_components, GL43.GL_MAX_COMPUTE_UNIFORM_COMPONENTS},
        {Shaderc.shaderc_limit_max_compute_texture_image_units, GL43.GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS},
        {Shaderc.shaderc_limit_max_compute_image_uniforms, GL43.GL_MAX_COMPUTE_IMAGE_UNIFORMS},
        {Shaderc.shaderc_limit_max_compute_atomic_counters, GL43.GL_MAX_COMPUTE_ATOMIC_COUNTERS},
        {Shaderc.shaderc_limit_max_compute_atomic_counter_buffers, GL43.GL_MAX_COMPUTE_ATOMIC_COUNTER_BUFFERS},
        {Shaderc.shaderc_limit_max_geometry_uniform_components, GL32.GL_MAX_GEOMETRY_UNIFORM_COMPONENTS},
        {Shaderc.shaderc_limit_max_geometry_texture_image_units, GL32.GL_MAX_GEOMETRY_TEXTURE_IMAGE_UNITS},
        {Shaderc.shaderc_limit_max_geometry_input_components, GL32.GL_MAX_GEOMETRY_INPUT_COMPONENTS},
        {Shaderc.shaderc_limit_max_geometry_output_components, GL32.GL_MAX_GEOMETRY_OUTPUT_COMPONENTS},
        {Shaderc.shaderc_limit_max_geometry_output_vertices, GL32.GL_MAX_GEOMETRY_OUTPUT_VERTICES},
        {Shaderc.shaderc_limit_max_geometry_total_output_components, GL32.GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS},
        {Shaderc.shaderc_limit_max_geometry_image_uniforms, GL42.GL_MAX_GEOMETRY_IMAGE_UNIFORMS},
        {Shaderc.shaderc_limit_max_geometry_atomic_counters, GL42.GL_MAX_GEOMETRY_ATOMIC_COUNTERS},
        {Shaderc.shaderc_limit_max_geometry_atomic_counter_buffers, GL42.GL_MAX_GEOMETRY_ATOMIC_COUNTER_BUFFERS},
        {Shaderc.shaderc_limit_max_tess_control_input_components, GL40.GL_MAX_TESS_CONTROL_INPUT_COMPONENTS},
        {Shaderc.shaderc_limit_max_tess_control_output_components, GL40.GL_MAX_TESS_CONTROL_OUTPUT_COMPONENTS},
        {Shaderc.shaderc_limit_max_tess_control_texture_image_units, GL40.GL_MAX_TESS_CONTROL_TEXTURE_IMAGE_UNITS},
        {Shaderc.shaderc_limit_max_tess_control_uniform_components, GL40.GL_MAX_TESS_CONTROL_UNIFORM_COMPONENTS},
        {Shaderc.shaderc_limit_max_tess_control_total_output_components, GL40.GL_MAX_TESS_CONTROL_TOTAL_OUTPUT_COMPONENTS},
        {Shaderc.shaderc_limit_max_tess_control_image_uniforms, GL42.GL_MAX_TESS_CONTROL_IMAGE_UNIFORMS},
        {Shaderc.shaderc_limit_max_tess_control_atomic_counters, GL42.GL_MAX_TESS_CONTROL_ATOMIC_COUNTERS},
        {Shaderc.shaderc_limit_max_tess_control_atomic_counter_buffers, GL42.GL_MAX_TESS_CONTROL_ATOMIC_COUNTER_BUFFERS},
        {Shaderc.shaderc_limit_max_tess_evaluation_input_components, GL40.GL_MAX_TESS_EVALUATION_INPUT_COMPONENTS},
        {Shaderc.shaderc_limit_max_tess_evaluation_output_components, GL40.GL_MAX_TESS_EVALUATION_OUTPUT_COMPONENTS},
        {Shaderc.shaderc_limit_max_tess_evaluation_texture_image_units, GL40.GL_MAX_TESS_EVALUATION_TEXTURE_IMAGE_UNITS},
        {Shaderc.shaderc_limit_max_tess_evaluation_uniform_components, GL40.GL_MAX_TESS_EVALUATION_UNIFORM_COMPONENTS},
        {Shaderc.shaderc_limit_max_tess_evaluation_image_uniforms, GL42.GL_MAX_TESS_EVALUATION_IMAGE_UNIFORMS},
        {Shaderc.shaderc_limit_max_tess_evaluation_atomic_counters, GL42.GL_MAX_TESS_EVALUATION_ATOMIC_COUNTERS},
        {Shaderc.shaderc_limit_max_tess_evaluation_atomic_counter_buffers, GL42.GL_MAX_TESS_EVALUATION_ATOMIC_COUNTER_BUFFERS},
        {Shaderc.shaderc_limit_max_tess_patch_components, GL40.GL_MAX_TESS_PATCH_COMPONENTS},
        {Shaderc.shaderc_limit_max_patch_vertices, GL40.GL_MAX_PATCH_VERTICES},
        {Shaderc.shaderc_limit_max_tess_gen_level, GL40.GL_MAX_TESS_GEN_LEVEL},
        {Shaderc.shaderc_limit_max_viewports, GL41.GL_MAX_VIEWPORTS},
        {Shaderc.shaderc_limit_max_image_units, GL42.GL_MAX_IMAGE_UNITS},
        {Shaderc.shaderc_limit_max_combined_shader_output_resources, GL43.GL_MAX_COMBINED_SHADER_OUTPUT_RESOURCES},
        {Shaderc.shaderc_limit_max_combined_image_units_and_fragment_outputs, GL43.GL_MAX_COMBINED_SHADER_OUTPUT_RESOURCES},
        {Shaderc.shaderc_limit_max_image_samples, GL42.GL_MAX_IMAGE_SAMPLES},
        {Shaderc.shaderc_limit_max_vertex_image_uniforms, GL42.GL_MAX_VERTEX_IMAGE_UNIFORMS},
        {Shaderc.shaderc_limit_max_fragment_image_uniforms, GL42.GL_MAX_FRAGMENT_IMAGE_UNIFORMS},
        {Shaderc.shaderc_limit_max_combined_image_uniforms, GL42.GL_MAX_COMBINED_IMAGE_UNIFORMS},
        {Shaderc.shaderc_limit_max_vertex_atomic_counters, GL42.GL_MAX_VERTEX_ATOMIC_COUNTERS},
        {Shaderc.shaderc_limit_max_fragment_atomic_counters, GL42.GL_MAX_FRAGMENT_ATOMIC_COUNTERS},
        {Shaderc.shaderc_limit_max_combined_atomic_counters, GL42.GL_MAX_COMBINED_ATOMIC_COUNTERS},
        {Shaderc.shaderc_limit_max_atomic_counter_bindings, GL42.GL_MAX_ATOMIC_COUNTER_BUFFER_BINDINGS},
        {Shaderc.shaderc_limit_max_vertex_atomic_counter_buffers, GL42.GL_MAX_VERTEX_ATOMIC_COUNTER_BUFFERS},
        {Shaderc.shaderc_limit_max_fragment_atomic_counter_buffers, GL42.GL_MAX_FRAGMENT_ATOMIC_COUNTER_BUFFERS},
        {Shaderc.shaderc_limit_max_combined_atomic_counter_buffers, GL42.GL_MAX_COMBINED_ATOMIC_COUNTER_BUFFERS},
        {Shaderc.shaderc_limit_max_atomic_counter_buffer_size, GL42.GL_MAX_ATOMIC_COUNTER_BUFFER_SIZE},
        {Shaderc.shaderc_limit_max_transform_feedback_buffers, GL40.GL_MAX_TRANSFORM_FEEDBACK_BUFFERS},
        {Shaderc.shaderc_limit_max_transform_feedback_interleaved_components, GL30.GL_MAX_TRANSFORM_FEEDBACK_INTERLEAVED_COMPONENTS},
        {Shaderc.shaderc_limit_max_clip_distances, GL30.GL_MAX_CLIP_DISTANCES},
        {Shaderc.shaderc_limit_max_cull_distances, GL45.GL_MAX_CULL_DISTANCES},
        {Shaderc.shaderc_limit_max_combined_clip_and_cull_distances, GL45.GL_MAX_COMBINED_CLIP_AND_CULL_DISTANCES},
        {Shaderc.shaderc_limit_max_samples, GL30.GL_MAX_SAMPLES},
    };

    private void sendBuiltinLimits() {
        if (builtinLimitsSent) return;
        builtinLimitsSent = true;
        final int[] pairs = new int[BUILTIN_LIMITS.length * 2];
        for (int i = 0; i < BUILTIN_LIMITS.length; i++) {
            pairs[i * 2] = BUILTIN_LIMITS[i][0];
            pairs[i * 2 + 1] = getInteger(BUILTIN_LIMITS[i][1]);
        }
        ShaderCompiler.setBuiltinLimits(pairs);
    }

    public int createShader(int type) {
        sendBuiltinLimits();
        return programs().createShader(type);
    }

    public void deleteShader(int shader) {
        programs().deleteShader(shader);
    }

    public boolean isShader(int obj) {
        return programs().isShader(obj);
    }

    public void shaderSource(int shader, CharSequence source) {
        final Shader s = programs().shader(shader);
        if (s != null) {
            s.source = source.toString();
            s.spirv = null;
            s.specialized = false;
        }
    }

    public void compileShader(int shader) {
        final Shader s = programs().shader(shader);
        if (s != null) programs().compile(s);
    }

    public String getShaderSource(int shader, int maxLength) {
        final Shader s = programs().shader(shader);
        if (s == null) return "";
        return s.source.length() > maxLength && maxLength > 0 ? s.source.substring(0, maxLength) : s.source;
    }

    public void getShaderSource(int shader, IntBuffer length, ByteBuffer source) {
        final Shader s = programs().shader(shader);
        writeString(s == null ? "" : s.source, length, source);
    }

    public int createProgram() {
        return programs().createProgram();
    }

    public void deleteProgram(int program) {
        programs().deleteProgram(program);
    }

    public boolean isProgram(int obj) {
        return programs().isProgram(obj);
    }

    public void attachShader(int program, int shader) {
        programs().attach(programs().program(program), programs().shader(shader));
    }

    public void detachShader(int program, int shader) {
        programs().detach(programs().program(program), programs().shader(shader));
    }

    public void bindAttribLocation(int program, int index, CharSequence name) {
        final P p = programs().program(program);
        if (p != null) p.attribBindings.put(name.toString(), index);
    }

    public void bindFragDataLocation(int program, int colorNumber, CharSequence name) {
        final P p = programs().program(program);
        if (p != null) p.fragDataBindings.put(name.toString(), colorNumber);
    }

    public void linkProgram(int program) {
        P p = programs().program(program);
        if (p == null) return;
        // Only a program made current with glUseProgram is drawn with by its object; a pipeline draws through a composite of its stages,
        // which a failed link leaves as it was, so a program only pipelines use links in place.
        if (p.linked && !p.pipelineComposite && anyStateHasCurrent(program)) {
            final P old = p;
            p = programs().relinkInUse(old);
            if (p.linked || !holdExecutable(old)) programs().retireExecutable(old);
        } else {
            programs().link(p);
        }
        // A link that works is what every state using the program draws with from then on.
        if (p.linked) releaseHeldExecutables(program);
        if (p.linked) p.linkedSeparable = p.separable;
        if (p.usesSubroutines) com.gtnewhorizons.angelica.glemu.compat.ProgramInfo.resetSubroutines(program);
    }

    public void validateProgram(int program) {
    }

    private boolean anyStateHasCurrent(int program) {
        for (GlState s : allStates()) if (s.program == program) return true;
        return false;
    }

    /** Keeps what a failed relink replaced drawing in each state that has the program current. Returns whether any does. */
    private boolean holdExecutable(P old) {
        boolean held = false;
        for (GlState s : allStates()) {
            if (s.program != old.id) continue;
            if (s.heldExecutable == null) s.heldExecutable = old;
            held |= s.heldExecutable == old;
        }
        return held;
    }

    private void releaseHeldExecutables(int program) {
        for (GlState s : allStates()) {
            if (s.heldExecutable != null && s.heldExecutable.id == program) releaseHeld(s);
        }
    }

    @SuppressWarnings("unchecked")
    private void releaseHeld(GlState st) {
        final ProgramBase<?> held = st.heldExecutable;
        if (held == null) return;
        st.heldExecutable = null;
        for (GlState s : allStates()) if (s.heldExecutable == held) return;
        programs().retireExecutable((P) held);
    }

    private List<GlState> allStates() {
        final List<GlState> all = new ArrayList<>(otherStates.size() + 1);
        all.add(mainState);
        all.addAll(otherStates);
        return all;
    }

    public void useProgram(int program) {
        final GlState st = st();
        if (st.program == program) return;
        final int prev = st.program;
        releaseHeld(st);
        st.program = program;
        final P old = programs().program(prev);
        if (old != null && old.deletePending && !anyStateUsesProgram(prev)) programs().deleteProgram(prev);
    }

    public String getShaderInfoLog(int shader, int maxLength) {
        final Shader s = programs().shader(shader);
        return truncate(s == null ? "" : s.log, maxLength);
    }

    public void getShaderInfoLog(int shader, IntBuffer length, ByteBuffer infoLog) {
        final Shader s = programs().shader(shader);
        writeString(s == null ? "" : s.log, length, infoLog);
    }

    public String getProgramInfoLog(int program, int maxLength) {
        final P p = programs().program(program);
        return truncate(p == null ? "" : p.log, maxLength);
    }

    public void getProgramInfoLog(int program, IntBuffer length, ByteBuffer infoLog) {
        final P p = programs().program(program);
        writeString(p == null ? "" : p.log, length, infoLog);
    }

    protected static String truncate(String s, int max) {
        return max > 0 && s.length() >= max ? s.substring(0, Math.max(0, max - 1)) : s;
    }

    protected static void writeString(String s, IntBuffer length, ByteBuffer out) {
        if (out == null) {
            if (length != null) length.put(length.position(), 0);
            return;
        }
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        final int n = Math.max(0, Math.min(bytes.length, out.remaining() - 1));
        for (int i = 0; i < n; i++) out.put(out.position() + i, bytes[i]);
        if (out.remaining() > 0) out.put(out.position() + n, (byte) 0);
        if (length != null) length.put(length.position(), n);
    }

    public int getShaderi(int shader, int pname) {
        return programs().shaderi(programs().shader(shader), pname);
    }

    public void programParameteri(int program, int pname, int value) {
        final ProgramBase<?> p = programBase(program);
        if (p != null && pname == GL41.GL_PROGRAM_SEPARABLE) p.separable = value != 0;
    }

    public boolean programIsSeparableAtNextLink(int program) {
        final ProgramBase<?> p = programBase(program);
        return p != null && p.separable;
    }

    public int getProgrami(int program, int pname) {
        final P named = programs().program(program);
        if (named != null && named.fromSpirv && (pname == GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH || pname == GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH
            || pname == GL30.GL_TRANSFORM_FEEDBACK_VARYING_MAX_LENGTH || pname == GL31.GL_ACTIVE_UNIFORM_BLOCK_MAX_NAME_LENGTH)) return 1;
        final int feedback = GlTransformFeedback.programQuery(program, pname);
        if (feedback >= 0) return feedback;
        return programs().programi(programs().program(program), pname);
    }

    public void getProgramiv(int program, int pname, IntBuffer params) {
        final P p = programs().program(program);
        if (params == null || params.remaining() == 0) return;
        if (pname == GL43.GL_COMPUTE_WORK_GROUP_SIZE && p != null && params.remaining() >= 3) {
            params.put(params.position(), p.localX).put(params.position() + 1, p.localY).put(params.position() + 2, p.localZ);
            return;
        }
        params.put(params.position(), programs().programi(p, pname));
    }

    public String getActiveUniform(int program, int index, int maxLength, IntBuffer sizeType) {
        final P p = programs().program(program);
        if (p == null || index < 0 || index >= p.activeUniformCount()) return "";
        final ProgramBase.Uniform u = p.activeUniform(index);
        if (sizeType != null && sizeType.remaining() >= 2) {
            sizeType.put(sizeType.position(), Math.max(1, u.arraySize));
            sizeType.put(sizeType.position() + 1, u.declaredDouble != 0 ? u.declaredDouble : u.glType);
        }
        return truncate(ProgramsBase.activeName(u), maxLength);
    }

    public void getActiveUniform(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        final P p = programs().program(program);
        if (p == null || index < 0 || index >= p.activeUniformCount()) return;
        final ProgramBase.Uniform u = p.activeUniform(index);
        if (size != null) size.put(size.position(), Math.max(1, u.arraySize));
        if (type != null) type.put(type.position(), u.declaredDouble != 0 ? u.declaredDouble : u.glType);
        writeString(ProgramsBase.activeName(u), length, name);
    }

    public String getActiveAttrib(int program, int index, int maxLength, IntBuffer sizeType) {
        final P p = programs().program(program);
        if (p == null || index < 0 || index >= p.attribs.size()) return "";
        final P.Attrib a = p.attribs.get(index);
        if (sizeType != null && sizeType.remaining() >= 2) {
            sizeType.put(sizeType.position(), 1);
            sizeType.put(sizeType.position() + 1, a.glType);
        }
        return truncate(a.name, maxLength);
    }

    public void getActiveAttrib(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        final P p = programs().program(program);
        if (p == null || index < 0 || index >= p.attribs.size()) return;
        final P.Attrib a = p.attribs.get(index);
        if (size != null) size.put(size.position(), a.size);
        if (type != null) type.put(type.position(), a.glType);
        writeString(a.name, length, name);
    }

    public int getAttribLocation(int program, CharSequence name) {
        final P p = programs().program(program);
        if (p == null || !p.linked) return -1;
        final String n = name.toString();
        final int bracket = n.indexOf('[');
        final String base = bracket < 0 ? n : n.substring(0, bracket);
        int element = 0;
        if (bracket >= 0) {
            if (!n.endsWith("]")) return -1;
            try {
                element = Integer.parseInt(n.substring(bracket + 1, n.length() - 1));
            } catch (NumberFormatException notAnElement) {
                return -1;
            }
        }
        for (P.Attrib a : p.attribs) {
            if (a.name.equals(base)) return element >= 0 && element * a.elementLocations < a.locations ? a.location + element * a.elementLocations : -1;
        }
        return -1;
    }

    public int getAttribLocation(int program, ByteBuffer name) {
        return getAttribLocation(program, MemoryUtil.memASCII(name));
    }

    public void getAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        final P p = programs().program(program);
        int written = 0;
        if (p != null && shaders != null) {
            for (int i = 0; i < p.attached.size() && written < shaders.remaining(); i++) shaders.put(shaders.position() + written++, p.attached.getInt(i));
        }
        if (count != null) count.put(count.position(), written);
    }

    public int getUniformLocation(int program, CharSequence name) {
        final P p = programs().program(program);
        if (p == null || !p.linked) return -1;
        final int location = p.locations.getInt(name.toString());
        if (location >= 0) return location;
        final int block = getUniformBlockIndex(program, GlslIncludes.BINDABLE_BLOCK_PREFIX + name);
        return block == GL31.GL_INVALID_INDEX ? -1 : BINDABLE_LOCATION_BASE + block;
    }

    /** Locations of EXT_bindable_uniform's, which are blocks here: past anything a real uniform has, so uniform calls ignore them. */
    public static final int BINDABLE_LOCATION_BASE = 1 << 24;

    public int getUniformLocation(int program, ByteBuffer name) {
        return getUniformLocation(program, MemoryUtil.memASCII(name));
    }

    public int getUniformBlockIndex(int program, CharSequence name) {
        final P p = programs().program(program);
        if (p == null) return GL31.GL_INVALID_INDEX;
        final String n = name.toString();
        for (int i = 0; i < p.ubos.size(); i++) if (p.ubos.get(i).name.equals(n)) return i;
        return GL31.GL_INVALID_INDEX;
    }

    public void shaderStorageBlockBinding(int program, int blockIndex, int binding) {
        final P p = programs().program(program);
        if (p == null) return;
        // Atomic counters are storage blocks here, which GL does not list as such.
        int remaining = blockIndex;
        for (ProgramBase.BufferBlock block : p.ssbos) {
            if (block.atomicCounters || block.internal()) continue;
            if (remaining-- == 0) {
                block.glBinding = binding;
                return;
            }
        }
    }

    public void uniformBlockBinding(int program, int blockIndex, int binding) {
        final P p = programs().program(program);
        if (p == null || blockIndex < 0 || blockIndex >= p.ubos.size()) return;
        p.ubos.get(blockIndex).glBinding = binding;
    }

    protected P currentProgram() {
        if (pipelineUniformProgram != 0 && st().program == pipelineProgram) return programs().program(pipelineUniformProgram);
        return programs().current(st());
    }

    /** Program 0 is the one uniforms currently go to. */
    public void uniform64(int program, int location, int count, int comps, long src) {
        programs().uniform64(program == 0 ? currentProgram() : programs().program(program), location, count, comps, src);
    }

    public void getUniform64(int program, int location, long out) {
        final P p = programs().program(program);
        if (p != null && location >= 0 && location < p.locationUniform.length && p.locationUniform[location] >= 0) {
            programs().getUniform64(p, location, components64(p.uniforms.get(p.locationUniform[location]).glType), out);
        }
    }

    private static int components64(int glType) {
        return switch (glType) {
            case GL20.GL_INT_VEC2, GL30.GL_UNSIGNED_INT_VEC2 -> 2;
            case GL20.GL_INT_VEC3, GL30.GL_UNSIGNED_INT_VEC3 -> 3;
            case GL20.GL_INT_VEC4, GL30.GL_UNSIGNED_INT_VEC4 -> 4;
            default -> 1;
        };
    }

    private int pipelineProgram, pipelineUniformProgram;
    private int[] pipelineStages;

    public void setPipeline(int program, int uniformProgram, int[] stages) {
        pipelineProgram = program;
        pipelineUniformProgram = uniformProgram;
        pipelineStages = stages;
    }

    public void setPipelineUniformProgram(int program) {
        pipelineUniformProgram = program;
    }

    public boolean pipelineProgramIsCurrent() {
        return pipelineProgram != 0 && st().program == pipelineProgram;
    }

    public void clearPipeline() {
        setPipeline(0, 0, null);
    }

    /** A pipeline's uniforms live in its stage programs, which share their default block layouts with the program it draws through. */
    private void syncPipelineUniforms(GlState st) {
        if (pipelineProgram == 0 || st.program != pipelineProgram) return;
        final P drawing = programs().program(pipelineProgram);
        if (drawing == null) return;
        for (int stage : pipelineStages) {
            final P from = programs().program(stage);
            if (from == null) continue;
            for (int s = 0; s < ProgramBase.STAGES; s++) {
                final ProgramBase.StageBlock source = from.blocks[s], target = drawing.blocks[s];
                if (source == null || target == null) continue;
                // Blocks of different sizes, or ones an emulated stage adds its own values to, are copied uniform by uniform.
                if (source.size != target.size || drawing.geometry != null || from.geometry != null) {
                    programs().copyDeclaredUniforms(from, drawing, s);
                    continue;
                }
                // Copied, and so uploaded again, only when a stage's values changed.
                if (sameBytes(source.address, target.address, source.size)) continue;
                MemoryUtil.memCopy(source.address, target.address, source.size);
                target.dirty = true;
            }
            for (ProgramBase.Uniform source : from.uniforms) {
                if (!source.isOpaque()) continue;
                for (ProgramBase.Uniform target : drawing.uniforms) {
                    if (target.isOpaque() && target.name.equals(source.name) && target.units.length == source.units.length && !Arrays.equals(source.units, target.units)) {
                        System.arraycopy(source.units, 0, target.units, 0, source.units.length);
                    }
                }
            }
            copyBlockBindings(from.ubos, drawing.ubos);
            copyBlockBindings(from.ssbos, drawing.ssbos);
            com.gtnewhorizons.angelica.glemu.compat.ProgramInfo.applySubroutines(from, pipelineProgram);
        }
    }

    private static boolean sameBytes(long a, long b, long size) {
        long at = 0;
        for (; at + 8 <= size; at += 8) if (MemoryUtil.memGetLong(a + at) != MemoryUtil.memGetLong(b + at)) return false;
        for (; at < size; at++) if (MemoryUtil.memGetByte(a + at) != MemoryUtil.memGetByte(b + at)) return false;
        return true;
    }

    private static void copyBlockBindings(List<ProgramBase.BufferBlock> from, List<ProgramBase.BufferBlock> to) {
        for (ProgramBase.BufferBlock source : from) {
            for (ProgramBase.BufferBlock target : to) {
                if (target.name.equals(source.name)) target.glBinding = source.glBinding;
            }
        }
    }

    /** A uniform of ours, which has a name but no use for the location a program gave it. */
    public void stageUniform(int program, String name, int value) {
        final P p = programs().program(program);
        if (p == null) return;
        final int location = p.locations.getInt(name);
        if (location < 0) return;
        MemoryUtil.memPutInt(scratch, value);
        programs().uniform(p, location, 1, 1, false, scratch);
    }

    public void uniformDoubles(int location, int count, int comps, long value) {
        programs().uniformDoubles(currentProgram(), location, count, comps, value);
    }

    public void uniformMatrixDoubles(int location, int count, int cols, int rows, boolean transpose, long value) {
        programs().uniformMatrixDoubles(currentProgram(), location, count, cols, rows, transpose, value);
    }

    /** Whether the uniform holds doubles, which have then been read into {@code out}. */
    public boolean getUniformDoubles(int program, int location, long out) {
        return programs().getUniformDoubles(programs().program(program), location, out);
    }

    public void uniform1i(int location, int v0) {
        MemoryUtil.memPutInt(scratch, v0);
        programs().uniform(currentProgram(), location, 1, 1, false, scratch);
    }

    public void uniform2i(int location, int v0, int v1) {
        MemoryUtil.memPutInt(scratch, v0);
        MemoryUtil.memPutInt(scratch + 4, v1);
        programs().uniform(currentProgram(), location, 1, 2, false, scratch);
    }

    public void uniform3i(int location, int v0, int v1, int v2) {
        MemoryUtil.memPutInt(scratch, v0);
        MemoryUtil.memPutInt(scratch + 4, v1);
        MemoryUtil.memPutInt(scratch + 8, v2);
        programs().uniform(currentProgram(), location, 1, 3, false, scratch);
    }

    public void uniform4i(int location, int v0, int v1, int v2, int v3) {
        MemoryUtil.memPutInt(scratch, v0);
        MemoryUtil.memPutInt(scratch + 4, v1);
        MemoryUtil.memPutInt(scratch + 8, v2);
        MemoryUtil.memPutInt(scratch + 12, v3);
        programs().uniform(currentProgram(), location, 1, 4, false, scratch);
    }

    public void uniform1f(int location, float v0) {
        MemoryUtil.memPutFloat(scratch, v0);
        programs().uniform(currentProgram(), location, 1, 1, true, scratch);
    }

    public void uniform2f(int location, float v0, float v1) {
        MemoryUtil.memPutFloat(scratch, v0);
        MemoryUtil.memPutFloat(scratch + 4, v1);
        programs().uniform(currentProgram(), location, 1, 2, true, scratch);
    }

    public void uniform3f(int location, float v0, float v1, float v2) {
        MemoryUtil.memPutFloat(scratch, v0);
        MemoryUtil.memPutFloat(scratch + 4, v1);
        MemoryUtil.memPutFloat(scratch + 8, v2);
        programs().uniform(currentProgram(), location, 1, 3, true, scratch);
    }

    public void uniform4f(int location, float v0, float v1, float v2, float v3) {
        MemoryUtil.memPutFloat(scratch, v0);
        MemoryUtil.memPutFloat(scratch + 4, v1);
        MemoryUtil.memPutFloat(scratch + 8, v2);
        MemoryUtil.memPutFloat(scratch + 12, v3);
        programs().uniform(currentProgram(), location, 1, 4, true, scratch);
    }

    public void uniform1fv(int location, FloatBuffer value) {
        if (value != null) programs().uniform(currentProgram(), location, value.remaining(), 1, true, memAddress(value));
    }

    public void uniform2(int location, FloatBuffer value) {
        if (value != null) programs().uniform(currentProgram(), location, value.remaining() / 2, 2, true, memAddress(value));
    }

    public void uniform3(int location, FloatBuffer value) {
        if (value != null) programs().uniform(currentProgram(), location, value.remaining() / 3, 3, true, memAddress(value));
    }

    public void uniform4(int location, FloatBuffer value) {
        if (value != null) programs().uniform(currentProgram(), location, value.remaining() / 4, 4, true, memAddress(value));
    }

    public void uniform3fv(int location, float[] values) {
        withFloats(values, a -> programs().uniform(currentProgram(), location, values.length / 3, 3, true, a));
    }

    public void uniform4fv(int location, float[] values) {
        withFloats(values, a -> programs().uniform(currentProgram(), location, values.length / 4, 4, true, a));
    }

    protected static void withFloats(float[] values, LongConsumer use) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final FloatBuffer b = stack.floats(values);
            use.accept(memAddress(b));
        }
    }

    public void uniform1iv(int location, IntBuffer value) {
        if (value != null) programs().uniform(currentProgram(), location, value.remaining(), 1, false, memAddress(value));
    }

    public void uniform2iv(int location, IntBuffer value) {
        if (value != null) programs().uniform(currentProgram(), location, value.remaining() / 2, 2, false, memAddress(value));
    }

    public void uniform3iv(int location, IntBuffer value) {
        if (value != null) programs().uniform(currentProgram(), location, value.remaining() / 3, 3, false, memAddress(value));
    }

    public void uniform4iv(int location, IntBuffer value) {
        if (value != null) programs().uniform(currentProgram(), location, value.remaining() / 4, 4, false, memAddress(value));
    }

    public void uniformMatrix2(int location, boolean transpose, FloatBuffer value) {
        if (value != null) programs().uniformMatrix(currentProgram(), location, value.remaining() / 4, 2, 2, transpose, memAddress(value));
    }

    public void uniformMatrix3(int location, boolean transpose, FloatBuffer value) {
        if (value != null) programs().uniformMatrix(currentProgram(), location, value.remaining() / 9, 3, 3, transpose, memAddress(value));
    }

    public void uniformMatrix4(int location, boolean transpose, FloatBuffer value) {
        if (value != null) programs().uniformMatrix(currentProgram(), location, value.remaining() / 16, 4, 4, transpose, memAddress(value));
    }

    public void getUniformfv(int program, int location, FloatBuffer params) {
        if (params != null) programs().getUniform(programs().program(program), location, memAddress(params), true);
    }

    public void getUniformiv(int program, int location, IntBuffer params) {
        if (params != null) programs().getUniform(programs().program(program), location, memAddress(params), false);
    }

    public void vertexAttrib2f(int index, float v0, float v1) {
        vertexAttrib4f(index, v0, v1, 0f, 1f);
    }

    public void vertexAttrib3f(int index, float v0, float v1, float v2) {
        vertexAttrib4f(index, v0, v1, v2, 1f);
    }

    public void vertexAttrib4f(int index, float v0, float v1, float v2, float v3) {
        if (index < 0 || index >= Vao.MAX_ATTRIBS) return;
        final GlState st = st();
        final float[] a = st.attribValue;
        a[index * 4] = v0;
        a[index * 4 + 1] = v1;
        a[index * 4 + 2] = v2;
        a[index * 4 + 3] = v3;
        st.attribVersion++;
    }

    public int genVertexArrays() {
        int id = nextVao++;
        while (vaos.containsKey(id)) id = nextVao++;
        vaos.put(id, new Vao(id));
        return id;
    }

    public void deleteVertexArrays(int array) {
        if (array == 0) return;
        final Vao removed = vaos.remove(array);
        convertedFrom.remove(removed);
        final int[] converted = convertedAttributes.remove(removed);
        if (converted != null) for (int name : converted) if (name != 0) buffers().delete(name);
        if (mainState.vao.id == array) mainState.vao = mainState.defaultVao;
        for (GlState s : otherStates) if (s.vao.id == array) s.vao = s.defaultVao;
    }

    public boolean isVertexArray(int array) {
        final Vao v = vaos.get(array);
        return v != null && v.named;
    }

    boolean isVertexArrayName(int array) {
        return vaos.containsKey(array);
    }

    /** The target a query was last begun on, which it keeps for good, or 0 if it has never begun. */
    int queryTarget(int id) {
        return queryTargets.get(id);
    }

    boolean queryIdActive(int id) {
        return activeQueries.containsValue(id);
    }

    boolean queryActive(int target) {
        return activeQueries.containsKey(target);
    }

    public int imagesPerStage() {
        return Math.min(GlState.MAX_IMAGE_UNITS, gpuCaps().maxPerStageDescriptorStorageImages);
    }

    public void bindVertexArray(int array) {
        final GlState st = st();
        if (array == 0) {
            st.vao = st.defaultVao;
            return;
        }
        Vao v = vaos.get(array);
        if (v == null) {
            v = new Vao(array);
            vaos.put(array, v);
        }
        v.named = true;
        st.vao = v;
    }

    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        if (type == GL41.GL_FIXED) warnOnce("GL_FIXED vertex attributes are not supported; values will be wrong");
        setAttribPointer(index, size, type, normalized, false, stride, pointer);
    }

    public void vertexAttribIPointer(int index, int size, int type, int stride, long pointer) {
        setAttribPointer(index, size, type, false, true, stride, pointer);
    }

    protected void setAttribPointer(int index, int size, int type, boolean normalized, boolean integer, int stride, long pointer) {
        if (index < 0 || index >= Vao.MAX_ATTRIBS) return;
        final GlState st = st();
        final Vao v = st.vao;
        final int buffer = st.arrayBuffer;
        if (v.size[index] != size || v.type[index] != type || v.normalized[index] != normalized || v.integer[index] != integer
            || v.bindingOf[index] != index || v.pointerStride[index] != stride || v.relativeOffset[index] != 0 || (v.bindingBuffer[index] == 0) != (buffer == 0)) {
            v.formatGen++;
        }
        v.size[index] = size;
        v.type[index] = type;
        v.normalized[index] = normalized;
        v.integer[index] = integer;
        v.isLong[index] = false;
        v.relativeOffset[index] = 0;
        v.bindingOf[index] = index;
        v.bindingBuffer[index] = buffer;
        v.bindingOffset[index] = pointer;
        // A stride of zero here means tightly packed, while the same in glBindVertexBuffer means no stride at all.
        v.bindingStride[index] = stride != 0 ? stride : v.packedSize(index);
        v.pointerStride[index] = stride;
        v.pointerOffset[index] = pointer;
    }

    public void enableVertexAttribArray(int index) {
        if (index < 0 || index >= Vao.MAX_ATTRIBS) return;
        final Vao v = st().vao;
        if (!v.enabled[index]) {
            v.enabled[index] = true;
            v.formatGen++;
        }
    }

    public void disableVertexAttribArray(int index) {
        if (index < 0 || index >= Vao.MAX_ATTRIBS) return;
        final Vao v = st().vao;
        if (v.enabled[index]) {
            v.enabled[index] = false;
            v.formatGen++;
        }
    }

    public void vertexAttribDivisor(int index, int divisor) {
        if (index < 0 || index >= Vao.MAX_ATTRIBS) return;
        vertexAttribBinding(index, index);
        vertexBindingDivisor(index, divisor);
    }

    public void vertexBindingDivisor(int binding, int divisor) {
        if (binding < 0 || binding >= Vao.MAX_BINDINGS) return;
        final Vao v = st().vao;
        if (v.bindingDivisor[binding] != divisor) {
            v.bindingDivisor[binding] = divisor;
            v.formatGen++;
        }
    }

    public void patchDefaultLevels(int pname, long values) {
        final float[] levels = pname == GL40.GL_PATCH_DEFAULT_OUTER_LEVEL ? st().patchDefaultOuter : st().patchDefaultInner;
        for (int i = 0; i < levels.length; i++) levels[i] = MemoryUtil.memGetFloat(values + i * (long) Float.BYTES);
    }

    public void patchVertices(int count) {
        if (count < 1 || count > 32) return;
        final GlState st = st();
        if (st.patchVertices == count) return;
        st.patchVertices = count;
        st.pipelineGen++;
    }

    public void bindVertexBuffer(int bindingindex, int buffer, long offset, int stride) {
        if (bindingindex < 0 || bindingindex >= Vao.MAX_BINDINGS) return;
        // A name never generated becomes a buffer, as glBindBuffer makes one in a compatibility profile.
        if (buffer != 0) buffers().getOrCreate(buffer).named = true;
        final Vao v = st().vao;
        if (v.bindingStride[bindingindex] != stride || (v.bindingBuffer[bindingindex] == 0) != (buffer == 0)) v.formatGen++;
        v.bindingBuffer[bindingindex] = buffer;
        v.bindingOffset[bindingindex] = offset;
        v.bindingStride[bindingindex] = stride;
    }

    public void vertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset) {
        if (attribindex < 0 || attribindex >= Vao.MAX_ATTRIBS) return;
        final Vao v = st().vao;
        v.size[attribindex] = size;
        v.type[attribindex] = type;
        v.normalized[attribindex] = normalized;
        v.integer[attribindex] = false;
        v.isLong[attribindex] = false;
        v.relativeOffset[attribindex] = relativeoffset;
        v.formatGen++;
    }

    public void vertexAttribIFormat(int attribindex, int size, int type, int relativeoffset) {
        if (attribindex < 0 || attribindex >= Vao.MAX_ATTRIBS) return;
        final Vao v = st().vao;
        v.size[attribindex] = size;
        v.type[attribindex] = type;
        v.normalized[attribindex] = false;
        v.integer[attribindex] = true;
        v.isLong[attribindex] = false;
        v.relativeOffset[attribindex] = relativeoffset;
        v.formatGen++;
    }

    public void vertexAttribBinding(int attribindex, int bindingindex) {
        if (attribindex < 0 || attribindex >= Vao.MAX_ATTRIBS || bindingindex < 0 || bindingindex >= Vao.MAX_BINDINGS) return;
        final Vao v = st().vao;
        if (v.bindingOf[attribindex] != bindingindex) {
            v.bindingOf[attribindex] = bindingindex;
            v.formatGen++;
        }
    }

    public int genBuffers() {
        return buffers().gen();
    }

    public int createBuffers() {
        final int id = buffers().gen();
        buffers().get(id).named = true;
        return id;
    }

    /** What a device's vertex fetch needs a stride to be a multiple of. */
    protected int vertexStrideAlignment() {
        return 1;
    }

    private boolean fetchesInexactly(Vao v, int loc) {
        final int type = v.type[loc], stride = v.stride(loc);
        if (stride < v.packedSize(loc) || stride % vertexStrideAlignment() != 0) return true;
        if (type == GL11.GL_DOUBLE || type == GL41.GL_FIXED) return true;
        // One narrow component short of a whole word, read as a float: what a device's vertex fetch does with those varies.
        if (!v.integer[loc] && !v.normalized[loc] && v.size[loc] == 1 && (type == GL11.GL_BYTE || type == GL11.GL_UNSIGNED_BYTE || type == GL11.GL_SHORT || type == GL11.GL_UNSIGNED_SHORT)) return true;
        if (!v.integer[loc] && (type == GL11.GL_INT || type == GL11.GL_UNSIGNED_INT)) return true;
        return (type == GL12.GL_UNSIGNED_INT_2_10_10_10_REV || type == GL33.GL_INT_2_10_10_10_REV) && (!v.normalized[loc] || v.size[loc] == GL12.GL_BGRA);
    }

    private void convertAttributes(GlState st) {
        final Vao v = st.vao;
        if (v.convertedGen != v.formatGen) {
            v.needsConversion = false;
            for (int loc = 0; loc < Vao.MAX_ATTRIBS; loc++) v.needsConversion |= v.enabled[loc] && v.bindingBuffer[v.bindingOf[loc]] != 0 && fetchesInexactly(v, loc);
            v.convertedGen = v.formatGen;
        }
        if (!v.needsConversion && v.convertedMask == 0) return;
        final ProgramBase<?> program = programs().current(st);
        final int inputs = program == null ? 0 : program.vertexInputMask;
        for (int loc = 0; loc < Vao.MAX_ATTRIBS; loc++) {
            final boolean doubles = program != null && program.inputDoubles[loc] > 0;
            final boolean wanted = v.needsConversion && (inputs >>> loc & 1) != 0 && v.enabled[loc] && v.bindingBuffer[v.bindingOf[loc]] != 0
                && (doubles ? wordsMisaligned(v, loc) : fetchesInexactly(v, loc));
            final int stride = !wanted ? -1 : unchangedSince(v, loc, doubles) ? v.convertedStride(loc) : remember(v, loc, doubles, doubles ? repackDoubles(v, loc) : convertAttribute(v, loc));
            final boolean was = (v.convertedMask >>> loc & 1) != 0, is = stride >= 0;
            if (is) {
                v.readConverted(loc, convertedNames(v)[loc], stride);
                v.convertedMask |= 1 << loc;
            } else if (was) {
                v.readConverted(loc, 0, 0);
                v.convertedMask &= ~(1 << loc);
            }
            if (is != was) {
                v.formatGen++;
                v.convertedGen = v.formatGen;
            }
        }
    }

    private final java.util.IdentityHashMap<Vao, int[]> convertedAttributes = new java.util.IdentityHashMap<>();

    /** What an attribute was last converted from: a conversion stands until its buffer's contents or the attribute's layout change. */
    private static final class ConvertedFrom {
        BufBase buffer;
        int version, type, size, stride;
        long gpuWrite, bufferSize, offset;
        boolean normalized, integer, doubles;
    }

    private final java.util.IdentityHashMap<Vao, ConvertedFrom[]> convertedFrom = new java.util.IdentityHashMap<>();

    private boolean unchangedSince(Vao v, int loc, boolean doubles) {
        if ((v.convertedMask >>> loc & 1) == 0) return false;
        final ConvertedFrom[] all = convertedFrom.get(v);
        final ConvertedFrom c = all == null ? null : all[loc];
        final int binding = v.bindingOf[loc];
        final B source = buffers().get(v.bindingBuffer[binding]);
        // A persistent mapping can be written at any time without telling GL.
        return c != null && source != null && !source.persistentlyMapped && c.buffer == source && c.version == source.contentVersion && c.gpuWrite == source.lastGpuWrite
            && c.bufferSize == source.size && c.offset == v.bindingOffset[binding] + v.relativeOffset[loc] && c.stride == v.bindingStride[binding] && c.type == v.type[loc]
            && c.size == v.size[loc] && c.normalized == v.normalized[loc] && c.integer == v.integer[loc] && c.doubles == doubles;
    }

    private int remember(Vao v, int loc, boolean doubles, int stride) {
        final ConvertedFrom c = convertedFrom.computeIfAbsent(v, k -> new ConvertedFrom[Vao.MAX_ATTRIBS])[loc] = new ConvertedFrom();
        final int binding = v.bindingOf[loc];
        final B source = buffers().get(v.bindingBuffer[binding]);
        c.buffer = source;
        if (source != null) {
            c.version = source.contentVersion;
            c.gpuWrite = source.lastGpuWrite;
            c.bufferSize = source.size;
        }
        c.offset = v.bindingOffset[binding] + v.relativeOffset[loc];
        c.stride = v.bindingStride[binding];
        c.type = v.type[loc];
        c.size = v.size[loc];
        c.normalized = v.normalized[loc];
        c.integer = v.integer[loc];
        c.doubles = doubles;
        return stride;
    }

    private int[] convertedNames(Vao v) {
        return convertedAttributes.computeIfAbsent(v, k -> new int[Vao.MAX_ATTRIBS]);
    }

    /** A double input reads its words as they are, which a device fetches only from offsets and strides of whole words. */
    private boolean wordsMisaligned(Vao v, int loc) {
        final long offset = v.bindingOffset[v.bindingOf[loc]] + v.relativeOffset[loc];
        return offset % 4 != 0 || v.stride(loc) % 4 != 0;
    }

    /** Copies a double attribute's elements, unchanged, to a buffer of its own where they start on whole words, returning its stride. */
    private int repackDoubles(Vao v, int loc) {
        final int binding = v.bindingOf[loc];
        final B source = buffers().get(v.bindingBuffer[binding]);
        final long offset = v.bindingOffset[binding] + v.relativeOffset[loc];
        final int element = v.size[loc] * Double.BYTES, stride = v.bindingStride[binding];
        if (source == null || source.size < offset + element) return -1;
        final int count = stride == 0 ? 1 : (int) ((source.size - offset - element) / stride + 1);
        final long read = stride == 0 ? element : (count - 1L) * stride + element;
        final long from = MemoryUtil.nmemAlloc(read), to = MemoryUtil.nmemAlloc((long) count * element);
        try {
            buffers().read(source, offset, read, from);
            for (int i = 0; i < count; i++) MemoryUtil.memCopy(from + (long) i * stride, to + (long) i * element, element);
            final int[] names = convertedNames(v);
            if (names[loc] == 0) names[loc] = buffers().gen();
            buffers().data(buffers().getOrCreate(names[loc]), (long) count * element, to, GL15.GL_STREAM_DRAW);
        } finally {
            MemoryUtil.nmemFree(from);
            MemoryUtil.nmemFree(to);
        }
        return stride == 0 ? 0 : element;
    }

    /** Writes what a draw can read of an attribute to a buffer of its own, returning its stride, or -1 if the buffer holds no whole element. */
    private int convertAttribute(Vao v, int loc) {
        final int binding = v.bindingOf[loc];
        final B source = buffers().get(v.bindingBuffer[binding]);
        final long offset = v.bindingOffset[binding] + v.relativeOffset[loc];
        final int element = v.packedSize(loc), stride = v.bindingStride[binding];
        if (source == null || source.size < offset + element) return -1;
        final int count = stride == 0 ? 1 : (int) ((source.size - offset - element) / stride + 1);
        final long read = stride == 0 ? element : (count - 1L) * stride + element;
        final int components = AttribConvert.components(v.size[loc], v.type[loc]);
        final long written = (long) count * components * Float.BYTES;
        final long from = MemoryUtil.nmemAlloc(read), to = MemoryUtil.nmemAlloc(written);
        try {
            buffers().read(source, offset, read, from);
            AttribConvert.convert(from, stride, count, v.size[loc], v.type[loc], v.normalized[loc], v.integer[loc], to);
            final int[] names = convertedNames(v);
            if (names[loc] == 0) names[loc] = buffers().gen();
            buffers().data(buffers().getOrCreate(names[loc]), written, to, GL15.GL_STREAM_DRAW);
        } finally {
            MemoryUtil.nmemFree(from);
            MemoryUtil.nmemFree(to);
        }
        return stride == 0 ? 0 : components * Float.BYTES;
    }

    public void deleteBuffers(int buffer) {
        if (buffer == 0) return;
        buffers().delete(buffer);
        unbindBufferEverywhere(buffer);
    }

    public void deleteBuffers(IntBuffer ids) {
        for (int i = ids.position(); i < ids.limit(); i++) deleteBuffers(ids.get(i));
    }

    protected void unbindBufferEverywhere(int id) {
        unbindBuffer(mainState, id);
        for (GlState s : otherStates) unbindBuffer(s, id);
        for (Vao v : vaos.values()) unbindFromVao(v, id);
        unbindFromVao(mainState.defaultVao, id);
        for (GlState s : otherStates) unbindFromVao(s.defaultVao, id);
    }

    protected static void unbindFromVao(Vao v, int id) {
        if (v.elementBuffer == id) v.elementBuffer = 0;
        for (int i = 0; i < Vao.MAX_BINDINGS; i++) if (v.bindingBuffer[i] == id) {
            v.bindingBuffer[i] = 0;
            v.formatGen++;
        }
    }

    protected static void unbindBuffer(GlState s, int id) {
        if (s.arrayBuffer == id) s.arrayBuffer = 0;
        if (s.uniformBuffer == id) s.uniformBuffer = 0;
        if (s.storageBuffer == id) s.storageBuffer = 0;
        if (s.copyRead == id) s.copyRead = 0;
        if (s.copyWrite == id) s.copyWrite = 0;
        if (s.pixelPack == id) s.pixelPack = 0;
        if (s.pixelUnpack == id) s.pixelUnpack = 0;
        if (s.drawIndirect == id) s.drawIndirect = 0;
        if (s.dispatchIndirect == id) s.dispatchIndirect = 0;
        if (s.parameterBuffer == id) s.parameterBuffer = 0;
        if (s.atomicBuffer == id) s.atomicBuffer = 0;
        if (s.textureBuffer == id) s.textureBuffer = 0;
        if (s.queryBuffer == id) s.queryBuffer = 0;
        if (s.xfbBuffer == id) s.xfbBuffer = 0;
        for (int i = 0; i < GlState.MAX_BUFFER_BINDINGS; i++) {
            if (s.uboBuffer[i] == id) {
                s.uboBuffer[i] = 0;
                s.uboOffset[i] = 0;
                s.uboSize[i] = 0;
            }
            if (s.ssboBuffer[i] == id) {
                s.ssboBuffer[i] = 0;
                s.ssboOffset[i] = 0;
                s.ssboSize[i] = 0;
            }
            if (s.atomicBufferAt[i] == id) {
                s.atomicBufferAt[i] = 0;
                s.atomicOffset[i] = 0;
                s.atomicSize[i] = 0;
            }
        }
    }

    public boolean isBuffer(int buffer) {
        return buffers().exists(buffer) && buffers().get(buffer).named;
    }

    protected int boundBuffer(GlState st, int target) {
        return switch (target) {
            case GL15.GL_ARRAY_BUFFER -> st.arrayBuffer;
            case GL15.GL_ELEMENT_ARRAY_BUFFER -> st.vao.elementBuffer;
            case GL31.GL_UNIFORM_BUFFER -> st.uniformBuffer;
            case GL43.GL_SHADER_STORAGE_BUFFER -> st.storageBuffer;
            case GL31.GL_COPY_READ_BUFFER -> st.copyRead;
            case GL31.GL_COPY_WRITE_BUFFER -> st.copyWrite;
            case GL21.GL_PIXEL_PACK_BUFFER -> st.pixelPack;
            case GL21.GL_PIXEL_UNPACK_BUFFER -> st.pixelUnpack;
            case GL40.GL_DRAW_INDIRECT_BUFFER -> st.drawIndirect;
            case GL43.GL_DISPATCH_INDIRECT_BUFFER -> st.dispatchIndirect;
            case GL31.GL_TEXTURE_BUFFER -> st.textureBuffer;
            case GL44.GL_QUERY_BUFFER -> st.queryBuffer;
            case GL42.GL_ATOMIC_COUNTER_BUFFER -> st.atomicBuffer;
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER -> st.xfbBuffer;
            case ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB -> st.parameterBuffer;
            default -> 0;
        };
    }

    protected B boundBuffer(int target) {
        return buffers().get(boundBuffer(st(), target));
    }

    public void bindBuffer(int target, int buffer) {
        final GlState st = st();
        if (buffer != 0) buffers().getOrCreate(buffer).named = true;
        switch (target) {
            case GL15.GL_ARRAY_BUFFER -> st.arrayBuffer = buffer;
            case GL15.GL_ELEMENT_ARRAY_BUFFER -> st.vao.elementBuffer = buffer;
            case GL31.GL_UNIFORM_BUFFER -> st.uniformBuffer = buffer;
            case GL43.GL_SHADER_STORAGE_BUFFER -> st.storageBuffer = buffer;
            case GL31.GL_COPY_READ_BUFFER -> st.copyRead = buffer;
            case GL31.GL_COPY_WRITE_BUFFER -> st.copyWrite = buffer;
            case GL21.GL_PIXEL_PACK_BUFFER -> st.pixelPack = buffer;
            case GL21.GL_PIXEL_UNPACK_BUFFER -> st.pixelUnpack = buffer;
            case GL40.GL_DRAW_INDIRECT_BUFFER -> st.drawIndirect = buffer;
            case GL43.GL_DISPATCH_INDIRECT_BUFFER -> st.dispatchIndirect = buffer;
            case GL31.GL_TEXTURE_BUFFER -> st.textureBuffer = buffer;
            case GL44.GL_QUERY_BUFFER -> st.queryBuffer = buffer;
            case GL42.GL_ATOMIC_COUNTER_BUFFER -> st.atomicBuffer = buffer;
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER -> st.xfbBuffer = buffer;
            case ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB -> st.parameterBuffer = buffer;
            default -> { }
        }
    }

    public void bindBufferBase(int target, int index, int buffer) {
        bindBufferRange(target, index, buffer, 0L, 0L);
    }

    public void bindBufferRange(int target, int index, int buffer, long offset, long size) {
        if (index < 0 || index >= GlState.MAX_BUFFER_BINDINGS) return;
        final GlState st = st();
        if (buffer != 0) {
            buffers().getOrCreate(buffer);
        } else {
            offset = 0L;
            size = 0L;
        }
        switch (target) {
            case GL31.GL_UNIFORM_BUFFER -> {
                st.uboBuffer[index] = buffer;
                st.uboOffset[index] = offset;
                st.uboSize[index] = size;
                st.uniformBuffer = buffer;
            }
            case GL43.GL_SHADER_STORAGE_BUFFER -> {
                st.ssboBuffer[index] = buffer;
                st.ssboOffset[index] = offset;
                st.ssboSize[index] = size;
                st.storageBuffer = buffer;
            }
            case GL42.GL_ATOMIC_COUNTER_BUFFER -> {
                st.atomicBufferAt[index] = buffer;
                st.atomicOffset[index] = offset;
                st.atomicSize[index] = size;
                st.atomicBuffer = buffer;
            }
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER -> {
                GlTransformFeedback.bindBuffer(index, buffer, offset, size);
                st.xfbBuffer = buffer;
            }
            default -> bindBuffer(target, buffer);
        }
    }

    public int getIndexedBufferBinding(int target, int index) {
        if (index < 0 || index >= GlState.MAX_BUFFER_BINDINGS) return 0;
        final GlState st = st();
        return switch (target) {
            case GL43.GL_SHADER_STORAGE_BUFFER -> st.ssboBuffer[index];
            case GL42.GL_ATOMIC_COUNTER_BUFFER -> st.atomicBufferAt[index];
            default -> st.uboBuffer[index];
        };
    }

    public int getIntegerIndexed(int pname, int index) {
        final GlState st = st();
        if (index < 0) return 0;
        final long feedback = GlTransformFeedback.indexedQuery(pname, index);
        if (feedback >= 0L) return (int) feedback;
        return switch (pname) {
            case GL43.GL_VERTEX_BINDING_BUFFER -> index < Vao.MAX_BINDINGS ? st.vao.bindingBuffer[index] : 0;
            case GL43.GL_VERTEX_BINDING_OFFSET -> index < Vao.MAX_BINDINGS ? (int) st.vao.bindingOffset[index] : 0;
            case GL43.GL_VERTEX_BINDING_STRIDE -> index < Vao.MAX_BINDINGS ? st.vao.bindingStride[index] : 0;
            case GL43.GL_VERTEX_BINDING_DIVISOR -> index < Vao.MAX_BINDINGS ? st.vao.bindingDivisor[index] : 0;
            case GL32.GL_SAMPLE_MASK_VALUE -> index == 0 ? st.sampleMaskWord : 0;
            case GL31.GL_UNIFORM_BUFFER_BINDING -> index < GlState.MAX_BUFFER_BINDINGS ? st.uboBuffer[index] : 0;
            case GL43.GL_SHADER_STORAGE_BUFFER_BINDING -> index < GlState.MAX_BUFFER_BINDINGS ? st.ssboBuffer[index] : 0;
            case GL31.GL_UNIFORM_BUFFER_START -> index < GlState.MAX_BUFFER_BINDINGS ? (int) st.uboOffset[index] : 0;
            case GL31.GL_UNIFORM_BUFFER_SIZE -> index < GlState.MAX_BUFFER_BINDINGS ? (int) st.uboSize[index] : 0;
            case GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT -> MAX_COMPUTE_WORK_GROUP_COUNT;
            case GL43.GL_MAX_COMPUTE_WORK_GROUP_SIZE -> index == 2 ? 64 : 1024;
            case GL30.GL_COLOR_WRITEMASK -> index >= GlState.MAX_DRAW_BUFFERS || st.colorMask[index][0] ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL30.GL_DRAW_BUFFER0 -> drawBufferEnum(st, index);
            case GL42.GL_IMAGE_BINDING_NAME -> index < GlState.MAX_IMAGE_UNITS ? st.imageTex[index] : 0;
            case GL42.GL_IMAGE_BINDING_LEVEL -> index < GlState.MAX_IMAGE_UNITS ? st.imageLevel[index] : 0;
            case GL42.GL_IMAGE_BINDING_LAYERED -> index < GlState.MAX_IMAGE_UNITS && st.imageLayered[index] ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL42.GL_IMAGE_BINDING_LAYER -> index < GlState.MAX_IMAGE_UNITS ? st.imageLayer[index] : 0;
            case GL42.GL_IMAGE_BINDING_ACCESS -> index < GlState.MAX_IMAGE_UNITS ? st.imageAccess[index] : GL15.GL_READ_ONLY;
            case GL42.GL_IMAGE_BINDING_FORMAT -> index < GlState.MAX_IMAGE_UNITS ? st.imageFormat[index] : 0;
            case GL43.GL_SHADER_STORAGE_BUFFER_START -> index < GlState.MAX_BUFFER_BINDINGS ? (int) st.ssboOffset[index] : 0;
            case GL43.GL_SHADER_STORAGE_BUFFER_SIZE -> index < GlState.MAX_BUFFER_BINDINGS ? (int) st.ssboSize[index] : 0;
            case GL42.GL_ATOMIC_COUNTER_BUFFER_BINDING -> index < GlState.MAX_BUFFER_BINDINGS ? st.atomicBufferAt[index] : 0;
            case GL42.GL_ATOMIC_COUNTER_BUFFER_START -> index < GlState.MAX_BUFFER_BINDINGS ? (int) st.atomicOffset[index] : 0;
            case GL42.GL_ATOMIC_COUNTER_BUFFER_SIZE -> index < GlState.MAX_BUFFER_BINDINGS ? (int) st.atomicSize[index] : 0;
            case GL33.GL_SAMPLER_BINDING -> index < GlState.MAX_UNITS ? st.samplers[index] : 0;
            case GL11.GL_TEXTURE_BINDING_1D, GL11.GL_TEXTURE_BINDING_2D, GL12.GL_TEXTURE_BINDING_3D, GL30.GL_TEXTURE_BINDING_1D_ARRAY, GL30.GL_TEXTURE_BINDING_2D_ARRAY, GL31.GL_TEXTURE_BINDING_RECTANGLE,
                 GL31.GL_TEXTURE_BINDING_BUFFER, GL13.GL_TEXTURE_BINDING_CUBE_MAP, GL40.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY, GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE, GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY -> {
                if (index >= GlState.MAX_UNITS) yield 0;
                final int active = st.activeUnit;
                st.activeUnit = index;
                final int bound = getInteger(pname);
                st.activeUnit = active;
                yield bound;
            }
            default -> getInteger(pname);
        };
    }

    public void bufferData(int target, long size, int usage) {
        final B b = boundBuffer(target);
        if (b != null) buffers().data(b, size, 0L, usage);
    }

    public void bufferData(int target, ByteBuffer data, int usage) {
        final B b = boundBuffer(target);
        if (b != null) buffers().data(b, data == null ? 0 : data.remaining(), data == null ? 0L : memAddress(data), usage);
    }

    public void bufferData(int target, FloatBuffer data, int usage) {
        final B b = boundBuffer(target);
        if (b != null) buffers().data(b, data.remaining() * 4L, memAddress(data), usage);
    }

    public void bufferData(int target, ShortBuffer data, int usage) {
        final B b = boundBuffer(target);
        if (b != null) buffers().data(b, data.remaining() * 2L, memAddress(data), usage);
    }

    public void bufferData(int target, IntBuffer data, int usage) {
        final B b = boundBuffer(target);
        if (b != null) buffers().data(b, data.remaining() * 4L, memAddress(data), usage);
    }

    public void bufferData(int target, DoubleBuffer data, int usage) {
        final B b = boundBuffer(target);
        if (b != null) buffers().data(b, data.remaining() * 8L, memAddress(data), usage);
    }

    public void bufferData(int target, int[] data, int usage) {
        final IntBuffer heap = MemoryUtil.memAllocInt(data.length);
        try {
            heap.put(data).flip();
            bufferData(target, heap, usage);
        } finally {
            MemoryUtil.memFree(heap);
        }
    }

    public void bufferData(int target, float[] data, int usage) {
        final FloatBuffer heap = MemoryUtil.memAllocFloat(data.length);
        try {
            heap.put(data).flip();
            bufferData(target, heap, usage);
        } finally {
            MemoryUtil.memFree(heap);
        }
    }

    public void bufferSubData(int target, long offset, ByteBuffer data) {
        final B b = boundBuffer(target);
        if (b != null && data != null) {
            b.contentVersion++;
            buffers().write(b, offset, data.remaining(), memAddress(data));
        }
    }

    public void bufferSubData(int target, long offset, ShortBuffer data) {
        final B b = boundBuffer(target);
        if (b != null && data != null) {
            b.contentVersion++;
            buffers().write(b, offset, data.remaining() * 2L, memAddress(data));
        }
    }

    public void bufferSubData(int target, long offset, IntBuffer data) {
        final B b = boundBuffer(target);
        if (b != null && data != null) {
            b.contentVersion++;
            buffers().write(b, offset, data.remaining() * 4L, memAddress(data));
        }
    }

    public void bufferSubData(int target, long offset, FloatBuffer data) {
        final B b = boundBuffer(target);
        if (b != null && data != null) {
            b.contentVersion++;
            buffers().write(b, offset, data.remaining() * 4L, memAddress(data));
        }
    }

    public void bufferSubData(int target, long offset, DoubleBuffer data) {
        final B b = boundBuffer(target);
        if (b != null && data != null) {
            b.contentVersion++;
            buffers().write(b, offset, data.remaining() * 8L, memAddress(data));
        }
    }

    public void bufferStorage(int target, ByteBuffer data, int flags) {
        final B b = boundBuffer(target);
        if (b != null) buffers().storage(b, data == null ? 0 : data.remaining(), data == null ? 0L : memAddress(data), flags);
    }

    public void bufferStorage(int target, long size, int flags) {
        final B b = boundBuffer(target);
        if (b != null) buffers().storage(b, size, 0L, flags);
    }

    /** A range past the end of the buffer is GL's to refuse, and reading it would fault in the driver's place. */
    private void readBufferRange(int target, long offset, long size, long dst) {
        final B b = boundBuffer(target);
        if (b == null || (b.mapped && (b.mapAccess & GL44.GL_MAP_PERSISTENT_BIT) == 0)) {
            recordError(GL11.GL_INVALID_OPERATION);
        } else if (offset < 0 || size < 0 || offset + size > b.size) {
            recordError(GL11.GL_INVALID_VALUE);
        } else {
            buffers().read(b, offset, size, dst);
        }
    }

    public void getBufferSubData(int target, long offset, ByteBuffer data) {
        writeBackImageWrites();
        if (data != null) readBufferRange(target, offset, data.remaining(), memAddress(data));
    }

    public void getBufferSubData(int target, long offset, ShortBuffer data) {
        if (data != null) readBufferRange(target, offset, data.remaining() * 2L, memAddress(data));
    }

    public void getBufferSubData(int target, long offset, IntBuffer data) {
        if (data != null) readBufferRange(target, offset, data.remaining() * 4L, memAddress(data));
    }

    public void getBufferSubData(int target, long offset, FloatBuffer data) {
        if (data != null) readBufferRange(target, offset, data.remaining() * 4L, memAddress(data));
    }

    public void getBufferSubData(int target, long offset, DoubleBuffer data) {
        if (data != null) readBufferRange(target, offset, data.remaining() * 8L, memAddress(data));
    }

    public int getBufferParameteri(int target, int pname) {
        final B b = boundBuffer(target);
        if (b == null) return 0;
        return switch (pname) {
            case GL15.GL_BUFFER_SIZE -> (int) Math.min(Integer.MAX_VALUE, b.size);
            case GL15.GL_BUFFER_USAGE -> b.usageHint == 0 ? GL15.GL_STATIC_DRAW : b.usageHint;
            case GL15.GL_BUFFER_MAPPED -> b.mapped ? 1 : 0;
            case GL15.GL_BUFFER_ACCESS -> {
                final int rw = b.mapped ? b.mapAccess & (GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT) : 0;
                yield rw == GL30.GL_MAP_READ_BIT ? GL15.GL_READ_ONLY : rw == GL30.GL_MAP_WRITE_BIT ? GL15.GL_WRITE_ONLY : GL15.GL_READ_WRITE;
            }
            case GL30.GL_BUFFER_ACCESS_FLAGS -> b.mapped ? b.mapAccess : 0;
            case GL30.GL_BUFFER_MAP_OFFSET -> b.mapped ? (int) b.mapOffset : 0;
            case GL30.GL_BUFFER_MAP_LENGTH -> b.mapped ? (int) b.mapLength : 0;
            case GL44.GL_BUFFER_IMMUTABLE_STORAGE -> b.immutable ? 1 : 0;
            case GL44.GL_BUFFER_STORAGE_FLAGS -> b.storageFlags;
            default -> 0;
        };
    }

    public ByteBuffer mapBuffer(int target, int access) {
        final B b = boundBuffer(target);
        if (b == null) return null;
        final int flags = switch (access) {
            case GL15.GL_READ_ONLY -> GL30.GL_MAP_READ_BIT;
            case GL15.GL_WRITE_ONLY -> GL30.GL_MAP_WRITE_BIT;
            default -> GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT;
        };
        return buffers().map(b, 0, b.size, flags);
    }

    public ByteBuffer mapBuffer(int target, int access, long length, ByteBuffer oldBuffer) {
        return mapBuffer(target, access);
    }

    public ByteBuffer mapBufferRange(int target, long offset, long length, int access) {
        return buffers().map(boundBuffer(target), offset, length, access);
    }

    public boolean unmapBuffer(int target) {
        return buffers().unmap(boundBuffer(target));
    }

    public void flushMappedBufferRange(int target, long offset, long length) {
        buffers().flushMapped(boundBuffer(target), offset, length);
    }

    public void copyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        final B read = boundBuffer(readTarget);
        final B write = boundBuffer(writeTarget);
        final boolean followed = copyFollowsIntoIndexShadow(read, write, readOffset, writeOffset, size);
        if (write != null) write.contentVersion++;
        buffers().copy(read, write, readOffset, writeOffset, size);
        if (followed) {
            MemoryUtil.memCopy(read.address() + readOffset, write.indexShadow + writeOffset, size);
            write.indexShadowVersion = write.contentVersion;
            write.indexShadowGpuWrites = write.gpuWrites;
        }
    }

    public void clearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type, ByteBuffer data) {
        final B b = boundBuffer(target);
        if (b == null) return;
        b.contentVersion++;
        final int pattern = data == null ? 0 : data.remaining();
        if (pattern == 0) {
            buffers().fill(b, offset, size, 0);
        } else if (pattern == 1) {
            buffers().fill(b, offset, size, (data.get(data.position()) & 0xFF) * 0x01010101);
        } else if (pattern == 4) {
            buffers().fill(b, offset, size, MemoryUtil.memGetInt(memAddress(data)));
        } else {
            final long tiled = MemoryUtil.nmemAlloc(size);
            try {
                for (long o = 0; o < size; o += pattern) MemoryUtil.memCopy(memAddress(data), tiled + o, Math.min(pattern, size - o));
                buffers().write(b, offset, size, tiled);
            } finally {
                MemoryUtil.nmemFree(tiled);
            }
        }
    }

    public void clearBufferData(int target, int internalformat, int format, int type, ByteBuffer data) {
        final B b = boundBuffer(target);
        if (b == null) return;
        clearBufferSubData(target, internalformat, 0, b.size, format, type, data);
    }

    public void namedBufferData(int buffer, long size, int usage) {
        final B b = buffers().getOrCreate(buffer);
        if (b != null) buffers().data(b, size, 0L, usage);
    }

    public void namedBufferData(int buffer, ByteBuffer data, int usage) {
        final B b = buffers().getOrCreate(buffer);
        if (b != null) buffers().data(b, data == null ? 0 : data.remaining(), data == null ? 0L : memAddress(data), usage);
    }

    public void namedBufferData(int buffer, FloatBuffer data, int usage) {
        final B b = buffers().getOrCreate(buffer);
        if (b != null) buffers().data(b, data.remaining() * 4L, memAddress(data), usage);
    }

    public void namedBufferSubData(int buffer, long offset, ByteBuffer data) {
        final B b = buffers().get(buffer);
        if (b != null && data != null) {
            b.contentVersion++;
            buffers().write(b, offset, data.remaining(), memAddress(data));
        }
    }

    /**
     * Every device claims 4.6 so code that checks only the version gets everything that works. An optional feature the device lacks
     * keeps its extension unadvertised for the code that does check.
     */
    private static final int[][] GL_VERSIONS = {{1, 1}, {1, 2}, {1, 3}, {1, 4}, {1, 5}, {2, 0}, {2, 1}, {3, 0}, {3, 1}, {3, 2}, {3, 3}, {4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}, {4, 5}, {4, 6}};

    public static Set<String> advertisedCapabilities(GpuCaps gpu) {
        final Set<String> caps = new HashSet<>();
        for (int[] v : GL_VERSIONS) caps.add("OpenGL" + v[0] + v[1]);
        for (String e : extensions(gpu)) caps.add(e);
        return caps;
    }

    public static String[] extensions(GpuCaps gpu) {
        final List<String> out = new ArrayList<>();
        for (String e : EXTENSIONS) {
            if (e.equals("GL_ARB_shader_draw_parameters") && !gpu.shaderDrawParameters) continue;
            if (e.equals("GL_ARB_indirect_parameters") && !gpu.drawIndirectCount) continue;
            if (e.equals("GL_ARB_base_instance") && !gpu.drawIndirectFirstInstance) continue;
            if (e.equals("GL_ARB_texture_cube_map_array") && !gpu.imageCubeArray) continue;
            out.add(e);
        }
        if (gpu.textureCompressionBC) {
            out.add("GL_EXT_texture_compression_s3tc");
            out.add("GL_EXT_texture_compression_rgtc");
            out.add("GL_ARB_texture_compression_rgtc");
            out.add("GL_ARB_texture_compression_bptc");
        }
        // A device without these stages has them run as compute shaders.
        out.add("GL_ARB_geometry_shader4");
        out.add("GL_ARB_tessellation_shader");
        return out.toArray(new String[0]);
    }

    protected String[] ext() {
        if (cachedExtensions == null) cachedExtensions = extensions(gpuCaps());
        return cachedExtensions;
    }

    /**
     * The GLSL versions shaders may declare, newest first, as GL 4.3 lists them for glGetStringi. The empty string says a
     * shader without a #version is taken too.
     */
    public static final String[] SHADING_LANGUAGE_VERSIONS = {"460 core", "450 core", "440 core", "430 core", "420 core", "410 core", "400 core",
        "330 core", "150 core", "140", "130", "120", "110", ""};

    public String getStringi(int name, int index) {
        if (name == GL20.GL_SHADING_LANGUAGE_VERSION) return index >= 0 && index < SHADING_LANGUAGE_VERSIONS.length ? SHADING_LANGUAGE_VERSIONS[index] : "";
        final String[] e = ext();
        return name == GL11.GL_EXTENSIONS && index >= 0 && index < e.length ? e[index] : "";
    }

    public final GlValidation validation() {
        return validation;
    }

    /** What glGetInteger answers for state GL lets it ask about, but which has a truer type or which nothing here reads. */
    private int otherInteger(GlState st, int pname) {
        if (GlValidation.cap(pname)) return isEnabled(pname) ? 1 : 0;
        return switch (pname) {
            case GL43.GL_NUM_SHADING_LANGUAGE_VERSIONS -> SHADING_LANGUAGE_VERSIONS.length;
            case GL30.GL_CLAMP_VERTEX_COLOR -> clampVertexColor;
            case GL30.GL_CLAMP_FRAGMENT_COLOR -> clampFragmentColor;
            case GL30.GL_CLAMP_READ_COLOR -> clampReadColor;
            case GL32.GL_QUADS_FOLLOW_PROVOKING_VERTEX_CONVENTION -> GL11.GL_TRUE;
            case GL31.GL_TEXTURE_BUFFER_DATA_STORE_BINDING -> {
                final BufferTexture t = bufferTextures.get(st.textures[st.activeUnit][GlState.T_BUFFER]);
                yield t == null ? 0 : t.buffer;
            }
            case GL11.GL_POINT_SIZE, GL11.GL_LINE_WIDTH, GL11.GL_DEPTH_CLEAR_VALUE, GL11.GL_POLYGON_OFFSET_UNITS, GL11.GL_POLYGON_OFFSET_FACTOR, GL13.GL_SAMPLE_COVERAGE_VALUE, GL14.GL_MAX_TEXTURE_LOD_BIAS -> Math.round(getFloat(pname));
            case GL11.GL_POINT_SIZE_RANGE, GL12.GL_ALIASED_POINT_SIZE_RANGE, GL11.GL_LINE_WIDTH_RANGE, GL12.GL_ALIASED_LINE_WIDTH_RANGE, GL11.GL_DEPTH_RANGE, GL11.GL_COLOR_CLEAR_VALUE, GL14.GL_BLEND_COLOR -> {
                final FloatBuffer values = BufferUtils.createFloatBuffer(16);
                getFloat(pname, values);
                yield Math.round(values.get(0));
            }
            case GL11.GL_POINT_SIZE_GRANULARITY, GL11.GL_LINE_WIDTH_GRANULARITY, GL11.GL_DOUBLEBUFFER, GL41.GL_SHADER_COMPILER -> 1;
            case GL40.GL_TRANSFORM_FEEDBACK_BUFFER_PAUSED, GL40.GL_TRANSFORM_FEEDBACK_BUFFER_ACTIVE, GL40.GL_TRANSFORM_FEEDBACK_BINDING -> GlTransformFeedback.currentState(pname);
            case GL11.GL_STEREO, GL11.GL_UNPACK_LSB_FIRST, GL11.GL_PACK_LSB_FIRST,
                 GL40.GL_MIN_SAMPLE_SHADING_VALUE, GL41.GL_VIEWPORT_SUBPIXEL_BITS, GL41.GL_PROGRAM_BINARY_FORMATS, GL41.GL_SHADER_BINARY_FORMATS -> 0;
            case GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE -> st.textures[st.activeUnit][GlState.T_2D_MS];
            case GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY -> st.textures[st.activeUnit][GlState.T_2D_MS_ARRAY];
            case GL11.GL_UNPACK_SWAP_BYTES -> st.unpack.swapBytes ? 1 : 0;
            case GL11.GL_PACK_SWAP_BYTES -> st.pack.swapBytes ? 1 : 0;
            case GL13.GL_SAMPLE_COVERAGE_INVERT -> st.sampleCoverageInvert ? 1 : 0;
            case GL12.GL_PACK_SKIP_IMAGES -> st.pack.skipImages;
            case GL12.GL_PACK_IMAGE_HEIGHT -> st.pack.imageHeight;
            case GL31.GL_TEXTURE_BUFFER -> boundBuffer(st, GL31.GL_TEXTURE_BUFFER);
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING -> boundBuffer(st, GL30.GL_TRANSFORM_FEEDBACK_BUFFER);
            case GL30.GL_MAX_CLIP_DISTANCES -> 8;
            case GL11.GL_SUBPIXEL_BITS -> 4;
            case GL41.GL_MAX_VIEWPORTS -> GlState.MAX_VIEWPORTS;
            case GL43.GL_MAX_ELEMENT_INDEX -> Integer.MAX_VALUE;
            case GL43.GL_MAX_VERTEX_ATTRIB_BINDINGS -> Vao.MAX_ATTRIBS;
            case GL43.GL_MAX_VERTEX_ATTRIB_RELATIVE_OFFSET -> 2047;
            case GL44.GL_MAX_VERTEX_ATTRIB_STRIDE -> 2048;
            case GL45.GL_MAX_CULL_DISTANCES -> 8;
            case GL45.GL_MAX_COMBINED_CLIP_AND_CULL_DISTANCES -> getInteger(GL30.GL_MAX_CLIP_DISTANCES);
            case GL43.GL_MAX_COMPUTE_UNIFORM_BLOCKS -> getInteger(GL31.GL_MAX_VERTEX_UNIFORM_BLOCKS);
            case GL43.GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS -> getInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS);
            case GL43.GL_MAX_COMPUTE_UNIFORM_COMPONENTS -> 4096;
            case GL43.GL_MAX_COMPUTE_ATOMIC_COUNTERS, GL42.GL_MAX_COMBINED_ATOMIC_COUNTERS, GL42.GL_MAX_FRAGMENT_ATOMIC_COUNTERS -> 8;
            case GL43.GL_MAX_COMPUTE_ATOMIC_COUNTER_BUFFERS, GL42.GL_MAX_ATOMIC_COUNTER_BUFFER_BINDINGS, GL42.GL_MAX_FRAGMENT_ATOMIC_COUNTER_BUFFERS, GL42.GL_MAX_COMBINED_ATOMIC_COUNTER_BUFFERS -> 8;
            case GL42.GL_MAX_ATOMIC_COUNTER_BUFFER_SIZE -> 16384;
            case 0x91B0 -> maxShaderCompilerThreads;
            case GL43.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE -> 32768;
            case GL41.GL_VIEWPORT_BOUNDS_RANGE -> -32768;
            case GL41.GL_LAYER_PROVOKING_VERTEX, GL41.GL_VIEWPORT_INDEX_PROVOKING_VERTEX -> GL41.GL_UNDEFINED_VERTEX;
            case GL31.GL_MAX_RECTANGLE_TEXTURE_SIZE -> gpuCaps().maxImageDimension2D;
            case GL13.GL_NUM_COMPRESSED_TEXTURE_FORMATS -> textures().compressedFormats().length;
            case GL33.GL_MAX_DUAL_SOURCE_DRAW_BUFFERS, GL32.GL_MAX_SAMPLE_MASK_WORDS -> 1;
            // Only a geometry shader can emit to a stream past the first, which then captures nothing, as a stream no shader writes does.
            case GL40.GL_MAX_VERTEX_STREAMS -> 4;
            case GL44.GL_MIN_MAP_BUFFER_ALIGNMENT -> 64;
            case GL41.GL_IMPLEMENTATION_COLOR_READ_TYPE -> GL11.GL_UNSIGNED_BYTE;
            case GL41.GL_IMPLEMENTATION_COLOR_READ_FORMAT -> GL11.GL_RGBA;
            case GL31.GL_MAX_TEXTURE_BUFFER_SIZE -> 65536;
            case GL30.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_COMPONENTS, GL30.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS, GL40.GL_MAX_TRANSFORM_FEEDBACK_BUFFERS -> 4;
            case GL30.GL_MAX_TRANSFORM_FEEDBACK_INTERLEAVED_COMPONENTS -> 64;
            case GL40.GL_MAX_SUBROUTINES -> 256;
            case GL40.GL_MAX_SUBROUTINE_UNIFORM_LOCATIONS -> 1024;
            case GL33.GL_TIMESTAMP -> (int) System.nanoTime();
            case GL40.GL_FRAGMENT_INTERPOLATION_OFFSET_BITS -> 4;
            case GL40.GL_MIN_FRAGMENT_INTERPOLATION_OFFSET -> -1;
            case GL40.GL_MAX_FRAGMENT_INTERPOLATION_OFFSET -> 0;
            // Stages before the fragment one write storage where the device lets a vertex shader, and always where they run as compute shaders.
            case GL42.GL_MAX_VERTEX_ATOMIC_COUNTERS, GL42.GL_MAX_TESS_CONTROL_ATOMIC_COUNTERS, GL42.GL_MAX_TESS_EVALUATION_ATOMIC_COUNTERS, GL42.GL_MAX_GEOMETRY_ATOMIC_COUNTERS,
                 GL42.GL_MAX_VERTEX_ATOMIC_COUNTER_BUFFERS, GL42.GL_MAX_TESS_CONTROL_ATOMIC_COUNTER_BUFFERS, GL42.GL_MAX_TESS_EVALUATION_ATOMIC_COUNTER_BUFFERS, GL42.GL_MAX_GEOMETRY_ATOMIC_COUNTER_BUFFERS ->
                gpuCaps().vertexPipelineStoresAndAtomics ? 8 : 0;
            case GL42.GL_MAX_TESS_CONTROL_IMAGE_UNIFORMS, GL42.GL_MAX_TESS_EVALUATION_IMAGE_UNIFORMS, GL42.GL_MAX_GEOMETRY_IMAGE_UNIFORMS ->
                gpuCaps().vertexPipelineStoresAndAtomics ? getInteger(GL42.GL_MAX_VERTEX_IMAGE_UNIFORMS) : 0;
            case GL43.GL_MAX_TESS_CONTROL_SHADER_STORAGE_BLOCKS, GL43.GL_MAX_TESS_EVALUATION_SHADER_STORAGE_BLOCKS, GL43.GL_MAX_GEOMETRY_SHADER_STORAGE_BLOCKS ->
                gpuCaps().vertexPipelineStoresAndAtomics ? getInteger(GL43.GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS) : 0;
            case GL42.GL_MAX_IMAGE_SAMPLES -> 0;
            case GL43.GL_MAX_COMBINED_SHADER_OUTPUT_RESOURCES -> getInteger(GL20.GL_MAX_DRAW_BUFFERS) + getInteger(GL43.GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS) + getInteger(GL42.GL_MAX_COMBINED_IMAGE_UNIFORMS);
            case GL31.GL_MAX_COMBINED_VERTEX_UNIFORM_COMPONENTS -> combinedUniformComponents(GL31.GL_MAX_VERTEX_UNIFORM_BLOCKS, GL20.GL_MAX_VERTEX_UNIFORM_COMPONENTS);
            case GL31.GL_MAX_COMBINED_FRAGMENT_UNIFORM_COMPONENTS -> combinedUniformComponents(GL31.GL_MAX_FRAGMENT_UNIFORM_BLOCKS, GL20.GL_MAX_FRAGMENT_UNIFORM_COMPONENTS);
            case GL31.GL_MAX_COMBINED_GEOMETRY_UNIFORM_COMPONENTS -> combinedUniformComponents(GL31.GL_MAX_GEOMETRY_UNIFORM_BLOCKS, GL32.GL_MAX_GEOMETRY_UNIFORM_COMPONENTS);
            case GL40.GL_MAX_COMBINED_TESS_CONTROL_UNIFORM_COMPONENTS -> combinedUniformComponents(GL40.GL_MAX_TESS_CONTROL_UNIFORM_BLOCKS, GL40.GL_MAX_TESS_CONTROL_UNIFORM_COMPONENTS);
            case GL40.GL_MAX_COMBINED_TESS_EVALUATION_UNIFORM_COMPONENTS -> combinedUniformComponents(GL40.GL_MAX_TESS_EVALUATION_UNIFORM_BLOCKS, GL40.GL_MAX_TESS_EVALUATION_UNIFORM_COMPONENTS);
            case GL43.GL_MAX_COMBINED_COMPUTE_UNIFORM_COMPONENTS -> combinedUniformComponents(GL43.GL_MAX_COMPUTE_UNIFORM_BLOCKS, GL43.GL_MAX_COMPUTE_UNIFORM_COMPONENTS);
            case GL40.GL_MIN_PROGRAM_TEXTURE_GATHER_OFFSET -> -8;
            case GL40.GL_MAX_PROGRAM_TEXTURE_GATHER_OFFSET -> 7;
            case GL32.GL_MAX_SERVER_WAIT_TIMEOUT -> Integer.MAX_VALUE;
            case GL40.GL_PATCH_DEFAULT_INNER_LEVEL, GL40.GL_PATCH_DEFAULT_OUTER_LEVEL -> 1;
            case 0x8221 -> GL11.GL_FALSE; // GL_PRIMITIVE_RESTART_FOR_PATCHES_SUPPORTED
            case GL43.GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT -> 16;
            // A stage the device lacks still reports the least GL allows: its programs fail at link either way, and 0 breaks a caller sizing arrays from the limit.
            case GL40.GL_MAX_GEOMETRY_SHADER_INVOCATIONS -> 32;
            // The device's own stages hold what it says they do; emulated ones what GL asks at least.
            case GL32.GL_MAX_GEOMETRY_INPUT_COMPONENTS -> nativeStages() ? gpuCaps().maxGeometryInputComponents : 64;
            case GL32.GL_MAX_GEOMETRY_OUTPUT_COMPONENTS -> nativeStages() ? gpuCaps().maxGeometryOutputComponents : 128;
            case GL40.GL_MAX_TESS_GEN_LEVEL -> nativeStages() ? gpuCaps().maxTessGenLevel : 64;
            case GL40.GL_MAX_TESS_CONTROL_INPUT_COMPONENTS -> nativeStages() ? gpuCaps().maxTessControlInputComponents : 128;
            case GL40.GL_MAX_TESS_CONTROL_OUTPUT_COMPONENTS -> nativeStages() ? gpuCaps().maxTessControlOutputComponents : 128;
            case GL40.GL_MAX_TESS_EVALUATION_INPUT_COMPONENTS -> nativeStages() ? gpuCaps().maxTessEvaluationInputComponents : 128;
            case GL40.GL_MAX_TESS_EVALUATION_OUTPUT_COMPONENTS -> nativeStages() ? gpuCaps().maxTessEvaluationOutputComponents : 128;
            case GL40.GL_MAX_TESS_CONTROL_UNIFORM_COMPONENTS, GL40.GL_MAX_TESS_EVALUATION_UNIFORM_COMPONENTS -> 1024;
            case GL40.GL_MAX_TESS_CONTROL_TEXTURE_IMAGE_UNITS, GL40.GL_MAX_TESS_EVALUATION_TEXTURE_IMAGE_UNITS -> 16;
            case GL40.GL_MAX_TESS_PATCH_COMPONENTS -> nativeStages() ? gpuCaps().maxTessPatchComponents : 120;
            case GL40.GL_MAX_TESS_CONTROL_TOTAL_OUTPUT_COMPONENTS -> nativeStages() ? gpuCaps().maxTessControlTotalOutputComponents : 4096;
            case GL40.GL_MAX_TESS_CONTROL_UNIFORM_BLOCKS, GL40.GL_MAX_TESS_EVALUATION_UNIFORM_BLOCKS -> gpuCaps().tessellationShader ? 12 : 0;
            default -> NOT_A_QUERY;
        };
    }

    /** Like GL, keeps the first error until glGetError reads it. */
    public final void recordError(int error) {
        if (GlCallLog.ENABLED) GlCallLog.refusal(error);
        final GlState st = st();
        if (st.error == GL11.GL_NO_ERROR) st.error = error;
    }

    public int getError() {
        final GlState st = st();
        final int error = st.error;
        st.error = GL11.GL_NO_ERROR;
        return error;
    }

    protected int drawBufferEnum(GlState st, int i) {
        final Fbo f = framebuffers.get(st.drawFbo);
        if (f == null || f.id == 0) return i == 0 && !st.defaultDrawNone ? GL11.GL_BACK : GL11.GL_NONE;
        if (i >= f.drawBuffers.length || f.drawBuffers[i] < 0) return GL11.GL_NONE;
        return GL30.GL_COLOR_ATTACHMENT0 + f.drawBuffers[i];
    }

    protected int readBufferEnum(GlState st) {
        final Fbo f = framebuffers.get(st.readFbo);
        if (f == null || f.id == 0) return st.defaultReadNone ? GL11.GL_NONE : GL11.GL_BACK;
        return f.readBuffer < 0 ? GL11.GL_NONE : GL30.GL_COLOR_ATTACHMENT0 + f.readBuffer;
    }

    public void getInteger(int pname, IntBuffer params) {
        if (params == null || params.remaining() == 0) return;
        final GlState st = st();
        final int p = params.position();
        switch (pname) {
            case GL11.GL_VIEWPORT -> {
                if (params.remaining() >= 4) params.put(p, st.vpX).put(p + 1, st.vpY).put(p + 2, st.vpW).put(p + 3, st.vpH);
            }
            case GL11.GL_SCISSOR_BOX -> {
                if (params.remaining() >= 4) params.put(p, st.scX).put(p + 1, st.scY).put(p + 2, st.scW).put(p + 3, st.scH);
            }
            case GL40.GL_PATCH_DEFAULT_OUTER_LEVEL, GL40.GL_PATCH_DEFAULT_INNER_LEVEL -> {
                final float[] levels = pname == GL40.GL_PATCH_DEFAULT_OUTER_LEVEL ? st.patchDefaultOuter : st.patchDefaultInner;
                for (int i = 0; i < Math.min(params.remaining(), levels.length); i++) params.put(p + i, Math.round(levels[i]));
            }
            case GL13.GL_COMPRESSED_TEXTURE_FORMATS -> {
                for (int format : textures().compressedFormats()) {
                    if (params.position() < params.limit()) params.put(format);
                }
                params.position(p);
            }
            case GL11.GL_MAX_VIEWPORT_DIMS -> {
                if (params.remaining() >= 2) params.put(p, gpuCaps().maxImageDimension2D).put(p + 1, gpuCaps().maxImageDimension2D);
            }
            case GL41.GL_VIEWPORT_BOUNDS_RANGE -> {
                if (params.remaining() >= 2) params.put(p, -32768).put(p + 1, 32767);
            }
            case GL11.GL_COLOR_WRITEMASK -> {
                if (params.remaining() >= 4) for (int i = 0; i < 4; i++) params.put(p + i, st.colorMask[0][i] ? 1 : 0);
            }
            case GL11.GL_POLYGON_MODE -> {
                params.put(p, st.polygonModeFront);
                if (params.remaining() >= 2) params.put(p + 1, st.polygonModeBack);
            }
            case GL12.GL_ALIASED_POINT_SIZE_RANGE, GL11.GL_POINT_SIZE_RANGE -> {
                if (params.remaining() >= 2) params.put(p, 1).put(p + 1, Math.round(gpuCaps().pointSizeMax));
            }
            default -> {
                final int n = GlLegacy.query(pname, legacyScratch);
                if (n > 1) {
                    for (int i = 0; i < Math.min(n, params.remaining()); i++) params.put(p + i, legacyInteger(pname, legacyScratch[i]));
                } else {
                    params.put(p, getInteger(pname));
                }
            }
        }
    }

    private final double[] legacyScratch = new double[16];

    /** A colour asked for as an integer spans the whole range, as GL maps it; anything else rounds. */
    private static int legacyInteger(int pname, double value) {
        if (GlLegacy.colorQuery(pname)) return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value * 2147483647.0));
        return (int) Math.round(value);
    }

    public float getFloat(int pname) {
        final GlState st = st();
        return switch (pname) {
            case GL11.GL_LINE_WIDTH -> st.lineWidth;
            case GL11.GL_POINT_SIZE -> st.pointSize;
            case GL11.GL_DEPTH_CLEAR_VALUE -> (float) st.clearDepth;
            case GL11.GL_POLYGON_OFFSET_FACTOR -> st.polyFactor;
            case GL11.GL_POLYGON_OFFSET_UNITS -> st.polyUnits;
            case EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT -> gpuCaps().maxSamplerAnisotropy;
            case GL14.GL_MAX_TEXTURE_LOD_BIAS -> 15f;
            case GL13.GL_SAMPLE_COVERAGE_VALUE -> st.sampleCoverageValue;
            case GL40.GL_MIN_SAMPLE_SHADING_VALUE -> st.minSampleShading;
            case GL40.GL_MIN_FRAGMENT_INTERPOLATION_OFFSET -> -0.5f;
            case GL40.GL_MAX_FRAGMENT_INTERPOLATION_OFFSET -> 0.4375f;
            default -> GlLegacy.query(pname, legacyScratch) != 0 ? (float) legacyScratch[0] : getInteger(pname);
        };
    }

    public void getFloat(int pname, FloatBuffer params) {
        if (params == null || params.remaining() == 0) return;
        final GlState st = st();
        final int p = params.position();
        switch (pname) {
            case GL11.GL_COLOR_CLEAR_VALUE -> {
                if (params.remaining() >= 4) for (int i = 0; i < 4; i++) params.put(p + i, st.clearColor[i]);
            }
            case GL14.GL_BLEND_COLOR -> {
                if (params.remaining() >= 4) for (int i = 0; i < 4; i++) params.put(p + i, st.blendColor[i]);
            }
            case GL40.GL_PATCH_DEFAULT_OUTER_LEVEL, GL40.GL_PATCH_DEFAULT_INNER_LEVEL -> {
                final float[] levels = pname == GL40.GL_PATCH_DEFAULT_OUTER_LEVEL ? st.patchDefaultOuter : st.patchDefaultInner;
                for (int i = 0; i < Math.min(params.remaining(), levels.length); i++) params.put(p + i, levels[i]);
            }
            case GL11.GL_DEPTH_RANGE -> {
                if (params.remaining() >= 2) params.put(p, (float) st.depthNear).put(p + 1, (float) st.depthFar);
            }
            case GL11.GL_MAX_VIEWPORT_DIMS -> {
                if (params.remaining() >= 2) params.put(p, gpuCaps().maxImageDimension2D).put(p + 1, gpuCaps().maxImageDimension2D);
            }
            case GL41.GL_VIEWPORT_BOUNDS_RANGE -> {
                if (params.remaining() >= 2) params.put(p, -32768).put(p + 1, 32767);
            }
            case GL12.GL_ALIASED_LINE_WIDTH_RANGE, GL11.GL_LINE_WIDTH_RANGE -> {
                if (params.remaining() >= 2) params.put(p, gpuCaps().lineWidthMin).put(p + 1, gpuCaps().lineWidthMax);
            }
            case GL12.GL_ALIASED_POINT_SIZE_RANGE, GL11.GL_POINT_SIZE_RANGE -> {
                if (params.remaining() >= 2) params.put(p, 1f).put(p + 1, gpuCaps().pointSizeMax);
            }
            case GL11.GL_VIEWPORT -> {
                if (params.remaining() >= 4) params.put(p, st.vpX).put(p + 1, st.vpY).put(p + 2, st.vpW).put(p + 3, st.vpH);
            }
            default -> {
                final int n = GlLegacy.query(pname, legacyScratch);
                if (n > 1) {
                    for (int i = 0; i < Math.min(n, params.remaining()); i++) params.put(p + i, (float) legacyScratch[i]);
                } else {
                    params.put(p, getFloat(pname));
                }
            }
        }
    }

    public boolean getBoolean(int pname) {
        final GlState st = st();
        return switch (pname) {
            case GL11.GL_DEPTH_WRITEMASK -> st.depthMask;
            case GL13.GL_SAMPLE_COVERAGE_INVERT -> st.sampleCoverageInvert;
            case GL40.GL_TRANSFORM_FEEDBACK_BUFFER_PAUSED, GL40.GL_TRANSFORM_FEEDBACK_BUFFER_ACTIVE -> GlTransformFeedback.currentState(pname) != 0;
            case GL11.GL_UNPACK_SWAP_BYTES -> st.unpack.swapBytes;
            case GL11.GL_PACK_SWAP_BYTES -> st.pack.swapBytes;
            // A value that is not a boolean reads as whether it is zero, as bindings do.
            case ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB, GL15.GL_ARRAY_BUFFER_BINDING, GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING, GL31.GL_UNIFORM_BUFFER_BINDING, GL43.GL_SHADER_STORAGE_BUFFER_BINDING, GL40.GL_DRAW_INDIRECT_BUFFER_BINDING,
                 GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING, GL30.GL_VERTEX_ARRAY_BINDING, GL20.GL_CURRENT_PROGRAM, GL41.GL_PROGRAM_PIPELINE_BINDING, GL11.GL_TEXTURE_BINDING_2D -> getInteger(pname) != 0;
            default -> isEnabled(pname) || getInteger(pname) != 0;
        };
    }

    public void getBoolean(int pname, ByteBuffer params) {
        if (params == null || params.remaining() == 0) return;
        final GlState st = st();
        if (pname == GL11.GL_COLOR_WRITEMASK && params.remaining() >= 4) {
            for (int i = 0; i < 4; i++) params.put(params.position() + i, (byte) (st.colorMask[0][i] ? 1 : 0));
            return;
        }
        if (pname == GL40.GL_PATCH_DEFAULT_OUTER_LEVEL || pname == GL40.GL_PATCH_DEFAULT_INNER_LEVEL) {
            final float[] levels = pname == GL40.GL_PATCH_DEFAULT_OUTER_LEVEL ? st.patchDefaultOuter : st.patchDefaultInner;
            for (int i = 0; i < Math.min(params.remaining(), levels.length); i++) params.put(params.position() + i, (byte) (levels[i] != 0f ? 1 : 0));
            return;
        }
        params.put(params.position(), (byte) (getBoolean(pname) ? 1 : 0));
    }

    /** Fences alive, by the serial each stands for: two made in one submission share it. */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap fences = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    public long fenceSync(int condition, int flags) {
        final long serial = currentSerial();
        fences.addTo(serial, 1);
        return serial;
    }

    public boolean isSync(long sync) {
        return sync != 0L && fences.get(sync) > 0;
    }

    public void waitSync(long sync, int flags, long timeout) {
    }

    public void deleteSync(long sync) {
        if (fences.addTo(sync, -1) <= 1) fences.remove(sync);
    }

    public int getSyncStatus(long sync) {
        pollStream();
        return isIdle(sync) ? GL32.GL_SIGNALED : GL32.GL_UNSIGNALED;
    }

    public int getSynci(long sync, int pname, IntBuffer length) {
        final int v = switch (pname) {
            case GL32.GL_OBJECT_TYPE -> GL32.GL_SYNC_FENCE;
            case GL32.GL_SYNC_CONDITION -> GL32.GL_SYNC_GPU_COMMANDS_COMPLETE;
            case GL32.GL_SYNC_FLAGS -> 0;
            default -> getSyncStatus(sync);
        };
        if (length != null && length.remaining() > 0) length.put(length.position(), 1);
        return v;
    }

    public void pushDebugGroup(int source, int id, CharSequence message) {
        debugPush(message.toString());
        st().debug.pushGroup(source, id, message.toString());
    }

    public void popDebugGroup() {
        debugPop();
        st().debug.popGroup();
    }

    public void debugMessageInsert(int source, int type, int id, int severity, CharSequence message) {
        st().debug.post(source, type, id, severity, message.toString());
    }

    public void debugMessageControl(int source, int type, int severity, IntBuffer ids, boolean enabled) {
        st().debug.control(source, type, severity, ids, enabled);
    }

    public void debugMessageCallback(GLDebugMessageListener listener, long userParam) {
        st().debug.setCallback(listener, userParam);
    }

    public int getDebugMessageLog(int count, IntBuffer sources, IntBuffer types, IntBuffer ids, IntBuffer severities, IntBuffer lengths, ByteBuffer messageLog) {
        return st().debug.drain(count, sources, types, ids, severities, lengths, messageLog);
    }

    /** Angelica's own logging of the stream: everything at medium severity or above, with no debug context needed. */
    public int setupDebugOutput(DebugMessageHandler handler) {
        final DebugOutput output = st().debug;
        output.control(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_LOW, null, false);
        output.control(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, null, false);
        output.setCallback((source, type, id, severity, length, message, userParam) ->
            handler.handleMessage(source, type, id, severity, MemoryUtil.memUTF8(message, length)), 0L);
        output.setEnabled(true);
        return 2;
    }

    public int disableDebugOutput() {
        final DebugOutput output = st().debug;
        output.setCallback(null, 0L);
        output.setEnabled(false);
        return 1;
    }

    public int genQueries() {
        final int id = nextQuery++;
        queryResults.put(id, 0L);
        return id;
    }

    public void genQueries(IntBuffer ids) {
        for (int i = ids.position(); i < ids.limit(); i++) ids.put(i, genQueries());
    }

    public void deleteQueries(int id) {
        if (occlusion != null) occlusion.forget(id);
        if (geometryCount != null) geometryCount.forget(id);
        queryResults.remove(id);
        queryStart.remove(id);
        queryTargets.remove(id);
    }

    public boolean isQuery(int query) {
        return queryStart.containsKey(query) || queryTargets.containsKey(query);
    }

    protected OcclusionQueries occlusion;

    private static boolean occlusionTarget(int target) {
        return target == GL15.GL_SAMPLES_PASSED || target == GL33.GL_ANY_SAMPLES_PASSED || target == GL43.GL_ANY_SAMPLES_PASSED_CONSERVATIVE;
    }

    protected final void occlusionPassBegan() {
        if (occlusion != null) occlusion.passBegan();
        if (geometryCount != null) geometryCount.passBegan();
    }

    /** Counts what geometry shaders the device runs emit, for GL_GEOMETRY_SHADER_PRIMITIVES_EMITTED; null, or unable to count, where it cannot. */
    protected OcclusionQueries geometryCount;

    protected final void occlusionPassEnding() {
        if (occlusion != null) occlusion.passEnding();
        if (geometryCount != null) geometryCount.passEnding();
    }

    private final PipelineStatistics statistics = new PipelineStatistics();

    protected final void noteStatistics(int mode, int count, int instances) {
        if (!GlTransformFeedback.inParts()) statistics.draw(st(), programs().current(st()), mode, count, instances, geometryCount != null && geometryCount.busy());
    }

    /** An index draw's vertices and primitives stop and start where primitive restart says, so the indices have to be read while they are counted. */
    protected final void noteElementStatistics(GlState st, int mode, int count, int type, long indices, int instances) {
        if (!st.primitiveRestart || !countingStatistics() || count <= 0 || GlTransformFeedback.inParts()) {
            noteStatistics(mode, count, instances);
            return;
        }
        final int readable = indicesInBuffer(st, count, type, indices);
        final int[] read = readIndices(st, readable, type, indices);
        final long restart = !st.primitiveRestartFixed ? Integer.toUnsignedLong(st.primitiveRestartIndex)
            : type == GL11.GL_UNSIGNED_BYTE ? 0xFFL : type == GL11.GL_UNSIGNED_SHORT ? 0xFFFFL : 0xFFFFFFFFL;
        int piece = 0;
        for (int i = 0; i < readable; i++) {
            if (Integer.toUnsignedLong(read[i]) == restart) {
                noteStatistics(mode, piece, instances);
                piece = 0;
            } else {
                piece++;
            }
        }
        noteStatistics(mode, piece, instances);
    }

    final void noteGeometryEmitted(long primitives) {
        statistics.emitted(primitives);
    }

    /** Commands in a buffer have to be read to be counted. */
    final boolean countingStatistics() {
        for (int target : activeQueries.keySet()) {
            if (PipelineStatistics.target(target)) return true;
        }
        return false;
    }

    public final void textureBarrier() {
        endPass();
        rendererBase().barrier();
    }

    public static final int TRANSFORM_FEEDBACK_OVERFLOW = 0x82EC, TRANSFORM_FEEDBACK_STREAM_OVERFLOW = 0x82ED;

    /** How the query of a stream other than the first is known among the active ones. */
    public static int queryKey(int target, int index) {
        return index == 0 ? target : target | index << 20;
    }

    public final void beginQueryIndexed(int target, int index, int id) {
        activeQueries.put(queryKey(target, index), id);
        queryTargets.put(id, target);
        queryResults.put(id, 0L);
        queryStart.put(id, streamCounter(target, index));
    }

    public final void endQueryIndexed(int target, int index) {
        final int key = queryKey(target, index);
        if (!activeQueries.containsKey(key)) return;
        final int id = activeQueries.remove(key);
        final long counted = streamCounter(target, index) - queryStart.get(id);
        queryResults.put(id, target == TRANSFORM_FEEDBACK_STREAM_OVERFLOW ? Math.min(1L, counted) : counted);
    }

    private static long streamCounter(int target, int stream) {
        return switch (target) {
            case GL30.GL_PRIMITIVES_GENERATED -> GlTransformFeedback.generatedPrimitives(stream);
            case GL30.GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN -> GlTransformFeedback.capturedPrimitives(stream);
            case TRANSFORM_FEEDBACK_STREAM_OVERFLOW -> GlTransformFeedback.overflows(stream);
            default -> 0L;
        };
    }

    public final int currentQuery(int target, int index) {
        return activeQueries.getOrDefault(queryKey(target, index), 0);
    }

    public void beginQuery(int target, int id) {
        activeQueries.put(target, id);
        queryTargets.put(id, target);
        if (PipelineStatistics.target(target)) {
            queryStart.put(id, statistics.value(target));
            queryResults.put(id, 0L);
            if (target == GL46C.GL_GEOMETRY_SHADER_PRIMITIVES_EMITTED && geometryCount != null && geometryCount.counts() && !geometryCount.busy()) geometryCount.begin(id);
            return;
        }
        if (occlusionTarget(target) && occlusion != null && occlusion.counts() && !occlusion.busy()) {
            queryResults.put(id, 0L);
            queryStart.put(id, 0L);
            occlusion.begin(id);
            return;
        }
        final boolean primitives = target == GL30.GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN || target == GL30.GL_PRIMITIVES_GENERATED;
        queryStart.put(id, primitives ? streamCounter(target, 0) : System.nanoTime());
        if (target == TRANSFORM_FEEDBACK_OVERFLOW) queryStart.put(id, GlTransformFeedback.overflowsInAnyStream());
        if (target == TRANSFORM_FEEDBACK_STREAM_OVERFLOW) queryStart.put(id, GlTransformFeedback.overflows(0));
        queryResults.put(id, 0L);
    }

    public void endQuery(int target) {
        if (!activeQueries.containsKey(target)) return;
        final int id = activeQueries.remove(target);
        if (occlusion != null && occlusion.pending(id)) {
            occlusion.end();
            return;
        }
        // What the device counted is added once it has finished; the rest, from emulated stages, is known now.
        if (geometryCount != null && geometryCount.pending(id)) geometryCount.end();
        final long result = PipelineStatistics.target(target) ? statistics.value(target) - queryStart.get(id) : switch (target) {
            case GL33.GL_TIME_ELAPSED -> System.nanoTime() - queryStart.get(id);
            case GL15.GL_SAMPLES_PASSED -> {
                warnOnce("occlusion queries are not counted on this backend; everything reports as visible");
                yield Integer.MAX_VALUE;
            }
            case GL33.GL_ANY_SAMPLES_PASSED, GL43.GL_ANY_SAMPLES_PASSED_CONSERVATIVE -> {
                warnOnce("occlusion queries are not counted on this backend; everything reports as visible");
                yield 1L;
            }
            case GL30.GL_PRIMITIVES_GENERATED, GL30.GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN -> streamCounter(target, 0) - queryStart.get(id);
            case TRANSFORM_FEEDBACK_OVERFLOW -> GlTransformFeedback.overflowsInAnyStream() != queryStart.get(id) ? 1L : 0L;
            case TRANSFORM_FEEDBACK_STREAM_OVERFLOW -> GlTransformFeedback.overflows(0) != queryStart.get(id) ? 1L : 0L;
            default -> 1L;
        };
        queryResults.put(id, result);
    }

    public void queryCounter(int id, int target) {
        queryResults.put(id, System.nanoTime());
    }

    protected long queryValue(int id, int pname) {
        if (pname == GL45.GL_QUERY_TARGET) return queryTarget(id);
        if (occlusion != null && occlusion.pending(id)) {
            final long serial = occlusion.lastUse(id);
            if (pname == GL15.GL_QUERY_RESULT) {
                flushAndWait(serial);
            } else if (!finishedWithoutWaiting(serial)) {
                return pname == GL15.GL_QUERY_RESULT_AVAILABLE ? 0L : queryResults.get(id);
            }
            final long counted = occlusion.collect(id);
            queryResults.put(id, queryTargets.get(id) == GL15.GL_SAMPLES_PASSED ? counted : Math.min(counted, 1L));
        }
        if (geometryCount != null && geometryCount.pending(id) && !activeQueries.containsValue(id)) {
            final long serial = geometryCount.lastUse(id);
            if (pname == GL15.GL_QUERY_RESULT) {
                flushAndWait(serial);
            } else if (!finishedWithoutWaiting(serial)) {
                return pname == GL15.GL_QUERY_RESULT_AVAILABLE ? 0L : queryResults.get(id);
            }
            queryResults.put(id, queryResults.get(id) + geometryCount.collect(id));
        }
        if (pname == GL15.GL_QUERY_RESULT_AVAILABLE) return 1L;
        return queryResults.get(id);
    }

    /** Sends what is recorded on its way, as a query asked about must eventually finish without any other call. */
    private boolean finishedWithoutWaiting(long serial) {
        pollStream();
        if (isIdle(serial)) return true;
        if (serial >= currentSerial()) {
            endPass();
            submitNow();
        }
        pollStream();
        return isIdle(serial);
    }

    public final int currentQuery(int target) {
        return activeQueries.getOrDefault(target, 0);
    }

    private int combinedUniformComponents(int blocks, int components) {
        return (int) Math.min(Integer.MAX_VALUE, (long) getInteger(blocks) * getInteger(GL31.GL_MAX_UNIFORM_BLOCK_SIZE) / 4L + getInteger(components));
    }

    public final void beginConditionalRender(int id, int mode) {
        final GlState st = st();
        st.conditionalRender = true;
        final boolean inverted = mode >= 0x8E17 && mode <= 0x8E1A;
        final boolean noWait = mode == GL30.GL_QUERY_NO_WAIT || mode == GL30.GL_QUERY_BY_REGION_NO_WAIT || mode == 0x8E18 || mode == 0x8E1A;
        if (noWait && queryValue(id, GL15.GL_QUERY_RESULT_AVAILABLE) == 0L) return;
        st.conditionalSkip = (queryValue(id, GL15.GL_QUERY_RESULT) != 0L) == inverted;
    }

    public final void endConditionalRender() {
        final GlState st = st();
        st.conditionalRender = false;
        st.conditionalSkip = false;
    }

    public void getQueryObjectui(int id, int pname, IntBuffer params) {
        if (params != null && params.remaining() > 0) params.put(params.position(), (int) Math.min(Integer.MAX_VALUE, queryValue(id, pname)));
    }

    public int getQueryObjecti(int id, int pname) {
        return (int) Math.min(Integer.MAX_VALUE, queryValue(id, pname));
    }

    public long getQueryObjectui64(int id, int pname) {
        return queryValue(id, pname);
    }

    public void renderbufferStorage(int target, int internalformat, int width, int height) {
        final T t = textures().renderbuffer(st().renderbuffer);
        if (t == null) return;
        textures().setSamples(t, 1);
        storeRenderbuffer(t, internalformat, width, height);
    }

    private void storeRenderbuffer(T t, int internalformat, int width, int height) {
        final Formats.Format f = textures().format(internalformat);
        if (t.hasImage() && t.format.equals(f) && t.width == width && t.height == height) return;
        if (width < 0 || height < 0) {
            recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        if (!textures().withinLimits(t, Math.max(1, width), Math.max(1, height), 1, 1)) return;
        releaseImage(t);
        t.format = f;
        t.width = Math.max(1, width);
        t.height = Math.max(1, height);
        t.depth = 1;
        t.layers = 1;
        t.levels = 1;
        if (!textures().allocate(t)) return;
        targetsChanged();
    }

    private byte[] retainedBlocks(T t, int target, int level) {
        final int index = retainedIndex(target, level);
        return t.compressedLevels == null || t.compressedLevels.length <= index ? null : t.compressedLevels[index];
    }

    /** Whatever modes its blocks use, the device can sample them into an image that reads back. */
    private boolean readCompressedByDrawing(T t, int level, int w, int h, int format, int type, long dst) {
        if (!com.gtnewhorizons.angelica.glemu.pipeline.ImagePass.reads(t)) return false;
        final GlState st = st();
        final Formats.Format texels = Formats.of(GL30.GL_RGBA32F, 0, 0);
        final T drawn = textures().internalTexture(GL11.GL_TEXTURE_2D, texels, w, h, 1, 1, "readCompressed");
        try {
            endPass();
            imagePass().copy(st, t, level, drawn, 0, 0, false, 0, 0, w, h, 0, 0, w, h);
            endPass();
            textures().readImage(drawn, Formats.ASPECT_COLOR, 0, 0, 0, 0, w, h, format, type, st.pack, dst);
        } finally {
            releaseImage(drawn);
        }
        return true;
    }

    /** Expands the blocks kept for a compressed image. A level uploaded through a pack buffer has none kept, and BC7 is only read in the one mode written here. */
    private void readBlocksAsPixels(T t, int target, int level, int format, int type, long dst) {
        final GlState st = st();
        final byte[] blocks = retainedBlocks(t, target, level);
        final int w = t.levelWidth(level), h = t.levelHeight(level);
        // The device decodes as sampling does, and in more precision than a host expansion through bytes keeps. Sampling would also undo sRGB and apply swizzles.
        final boolean drawable = com.gtnewhorizons.angelica.glemu.pipeline.ImagePass.reads(t) && !t.format.srgb() && t.baseLevel == 0
            && t.swizzle[0] == GL11.GL_RED && t.swizzle[1] == GL11.GL_GREEN && t.swizzle[2] == GL11.GL_BLUE && t.swizzle[3] == GL11.GL_ALPHA;
        if (blocks == null || drawable) {
            if (!readCompressedByDrawing(t, level, w, h, format, type, dst)) refuseCompressedRead(t);
            return;
        }
        final int slices = retainedSlices(t, level);
        final long sliceBlocks = Formats.imageBytes(t.format, w, h), sliceBytes = PixelConvert.rowStride(w, format, type, st.pack) * (st.pack.imageHeight > 0 ? st.pack.imageHeight : h);
        final Formats.Format texels = BlockCodec.isSigned(t.format) ? Formats.of(GL31.GL_RGBA8_SNORM, 0, 0) : Formats.RGBA8;
        final ByteBuffer all = MemoryUtil.memAlloc(blocks.length);
        final long rgba = MemoryUtil.nmemAlloc((long) w * h * 4);
        try {
            all.put(blocks).flip();
            for (int slice = 0; slice < slices; slice++) {
                if (!BlockCodec.decode(t.format, memAddress(all) + slice * sliceBlocks, w, h, rgba)) {
                    if (slices != 1 || !readCompressedByDrawing(t, level, w, h, format, type, dst)) refuseCompressedRead(t);
                    return;
                }
                PixelConvert.pack(texels, rgba, w, h, format, type, st.pack, dst + (st.pack.skipImages + slice) * sliceBytes);
            }
        } finally {
            MemoryUtil.memFree(all);
            MemoryUtil.nmemFree(rgba);
        }
    }

    protected void getTexImageImpl(int target, int level, int format, int type, long dst) {
        final GlState st = st();
        final T t = textures().get(st.boundTexture(target));
        if (level < 0) {
            recordError(GL11.GL_INVALID_VALUE);
            return;
        }
        if (t == null || !t.hasImage() || level >= t.levels) return;
        if (format == 0 && t.format.isCompressed()) {
            final byte[] blocks = retainedBlocks(t, target, level);
            if (blocks != null) MemoryUtil.memByteBuffer(dst, blocks.length).put(blocks);
            return;
        }
        if (t.format.isCompressed()) {
            readBlocksAsPixels(t, target, level, format, type, dst);
            return;
        }
        final int aspect = format == GL11.GL_STENCIL_INDEX ? Formats.ASPECT_STENCIL : t.format.isDepth() ? Formats.ASPECT_DEPTH : Formats.ASPECT_COLOR;
        final int w = t.levelWidth(level), h = t.levelHeight(level);
        if (t.target == GL13.GL_TEXTURE_CUBE_MAP) {
            textures().readImage(t, aspect, level, TexturesBase.cubeFace(target), 0, 0, w, h, format, type, st.pack, dst);
            return;
        }
        // 3D textures and arrays come back slice by slice, tightly stacked per the pack state's image height.
        final int slices = t.is3D() ? t.levelDepth(level) : t.layers;
        final long sliceBytes = PixelConvert.rowStride(w, format, type, st.pack) * (st.pack.imageHeight > 0 ? st.pack.imageHeight : h);
        for (int slice = 0; slice < slices; slice++) textures().readImage(t, aspect, level, slice, 0, 0, w, h, format, type, st.pack, dst + (st.pack.skipImages + slice) * sliceBytes);
    }

    public void clearTexImage(int texture, int level, int format, int type) {
        final T t = textures().get(texture);
        if (t == null || !t.hasImage()) return;
        if (t.levels > 1) {
            clearTexImageWith(texture, level, format, type, 0L);
            return;
        }
        endPass();
        clearImage(t);
        t.pendingClear = false;
        t.lastUse = currentSerial();
    }

    public void renderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) {
        final T t = textures().renderbuffer(st().renderbuffer);
        if (t == null) return;
        textures().setSamples(t, deviceSamples(samples));
        storeRenderbuffer(t, internalformat, width, height);
    }

    /**
     * A multisampled target keeps its type for the shaders sampling it, whose native images must be multisampled to match, which one sample
     * is not. GL's sample counts are least values, so the next count up is correct.
     */
    private int multisampledTargetSamples(int requested) {
        return Math.max(2, deviceSamples(requested));
    }

    private int deviceSamples(int requested) {
        if (requested <= 1) return 1;
        return Math.min(Integer.highestOneBit(requested - 1) << 1, Math.max(1, gpuCaps().maxSamples));
    }

    /** Samples per pixel of a framebuffer, as GL_SAMPLES counts them: 0 for one that is not multisampled. */
    public final int framebufferSamples(Fbo f) {
        if (f == null || f.id == 0) return 0;
        for (int i = 0; i < Fbo.MAX_COLOR; i++) {
            if (f.colorTex[i] == 0) continue;
            final T t = f.colorRbo[i] ? textures().renderbuffer(f.colorTex[i]) : textures().get(f.colorTex[i]);
            if (t != null) return t.samples > 1 ? t.samples : 0;
        }
        final int ds = f.depthTex != 0 ? f.depthTex : f.stencilTex;
        if (ds == 0) return 0;
        final T t = (f.depthTex != 0 ? f.depthRbo : f.stencilRbo) ? textures().renderbuffer(ds) : textures().get(ds);
        return t != null && t.samples > 1 ? t.samples : 0;
    }

    /** A multisampled texture's one level, which glTexImage*Multisample and glTexStorage*Multisample both give it. */
    public void multisampleImage(int target, int samples, int internalFormat, int width, int height, int layers, boolean immutable) {
        final GlState st = st();
        if (target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE || target == GL32.GL_PROXY_TEXTURE_2D_MULTISAMPLE_ARRAY) return;
        final T t = textures().getOrCreate(st.boundTexture(target), target);
        if (t == null) return;
        if (t.immutable) {
            recordError(GL11.GL_INVALID_OPERATION);
            return;
        }
        textures().setSamples(t, multisampledTargetSamples(samples));
        if (!textures().specify(t, textures().format(internalFormat), 0, width, height, 1, layers)) return;
        t.immutable = immutable;
    }

    /** Where a sample lies in its pixel, from the standard patterns both native APIs use. */
    public final float samplePosition(int samples, int index, boolean y) {
        final float[] standard = switch (samples) {
            case 2 -> new float[] {0.75f, 0.75f, 0.25f, 0.25f};
            case 4 -> new float[] {0.375f, 0.125f, 0.875f, 0.375f, 0.125f, 0.625f, 0.625f, 0.875f};
            case 8 -> new float[] {0.5625f, 0.3125f, 0.4375f, 0.6875f, 0.8125f, 0.5625f, 0.3125f, 0.1875f, 0.1875f, 0.8125f, 0.0625f, 0.4375f, 0.6875f, 0.9375f, 0.9375f, 0.0625f};
            default -> new float[] {0.5f, 0.5f};
        };
        final int at = index * 2 + (y ? 1 : 0);
        return at < standard.length ? standard[at] : 0.5f;
    }

    protected void blit(GlState st, Fbo draw, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        if (st.conditionalSkip) return;
        final TargetBase<T> src = readTarget(st);
        blitTarget().fboId = Integer.MIN_VALUE;
        resolveTarget(blitTarget(), draw, st);
        final TargetBase<T> dst = blitTarget();
        endPass();
        if (st.scissorTest) {
            final int[] x = {sx0, sx1, dx0, dx1}, y = {sy0, sy1, dy0, dy1};
            if (!scissorBlitAxis(x, st.scX, st.scX + st.scW) || !scissorBlitAxis(y, st.scY, st.scY + st.scH)) return;
            sx0 = x[0]; sx1 = x[1]; dx0 = x[2]; dx1 = x[3];
            sy0 = y[0]; sy1 = y[1]; dy0 = y[2]; dy1 = y[3];
        }
        if ((mask & GL11.GL_COLOR_BUFFER_BIT) != 0 && src.color[0] != null) {
            for (int i = 0; i < dst.colorCount; i++) {
                if (dst.color[i] == null) continue;
                blitImage(src.color[0], src.colorLevel[0], blitSrcLayer >= 0 ? blitSrcLayer : src.colorLayer[0], dst.color[i], dst.colorLevel[i], blitDstLayer >= 0 ? blitDstLayer : dst.colorLayer[i], Formats.ASPECT_COLOR,
                    sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, filter == GL11.GL_LINEAR);
            }
        }
        final int dsMask = mask & (GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        if (dsMask != 0 && src.depth != null && dst.depth != null) {
            int aspect = 0;
            if ((dsMask & GL11.GL_DEPTH_BUFFER_BIT) != 0 && src.hasDepth && dst.hasDepth) aspect |= Formats.ASPECT_DEPTH;
            if ((dsMask & GL11.GL_STENCIL_BUFFER_BIT) != 0 && src.hasStencil && dst.hasStencil) aspect |= Formats.ASPECT_STENCIL;
            if (aspect != 0) {
                blitImage(src.depth, src.depthLevel, blitSrcLayer >= 0 ? blitSrcLayer : src.depthLayer, dst.depth, dst.depthLevel, blitDstLayer >= 0 ? blitDstLayer : dst.depthLayer, aspect, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, false);
            }
        }
    }

    /** Narrows a target span to the scissor, taking the same part of the source with it. */
    private static boolean scissorBlitAxis(int[] e, int low, int high) {
        final int d0 = e[2], d1 = e[3], s0 = e[0], s1 = e[1];
        if (d0 == d1) return false;
        final int from = Math.max(Math.min(d0, d1), low), to = Math.min(Math.max(d0, d1), high);
        if (from >= to) return false;
        final double scale = (double) (s1 - s0) / (d1 - d0);
        final int nd0 = d0 < d1 ? from : to, nd1 = d0 < d1 ? to : from;
        e[0] = (int) Math.round(s0 + (nd0 - d0) * scale);
        e[1] = (int) Math.round(s0 + (nd1 - d0) * scale);
        e[2] = nd0;
        e[3] = nd1;
        return true;
    }

    private int blitSrcLayer = -1, blitDstLayer = -1;

    /** A blit between named layers of whatever is attached, rather than the layers the framebuffers selected. */
    public void blitFramebufferLayer(int sx0, int sy0, int sx1, int sy1, int srcLayer, int dx0, int dy0, int dx1, int dy1, int dstLayer, int mask, int filter) {
        blitSrcLayer = srcLayer;
        blitDstLayer = dstLayer;
        try {
            blitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
        } finally {
            blitSrcLayer = blitDstLayer = -1;
        }
    }

    /** Every layer the read and draw attachments have in common. */
    public void blitFramebufferLayers(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        final GlState st = st();
        final TargetBase<T> src = readTarget(st);
        final T image = src.color[0] != null ? src.color[0] : src.depth;
        if (image == null) return;
        final int layers = image.is3D() ? image.depth : image.layers;
        for (int layer = 0; layer < layers; layer++) blitFramebufferLayer(sx0, sy0, sx1, sy1, layer, dx0, dy0, dx1, dy1, layer, mask, filter);
    }

    /** Sets uniforms up for one program from another's, as a pipeline does, around a call that draws or dispatches through it. */
    public void withUniformsFrom(int program, int source, Runnable call) {
        final int savedProgram = pipelineProgram, savedUniforms = pipelineUniformProgram;
        final int[] savedStages = pipelineStages;
        setPipeline(program, 0, new int[] {source});
        try {
            call.run();
        } finally {
            setPipeline(savedProgram, savedUniforms, savedStages);
        }
    }

    public void textureFromRenderbuffer(int texture, int renderbuffer) {
        textures().aliasRenderbuffer(texture, renderbuffer);
    }

    protected void readPixelsImpl(int x, int y, int w, int h, int format, int type, long dst) {
        syncTextureViews();
        final GlState st = st();
        final TargetBase<T> src = readTarget(st);
        final boolean depth = format == GL11.GL_DEPTH_COMPONENT || format == GL30.GL_DEPTH_STENCIL;
        final boolean stencil = format == GL11.GL_STENCIL_INDEX;
        final T t = depth || stencil ? src.depth : src.color[0];
        if (t == null) return;
        final int level = depth || stencil ? src.depthLevel : src.colorLevel[0];
        final int layer = depth || stencil ? src.depthLayer : src.colorLayer[0];
        final int x0 = Math.max(0, x), y0 = Math.max(0, y);
        final int x1 = Math.min(t.levelWidth(level), x + w), y1 = Math.min(t.levelHeight(level), y + h);
        if (x1 <= x0 || y1 <= y0) return;
        final int aspect = stencil ? Formats.ASPECT_STENCIL : depth ? Formats.ASPECT_DEPTH : Formats.ASPECT_COLOR;
        final PixelConvert.Store pack = new PixelConvert.Store();
        pack.copyFrom(st.pack);
        pack.rowLength = st.pack.rowLength > 0 ? st.pack.rowLength : w;
        pack.skipPixels += x0 - x;
        pack.skipRows += y0 - y;
        textures().readImage(t, aspect, level, layer, x0, y0, x1 - x0, y1 - y0, format, type, pack, dst);
        if (aspect == Formats.ASPECT_COLOR) clampRead(t.format, x1 - x0, y1 - y0, format, type, pack, dst);
    }

    private String apiDisplayName() {
        final String version = apiVersion();
        return version.isEmpty() ? apiName() : apiName() + " " + version;
    }

    public String rendererString() {
        return gpuCaps().deviceName + " (Angelica/" + apiDisplayName() + ", " + gpuCaps().driverName + ")";
    }

    public String getString(int pname) {
        return switch (pname) {
            case GL11.GL_VENDOR -> vendorString();
            case GL11.GL_RENDERER -> rendererString();
            case GL11.GL_VERSION -> "4.6.0 Core Profile Context [Angelica/" + apiDisplayName() + "]";
            case GL20.GL_SHADING_LANGUAGE_VERSION -> "4.60";
            case GL11.GL_EXTENSIONS -> String.join(" ", ext());
            default -> "";
        };
    }

    public int getInteger(int pname) {
        final GlState st = st();
        return switch (pname) {
            case GL30.GL_MAJOR_VERSION -> 4;
            case GL30.GL_MINOR_VERSION -> 6;
            case GL30.GL_NUM_EXTENSIONS -> ext().length;
            case GL32.GL_CONTEXT_PROFILE_MASK -> GL32.GL_CONTEXT_CORE_PROFILE_BIT;
            case GL30.GL_CONTEXT_FLAGS -> 0;
            case GL11.GL_MAX_TEXTURE_SIZE -> Math.min(gpuCaps().maxImageDimension1D, gpuCaps().maxImageDimension2D);
            case GL12.GL_MAX_3D_TEXTURE_SIZE -> gpuCaps().maxImageDimension3D;
            case GL13.GL_MAX_CUBE_MAP_TEXTURE_SIZE -> gpuCaps().maxImageDimensionCube;
            case GL30.GL_MAX_ARRAY_TEXTURE_LAYERS -> gpuCaps().maxImageArrayLayers;
            case GL30.GL_MAX_RENDERBUFFER_SIZE -> Math.min(gpuCaps().maxImageDimension2D, Math.min(gpuCaps().maxFramebufferWidth, gpuCaps().maxFramebufferHeight));
            case GL20.GL_MAX_TEXTURE_IMAGE_UNITS, GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, GL32.GL_MAX_GEOMETRY_TEXTURE_IMAGE_UNITS -> Math.min(32, Math.min(gpuCaps().maxPerStageDescriptorSampledImages, gpuCaps().maxPerStageDescriptorSamplers));
            case GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS -> GlState.MAX_UNITS;
            case GL45.GL_CLIP_ORIGIN -> (st().clipState & 1) != 0 ? GL45.GL_UPPER_LEFT : GL45.GL_LOWER_LEFT;
            case GL45.GL_CLIP_DEPTH_MODE -> (st().clipState & 2) != 0 ? GL45.GL_ZERO_TO_ONE : GL45.GL_NEGATIVE_ONE_TO_ONE;
            case GL20.GL_MAX_VERTEX_ATTRIBS -> Vao.MAX_ATTRIBS;
            case GL20.GL_MAX_DRAW_BUFFERS, GL30.GL_MAX_COLOR_ATTACHMENTS -> Math.min(Fbo.MAX_COLOR, gpuCaps().maxColorAttachments);
            case GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS -> GlState.MAX_BUFFER_BINDINGS;
            case GL31.GL_MAX_UNIFORM_BLOCK_SIZE -> gpuCaps().maxUniformBlockSize;
            case GL31.GL_MAX_VERTEX_UNIFORM_BLOCKS, GL31.GL_MAX_FRAGMENT_UNIFORM_BLOCKS, GL31.GL_MAX_GEOMETRY_UNIFORM_BLOCKS, GL40.GL_MAX_TESS_CONTROL_UNIFORM_BLOCKS, GL40.GL_MAX_TESS_EVALUATION_UNIFORM_BLOCKS -> Math.min(14, gpuCaps().maxPerStageDescriptorUniformBuffers - 1);
            case GL31.GL_MAX_COMBINED_UNIFORM_BLOCKS -> 70;
            case GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT -> (int) Math.max(16, gpuCaps().minUniformBufferOffsetAlignment);
            case GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT -> (int) Math.max(16, gpuCaps().minStorageBufferOffsetAlignment);
            case GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS -> Math.min(GlState.MAX_BUFFER_BINDINGS, 16);
            case GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE -> 1 << 27;
            case GL43.GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS, GL43.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS, GL43.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS, GL43.GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS -> Math.min(16, gpuCaps().maxPerStageDescriptorStorageBuffers);
            case GL42.GL_MAX_IMAGE_UNITS -> GlState.MAX_IMAGE_UNITS;
            // Units are shared, and a stage has its full share of uniforms however many the others declare.
            case GL42.GL_MAX_COMBINED_IMAGE_UNIFORMS -> 5 * getInteger(GL42.GL_MAX_VERTEX_IMAGE_UNIFORMS);
            case GL42.GL_MAX_FRAGMENT_IMAGE_UNIFORMS, GL42.GL_MAX_VERTEX_IMAGE_UNIFORMS, GL43.GL_MAX_COMPUTE_IMAGE_UNIFORMS -> Math.min(imagesPerStage(), gpuCaps().maxReadWriteImagesPerStage);
            case GL43.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS -> gpuCaps().maxComputeWorkGroupInvocations;
            case GL20.GL_MAX_VERTEX_UNIFORM_COMPONENTS, GL20.GL_MAX_FRAGMENT_UNIFORM_COMPONENTS, GL32.GL_MAX_GEOMETRY_UNIFORM_COMPONENTS -> 4096;
            case GL41.GL_MAX_VERTEX_UNIFORM_VECTORS, GL41.GL_MAX_FRAGMENT_UNIFORM_VECTORS -> 1024;
            case GL20.GL_MAX_VARYING_FLOATS -> 124;
            case GL41.GL_MAX_VARYING_VECTORS -> 31;
            case GL32.GL_MAX_VERTEX_OUTPUT_COMPONENTS -> 124;
            // Nothing can send a fragment shader more than the 124 above, so this asks nothing of the device.
            case GL32.GL_MAX_FRAGMENT_INPUT_COMPONENTS -> 128;
            case GL32.GL_MAX_INTEGER_SAMPLES -> getInteger(GL30.GL_MAX_SAMPLES);
            case GL30.GL_MAX_SAMPLES, GL32.GL_MAX_COLOR_TEXTURE_SAMPLES, GL32.GL_MAX_DEPTH_TEXTURE_SAMPLES, GL43.GL_MAX_FRAMEBUFFER_SAMPLES -> Math.max(1, gpuCaps().maxSamples);
            case GL13.GL_SAMPLES -> framebufferSamples(framebuffers.get(st.drawFbo));
            case GL13.GL_SAMPLE_BUFFERS -> framebufferSamples(framebuffers.get(st.drawFbo)) > 1 ? 1 : 0;
            case GL12.GL_MAX_ELEMENTS_VERTICES, GL12.GL_MAX_ELEMENTS_INDICES -> 1 << 20;
            case GL15.GL_ARRAY_BUFFER_BINDING -> st.arrayBuffer;
            case GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING -> st.vao.elementBuffer;
            case GL30.GL_VERTEX_ARRAY_BINDING -> st.vao.id;
            case GL20.GL_CURRENT_PROGRAM -> st.program;
            case GL13.GL_ACTIVE_TEXTURE -> GL13.GL_TEXTURE0 + st.activeUnit;
            case GL11.GL_TEXTURE_BINDING_1D -> st.textures[st.activeUnit][GlState.T_1D];
            case GL11.GL_TEXTURE_BINDING_2D -> st.textures[st.activeUnit][GlState.T_2D];
            case GL12.GL_TEXTURE_BINDING_3D -> st.textures[st.activeUnit][GlState.T_3D];
            case GL13.GL_TEXTURE_BINDING_CUBE_MAP -> st.textures[st.activeUnit][GlState.T_CUBE];
            case GL30.GL_TEXTURE_BINDING_2D_ARRAY -> st.textures[st.activeUnit][GlState.T_2D_ARRAY];
            case GL30.GL_TEXTURE_BINDING_1D_ARRAY -> st.textures[st.activeUnit][GlState.T_1D_ARRAY];
            case GL31.GL_TEXTURE_BINDING_RECTANGLE -> st.textures[st.activeUnit][GlState.T_RECT];
            case GL31.GL_TEXTURE_BINDING_BUFFER -> st.textures[st.activeUnit][GlState.T_BUFFER];
            case GL40.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY -> st.textures[st.activeUnit][GlState.T_CUBE_ARRAY];
            case GL33.GL_SAMPLER_BINDING -> st.samplers[st.activeUnit];
            case GL30.GL_DRAW_FRAMEBUFFER_BINDING -> st.drawFbo;
            case GL30.GL_READ_FRAMEBUFFER_BINDING -> st.readFbo;
            case GL30.GL_RENDERBUFFER_BINDING -> st.renderbuffer;
            case GL31.GL_UNIFORM_BUFFER_BINDING -> st.uniformBuffer;
            case GL43.GL_SHADER_STORAGE_BUFFER_BINDING -> st.storageBuffer;
            case GL21.GL_PIXEL_PACK_BUFFER_BINDING -> st.pixelPack;
            case GL21.GL_PIXEL_UNPACK_BUFFER_BINDING -> st.pixelUnpack;
            case GL42.GL_COPY_READ_BUFFER_BINDING -> st.copyRead;
            case GL42.GL_COPY_WRITE_BUFFER_BINDING -> st.copyWrite;
            case GL40.GL_DRAW_INDIRECT_BUFFER_BINDING -> st.drawIndirect;
            case ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB -> st.parameterBuffer;
            case GL42.GL_ATOMIC_COUNTER_BUFFER_BINDING -> st.atomicBuffer;
            case GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING -> st.dispatchIndirect;
            case GL11.GL_BLEND_SRC, GL14.GL_BLEND_SRC_RGB -> st.blendSrcRgb[0];
            case GL11.GL_BLEND_DST, GL14.GL_BLEND_DST_RGB -> st.blendDstRgb[0];
            case GL14.GL_BLEND_SRC_ALPHA -> st.blendSrcA[0];
            case GL14.GL_BLEND_DST_ALPHA -> st.blendDstA[0];
            case GL14.GL_BLEND_EQUATION -> st.blendEqRgb[0];
            case GL20.GL_BLEND_EQUATION_ALPHA -> st.blendEqA[0];
            case GL11.GL_DEPTH_FUNC -> st.depthFunc;
            case GL11.GL_CULL_FACE_MODE -> st.cullFace;
            case GL11.GL_FRONT_FACE -> st.frontFace;
            case GL11.GL_STENCIL_FUNC -> st.stencilFunc[0];
            case GL11.GL_STENCIL_REF -> st.stencilRef[0];
            case GL11.GL_STENCIL_VALUE_MASK -> st.stencilValueMask[0];
            case GL11.GL_STENCIL_WRITEMASK -> st.stencilWriteMask[0];
            case GL11.GL_STENCIL_FAIL -> st.stencilFail[0];
            case GL20.GL_STENCIL_BACK_FAIL -> st.stencilFail[1];
            case GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL -> st.stencilDepthFail[1];
            case GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS -> st.stencilPass[1];
            case GL12.GL_UNPACK_IMAGE_HEIGHT -> st.unpack.imageHeight;
            case GL12.GL_UNPACK_SKIP_IMAGES -> st.unpack.skipImages;
            case GL42.GL_UNPACK_COMPRESSED_BLOCK_WIDTH -> st.unpack.blockWidth;
            case GL42.GL_UNPACK_COMPRESSED_BLOCK_HEIGHT -> st.unpack.blockHeight;
            case GL42.GL_UNPACK_COMPRESSED_BLOCK_DEPTH -> st.unpack.blockDepth;
            case GL42.GL_UNPACK_COMPRESSED_BLOCK_SIZE -> st.unpack.blockSize;
            case GL42.GL_PACK_COMPRESSED_BLOCK_WIDTH -> st.pack.blockWidth;
            case GL42.GL_PACK_COMPRESSED_BLOCK_HEIGHT -> st.pack.blockHeight;
            case GL42.GL_PACK_COMPRESSED_BLOCK_DEPTH -> st.pack.blockDepth;
            case GL42.GL_PACK_COMPRESSED_BLOCK_SIZE -> st.pack.blockSize;
            case GL11.GL_STENCIL_PASS_DEPTH_FAIL -> st.stencilDepthFail[0];
            case GL11.GL_STENCIL_PASS_DEPTH_PASS -> st.stencilPass[0];
            case GL20.GL_STENCIL_BACK_FUNC -> st.stencilFunc[1];
            case GL20.GL_STENCIL_BACK_REF -> st.stencilRef[1];
            case GL20.GL_STENCIL_BACK_VALUE_MASK -> st.stencilValueMask[1];
            case GL20.GL_STENCIL_BACK_WRITEMASK -> st.stencilWriteMask[1];
            case GL11.GL_STENCIL_CLEAR_VALUE -> st.clearStencil;
            case GL11.GL_UNPACK_ALIGNMENT -> st.unpack.alignment;
            case GL11.GL_UNPACK_ROW_LENGTH -> st.unpack.rowLength;
            case GL11.GL_UNPACK_SKIP_ROWS -> st.unpack.skipRows;
            case GL11.GL_UNPACK_SKIP_PIXELS -> st.unpack.skipPixels;
            case GL11.GL_PACK_ALIGNMENT -> st.pack.alignment;
            case GL11.GL_PACK_ROW_LENGTH -> st.pack.rowLength;
            case GL11.GL_PACK_SKIP_ROWS -> st.pack.skipRows;
            case GL11.GL_PACK_SKIP_PIXELS -> st.pack.skipPixels;
            case GL11.GL_DRAW_BUFFER -> drawBufferEnum(st, 0);
            case GL11.GL_READ_BUFFER -> readBufferEnum(st);
            case GL11.GL_POLYGON_MODE -> st.polygonModeFront;
            case GL11.GL_LOGIC_OP_MODE -> st.logicOp;
            case GL11.GL_DEPTH_BITS -> 24;
            case GL11.GL_STENCIL_BITS -> 8;
            case GL11.GL_RED_BITS, GL11.GL_GREEN_BITS, GL11.GL_BLUE_BITS, GL11.GL_ALPHA_BITS -> 8;
            case GL32.GL_PROVOKING_VERTEX -> st.provokingFirst ? GL32.GL_FIRST_VERTEX_CONVENTION : GL32.GL_LAST_VERTEX_CONVENTION;
            case GL31.GL_PRIMITIVE_RESTART_INDEX -> st.primitiveRestartIndex;
            case EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT -> (int) gpuCaps().maxSamplerAnisotropy;
            case KHRDebug.GL_MAX_DEBUG_GROUP_STACK_DEPTH -> 64;
            case KHRDebug.GL_MAX_LABEL_LENGTH -> 256;
            case KHRDebug.GL_MAX_DEBUG_MESSAGE_LENGTH -> 1024;
            case KHRDebug.GL_MAX_DEBUG_LOGGED_MESSAGES -> 64;
            case KHRDebug.GL_DEBUG_LOGGED_MESSAGES -> st.debug.loggedMessages();
            case KHRDebug.GL_DEBUG_NEXT_LOGGED_MESSAGE_LENGTH -> st.debug.nextLoggedLength();
            case KHRDebug.GL_DEBUG_GROUP_STACK_DEPTH -> st.debug.groupDepth();
            case NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX, NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX -> (int) Math.min(Integer.MAX_VALUE, deviceLocalBytes() / 1024);
            case NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX -> (int) Math.min(Integer.MAX_VALUE, Math.max(0, deviceLocalBytes() - allocatedBytes()) / 1024);
            case GL43.GL_MAX_FRAMEBUFFER_WIDTH -> gpuCaps().maxFramebufferWidth;
            case GL43.GL_MAX_FRAMEBUFFER_HEIGHT -> gpuCaps().maxFramebufferHeight;
            case GL43.GL_MAX_FRAMEBUFFER_LAYERS -> gpuCaps().maxImageArrayLayers;
            case GL32.GL_MAX_GEOMETRY_OUTPUT_VERTICES -> 256;
            case GL32.GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS -> 1024;
            case GL40.GL_PATCH_VERTICES -> st.patchVertices;
            case GL40.GL_MAX_PATCH_VERTICES -> 32;
            case GL30.GL_MAX_PROGRAM_TEXEL_OFFSET -> 7;
            case GL30.GL_MIN_PROGRAM_TEXEL_OFFSET -> -8;
            case GL43.GL_MAX_UNIFORM_LOCATIONS -> 1024;
            case GL41.GL_NUM_PROGRAM_BINARY_FORMATS -> 0;
            case GL41.GL_NUM_SHADER_BINARY_FORMATS -> 1;
            case GL41.GL_SHADER_BINARY_FORMATS -> GL46.GL_SHADER_BINARY_FORMAT_SPIR_V;
            case GL41.GL_PROGRAM_PIPELINE_BINDING -> GlPipelines.boundPipeline();
            case GL46.GL_NUM_SPIR_V_EXTENSIONS -> 0;
            default -> {
                if (pname >= GL20.GL_DRAW_BUFFER0 && pname <= GL20.GL_DRAW_BUFFER15) yield drawBufferEnum(st, pname - GL20.GL_DRAW_BUFFER0);
                final int other = otherInteger(st, pname);
                if (other != NOT_A_QUERY) yield other;
                if (GlLegacy.query(pname, legacyScratch) != 0) yield legacyInteger(pname, legacyScratch[0]);
                warnOnce("glGetInteger(0x" + Integer.toHexString(pname) + ") is not tracked, returning 0");
                recordError(GL11.GL_INVALID_ENUM);
                yield 0;
            }
        };
    }

    public int clientWaitSync(long sync, int flags, long timeout) {
        if (sync <= 0L) return GL32.GL_WAIT_FAILED;
        pollStream();
        if (isIdle(sync)) return GL32.GL_ALREADY_SIGNALED;
        if (sync >= currentSerial()) {
            if ((flags & GL32.GL_SYNC_FLUSH_COMMANDS_BIT) == 0 && timeout == 0L) return GL32.GL_TIMEOUT_EXPIRED;
            endPass();
            submitNow();
        }
        if (timeout == 0L) {
            pollStream();
            return isIdle(sync) ? GL32.GL_ALREADY_SIGNALED : GL32.GL_TIMEOUT_EXPIRED;
        }
        return waitForSerial(sync, timeout) ? GL32.GL_CONDITION_SATISFIED : GL32.GL_TIMEOUT_EXPIRED;
    }

    private static final class BufferTexture {
        int buffer, internal, declared, format, type, texelBytes;
        long offset, size;
        int syncedVersion = -1;
        long syncedFrame = -1L, syncedGpuWrite = -1L;
        boolean synced;
        /** An imageBuffer may have written the image since the buffer was last brought up to date. */
        boolean imageWritten;
    }

    private final Int2ObjectOpenHashMap<BufferTexture> bufferTextures = new Int2ObjectOpenHashMap<>();

    /** A level query about a buffer texture, whose only level is the buffer's range. Negative for anything else it could be asked. */
    private int bufferTextureParam(int texture, int pname) {
        final BufferTexture t = bufferTextures.get(texture);
        final B b = t == null ? null : buffers().get(t.buffer);
        final long size = b == null ? 0L : t.size > 0L ? t.size : b.size - t.offset;
        return switch (pname) {
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> t == null ? GL11.GL_RGBA8 : t.declared;
            case GL11.GL_TEXTURE_WIDTH -> t == null ? 0 : (int) Math.min(size / t.texelBytes, getInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE));
            case GL11.GL_TEXTURE_HEIGHT, GL12.GL_TEXTURE_DEPTH -> t == null ? 0 : 1;
            case GL43.GL_TEXTURE_BUFFER_SIZE -> (int) size;
            case GL43.GL_TEXTURE_BUFFER_OFFSET -> t == null ? 0 : (int) t.offset;
            case GL31.GL_TEXTURE_BUFFER_DATA_STORE_BINDING -> t == null ? 0 : t.buffer;
            default -> -1;
        };
    }

    public void texBuffer(int internalFormat, int buffer, long offset, long size) {
        final GlState st = st();
        textureBuffer(st.textures[st.activeUnit][GlState.T_BUFFER], internalFormat, buffer, offset, size);
    }

    public void textureBuffer(int texture, int internalFormat, int buffer, long offset, long size) {
        if (texture == 0) return;
        if (buffer == 0) {
            bufferTextures.remove(texture);
            return;
        }
        final int[] layout = bufferTextureLayout(internalFormat);
        if (layout == null) {
            warnOnce("buffer texture format 0x" + Integer.toHexString(internalFormat));
            recordError(GL11.GL_INVALID_ENUM);
            return;
        }
        final BufferTexture t = new BufferTexture();
        t.buffer = buffer;
        t.internal = layout[0];
        t.format = layout[1];
        t.type = layout[2];
        t.texelBytes = layout[3];
        t.offset = offset;
        t.size = size;
        t.declared = internalFormat;
        bufferTextures.put(texture, t);
    }

    /** Sampling format, client format, client type and texel size. Three-component floats widen, since no backend samples those. */
    private static int[] bufferTextureLayout(int internal) {
        return switch (internal) {
            case GL30.GL_R8 -> new int[] {internal, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 1};
            case GL30.GL_R16 -> new int[] {internal, GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, 2};
            case GL30.GL_R16F -> new int[] {internal, GL11.GL_RED, GL30.GL_HALF_FLOAT, 2};
            case GL30.GL_R32F -> new int[] {internal, GL11.GL_RED, GL11.GL_FLOAT, 4};
            case GL30.GL_R8I -> new int[] {internal, GL30.GL_RED_INTEGER, GL11.GL_BYTE, 1};
            case GL30.GL_R16I -> new int[] {internal, GL30.GL_RED_INTEGER, GL11.GL_SHORT, 2};
            case GL30.GL_R32I -> new int[] {internal, GL30.GL_RED_INTEGER, GL11.GL_INT, 4};
            case GL30.GL_R8UI -> new int[] {internal, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, 1};
            case GL30.GL_R16UI -> new int[] {internal, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, 2};
            case GL30.GL_R32UI -> new int[] {internal, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, 4};
            case GL30.GL_RG8 -> new int[] {internal, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, 2};
            case GL30.GL_RG16 -> new int[] {internal, GL30.GL_RG, GL11.GL_UNSIGNED_SHORT, 4};
            case GL30.GL_RG16F -> new int[] {internal, GL30.GL_RG, GL30.GL_HALF_FLOAT, 4};
            case GL30.GL_RG32F -> new int[] {internal, GL30.GL_RG, GL11.GL_FLOAT, 8};
            case GL30.GL_RG8I -> new int[] {internal, GL30.GL_RG_INTEGER, GL11.GL_BYTE, 2};
            case GL30.GL_RG16I -> new int[] {internal, GL30.GL_RG_INTEGER, GL11.GL_SHORT, 4};
            case GL30.GL_RG32I -> new int[] {internal, GL30.GL_RG_INTEGER, GL11.GL_INT, 8};
            case GL30.GL_RG8UI -> new int[] {internal, GL30.GL_RG_INTEGER, GL11.GL_UNSIGNED_BYTE, 2};
            case GL30.GL_RG16UI -> new int[] {internal, GL30.GL_RG_INTEGER, GL11.GL_UNSIGNED_SHORT, 4};
            case GL30.GL_RG32UI -> new int[] {internal, GL30.GL_RG_INTEGER, GL11.GL_UNSIGNED_INT, 8};
            case GL30.GL_RGB32F -> new int[] {GL30.GL_RGBA32F, GL11.GL_RGB, GL11.GL_FLOAT, 12};
            case GL30.GL_RGB32I -> new int[] {GL30.GL_RGBA32I, GL30.GL_RGB_INTEGER, GL11.GL_INT, 12};
            case GL30.GL_RGB32UI -> new int[] {GL30.GL_RGBA32UI, GL30.GL_RGB_INTEGER, GL11.GL_UNSIGNED_INT, 12};
            case GL11.GL_RGBA8 -> new int[] {internal, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4};
            case GL11.GL_RGBA16 -> new int[] {internal, GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, 8};
            case GL30.GL_RGBA16F -> new int[] {internal, GL11.GL_RGBA, GL30.GL_HALF_FLOAT, 8};
            case GL30.GL_RGBA32F -> new int[] {internal, GL11.GL_RGBA, GL11.GL_FLOAT, 16};
            case GL30.GL_RGBA8I -> new int[] {internal, GL30.GL_RGBA_INTEGER, GL11.GL_BYTE, 4};
            case GL30.GL_RGBA16I -> new int[] {internal, GL30.GL_RGBA_INTEGER, GL11.GL_SHORT, 8};
            case GL30.GL_RGBA32I -> new int[] {internal, GL30.GL_RGBA_INTEGER, GL11.GL_INT, 16};
            case GL30.GL_RGBA8UI -> new int[] {internal, GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_BYTE, 4};
            case GL30.GL_RGBA16UI -> new int[] {internal, GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_SHORT, 8};
            case GL30.GL_RGBA32UI -> new int[] {internal, GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_INT, 16};
            default -> null;
        };
    }

    /**
     * Copies each bound buffer texture's buffer to the 2D image shaders read it from, when it changed. A persistent mapping
     * announces nothing, so a buffer under one is copied again once a frame.
     */
    public final void syncBeforeDraw(GlState st) {
        syncTextureViews();
        syncPipelineUniforms(st);
        sendLodBias(st);
        convertAttributes(st);
        sendDrawParameters(st);
        sendClipControl(st);
        if (bufferTextures.isEmpty()) return;
        for (int unit = 0; unit < GlState.MAX_UNITS; unit++) syncBufferTexture(st, st.textures[unit][GlState.T_BUFFER]);
        for (int unit = 0; unit < GlState.MAX_IMAGE_UNITS; unit++) {
            final int texture = st.imageTex[unit];
            final BufferTexture t = texture == 0 ? null : bufferTextures.get(texture);
            if (t == null) continue;
            syncBufferTexture(st, texture);
            if (st.imageAccess[unit] != GL15.GL_READ_ONLY) t.imageWritten = true;
        }
    }

    /** Sets the hidden uniform through which each sampler's GL_TEXTURE_LOD_BIAS reaches the samples that add it, on a device needing that. */
    private void sendLodBias(GlState st) {
        if (!ShaderCompiler.lodBiasInShader) return;
        final P p = programs().current(st);
        if (p == null || !p.linked) return;
        if (p.lodBiasGen != p.gen) {
            p.lodBiasGen = p.gen;
            p.biasesSamples = p.uniforms.stream().anyMatch(u -> u.name.startsWith(LodBiasLowering.PREFIX));
        }
        if (!p.biasesSamples) return;
        for (ProgramBase.Uniform u : p.uniforms) {
            if (!u.isOpaque() || u.units == null) continue;
            if (u.lodBiasLocation == -2) {
                final String name = LodBiasLowering.biasNameOf(u.name);
                final int plain = p.locations.getInt(name);
                final int base = plain >= 0 ? plain : p.locations.getInt(name + "[0]");
                u.lodBiasLocation = base < 0 ? -1 : base + biasElement(p, u, name);
                u.lodBias = new float[u.units.length];
            }
            if (u.lodBiasLocation < 0) continue;
            final int slot = RendererBase.biasSlot(u.glType);
            if (slot < 0) continue;
            for (int e = 0; e < u.units.length; e++) {
                final int unit = Math.min(Math.max(0, u.units[e]), GlState.MAX_UNITS - 1);
                final TexBase t = textures().get(st.textures[unit][slot]);
                final SamplerObj object = st.samplers[unit] != 0 ? samplerObjects.get(st.samplers[unit]) : null;
                final float bias = Math.max(-15f, Math.min(15f, object != null ? object.lodBias : t != null ? t.lodBias : 0f));
                if (bias == u.lodBias[e]) continue;
                u.lodBias[e] = bias;
                MemoryUtil.memPutFloat(scratch, bias);
                programs().uniform(p, u.lodBiasLocation + e, 1, 1, true, scratch);
            }
        }
    }

    /** Which element of its bias a sampler's first unit takes: a struct's elements count the member's units in turn. */
    private static int biasElement(ProgramBase<?> p, ProgramBase.Uniform u, String biasName) {
        final int[] at = LodBiasLowering.structElementsOf(u.name);
        if (at == null) return 0;
        // The member's length, from the units of the struct's first element.
        int perElement = 0;
        for (ProgramBase.Uniform other : p.uniforms) {
            final int[] o = other.isOpaque() && other.units != null ? LodBiasLowering.structElementsOf(other.name) : null;
            if (o != null && o[0] == 0 && LodBiasLowering.biasNameOf(other.name).equals(biasName)) perElement = Math.max(perElement, o[1] + other.units.length);
        }
        return at[0] * Math.max(1, perElement) + at[1];
    }

    private void syncBufferTexture(GlState st, int texture) {
        if (texture == 0) return;
        final BufferTexture t = bufferTextures.get(texture);
        final B b = t == null ? null : buffers().get(t.buffer);
        if (b == null) return;
        if (t.imageWritten) writeBackBufferTexture(texture, t, b);
        // The device's writes move lastGpuWrite; only a persistent mapping can change the contents unseen, so only that is looked at again each frame.
        if (t.synced && t.syncedVersion == b.contentVersion && t.syncedGpuWrite == b.lastGpuWrite && (!b.persistentlyMapped || t.syncedFrame == frames)) return;
        t.synced = true;
        t.syncedVersion = b.contentVersion;
        t.syncedGpuWrite = b.lastGpuWrite;
        t.syncedFrame = frames;
        uploadBufferTexture(st, texture, t, b);
    }

    /** A buffer the device wrote behind the calls that keep count, which the textures over it have to see. */
    public final void markBufferWritten(int buffer) {
        final B b = buffers().get(buffer);
        if (b != null) b.contentVersion++;
    }

    /** Reading a buffer sees what image stores left in the texture over it, barrier or not. */
    private void writeBackImageWrites() {
        if (bufferTextures.isEmpty()) return;
        for (Int2ObjectMap.Entry<BufferTexture> entry : bufferTextures.int2ObjectEntrySet()) {
            final BufferTexture t = entry.getValue();
            final B b = t.imageWritten ? buffers().get(t.buffer) : null;
            if (b != null) writeBackBufferTexture(entry.getIntKey(), t, b);
        }
    }

    private void writeBackBufferTextures() {
        if (bufferTextures.isEmpty()) return;
        for (Int2ObjectMap.Entry<BufferTexture> entry : bufferTextures.int2ObjectEntrySet()) {
            final BufferTexture t = entry.getValue();
            final B b = buffers().get(t.buffer);
            // A shader may have written the buffer itself, which only a barrier promises to show.
            if (b != null && t.imageWritten) writeBackBufferTexture(entry.getIntKey(), t, b);
            else t.synced = false;
        }
    }

    private void writeBackBufferTexture(int texture, BufferTexture t, B b) {
        t.imageWritten = false;
        final T image = textures().get(texture);
        final long bytes = t.size <= 0 ? b.size - t.offset : Math.min(t.size, b.size - t.offset);
        final long texels = bytes / t.texelBytes;
        if (image == null || !image.hasImage() || texels <= 0) return;
        final PixelConvert.Store tight = new PixelConvert.Store();
        tight.alignment = 1;
        final int width = image.levelWidth(0), height = image.levelHeight(0);
        final long copy = MemoryUtil.nmemCalloc((long) width * height, t.texelBytes);
        try {
            if (textures().readImage(image, Formats.ASPECT_COLOR, 0, 0, 0, 0, width, height, t.format, t.type, tight, copy)) {
                // The buffer may have grown since the image was sized for it.
                buffers().write(b, t.offset, Math.min(texels, (long) width * height) * t.texelBytes, copy);
                b.contentVersion++;
                t.syncedVersion = b.contentVersion;
                t.syncedGpuWrite = b.lastGpuWrite;
            }
        } finally {
            MemoryUtil.nmemFree(copy);
        }
    }

    private void uploadBufferTexture(GlState st, int texture, BufferTexture t, B b) {
        final long bytes = t.size <= 0 ? b.size - t.offset : Math.min(t.size, b.size - t.offset);
        final long texels = bytes / t.texelBytes;
        if (texels <= 0) return;
        final int row = 1 << Spirv.BUFFER_TEXTURE_ROW_BITS;
        final int width = (int) Math.min(row, texels), height = (int) ((texels + row - 1) / row);
        if (texels > row && texels % row != 0) warnOnce("textureSize of a buffer texture reports whole rows of " + row + " texels; this one has " + texels);
        final long copy = MemoryUtil.nmemCalloc((long) width * height, t.texelBytes);
        final PixelConvert.Store saved = new PixelConvert.Store();
        saved.copyFrom(st.unpack);
        final int unpackBuffer = st.pixelUnpack, previous = st.textures[st.activeUnit][GlState.T_2D];
        try {
            buffers().read(b, t.offset, texels * t.texelBytes, copy);
            st.unpack.copyFrom(new PixelConvert.Store());
            st.unpack.alignment = 1;
            st.pixelUnpack = 0;
            textures().getOrCreate(texture, GL31.GL_TEXTURE_BUFFER);
            st.textures[st.activeUnit][GlState.T_2D] = texture;
            texImage2D(GL11.GL_TEXTURE_2D, 0, t.internal, width, height, 0, t.format, t.type, copy);
        } finally {
            st.textures[st.activeUnit][GlState.T_2D] = previous;
            st.pixelUnpack = unpackBuffer;
            st.unpack.copyFrom(saved);
            MemoryUtil.nmemFree(copy);
        }
    }

    public String objectLabelOf(int identifier, int name) {
        final String label = switch (identifier) {
            case GL11.GL_TEXTURE -> {
                final T t = textures().get(name);
                yield t == null ? null : t.label;
            }
            case KHRDebug.GL_BUFFER -> {
                final B b = buffers().get(name);
                yield b == null ? null : b.label;
            }
            case KHRDebug.GL_PROGRAM -> {
                final P p = programs().program(name);
                yield p == null ? null : p.label;
            }
            case KHRDebug.GL_SHADER -> {
                final Shader s = programs().shader(name);
                yield s == null ? null : s.label;
            }
            default -> null;
        };
        return label == null ? "" : label;
    }

    public void objectLabel(int identifier, int name, CharSequence label) {
        final String l = label == null ? null : label.toString();
        switch (identifier) {
            case GL11.GL_TEXTURE -> {
                final T t = textures().get(name);
                if (t != null) {
                    t.label = l;
                    if (t.image != 0L) debugNameImage(t.image, l);
                }
            }
            case KHRDebug.GL_BUFFER -> {
                final B b = buffers().get(name);
                if (b != null) b.label = l;
            }
            case KHRDebug.GL_PROGRAM -> {
                final P p = programs().program(name);
                if (p != null) p.label = l;
            }
            case KHRDebug.GL_SHADER -> {
                final Shader s = programs().shader(name);
                if (s != null) s.label = l;
            }
            default -> { }
        }
    }
}
