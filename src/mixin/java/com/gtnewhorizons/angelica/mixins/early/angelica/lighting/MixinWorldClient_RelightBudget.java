package com.gtnewhorizons.angelica.mixins.early.angelica.lighting;

import com.gtnewhorizons.angelica.rendering.lighting.RelightBudget;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldClient.class)
public abstract class MixinWorldClient_RelightBudget {

    @Inject(method = "tick", at = @At("HEAD"))
    private void angelica$startRelightBudget(CallbackInfo ci) {
        RelightBudget.beginTick(System.nanoTime());
    }
}
