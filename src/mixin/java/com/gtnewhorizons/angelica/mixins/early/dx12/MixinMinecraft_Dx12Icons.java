package com.gtnewhorizons.angelica.mixins.early.dx12;

import com.gtnewhorizons.angelica.dx12.Dx12Gate;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.nio.ByteBuffer;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft_Dx12Icons {

    @ModifyArg(method = "startGame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setIcon([Ljava/nio/ByteBuffer;)I", remap = false), index = 0)
    private ByteBuffer[] angelicaMtl$rememberIcons(ByteBuffer[] icons) {
        Dx12Gate.rememberIcons(icons);
        return icons;
    }
}
