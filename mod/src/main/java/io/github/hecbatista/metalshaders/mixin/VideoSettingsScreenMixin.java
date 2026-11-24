package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.gui.ShaderPackScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A "Shader Packs..." button in Video Settings, right under the graphics preset (where Iris puts
 * its own). VideoSettingsScreen.addOptions (26.3) adds the preset with the second
 * OptionsList.addBig(OptionInstance) call; the button goes in with addBig(AbstractWidget).
 */
@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At(value = "INVOKE", ordinal = 1, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/gui/components/OptionsList;addBig(Lnet/minecraft/client/OptionInstance;)V"))
    private void luxium$shaderPacksButton(CallbackInfo ci) {
        if (this.list != null) {
            this.list.addBig(Button.builder(Component.translatable("luxium.shaderPacks.button"),
                    b -> this.minecraft.gui.setScreen(new ShaderPackScreen(this))).build());
        }
    }
}
