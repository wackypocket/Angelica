package com.gtnewhorizons.angelica.mixins.early.angelica.lighting;

import com.gtnewhorizons.angelica.rendering.lighting.RelightBudget;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a client chunk's relight checks, between two of its up to eight steps, once the tick's budget is spent. A step
 * next to lava or another light source can flood-fill light for fifteen blocks; the chunk's counter keeps its place.
 */
@Mixin(Chunk.class)
public abstract class MixinChunk_RelightBudget {

    @Shadow
    public World worldObj;

    @Inject(method = "enqueueRelightChecks", cancellable = true,
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/chunk/Chunk;queuedLightChecks:I", ordinal = 0))
    private void angelica$stopWhenOverBudget(CallbackInfo ci) {
        if (this.worldObj.isRemote && RelightBudget.exhausted(System.nanoTime())) ci.cancel();
    }
}
