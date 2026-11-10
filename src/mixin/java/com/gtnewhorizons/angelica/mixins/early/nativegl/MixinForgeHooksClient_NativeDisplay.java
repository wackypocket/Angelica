package com.gtnewhorizons.angelica.mixins.early.nativegl;

import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.loading.NativeGates;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class MixinForgeHooksClient_NativeDisplay {

    @Shadow static int stencilBits;

    @Inject(method = "createDisplay", at = @At("HEAD"), cancellable = true)
    private static void angelica$createDisplay(CallbackInfo ci) {
        final NativeGate gate = NativeGates.windowOwner();
        if (gate != null && gate.takeWindow(8)) {
            stencilBits = 8;
            ci.cancel();
        }
    }
}
