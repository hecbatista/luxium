package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.gui.LuxiumDebugEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Luxium's F3 line shows while F3 is open unless the player chose otherwise: a status the player
 * set is kept (the game saves it), only a missing one becomes IN_OVERLAY. As Iris's
 * MixinDebugScreenEntriesList, at the head of DebugScreenEntryList.rebuildCurrentList (26.3).
 */
@Mixin(DebugScreenEntryList.class)
abstract class DebugScreenEntryListMixin {
    @Shadow
    @Final
    private Map<Identifier, DebugScreenEntryStatus> allStatuses;

    @Inject(method = "rebuildCurrentList", at = @At("HEAD"))
    private void luxium$showByDefault(CallbackInfo ci) {
        allStatuses.putIfAbsent(LuxiumDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
    }
}
