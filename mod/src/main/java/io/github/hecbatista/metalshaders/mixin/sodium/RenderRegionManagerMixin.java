package io.github.hecbatista.metalshaders.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * When an upload replaces a section's mesh, Sodium 0.9.2 empties that region's batch for the pass
 * (RenderRegionManager.uploadResults, three calls). The light's batch must be emptied too, or it
 * would draw the old mesh's offsets (Iris MixinRenderRegionManager, see NOTICE).
 */
@Mixin(value = RenderRegionManager.class, remap = false)
abstract class RenderRegionManagerMixin {
    @Redirect(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;clearCachedBatchFor(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V"),
            require = 3)
    private void luxium$clearBothBatches(RenderRegion region, TerrainRenderPass pass) {
        region.clearCachedBatchFor(pass);
        ((io.github.hecbatista.metalshaders.sodium.ShadowRegion) region).luxium$clearOthersFor(pass);
    }
}
