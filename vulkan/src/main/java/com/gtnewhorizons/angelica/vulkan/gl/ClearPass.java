package com.gtnewhorizons.angelica.vulkan.gl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class ClearPass {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private final VulkanGL gl;

    ClearPass(VulkanGL gl) {
        this.gl = gl;
    }

    void maskedColorClear(GlState st, Target t, int attachment, boolean[] mask) {
        gl.warnOnce("masked color clear (mask " + mask[0] + "," + mask[1] + "," + mask[2] + "," + mask[3] + ") is not emulated yet");
    }

    void maskedStencilClear(GlState st, Target t) {
        gl.warnOnce("masked stencil clear is not emulated yet");
    }
}
