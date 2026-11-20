package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.render.ItemDrawState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes which item a render state shows (Iris MixinItemRenderer on ItemModelResolver.appendItemLayers). */
@Mixin(ItemModelResolver.class)
abstract class ItemModelResolverMixin {
    @Inject(method = "appendItemLayers", at = @At("HEAD"))
    private void luxium$noteItem(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext, Level level,
                                 ItemOwner owner, int seed, CallbackInfo ci) {
        ItemDrawState.Shown shown = (ItemDrawState.Shown) output;
        if (item != null) {
            shown.luxium$setShown(item.getItem(), item.get(DataComponents.ITEM_MODEL));
        } else {
            shown.luxium$setShown(null, null);
        }
    }
}
