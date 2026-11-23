package io.github.hecbatista.metalshaders.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.hecbatista.metalshaders.render.DrawIds;
import io.github.hecbatista.metalshaders.render.ItemDrawState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While one layer of an item is submitted (ItemStackRenderState.LayerRenderState.submit, 26.3),
 * its item's ids are current (Iris ItemStackStateLayerMixin). The layer is an inner class, so its
 * render state is caught from the constructor, as Iris does.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
abstract class ItemLayerRenderStateMixin {
    @Unique
    private ItemStackRenderState luxium$parent;
    @Unique
    private DrawIds.Ids luxium$before;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void luxium$catchParent(ItemStackRenderState parent, CallbackInfo ci) {
        luxium$parent = parent;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void luxium$enter(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline, CallbackInfo ci) {
        ItemDrawState.Shown shown = (ItemDrawState.Shown) luxium$parent;
        luxium$before = shown == null ? DrawIds.current() : DrawIds.enterItem(shown.luxium$item(), shown.luxium$model());
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void luxium$leave(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline, CallbackInfo ci) {
        DrawIds.leaveItem(luxium$before);
        luxium$before = null;
    }
}
