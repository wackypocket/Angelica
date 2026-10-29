package com.gtnewhorizons.angelica.vulkan.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescriptorSetCacheTest {

    private static final long EPOCH = 3L;

    private static void fill(Program p, int slot) {
        p.cachedEpochs[slot] = EPOCH;
        p.touchCached(slot);
    }

    @Test
    void bucketsAreAlignedToTheWayCount() {
        for (long h = -1000; h < 1000; h++) {
            final int start = Program.bucketStart(h * 0x9E3779B97F4A7C15L);
            assertEquals(0, start % Program.SET_CACHE_WAYS);
            assertTrue(start >= 0 && start + Program.SET_CACHE_WAYS <= Program.SET_CACHE_SIZE);
        }
    }

    @Test
    void anEmptyOrStaleWayIsFilledFirst() {
        final Program p = new Program(1);
        final int bucket = Program.bucketStart(42L);
        assertEquals(bucket, p.evictionSlot(bucket, EPOCH));
        fill(p, bucket);
        assertEquals(bucket + 1, p.evictionSlot(bucket, EPOCH));
        fill(p, bucket + 1);
        p.cachedEpochs[bucket + 1] = EPOCH - 1;
        assertEquals(bucket + 1, p.evictionSlot(bucket, EPOCH), "a set from an older pool epoch is free");
    }

    @Test
    void theLeastRecentlyUsedWayIsEvicted() {
        final Program p = new Program(1);
        final int bucket = Program.bucketStart(42L);
        fill(p, bucket);
        fill(p, bucket + 1);
        assertEquals(bucket, p.evictionSlot(bucket, EPOCH));
        p.touchCached(bucket);
        assertEquals(bucket + 1, p.evictionSlot(bucket, EPOCH), "a hit protects its way");
        p.touchCached((bucket + Program.SET_CACHE_WAYS) % Program.SET_CACHE_SIZE);
        assertEquals(bucket + 1, p.evictionSlot(bucket, EPOCH), "use in another bucket does not affect this one");
    }
}
