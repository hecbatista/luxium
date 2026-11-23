package io.github.hecbatista.metalshaders.mixin.sodium;

import io.github.hecbatista.metalshaders.sodium.BlockStateIds;
import io.github.hecbatista.metalshaders.sodium.LuxiumChunkVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells LuxiumChunkVertex which block is being meshed on this thread, so every quad Sodium 0.9.2's
 * BlockRenderer.renderModel pushes carries the pack's block id (mc_Entity). Opaque and cutout
 * quads are written during renderModel (ChunkMeshBufferBuilder.push -> encoder.write). Translucent
 * quads are written later by the translucent sorter and so don't get it yet (step 6d-3).
 */
@Mixin(value = BlockRenderer.class, remap = false)
abstract class BlockRendererMixin {
    @Inject(method = "renderModel", at = @At("HEAD"))
    private void luxium$blockId(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        LuxiumChunkVertex.setBlockId(BlockStateIds.of(state));
    }

    @Inject(method = "renderModel", at = @At("RETURN"))
    private void luxium$blockIdDone(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        LuxiumChunkVertex.setBlockId(0);
    }
}
