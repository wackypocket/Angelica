package com.gtnewhorizons.angelica.mixins.early.angelica.startup;

import com.gtnewhorizons.angelica.client.LaunchEventPump;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SuppressWarnings("deprecation")
@Mixin(value = cpw.mods.fml.client.SplashProgress.class, remap = false)
public class MixinSplashProgressEventPump {

    @Shadow private static boolean enabled;

    @Inject(method = "start", at = @At("RETURN"))
    private static void angelica$pumpEventsWithoutSplash(CallbackInfo ci) {
        if (!enabled) LaunchEventPump.start();
    }

    @Inject(method = "finish", at = @At("RETURN"))
    private static void angelica$stopPumpingEvents(CallbackInfo ci) {
        LaunchEventPump.stop();
    }
}
