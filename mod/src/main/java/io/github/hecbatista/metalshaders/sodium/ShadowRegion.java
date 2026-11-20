package io.github.hecbatista.metalshaders.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

/**
 * A Sodium RenderRegion with a second render list and a second set of draw batches, for the
 * light (step 7c; added by mixin.sodium.RenderRegionMixin, after Iris's ShadowRenderRegion).
 */
public interface ShadowRegion {
    /** Makes the light's list and batches the region's own, until {@link #luxium$swapToCamera}. */
    void luxium$swapToShadow();

    void luxium$swapToCamera();

    /** Empties the batch for one pass in the map that isn't active (an upload replaced a mesh). */
    void luxium$clearOthersFor(TerrainRenderPass pass);
}
