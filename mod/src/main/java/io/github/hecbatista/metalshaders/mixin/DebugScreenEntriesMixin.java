package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.gui.LuxiumDebugEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers Luxium's F3 line with the game's own debug entries (DebugScreenEntries, 26.3), as Iris does (MixinDebugEntries). */
@Mixin(DebugScreenEntries.class)
abstract class DebugScreenEntriesMixin {
    @Shadow
    private static Identifier register(Identifier identifier, DebugScreenEntry entry) {
        throw new AssertionError();
    }

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void luxium$register(CallbackInfo ci) {
        register(LuxiumDebugEntry.ID, new LuxiumDebugEntry());
    }
}
