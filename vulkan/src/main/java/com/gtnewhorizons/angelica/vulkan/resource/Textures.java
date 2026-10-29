package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.glemu.resource.TexturesBase;
import com.gtnewhorizons.angelica.glemu.resource.BufBase;

import com.gtnewhorizons.angelica.glemu.resource.PixelFormats;

import com.gtnewhorizons.angelica.glemu.resource.Formats;

import com.gtnewhorizons.angelica.glemu.resource.PixelConvert;

import com.gtnewhorizons.angelica.glemu.resource.FormatUtil;
import com.gtnewhorizons.angelica.glemu.resource.TexBase;

import com.gtnewhorizons.angelica.glemu.gl.GlState;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import com.gtnewhorizons.angelica.vulkan.memory.MemoryAllocator;
import com.gtnewhorizons.angelica.vulkan.memory.VkBufferAlloc;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;

import static org.lwjgl.vulkan.VK10.*;

public final class Textures extends TexturesBase<Tex> {
    private final VulkanGL gl;
    public long uploads, uploadBytes, hoistedUploads;
    private final java.util.ArrayList<Tex> pendingUploads = new java.util.ArrayList<>();

    private static boolean overlaps(int[] r, int count, int level, int layer, int layers, int x, int y, int z, int w, int h, int d) {
        for (int i = 0; i < count * 9; i += 9) {
            if (r[i] != level || layer >= r[i + 2] || layer + layers <= r[i + 1]) continue;
            if (x < r[i + 6] && x + w > r[i + 3] && y < r[i + 7] && y + h > r[i + 4] && z < r[i + 8] && z + d > r[i + 5]) return true;
        }
        return false;
    }

    private static int[] record(int[] r, int index, int level, int layer, int layers, int x, int y, int z, int w, int h, int d) {
        if ((index + 1) * 9 > r.length) r = java.util.Arrays.copyOf(r, r.length * 2);
        final int i = index * 9;
        r[i] = level;
        r[i + 1] = layer;
        r[i + 2] = layer + layers;
        r[i + 3] = x;
        r[i + 4] = y;
        r[i + 5] = z;
        r[i + 6] = x + w;
        r[i + 7] = y + h;
        r[i + 8] = z + d;
        return r;
    }

    public void flushUploads() {
        if (pendingUploads.isEmpty()) return;
        final VkCommandBuffer cmd = gl.stream.uploadCmd();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int n = 0;
            for (Tex t : pendingUploads) if (t.uploadFinalLayout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) n++;
            if (n > 0) {
                final org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer b = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(n, stack);
                int i = 0;
                for (Tex t : pendingUploads) {
                    if (t.uploadFinalLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) continue;
                    b.get(i++).sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .newLayout(t.uploadFinalLayout)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(t.uploadImage)
                        .subresourceRange().set(t.uploadAspect, 0, VK_REMAINING_MIP_LEVELS, 0, VK_REMAINING_ARRAY_LAYERS);
                }
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, b);
            }
        }
        for (Tex t : pendingUploads) t.uploadSerial = -1;
        pendingUploads.clear();
    }

    public Textures(VulkanGL gl) {
        this.gl = gl;
        depth24Stencil8 = PixelFormats.PF_DEPTH24_UNORM_STENCIL8;
        depth24 = PixelFormats.PF_X8_DEPTH24_UNORM;
    }

    public void pickDepthFormats() {
        final int dsFeature = VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        if (!gl.images.supports(VK_FORMAT_D24_UNORM_S8_UINT, dsFeature)) depth24Stencil8 = PixelFormats.PF_DEPTH32_FLOAT_STENCIL8;
        if (!gl.images.supports(VK_FORMAT_X8_D24_UNORM_PACK32, dsFeature)) depth24 = gl.images.supports(VK_FORMAT_D24_UNORM_S8_UINT, dsFeature) ? PixelFormats.PF_DEPTH24_UNORM_STENCIL8 : PixelFormats.PF_DEPTH32_FLOAT;
    }

        public void specify(Tex t, Formats.Format f, int level, int w, int h, int d, int layers) {
        specify(t, f, level, w, h, d, layers, false);
    }

    @Override protected Tex snapshot(Tex t) {
        final Tex s = new Tex(t.id, t.renderbuffer);
        s.target = t.target;
        s.format = t.format;
        s.width = t.width;
        s.height = t.height;
        s.depth = t.depth;
        s.layers = t.layers;
        s.levels = t.levels;
        s.image = t.image;
        s.memory = t.memory;
        s.view = t.view;
        s.layout = t.layout;
        s.discardedFrom = t.discardedFrom;
        s.lastUse = t.lastUse;
        s.attachmentViews.putAll(t.attachmentViews);
        s.pendingClear = t.pendingClear;
        s.needsClear = t.needsClear;
        System.arraycopy(t.clearColor, 0, s.clearColor, 0, 4);
        s.clearDepth = t.clearDepth;
        s.clearStencil = t.clearStencil;
        return s;
    }

    @Override protected void copyLevels(Tex from, Tex to) {
        gl.renderer.materialize(from);
        gl.renderer.endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        gl.images.transition(cmd, from, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        gl.images.transition(cmd, to, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        final int n = Math.min(from.levels, to.levels);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageCopy.Buffer regions = VkImageCopy.calloc(n, stack);
            for (int l = 0; l < n; l++) {
                final VkImageCopy r = regions.get(l);
                r.srcSubresource().set(from.format.aspect(), l, 0, from.layers);
                r.dstSubresource().set(to.format.aspect(), l, 0, to.layers);
                r.extent().set(from.levelWidth(l), from.levelHeight(l), from.levelDepth(l));
            }
            vkCmdCopyImage(cmd, from.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, to.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
        }
        to.needsClear = false;
        from.lastUse = gl.stream.currentSerial();
        gl.images.release(from);
    }

    @Override protected void uploadFrom(GlState st, Tex t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int format, int type, long src) {
        final Formats.Format f = t.format;
        final int rows = h * Math.max(1, d) * layerCount;
        final long bytes;
        if (f.isCompressed()) {
            bytes = FormatUtil.imageBytes(f, w, h) * Math.max(1, d) * layerCount;
        } else {
            bytes = (long) w * rows * f.texelBytes();
        }
        gl.stagingRing.alloc(bytes, 16);
        final long staging = gl.stagingRing.address;
        final long stagingBuffer = gl.stagingRing.buffer;
        final long stagingOffset = gl.stagingRing.offset;
        if (f.isCompressed()) {
            MemoryUtil.memCopy(src, staging, bytes);
        } else {
            final PixelConvert.Store s = st.unpack;
            final int imgDepth = Math.max(1, d) * layerCount;
            if (!PixelConvert.unpack(f, format, type, src, w, h, imgDepth, s, staging)) {
                gl.warnOnce("cannot convert pixels format=0x" + Integer.toHexString(format) + " type=0x" + Integer.toHexString(type) + " into " + VkFormats.of(f.pf()));
            }
        }
        final boolean hoist = gl.stream.canHoist(t.lastUse) && !t.pendingClear && !t.needsClear;
        final VkCommandBuffer cmd;
        if (hoist) {
            cmd = gl.stream.uploadCmd();
            hoistedUploads++;
        } else {
            gl.renderer.materialize(t);
            gl.renderer.releaseFromPass(t);
            gl.renderer.endPass();
            cmd = gl.stream.cmd();
        }
        final int depthExtent = Math.max(1, d);
        final long serial = gl.stream.currentSerial();
        final boolean continuing = hoist && t.uploadSerial == serial && t.uploadImage == t.image;
        final boolean copyRun = !hoist && t.layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && t.copySerial == serial && t.copyRectCount > 0;
        if (continuing) {
            if (overlaps(t.uploadRects, t.uploadRectCount, level, layer, layerCount, x, y, z, w, h, depthExtent)) {
                gl.images.barrier(cmd, t.image, t.format.aspect(), 0, VK_REMAINING_MIP_LEVELS, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, false);
                t.uploadRectCount = 0;
            }
        } else if (!copyRun || overlaps(t.copyRects, t.copyRectCount, level, layer, layerCount, x, y, z, w, h, depthExtent)) {
            final boolean full = level == 0 && x == 0 && y == 0 && z == 0 && w == t.width && h == t.height && t.levels == 1 && layerCount == t.layers;
            if (full && t.layout != VK_IMAGE_LAYOUT_UNDEFINED) {
                t.discardedFrom = t.layout;
                t.layout = VK_IMAGE_LAYOUT_UNDEFINED;
            }
            gl.images.transition(cmd, t, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            if (hoist) {
                t.uploadSerial = serial;
                t.uploadImage = t.image;
                t.uploadAspect = t.format.aspect();
                t.uploadFinalLayout = (t.usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0 ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
                t.uploadRectCount = 0;
                pendingUploads.add(t);
            } else {
                t.copySerial = serial;
            }
        }
        if (hoist) t.uploadRects = record(t.uploadRects, t.uploadRectCount++, level, layer, layerCount, x, y, z, w, h, depthExtent);
        else t.copyRects = record(t.copyRects, t.copyRectCount++, level, layer, layerCount, x, y, z, w, h, depthExtent);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(stagingOffset).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource().set(f.isDepthOrStencil() ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT, level, layer, layerCount);
            region.get(0).imageOffset().set(x, y, z);
            region.get(0).imageExtent().set(w, h, Math.max(1, d));
            vkCmdCopyBufferToImage(cmd, stagingBuffer, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
        t.needsClear = false;
        if (hoist) {
            t.layout = t.uploadFinalLayout;
        } else {
            t.lastUse = gl.stream.currentSerial();
        }
        uploads++;
        uploadBytes += bytes;
    }

    public void copyFromFramebuffer(GlState st, Tex dst, int target, int level, int dx, int dy, int dz, int sx, int sy, int w, int h) {
        if (dst == null || !dst.hasImage() || w <= 0 || h <= 0) return;
        final Target src = gl.renderer.readTarget(st);
        final boolean depth = dst.format.isDepthOrStencil();
        final Tex s = depth ? src.depth : src.color[0];
        if (s == null) return;
        final boolean depthViaBuffer = depth && VkFormats.of(s.format.pf()) != VkFormats.of(dst.format.pf());
        if (depthViaBuffer && (depthBits(VkFormats.of(s.format.pf())) == 0 || depthBits(VkFormats.of(s.format.pf())) != depthBits(VkFormats.of(dst.format.pf())))) {
            gl.warnOnce("glCopyTexImage between depth formats " + VkFormats.of(s.format.pf()) + " and " + VkFormats.of(dst.format.pf()) + " is not supported");
            return;
        }
        final int srcLevel = depth ? src.depthLevel : src.colorLevel[0];
        final int srcLayer = depth ? src.depthLayer : src.colorLayer[0];
        int x0 = sx, y0 = sy, x1 = sx + w, y1 = sy + h;
        final int sw = s.levelWidth(srcLevel), sh = s.levelHeight(srcLevel);
        final int cx0 = Math.max(0, x0), cy0 = Math.max(0, y0), cx1 = Math.min(sw, x1), cy1 = Math.min(sh, y1);
        if (cx1 <= cx0 || cy1 <= cy0) return;
        dx += cx0 - x0;
        dy += cy0 - y0;
        final int cw = cx1 - cx0, ch = cy1 - cy0;
        int dstLayer = dst.isCube() ? cubeFace(target) : (dst.target == GL30.GL_TEXTURE_2D_ARRAY ? dz : 0);
        gl.renderer.materialize(s);
        gl.renderer.materialize(dst);
        gl.renderer.endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        if (s == dst) {
            gl.images.transition(cmd, s, VK_IMAGE_LAYOUT_GENERAL);
        } else {
            gl.images.transition(cmd, s, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            gl.images.transition(cmd, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        }
        final int srcLayout = s == dst ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        final int dstLayout = s == dst ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (depthViaBuffer) {
                final long bytes = (long) cw * ch * (depthBits(VkFormats.of(s.format.pf())) == 16 ? 2 : 4);
                final VkBufferAlloc staging = VkBufferAlloc.create(gl.gpu, gl.allocator, bytes, Buffers.USAGE, MemoryAllocator.USAGE_DEVICE);
                final VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, stack);
                r.get(0).imageSubresource().set(VK_IMAGE_ASPECT_DEPTH_BIT, srcLevel, srcLayer, 1);
                r.get(0).imageOffset().set(cx0, cy0, 0);
                r.get(0).imageExtent().set(cw, ch, 1);
                vkCmdCopyImageToBuffer(cmd, s.image, srcLayout, staging.buffer, r);
                gl.images.globalBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
                r.get(0).imageSubresource().set(VK_IMAGE_ASPECT_DEPTH_BIT, level, dstLayer, 1);
                r.get(0).imageOffset().set(dx, dy, dst.is3D() ? dz : 0);
                vkCmdCopyBufferToImage(cmd, staging.buffer, dst.image, dstLayout, r);
                gl.stream.defer(() -> staging.destroy(gl.gpu, gl.allocator));
            } else if (VkFormats.of(s.format.pf()) == VkFormats.of(dst.format.pf()) || VkFormats.texelBytes(VkFormats.of(s.format.pf())) == VkFormats.texelBytes(VkFormats.of(dst.format.pf())) && !s.format.isDepthOrStencil() && !dst.format.isDepthOrStencil()) {
                final VkImageCopy.Buffer r = VkImageCopy.calloc(1, stack);
                r.srcSubresource().set(s.format.aspect(), srcLevel, srcLayer, 1);
                r.srcOffset().set(cx0, cy0, 0);
                r.dstSubresource().set(dst.format.aspect(), level, dstLayer, 1);
                r.dstOffset().set(dx, dy, dst.is3D() ? dz : 0);
                r.extent().set(cw, ch, 1);
                vkCmdCopyImage(cmd, s.image, srcLayout, dst.image, dstLayout, r);
            } else {
                final VkImageBlit.Buffer b = VkImageBlit.calloc(1, stack);
                b.srcSubresource().set(s.format.aspect(), srcLevel, srcLayer, 1);
                b.srcOffsets(0).set(cx0, cy0, 0);
                b.srcOffsets(1).set(cx1, cy1, 1);
                b.dstSubresource().set(dst.format.aspect(), level, dstLayer, 1);
                b.dstOffsets(0).set(dx, dy, 0);
                b.dstOffsets(1).set(dx + cw, dy + ch, 1);
                vkCmdBlitImage(cmd, s.image, srcLayout, dst.image, dstLayout, b, VK_FILTER_NEAREST);
            }
        }
        final long serial = gl.stream.currentSerial();
        s.lastUse = serial;
        dst.lastUse = serial;
    }

    private static int depthBits(int vk) {
        return switch (vk) {
            case VK_FORMAT_D16_UNORM -> 16;
            case VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D24_UNORM_S8_UINT -> 24;
            case VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT -> 32;
            default -> 0;
        };
    }

    public void copyImage(Tex src, int srcLevel, int sx, int sy, int sz, Tex dst, int dstLevel, int dx, int dy, int dz, int w, int h, int d) {
        if (src == null || dst == null || !src.hasImage() || !dst.hasImage()) return;
        gl.renderer.materialize(src);
        gl.renderer.materialize(dst);
        gl.renderer.endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        final boolean same = src == dst;
        if (same) {
            gl.images.transition(cmd, src, VK_IMAGE_LAYOUT_GENERAL);
        } else {
            gl.images.transition(cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            gl.images.transition(cmd, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageCopy.Buffer r = VkImageCopy.calloc(1, stack);
            final boolean srcLayered = src.layers > 1, dstLayered = dst.layers > 1;
            r.srcSubresource().set(src.format.aspect(), srcLevel, srcLayered ? sz : 0, srcLayered ? d : 1);
            r.srcOffset().set(sx, sy, srcLayered ? 0 : sz);
            r.dstSubresource().set(dst.format.aspect(), dstLevel, dstLayered ? dz : 0, dstLayered ? d : 1);
            r.dstOffset().set(dx, dy, dstLayered ? 0 : dz);
            r.extent().set(w, h, srcLayered ? 1 : d);
            vkCmdCopyImage(cmd, src.image, same ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.image, same ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
        }
        final long serial = gl.stream.currentSerial();
        src.lastUse = serial;
        dst.lastUse = serial;
    }

    @Override public boolean readImage(Tex t, int aspect, int level, int layer, int x, int y, int w, int h, int format, int type, PixelConvert.Store pack, long dst) {
        if (t == null || !t.hasImage() || w <= 0 || h <= 0) return false;
        gl.renderer.materialize(t);
        gl.renderer.releaseFromPass(t);
        gl.renderer.endPass();
        final int texel = t.format.isDepthOrStencil() ? (VkFormats.of(t.format.pf()) == VK_FORMAT_D16_UNORM ? 2 : 4) : t.format.texelBytes();
        final long bytes = (long) w * h * texel;
        final VkBufferAlloc staging = VkBufferAlloc.create(gl.gpu, gl.allocator, bytes, Buffers.USAGE, MemoryAllocator.USAGE_READBACK);
        try {
            final VkCommandBuffer cmd = gl.stream.cmd();
            gl.images.transition(cmd, t, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0).bufferOffset(0);
                region.get(0).imageSubresource().set(aspect, level, layer, 1);
                region.get(0).imageOffset().set(x, y, 0);
                region.get(0).imageExtent().set(w, h, 1);
                vkCmdCopyImageToBuffer(cmd, t.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging.buffer, region);
            }
            t.lastUse = gl.stream.currentSerial();
            gl.flushAndWait(gl.stream.currentSerial());
            final Formats.Format view = aspect == VK_IMAGE_ASPECT_STENCIL_BIT ? null : t.format;
            if (view == null) {
                for (int row = 0; row < h; row++) {
                    for (int col = 0; col < w; col++) {
                        MemoryUtil.memPutByte(dst + (long) row * PixelConvert.rowStride(w, format, type, pack) + col, MemoryUtil.memGetByte(staging.alloc.mapped + (long) row * w + col));
                    }
                }
                return true;
            }
            if (!PixelConvert.pack(view, staging.alloc.mapped, w, h, format, type, pack, dst)) return false;
            if (format == GL30.GL_DEPTH_STENCIL && t.format.isStencil()) mergeStencil(t, level, layer, x, y, w, h, type, pack, dst);
            return true;
        } finally {
            staging.destroy(gl.gpu, gl.allocator);
        }
    }

    public long storageImageView(GlState st, int unit, Target target) {
        Tex t = get(st.imageTex[unit]);
        if (t == null || !t.hasImage()) {
            t = gl.renderer.dummy2D;
        }
        if ((t.usage & VK_IMAGE_USAGE_STORAGE_BIT) == 0 && t.id > 0) {
            enableStorage(t);
        }
        gl.renderer.materialize(t);
        if (t.layout != VK_IMAGE_LAYOUT_GENERAL) {
            gl.renderer.releaseFromPass(t);
            gl.renderer.endPass();
            gl.images.transition(gl.stream.cmd(), t, VK_IMAGE_LAYOUT_GENERAL);
        }
        t.lastUse = gl.stream.currentSerial();
        int vk = VkFormats.of(t.format.pf());
        final int glFmt = st.imageFormat[unit];
        if (glFmt != 0) {
            final Formats.Format f = Formats.of(glFmt, depth24Stencil8, depth24);
            if (f != null && VkFormats.texelBytes(VkFormats.of(f.pf())) == VkFormats.texelBytes(vk)) vk = VkFormats.of(f.pf());
        }
        return gl.images.storageView(t, st.imageLevel[unit], st.imageLayer[unit], st.imageLayered[unit], vk);
    }

    private void enableStorage(Tex t) {
        final Tex old = snapshot(t);
        t.image = 0L;
        t.memory = null;
        t.view = 0L;
        t.attachmentViews.clear();
        gl.renderer.onImageReleased(t);
        gl.images.create(t, true);
        copyLevels(old, t);
        t.viewDirty = true;
    }

    @Override protected Tex newTex(int id, boolean renderbuffer) {
        return new Tex(id, renderbuffer);
    }

    @Override protected void warnOnce(String message) {
        gl.warnOnce(message);
    }

    @Override protected int getInteger(int pname) {
        return gl.getInteger(pname);
    }

    @Override protected void createImage(Tex t) {
        gl.images.create(t, false);
    }

    @Override protected void releaseImage(Tex t) {
        gl.images.release(t);
    }

    @Override protected void onImageReleased(Tex t) {
        gl.renderer.onImageReleased(t);
    }

    @Override protected void clearImageHandles(Tex t) {
        t.image = 0L;
        t.memory = null;
        t.view = 0L;
        t.attachmentViews.clear();
    }

    @Override protected void targetsChanged() {
        gl.renderer.targetGen++;
    }

    @Override protected void onTextureDeleted(int id, boolean renderbuffer) {
        gl.framebuffers.onTextureDeleted(id, renderbuffer);
    }

    @Override protected boolean sampleable(Formats.Format f) {
        return gl.images.supports(VkFormats.of(f.pf()), VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT);
    }

    @Override protected BufBase unpackBuffer(int id) {
        return gl.buffers.get(id);
    }

    @Override protected void readBuffer(BufBase buffer, long offset, long size, long dst) {
        gl.buffers.read((Buf) buffer, offset, size, dst);
    }

    @Override protected boolean isIdle(long serial) {
        return gl.stream.isIdle(serial);
    }

    @Override protected void flushAndWait(long serial) {
        gl.flushAndWait(serial);
    }

    @Override protected boolean canGenerateMips(Tex t) {
        if (gl.images.supports(VkFormats.of(t.format.pf()), VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT)) return true;
        gl.warnOnce("cannot generate mipmaps for format " + VkFormats.of(t.format.pf()));
        return false;
    }

    @Override protected void prepareForMipGeneration(Tex t) {
        gl.renderer.materialize(t);
        gl.renderer.releaseFromPass(t);
        gl.renderer.endPass();
    }

    @Override protected void generateMips(Tex t) {
        final VkCommandBuffer cmd = gl.stream.cmd();
        final int aspect = t.format.aspect();
        final boolean linear = gl.images.supports(VkFormats.of(t.format.pf()), VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) && !t.format.isDepthOrStencil() && !t.format.isInteger();
        gl.images.barrier(cmd, t.image, aspect, 0, 1, t.layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, false);
        gl.images.barrier(cmd, t.image, aspect, 1, t.levels - 1, t.layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            for (int l = 1; l < t.levels; l++) {
                final VkImageBlit b = blit.get(0);
                b.srcSubresource().set(aspect, l - 1, 0, t.layers);
                b.srcOffsets(0).set(0, 0, 0);
                b.srcOffsets(1).set(TexBase.mipSourceExtent(t.levelWidth(l - 1), t.levelWidth(l)), TexBase.mipSourceExtent(t.levelHeight(l - 1), t.levelHeight(l)), TexBase.mipSourceExtent(t.levelDepth(l - 1), t.levelDepth(l)));
                b.dstSubresource().set(aspect, l, 0, t.layers);
                b.dstOffsets(0).set(0, 0, 0);
                b.dstOffsets(1).set(t.levelWidth(l), t.levelHeight(l), t.levelDepth(l));
                vkCmdBlitImage(cmd, t.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
                gl.images.barrier(cmd, t.image, aspect, l, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, false);
            }
        }
        t.layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    }

    @Override protected void mipsGenerated(Tex t) {
        t.lastUse = gl.stream.currentSerial();
    }
}
