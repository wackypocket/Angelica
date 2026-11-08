package com.gtnewhorizons.angelica.mixins.early.nativegl;

import com.gtnewhorizons.angelica.glemu.NativeGate;
import com.gtnewhorizons.angelica.loading.NativeGates;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.nio.ByteBuffer;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft_NativeIcons {

    @ModifyArg(method = "startGame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setIcon([Ljava/nio/ByteBuffer;)I", remap = false), index = 0)
    private ByteBuffer[] angelica$rememberIcons(ByteBuffer[] icons) {
        final NativeGate gate = NativeGates.windowOwner();
        if (gate != null) gate.rememberIcons(icons);
        return icons;
    }
}
