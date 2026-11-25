package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.gui.LuxiumKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Adds Luxium's key to the game's key list (Options.keyMappings, 26.3) before options.txt is read,
 * so its binding is saved, loaded and shown in Controls. Fabric API's key mapping module does the
 * same at the same place; each adds to whatever the array holds, so both work together.
 */
@Mixin(Options.class)
abstract class OptionsKeysMixin {
    @Shadow
    @Final
    @Mutable
    public KeyMapping[] keyMappings;

    @Inject(method = "load", at = @At("HEAD"))
    private void luxium$addKeys(CallbackInfo ci) {
        if (!Arrays.asList(keyMappings).contains(LuxiumKeys.SHADER_PACKS)) {
            KeyMapping[] more = Arrays.copyOf(keyMappings, keyMappings.length + 1);
            more[keyMappings.length] = LuxiumKeys.SHADER_PACKS;
            keyMappings = more;
        }
    }
}
