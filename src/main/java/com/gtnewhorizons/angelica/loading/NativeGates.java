package com.gtnewhorizons.angelica.loading;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.metal.MetalGate;
import com.gtnewhorizons.angelica.vulkan.VulkanGate;

import java.util.List;

public final class NativeGates {
    public static final List<NativeGate> ALL = List.of(VulkanGate.GATE, MetalGate.GATE);

    private NativeGates() {}

    public static NativeGate forDriver(String driver) {
        if (BackendOptions.DRIVER_METAL.equals(driver)) return MetalGate.GATE;
        return VulkanGate.GATE;
    }

    /** The native backend asked for that can run here and will own the window, or null. */
    public static NativeGate windowOwner() {
        for (NativeGate gate : ALL) {
            if (gate.isRequested() && gate.isAvailable()) return gate;
        }
        return null;
    }
}
