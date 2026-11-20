package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.render.ItemDrawState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The item and model an item render state shows (Iris ItemStackStateMixin). */
@Mixin(ItemStackRenderState.class)
abstract class ItemStackRenderStateMixin implements ItemDrawState.Shown {
    @Unique
    private Item luxium$item;
    @Unique
    private Identifier luxium$model;

    @Override
    public void luxium$setShown(Item item, Identifier model) {
        luxium$item = item;
        luxium$model = model;
    }

    @Override
    public Item luxium$item() {
        return luxium$item;
    }

    @Override
    public Identifier luxium$model() {
        return luxium$model;
    }
}
