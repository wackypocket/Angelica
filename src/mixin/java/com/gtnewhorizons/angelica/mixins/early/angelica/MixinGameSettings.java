package com.gtnewhorizons.angelica.mixins.early.angelica;

import com.gtnewhorizons.angelica.client.FullscreenPreference;
import com.gtnewhorizons.angelica.mixins.interfaces.IGameSettingsExt;
import com.gtnewhorizons.angelica.proxy.ClientProxy;
import net.minecraft.client.settings.GameSettings;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameSettings.class)
public class MixinGameSettings implements IGameSettingsExt {
    @Unique
    private boolean angelica$showFpsGraph = false;

    @Unique
    private final FullscreenPreference angelica$fullscreenPreference = new FullscreenPreference();

    @Shadow
    public boolean fullScreen;

    @Redirect(method = "saveOptions", at = @At(value = "FIELD", target = "Lnet/minecraft/client/settings/GameSettings;fullScreen:Z", opcode = Opcodes.GETFIELD))
    private boolean angelica$saveChosenFullscreen(GameSettings settings) {
        return angelica$fullscreenPreference.valueToSave(this.fullScreen);
    }

    @Inject(method = "setOptionValue", at = @At("RETURN"))
    private void angelica$rememberFullscreenChoice(GameSettings.Options option, int value, CallbackInfo ci) {
        if (option == GameSettings.Options.USE_FULLSCREEN) angelica$fullscreenPreference.chosenInSettings();
    }

    @Override
    public void angelica$followWindowFullscreen(boolean fullscreen) {
        angelica$fullscreenPreference.beforeWindowChange(this.fullScreen);
        this.fullScreen = fullscreen;
    }

    @Override
    public void angelica$fullscreenChosenInSettings() {
        angelica$fullscreenPreference.chosenInSettings();
    }

    @Redirect(method = "setOptionValue", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setVSyncEnabled(Z)V", remap = false))
    private void angelica$redirectVSync(boolean sync) {
        ClientProxy.toggleVSync(sync);
    }

    @Override
    public boolean angelica$showFpsGraph() {
        return this.angelica$showFpsGraph;
    }

    @Override
    public void angelica$setShowFpsGraph(boolean renderFpsGraph) {
        this.angelica$showFpsGraph = renderFpsGraph;
    }
}
