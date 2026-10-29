package com.gtnewhorizons.angelica.vulkan.util;

import com.gtnewhorizons.angelica.vulkan.gl.VulkanGL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDebugUtilsLabelEXT;
import org.lwjgl.vulkan.VkDebugUtilsObjectNameInfoEXT;

public final class DebugNames {
    private final VulkanGL gl;
    private final boolean enabled;

    public DebugNames(VulkanGL gl, boolean enabled) {
        this.gl = gl;
        this.enabled = enabled && gl.gpu.debugUtils;
    }

    public void name(int type, long handle, String name) {
        if (!enabled || handle == 0L || name == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkDebugUtilsObjectNameInfoEXT info = VkDebugUtilsObjectNameInfoEXT.calloc(stack).sType$Default()
                .objectType(type).objectHandle(handle).pObjectName(stack.UTF8(name));
            EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(gl.gpu.device, info);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void push(String label) {
        if (!enabled) return;
        final VkCommandBuffer cmd = gl.stream.cmd();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            EXTDebugUtils.vkCmdBeginDebugUtilsLabelEXT(cmd, VkDebugUtilsLabelEXT.calloc(stack).sType$Default().pLabelName(stack.UTF8(label)));
        }
        depth++;
    }

    public void pop() {
        if (!enabled || depth == 0) return;
        EXTDebugUtils.vkCmdEndDebugUtilsLabelEXT(gl.stream.cmd());
        depth--;
    }

    public void closeAll() {
        while (enabled && depth > 0) pop();
    }

    int depth;
}
