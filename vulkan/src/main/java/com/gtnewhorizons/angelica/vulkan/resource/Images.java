package com.gtnewhorizons.angelica.vulkan.resource;

import com.gtnewhorizons.angelica.glemu.resource.BuffersBase;
import com.gtnewhorizons.angelica.glemu.gl.TokenMaps;
import com.gtnewhorizons.angelica.glemu.resource.Formats;
import com.gtnewhorizons.angelica.glemu.resource.PixelFormats;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import com.gtnewhorizons.angelica.vulkan.memory.Allocation;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.nio.LongBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_IMAGE_CREATE_2D_ARRAY_COMPATIBLE_BIT;

public final class Images {
    private static final long PENDING_FREE_LIMIT = 512L << 20;

    private final VulkanGL gl;
    private long pendingFreeBytes;
    private final Int2IntOpenHashMap optimalFeatures = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap bufferFeatures = new Int2IntOpenHashMap();
    public long generation;

    public Images(VulkanGL gl) {
        this.gl = gl;
        optimalFeatures.defaultReturnValue(-1);
        bufferFeatures.defaultReturnValue(-1);
    }

    int optimalFeatures(int vkFormat) {
        int f = optimalFeatures.get(vkFormat);
        if (f == -1) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final VkFormatProperties props = VkFormatProperties.malloc(stack);
                vkGetPhysicalDeviceFormatProperties(gl.gpu.physical, vkFormat, props);
                f = props.optimalTilingFeatures();
                bufferFeatures.put(vkFormat, props.bufferFeatures());
            }
            optimalFeatures.put(vkFormat, f);
        }
        return f;
    }

    public int bufferFeatures(int vkFormat) {
        optimalFeatures(vkFormat);
        return bufferFeatures.get(vkFormat);
    }

    public boolean supports(int vkFormat, int feature) {
        return (optimalFeatures(vkFormat) & feature) == feature;
    }

    public void create(Tex t, boolean storage) {
        generation++;
        final boolean shared = t.presentShared && gl.gpu.crossFamilyPresent();
        final Formats.Format f = t.format;
        final int features = optimalFeatures(VkFormats.of(f.pf()));
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if ((features & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0) usage |= VK_IMAGE_USAGE_SAMPLED_BIT;
        if (f.isDepthOrStencil()) {
            if ((features & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) usage |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
        } else if ((features & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) != 0 && !f.isCompressed()) {
            usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        }
        if (storage && (features & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0) usage |= VK_IMAGE_USAGE_STORAGE_BIT;
        int flags = 0;
        final int imageType;
        int arrayLayers = 1;
        int depth = 1;
        if (t.is3D()) {
            imageType = VK_IMAGE_TYPE_3D;
            depth = t.depth;
            if (gl.gpu.imageView2DOn3DImage) flags |= VK_IMAGE_CREATE_2D_ARRAY_COMPATIBLE_BIT;
        } else if (t.target == GL11.GL_TEXTURE_1D || t.target == GL30.GL_TEXTURE_1D_ARRAY) {
            imageType = VK_IMAGE_TYPE_1D;
            arrayLayers = t.layers;
        } else {
            imageType = VK_IMAGE_TYPE_2D;
            arrayLayers = t.layers;
            if (t.isCube()) flags |= VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT;
        }
        // Storage images and sRGB attachments reinterpret the format. The shared exponent and small float formats ask for it to stay out of compressed
        // storage, which rewrites their texels into another form of the same value and so breaks copies between formats.
        if (storage || Formats.linearOf(t.format.pf()) != t.format.pf() || t.format.pf() == PixelFormats.PF_RGB9E5_FLOAT || t.format.pf() == PixelFormats.PF_RG11B10_FLOAT) flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .flags(flags)
                .imageType(imageType)
                .format(VkFormats.of(f.pf()))
                .mipLevels(t.levels)
                .arrayLayers(arrayLayers)
                .samples(t.samples)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(shared ? VK_SHARING_MODE_CONCURRENT : VK_SHARING_MODE_EXCLUSIVE)
                .pQueueFamilyIndices(shared ? stack.ints(gl.gpu.queueFamily, gl.gpu.presentQueueFamily) : null)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(t.width, imageType == VK_IMAGE_TYPE_1D ? 1 : t.height, depth);
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateImage(gl.gpu.device, info, null, p), "vkCreateImage");
            t.image = p.get(0);
            final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(gl.gpu.device, t.image, req);
            try {
                t.memory = gl.allocator.allocate(req, BuffersBase.USAGE_DEVICE, true);
            } catch (RuntimeException | OutOfMemoryError e) {
                vkDestroyImage(gl.gpu.device, t.image, null);
                t.image = 0L;
                throw e;
            }
            VkResults.check(vkBindImageMemory(gl.gpu.device, t.image, t.memory.memory, t.memory.offset), "vkBindImageMemory");
        }
        t.usage = usage;
        t.layout = VK_IMAGE_LAYOUT_UNDEFINED;
        t.discardedFrom = VK_IMAGE_LAYOUT_UNDEFINED;
        t.needsClear = !f.isCompressed();
        t.viewDirty = true;
        t.samplerDirty = true;
        if (t.label != null) gl.debug.name(VK_OBJECT_TYPE_IMAGE, t.image, t.label);
    }

    public void release(Tex t) {
        if (t.image == 0L) return;
        generation++;
        final long image = t.image;
        final Allocation mem = t.memory;
        final long view = t.view;
        final long[] storageViews = t.storageViews.values().toLongArray();
        final long[] views = t.attachmentViews.values().toLongArray();
        gl.renderer.forgetViews(t);
        final long bytes = mem != null ? mem.size : 0L;
        pendingFreeBytes += bytes;
        gl.stream.deferAfter(Math.max(t.lastUse, gl.stream.currentSerial()), () -> {
            if (view != 0L) vkDestroyImageView(gl.gpu.device, view, null);
            for (long v : storageViews) vkDestroyImageView(gl.gpu.device, v, null);
            for (long v : views) vkDestroyImageView(gl.gpu.device, v, null);
            vkDestroyImage(gl.gpu.device, image, null);
            gl.allocator.free(mem);
            pendingFreeBytes -= bytes;
        });
        gl.textures.clearImageHandles(t);
        t.layout = VK_IMAGE_LAYOUT_UNDEFINED;
        t.pendingClear = false;
        gl.renderer.onImageReleased(t);
        if (pendingFreeBytes >= PENDING_FREE_LIMIT) gl.flushAndWait(gl.stream.currentSerial());
    }

    public static int viewTypeFor(Tex t) {
        if (t.is3D()) return VK_IMAGE_VIEW_TYPE_3D;
        return switch (t.target) {
            case GL11.GL_TEXTURE_1D -> VK_IMAGE_VIEW_TYPE_1D;
            case GL30.GL_TEXTURE_1D_ARRAY -> VK_IMAGE_VIEW_TYPE_1D_ARRAY;
            case GL30.GL_TEXTURE_2D_ARRAY, GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY -> VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            case GL13.GL_TEXTURE_CUBE_MAP -> VK_IMAGE_VIEW_TYPE_CUBE;
            case GL40.GL_TEXTURE_CUBE_MAP_ARRAY -> VK_IMAGE_VIEW_TYPE_CUBE_ARRAY;
            default -> VK_IMAGE_VIEW_TYPE_2D;
        };
    }

    public long createSampledView(Tex t, int viewType) {
        final Formats.Format f = t.format;
        int aspect = f.aspect();
        if (aspect == Formats.ASPECT_DS) aspect = t.samplesStencil() ? VK_IMAGE_ASPECT_STENCIL_BIT : VK_IMAGE_ASPECT_DEPTH_BIT;
        int layerCount = switch (viewType) {
            case VK_IMAGE_VIEW_TYPE_2D, VK_IMAGE_VIEW_TYPE_1D, VK_IMAGE_VIEW_TYPE_3D -> 1;
            case VK_IMAGE_VIEW_TYPE_CUBE -> 6;
            default -> t.is3D() ? 1 : t.layers;
        };
        if (viewType == VK_IMAGE_VIEW_TYPE_CUBE_ARRAY) layerCount = t.layers - t.layers % 6;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(t.image)
                .viewType(viewType)
                .format(VkFormats.of(f.pf()));
            final int[] base = f.swizzle();
            info.components().set(
                swizzle(t.swizzle[0], base, 0), swizzle(t.swizzle[1], base, 1),
                swizzle(t.swizzle[2], base, 2), swizzle(t.swizzle[3], base, 3));
            if (f.isDepthOrStencil()) {
                final boolean identity = t.swizzle[0] == GL11.GL_RED && t.swizzle[1] == GL11.GL_GREEN && t.swizzle[2] == GL11.GL_BLUE && t.swizzle[3] == GL11.GL_ALPHA;
                if (identity) info.components().set(VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY);
                else info.components().set(depthSwizzle(t.swizzle[0]), depthSwizzle(t.swizzle[1]), depthSwizzle(t.swizzle[2]), depthSwizzle(t.swizzle[3]));
            }
            // RGB textures live in RGBA storage; alpha reads as 1 the way GL defines it.
            if ((f.baseFormat() == GL11.GL_RGB || f.baseFormat() == GL30.GL_RGB_INTEGER) && !f.isCompressed() && base == null) {
                if (t.swizzle[0] == GL11.GL_ALPHA) info.components().r(VK_COMPONENT_SWIZZLE_ONE);
                if (t.swizzle[1] == GL11.GL_ALPHA) info.components().g(VK_COMPONENT_SWIZZLE_ONE);
                if (t.swizzle[2] == GL11.GL_ALPHA) info.components().b(VK_COMPONENT_SWIZZLE_ONE);
                if (t.swizzle[3] == GL11.GL_ALPHA) info.components().a(VK_COMPONENT_SWIZZLE_ONE);
            }
            info.subresourceRange().set(aspect, t.viewBaseLevel(), t.viewLevelCount(), 0, Math.max(1, layerCount));
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateImageView(gl.gpu.device, info, null, p), "vkCreateImageView");
            return p.get(0);
        }
    }

    /** A depth or stencil texture reads as (value, 0, 0, 1). */
    private static int depthSwizzle(int glSwizzle) {
        return switch (glSwizzle) {
            case GL11.GL_RED -> VK_COMPONENT_SWIZZLE_R;
            case GL11.GL_ALPHA, GL11.GL_ONE -> VK_COMPONENT_SWIZZLE_ONE;
            default -> VK_COMPONENT_SWIZZLE_ZERO;
        };
    }

    private static int swizzle(int glSwizzle, int[] base, int channel) {
        final int requested = TokenMaps.swizzleSource(glSwizzle, channel);
        if (requested == TokenMaps.SWIZZLE_TO_ZERO) return VK_COMPONENT_SWIZZLE_ZERO;
        if (requested == TokenMaps.SWIZZLE_TO_ONE) return VK_COMPONENT_SWIZZLE_ONE;
        if (base != null) return VkFormats.swizzle(base[requested]);
        return switch (requested) {
            case 0 -> VK_COMPONENT_SWIZZLE_R;
            case 1 -> VK_COMPONENT_SWIZZLE_G;
            case 2 -> VK_COMPONENT_SWIZZLE_B;
            default -> VK_COMPONENT_SWIZZLE_A;
        };
    }

    private static int attachmentViewType(Tex t, int layerCount) {
        final boolean oneDimensional = t.target == GL11.GL_TEXTURE_1D || t.target == GL30.GL_TEXTURE_1D_ARRAY;
        if (layerCount > 1) return oneDimensional ? VK_IMAGE_VIEW_TYPE_1D_ARRAY : VK_IMAGE_VIEW_TYPE_2D_ARRAY;
        return oneDimensional ? VK_IMAGE_VIEW_TYPE_1D : VK_IMAGE_VIEW_TYPE_2D;
    }

    public long attachmentView(Tex t, int level, int layer, int layerCount) {
        return attachmentView(t, level, layer, layerCount, true);
    }

    public long attachmentView(Tex t, int level, int layer, int layerCount, boolean srgbWrites) {
        final long key = level | (long) layer << 8 | (long) layerCount << 32 | (srgbWrites ? 0L : 1L << 62);
        final long cached = t.attachmentViews.get(key);
        if (cached != 0L) return cached;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(t.image)
                .viewType(attachmentViewType(t, layerCount))
                .format(VkFormats.of(srgbWrites ? t.format.pf() : Formats.linearOf(t.format.pf())));
            info.subresourceRange().set(t.format.aspect(), level, 1, layer, layerCount);
            final LongBuffer p = stack.mallocLong(1);
            if (t.is3D() && !gl.gpu.imageView2DOn3DImage) {
                info.viewType(VK_IMAGE_VIEW_TYPE_3D);
                // MoltenVK has no 2D views of a 3D image without heaps. A 3D view of every level uses the image itself and draws to the base level at the depth plane the base layer names.
                if (gl.gpu.isMoltenVkOrApple()) {
                    info.subresourceRange().levelCount(t.levels);
                } else {
                    if (layer != 0) gl.warnOnce("this device cannot render to one slice of a 3D texture; using slice 0");
                    info.subresourceRange().baseArrayLayer(0).layerCount(1);
                }
            }
            VkResults.check(vkCreateImageView(gl.gpu.device, info, null, p), "vkCreateImageView");
            t.attachmentViews.put(key, p.get(0));
            return p.get(0);
        }
    }

    long storageView(Tex t, int level, int layer, boolean layered, int vkFormat) {
        final long key = level | (long) (layered ? 0 : layer) << 8 | (long) (vkFormat & 0x3FFFFFFF) << 32 | (layered ? 1L << 63 : 0L);
        final long cached = t.storageViews.get(key);
        if (cached != 0L) return cached;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int viewType = viewTypeFor(t);
            // Shaders here hold cube images as arrays of faces where the device's compiler wants that.
            if (com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler.cubeImagesAsArrays && (viewType == VK_IMAGE_VIEW_TYPE_CUBE || viewType == VK_IMAGE_VIEW_TYPE_CUBE_ARRAY)) viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            int baseLayer = 0, count = t.is3D() ? 1 : t.target == GL13.GL_TEXTURE_CUBE_MAP ? 6 : t.layers;
            if (!layered && t.is3D() && layer != 0) gl.warnOnce("Vulkan cannot bind one slice of a 3D texture as an image; binding the whole texture");
            if (!layered && !t.is3D() && (t.layers > 1 || t.target == GL13.GL_TEXTURE_CUBE_MAP)) {
                viewType = t.target == GL30.GL_TEXTURE_1D_ARRAY ? VK_IMAGE_VIEW_TYPE_1D : VK_IMAGE_VIEW_TYPE_2D;
                baseLayer = layer;
                count = 1;
            }
            final VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(t.image)
                .viewType(viewType)
                .format(vkFormat);
            info.subresourceRange().set(VK_IMAGE_ASPECT_COLOR_BIT, level, 1, baseLayer, count);
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateImageView(gl.gpu.device, info, null, p), "vkCreateImageView");
            t.storageViews.put(key, p.get(0));
            return p.get(0);
        }
    }

    int stagesFor(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> gl.renderer.shaderStages();
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_PIPELINE_STAGE_TRANSFER_BIT;
            default -> VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        };
    }

    static int accessFor(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> 0;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_ACCESS_SHADER_READ_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK_ACCESS_TRANSFER_READ_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_ACCESS_TRANSFER_WRITE_BIT;
            default -> VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        };
    }

    public record Transition(long image, int aspect, int from, int to, boolean discard) {}

    public void transition(VkCommandBuffer cmd, Tex t, int layout) {
        if (t.layout == layout && !isWriteLayout(layout)) return;
        record(cmd, retarget(t, layout));
        if (!gl.stream.isUploadCmd(cmd)) {
            final long serial = gl.stream.currentSerial();
            t.lastUse = Math.max(t.lastUse, serial);
            t.lastStateChange = serial;
        }
    }

    public Transition transitionLater(Tex t, int layout) {
        final Transition pending = retarget(t, layout);
        final long serial = gl.stream.currentSerial();
        t.lastUse = Math.max(t.lastUse, serial);
        t.lastStateChange = serial;
        return pending;
    }

    public void record(VkCommandBuffer cmd, Transition p) {
        barrier(cmd, p.image, p.aspect, 0, VK_REMAINING_MIP_LEVELS, p.from, p.to, p.discard);
    }

    private static Transition retarget(Tex t, int layout) {
        final int old = t.layout;
        final boolean discard = old == VK_IMAGE_LAYOUT_UNDEFINED;
        final Transition p = new Transition(t.image, t.format.aspect(), discard ? t.discardedFrom : old, layout, discard);
        t.layout = layout;
        t.copyRectCount = 0;
        t.discardedFrom = VK_IMAGE_LAYOUT_UNDEFINED;
        return p;
    }

    private static boolean isWriteLayout(int layout) {
        return layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL || layout == VK_IMAGE_LAYOUT_GENERAL
            || layout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL || layout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    }

    void barrier(VkCommandBuffer cmd, long image, int aspect, int baseLevel, int levelCount, int oldLayout, int newLayout, boolean discard) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(accessFor(oldLayout))
                .dstAccessMask(accessFor(newLayout))
                .oldLayout(discard ? VK_IMAGE_LAYOUT_UNDEFINED : oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
            b.subresourceRange().set(aspect, baseLevel, levelCount, 0, VK_REMAINING_ARRAY_LAYERS);
            vkCmdPipelineBarrier(cmd, stagesFor(oldLayout), stagesFor(newLayout), 0, null, null, b);
        }
    }

    public static int copySourceLayout(boolean sameImage) {
        return sameImage ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    }

    public static int copyDestinationLayout(boolean sameImage) {
        return sameImage ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    }

    /** Puts two images in the layouts a copy between them needs; one image copied onto itself stays in GENERAL. */
    public void transitionForCopy(VkCommandBuffer cmd, Tex src, Tex dst) {
        transition(cmd, src, copySourceLayout(src == dst));
        if (src != dst) transition(cmd, dst, copyDestinationLayout(false));
    }

    public static void fillClearColor(VkClearColorValue value, Tex t, float[] color) {
        final boolean integer = t.format.isInteger();
        for (int i = 0; i < 4; i++) {
            if (integer) value.int32(i, (int) color[i]);
            else value.float32(i, color[i]);
        }
    }

    public void clearImage(VkCommandBuffer cmd, Tex t, float[] color, float depth, int stencil) {
        clearImage(cmd, t, color, depth, stencil, t.format.aspect());
    }

    public void clearImage(VkCommandBuffer cmd, Tex t, float[] color, float depth, int stencil, int aspects) {
        transition(cmd, t, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).set(aspects, 0, VK_REMAINING_MIP_LEVELS, 0, VK_REMAINING_ARRAY_LAYERS);
            if (t.format.isDepthOrStencil()) {
                final VkClearDepthStencilValue v = VkClearDepthStencilValue.calloc(stack).depth(depth).stencil(stencil);
                vkCmdClearDepthStencilImage(cmd, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, v, range);
            } else {
                final VkClearColorValue v = VkClearColorValue.calloc(stack);
                fillClearColor(v, t, color);
                vkCmdClearColorImage(cmd, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, v, range);
            }
        }
        t.needsClear = false;
    }

    public static int glTargetForViewType(int viewType) {
        return switch (viewType) {
            case VK_IMAGE_VIEW_TYPE_1D -> GL11.GL_TEXTURE_1D;
            case VK_IMAGE_VIEW_TYPE_1D_ARRAY -> GL30.GL_TEXTURE_1D_ARRAY;
            case VK_IMAGE_VIEW_TYPE_2D_ARRAY -> GL30.GL_TEXTURE_2D_ARRAY;
            case VK_IMAGE_VIEW_TYPE_3D -> GL12.GL_TEXTURE_3D;
            case VK_IMAGE_VIEW_TYPE_CUBE -> GL13.GL_TEXTURE_CUBE_MAP;
            case VK_IMAGE_VIEW_TYPE_CUBE_ARRAY -> GL40.GL_TEXTURE_CUBE_MAP_ARRAY;
            default -> GL11.GL_TEXTURE_2D;
        };
    }
}
