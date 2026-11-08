package com.gtnewhorizons.angelica.vulkan;

import com.gtnewhorizons.angelica.glemu.NativeLwjglService;

public final class VulkanLWJGLService extends NativeLwjglService {
    public static VulkanLWJGLService create() {
        return create(VulkanGate.GATE, VulkanLWJGLService.class, VulkanLWJGLService::new);
    }
}
