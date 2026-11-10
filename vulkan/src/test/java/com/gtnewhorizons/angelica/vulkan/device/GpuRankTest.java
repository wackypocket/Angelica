package com.gtnewhorizons.angelica.vulkan.device;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_CPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;

class GpuRankTest {
    private static final Gpu.Candidate LAVAPIPE = new Gpu.Candidate(null, "llvmpipe (LLVM 19.1.1, 256 bits)", VK_PHYSICAL_DEVICE_TYPE_CPU, 0);
    private static final Gpu.Candidate INTEGRATED = new Gpu.Candidate(null, "Intel(R) UHD Graphics 630", VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU, 1);
    private static final Gpu.Candidate DISCRETE = new Gpu.Candidate(null, "NVIDIA GeForce RTX 3070", VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU, 2);
    private static final List<Gpu.Candidate> ALL = List.of(LAVAPIPE, INTEGRATED, DISCRETE);

    @Test
    void theBestTypeIsTriedFirstAndTheRestFollowAsFallbacks() {
        assertEquals(List.of(DISCRETE, INTEGRATED), Gpu.rank(ALL, "", false));
    }

    @Test
    void aSoftwareDeviceOnlyTakesPartWhenAllowed() {
        assertEquals(List.of(), Gpu.rank(List.of(LAVAPIPE), "", false));
        assertEquals(List.of(DISCRETE, INTEGRATED, LAVAPIPE), Gpu.rank(ALL, "", true));
    }

    @Test
    void aNamedDeviceComesFirstEvenWhenItIsSoftware() {
        assertEquals(List.of(INTEGRATED, DISCRETE), Gpu.rank(ALL, "intel", false));
        assertEquals(List.of(LAVAPIPE, DISCRETE, INTEGRATED), Gpu.rank(ALL, "0", false));
    }

    @Test
    void anIndexWinsOverANameContainingTheSameDigit() {
        final Gpu.Candidate gtx = new Gpu.Candidate(null, "NVIDIA GeForce GTX 1660", VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU, 0);
        final Gpu.Candidate uhd = new Gpu.Candidate(null, "Intel(R) UHD Graphics 630", VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU, 1);
        assertEquals(List.of(uhd, gtx), Gpu.rank(List.of(gtx, uhd), "1", false));
        assertEquals(List.of(gtx, uhd), Gpu.rank(List.of(gtx, uhd), "1660", false));
    }

    @Test
    void aNameThatMatchesNothingRanksAsUsual() {
        assertEquals(List.of(DISCRETE, INTEGRATED), Gpu.rank(ALL, "radeon", false));
    }
}
