package com.gtnewhorizons.angelica.vulkan.device;

import com.gtnewhorizons.angelica.glemu.NativeDrawable;
import com.gtnewhorizons.angelica.vulkan.VulkanGate;

public final class VkDrawable extends NativeDrawable {
    public VkDrawable() {
        super(VulkanGate.GATE::isActive);
    }
}
