package com.gtnewhorizons.angelica.vulkan.device;

import com.gtnewhorizons.angelica.glsm.backend.MoltenVK;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLError;

import static org.lwjgl.sdl.SDLHints.SDL_HINT_VULKAN_LIBRARY;
import static org.lwjgl.sdl.SDLHints.SDL_SetHint;

final class MoltenVkLoader {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");

    private MoltenVkLoader() {}

    static void pointSdlAtMoltenVk() {
        final String path = MoltenVK.locate();
        if (path == null) {
            LOG.warn("Vulkan on macOS needs MoltenVK. Add {} to the mods folder or install MoltenVK.", MoltenVK.JAR_NAME);
            return;
        }
        if (MoltenVK.source() == MoltenVK.Source.ENV) return;
        if (SDL_SetHint(SDL_HINT_VULKAN_LIBRARY, path)) {
            LOG.info("MoltenVK: {}", path);
        } else {
            LOG.warn("Could not point SDL at MoltenVK {}: {}", path, SDLError.SDL_GetError());
        }
    }
}
