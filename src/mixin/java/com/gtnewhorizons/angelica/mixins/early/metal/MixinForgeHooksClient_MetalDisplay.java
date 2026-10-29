package com.gtnewhorizons.angelica.mixins.early.metal;

import com.gtnewhorizons.angelica.metal.MetalGate;
import net.minecraftforge.client.ForgeHooksClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.PixelFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.imageio.ImageIO;

@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class MixinForgeHooksClient_MetalDisplay {

    @Unique private static final Logger LOGGER = LogManager.getLogger("Angelica/Metal");

    @Shadow static int stencilBits;

    @Inject(method = "createDisplay", at = @At("HEAD"), cancellable = true)
    private static void angelicaMtl$createDisplay(CallbackInfo ci) throws LWJGLException {
        if (!MetalGate.isDeviceReady()) {
            LOGGER.warn("No usable Metal device; creating an OpenGL window instead");
            return;
        }

        ImageIO.setUseCache(false);
        stencilBits = 8;
        final PixelFormat format = new PixelFormat().withDepthBits(24).withStencilBits(stencilBits);

        if (MetalGate.createDisplay(format, null)) {
            LOGGER.info("Created Metal window");
            ci.cancel();
            return;
        }

        LOGGER.warn("Metal could not take the window; falling back to OpenGL");
        MetalGate.fallBackToGL();
    }
}
