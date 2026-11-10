package com.gtnewhorizons.angelica.vulkan.memory;

import com.gtnewhorizons.angelica.glemu.resource.BuffersBase;
import com.gtnewhorizons.angelica.vulkan.device.Gpu;
import com.gtnewhorizons.angelica.vulkan.device.VkResults;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.vulkan.VK10.*;

public final class MemoryAllocator {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private static final long DEVICE_BLOCK = 128L << 20;
    private static final long HOST_BLOCK = 32L << 20;
    private static final long DEDICATED_IMAGE_BYTES = 1L << 20;

    private final Gpu gpu;
    private final VkPhysicalDeviceMemoryProperties props;
    private final List<Block>[] bufferBlocks;
    private final List<Block>[] imageBlocks;
    private final boolean largeBar;
    private final boolean unifiedBuffers;
    private long allocatedBytes;
    private long usedBytes;
    private int allocationCount;
    private final int dedicatedImageLimit;
    private Runnable reclaim;
    private boolean reclaiming;

    @SuppressWarnings("unchecked")
    public MemoryAllocator(Gpu gpu) {
        this.gpu = gpu;
        this.props = gpu.memoryProperties;
        final int n = props.memoryTypeCount();
        bufferBlocks = new List[n];
        imageBlocks = new List[n];
        for (int i = 0; i < n; i++) {
            bufferBlocks[i] = new ArrayList<>();
            imageBlocks[i] = new ArrayList<>();
        }
        boolean bar = false;
        for (int i = 0; i < n; i++) {
            final int flags = props.memoryTypes(i).propertyFlags();
            if ((flags & (VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) == (VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
                final long heap = props.memoryHeaps(props.memoryTypes(i).heapIndex()).size();
                if (heap > (1L << 30)) bar = true;
            }
        }
        largeBar = bar;
        boolean allDeviceLocal = props.memoryHeapCount() > 0;
        for (int i = 0; i < props.memoryHeapCount(); i++) {
            if ((props.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) == 0) allDeviceLocal = false;
        }
        unifiedBuffers = gpu.isMoltenVkOrApple() && allDeviceLocal;
        final long maxAllocations = gpu.maxMemoryAllocationCount > 0 ? gpu.maxMemoryAllocationCount : 4096;
        dedicatedImageLimit = (int) Math.min(Integer.MAX_VALUE, maxAllocations - maxAllocations / 4);
        LOG.info("Vulkan memory: {} types, {} heaps, resizable BAR {}, unified buffers {}", n, props.memoryHeapCount(), largeBar, unifiedBuffers);
    }

    public void setReclaim(Runnable reclaim) { this.reclaim = reclaim; }
    public long allocatedBytes() { return allocatedBytes; }

    private int findType(int typeBits, int required, int preferred, int avoided) {
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < props.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) == 0) continue;
            final int flags = props.memoryTypes(i).propertyFlags();
            if ((flags & required) != required) continue;
            int score = Integer.bitCount(flags & preferred) * 4 - Integer.bitCount(flags & avoided) * 3;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private int typeFor(int typeBits, int usage) {
        // Each usage prefers a property set and avoids another; STREAM takes a device-local host-visible type when the GPU exposes one (resizable BAR).
        int t;
        switch (usage) {
            case BuffersBase.USAGE_UPLOAD -> t = findType(typeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
            case BuffersBase.USAGE_READBACK -> t = findType(typeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, VK_MEMORY_PROPERTY_HOST_CACHED_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            case BuffersBase.USAGE_STREAM -> {
                t = largeBar ? findType(typeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, VK_MEMORY_PROPERTY_HOST_CACHED_BIT) : -1;
                if (t < 0) t = findType(typeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0, VK_MEMORY_PROPERTY_HOST_CACHED_BIT | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            }
            default -> {
                t = findType(typeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
                if (t < 0) t = findType(typeBits, 0, 0, 0);
            }
        }
        if (t < 0) throw new IllegalStateException("No Vulkan memory type for usage " + usage + " in bits 0x" + Integer.toHexString(typeBits));
        return t;
    }

    private boolean isHostVisible(int type) {
        return (props.memoryTypes(type).propertyFlags() & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;
    }

    public Allocation allocate(VkMemoryRequirements req, int usage, boolean image) {
        int type = -1;
        // On unified memory, device buffers are placed in host-visible memory so writes never need a staging copy.
        if (unifiedBuffers && !image && usage == BuffersBase.USAGE_DEVICE) {
            type = findType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0, 0);
        }
        if (type < 0) type = typeFor(req.memoryTypeBits(), usage);
        try {
            return allocate(type, req.size(), req.alignment(), image);
        } catch (OutOfMemoryError oom) {
            final Allocation reclaimed = retryAfterReclaim(type, req, image);
            if (reclaimed != null) return reclaimed;
            if (usage == BuffersBase.USAGE_STREAM || usage == BuffersBase.USAGE_DEVICE) {
                final int fallback = findType(req.memoryTypeBits(), 0, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                if (fallback >= 0 && fallback != type) {
                    LOG.warn("Vulkan memory type {} is full; placing {} bytes in type {}", type, req.size(), fallback);
                    return allocate(fallback, req.size(), req.alignment(), image);
                }
            }
            throw oom;
        }
    }

    private Allocation retryAfterReclaim(int type, VkMemoryRequirements req, boolean image) {
        if (reclaim == null || reclaiming) return null;
        reclaiming = true;
        try {
            final long before = allocatedBytes;
            reclaim.run();
            trim();
            LOG.info("Vulkan memory type {} is full; reclaimed {} MiB, retrying", type, (before - allocatedBytes) >> 20);
            return allocate(type, req.size(), req.alignment(), image);
        } catch (OutOfMemoryError stillFull) {
            return null;
        } finally {
            reclaiming = false;
        }
    }

    private Allocation allocate(int type, long size, long alignment, boolean image) {
        final boolean host = isHostVisible(type);
        final long blockSize = host ? HOST_BLOCK : DEVICE_BLOCK;
        // Allocations larger than half a block get their own block; the rest sub-allocate from per-type block lists.
        // Large images do too, so their memory goes back to the driver as soon as they are freed.
        if (size > blockSize / 2 || image && size >= DEDICATED_IMAGE_BYTES && allocationCount < dedicatedImageLimit) {
            final Block dedicated = newBlock(type, size, host, true);
            final Allocation a = dedicated.take(0, size);
            usedBytes += size;
            return a;
        }
        final List<Block> blocks = image ? imageBlocks[type] : bufferBlocks[type];
        for (int i = 0, n = blocks.size(); i < n; i++) {
            final Allocation a = blocks.get(i).tryAllocate(size, alignment);
            if (a != null) {
                usedBytes += a.size;
                return a;
            }
        }
        final Block block = newBlock(type, blockSize, host, false);
        blocks.add(block);
        final Allocation a = block.tryAllocate(size, alignment);
        if (a == null) throw new IllegalStateException("fresh block cannot hold " + size + " bytes");
        usedBytes += a.size;
        return a;
    }

    private Block newBlock(int type, long size, boolean host, boolean dedicated) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkMemoryAllocateInfo info = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .allocationSize(size)
                .memoryTypeIndex(type);
            final LongBuffer pMem = stack.mallocLong(1);
            final int res = vkAllocateMemory(gpu.device, info, null, pMem);
            if (res == VK_ERROR_OUT_OF_DEVICE_MEMORY || res == VK_ERROR_OUT_OF_HOST_MEMORY) {
                throw new OutOfMemoryError("vkAllocateMemory(" + size + ", type " + type + "): " + VkResults.name(res));
            }
            VkResults.check(res, "vkAllocateMemory");
            long mapped = 0L;
            if (host) {
                final PointerBuffer pp = stack.mallocPointer(1);
                VkResults.check(vkMapMemory(gpu.device, pMem.get(0), 0, VK_WHOLE_SIZE, 0, pp), "vkMapMemory");
                mapped = pp.get(0);
            }
            allocatedBytes += size;
            allocationCount++;
            return new Block(pMem.get(0), size, mapped, dedicated);
        }
    }

    public void free(Allocation a) {
        if (a == null || a.block == null) return;
        usedBytes -= a.size;
        final Block block = a.block;
        a.block = null;
        if (block.dedicated) {
            releaseBlock(block);
            return;
        }
        block.release(a.offset, a.size);
    }

    private void releaseBlock(Block block) {
        vkFreeMemory(gpu.device, block.memory, null);
        allocatedBytes -= block.size;
        allocationCount--;
    }

    public void trim() {
        for (List<Block>[] lists : List.of(bufferBlocks, imageBlocks)) {
            for (List<Block> blocks : lists) {
                boolean keptOne = false;
                for (int i = blocks.size() - 1; i >= 0; i--) {
                    final Block b = blocks.get(i);
                    if (!b.isEmpty()) continue;
                    if (!keptOne) {
                        keptOne = true;
                        continue;
                    }
                    blocks.remove(i);
                    releaseBlock(b);
                }
            }
        }
    }

    public void destroy() {
        for (List<Block>[] lists : List.of(bufferBlocks, imageBlocks)) {
            for (List<Block> blocks : lists) {
                for (Block b : blocks) releaseBlock(b);
                blocks.clear();
            }
        }
    }

    public String describe() {
        return String.format("%d allocations, %.1f MiB used of %.1f MiB", allocationCount, usedBytes / 1048576.0, allocatedBytes / 1048576.0);
    }

    static final class Block {
        final long memory;
        final long size;
        final long mapped;
        final boolean dedicated;
        private long[] freeOffsets = new long[16];
        private long[] freeSizes = new long[16];
        private int freeCount;

        Block(long memory, long size, long mapped, boolean dedicated) {
            this.memory = memory;
            this.size = size;
            this.mapped = mapped;
            this.dedicated = dedicated;
            if (!dedicated) {
                freeOffsets[0] = 0;
                freeSizes[0] = size;
                freeCount = 1;
            }
        }

        boolean isEmpty() {
            return freeCount == 1 && freeSizes[0] == size;
        }

        Allocation take(long offset, long length) {
            return new Allocation(this, memory, offset, length, mapped == 0L ? 0L : mapped + offset);
        }

        Allocation tryAllocate(long length, long alignment) {
            for (int i = 0; i < freeCount; i++) {
                final long start = freeOffsets[i];
                final long aligned = (start + alignment - 1) / alignment * alignment;
                final long end = start + freeSizes[i];
                if (aligned + length > end) continue;
                final long headPad = aligned - start;
                final long tail = end - (aligned + length);
                if (headPad == 0 && tail == 0) {
                    removeAt(i);
                } else if (headPad == 0) {
                    freeOffsets[i] = aligned + length;
                    freeSizes[i] = tail;
                } else if (tail == 0) {
                    freeSizes[i] = headPad;
                } else {
                    freeSizes[i] = headPad;
                    insertAt(i + 1, aligned + length, tail);
                }
                return take(aligned, length);
            }
            return null;
        }

        void release(long offset, long length) {
            int i = 0;
            while (i < freeCount && freeOffsets[i] < offset) i++;
            final boolean mergePrev = i > 0 && freeOffsets[i - 1] + freeSizes[i - 1] == offset;
            final boolean mergeNext = i < freeCount && offset + length == freeOffsets[i];
            if (mergePrev && mergeNext) {
                freeSizes[i - 1] += length + freeSizes[i];
                removeAt(i);
            } else if (mergePrev) {
                freeSizes[i - 1] += length;
            } else if (mergeNext) {
                freeOffsets[i] = offset;
                freeSizes[i] += length;
            } else {
                insertAt(i, offset, length);
            }
        }

        private void removeAt(int i) {
            System.arraycopy(freeOffsets, i + 1, freeOffsets, i, freeCount - i - 1);
            System.arraycopy(freeSizes, i + 1, freeSizes, i, freeCount - i - 1);
            freeCount--;
        }

        private void insertAt(int i, long offset, long length) {
            if (freeCount == freeOffsets.length) {
                freeOffsets = Arrays.copyOf(freeOffsets, freeCount * 2);
                freeSizes = Arrays.copyOf(freeSizes, freeCount * 2);
            }
            System.arraycopy(freeOffsets, i, freeOffsets, i + 1, freeCount - i);
            System.arraycopy(freeSizes, i, freeSizes, i + 1, freeCount - i);
            freeOffsets[i] = offset;
            freeSizes[i] = length;
            freeCount++;
        }
    }
}
