package io.github.hecbatista.metalshaders.render;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Added by mixin: what an ItemStackRenderState is showing (its item and model component), and the
 * ids an item submit was made with (step 7e, after Iris's ItemContextState and ModelStorage).
 */
public interface ItemDrawState {
    interface Shown {
        void luxium$setShown(Item item, Identifier model);

        Item luxium$item();

        Identifier luxium$model();
    }

    interface Captured {
        DrawIds.Ids luxium$ids();
    }
}
