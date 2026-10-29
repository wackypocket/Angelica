package com.gtnewhorizons.angelica.mixins.early.dx12;

import com.gtnewhorizons.angelica.dx12.Dx12Gate;
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
public abstract class MixinForgeHooksClient_Dx12Display {

    @Unique private static final Logger LOGGER = LogManager.getLogger("Angelica/D3D12");

    @Shadow static int stencilBits;

    @Inject(method = "createDisplay", at = @At("HEAD"), cancellable = true)
    private static void angelicaMtl$createDisplay(CallbackInfo ci) throws LWJGLException {
        if (!Dx12Gate.isDeviceReady()) {
            LOGGER.warn("No usable Direct3D 12 device; creating an OpenGL window instead");
            return;
        }

        ImageIO.setUseCache(false);
        stencilBits = 8;
        final PixelFormat format = new PixelFormat().withDepthBits(24).withStencilBits(stencilBits);

        if (Dx12Gate.createDisplay(format, null)) {
            LOGGER.info("Created Direct3D 12 window");
            ci.cancel();
            return;
        }

        LOGGER.warn("Direct3D 12 could not take the window; falling back to OpenGL");
        Dx12Gate.fallBackToGL();
    }
}
