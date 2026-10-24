package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.vulkan.memory.MemoryAllocator;
import com.gtnewhorizons.angelica.vulkan.memory.VkBufferAlloc;
import com.gtnewhorizons.angelica.vulkan.resource.Buf;
import com.gtnewhorizons.angelica.vulkan.resource.Formats;
import com.gtnewhorizons.angelica.vulkan.resource.PixelConvert;
import com.gtnewhorizons.angelica.vulkan.resource.Tex;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;

import static org.lwjgl.vulkan.VK10.*;

final class Textures {
    private final VulkanGL gl;
    private final Int2ObjectOpenHashMap<Tex> textures = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Tex> renderbuffers = new Int2ObjectOpenHashMap<>();
    private int nextTexId = 1, nextRboId = 1, nextInternalId = -1000;
    int depth24Stencil8 = VK_FORMAT_D24_UNORM_S8_UINT;
    int depth24 = VK_FORMAT_X8_D24_UNORM_PACK32;
    long uploads, uploadBytes;

    Textures(VulkanGL gl) {
        this.gl = gl;
    }

    void pickDepthFormats() {
        final int dsFeature = VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        if (!gl.images.supports(VK_FORMAT_D24_UNORM_S8_UINT, dsFeature)) depth24Stencil8 = VK_FORMAT_D32_SFLOAT_S8_UINT;
        if (!gl.images.supports(VK_FORMAT_X8_D24_UNORM_PACK32, dsFeature)) depth24 = gl.images.supports(VK_FORMAT_D24_UNORM_S8_UINT, dsFeature) ? VK_FORMAT_D24_UNORM_S8_UINT : VK_FORMAT_D32_SFLOAT;
    }

    Formats.Format format(int glInternal) {
        Formats.Format f = Formats.of(glInternal, depth24Stencil8, depth24);
        if (f == null) {
            gl.warnOnce("unsupported internal format 0x" + Integer.toHexString(glInternal) + ", using RGBA8");
            f = Formats.RGBA8;
        }
        if (f.isCompressed() && !gl.images.supports(f.vk(), VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)) {
            gl.warnOnce("compressed format 0x" + Integer.toHexString(glInternal) + " unsupported by the device");
        }
        return f;
    }

    int gen() {
        int id = nextTexId++;
        while (textures.containsKey(id)) id = nextTexId++;
        textures.put(id, new Tex(id, false));
        return id;
    }

    int genRenderbuffer() {
        int id = nextRboId++;
        while (renderbuffers.containsKey(id)) id = nextRboId++;
        renderbuffers.put(id, new Tex(id, true));
        return id;
    }

    Tex get(int id) {
        return id == 0 ? null : textures.get(id);
    }

    Tex getOrCreate(int id, int target) {
        if (id == 0) return null;
        Tex t = textures.get(id);
        if (t == null) {
            t = new Tex(id, false);
            textures.put(id, t);
            if (id >= nextTexId) nextTexId = id + 1;
        }
        if (t.target == 0 && target != 0) t.target = canonicalTarget(target);
        return t;
    }

    Tex renderbuffer(int id) {
        if (id == 0) return null;
        Tex t = renderbuffers.get(id);
        if (t == null) {
            t = new Tex(id, true);
            renderbuffers.put(id, t);
            if (id >= nextRboId) nextRboId = id + 1;
        }
        t.target = GL11.GL_TEXTURE_2D;
        return t;
    }

    boolean exists(int id) {
        return textures.containsKey(id);
    }

    boolean renderbufferExists(int id) {
        return renderbuffers.containsKey(id);
    }

    Tex attachment(int id, boolean rbo) {
        return rbo ? renderbuffers.get(id) : textures.get(id);
    }

    void delete(int id) {
        final Tex t = textures.remove(id);
        if (t == null) return;
        gl.images.release(t);
        gl.framebuffers.onTextureDeleted(id, false);
    }

    void deleteRenderbuffer(int id) {
        final Tex t = renderbuffers.remove(id);
        if (t == null) return;
        gl.images.release(t);
        gl.framebuffers.onTextureDeleted(id, true);
    }

    static int canonicalTarget(int target) {
        if (target >= GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z) return GL13.GL_TEXTURE_CUBE_MAP;
        if (target == GL13.GL_PROXY_TEXTURE_CUBE_MAP) return GL13.GL_TEXTURE_CUBE_MAP;
        return switch (target) {
            case GL31.GL_TEXTURE_RECTANGLE -> GL11.GL_TEXTURE_2D;
            default -> target;
        };
    }

    static int cubeFace(int target) {
        return target >= GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z ? target - GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X : 0;
    }

    Tex internalTexture(int target, Formats.Format f, int w, int h, int d, int layers, String label) {
        final Tex t = new Tex(nextInternalId--, false);
        t.target = target;
        t.format = f;
        t.width = w;
        t.height = h;
        t.depth = d;
        t.layers = layers;
        t.levels = 1;
        t.label = label;
        t.minFilter = GL11.GL_NEAREST;
        gl.images.create(t, false);
        return t;
    }

    static int fullChain(int w, int h, int d) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(w, Math.max(h, d)));
    }

    void specify(Tex t, Formats.Format f, int level, int w, int h, int d, int layers) {
        final int baseW = Math.max(1, w << level), baseH = t.target == GL11.GL_TEXTURE_1D || t.target == GL30.GL_TEXTURE_1D_ARRAY ? Math.max(1, h) : Math.max(1, h << level);
        final int baseD = t.is3D() ? Math.max(1, d << level) : 1;
        final boolean sameShape = t.hasImage() && t.format.vk() == f.vk() && t.format.glInternal() == f.glInternal() && t.width == baseW && t.height == baseH && t.depth == baseD && t.layers == layers;
        if (sameShape && level < t.levels) {
            t.definedLevels |= 1 << level;
            return;
        }
        int levels = Math.max(level + 1, 1);
        if (t.maxLevel < 1000) levels = Math.max(levels, t.maxLevel + 1);
        if (sameShape) levels = Math.max(levels, t.levels);
        levels = Math.min(levels, fullChain(baseW, baseH, baseD));
        final Tex old = sameShape ? snapshot(t) : null;
        if (!sameShape) {
            gl.images.release(t);
            t.definedLevels = 0;
        } else {
            // keep the old image alive for the copy below
            t.image = 0L;
            t.memory = null;
            t.view = 0L;
            t.attachmentViews.clear();
            gl.renderer.onImageReleased(t);
        }
        t.format = f;
        t.width = baseW;
        t.height = baseH;
        t.depth = baseD;
        t.layers = layers;
        t.levels = levels;
        gl.images.create(t, false);
        if (old != null) copyLevels(old, t);
        t.definedLevels |= 1 << level;
        gl.renderer.targetGen++;
    }

    private Tex snapshot(Tex t) {
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
        s.lastUse = t.lastUse;
        s.attachmentViews.putAll(t.attachmentViews);
        s.pendingClear = t.pendingClear;
        s.needsClear = t.needsClear;
        System.arraycopy(t.clearColor, 0, s.clearColor, 0, 4);
        s.clearDepth = t.clearDepth;
        s.clearStencil = t.clearStencil;
        return s;
    }

    private void copyLevels(Tex from, Tex to) {
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

    void texImage(GlState st, int target, int level, int internalFormat, int w, int h, int d, int format, int type, long pixels, boolean fromPbo) {
        if (isProxy(target)) {
            proxy(st, target, level, internalFormat, w, h, d);
            return;
        }
        final Tex t = getOrCreate(st.boundTexture(target), target);
        if (t == null) return;
        if (t.immutable) {
            gl.warnOnce("glTexImage on an immutable texture " + t.id);
            return;
        }
        if (w <= 0 || h <= 0 || d <= 0) return;
        int internal = internalFormat;
        if (internal == format && (internal == GL11.GL_RGBA || internal == GL11.GL_RGB || internal == GL11.GL_RED || internal == GL30.GL_RG)) {
            internal = Formats.sizedFor(format, type);
        }
        final Formats.Format f = format(internal);
        final int layers = t.isCube() ? 6 : (t.target == GL30.GL_TEXTURE_2D_ARRAY || t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY ? d : (t.target == GL30.GL_TEXTURE_1D_ARRAY ? h : 1));
        final int depth = t.is3D() ? d : 1;
        final int height = t.target == GL30.GL_TEXTURE_1D_ARRAY ? 1 : h;
        specify(t, f, level, w, height, depth, layers);
        if (pixels != 0L || fromPbo) {
            final int face = cubeFace(target);
            final int baseLayer = t.isCube() ? face : 0;
            final int layerCount = t.isCube() ? 1 : (t.target == GL30.GL_TEXTURE_2D_ARRAY || t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY || t.target == GL30.GL_TEXTURE_1D_ARRAY ? layers : 1);
            upload(st, t, level, 0, 0, 0, w, height, t.is3D() ? depth : 1, baseLayer, layerCount, format, type, pixels, fromPbo);
        }
    }

    void texStorage(GlState st, int target, int levels, int internalFormat, int w, int h, int d) {
        final Tex t = getOrCreate(st.boundTexture(target), target);
        if (t == null) return;
        storage(t, levels, internalFormat, w, h, d);
    }

    void storage(Tex t, int levels, int internalFormat, int w, int h, int d) {
        final Formats.Format f = format(internalFormat);
        gl.images.release(t);
        t.format = f;
        t.width = Math.max(1, w);
        t.height = t.target == GL30.GL_TEXTURE_1D_ARRAY ? 1 : Math.max(1, h);
        t.depth = t.is3D() ? Math.max(1, d) : 1;
        t.layers = t.isCube() ? 6 * (t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY ? Math.max(1, d / 6) : 1)
            : (t.target == GL30.GL_TEXTURE_2D_ARRAY ? Math.max(1, d) : t.target == GL30.GL_TEXTURE_1D_ARRAY ? Math.max(1, h) : 1);
        t.levels = Math.max(1, Math.min(levels, fullChain(t.width, t.height, t.depth)));
        t.immutable = true;
        t.definedLevels = (1 << t.levels) - 1;
        gl.images.create(t, false);
        gl.renderer.targetGen++;
    }

    void texSubImage(GlState st, int target, int level, int x, int y, int z, int w, int h, int d, int format, int type, long pixels, boolean fromPbo) {
        final Tex t = get(st.boundTexture(target));
        if (t == null || !t.hasImage()) return;
        subImage(st, t, target, level, x, y, z, w, h, d, format, type, pixels, fromPbo);
    }

    void subImage(GlState st, Tex t, int target, int level, int x, int y, int z, int w, int h, int d, int format, int type, long pixels, boolean fromPbo) {
        if (w <= 0 || h <= 0 || d <= 0 || level >= t.levels) return;
        if (pixels == 0L && !fromPbo) return;
        int layer = 0, layerCount = 1, zOff = 0, depth = 1;
        if (t.isCube() && t.target == GL13.GL_TEXTURE_CUBE_MAP) {
            layer = cubeFace(target);
        } else if (t.target == GL30.GL_TEXTURE_2D_ARRAY || t.target == GL40.GL_TEXTURE_CUBE_MAP_ARRAY) {
            layer = z;
            layerCount = d;
        } else if (t.target == GL30.GL_TEXTURE_1D_ARRAY) {
            layer = y;
            layerCount = h;
            y = 0;
            h = 1;
        } else if (t.is3D()) {
            zOff = z;
            depth = d;
        }
        upload(st, t, level, x, y, zOff, w, h, depth, layer, layerCount, format, type, pixels, fromPbo);
    }

    void upload(GlState st, Tex t, int level, int x, int y, int z, int w, int h, int d, int layer, int layerCount, int format, int type, long pixels, boolean fromPbo) {
        final Formats.Format f = t.format;
        long src = pixels;
        if (fromPbo) {
            final Buf pbo = gl.buffers.get(st.pixelUnpack);
            if (pbo == null || pbo.alloc == null) return;
            if (pbo.lastUse > gl.stream.completedSerial() && !pbo.hostVisible()) {
                gl.warnOnce("unpack from a device-local PBO");
                return;
            }
            if (!pbo.hostVisible()) return;
            if (pbo.lastUse > gl.stream.completedSerial() && pbo.lastUse >= gl.stream.currentSerial()) {
                // GPU writes to the PBO earlier in this recording must land first
                gl.flushAndWait(pbo.lastUse);
            }
            src = pbo.address() + pixels;
        }
        final int rows = h * Math.max(1, d) * layerCount;
        final long bytes;
        if (f.isCompressed()) {
            bytes = Formats.imageBytes(f, w, h) * Math.max(1, d) * layerCount;
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
            // each layer is a separate client image of h rows
            final PixelConvert.Store s = st.unpack;
            final int imgDepth = Math.max(1, d) * layerCount;
            if (!PixelConvert.unpack(f, format, type, src, w, h, imgDepth, s, staging)) {
                gl.warnOnce("cannot convert pixels format=0x" + Integer.toHexString(format) + " type=0x" + Integer.toHexString(type) + " into " + f.vk());
            }
        }
        gl.renderer.materialize(t);
        gl.renderer.releaseFromPass(t);
        gl.renderer.endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        final boolean full = level == 0 && x == 0 && y == 0 && z == 0 && w == t.width && h == t.height && t.levels == 1 && layerCount == t.layers;
        if (full && t.layout != VK_IMAGE_LAYOUT_UNDEFINED) {
            t.layout = VK_IMAGE_LAYOUT_UNDEFINED; // discard: the copy covers everything
        }
        gl.images.transition(cmd, t, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(stagingOffset).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource().set(f.isDepthOrStencil() ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT, level, layer, layerCount);
            region.get(0).imageOffset().set(x, y, z);
            region.get(0).imageExtent().set(w, h, Math.max(1, d));
            vkCmdCopyBufferToImage(cmd, stagingBuffer, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
        t.needsClear = false;
        t.lastUse = gl.stream.currentSerial();
        uploads++;
        uploadBytes += bytes;
    }

    void compressedTexImage(GlState st, int target, int level, int internalFormat, int w, int h, int d, long data, int size) {
        final Tex t = getOrCreate(st.boundTexture(target), target);
        if (t == null || w <= 0 || h <= 0) return;
        final Formats.Format f = format(internalFormat);
        specify(t, f, level, w, h, Math.max(1, d), t.isCube() ? 6 : 1);
        if (data != 0L) upload(st, t, level, 0, 0, 0, w, h, 1, cubeFace(target), 1, 0, 0, data, false);
    }

    void generateMipmap(Tex t) {
        if (t == null || !t.hasImage()) return;
        final int want = Math.min(fullChain(t.width, t.height, t.depth), t.maxLevel < 1000 ? t.maxLevel + 1 : 1000);
        if (t.levels < want && !t.immutable) {
            final int keepMax = t.maxLevel;
            t.maxLevel = want - 1;
            specify(t, t.format, 0, t.width, t.height, t.depth, t.layers);
            t.maxLevel = keepMax;
        }
        if (t.levels <= 1) return;
        if (!gl.images.supports(t.format.vk(), VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
            gl.warnOnce("cannot generate mipmaps for format " + t.format.vk());
            return;
        }
        gl.renderer.materialize(t);
        gl.renderer.releaseFromPass(t);
        gl.renderer.endPass();
        final VkCommandBuffer cmd = gl.stream.cmd();
        final int aspect = t.format.aspect();
        final boolean linear = gl.images.supports(t.format.vk(), VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) && !t.format.isDepthOrStencil() && !t.format.isInteger();
        // level 0 -> TRANSFER_SRC, rest -> TRANSFER_DST
        gl.images.barrier(cmd, t.image, aspect, 0, 1, t.layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, false);
        gl.images.barrier(cmd, t.image, aspect, 1, t.levels - 1, t.layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            for (int l = 1; l < t.levels; l++) {
                final VkImageBlit b = blit.get(0);
                b.srcSubresource().set(aspect, l - 1, 0, t.layers);
                b.srcOffsets(0).set(0, 0, 0);
                b.srcOffsets(1).set(t.levelWidth(l - 1), t.levelHeight(l - 1), t.levelDepth(l - 1));
                b.dstSubresource().set(aspect, l, 0, t.layers);
                b.dstOffsets(0).set(0, 0, 0);
                b.dstOffsets(1).set(t.levelWidth(l), t.levelHeight(l), t.levelDepth(l));
                vkCmdBlitImage(cmd, t.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
                gl.images.barrier(cmd, t.image, aspect, l, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, false);
            }
        }
        t.layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        t.definedLevels = (1 << t.levels) - 1;
        t.lastUse = gl.stream.currentSerial();
    }

    void copyFromFramebuffer(GlState st, Tex dst, int target, int level, int dx, int dy, int dz, int sx, int sy, int w, int h) {
        if (dst == null || !dst.hasImage() || w <= 0 || h <= 0) return;
        final Target src = gl.renderer.readTarget(st);
        final Tex s = src.color[0];
        if (s == null) return;
        // clip the source rectangle to the read buffer
        int x0 = sx, y0 = sy, x1 = sx + w, y1 = sy + h;
        final int sw = s.levelWidth(src.colorLevel[0]), sh = s.levelHeight(src.colorLevel[0]);
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
            if (s.format.vk() == dst.format.vk() || Formats.vkTexelBytes(s.format.vk()) == Formats.vkTexelBytes(dst.format.vk()) && !s.format.isDepthOrStencil() && !dst.format.isDepthOrStencil()) {
                final VkImageCopy.Buffer r = VkImageCopy.calloc(1, stack);
                r.srcSubresource().set(s.format.aspect(), src.colorLevel[0], src.colorLayer[0], 1);
                r.srcOffset().set(cx0, cy0, 0);
                r.dstSubresource().set(dst.format.aspect(), level, dstLayer, 1);
                r.dstOffset().set(dx, dy, dst.is3D() ? dz : 0);
                r.extent().set(cw, ch, 1);
                vkCmdCopyImage(cmd, s.image, srcLayout, dst.image, dstLayout, r);
            } else {
                final VkImageBlit.Buffer b = VkImageBlit.calloc(1, stack);
                b.srcSubresource().set(s.format.aspect(), src.colorLevel[0], src.colorLayer[0], 1);
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

    void copyImage(Tex src, int srcLevel, int sx, int sy, int sz, Tex dst, int dstLevel, int dx, int dy, int dz, int w, int h, int d) {
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

    boolean readImage(Tex t, int aspect, int level, int layer, int x, int y, int w, int h, int format, int type, PixelConvert.Store pack, long dst) {
        if (t == null || !t.hasImage() || w <= 0 || h <= 0) return false;
        gl.renderer.materialize(t);
        gl.renderer.releaseFromPass(t);
        gl.renderer.endPass();
        final int texel = t.format.isDepthOrStencil() ? (t.format.vk() == VK_FORMAT_D16_UNORM ? 2 : 4) : t.format.texelBytes();
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
                // stencil index: one byte per texel
                for (int row = 0; row < h; row++) {
                    for (int col = 0; col < w; col++) {
                        MemoryUtil.memPutByte(dst + (long) row * PixelConvert.rowStride(w, format, type, pack) + col, MemoryUtil.memGetByte(staging.alloc.mapped + (long) row * w + col));
                    }
                }
                return true;
            }
            return PixelConvert.pack(view, staging.alloc.mapped, w, h, format, type, pack, dst);
        } finally {
            staging.destroy(gl.gpu, gl.allocator);
        }
    }

    long storageImageView(GlState st, int unit, Target target) {
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
        int vk = t.format.vk();
        final int glFmt = st.imageFormat[unit];
        if (glFmt != 0) {
            final Formats.Format f = Formats.of(glFmt, depth24Stencil8, depth24);
            if (f != null && Formats.vkTexelBytes(f.vk()) == Formats.vkTexelBytes(vk)) vk = f.vk();
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

    void destroy() {
        for (Tex t : textures.values()) gl.images.release(t);
        for (Tex t : renderbuffers.values()) gl.images.release(t);
        textures.clear();
        renderbuffers.clear();
    }

    static boolean isProxy(int target) {
        return target == GL11.GL_PROXY_TEXTURE_1D || target == GL11.GL_PROXY_TEXTURE_2D || target == GL12.GL_PROXY_TEXTURE_3D
            || target == GL13.GL_PROXY_TEXTURE_CUBE_MAP || target == GL30.GL_PROXY_TEXTURE_2D_ARRAY || target == GL30.GL_PROXY_TEXTURE_1D_ARRAY
            || target == org.lwjgl.opengl.GL31.GL_PROXY_TEXTURE_RECTANGLE;
    }

    private void proxy(GlState st, int target, int level, int internalFormat, int w, int h, int d) {
        final Formats.Format f = Formats.of(internalFormat, depth24Stencil8, depth24);
        final int max = target == GL12.GL_PROXY_TEXTURE_3D ? gl.gpu.maxImageDimension3D : gl.gpu.maxImageDimension2D;
        final int maxLayers = gl.gpu.maxImageArrayLayers;
        final int baseW = w << level, baseH = h << level;
        final boolean depthIsLayers = target == GL30.GL_PROXY_TEXTURE_2D_ARRAY;
        final boolean fits = f != null && w >= 0 && h >= 0 && d >= 0 && baseW <= max && baseH <= max
            && (depthIsLayers ? d <= maxLayers : d <= max) && level < 16
            && gl.images.supports(f.vk(), VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT);
        st.proxyTarget = target;
        st.proxyLevel = level;
        st.proxyWidth = fits ? w : 0;
        st.proxyHeight = fits ? h : 0;
        st.proxyDepth = fits ? d : 0;
        st.proxyFormat = fits ? internalFormat : 0;
    }

    static int proxyParam(GlState st, int target, int level, int pname) {
        if (st.proxyTarget != target || st.proxyLevel != level) return 0;
        return switch (pname) {
            case GL11.GL_TEXTURE_WIDTH -> st.proxyWidth;
            case GL11.GL_TEXTURE_HEIGHT -> st.proxyHeight;
            case GL12.GL_TEXTURE_DEPTH -> st.proxyDepth;
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> st.proxyFormat;
            default -> 0;
        };
    }

    static boolean isTextureTarget(int target) {
        return GlState.targetIndex(target) >= 0 || target == GL14.GL_TEXTURE_FILTER_CONTROL || target == GL42.GL_TEXTURE_IMMUTABLE_FORMAT;
    }
}
