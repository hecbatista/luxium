package io.github.hecbatista.metalshaders.mixin;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** BlockEntityRenderState keeps its block state private (26.3); the pack's blockEntityId is the block's id. */
@Mixin(BlockEntityRenderState.class)
public interface BlockEntityRenderStateAccessor {
    @Accessor("blockState")
    BlockState luxium$blockState();
}
