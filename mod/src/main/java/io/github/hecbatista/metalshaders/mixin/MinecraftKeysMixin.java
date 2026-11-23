package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.gui.LuxiumKeys;
import io.github.hecbatista.metalshaders.gui.ShaderPackScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The I key (LuxiumKeys), read where the game reads its own keys (Minecraft.handleKeybinds, 26.3). */
@Mixin(Minecraft.class)
abstract class MinecraftKeysMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void luxium$keys(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        while (LuxiumKeys.SHADER_PACKS.consumeClick()) {
            mc.gui.setScreen(new ShaderPackScreen(mc.gui.screen()));
        }
    }
}
