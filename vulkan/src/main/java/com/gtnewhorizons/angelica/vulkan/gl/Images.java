package com.gtnewhorizons.angelica.vulkan.gl;

import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import com.gtnewhorizons.angelica.vulkan.memory.MemoryAllocator;
import com.gtnewhorizons.angelica.vulkan.resource.Formats;
import com.gtnewhorizons.angelica.vulkan.resource.Tex;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

final class Images {
    private final VulkanGL gl;
    private final Int2IntOpenHashMap optimalFeatures = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap bufferFeatures = new Int2IntOpenHashMap();

    Images(VulkanGL gl) {
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

    int bufferFeatures(int vkFormat) {
        optimalFeatures(vkFormat);
        return bufferFeatures.get(vkFormat);
    }

    boolean supports(int vkFormat, int feature) {
        return (optimalFeatures(vkFormat) & feature) == feature;
    }

    void create(Tex t, boolean storage) {
        final Formats.Format f = t.format;
        final int features = optimalFeatures(f.vk());
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
        } else if (t.target == GL11.GL_TEXTURE_1D || t.target == GL30.GL_TEXTURE_1D_ARRAY) {
            imageType = VK_IMAGE_TYPE_1D;
            arrayLayers = t.layers;
        } else {
            imageType = VK_IMAGE_TYPE_2D;
            arrayLayers = t.layers;
            if (t.isCube()) flags |= VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT;
        }
        if (storage) flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .flags(flags)
                .imageType(imageType)
                .format(f.vk())
                .mipLevels(t.levels)
                .arrayLayers(arrayLayers)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(t.width, imageType == VK_IMAGE_TYPE_1D ? 1 : t.height, depth);
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateImage(gl.gpu.device, info, null, p), "vkCreateImage");
            t.image = p.get(0);
            final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(gl.gpu.device, t.image, req);
            try {
                t.memory = gl.allocator.allocate(req, MemoryAllocator.USAGE_DEVICE, true);
            } catch (RuntimeException | OutOfMemoryError e) {
                vkDestroyImage(gl.gpu.device, t.image, null);
                t.image = 0L;
                throw e;
            }
            VkResults.check(vkBindImageMemory(gl.gpu.device, t.image, t.memory.memory, t.memory.offset), "vkBindImageMemory");
        }
        t.usage = usage;
        t.layout = VK_IMAGE_LAYOUT_UNDEFINED;
        t.needsClear = !f.isCompressed();
        t.viewDirty = true;
        t.samplerDirty = true;
        if (t.label != null) gl.debug.name(VK_OBJECT_TYPE_IMAGE, t.image, t.label);
    }

    void release(Tex t) {
        if (t.image == 0L) return;
        final long image = t.image;
        final com.gtnewhorizons.angelica.vulkan.memory.Allocation mem = t.memory;
        final long view = t.view;
        final long storageView = t.storageView;
        final long[] views = t.attachmentViews.values().toLongArray();
        final long[] sampledViews = gl.renderer.forgetViews(t);
        gl.stream.deferAfter(Math.max(t.lastUse, gl.stream.currentSerial()), () -> {
            if (view != 0L) vkDestroyImageView(gl.gpu.device, view, null);
            if (storageView != 0L) vkDestroyImageView(gl.gpu.device, storageView, null);
            for (long v : views) vkDestroyImageView(gl.gpu.device, v, null);
            for (long v : sampledViews) vkDestroyImageView(gl.gpu.device, v, null);
            vkDestroyImage(gl.gpu.device, image, null);
            gl.allocator.free(mem);
        });
        t.image = 0L;
        t.memory = null;
        t.view = 0L;
        t.storageView = 0L;
        t.storageViewKey = -1;
        t.attachmentViews.clear();
        t.layout = VK_IMAGE_LAYOUT_UNDEFINED;
        t.pendingClear = false;
        gl.renderer.onImageReleased(t);
    }

    static int viewTypeFor(Tex t) {
        if (t.is3D()) return VK_IMAGE_VIEW_TYPE_3D;
        return switch (t.target) {
            case GL11.GL_TEXTURE_1D -> VK_IMAGE_VIEW_TYPE_1D;
            case GL30.GL_TEXTURE_1D_ARRAY -> VK_IMAGE_VIEW_TYPE_1D_ARRAY;
            case GL30.GL_TEXTURE_2D_ARRAY -> VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            case GL13.GL_TEXTURE_CUBE_MAP -> VK_IMAGE_VIEW_TYPE_CUBE;
            case GL40.GL_TEXTURE_CUBE_MAP_ARRAY -> VK_IMAGE_VIEW_TYPE_CUBE_ARRAY;
            default -> VK_IMAGE_VIEW_TYPE_2D;
        };
    }

    long createSampledView(Tex t, int viewType) {
        final Formats.Format f = t.format;
        int aspect = f.aspect();
        if (aspect == Formats.ASPECT_DS) aspect = t.sampleStencil ? VK_IMAGE_ASPECT_STENCIL_BIT : VK_IMAGE_ASPECT_DEPTH_BIT;
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
                .format(f.vk());
            final int[] base = f.swizzle();
            info.components().set(
                swizzle(t.swizzle[0], base, 0), swizzle(t.swizzle[1], base, 1),
                swizzle(t.swizzle[2], base, 2), swizzle(t.swizzle[3], base, 3));
            if (f.isDepthOrStencil()) {
                info.components().set(VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY);
            }
            if (f.baseFormat() == GL11.GL_RGB && !f.isCompressed() && base == null && t.swizzle[3] == GL11.GL_ALPHA) {
                info.components().a(VK_COMPONENT_SWIZZLE_ONE);
            }
            info.subresourceRange().set(aspect, t.viewBaseLevel(), t.viewLevelCount(), 0, Math.max(1, layerCount));
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateImageView(gl.gpu.device, info, null, p), "vkCreateImageView");
            return p.get(0);
        }
    }

    private static int swizzle(int glSwizzle, int[] base, int channel) {
        final int requested = switch (glSwizzle) {
            case GL11.GL_RED -> 0;
            case GL11.GL_GREEN -> 1;
            case GL11.GL_BLUE -> 2;
            case GL11.GL_ALPHA -> 3;
            case GL11.GL_ZERO -> -1;
            case GL11.GL_ONE -> -2;
            default -> channel;
        };
        if (requested == -1) return VK_COMPONENT_SWIZZLE_ZERO;
        if (requested == -2) return VK_COMPONENT_SWIZZLE_ONE;
        if (base != null) return base[requested];
        return switch (requested) {
            case 0 -> VK_COMPONENT_SWIZZLE_R;
            case 1 -> VK_COMPONENT_SWIZZLE_G;
            case 2 -> VK_COMPONENT_SWIZZLE_B;
            default -> VK_COMPONENT_SWIZZLE_A;
        };
    }

    long attachmentView(Tex t, int level, int layer, int layerCount) {
        final int key = level | layer << 8 | layerCount << 20;
        final long cached = t.attachmentViews.get(key);
        if (cached != 0L) return cached;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(t.image)
                .viewType(layerCount > 1 ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : (t.target == GL11.GL_TEXTURE_1D ? VK_IMAGE_VIEW_TYPE_1D : VK_IMAGE_VIEW_TYPE_2D))
                .format(t.format.vk());
            info.subresourceRange().set(t.format.aspect(), level, 1, layer, layerCount);
            final LongBuffer p = stack.mallocLong(1);
            if (t.is3D()) {
                info.viewType(VK_IMAGE_VIEW_TYPE_3D);
                info.subresourceRange().baseArrayLayer(0).layerCount(1);
            }
            VkResults.check(vkCreateImageView(gl.gpu.device, info, null, p), "vkCreateImageView");
            t.attachmentViews.put(key, p.get(0));
            return p.get(0);
        }
    }

    long storageView(Tex t, int level, int layer, boolean layered, int vkFormat) {
        final int key = level | (layered ? 0xFF : layer) << 8 | (vkFormat & 0x3FF) << 16;
        if (t.storageView != 0L && t.storageViewKey == key) return t.storageView;
        if (t.storageView != 0L) {
            final long old = t.storageView;
            gl.stream.defer(() -> vkDestroyImageView(gl.gpu.device, old, null));
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int viewType = viewTypeFor(t);
            int baseLayer = 0, count = t.is3D() ? 1 : t.layers;
            if (!layered && !t.is3D() && t.layers > 1) {
                viewType = VK_IMAGE_VIEW_TYPE_2D;
                baseLayer = layer;
                count = 1;
            } else if (viewType == VK_IMAGE_VIEW_TYPE_CUBE) {
                viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            }
            final VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(t.image)
                .viewType(viewType)
                .format(vkFormat);
            info.subresourceRange().set(VK_IMAGE_ASPECT_COLOR_BIT, level, 1, baseLayer, count);
            final LongBuffer p = stack.mallocLong(1);
            VkResults.check(vkCreateImageView(gl.gpu.device, info, null, p), "vkCreateImageView");
            t.storageView = p.get(0);
            t.storageViewKey = key;
            return t.storageView;
        }
    }

    int stagesFor(int layout, boolean src) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> src ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                | (gl.gpu.geometryShader ? VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT : 0);
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
            case org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> 0;
            default -> VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        };
    }

    void transition(VkCommandBuffer cmd, Tex t, int layout) {
        final int old = t.layout;
        if (old == layout && !isWriteLayout(layout)) return;
        barrier(cmd, t.image, t.format.aspect(), 0, VK_REMAINING_MIP_LEVELS, old, layout, discardOld(old));
        t.layout = layout;
    }

    private static boolean isWriteLayout(int layout) {
        return layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL || layout == VK_IMAGE_LAYOUT_GENERAL;
    }

    private static boolean discardOld(int old) {
        return old == VK_IMAGE_LAYOUT_UNDEFINED;
    }

    void barrier(VkCommandBuffer cmd, long image, int aspect, int baseLevel, int levelCount, int oldLayout, int newLayout, boolean discard) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(accessFor(oldLayout) & WRITE_OR_READ_MASK)
                .dstAccessMask(accessFor(newLayout))
                .oldLayout(discard ? VK_IMAGE_LAYOUT_UNDEFINED : oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
            b.subresourceRange().set(aspect, baseLevel, levelCount, 0, VK_REMAINING_ARRAY_LAYERS);
            vkCmdPipelineBarrier(cmd, stagesFor(oldLayout, true), stagesFor(newLayout, false), 0, null, null, b);
        }
    }

    private static final int WRITE_OR_READ_MASK = ~0;

    void globalBarrier(VkCommandBuffer cmd, int srcStages, int srcAccess, int dstStages, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(cmd, srcStages, dstStages, 0, b, null, null);
        }
    }

    void clearImage(VkCommandBuffer cmd, Tex t, float[] color, float depth, int stencil) {
        transition(cmd, t, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).set(t.format.aspect(), 0, VK_REMAINING_MIP_LEVELS, 0, VK_REMAINING_ARRAY_LAYERS);
            if (t.format.isDepthOrStencil()) {
                final VkClearDepthStencilValue v = VkClearDepthStencilValue.calloc(stack).depth(depth).stencil(stencil);
                vkCmdClearDepthStencilImage(cmd, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, v, range);
            } else {
                final VkClearColorValue v = VkClearColorValue.calloc(stack);
                if (t.format.kind() == Formats.K_UINT || t.format.kind() == Formats.K_SINT) {
                    for (int i = 0; i < 4; i++) v.int32(i, (int) color[i]);
                } else {
                    for (int i = 0; i < 4; i++) v.float32(i, color[i]);
                }
                vkCmdClearColorImage(cmd, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, v, range);
            }
        }
        t.needsClear = false;
    }

    static int glTargetForViewType(int viewType) {
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
