package com.gtnewhorizons.angelica.vulkan.device;

import com.gtnewhorizons.angelica.config.SystemProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkLayerSettingEXT;
import org.lwjgl.vulkan.VkLayerSettingsCreateInfoEXT;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.EXTLayerSettings.VK_LAYER_SETTING_TYPE_BOOL32_EXT;
import static org.lwjgl.vulkan.EXTLayerSettings.VK_LAYER_SETTING_TYPE_INT32_EXT;

final class MoltenVkSettings {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");
    private static final String LAYER = "MoltenVK";

    private MoltenVkSettings() {}

    private record Value(int type, int value) {}

    static VkLayerSettingsCreateInfoEXT build(MemoryStack stack) {
        final Map<String, Value> settings = new LinkedHashMap<>();
        settings.put("MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS", bool(!SystemProperties.MOLTENVK_ASYNC_SUBMIT));
        settings.put("MVK_CONFIG_SHOULD_MAXIMIZE_CONCURRENT_COMPILATION", bool(true));
        if (!SystemProperties.MOLTENVK_ARGUMENT_BUFFERS) settings.put("MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS", new Value(VK_LAYER_SETTING_TYPE_INT32_EXT, 0));

        final VkLayerSettingEXT.Buffer out = VkLayerSettingEXT.calloc(settings.size(), stack);
        final StringBuilder log = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Value> e : settings.entrySet()) {
            final ByteBuffer value = stack.malloc(4);
            value.putInt(0, e.getValue().value);
            out.get(i++)
                .pLayerName(stack.UTF8(LAYER))
                .pSettingName(stack.UTF8(e.getKey()))
                .type(e.getValue().type)
                .valueCount(1)
                .pValues(value);
            if (log.length() > 0) log.append(", ");
            log.append(e.getKey()).append('=').append(e.getValue().value);
        }
        LOG.info("MoltenVK settings: {}", log);
        return VkLayerSettingsCreateInfoEXT.calloc(stack).sType$Default().pSettings(out);
    }

    private static Value bool(boolean b) {
        return new Value(VK_LAYER_SETTING_TYPE_BOOL32_EXT, b ? 1 : 0);
    }
}
