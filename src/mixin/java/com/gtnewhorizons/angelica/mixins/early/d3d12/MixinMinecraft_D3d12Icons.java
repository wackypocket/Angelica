package com.gtnewhorizons.angelica.mixins.early.d3d12;

import com.gtnewhorizons.angelica.d3d12.D3d12Gate;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.nio.ByteBuffer;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft_D3d12Icons {

    @ModifyArg(method = "startGame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setIcon([Ljava/nio/ByteBuffer;)I", remap = false), index = 0)
    private ByteBuffer[] angelicaD3d12$rememberIcons(ByteBuffer[] icons) {
        D3d12Gate.rememberIcons(icons);
        return icons;
    }
}
