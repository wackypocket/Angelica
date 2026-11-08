package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.vulkan.frame.CommandStream;
import com.gtnewhorizons.angelica.glemu.gl.TokenMaps;
import com.gtnewhorizons.angelica.glemu.gl.PipelineStamp;
import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.gl.DynamicStateCache;
import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.glemu.gl.RendererBase;
import com.gtnewhorizons.angelica.glemu.pipeline.ClearPass;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.SamplerObj;
import com.gtnewhorizons.angelica.glemu.resource.Vao;
import com.gtnewhorizons.angelica.glemu.shader.LogicOpLowering;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import com.gtnewhorizons.angelica.vulkan.pipeline.DescriptorPools;
import com.gtnewhorizons.angelica.vulkan.pipeline.Pipelines;
import com.gtnewhorizons.angelica.vulkan.pipeline.VertexFormats;
import com.gtnewhorizons.angelica.vulkan.resource.Buf;
import com.gtnewhorizons.angelica.vulkan.resource.DefaultFramebuffer;
import com.gtnewhorizons.angelica.vulkan.resource.Images;
import com.gtnewhorizons.angelica.vulkan.resource.Target;
import com.gtnewhorizons.angelica.vulkan.resource.Tex;
import com.gtnewhorizons.angelica.vulkan.resource.VkFormats;
import com.gtnewhorizons.angelica.vulkan.shader.Program;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.io.File;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferInheritanceInfo;
import org.lwjgl.vulkan.VkCommandBufferInheritanceRenderingInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.*;

public final class Renderer extends RendererBase<Tex, Program, Target> {

    private final VulkanGL gl;
    public final Pipelines pipelines;
    final DescriptorPools pools;
    private final Pipelines.Desc desc = new Pipelines.Desc();

    private long boundSerial = -1;
    private long boundPipeline;
    private long boundSet;
    private long boundLayout;
    private final int[] boundDynamicOffsets = new int[Program.MAX_DYNAMIC_OFFSETS];
    private int boundDynamicCount = -1;
    private final DynamicStateCache dynamicCache = new DynamicStateCache();
    private long boundIndexBuffer = -1;
    private long boundIndexOffset = -1;
    private int boundIndexType = -1;
    private final long vbHandles = MemoryUtil.nmemAlloc(32 * 8);
    private final VkRenderingAttachmentInfo.Buffer passColors = VkRenderingAttachmentInfo.calloc(GlState.MAX_DRAW_BUFFERS);
    private final VkRenderingAttachmentInfo passDepth = VkRenderingAttachmentInfo.calloc();
    private final VkRenderingAttachmentInfo passStencil = VkRenderingAttachmentInfo.calloc();
    private final VkRenderingInfo passInfo = VkRenderingInfo.calloc();
    private final IntBuffer inheritFormats = MemoryUtil.memAllocInt(GlState.MAX_DRAW_BUFFERS);
    private final VkCommandBufferInheritanceRenderingInfo inheritRendering = VkCommandBufferInheritanceRenderingInfo.calloc();
    private final VkCommandBufferInheritanceInfo inherit = VkCommandBufferInheritanceInfo.calloc();
    private final boolean deferPasses;
    private final ArrayList<Images.Transition> prePassBarriers = new ArrayList<>();
    long depthDiscards;
    private final long vbOffsets = MemoryUtil.nmemAlloc(32 * 8);
    private final long setScratch = MemoryUtil.nmemAlloc(8 + Program.MAX_DYNAMIC_OFFSETS * 4L);
    private final VkViewport viewportScratch = VkViewport.calloc();
    private final VkRect2D scissorScratch = VkRect2D.calloc();
    private final long blendScratch = MemoryUtil.nmemAlloc(16);
    private final long[] boundVb = new long[32];
    private final long[] boundVbOff = new long[32];
    private int boundVbCount = -1;

    private final PipelineStamp pipelineStamp = new PipelineStamp();

    public boolean pendingTransferBarrier;

    private record SamplerKey(long state, float minLod, float maxLod, float bias, float aniso) {}
    private final Object2LongOpenHashMap<SamplerKey> samplers = new Object2LongOpenHashMap<>();

    private Buf dummyBuffer;

    long draws, passes, setWrites;

    private long[] keyScratch = new long[256];
    private final int[] constAttrib = new int[2 * Vao.MAX_ATTRIBS];

    private Program descVertexProgram;
    private int descVertexProgramGen, descVertexLayout = -1;
    private final int[] dynamicOffsets = new int[Program.MAX_DYNAMIC_OFFSETS];
    private int dynamicCount;
    private static final int MAX_RESOLVE_PASSES = 4;
    private long pushedLayout;
    private long constRingBuffer;
    private long constRingOffset;

    Renderer(VulkanGL gl, File pipelineCacheFile) {
        super(gl, new Target(), new Target(), new Target(), new Tex[LogicOpLowering.MAX_LOCATIONS]);
        this.gl = gl;
        this.pipelines = new Pipelines(gl, pipelineCacheFile);
        this.pools = new DescriptorPools(gl);
        this.deferPasses = !SystemProperties.DISABLE_VULKAN_DEFER_PASSES && gl.gpu.isMoltenVkOrApple() && !gl.debug.enabled();
    }

    void init() {
        dummy2D = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, Formats.RGBA8, 1, 1, 1, 1, "dummy2D");
        dummyStorage = gl.textures.internalStorageTexture(Formats.RGBA8, "dummyStorage");
        dummyStorageRead = gl.textures.internalStorageTexture(Formats.RGBA8, "dummyStorageRead", 256);
        dummy2DArray = gl.textures.internalTexture(GL30.GL_TEXTURE_2D_ARRAY, Formats.RGBA8, 1, 1, 1, 1, "dummy2DArray");
        dummy3D = gl.textures.internalTexture(GL12.GL_TEXTURE_3D, Formats.RGBA8, 1, 1, 1, 1, "dummy3D");
        dummyCube = gl.textures.internalTexture(GL13.GL_TEXTURE_CUBE_MAP, Formats.RGBA8, 1, 1, 1, 6, "dummyCube");
        dummyDepth = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, gl.textures.format(GL14.GL_DEPTH_COMPONENT24), 1, 1, 1, 1, "dummyDepth");
        final float[] black = {0, 0, 0, 1};
        final VkCommandBuffer cmd = gl.stream.cmd();
        for (Tex t : new Tex[] {dummy2D, dummy2DArray, dummy3D, dummyCube, dummyDepth}) clearDummy(t);
        gl.images.clearImage(cmd, dummyStorage, black, 1f, 0);
        gl.images.clearImage(cmd, dummyStorageRead, new float[4], 1f, 0);
        dummyBuffer = gl.buffers.internalBuffer(65536, "dummyBuffer");
    }

    public void endPass() {
        endPass(false);
    }

    void endPass(boolean frameEnd) {
        if (!passActive) return;
        gl.occlusionEnding();
        if (deferPasses) {
            final VkCommandBuffer contents = gl.stream.endSecondary();
            if (frameEnd && passTarget.depth != null && passTarget.depth == gl.defaultFb.depth) {
                if (passInfo.pDepthAttachment() != null) passDepth.storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
                if (passInfo.pStencilAttachment() != null) passStencil.storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
                passTarget.depth.discardedFrom = passTarget.depth.layout;
                passTarget.depth.layout = VK_IMAGE_LAYOUT_UNDEFINED;
                depthDiscards++;
            }
            final VkCommandBuffer cmd = gl.stream.cmd();
            for (int i = 0; i < prePassBarriers.size(); i++) gl.images.record(cmd, prePassBarriers.get(i));
            prePassBarriers.clear();
            beginRendering(cmd);
            vkCmdExecuteCommands(cmd, contents);
            endRendering(cmd);
        } else {
            endRendering(gl.stream.cmd());
        }
        passActive = false;
    }

    void renderEmptyPass(VkCommandBuffer cmd, VkRenderingInfo info) {
        if (gl.gpu.dynamicRenderingCore) vkCmdBeginRendering(cmd, info);
        else KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
        endRendering(cmd);
    }

    private void beginRendering(VkCommandBuffer cmd) {
        if (gl.gpu.dynamicRenderingCore) vkCmdBeginRendering(cmd, passInfo);
        else KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, passInfo);
    }

    private void endRendering(VkCommandBuffer cmd) {
        if (gl.gpu.dynamicRenderingCore) vkCmdEndRendering(cmd);
        else KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
    }

    @Override protected boolean renderable(int pf) {
        return gl.images.supports(VkFormats.of(pf), VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT);
    }

    /** Whether the current pass has every sample at the pixel's centre. Without variableSampleLocations a pass's pipelines must agree on it, so a change starts a new pass. */
    private boolean passCentered;

    void ensurePass(Target t) {
        ensurePass(t, false);
    }

    void ensurePass(Target t, boolean centered) {
        if (sameAsPass(t) && passCentered == centered) return;
        passCentered = centered;
        endPass();
        materializePartialAttachments(t);
        final VkCommandBuffer cmd = gl.stream.cmd();
        final long serial = gl.stream.currentSerial();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkRenderingAttachmentInfo.Buffer colors = passColors;
            colors.limit(colors.capacity());
            MemoryUtil.memSet(colors.address(), 0, (long) colors.capacity() * VkRenderingAttachmentInfo.SIZEOF);
            for (int i = 0; i < t.colorCount; i++) {
                final VkRenderingAttachmentInfo a = colors.get(i).sType$Default();
                final Tex tex = t.color[i];
                if (tex == null) {
                    a.imageView(VK_NULL_HANDLE);
                    continue;
                }
                final int layout = tex.layout == VK_IMAGE_LAYOUT_GENERAL ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                final boolean undefined = tex.layout == VK_IMAGE_LAYOUT_UNDEFINED;
                // Clears requested since the last pass become load ops; an undefined image is loaded as don't-care.
                int loadOp = undefined ? VK_ATTACHMENT_LOAD_OP_DONT_CARE : VK_ATTACHMENT_LOAD_OP_LOAD;
                if (tex.pendingClear && (tex.clearAspects & VK_IMAGE_ASPECT_COLOR_BIT) != 0) {
                    loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
                    Images.fillClearColor(a.clearValue().color(), tex, tex.clearColor);
                    tex.pendingClear = false;
                    tex.needsClear = false;
                } else if (tex.needsClear) {
                    loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
                    tex.needsClear = false;
                }
                gl.images.transition(cmd, tex, layout);
                t.colorView[i] = gl.images.attachmentView(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i], gl.st().framebufferSrgb);
                a.imageView(t.colorView[i]).imageLayout(layout).loadOp(loadOp).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                tex.markGpuWrite(serial);
            }
            colors.limit(t.colorCount);
            VkRenderingAttachmentInfo depthInfo = null, stencilInfo = null;
            if (t.depth != null) {
                final Tex tex = t.depth;
                final int layout = tex.layout == VK_IMAGE_LAYOUT_GENERAL ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
                final boolean undefined = tex.layout == VK_IMAGE_LAYOUT_UNDEFINED;
                int depthLoad = undefined ? VK_ATTACHMENT_LOAD_OP_DONT_CARE : VK_ATTACHMENT_LOAD_OP_LOAD;
                int stencilLoad = depthLoad;
                float clearDepth = 1f;
                int clearStencil = 0;
                if (tex.needsClear) {
                    depthLoad = stencilLoad = VK_ATTACHMENT_LOAD_OP_CLEAR;
                    tex.needsClear = false;
                }
                if (tex.pendingClear) {
                    if ((tex.clearAspects & VK_IMAGE_ASPECT_DEPTH_BIT) != 0) {
                        depthLoad = VK_ATTACHMENT_LOAD_OP_CLEAR;
                        clearDepth = tex.clearDepth;
                    }
                    if ((tex.clearAspects & VK_IMAGE_ASPECT_STENCIL_BIT) != 0) {
                        stencilLoad = VK_ATTACHMENT_LOAD_OP_CLEAR;
                        clearStencil = tex.clearStencil;
                    }
                    tex.pendingClear = false;
                }
                gl.images.transition(cmd, tex, layout);
                t.depthView = gl.images.attachmentView(tex, t.depthLevel, t.depthLayer, t.depthLayers);
                if (t.hasDepth) {
                    depthInfo = passDepth.set(VkRenderingAttachmentInfo.calloc(stack)).sType$Default()
                        .imageView(t.depthView).imageLayout(layout).loadOp(depthLoad).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                    depthInfo.clearValue().depthStencil().set(clearDepth, clearStencil);
                }
                if (t.hasStencil) {
                    stencilInfo = passStencil.set(VkRenderingAttachmentInfo.calloc(stack)).sType$Default()
                        .imageView(t.depthView).imageLayout(layout).loadOp(stencilLoad).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                    stencilInfo.clearValue().depthStencil().set(clearDepth, clearStencil);
                }
                tex.markGpuWrite(serial);
            }
            final int layers = layerCount(t);
            final VkRenderingInfo info = passInfo.set(VkRenderingInfo.calloc(stack)).sType$Default()
                .flags(deferPasses ? VK_RENDERING_CONTENTS_SECONDARY_COMMAND_BUFFERS_BIT : 0)
                .layerCount(layers)
                .pColorAttachments(t.colorCount == 0 ? null : colors)
                .pDepthAttachment(depthInfo)
                .pStencilAttachment(stencilInfo);
            info.renderArea().offset().set(0, 0);
            info.renderArea().extent().set(Math.max(1, t.width), Math.max(1, t.height));
            // Deferred passes record into a secondary command buffer, so barriers for textures first sampled mid-pass can be placed in front of it.
            if (deferPasses) {
                inheritFormats.clear();
                for (int i = 0; i < t.colorCount; i++) inheritFormats.put(i, t.color[i] == null ? VK_FORMAT_UNDEFINED : VkFormats.of(gl.st().framebufferSrgb ? t.color[i].format.pf() : Formats.linearOf(t.color[i].format.pf())));
                inheritFormats.limit(t.colorCount);
                inheritRendering.sType$Default()
                    .pColorAttachmentFormats(t.colorCount == 0 ? null : inheritFormats)
                    .depthAttachmentFormat(depthInfo != null ? VkFormats.of(t.depth.format.pf()) : VK_FORMAT_UNDEFINED)
                    .stencilAttachmentFormat(stencilInfo != null ? VkFormats.of(t.depth.format.pf()) : VK_FORMAT_UNDEFINED)
                    .rasterizationSamples(targetSamples(t));
                inherit.sType$Default().pNext(inheritRendering.address());
                gl.stream.beginSecondary(inherit);
                boundSerial = -1;
            } else {
                beginRendering(cmd);
            }
        }
        copyTarget(t, passTarget);
        passActive = true;
        gl.occlusionBegan();
        passes++;
        dynamicCache.recheck();
    }

    private static boolean[] onlyAttachment(int attachment) {
        final boolean[] colors = new boolean[attachment + 1];
        colors[attachment] = true;
        return colors;
    }

    @Override public void clear(GlState st, int mask) {
        final Target t = resolveDrawTarget(st);
        if (!hasSize(t)) return;
        final boolean scissored = st.scissorTest && (st.scX > 0 || st.scY > 0 || st.scX + st.scW < t.width || st.scY + st.scH < t.height);
        if (st.scissorTest && (st.scW <= 0 || st.scH <= 0)) return;
        final boolean wantColor = (mask & GL11.GL_COLOR_BUFFER_BIT) != 0;
        final boolean wantDepth = (mask & GL11.GL_DEPTH_BUFFER_BIT) != 0 && st.depthMask && t.hasDepth;
        final boolean wantStencil = (mask & GL11.GL_STENCIL_BUFFER_BIT) != 0 && t.hasStencil && (st.stencilWriteMask[0] & 0xFF) != 0;
        final boolean stencilPartial = wantStencil && (st.stencilWriteMask[0] & 0xFF) != 0xFF;

        // A full clear outside a pass is deferred into the next pass's load op; partial or in-pass clears clear the attachments in place.
        final boolean inPass = sameAsPass(t);
        boolean anyImmediate = false;
        boolean colorLoadClear = false;
        int deferred = 0;
        if (wantColor) {
            for (int i = 0; i < t.colorCount; i++) {
                final Tex tex = t.color[i];
                if (tex == null) continue;
                final boolean[] cm = st.colorMask[Math.min(i, GlState.MAX_DRAW_BUFFERS - 1)];
                final boolean full = coversEveryChannel(cm, tex);
                final boolean none = !cm[0] && !cm[1] && !cm[2] && !cm[3];
                if (none) continue;
                if (!inPass && !scissored && full && !clearFollowsSrgbState(tex)) {
                    // The open pass may hold this image under another target; a later draw there must land after the clear.
                    releaseFromPass(tex);
                    tex.pendingClear = true;
                    tex.writes++;
                    tex.clearAspects = VK_IMAGE_ASPECT_COLOR_BIT;
                    System.arraycopy(st.clearColor, 0, tex.clearColor, 0, 4);
                    deferred |= 1 << i;
                    if (!canDefer(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i])) colorLoadClear = true;
                } else {
                    anyImmediate = true;
                }
            }
        }
        boolean depthImmediate = false, depthLoadClear = false;
        if ((wantDepth || wantStencil) && t.depth != null) {
            final Tex tex = t.depth;
            if (!inPass && !scissored && !stencilPartial) {
                releaseFromPass(tex);
                if (!tex.pendingClear) tex.clearAspects = 0;
                tex.pendingClear = true;
                tex.writes++;
                if (wantDepth) {
                    tex.clearAspects |= VK_IMAGE_ASPECT_DEPTH_BIT;
                    tex.clearDepth = (float) st.clearDepth;
                }
                if (wantStencil) {
                    tex.clearAspects |= VK_IMAGE_ASPECT_STENCIL_BIT;
                    tex.clearStencil = st.clearStencil & 0xFF;
                }
                if (!canDefer(tex, t.depthLevel, t.depthLayer, t.depthLayers)) {
                    depthLoadClear = true;
                }
            } else {
                depthImmediate = true;
            }
        }
        if (!anyImmediate && !depthImmediate && !colorLoadClear && !depthLoadClear) return;
        ensurePass(t);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkClearAttachment.Buffer atts = VkClearAttachment.calloc(t.colorCount + 1, stack);
            int n = 0;
            if (anyImmediate) {
                for (int i = 0; i < t.colorCount; i++) {
                    final Tex tex = t.color[i];
                    if (tex == null) continue;
                    final boolean[] cm = st.colorMask[Math.min(i, GlState.MAX_DRAW_BUFFERS - 1)];
                    if (!cm[0] && !cm[1] && !cm[2] && !cm[3]) continue;
                    if ((deferred & 1 << i) != 0) continue;
                    if (!coversEveryChannel(cm, tex)) {
                        if (tex.format.isInteger()) gl.warnOnce("masked clear of integer color attachment " + i + " is not emulated");
                        else gl.clearPass.clear(st, t, onlyAttachment(i), false, false);
                        continue;
                    }
                    final VkClearAttachment a = atts.get(n++).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(i);
                    Images.fillClearColor(a.clearValue().color(), tex, st.clearColor);
                }
            }
            if (depthImmediate && t.depth != null) {
                int aspects = 0;
                if (wantDepth) aspects |= VK_IMAGE_ASPECT_DEPTH_BIT;
                if (wantStencil && !stencilPartial) aspects |= VK_IMAGE_ASPECT_STENCIL_BIT;
                if (aspects != 0) {
                    atts.get(n++).aspectMask(aspects).clearValue().depthStencil().set((float) st.clearDepth, st.clearStencil & 0xFF);
                }
                if (stencilPartial) gl.clearPass.clear(st, t, ClearPass.NO_COLORS, false, true);
            }
            if (n > 0) {
                atts.limit(n);
                final VkClearRect.Buffer rect = VkClearRect.calloc(1, stack);
                rect.get(0).baseArrayLayer(0).layerCount(layerCount(t));
                final VkRect2D r = rect.get(0).rect();
                if (st.scissorTest) {
                    final int x0 = Math.max(0, st.scX), y0 = Math.max(0, st.scY);
                    final int x1 = Math.min(t.width, st.scX + st.scW), y1 = Math.min(t.height, st.scY + st.scH);
                    if (x1 <= x0 || y1 <= y0) return;
                    r.offset().set(x0, y0);
                    r.extent().set(x1 - x0, y1 - y0);
                } else {
                    r.offset().set(0, 0);
                    r.extent().set(t.width, t.height);
                }
                vkCmdClearAttachments(gl.stream.cmd(), atts, rect);
            }
        }
    }

    private void resetBoundIfNewCommandBuffer() {
        final long serial = gl.stream.currentSerial();
        if (boundSerial != serial) {
            boundSerial = serial;
            boundPipeline = 0L;
            boundSet = 0L;
            boundLayout = 0L;
            pushedLayout = 0L;
            boundDynamicCount = -1;
            dynamicCache.invalidate();
            boundIndexBuffer = -1;
            boundIndexOffset = -1;
            boundIndexType = -1;
            boundVbCount = -1;
        }
    }

    boolean prepareDraw(GlState st, int glMode, int vkTopology) {
        if (st.conditionalSkip) return false;
        gl.syncBeforeDraw(st);
        gl.applyPointSize(st, glMode);
        gl.applySampleCount(st);
        Program prog = gl.programs.current(st);
        if (prog != null && prog.linked && prog.noShaders) {
            gl.warnOnce("draw with program " + st.program + ", which has no shaders, draws nothing");
            return false;
        }
        if (prog == null || !prog.linked || prog.compute || prog.vertexless) {
            gl.warnOnce("draw without a linked graphics program (program " + st.program + ")");
            gl.recordError(GL11.GL_INVALID_OPERATION);
            return false;
        }
        final Target t = resolveDrawTarget(st);
        if (!hasSize(t)) return false;
        if (st.vpW <= 0 || st.vpH <= 0) return false;
        if (st.scissorTest && (st.scW <= 0 || st.scH <= 0)) return false;
        logicDst = null;
        if (st.logicOpEnabled && !gl.gpu.logicOp) prog = logicOpProgram(st, prog, t, gl.gpu.isMoltenVkOrApple() ? targetSamples(t) : 1);
        else if (!gl.gpu.isMoltenVkOrApple()) prog = borderProgram(st, prog);
        else prog = sampleRateProgram(st, prog, targetSamples(t));

        final long set = resolveDescriptors(st, prog, t, false);
        if (pendingTransferBarrier || pendingComputeBarrier) {
            endPass();
            final VkCommandBuffer cmd = gl.stream.cmd();
            int src = 0, srcAccess = 0;
            if (pendingTransferBarrier) {
                src |= VK_PIPELINE_STAGE_TRANSFER_BIT;
                srcAccess |= VK_ACCESS_TRANSFER_WRITE_BIT;
            }
            if (pendingComputeBarrier) {
                src |= shaderStages();
                srcAccess |= VK_ACCESS_SHADER_WRITE_BIT;
            }
            CommandStream.memoryBarrier(cmd, src, srcAccess,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | shaderStages() | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            pendingTransferBarrier = false;
            pendingComputeBarrier = false;
        }
        final int samples = targetSamples(t);
        ensurePass(t, samples > 1 && !st.multisample && gl.gpu.centersSamples(samples));
        resetBoundIfNewCommandBuffer();
        final VkCommandBuffer cmd = gl.stream.cmd();

        final Vao vao = st.vao;
        long pipeline = pipelineStamp.get(prog, st, vao, glMode, t, 0L);
        if (pipeline == 0L) {
            fillDesc(st, prog, vao, t, vkTopology);
            desc.encode(pipelines.probe);
            pipeline = pipelines.get(desc);
            if (pipeline == 0L) return false;
            pipelineStamp.set(prog, st, vao, glMode, t, 0L, pipeline);
        }
        if (pipeline != boundPipeline) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            boundPipeline = pipeline;
        }
        applyDynamicState(cmd, st, t);
        bindDescriptors(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, prog, set);
        pushUniforms(cmd, prog);
        bindVertexBuffers(cmd, st, prog, vao);
        draws++;
        return true;
    }

    private void fillDesc(GlState st, Program prog, Vao vao, Target t, int topology) {
        final Pipelines.Desc d = desc;
        d.program = prog;
        d.topology = topology;
        // Portability devices always restart strips, so the pipeline has to say it does.
        d.primitiveRestart = isStrip(topology) && (st.primitiveRestart || st.primitiveRestartFixed || gl.gpu.portabilitySubset) ? 1 : 0;
        d.patchPoints = topology == VK_PRIMITIVE_TOPOLOGY_PATCH_LIST ? st.patchVertices : 0;
        d.polygonMode = gl.gpu.fillModeNonSolid ? polygonMode(st.polygonModeFront) : VK_POLYGON_MODE_FILL;
        d.cullMode = !st.cull ? VK_CULL_MODE_NONE : switch (st.cullFace) {
            case GL11.GL_FRONT -> VK_CULL_MODE_FRONT_BIT;
            case GL11.GL_FRONT_AND_BACK -> VK_CULL_MODE_FRONT_AND_BACK;
            default -> VK_CULL_MODE_BACK_BIT;
        };
        d.frontFace = st.frontFace == GL11.GL_CCW == ((st.clipState & 1) == 0) ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE; // the framebuffer's y runs down, which inverts winding
        final boolean offset = switch (d.polygonMode) {
            case VK_POLYGON_MODE_LINE -> st.polyOffsetLine;
            case VK_POLYGON_MODE_POINT -> st.polyOffsetPoint;
            default -> st.polyOffsetFill;
        };
        d.depthBias = offset ? 1 : 0;
        d.depthClamp = st.depthClamp && gl.gpu.depthClamp ? 1 : 0;
        d.provokingLast = st.provokingFirst ? 0 : 1;
        d.samples = targetSamples(t);
        final boolean multisampling = d.samples > 1 && st.multisample;
        d.centered = d.samples > 1 && !st.multisample && gl.gpu.centersSamples(d.samples) ? 1 : 0;
        if (d.samples > 1 && !st.multisample && d.centered == 0) gl.warnOnce("drawing to a multisampled target with GL_MULTISAMPLE off still averages edges");
        d.coverageMask = com.gtnewhorizons.angelica.glemu.gl.GlBase.coverageMask(st, d.samples);
        d.alphaToCoverage = multisampling && st.sampleAlphaToCoverage ? 1 : 0;
        d.alphaToOne = multisampling && st.sampleAlphaToOne && gl.gpu.alphaToOne ? 1 : 0;
        d.minSampleShadingBits = multisampling && st.sampleShading && gl.gpu.sampleRateShading ? Float.floatToRawIntBits(st.minSampleShading) : 0;
        final boolean hasDepth = t.depth != null && t.hasDepth;
        d.depthTest = st.depthTest && hasDepth ? 1 : 0;
        d.depthWrite = st.depthTest && st.depthMask && hasDepth ? 1 : 0;
        d.depthCompare = compareOp(st.depthFunc);
        d.stencilTest = st.stencilTest && t.depth != null && t.hasStencil ? 1 : 0;
        if (d.stencilTest != 0) {
            for (int f = 0; f < 2; f++) {
                final int[] ops = f == 0 ? d.stencilFront : d.stencilBack;
                ops[0] = stencilOp(st.stencilFail[f]);
                ops[1] = stencilOp(st.stencilPass[f]);
                ops[2] = stencilOp(st.stencilDepthFail[f]);
                ops[3] = compareOp(st.stencilFunc[f]);
            }
        }
        d.logicOpEnable = st.logicOpEnabled && gl.gpu.logicOp ? 1 : 0;
        d.logicOp = logicOp(st.logicOp);
        d.colorCount = t.colorCount;
        for (int i = 0; i < t.colorCount; i++) {
            final Tex tex = t.color[i];
            d.colorFormat[i] = tex == null ? VK_FORMAT_UNDEFINED : VkFormats.of(st.framebufferSrgb ? tex.format.pf() : Formats.linearOf(tex.format.pf()));
            final int b = i * 8;
            // Without independent blending every attachment must share the first one's state.
            final int bi = gl.gpu.independentBlend ? Math.min(i, GlState.MAX_DRAW_BUFFERS - 1) : 0;
            final boolean integer = tex != null && tex.format.isInteger();
            final boolean blendable = tex != null && gl.images.supports(d.colorFormat[i], VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT);
            if (st.blend[bi] && tex != null && !integer && !blendable) gl.warnOnce("blending dropped: format " + d.colorFormat[i] + " cannot blend");
            d.blend[b] = st.blend[bi] && !integer && blendable && !(st.logicOpEnabled && !gl.gpu.logicOp) ? 1 : 0;
            if (d.blend[b] != 0) {
                d.blend[b + 1] = supportedBlendFactor(st.blendSrcRgb[bi]);
                d.blend[b + 2] = supportedBlendFactor(st.blendDstRgb[bi]);
                d.blend[b + 3] = blendOp(st.blendEqRgb[bi]);
                d.blend[b + 4] = supportedBlendFactor(st.blendSrcA[bi]);
                d.blend[b + 5] = supportedBlendFactor(st.blendDstA[bi]);
                d.blend[b + 6] = blendOp(st.blendEqA[bi]);
            } else {
                d.blend[b + 1] = d.blend[b + 2] = d.blend[b + 3] = d.blend[b + 4] = d.blend[b + 5] = d.blend[b + 6] = 0;
            }
            final boolean[] cm = st.colorMask[bi];
            int wm = (cm[0] ? VK_COLOR_COMPONENT_R_BIT : 0) | (cm[1] ? VK_COLOR_COMPONENT_G_BIT : 0) | (cm[2] ? VK_COLOR_COMPONENT_B_BIT : 0) | (cm[3] ? VK_COLOR_COMPONENT_A_BIT : 0);
            if (gl.gpu.independentBlend && (i >= prog.colorOutputs || tex == null)) wm = 0;
            d.blend[b + 7] = wm;
        }
        d.depthFormat = t.depth != null && t.hasDepth ? VkFormats.of(t.depth.format.pf()) : VK_FORMAT_UNDEFINED;
        d.stencilFormat = t.depth != null && t.hasStencil ? VkFormats.of(t.depth.format.pf()) : VK_FORMAT_UNDEFINED;
        d.clipMask = prog.clipMask ? st.clipDistances & 0xFF : 0;

        if (prog == descVertexProgram && prog.gen == descVertexProgramGen && vao.layoutId() == descVertexLayout) return;
        descVertexProgram = prog;
        descVertexProgramGen = prog.gen;
        descVertexLayout = vao.layoutId();
        int n = 0;
        int mask = prog.vertexInputMask;
        while (mask != 0) {
            final int location = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            final int loc = attribOf(prog, location);
            d.vLocation[n] = location;
            if (readsArray(prog, vao, location)) {
                if (prog.inputDoubles[loc] > 0) {
                    d.vFormat[n] = doubleWords(prog, vao, location) == 2 ? VK_FORMAT_R32G32_UINT : VK_FORMAT_R32G32B32A32_UINT;
                } else {
                    int fmt = VertexFormats.vkFormat(vao.readSize(loc), vao.readType(loc), vao.readNormalized(loc), vao.integer[loc]);
                    if (!VertexFormats.exact(vao.readType(loc), vao.integer[loc])) gl.warnOnce("vertex attribute type 0x" + Integer.toHexString(vao.type[loc]) + " has no exact Vulkan format");
                    d.vFormat[n] = gl.vertexFormatFallback(fmt);
                }
                d.vStride[n] = vao.readStride(loc);
                final int divisor = vao.bindingDivisor[vao.bindingOf[loc]];
                if (divisor > 1 && !gl.gpu.vertexAttributeDivisor) gl.warnOnce("attribute divisors above 1 need VK_EXT_vertex_attribute_divisor; stepping every instance");
                d.vRate[n] = divisor > 0 ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX;
                d.vDivisor[n] = gl.gpu.vertexAttributeDivisor ? Math.max(1, divisor) : 1;
            } else {
                d.vFormat[n] = switch (prog.inputKind[location]) {
                    case 1 -> VK_FORMAT_R32G32B32A32_SINT;
                    case 2 -> VK_FORMAT_R32G32B32A32_UINT;
                    default -> VK_FORMAT_R32G32B32A32_SFLOAT;
                };
                d.vStride[n] = 0;
                d.vRate[n] = VK_VERTEX_INPUT_RATE_VERTEX;
                d.vDivisor[n] = 1;
            }
            n++;
        }
        d.vertexCount = n;
    }

    private static boolean isStrip(int topology) {
        return topology == VK_PRIMITIVE_TOPOLOGY_LINE_STRIP || topology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP || topology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN
            || topology == VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY || topology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY;
    }

    private final double[] viewportValues = new double[6];
    private final int[] scissorValues = new int[4];

    private void applyViewportArray(VkCommandBuffer cmd, GlState st, Target t) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkViewport.Buffer viewports = VkViewport.calloc(GlState.MAX_VIEWPORTS, stack);
            final VkRect2D.Buffer scissors = VkRect2D.calloc(GlState.MAX_VIEWPORTS, stack);
            for (int i = 0; i < GlState.MAX_VIEWPORTS; i++) {
                gl.viewportAt(st, i, viewportValues);
                viewports.get(i).x((float) viewportValues[0]).y((float) viewportValues[1]).width((float) viewportValues[2]).height((float) viewportValues[3]).minDepth((float) viewportValues[4]).maxDepth((float) viewportValues[5]);
                gl.scissorAt(st, i, t.width, t.height, scissorValues);
                scissors.get(i).offset().set(scissorValues[0], scissorValues[1]);
                scissors.get(i).extent().set(scissorValues[2], scissorValues[3]);
            }
            vkCmdSetViewport(cmd, 0, viewports);
            vkCmdSetScissor(cmd, 0, scissors);
        }
    }

    private void applyDynamicState(VkCommandBuffer cmd, GlState st, Target t) {
        final DynamicStateCache c = dynamicCache;
        final Program drawing = gl.programs.current(st);
        if (drawing != null && drawing.writesViewportIndex && gl.gpu.multiViewport) {
            // A shader writing gl_ViewportIndex gets every viewport; the cache only tracks the first, so it forgets it and sets it again.
            applyViewportArray(cmd, st, t);
            c.forgetViewports();
        }
        if (c.upToDate(st, 0, t.globalGen, t.fboId, 0)) return;

        final float minDepth = (float) Math.max(0, Math.min(1, st.depthNear)), maxDepth = (float) Math.max(0, Math.min(1, st.depthFar));
        if (c.viewport(st.vpX, st.vpY, st.vpW, st.vpH, minDepth, maxDepth)) {
            viewportScratch.x(st.vpX).y(st.vpY).width(st.vpW).height(st.vpH).minDepth(minDepth).maxDepth(maxDepth);
            nvkCmdSetViewport(cmd, 0, 1, viewportScratch.address());
        }

        final int sx, sy, sw, sh;
        if (st.rasterizerDiscard) {
            sx = sy = sw = sh = 0;
        } else if (st.scissorTest) {
            sx = Math.max(0, st.scX);
            sy = Math.max(0, st.scY);
            sw = Math.max(sx, Math.min(t.width, st.scX + st.scW)) - sx;
            sh = Math.max(sy, Math.min(t.height, st.scY + st.scH)) - sy;
        } else {
            sx = sy = 0;
            sw = t.width;
            sh = t.height;
        }
        if (c.scissor(sx, sy, sw, sh)) {
            scissorScratch.offset().set(sx, sy);
            scissorScratch.extent().set(sw, sh);
            nvkCmdSetScissor(cmd, 0, 1, scissorScratch.address());
        }

        final float lineWidth = gl.gpu.wideLines ? Math.max(gl.gpu.lineWidthMin, Math.min(gl.gpu.lineWidthMax, st.lineWidth)) : 1f;
        if (c.lineWidth(lineWidth)) vkCmdSetLineWidth(cmd, lineWidth);

        final float biasClamp = gl.gpu.depthBiasClamp ? st.polyClamp : 0f;
        if (c.depthBias(st.polyUnits, st.polyFactor, biasClamp)) vkCmdSetDepthBias(cmd, st.polyUnits, biasClamp, st.polyFactor);

        if (c.blendColor(st.blendColor)) {
            for (int i = 0; i < 4; i++) MemoryUtil.memPutFloat(blendScratch + i * 4L, st.blendColor[i]);
            nvkCmdSetBlendConstants(cmd, blendScratch);
        }

        final int cmpF = st.stencilValueMask[0] & 0xFF, cmpB = st.stencilValueMask[1] & 0xFF;
        if (c.stencilCompareMask(cmpF, cmpB)) {
            if (cmpF == cmpB) {
                vkCmdSetStencilCompareMask(cmd, VK_STENCIL_FACE_FRONT_AND_BACK, cmpF);
            } else {
                vkCmdSetStencilCompareMask(cmd, VK_STENCIL_FACE_FRONT_BIT, cmpF);
                vkCmdSetStencilCompareMask(cmd, VK_STENCIL_FACE_BACK_BIT, cmpB);
            }
        }
        final int wrF = st.stencilWriteMask[0] & 0xFF, wrB = st.stencilWriteMask[1] & 0xFF;
        if (c.stencilWriteMask(wrF, wrB)) {
            if (wrF == wrB) {
                vkCmdSetStencilWriteMask(cmd, VK_STENCIL_FACE_FRONT_AND_BACK, wrF);
            } else {
                vkCmdSetStencilWriteMask(cmd, VK_STENCIL_FACE_FRONT_BIT, wrF);
                vkCmdSetStencilWriteMask(cmd, VK_STENCIL_FACE_BACK_BIT, wrB);
            }
        }
        final int refF = st.stencilRef[0] & 0xFF, refB = st.stencilRef[1] & 0xFF;
        if (c.stencilReference(refF, refB)) {
            if (refF == refB) {
                vkCmdSetStencilReference(cmd, VK_STENCIL_FACE_FRONT_AND_BACK, refF);
            } else {
                vkCmdSetStencilReference(cmd, VK_STENCIL_FACE_FRONT_BIT, refF);
                vkCmdSetStencilReference(cmd, VK_STENCIL_FACE_BACK_BIT, refB);
            }
        }
    }

    /**
     * Building the key can recreate an image (first storage use, format view) and destroy views an earlier binding
     * already put in it, so the key is rebuilt until no image changed and only then is a set looked up or written.
     */
    long resolveDescriptors(GlState st, Program prog, Target target, boolean compute) {
        int k;
        for (int pass = 1; ; pass++) {
            final long imagesBefore = gl.images.generation;
            k = buildDescriptorKey(st, prog, target, compute);
            if (gl.images.generation == imagesBefore) break;
            if (pass == MAX_RESOLVE_PASSES) {
                gl.warnOnce("program " + prog.id + " kept recreating images while its descriptors were resolved");
                break;
            }
        }
        return lookupOrWriteSet(prog, k);
    }

    private int buildDescriptorKey(GlState st, Program prog, Target target, boolean compute) {
        int keyLength = 0;
        for (int bi = 0; bi < prog.bindings.size(); bi++) keyLength += 3 * Math.max(1, prog.bindings.get(bi).count);
        if (keyScratch.length < keyLength) keyScratch = new long[keyLength * 2];
        final long[] key = keyScratch;
        int k = 0;
        dynamicCount = 0;
        for (int bi = 0; bi < prog.bindings.size(); bi++) {
            final Program.Binding b = prog.bindings.get(bi);
            if (b.kind != Program.BindingKind.STORAGE_IMAGE) continue;
            final Program.Uniform u = prog.uniforms.get(b.ref);
            for (int e = 0; e < b.count; e++) {
                gl.textures.prepareStorageImage(st, Math.min(Math.max(0, u.units[e]), GlState.MAX_IMAGE_UNITS - 1));
            }
        }
        // Read after the loop above, which can submit, so uses are stamped with the submission they land in.
        final long serial = gl.stream.currentSerial();
        for (int bi = 0; bi < prog.bindings.size(); bi++) {
            final Program.Binding b = prog.bindings.get(bi);
            switch (b.kind) {
                case DEFAULT_BLOCK -> {
                    final Program.StageBlock blk = prog.blocks[b.ref];
                    if (blk.dirty || blk.ringSerial != serial) {
                        gl.uniformRing.alloc(blk.size, gl.gpu.minUniformBufferOffsetAlignment);
                        MemoryUtil.memCopy(blk.address, gl.uniformRing.address, blk.size);
                        blk.ringBuffer = gl.uniformRing.buffer;
                        blk.ringOffset = gl.uniformRing.offset;
                        blk.ringSerial = serial;
                        blk.dirty = false;
                    }
                    key[k++] = blk.ringBuffer;
                    key[k++] = blk.size;
                    dynamicOffsets[dynamicCount++] = (int) blk.ringOffset;
                }
                case UNIFORM_BUFFER -> {
                    final Program.BufferBlock bb = prog.ubos.get(b.ref);
                    final int point = Math.min(bb.glBinding, GlState.MAX_BUFFER_BINDINGS - 1);
                    Buf buf = gl.buffers.get(st.uboBuffer[point]);
                    long offset = st.uboOffset[point];
                    if (buf == null || buf.alloc == null || offset >= buf.size) {
                        buf = dummyBuffer;
                        offset = 0;
                    }
                    final long avail = buf.size - offset;
                    long range = st.uboSize[point] > 0 ? Math.min(st.uboSize[point], avail) : avail;
                    range = Math.min(range, Math.max(16, bb.size));
                    range = Math.min(range, gl.gpu.maxUniformBlockSize);
                    gl.buffers.use(buf, compute ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : shaderStages());
                    key[k++] = buf.handle();
                    key[k++] = range;
                    if (b.dynamic) {
                        dynamicOffsets[dynamicCount++] = (int) offset;
                    } else {
                        key[k++] = offset;
                    }
                }
                case STORAGE_BUFFER -> {
                    final Program.BufferBlock bb = prog.ssbos.get(b.ref);
                    final int point = Math.min(bb.glBinding, GlState.MAX_BUFFER_BINDINGS - 1);
                    Buf buf = gl.buffers.get(bb.atomicCounters ? st.atomicBufferAt[point] : st.ssboBuffer[point]);
                    long offset = bb.atomicCounters ? st.atomicOffset[point] : st.ssboOffset[point];
                    final long size = bb.atomicCounters ? st.atomicSize[point] : st.ssboSize[point];
                    if (buf == null || buf.alloc == null || offset >= buf.size) {
                        buf = dummyBuffer;
                        offset = 0;
                    }
                    final long range = size > 0 ? Math.min(size, buf.size - offset) : buf.size - offset;
                    if (bb.readOnly) gl.buffers.use(buf, compute ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : shaderStages());
                    else gl.buffers.gpuWrite(buf, compute ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : shaderStages());
                    key[k++] = buf.handle();
                    key[k++] = offset;
                    key[k++] = range;
                }
                case COMBINED_SAMPLER, SAMPLED_IMAGE, SAMPLER -> {
                    final Program.Uniform u = prog.uniforms.get(b.ref);
                    for (int e = 0; e < b.count; e++) {
                        final int unit = Math.min(Math.max(0, u.units[e]), GlState.MAX_UNITS - 1);
                        long sampler = 0L, view = 0L;
                        int layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                        if (b.kind != Program.BindingKind.SAMPLER) {
                            final int viewType = viewTypeFor(b);
                            Tex tex = logicDst != null && u.name.startsWith(LogicOpLowering.DST_SAMPLER_PREFIX)
                                ? logicDst[Integer.parseInt(u.name.substring(LogicOpLowering.DST_SAMPLER_PREFIX.length()))]
                                : gl.textures.get(st.textures[unit][TokenMaps.targetSlot(b.dim, b.arrayed)]);
                            if (tex == null || !tex.hasImage() || Images.viewTypeFor(tex) != viewType
                                || !u.name.startsWith(LogicOpLowering.DST_SAMPLER_PREFIX) && !gl.sampledComplete(st, unit, tex)) {
                                tex = dummyFor(viewType, b.shadow, b.glType);
                            }
                            layout = prepareSampled(tex, target);
                            view = sampledView(tex, viewType);
                            tex.lastUse = serial;
                            if (b.kind == Program.BindingKind.COMBINED_SAMPLER) sampler = samplerFor(st, unit, tex);
                        } else {
                            sampler = samplerFor(st, unit, null);
                        }
                        key[k++] = view;
                        key[k++] = sampler;
                        key[k++] = layout;
                    }
                }
                case STORAGE_IMAGE -> {
                    final Program.Uniform u = prog.uniforms.get(b.ref);
                    for (int e = 0; e < b.count; e++) {
                        final int unit = Math.min(Math.max(0, u.units[e]), GlState.MAX_IMAGE_UNITS - 1);
                        final long view = gl.textures.storageImageView(st, unit, serial);
                        key[k++] = view;
                        key[k++] = 0L;
                        key[k++] = VK_IMAGE_LAYOUT_GENERAL;
                    }
                }
            }
        }
        return k;
    }

    private long lookupOrWriteSet(Program prog, int k) {
        final long[] key = keyScratch;
        if (prog.bindings.isEmpty()) return 0L;
        final long epoch = pools.epoch;
        long hash = k;
        for (int i = 0; i < k; i++) hash = hash * 0x9E3779B97F4A7C15L + key[i];
        final int bucket = Program.bucketStart(hash);
        for (int i = bucket; i < bucket + Program.SET_CACHE_WAYS; i++) {
            if (prog.cachedHashes[i] != hash || prog.cachedEpochs[i] != epoch) continue;
            final long[] ck = prog.cachedKeys[i];
            if (ck != null && ck.length == k && Arrays.equals(ck, 0, k, key, 0, k)) {
                prog.touchCached(i);
                return prog.cachedSets[i];
            }
        }
        final long set = pools.allocate(prog.setLayout);
        writeSet(set, prog, key);
        final int slot = prog.evictionSlot(bucket, epoch);
        prog.touchCached(slot);
        long[] ck = prog.cachedKeys[slot];
        if (ck == null || ck.length != k) ck = prog.cachedKeys[slot] = new long[k];
        System.arraycopy(key, 0, ck, 0, k);
        prog.cachedSets[slot] = set;
        prog.cachedEpochs[slot] = pools.epoch;
        prog.cachedHashes[slot] = hash;
        return set;
    }

    private void writeSet(long set, Program prog, long[] key) {
        setWrites++;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(prog.bindings.size(), stack);
            int k = 0;
            for (int bi = 0; bi < prog.bindings.size(); bi++) {
                final Program.Binding b = prog.bindings.get(bi);
                final VkWriteDescriptorSet w = writes.get(bi).sType$Default().dstSet(set).dstBinding(b.binding).dstArrayElement(0).descriptorType(b.descriptorType);
                switch (b.kind) {
                    case DEFAULT_BLOCK -> {
                        final VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
                        info.get(0).buffer(key[k]).offset(0).range(key[k + 1]);
                        k += 2;
                        w.pBufferInfo(info).descriptorCount(1);
                    }
                    case UNIFORM_BUFFER -> {
                        final VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
                        if (b.dynamic) {
                            info.get(0).buffer(key[k]).offset(0).range(key[k + 1]);
                            k += 2;
                        } else {
                            info.get(0).buffer(key[k]).offset(key[k + 2]).range(key[k + 1]);
                            k += 3;
                        }
                        w.pBufferInfo(info).descriptorCount(1);
                    }
                    case STORAGE_BUFFER -> {
                        final VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
                        info.get(0).buffer(key[k]).offset(key[k + 1]).range(key[k + 2]);
                        k += 3;
                        w.pBufferInfo(info).descriptorCount(1);
                    }
                    default -> {
                        final VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(b.count, stack);
                        for (int e = 0; e < b.count; e++) {
                            info.get(e).imageView(key[k]).sampler(key[k + 1]).imageLayout((int) key[k + 2]);
                            k += 3;
                        }
                        w.pImageInfo(info).descriptorCount(b.count);
                    }
                }
            }
            vkUpdateDescriptorSets(gl.gpu.device, writes, null);
        }
    }

    private void pushUniforms(VkCommandBuffer cmd, Program prog) {
        final boolean fresh = prog.pipelineLayout != pushedLayout;
        for (int s = 0; s < Program.STAGES; s++) {
            final Program.StageBlock blk = prog.blocks[s];
            if (blk == null || blk.pushOffset < 0 || !fresh && !blk.dirty) continue;
            nvkCmdPushConstants(cmd, prog.pipelineLayout, prog.stageBits[s], blk.pushOffset, blk.size, blk.address);
            blk.dirty = false;
        }
        pushedLayout = prog.pipelineLayout;
    }

    void bindDescriptors(VkCommandBuffer cmd, int bindPoint, Program prog, long set) {
        if (set == 0L) return;
        boolean same = set == boundSet && prog.pipelineLayout == boundLayout && boundDynamicCount == dynamicCount;
        if (same) {
            for (int i = 0; i < dynamicCount; i++) {
                if (boundDynamicOffsets[i] != dynamicOffsets[i]) {
                    same = false;
                    break;
                }
            }
        }
        if (same && bindPoint == VK_PIPELINE_BIND_POINT_GRAPHICS) return;
        MemoryUtil.memPutLong(setScratch, set);
        for (int i = 0; i < dynamicCount; i++) MemoryUtil.memPutInt(setScratch + 8 + i * 4L, dynamicOffsets[i]);
        nvkCmdBindDescriptorSets(cmd, bindPoint, prog.pipelineLayout, 0, 1, setScratch, dynamicCount, setScratch + 8);
        if (bindPoint == VK_PIPELINE_BIND_POINT_GRAPHICS) {
            boundSet = set;
            boundLayout = prog.pipelineLayout;
            boundDynamicCount = dynamicCount;
            System.arraycopy(dynamicOffsets, 0, boundDynamicOffsets, 0, dynamicCount);
        }
    }

    private static int targetSamples(Target t) {
        for (int i = 0; i < t.colorCount; i++) {
            if (t.color[i] != null) return t.color[i].samples;
        }
        return t.depth != null ? t.depth.samples : 1;
    }

    private static int viewTypeFor(Program.Binding b) {
        return switch (b.dim) {
            case 0 -> b.arrayed ? VK_IMAGE_VIEW_TYPE_1D_ARRAY : VK_IMAGE_VIEW_TYPE_1D;
            case 2 -> VK_IMAGE_VIEW_TYPE_3D;
            case 3 -> b.arrayed ? VK_IMAGE_VIEW_TYPE_CUBE_ARRAY : VK_IMAGE_VIEW_TYPE_CUBE;
            default -> b.arrayed ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D;
        };
    }

    @Override protected int dummyTarget(int viewType) {
        return Images.glTargetForViewType(viewType);
    }

    @Override protected void clearDummy(Tex t) {
        final VkCommandBuffer cmd = gl.stream.cmd();
        gl.images.clearImage(cmd, t, new float[] {0, 0, 0, 1}, 1f, 0);
        gl.images.transition(cmd, t, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private int prepareSampled(Tex tex, Target target) {
        if (tex.pendingClear || tex.needsClear) materialize(tex);
        // A sampled current attachment stays in GENERAL; anything else moves to shader-read, ahead of the pass when the barrier can be hoisted.
        final boolean feedback = target != null && target.contains(tex);
        if (feedback) {
            if (tex.layout != VK_IMAGE_LAYOUT_GENERAL) {
                endPass();
                gl.images.transition(gl.stream.cmd(), tex, VK_IMAGE_LAYOUT_GENERAL);
            }
            return VK_IMAGE_LAYOUT_GENERAL;
        }
        if (tex.layout == VK_IMAGE_LAYOUT_GENERAL && !tex.storageViews.isEmpty()) return VK_IMAGE_LAYOUT_GENERAL;
        if (tex.layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            if (deferPasses && passActive && gl.stream.inSecondary() && !passTarget.contains(tex)) {
                prePassBarriers.add(gl.images.transitionLater(tex, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                return VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }
            endPass();
            gl.images.transition(gl.stream.cmd(), tex, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        return VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    long sampledView(Tex tex, int viewType) {
        if (tex.viewDirty) {
            refreshViews(tex);
        }
        if (tex.view == 0L) tex.view = gl.images.createSampledView(tex, viewType);
        return tex.view;
    }

    private void refreshViews(Tex tex) {
        tex.viewDirty = false;
        final long old = tex.view;
        tex.view = 0L;
        if (old != 0L) {
            gl.stream.defer(() -> {
                vkDestroyImageView(gl.gpu.device, old, null);
                pools.epoch++;
            });
        }
    }

    public void invalidateDescriptorCache() {
        pools.epoch++;
    }

    long samplerFor(GlState st, int unit, Tex tex) {
        final SamplerObj so = gl.samplerObjects.get(st.samplers[unit]);
        if (so == null && tex != null && !tex.samplerDirty && tex.sampler != 0L) return tex.sampler;
        final boolean integer = tex != null && (tex.format.isInteger() || tex.format.isStencil() && tex.samplesStencil());
        final boolean linearOk = tex == null || !integer && gl.images.supports(VkFormats.of(tex.format.pf()), VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
        final int traits = (integer ? 1 : 0) | (linearOk ? 2 : 0) | (tex != null && tex.format.isDepth() ? 4 : 0);
        if (so != null && so.sampler(traits) != 0L) return so.sampler(traits);
        final SamplerSource src = SamplerSource.of(so, tex);
        final int min = src.min(), mag = src.mag(), ws = src.ws(), wt = src.wt(), wr = src.wr(), cmpMode = src.cmpMode(), cmpFunc = src.cmpFunc();
        final float minLod = src.minLod(), maxLod = src.maxLod(), bias = src.bias(), aniso = src.aniso();
        final float[] border = src.border();
        final int magF = linearOk && mag == GL11.GL_LINEAR ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        final int minF = linearOk && (min == GL11.GL_LINEAR || min == GL11.GL_LINEAR_MIPMAP_NEAREST || min == GL11.GL_LINEAR_MIPMAP_LINEAR) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        final boolean mip = min != GL11.GL_NEAREST && min != GL11.GL_LINEAR;
        final int mipMode = linearOk && (min == GL11.GL_NEAREST_MIPMAP_LINEAR || min == GL11.GL_LINEAR_MIPMAP_LINEAR) ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST;
        final boolean compare = cmpMode == GL14.GL_COMPARE_R_TO_TEXTURE && tex != null && tex.format.isDepth();
        final float maxAniso = gl.gpu.samplerAnisotropy ? Math.min(aniso, gl.gpu.maxSamplerAnisotropy) : 1f;
        final int borderColor = borderColor(border, integer);
        final long state = magF | minF << 1 | mipMode << 2 | (mip ? 1 : 0) << 3 | (long) wrap(ws) << 4 | (long) wrap(wt) << 7 | (long) wrap(wr) << 10
            | (long) (compare ? 1 : 0) << 13 | (long) compareOp(cmpFunc) << 14 | (long) borderColor << 17;
        final SamplerKey key = new SamplerKey(state, minLod, maxLod, bias, maxAniso);
        long sampler = samplers.getLong(key);
        if (sampler == 0L) {
            final boolean deviceBias = gl.gpu.samplerMipLodBias && !com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler.lodBiasInShader;
            if (bias != 0f && !deviceBias && !com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler.lodBiasInShader) gl.warnOnce("GL_TEXTURE_LOD_BIAS is not supported on this device");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(magF).minFilter(minF).mipmapMode(mipMode)
                    .addressModeU(wrap(ws)).addressModeV(wrap(wt)).addressModeW(wrap(wr))
                    .mipLodBias(deviceBias ? Math.max(-gl.gpu.maxSamplerLodBias, Math.min(gl.gpu.maxSamplerLodBias, bias)) : 0f)
                    .anisotropyEnable(maxAniso > 1f && mip)
                    .maxAnisotropy(Math.max(1f, maxAniso))
                    .compareEnable(compare)
                    .compareOp(compareOp(cmpFunc))
                    .minLod(mip ? Math.max(0f, minLod) : 0f)
                    .maxLod(mip ? Math.max(Math.max(0f, minLod), Math.min(maxLod, 1000f)) : 0.25f)
                    .borderColor(borderColor)
                    .unnormalizedCoordinates(false);
                final LongBuffer p = stack.mallocLong(1);
                VkResults.check(vkCreateSampler(gl.gpu.device, info, null, p), "vkCreateSampler");
                sampler = p.get(0);
                samplers.put(key, sampler);
            }
        }
        if (so != null) so.keepSampler(traits, sampler);
        if (so == null && tex != null) {
            tex.sampler = sampler;
            tex.samplerDirty = false;
        }
        return sampler;
    }

    private int wrap(int gl) {
        return switch (gl) {
            case GL12.GL_CLAMP_TO_EDGE, GL11.GL_CLAMP -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case GL13.GL_CLAMP_TO_BORDER -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
            case GL14.GL_MIRRORED_REPEAT -> VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            case GL44.GL_MIRROR_CLAMP_TO_EDGE -> this.gl.gpu.samplerMirrorClampToEdge ? VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            default -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
        };
    }

    private static int borderColor(float[] b, boolean integer) {
        final boolean opaque = b[3] >= 0.5f;
        final boolean white = b[0] >= 0.5f && b[1] >= 0.5f && b[2] >= 0.5f;
        if (integer) return opaque ? (white ? VK_BORDER_COLOR_INT_OPAQUE_WHITE : VK_BORDER_COLOR_INT_OPAQUE_BLACK) : VK_BORDER_COLOR_INT_TRANSPARENT_BLACK;
        return opaque ? (white ? VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE : VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK) : VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
    }

    private void bindVertexBuffers(VkCommandBuffer cmd, GlState st, Program prog, Vao vao) {
        final long serial = gl.stream.currentSerial();
        int n = 0;
        int mask = prog.vertexInputMask;
        boolean needConst = false;
        while (mask != 0) {
            final int location = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            final int loc = attribOf(prog, location);
            final Buf buf = readsArray(prog, vao, location) ? gl.buffers.get(vao.readBuffer(loc)) : null;
            if (buf != null && buf.alloc != null) {
                gl.buffers.use(buf, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT);
                MemoryUtil.memPutLong(vbHandles + n * 8L, buf.handle());
                constAttrib[n] = -1;
                MemoryUtil.memPutLong(vbOffsets + n * 8L, vao.readOffset(loc) + wordsOffset(location));
            } else {
                needConst = true;
                constAttrib[n] = location;
            }
            n++;
        }
        // Disabled attribute arrays read their constant glVertexAttrib value from a per-frame ring slot bound as a zero-stride buffer.
        if (needConst) {
            if (writeConstantAttribs(st, serial, gl.uniformRing)) {
                constRingBuffer = gl.uniformRing.buffer;
                constRingOffset = gl.uniformRing.offset;
            }
            for (int i = 0; i < n; i++) {
                final int location = constAttrib[i];
                if (location >= 0) {
                    MemoryUtil.memPutLong(vbHandles + i * 8L, constRingBuffer);
                    MemoryUtil.memPutLong(vbOffsets + i * 8L, constRingOffset + constantOffset(prog, vao, location));
                }
            }
        }
        if (n == 0) return;
        boolean same = boundVbCount == n;
        if (same) {
            for (int i = 0; i < n; i++) {
                if (boundVb[i] != MemoryUtil.memGetLong(vbHandles + i * 8L) || boundVbOff[i] != MemoryUtil.memGetLong(vbOffsets + i * 8L)) {
                    same = false;
                    break;
                }
            }
        }
        if (!same) {
            nvkCmdBindVertexBuffers(cmd, 0, n, vbHandles, vbOffsets);
            for (int i = 0; i < n; i++) {
                boundVb[i] = MemoryUtil.memGetLong(vbHandles + i * 8L);
                boundVbOff[i] = MemoryUtil.memGetLong(vbOffsets + i * 8L);
            }
            boundVbCount = n;
        }
    }

    void bindIndexBuffer(VkCommandBuffer cmd, long buffer, long offset, int vkIndexType) {
        if (buffer == boundIndexBuffer && offset == boundIndexOffset && vkIndexType == boundIndexType) return;
        vkCmdBindIndexBuffer(cmd, buffer, offset, vkIndexType);
        boundIndexBuffer = buffer;
        boundIndexOffset = offset;
        boundIndexType = vkIndexType;
    }

    public int shaderStages() {
        int stages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        if (gl.gpu.geometryShader) stages |= VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT;
        if (gl.gpu.tessellationShader) stages |= VK_PIPELINE_STAGE_TESSELLATION_CONTROL_SHADER_BIT | VK_PIPELINE_STAGE_TESSELLATION_EVALUATION_SHADER_BIT;
        return stages;
    }

    boolean prepareDispatch(GlState st) {
        gl.syncBeforeDraw(st);
        final Program prog = gl.programs.current(st);
        if (prog == null || !prog.linked || !prog.compute) return false;
        final long set = resolveDescriptors(st, prog, null, true);
        endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        if (pendingTransferBarrier || pendingComputeBarrier) {
            CommandStream.memoryBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | shaderStages(),
                VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT);
            pendingTransferBarrier = false;
            pendingComputeBarrier = false;
        }
        resetBoundIfNewCommandBuffer();
        final long pipeline = pipelines.compute(prog);
        if (pipeline == 0L) return false;
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        bindDescriptors(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, prog, set);
        return true;
    }

    static int compareOp(int f) {
        return switch (f) {
            case GL11.GL_NEVER -> VK_COMPARE_OP_NEVER;
            case GL11.GL_LESS -> VK_COMPARE_OP_LESS;
            case GL11.GL_EQUAL -> VK_COMPARE_OP_EQUAL;
            case GL11.GL_LEQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case GL11.GL_GREATER -> VK_COMPARE_OP_GREATER;
            case GL11.GL_NOTEQUAL -> VK_COMPARE_OP_NOT_EQUAL;
            case GL11.GL_GEQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            default -> VK_COMPARE_OP_ALWAYS;
        };
    }

    static int stencilOp(int op) {
        return switch (op) {
            case GL11.GL_ZERO -> VK_STENCIL_OP_ZERO;
            case GL11.GL_REPLACE -> VK_STENCIL_OP_REPLACE;
            case GL11.GL_INCR -> VK_STENCIL_OP_INCREMENT_AND_CLAMP;
            case GL11.GL_DECR -> VK_STENCIL_OP_DECREMENT_AND_CLAMP;
            case GL11.GL_INVERT -> VK_STENCIL_OP_INVERT;
            case GL14.GL_INCR_WRAP -> VK_STENCIL_OP_INCREMENT_AND_WRAP;
            case GL14.GL_DECR_WRAP -> VK_STENCIL_OP_DECREMENT_AND_WRAP;
            default -> VK_STENCIL_OP_KEEP;
        };
    }

    private int supportedBlendFactor(int f) {
        if (gl.gpu.dualSrcBlend) return blendFactor(f);
        final int firstSource = switch (f) {
            case GL33.GL_SRC1_COLOR -> GL11.GL_SRC_COLOR;
            case GL33.GL_ONE_MINUS_SRC1_COLOR -> GL11.GL_ONE_MINUS_SRC_COLOR;
            case GL15.GL_SRC1_ALPHA -> GL11.GL_SRC_ALPHA;
            case GL33.GL_ONE_MINUS_SRC1_ALPHA -> GL11.GL_ONE_MINUS_SRC_ALPHA;
            default -> f;
        };
        if (firstSource != f) gl.warnOnce("this device has no dual-source blending; blending with the first output");
        return blendFactor(firstSource);
    }

    static int blendFactor(int f) {
        return switch (f) {
            case GL11.GL_ZERO -> VK_BLEND_FACTOR_ZERO;
            case GL11.GL_ONE -> VK_BLEND_FACTOR_ONE;
            case GL11.GL_SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case GL11.GL_ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case GL11.GL_DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case GL11.GL_ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case GL11.GL_SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case GL11.GL_ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case GL11.GL_DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case GL11.GL_ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case GL14.GL_CONSTANT_COLOR -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case GL14.GL_ONE_MINUS_CONSTANT_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case GL14.GL_CONSTANT_ALPHA -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case GL14.GL_ONE_MINUS_CONSTANT_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case GL11.GL_SRC_ALPHA_SATURATE -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case GL33.GL_SRC1_COLOR -> VK_BLEND_FACTOR_SRC1_COLOR;
            case GL33.GL_ONE_MINUS_SRC1_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC1_COLOR;
            case GL15.GL_SRC1_ALPHA -> VK_BLEND_FACTOR_SRC1_ALPHA;
            case GL33.GL_ONE_MINUS_SRC1_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC1_ALPHA;
            default -> VK_BLEND_FACTOR_ONE;
        };
    }

    static int blendOp(int eq) {
        return switch (eq) {
            case GL14.GL_FUNC_SUBTRACT -> VK_BLEND_OP_SUBTRACT;
            case GL14.GL_FUNC_REVERSE_SUBTRACT -> VK_BLEND_OP_REVERSE_SUBTRACT;
            case GL14.GL_MIN -> VK_BLEND_OP_MIN;
            case GL14.GL_MAX -> VK_BLEND_OP_MAX;
            default -> VK_BLEND_OP_ADD;
        };
    }

    static int logicOp(int op) {
        return switch (op) {
            case GL11.GL_CLEAR -> VK_LOGIC_OP_CLEAR;
            case GL11.GL_AND -> VK_LOGIC_OP_AND;
            case GL11.GL_AND_REVERSE -> VK_LOGIC_OP_AND_REVERSE;
            case GL11.GL_AND_INVERTED -> VK_LOGIC_OP_AND_INVERTED;
            case GL11.GL_NOOP -> VK_LOGIC_OP_NO_OP;
            case GL11.GL_XOR -> VK_LOGIC_OP_XOR;
            case GL11.GL_OR -> VK_LOGIC_OP_OR;
            case GL11.GL_NOR -> VK_LOGIC_OP_NOR;
            case GL11.GL_EQUIV -> VK_LOGIC_OP_EQUIVALENT;
            case GL11.GL_INVERT -> VK_LOGIC_OP_INVERT;
            case GL11.GL_OR_REVERSE -> VK_LOGIC_OP_OR_REVERSE;
            case GL11.GL_COPY_INVERTED -> VK_LOGIC_OP_COPY_INVERTED;
            case GL11.GL_OR_INVERTED -> VK_LOGIC_OP_OR_INVERTED;
            case GL11.GL_NAND -> VK_LOGIC_OP_NAND;
            case GL11.GL_SET -> VK_LOGIC_OP_SET;
            default -> VK_LOGIC_OP_COPY;
        };
    }

    static int polygonMode(int mode) {
        return switch (mode) {
            case GL11.GL_LINE -> VK_POLYGON_MODE_LINE;
            case GL11.GL_POINT -> VK_POLYGON_MODE_POINT;
            default -> VK_POLYGON_MODE_FILL;
        };
    }

    static int topology(int mode) {
        return switch (mode) {
            case GL11.GL_POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case GL11.GL_LINES -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case GL11.GL_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case GL11.GL_TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case GL11.GL_TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            case GL32.GL_LINES_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST_WITH_ADJACENCY;
            case GL32.GL_LINE_STRIP_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY;
            case GL32.GL_TRIANGLES_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST_WITH_ADJACENCY;
            case GL32.GL_TRIANGLE_STRIP_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY;
            case GL40.GL_PATCHES -> VK_PRIMITIVE_TOPOLOGY_PATCH_LIST;
            default -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        };
    }

    @Override public void destroy() {
        endPass();
        passColors.free();
        passDepth.free();
        passStencil.free();
        passInfo.free();
        MemoryUtil.memFree(inheritFormats);
        inheritRendering.free();
        inherit.free();
        releaseDummyTextures();
        releaseSharedImages();
        if (dummyBuffer != null && dummyBuffer.alloc != null) {
            dummyBuffer.alloc.destroy(gl.gpu, gl.allocator);
            dummyBuffer.alloc = null;
        }
        for (long s : samplers.values()) vkDestroySampler(gl.gpu.device, s, null);
        samplers.clear();
        pipelines.destroy();
        pools.destroy();
        MemoryUtil.nmemFree(vbHandles);
        MemoryUtil.nmemFree(vbOffsets);
        MemoryUtil.nmemFree(setScratch);
        MemoryUtil.nmemFree(blendScratch);
        viewportScratch.free();
        scissorScratch.free();
    }

    @Override protected DefaultFramebuffer defaultFramebuffer() { return gl.defaultFb; }
    @Override protected Tex newTex(int id) { return new Tex(id, false); }
    @Override protected boolean sameFormat(int pfA, int pfB) { return VkFormats.of(pfA) == VkFormats.of(pfB); }
    @Override protected void clearImage(Tex t, float[] color, float depth, int stencil, int aspects) { gl.images.clearImage(gl.stream.cmd(), t, color, depth, stencil, aspects); }
    @Override protected void onViewsForgotten() { pools.epoch++; }
}
