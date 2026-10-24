package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import com.gtnewhorizons.angelica.vulkan.resource.Buf;
import com.gtnewhorizons.angelica.vulkan.resource.Formats;
import com.gtnewhorizons.angelica.vulkan.resource.Tex;
import com.gtnewhorizons.angelica.vulkan.shader.Program;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.*;

final class Renderer {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private final VulkanGL gl;
    final Pipelines pipelines;
    final DescriptorPools pools;
    private final Pipelines.Desc desc = new Pipelines.Desc();

    // render pass
    boolean passActive;
    private final Target passTarget = new Target();
    final Target drawTarget = new Target();
    private final Target scratchTarget = new Target();
    int targetGen = 1;

    private long boundSerial = -1;
    private long boundPipeline;
    private long boundSet;
    private long boundLayout;
    private final int[] boundDynamicOffsets = new int[32];
    private int boundDynamicCount = -1;
    private int dynamicStamp = -1;
    private long boundIndexBuffer = -1;
    private long boundIndexOffset = -1;
    private int boundIndexType = -1;
    private final long vbHandles = MemoryUtil.nmemAlloc(16 * 8);
    private final long vbOffsets = MemoryUtil.nmemAlloc(16 * 8);
    private final long[] boundVb = new long[16];
    private final long[] boundVbOff = new long[16];
    private int boundVbCount = -1;

    // pipeline fast path
    private int lastProgram = -1, lastProgramGen = -1, lastPipelineGen = -1, lastVaoFormatGen = -1, lastVaoId = -1, lastMode = -1, lastTargetGen = -1, lastFboId = Integer.MIN_VALUE;
    private Object lastState;
    private long lastPipeline;

    boolean pendingTransferBarrier;
    boolean pendingComputeBarrier;

    // samplers
    private final Long2LongOpenHashMap samplers = new Long2LongOpenHashMap();
    // extra sampled views by texture id: [viewType] -> view
    private final Int2ObjectOpenHashMap<long[]> extraViews = new Int2ObjectOpenHashMap<>();

    Tex dummy2D, dummy2DArray, dummy3D, dummyCube, dummyDepth;
    private Buf dummyBuffer;

    // stats
    long draws, passes, setWrites;

    private final long[] keyScratch = new long[256];

    Renderer(VulkanGL gl, java.io.File pipelineCacheFile) {
        this.gl = gl;
        this.pipelines = new Pipelines(gl, pipelineCacheFile);
        this.pools = new DescriptorPools(gl);
    }

    void init() {
        dummy2D = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, Formats.RGBA8, 1, 1, 1, 1, "dummy2D");
        dummy2DArray = gl.textures.internalTexture(GL30.GL_TEXTURE_2D_ARRAY, Formats.RGBA8, 1, 1, 1, 1, "dummy2DArray");
        dummy3D = gl.textures.internalTexture(GL12.GL_TEXTURE_3D, Formats.RGBA8, 1, 1, 1, 1, "dummy3D");
        dummyCube = gl.textures.internalTexture(GL13.GL_TEXTURE_CUBE_MAP, Formats.RGBA8, 1, 1, 1, 6, "dummyCube");
        dummyDepth = gl.textures.internalTexture(GL11.GL_TEXTURE_2D, gl.textures.format(GL14.GL_DEPTH_COMPONENT24), 1, 1, 1, 1, "dummyDepth");
        final float[] black = {0, 0, 0, 1};
        final VkCommandBuffer cmd = gl.stream.cmd();
        for (Tex t : new Tex[] {dummy2D, dummy2DArray, dummy3D, dummyCube}) {
            gl.images.clearImage(cmd, t, black, 1f, 0);
            gl.images.transition(cmd, t, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        gl.images.clearImage(cmd, dummyDepth, black, 1f, 0);
        gl.images.transition(cmd, dummyDepth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        dummyBuffer = gl.buffers.internalBuffer(65536, "dummyBuffer");
    }

    Target resolveDrawTarget(GlState st) {
        final Fbo fbo = gl.framebuffers.get(st.drawFbo);
        resolve(drawTarget, fbo, st, true);
        return drawTarget;
    }

    void resolve(Target t, Fbo fbo, GlState st, boolean draw) {
        final int fboId = fbo == null ? 0 : fbo.id;
        final int fboGen = fbo == null ? 0 : fbo.gen;
        if (t.fboId == fboId && t.fboGen == fboGen && t.globalGen == targetGen && (fboId != 0 || !draw || !st.defaultDrawNone)) {
            return;
        }
        t.clear();
        t.fboId = fboId;
        t.fboGen = fboGen;
        t.globalGen = targetGen;
        int w = Integer.MAX_VALUE, h = Integer.MAX_VALUE;
        if (fbo == null || fbo.id == 0) {
            final DefaultFramebuffer d = gl.defaultFb;
            if (!(draw && st.defaultDrawNone)) {
                t.color[0] = d.color;
                t.colorLayers[0] = 1;
                t.colorCount = 1;
            } else {
                t.colorCount = 0;
            }
            t.depth = d.depth;
            t.hasDepth = true;
            t.hasStencil = d.depth.format.isStencil();
            t.width = d.width;
            t.height = d.height;
            return;
        }
        int count = 0;
        for (int i = 0; i < fbo.drawBuffers.length; i++) {
            final int a = fbo.drawBuffers[i];
            if (a < 0 || fbo.colorTex[a] == 0) continue;
            final Tex tex = gl.textures.attachment(fbo.colorTex[a], fbo.colorRbo[a]);
            if (tex == null || !tex.hasImage()) continue;
            t.color[i] = tex;
            t.colorLevel[i] = fbo.colorLevel[a];
            t.colorLayer[i] = fbo.colorLayered[a] ? 0 : fbo.colorLayer[a];
            t.colorLayers[i] = fbo.colorLayered[a] ? tex.layers : 1;
            count = i + 1;
            w = Math.min(w, tex.levelWidth(t.colorLevel[i]));
            h = Math.min(h, tex.levelHeight(t.colorLevel[i]));
        }
        t.colorCount = count;
        final int depthId = fbo.depthTex != 0 ? fbo.depthTex : fbo.stencilTex;
        if (depthId != 0) {
            final boolean rbo = fbo.depthTex != 0 ? fbo.depthRbo : fbo.stencilRbo;
            final Tex tex = gl.textures.attachment(depthId, rbo);
            if (tex != null && tex.hasImage() && tex.format.isDepthOrStencil()) {
                t.depth = tex;
                t.depthLevel = fbo.depthTex != 0 ? fbo.depthLevel : fbo.stencilLevel;
                t.depthLayer = fbo.depthTex != 0 ? fbo.depthLayer : fbo.stencilLayer;
                t.depthLayers = fbo.depthLayered ? tex.layers : 1;
                t.hasDepth = tex.format.isDepth();
                t.hasStencil = tex.format.isStencil();
                w = Math.min(w, tex.levelWidth(t.depthLevel));
                h = Math.min(h, tex.levelHeight(t.depthLevel));
            }
        }
        t.width = w == Integer.MAX_VALUE ? 0 : w;
        t.height = h == Integer.MAX_VALUE ? 0 : h;
    }

    Target readTarget(GlState st) {
        final Target t = scratchTarget;
        t.clear();
        t.fboId = Integer.MIN_VALUE;
        final Fbo fbo = gl.framebuffers.get(st.readFbo);
        if (fbo == null || fbo.id == 0) {
            final DefaultFramebuffer d = gl.defaultFb;
            if (!st.defaultReadNone) {
                t.color[0] = d.color;
                t.colorLayers[0] = 1;
                t.colorCount = 1;
            }
            t.depth = d.depth;
            t.hasDepth = true;
            t.hasStencil = d.depth.format.isStencil();
            t.width = d.width;
            t.height = d.height;
            return t;
        }
        final int idx = fbo.readBuffer;
        if (idx >= 0 && idx < Fbo.MAX_COLOR && fbo.colorTex[idx] != 0) {
            final Tex tex = gl.textures.attachment(fbo.colorTex[idx], fbo.colorRbo[idx]);
            if (tex != null && tex.hasImage()) {
                t.color[0] = tex;
                t.colorLevel[0] = fbo.colorLevel[idx];
                t.colorLayer[0] = fbo.colorLayered[idx] ? 0 : fbo.colorLayer[idx];
                t.colorLayers[0] = 1;
                t.colorCount = 1;
                t.width = tex.levelWidth(t.colorLevel[0]);
                t.height = tex.levelHeight(t.colorLevel[0]);
            }
        }
        final int depthId = fbo.depthTex != 0 ? fbo.depthTex : fbo.stencilTex;
        if (depthId != 0) {
            final Tex tex = gl.textures.attachment(depthId, fbo.depthTex != 0 ? fbo.depthRbo : fbo.stencilRbo);
            if (tex != null && tex.hasImage() && tex.format.isDepthOrStencil()) {
                t.depth = tex;
                t.depthLevel = fbo.depthTex != 0 ? fbo.depthLevel : fbo.stencilLevel;
                t.depthLayer = fbo.depthTex != 0 ? fbo.depthLayer : fbo.stencilLayer;
                t.hasDepth = tex.format.isDepth();
                t.hasStencil = tex.format.isStencil();
                if (t.width == 0) {
                    t.width = tex.levelWidth(t.depthLevel);
                    t.height = tex.levelHeight(t.depthLevel);
                }
            }
        }
        return t;
    }

    void onBufferRenamed() {
        // vertex/index/descriptor bindings compare handles, so a new storage handle rebinds on its own
    }


    void endPass() {
        if (!passActive) return;
        final VkCommandBuffer cmd = gl.stream.cmd();
        if (gl.gpu.dynamicRenderingCore) vkCmdEndRendering(cmd);
        else KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        passActive = false;
    }

    private boolean sameAsPass(Target t) {
        return passActive && passTarget.fboId == t.fboId && passTarget.fboGen == t.fboGen && passTarget.globalGen == t.globalGen
            && passTarget.colorCount == t.colorCount && passTarget.width == t.width && passTarget.height == t.height
            && sameAttachments(t);
    }

    private boolean sameAttachments(Target t) {
        for (int i = 0; i < t.colorCount; i++) if (passTarget.color[i] != t.color[i]) return false;
        return passTarget.depth == t.depth;
    }

    void ensurePass(Target t) {
        if (sameAsPass(t)) return;
        endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        final long serial = gl.stream.currentSerial();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkRenderingAttachmentInfo.Buffer colors = VkRenderingAttachmentInfo.calloc(Math.max(1, t.colorCount), stack);
            for (int i = 0; i < t.colorCount; i++) {
                final VkRenderingAttachmentInfo a = colors.get(i).sType$Default();
                final Tex tex = t.color[i];
                if (tex == null) {
                    a.imageView(VK_NULL_HANDLE);
                    continue;
                }
                final int layout = tex.layout == VK_IMAGE_LAYOUT_GENERAL ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                final boolean undefined = tex.layout == VK_IMAGE_LAYOUT_UNDEFINED;
                int loadOp = undefined ? VK_ATTACHMENT_LOAD_OP_DONT_CARE : VK_ATTACHMENT_LOAD_OP_LOAD;
                if (tex.pendingClear && (tex.clearAspects & VK_IMAGE_ASPECT_COLOR_BIT) != 0) {
                    loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
                    setClearColor(a, tex, tex.clearColor);
                    tex.pendingClear = false;
                    tex.needsClear = false;
                } else if (tex.needsClear) {
                    loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
                    tex.needsClear = false;
                }
                gl.images.transition(cmd, tex, layout);
                t.colorView[i] = gl.images.attachmentView(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i]);
                a.imageView(t.colorView[i]).imageLayout(layout).loadOp(loadOp).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                tex.lastUse = serial;
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
                    depthInfo = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                        .imageView(t.depthView).imageLayout(layout).loadOp(depthLoad).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                    depthInfo.clearValue().depthStencil().set(clearDepth, clearStencil);
                }
                if (t.hasStencil) {
                    stencilInfo = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                        .imageView(t.depthView).imageLayout(layout).loadOp(stencilLoad).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                    stencilInfo.clearValue().depthStencil().set(clearDepth, clearStencil);
                }
                tex.lastUse = serial;
            }
            final int layers = layerCount(t);
            final VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default()
                .layerCount(layers)
                .pColorAttachments(t.colorCount == 0 ? null : colors)
                .pDepthAttachment(depthInfo)
                .pStencilAttachment(stencilInfo);
            info.renderArea().offset().set(0, 0);
            info.renderArea().extent().set(Math.max(1, t.width), Math.max(1, t.height));
            if (gl.gpu.dynamicRenderingCore) vkCmdBeginRendering(cmd, info);
            else KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
        }
        copyTarget(t, passTarget);
        passActive = true;
        passes++;
        dynamicStamp = -1;
    }

    private static int layerCount(Target t) {
        int n = 1;
        for (int i = 0; i < t.colorCount; i++) if (t.color[i] != null) n = Math.max(n, t.colorLayers[i]);
        if (t.depth != null) n = Math.max(n, t.depthLayers);
        return n;
    }

    private static void setClearColor(VkRenderingAttachmentInfo a, Tex tex, float[] c) {
        if (tex.format.kind() == Formats.K_UINT || tex.format.kind() == Formats.K_SINT) {
            for (int i = 0; i < 4; i++) a.clearValue().color().int32(i, (int) c[i]);
        } else {
            for (int i = 0; i < 4; i++) a.clearValue().color().float32(i, c[i]);
        }
    }

    private static void copyTarget(Target from, Target to) {
        to.clear();
        to.fboId = from.fboId;
        to.fboGen = from.fboGen;
        to.globalGen = from.globalGen;
        to.colorCount = from.colorCount;
        System.arraycopy(from.color, 0, to.color, 0, from.color.length);
        System.arraycopy(from.colorLevel, 0, to.colorLevel, 0, from.colorLevel.length);
        System.arraycopy(from.colorLayer, 0, to.colorLayer, 0, from.colorLayer.length);
        System.arraycopy(from.colorLayers, 0, to.colorLayers, 0, from.colorLayers.length);
        System.arraycopy(from.colorView, 0, to.colorView, 0, from.colorView.length);
        to.depth = from.depth;
        to.depthLevel = from.depthLevel;
        to.depthLayer = from.depthLayer;
        to.depthLayers = from.depthLayers;
        to.depthView = from.depthView;
        to.hasDepth = from.hasDepth;
        to.hasStencil = from.hasStencil;
        to.width = from.width;
        to.height = from.height;
    }

    void releaseFromPass(Tex t) {
        if (passActive && passTarget.contains(t)) endPass();
    }

    void onImageReleased(Tex t) {
        if (passActive && passTarget.contains(t)) endPass();
        targetGen++;
    }

    void materialize(Tex t) {
        if (!t.hasImage()) return;
        if (!t.pendingClear && !t.needsClear) return;
        releaseFromPass(t);
        endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        if (t.pendingClear) {
            gl.images.clearImage(cmd, t, t.clearColor, t.clearDepth, t.clearStencil);
            t.pendingClear = false;
        } else {
            gl.images.clearImage(cmd, t, ZERO, 1f, 0);
        }
        t.lastUse = gl.stream.currentSerial();
    }

    private static final float[] ZERO = new float[4];


    void clear(GlState st, int mask) {
        final Target t = resolveDrawTarget(st);
        if (t.width <= 0 || t.height <= 0) return;
        final boolean scissored = st.scissorTest && (st.scX > 0 || st.scY > 0 || st.scX + st.scW < t.width || st.scY + st.scH < t.height);
        if (st.scissorTest && (st.scW <= 0 || st.scH <= 0)) return;
        final boolean wantColor = (mask & GL11.GL_COLOR_BUFFER_BIT) != 0;
        final boolean wantDepth = (mask & GL11.GL_DEPTH_BUFFER_BIT) != 0 && st.depthMask && t.hasDepth;
        final boolean wantStencil = (mask & GL11.GL_STENCIL_BUFFER_BIT) != 0 && t.hasStencil && (st.stencilWriteMask[0] & 0xFF) != 0;
        final boolean stencilPartial = wantStencil && (st.stencilWriteMask[0] & 0xFF) != 0xFF;

        final boolean inPass = sameAsPass(t);
        boolean anyImmediate = false;
        if (wantColor) {
            for (int i = 0; i < t.colorCount; i++) {
                final Tex tex = t.color[i];
                if (tex == null) continue;
                final boolean[] cm = st.colorMask[Math.min(i, GlState.MAX_DRAW_BUFFERS - 1)];
                final boolean full = cm[0] && cm[1] && cm[2] && cm[3];
                final boolean none = !cm[0] && !cm[1] && !cm[2] && !cm[3];
                if (none) continue;
                if (!inPass && !scissored && full && canDefer(tex, t.colorLevel[i], t.colorLayer[i], t.colorLayers[i])) {
                    tex.pendingClear = true;
                    tex.clearAspects = VK_IMAGE_ASPECT_COLOR_BIT;
                    System.arraycopy(st.clearColor, 0, tex.clearColor, 0, 4);
                } else {
                    anyImmediate = true;
                }
            }
        }
        boolean depthImmediate = false;
        if ((wantDepth || wantStencil) && t.depth != null) {
            final Tex tex = t.depth;
            final boolean dsFull = (!t.hasDepth || wantDepth || !t.hasStencil) && (!t.hasStencil || wantStencil || !t.hasDepth);
            if (!inPass && !scissored && !stencilPartial && canDefer(tex, t.depthLevel, t.depthLayer, t.depthLayers)) {
                if (!tex.pendingClear) tex.clearAspects = 0;
                tex.pendingClear = true;
                if (wantDepth) {
                    tex.clearAspects |= VK_IMAGE_ASPECT_DEPTH_BIT;
                    tex.clearDepth = (float) st.clearDepth;
                }
                if (wantStencil) {
                    tex.clearAspects |= VK_IMAGE_ASPECT_STENCIL_BIT;
                    tex.clearStencil = st.clearStencil & 0xFF;
                }
                if (!dsFull && tex.layout == VK_IMAGE_LAYOUT_UNDEFINED && !tex.needsClear) {
                    // the other aspect must survive; an undefined layout would discard it
                    depthImmediate = true;
                    tex.pendingClear = false;
                }
            } else {
                depthImmediate = true;
            }
        }
        if (!anyImmediate && !depthImmediate) return;

        prepareAttachmentsForPass(t);
        ensurePass(t);
        final VkCommandBuffer cmd = gl.stream.cmd();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkClearAttachment.Buffer atts = VkClearAttachment.calloc(t.colorCount + 1, stack);
            int n = 0;
            if (anyImmediate) {
                for (int i = 0; i < t.colorCount; i++) {
                    final Tex tex = t.color[i];
                    if (tex == null) continue;
                    final boolean[] cm = st.colorMask[Math.min(i, GlState.MAX_DRAW_BUFFERS - 1)];
                    if (!cm[0] && !cm[1] && !cm[2] && !cm[3]) continue;
                    if (tex.pendingClear) continue;
                    if (!(cm[0] && cm[1] && cm[2] && cm[3])) {
                        gl.clearPass.maskedColorClear(st, t, i, cm);
                        continue;
                    }
                    final VkClearAttachment a = atts.get(n++).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(i);
                    if (tex.format.kind() == Formats.K_UINT || tex.format.kind() == Formats.K_SINT) {
                        for (int c = 0; c < 4; c++) a.clearValue().color().int32(c, (int) st.clearColor[c]);
                    } else {
                        for (int c = 0; c < 4; c++) a.clearValue().color().float32(c, st.clearColor[c]);
                    }
                }
            }
            if (depthImmediate && t.depth != null) {
                int aspects = 0;
                if (wantDepth) aspects |= VK_IMAGE_ASPECT_DEPTH_BIT;
                if (wantStencil && !stencilPartial) aspects |= VK_IMAGE_ASPECT_STENCIL_BIT;
                if (aspects != 0) {
                    atts.get(n++).aspectMask(aspects).clearValue().depthStencil().set((float) st.clearDepth, st.clearStencil & 0xFF);
                }
                if (stencilPartial) gl.clearPass.maskedStencilClear(st, t);
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
                vkCmdClearAttachments(cmd, atts, rect);
            }
        }
    }

    private static boolean canDefer(Tex tex, int level, int layer, int layers) {
        return level == 0 && layer == 0 && tex.levels == 1 && tex.layers == layers;
    }

    private void prepareAttachmentsForPass(Target t) {
        // ensurePass consumes pending clears and transitions; nothing else to do
    }


    private void resetBoundIfNewCommandBuffer() {
        final long serial = gl.stream.currentSerial();
        if (boundSerial != serial || !gl.stream.hasRecording()) {
            boundSerial = serial;
            boundPipeline = 0L;
            boundSet = 0L;
            boundLayout = 0L;
            boundDynamicCount = -1;
            dynamicStamp = -1;
            boundIndexBuffer = -1;
            boundIndexOffset = -1;
            boundIndexType = -1;
            boundVbCount = -1;
        }
    }

    boolean prepareDraw(GlState st, int glMode, int vkTopology) {
        final Program prog = gl.programs.current(st);
        if (prog == null || !prog.linked || prog.compute) {
            gl.warnOnce("draw without a linked graphics program (program " + st.program + ")");
            return false;
        }
        final Target t = resolveDrawTarget(st);
        if (t.width <= 0 || t.height <= 0) return false;
        if (st.vpW <= 0 || st.vpH <= 0) return false;
        if (st.scissorTest && (st.scW <= 0 || st.scH <= 0)) return false;

        // resources first: transitions and barriers must happen outside rendering
        final long set = resolveDescriptors(st, prog, t, false);
        if (set == -1L) return false;
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
            gl.images.globalBarrier(cmd, src, srcAccess,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | shaderStages() | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            pendingTransferBarrier = false;
            pendingComputeBarrier = false;
        }
        ensurePass(t);
        resetBoundIfNewCommandBuffer();
        final VkCommandBuffer cmd = gl.stream.cmd();

        // pipeline
        final Vao vao = st.vao;
        long pipeline;
        if (prog.id == lastProgram && prog.gen == lastProgramGen && st.pipelineGen == lastPipelineGen && vao.formatGen == lastVaoFormatGen && vao.id == lastVaoId
            && glMode == lastMode && t.globalGen == lastTargetGen && t.fboId == lastFboId && st == lastState && lastPipeline != 0L) {
            pipeline = lastPipeline;
        } else {
            fillDesc(st, prog, vao, t, vkTopology);
            desc.encode(pipelines.probe);
            pipeline = pipelines.get(desc);
            if (pipeline == 0L) return false;
            lastProgram = prog.id;
            lastProgramGen = prog.gen;
            lastPipelineGen = st.pipelineGen;
            lastVaoFormatGen = vao.formatGen;
            lastVaoId = vao.id;
            lastMode = glMode;
            lastTargetGen = t.globalGen;
            lastFboId = t.fboId;
            lastState = st;
            lastPipeline = pipeline;
        }
        if (pipeline != boundPipeline) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            boundPipeline = pipeline;
        }
        applyDynamicState(cmd, st, t);
        bindDescriptors(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, prog, set);
        if (!bindVertexBuffers(cmd, st, prog, vao)) return false;
        draws++;
        return true;
    }

    private void fillDesc(GlState st, Program prog, Vao vao, Target t, int topology) {
        final Pipelines.Desc d = desc;
        d.program = prog;
        d.topology = topology;
        d.primitiveRestart = (st.primitiveRestart || st.primitiveRestartFixed) && isStrip(topology) ? 1 : 0;
        d.patchPoints = topology == VK_PRIMITIVE_TOPOLOGY_PATCH_LIST ? Math.max(1, gl.patchVertices) : 0;
        d.polygonMode = gl.gpu.fillModeNonSolid ? polygonMode(st.polygonModeFront) : VK_POLYGON_MODE_FILL;
        d.cullMode = !st.cull ? VK_CULL_MODE_NONE : switch (st.cullFace) {
            case GL11.GL_FRONT -> VK_CULL_MODE_FRONT_BIT;
            case GL11.GL_FRONT_AND_BACK -> VK_CULL_MODE_FRONT_AND_BACK;
            default -> VK_CULL_MODE_BACK_BIT;
        };
        // framebuffer rows keep GL's bottom-up order, which flips the winding Vulkan sees
        d.frontFace = st.frontFace == GL11.GL_CCW ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE;
        final boolean offset = switch (d.polygonMode) {
            case VK_POLYGON_MODE_LINE -> st.polyOffsetLine;
            case VK_POLYGON_MODE_POINT -> st.polyOffsetPoint;
            default -> st.polyOffsetFill;
        };
        d.depthBias = offset ? 1 : 0;
        d.depthClamp = st.depthClamp && gl.gpu.depthClamp ? 1 : 0;
        d.discard = st.rasterizerDiscard ? 1 : 0;
        d.provokingLast = st.provokingFirst ? 0 : 1;
        d.alphaToCoverage = 0;
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
            d.colorFormat[i] = tex == null ? VK_FORMAT_UNDEFINED : tex.format.vk();
            final int b = i * 8;
            final int bi = Math.min(i, GlState.MAX_DRAW_BUFFERS - 1);
            final boolean integer = tex != null && tex.format.isInteger();
            d.blend[b] = st.blend[bi] && !integer && tex != null ? 1 : 0;
            if (d.blend[b] != 0) {
                d.blend[b + 1] = blendFactor(st.blendSrcRgb[bi]);
                d.blend[b + 2] = blendFactor(st.blendDstRgb[bi]);
                d.blend[b + 3] = blendOp(st.blendEqRgb[bi]);
                d.blend[b + 4] = blendFactor(st.blendSrcA[bi]);
                d.blend[b + 5] = blendFactor(st.blendDstA[bi]);
                d.blend[b + 6] = blendOp(st.blendEqA[bi]);
            } else {
                d.blend[b + 1] = d.blend[b + 2] = d.blend[b + 3] = d.blend[b + 4] = d.blend[b + 5] = d.blend[b + 6] = 0;
            }
            final boolean[] cm = st.colorMask[bi];
            int wm = (cm[0] ? VK_COLOR_COMPONENT_R_BIT : 0) | (cm[1] ? VK_COLOR_COMPONENT_G_BIT : 0) | (cm[2] ? VK_COLOR_COMPONENT_B_BIT : 0) | (cm[3] ? VK_COLOR_COMPONENT_A_BIT : 0);
            if (i >= prog.colorOutputs || tex == null) wm = 0;
            d.blend[b + 7] = wm;
        }
        d.depthFormat = t.depth != null && t.hasDepth ? t.depth.format.vk() : VK_FORMAT_UNDEFINED;
        d.stencilFormat = t.depth != null && t.hasStencil ? t.depth.format.vk() : VK_FORMAT_UNDEFINED;

        int n = 0;
        int mask = prog.vertexInputMask;
        while (mask != 0) {
            final int loc = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            d.vLocation[n] = loc;
            if (vao.enabled[loc] && vao.bindingBuffer[vao.bindingOf[loc]] != 0) {
                int fmt = VertexFormats.vkFormat(vao.size[loc], vao.type[loc], vao.normalized[loc], vao.integer[loc]);
                fmt = gl.vertexFormatFallback(fmt);
                d.vFormat[n] = fmt;
                d.vStride[n] = vao.stride(loc);
                d.vRate[n] = vao.bindingDivisor[vao.bindingOf[loc]] > 0 ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX;
            } else {
                d.vFormat[n] = switch (prog.inputKind[loc]) {
                    case 1 -> VK_FORMAT_R32G32B32A32_SINT;
                    case 2 -> VK_FORMAT_R32G32B32A32_UINT;
                    default -> VK_FORMAT_R32G32B32A32_SFLOAT;
                };
                d.vStride[n] = 0;
                d.vRate[n] = VK_VERTEX_INPUT_RATE_VERTEX;
            }
            n++;
        }
        d.vertexCount = n;
    }

    private static boolean isStrip(int topology) {
        return topology == VK_PRIMITIVE_TOPOLOGY_LINE_STRIP || topology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP || topology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN
            || topology == VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY || topology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY;
    }

    private void applyDynamicState(VkCommandBuffer cmd, GlState st, Target t) {
        final int stamp = st.dynamicGen * 31 + t.globalGen * 17 + t.fboId + (st == lastState ? 0 : 7);
        if (stamp == dynamicStamp) return;
        dynamicStamp = stamp;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkViewport.Buffer vp = VkViewport.calloc(1, stack);
            vp.x(st.vpX).y(st.vpY).width(st.vpW).height(st.vpH).minDepth((float) Math.max(0, Math.min(1, st.depthNear))).maxDepth((float) Math.max(0, Math.min(1, st.depthFar)));
            vkCmdSetViewport(cmd, 0, vp);
            final VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
            if (st.scissorTest) {
                final int x0 = Math.max(0, st.scX), y0 = Math.max(0, st.scY);
                final int x1 = Math.max(x0, Math.min(t.width, st.scX + st.scW)), y1 = Math.max(y0, Math.min(t.height, st.scY + st.scH));
                sc.offset().set(x0, y0);
                sc.extent().set(x1 - x0, y1 - y0);
            } else {
                sc.offset().set(0, 0);
                sc.extent().set(t.width, t.height);
            }
            vkCmdSetScissor(cmd, 0, sc);
            vkCmdSetLineWidth(cmd, gl.gpu.wideLines ? Math.max(gl.gpu.lineWidthMin, Math.min(gl.gpu.lineWidthMax, st.lineWidth)) : 1f);
            vkCmdSetDepthBias(cmd, st.polyUnits, gl.gpu.depthBiasClamp ? st.polyClamp : 0f, st.polyFactor);
            vkCmdSetBlendConstants(cmd, stack.floats(st.blendColor[0], st.blendColor[1], st.blendColor[2], st.blendColor[3]));
            vkCmdSetStencilCompareMask(cmd, VK_STENCIL_FACE_FRONT_BIT, st.stencilValueMask[0] & 0xFF);
            vkCmdSetStencilCompareMask(cmd, VK_STENCIL_FACE_BACK_BIT, st.stencilValueMask[1] & 0xFF);
            vkCmdSetStencilWriteMask(cmd, VK_STENCIL_FACE_FRONT_BIT, st.stencilWriteMask[0] & 0xFF);
            vkCmdSetStencilWriteMask(cmd, VK_STENCIL_FACE_BACK_BIT, st.stencilWriteMask[1] & 0xFF);
            vkCmdSetStencilReference(cmd, VK_STENCIL_FACE_FRONT_BIT, st.stencilRef[0] & 0xFF);
            vkCmdSetStencilReference(cmd, VK_STENCIL_FACE_BACK_BIT, st.stencilRef[1] & 0xFF);
        }
    }


    private final int[] dynamicOffsets = new int[32];
    private int dynamicCount;

    long resolveDescriptors(GlState st, Program prog, Target target, boolean compute) {
        final long serial = gl.stream.currentSerial();
        final long[] key = keyScratch;
        int k = 0;
        dynamicCount = 0;
        final int imageLayoutFor = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        for (int bi = 0; bi < prog.bindings.size(); bi++) {
            final Program.Binding b = prog.bindings.get(bi);
            if (b.kind != Program.BindingKind.STORAGE_IMAGE) continue;
            final Program.Uniform u = prog.uniforms.get(b.ref);
            for (int e = 0; e < b.count; e++) {
                gl.textures.storageImageView(st, Math.min(Math.max(0, u.units[e]), GlState.MAX_IMAGE_UNITS - 1), target);
            }
        }
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
                    range = Math.min(range, 65536);
                    buf.lastUse = serial;
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
                    Buf buf = gl.buffers.get(st.ssboBuffer[point]);
                    long offset = st.ssboOffset[point];
                    if (buf == null || buf.alloc == null || offset >= buf.size) {
                        buf = dummyBuffer;
                        offset = 0;
                    }
                    final long range = st.ssboSize[point] > 0 ? Math.min(st.ssboSize[point], buf.size - offset) : buf.size - offset;
                    buf.lastUse = serial;
                    key[k++] = buf.handle();
                    key[k++] = offset;
                    key[k++] = range;
                }
                case COMBINED_SAMPLER, SAMPLED_IMAGE, SAMPLER -> {
                    final Program.Uniform u = prog.uniforms.get(b.ref);
                    for (int e = 0; e < b.count; e++) {
                        final int unit = Math.min(Math.max(0, u.units[e]), GlState.MAX_UNITS - 1);
                        long sampler = 0L, view = 0L;
                        int layout = imageLayoutFor;
                        if (b.kind != Program.BindingKind.SAMPLER) {
                            final int viewType = viewTypeFor(b);
                            Tex tex = gl.textures.get(st.textures[unit][targetSlot(viewType)]);
                            if (tex == null || !tex.hasImage() || Images.viewTypeFor(tex) != viewType && !compatibleView(tex, viewType)) {
                                tex = dummyFor(viewType, b.shadow);
                            }
                            layout = prepareSampled(tex, target);
                            if (layout == -1) return -1L;
                            view = sampledView(tex, viewType);
                            tex.lastUse = serial;
                            if (b.kind == Program.BindingKind.COMBINED_SAMPLER) sampler = samplerFor(st, unit, tex, b.shadow);
                        } else {
                            sampler = samplerFor(st, unit, null, false);
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
                        final long view = gl.textures.storageImageView(st, unit, target);
                        key[k++] = view;
                        key[k++] = 0L;
                        key[k++] = VK_IMAGE_LAYOUT_GENERAL;
                    }
                }
            }
        }
        if (prog.bindings.isEmpty()) return 0L;
        if (prog.cachedSet != 0L && prog.cachedEpoch == pools.epoch && prog.cachedKey.length == k && Arrays.equals(prog.cachedKey, 0, k, key, 0, k)) {
            return prog.cachedSet;
        }
        final long set = pools.allocate(prog.setLayout);
        writeSet(set, prog, key);
        prog.cachedSet = set;
        prog.cachedEpoch = pools.epoch;
        if (prog.cachedKey.length != k) prog.cachedKey = new long[k];
        System.arraycopy(key, 0, prog.cachedKey, 0, k);
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
                        if (b.descriptorType == VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER || b.descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER) {
                            // texel buffers are not emulated; point at nothing valid would be an error, so skip the write
                            k += 3 * b.count;
                            w.descriptorCount(0);
                            continue;
                        }
                        final VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(b.count, stack);
                        for (int e = 0; e < b.count; e++) {
                            info.get(e).imageView(key[k]).sampler(key[k + 1]).imageLayout((int) key[k + 2]);
                            k += 3;
                        }
                        w.pImageInfo(info).descriptorCount(b.count);
                    }
                }
            }
            int n = 0;
            for (int i = 0; i < writes.capacity(); i++) if (writes.get(i).descriptorCount() > 0) {
                if (n != i) MemoryUtil.memCopy(writes.get(i).address(), writes.get(n).address(), VkWriteDescriptorSet.SIZEOF);
                n++;
            }
            writes.limit(n);
            vkUpdateDescriptorSets(gl.gpu.device, writes, null);
        }
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final java.nio.IntBuffer offs = stack.mallocInt(dynamicCount);
            for (int i = 0; i < dynamicCount; i++) offs.put(i, dynamicOffsets[i]);
            vkCmdBindDescriptorSets(cmd, bindPoint, prog.pipelineLayout, 0, stack.longs(set), offs);
        }
        if (bindPoint == VK_PIPELINE_BIND_POINT_GRAPHICS) {
            boundSet = set;
            boundLayout = prog.pipelineLayout;
            boundDynamicCount = dynamicCount;
            System.arraycopy(dynamicOffsets, 0, boundDynamicOffsets, 0, dynamicCount);
        } else {
            boundSet = 0L;
            boundLayout = 0L;
        }
    }

    private static int viewTypeFor(Program.Binding b) {
        return switch (b.dim) {
            case 0 -> b.arrayed ? VK_IMAGE_VIEW_TYPE_1D_ARRAY : VK_IMAGE_VIEW_TYPE_1D;
            case 2 -> VK_IMAGE_VIEW_TYPE_3D;
            case 3 -> b.arrayed ? VK_IMAGE_VIEW_TYPE_CUBE_ARRAY : VK_IMAGE_VIEW_TYPE_CUBE;
            default -> b.arrayed ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D;
        };
    }

    private static int targetSlot(int viewType) {
        return switch (viewType) {
            case VK_IMAGE_VIEW_TYPE_1D -> GlState.T_1D;
            case VK_IMAGE_VIEW_TYPE_1D_ARRAY -> GlState.T_1D_ARRAY;
            case VK_IMAGE_VIEW_TYPE_2D_ARRAY -> GlState.T_2D_ARRAY;
            case VK_IMAGE_VIEW_TYPE_3D -> GlState.T_3D;
            case VK_IMAGE_VIEW_TYPE_CUBE -> GlState.T_CUBE;
            case VK_IMAGE_VIEW_TYPE_CUBE_ARRAY -> GlState.T_CUBE_ARRAY;
            default -> GlState.T_2D;
        };
    }

    private static boolean compatibleView(Tex tex, int viewType) {
        final int own = Images.viewTypeFor(tex);
        if (own == viewType) return true;
        return viewType == VK_IMAGE_VIEW_TYPE_2D && own == VK_IMAGE_VIEW_TYPE_2D;
    }

    private Tex dummyFor(int viewType, boolean shadow) {
        if (shadow && viewType == VK_IMAGE_VIEW_TYPE_2D) return dummyDepth;
        return switch (viewType) {
            case VK_IMAGE_VIEW_TYPE_2D_ARRAY -> dummy2DArray;
            case VK_IMAGE_VIEW_TYPE_3D -> dummy3D;
            case VK_IMAGE_VIEW_TYPE_CUBE -> dummyCube;
            default -> dummy2D;
        };
    }

    private int prepareSampled(Tex tex, Target target) {
        if (tex.pendingClear || tex.needsClear) materialize(tex);
        final boolean feedback = target != null && passActive && passTarget.contains(tex) || target != null && target.contains(tex);
        if (feedback) {
            if (tex.layout != VK_IMAGE_LAYOUT_GENERAL) {
                endPass();
                gl.images.transition(gl.stream.cmd(), tex, VK_IMAGE_LAYOUT_GENERAL);
            }
            return VK_IMAGE_LAYOUT_GENERAL;
        }
        if (tex.layout == VK_IMAGE_LAYOUT_GENERAL && tex.storageView != 0L) return VK_IMAGE_LAYOUT_GENERAL;
        if (tex.layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            releaseFromPass(tex);
            endPass();
            gl.images.transition(gl.stream.cmd(), tex, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        return VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    long sampledView(Tex tex, int viewType) {
        if (tex.viewDirty) {
            refreshViews(tex);
        }
        if (Images.viewTypeFor(tex) == viewType) {
            if (tex.view == 0L) tex.view = gl.images.createSampledView(tex, viewType);
            return tex.view;
        }
        long[] extra = extraViews.get(tex.id);
        if (extra == null) {
            extra = new long[8];
            extraViews.put(tex.id, extra);
        }
        if (extra[viewType] == 0L) extra[viewType] = gl.images.createSampledView(tex, viewType);
        return extra[viewType];
    }

    private void refreshViews(Tex tex) {
        tex.viewDirty = false;
        final long old = tex.view;
        tex.view = 0L;
        final long[] extra = extraViews.remove(tex.id);
        if (old != 0L || extra != null) {
            gl.stream.defer(() -> {
                if (old != 0L) vkDestroyImageView(gl.gpu.device, old, null);
                if (extra != null) for (long v : extra) if (v != 0L) vkDestroyImageView(gl.gpu.device, v, null);
                pools.epoch++;
            });
        }
    }

    long[] forgetViews(Tex t) {
        final long[] extra = extraViews.remove(t.id);
        pools.epoch++;
        if (extra == null) return new long[0];
        return Arrays.stream(extra).filter(v -> v != 0L).toArray();
    }

    long samplerFor(GlState st, int unit, Tex tex, boolean shadowBinding) {
        final SamplerObj so = gl.samplerObjects.get(st.samplers[unit]);
        final int min, mag, ws, wt, wr, cmpMode, cmpFunc;
        final float minLod, maxLod, bias, aniso;
        final float[] border;
        if (so != null) {
            min = so.minFilter; mag = so.magFilter; ws = so.wrapS; wt = so.wrapT; wr = so.wrapR;
            minLod = so.minLod; maxLod = so.maxLod; bias = so.lodBias; aniso = so.anisotropy; cmpMode = so.compareMode; cmpFunc = so.compareFunc; border = so.border;
        } else if (tex != null) {
            if (!tex.samplerDirty && tex.sampler != 0L) return tex.sampler;
            min = tex.minFilter; mag = tex.magFilter; ws = tex.wrapS; wt = tex.wrapT; wr = tex.wrapR;
            minLod = tex.minLod; maxLod = tex.maxLod; bias = tex.lodBias; aniso = tex.anisotropy; cmpMode = tex.compareMode; cmpFunc = tex.compareFunc; border = tex.border;
        } else {
            min = GL11.GL_NEAREST; mag = GL11.GL_NEAREST; ws = wt = wr = GL12.GL_CLAMP_TO_EDGE;
            minLod = -1000; maxLod = 1000; bias = 0; aniso = 1; cmpMode = GL11.GL_NONE; cmpFunc = GL11.GL_LEQUAL; border = ZERO;
        }
        final boolean integer = tex != null && (tex.format.isInteger() || tex.format.vk() == VK_FORMAT_D32_SFLOAT_S8_UINT && tex.sampleStencil);
        final boolean linearOk = tex == null || !integer && gl.images.supports(tex.format.vk(), VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
        final int magF = linearOk && mag == GL11.GL_LINEAR ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        final int minF = linearOk && (min == GL11.GL_LINEAR || min == GL11.GL_LINEAR_MIPMAP_NEAREST || min == GL11.GL_LINEAR_MIPMAP_LINEAR) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        final boolean mip = min != GL11.GL_NEAREST && min != GL11.GL_LINEAR;
        final int mipMode = linearOk && (min == GL11.GL_NEAREST_MIPMAP_LINEAR || min == GL11.GL_LINEAR_MIPMAP_LINEAR) ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST;
        final boolean compare = cmpMode == GL14.GL_COMPARE_R_TO_TEXTURE && tex != null && tex.format.isDepth() || shadowBinding && tex != null && tex.format.isDepth() && cmpMode != GL11.GL_NONE;
        final float maxAniso = gl.gpu.samplerAnisotropy ? Math.min(aniso, gl.gpu.maxSamplerAnisotropy) : 1f;
        final int borderColor = borderColor(border, integer);
        long key = magF | minF << 1 | mipMode << 2 | (mip ? 1 : 0) << 3 | (long) wrap(ws) << 4 | (long) wrap(wt) << 7 | (long) wrap(wr) << 10
            | (long) (compare ? 1 : 0) << 13 | (long) compareOp(cmpFunc) << 14 | (long) borderColor << 17 | (long) Math.round(maxAniso) << 20;
        key = key * 31 + Float.floatToIntBits(minLod);
        key = key * 31 + Float.floatToIntBits(maxLod);
        key = key * 31 + Float.floatToIntBits(bias);
        long sampler = samplers.get(key);
        if (sampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(magF).minFilter(minF).mipmapMode(mipMode)
                    .addressModeU(wrap(ws)).addressModeV(wrap(wt)).addressModeW(wrap(wr))
                    .mipLodBias(Math.max(-15f, Math.min(15f, bias)))
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
            case org.lwjgl.opengl.GL14.GL_MIRRORED_REPEAT -> VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            case org.lwjgl.opengl.GL44.GL_MIRROR_CLAMP_TO_EDGE -> this.gl.gpu.samplerMirrorClampToEdge ? VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            default -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
        };
    }

    private static int borderColor(float[] b, boolean integer) {
        final boolean opaque = b[3] >= 0.5f;
        final boolean white = b[0] >= 0.5f && b[1] >= 0.5f && b[2] >= 0.5f;
        if (integer) return opaque ? (white ? VK_BORDER_COLOR_INT_OPAQUE_WHITE : VK_BORDER_COLOR_INT_OPAQUE_BLACK) : VK_BORDER_COLOR_INT_TRANSPARENT_BLACK;
        return opaque ? (white ? VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE : VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK) : VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
    }


    private final float[] lastConstValues = new float[64];
    private long constRingBuffer;
    private long constRingOffset;
    private boolean constValid;

    private boolean bindVertexBuffers(VkCommandBuffer cmd, GlState st, Program prog, Vao vao) {
        final long serial = gl.stream.currentSerial();
        int n = 0;
        int mask = prog.vertexInputMask;
        boolean needConst = false;
        while (mask != 0) {
            final int loc = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            final int binding = vao.bindingOf[loc];
            final Buf buf = vao.enabled[loc] ? gl.buffers.get(vao.bindingBuffer[binding]) : null;
            if (buf != null && buf.alloc != null) {
                buf.lastUse = serial;
                MemoryUtil.memPutLong(vbHandles + n * 8L, buf.handle());
                MemoryUtil.memPutLong(vbOffsets + n * 8L, vao.bindingOffset[binding] + vao.relativeOffset[loc]);
            } else {
                needConst = true;
                MemoryUtil.memPutLong(vbHandles + n * 8L, -loc - 1);
            }
            n++;
        }
        if (needConst) {
            if (!constValid || constRingSerial != serial || !Arrays.equals(lastConstValues, st.attribValue)) {
                gl.uniformRing.alloc(Vao.MAX_ATTRIBS * 16L, 16);
                for (int i = 0; i < Vao.MAX_ATTRIBS * 4; i++) MemoryUtil.memPutFloat(gl.uniformRing.address + i * 4L, st.attribValue[i]);
                constRingBuffer = gl.uniformRing.buffer;
                constRingOffset = gl.uniformRing.offset;
                constRingSerial = serial;
                System.arraycopy(st.attribValue, 0, lastConstValues, 0, lastConstValues.length);
                constValid = true;
            }
            for (int i = 0; i < n; i++) {
                final long h = MemoryUtil.memGetLong(vbHandles + i * 8L);
                if (h < 0) {
                    final int loc = (int) (-h - 1);
                    MemoryUtil.memPutLong(vbHandles + i * 8L, constRingBuffer);
                    MemoryUtil.memPutLong(vbOffsets + i * 8L, constRingOffset + loc * 16L);
                }
            }
        }
        if (n == 0) return true;
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
        return true;
    }

    private long constRingSerial = -1;

    boolean bindIndexBuffer(VkCommandBuffer cmd, long buffer, long offset, int vkIndexType) {
        if (buffer == boundIndexBuffer && offset == boundIndexOffset && vkIndexType == boundIndexType) return true;
        vkCmdBindIndexBuffer(cmd, buffer, offset, vkIndexType);
        boundIndexBuffer = buffer;
        boundIndexOffset = offset;
        boundIndexType = vkIndexType;
        return true;
    }


    private int shaderStages() {
        int stages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        if (gl.gpu.geometryShader) stages |= VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT;
        if (gl.gpu.tessellationShader) stages |= VK_PIPELINE_STAGE_TESSELLATION_CONTROL_SHADER_BIT | VK_PIPELINE_STAGE_TESSELLATION_EVALUATION_SHADER_BIT;
        return stages;
    }

    boolean prepareDispatch(GlState st) {
        final Program prog = gl.programs.current(st);
        if (prog == null || !prog.linked || !prog.compute) return false;
        final long set = resolveDescriptors(st, prog, null, true);
        if (set == -1L) return false;
        endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        if (pendingTransferBarrier || pendingComputeBarrier) {
            gl.images.globalBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | shaderStages(),
                VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT);
            pendingTransferBarrier = false;
            pendingComputeBarrier = false;
        }
        resetBoundIfNewCommandBuffer();
        final long pipeline = pipelines.compute(prog);
        if (pipeline == 0L) return false;
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        boundPipeline = 0L;
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
            case org.lwjgl.opengl.GL33.GL_SRC1_COLOR -> VK_BLEND_FACTOR_SRC1_COLOR;
            case org.lwjgl.opengl.GL33.GL_ONE_MINUS_SRC1_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC1_COLOR;
            case org.lwjgl.opengl.GL15.GL_SRC1_ALPHA -> VK_BLEND_FACTOR_SRC1_ALPHA;
            case org.lwjgl.opengl.GL33.GL_ONE_MINUS_SRC1_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC1_ALPHA;
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

    static int topology(int mode, boolean fans) {
        return switch (mode) {
            case GL11.GL_POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case GL11.GL_LINES -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case GL11.GL_LINE_STRIP, GL11.GL_LINE_LOOP -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case GL11.GL_TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case GL11.GL_TRIANGLE_FAN, GL11.GL_POLYGON -> fans ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case GL32.GL_LINES_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST_WITH_ADJACENCY;
            case GL32.GL_LINE_STRIP_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY;
            case GL32.GL_TRIANGLES_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST_WITH_ADJACENCY;
            case GL32.GL_TRIANGLE_STRIP_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY;
            case GL40.GL_PATCHES -> VK_PRIMITIVE_TOPOLOGY_PATCH_LIST;
            default -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        };
    }

    void destroy() {
        endPass();
        for (long s : samplers.values()) vkDestroySampler(gl.gpu.device, s, null);
        samplers.clear();
        pipelines.destroy();
        pools.destroy();
        MemoryUtil.nmemFree(vbHandles);
        MemoryUtil.nmemFree(vbOffsets);
    }
}
