package io.github.hecbatista.metalshaders.mixin.sodium;

import io.github.hecbatista.metalshaders.sodium.BlockStateIds;
import io.github.hecbatista.metalshaders.sodium.LuxiumChunkVertex;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fluids are meshed by Sodium 0.9.2's DefaultFluidRenderer, not BlockRenderer. Their quads carry
 * the id of the fluid's own block (water, lava) with Iris's fluid bit set, so a waterlogged
 * block's water reads as water.
 */
@Mixin(value = DefaultFluidRenderer.class, remap = false)
abstract class DefaultFluidRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void luxium$fluidId(LevelSlice level, BlockState blockState, FluidState fluidState, BlockPos blockPos, BlockPos offset,
                                TranslucentGeometryCollector collector, ChunkModelBuilder meshBuilder, Material material,
                                ColorProvider<FluidState> colorProvider, FluidModel sprites, CallbackInfo ci) {
        LuxiumChunkVertex.setBlockId(BlockStateIds.ofFluid(fluidState.createLegacyBlock()));
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void luxium$fluidIdDone(LevelSlice level, BlockState blockState, FluidState fluidState, BlockPos blockPos, BlockPos offset,
                                    TranslucentGeometryCollector collector, ChunkModelBuilder meshBuilder, Material material,
                                    ColorProvider<FluidState> colorProvider, FluidModel sprites, CallbackInfo ci) {
        LuxiumChunkVertex.setBlockId(0);
    }
}
