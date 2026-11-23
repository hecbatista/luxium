package io.github.hecbatista.metalshaders.mixin.sodium;

import io.github.hecbatista.metalshaders.sodium.ShadowRegion;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Each Sodium 0.9.2 region keeps one render list and one batch per terrain pass (RenderRegion,
 * decompiled). The light's walk needs its own of both, as Iris's MixinRenderRegion gives it (see
 * NOTICE): a list of its own, because the list resets only on the first walk of a frame; batches of
 * their own, because a batch is kept until its list changes, so the light would otherwise draw the
 * camera's batch (Vitrail sky-and-shadows.md). Whatever empties a batch empties all three maps.
 */
@Mixin(value = RenderRegion.class, remap = false)
abstract class RenderRegionMixin implements ShadowRegion {
    @Shadow
    @Final
    @Mutable
    private ChunkRenderList renderList;

    @Shadow
    @Final
    @Mutable
    private Map<TerrainRenderPass, MultiDrawBatch> cachedBatches;

    @Unique
    private ChunkRenderList luxium$otherList;
    @Unique
    private Map<TerrainRenderPass, MultiDrawBatch> luxium$otherBatches;
    @Unique
    private boolean luxium$shadowActive;

    @Override
    public void luxium$swapToShadow() {
        if (!luxium$shadowActive) {
            luxium$swap();
            luxium$shadowActive = true;
        }
    }

    @Override
    public void luxium$swapToCamera() {
        if (luxium$shadowActive) {
            luxium$swap();
            luxium$shadowActive = false;
        }
    }

    @Unique
    private void luxium$swap() {
        if (luxium$otherList == null) {
            luxium$otherList = new ChunkRenderList((RenderRegion) (Object) this);
            luxium$otherBatches = new Reference2ReferenceOpenHashMap<>();
        }
        ChunkRenderList list = renderList;
        renderList = luxium$otherList;
        luxium$otherList = list;
        Map<TerrainRenderPass, MultiDrawBatch> batches = cachedBatches;
        cachedBatches = luxium$otherBatches;
        luxium$otherBatches = batches;
    }

    @Unique
    private void luxium$clearOtherBatches(TerrainRenderPass pass) {
        if (luxium$otherBatches == null) {
            return;
        }
        if (pass == null) {
            luxium$otherBatches.values().forEach(MultiDrawBatch::clear);
        } else {
            MultiDrawBatch batch = luxium$otherBatches.get(pass);
            if (batch != null) {
                batch.clear();
            }
        }
    }

    @Redirect(method = {"onGeometryBufferChange", "onGeometrySegmentChange", "update"}, at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;clearAllCachedBatches()V"))
    private void luxium$clearAll(RenderRegion region) {
        region.clearAllCachedBatches();
        luxium$clearOtherBatches(null);
    }

    @Redirect(method = {"onIndexBufferChange", "onIndexSegmentChange"}, at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;clearCachedBatchFor(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V"))
    private void luxium$clearFor(RenderRegion region, TerrainRenderPass pass) {
        region.clearCachedBatchFor(pass);
        luxium$clearOtherBatches(pass);
    }

    /** Called by RenderRegionManagerMixin when an upload replaces a section's mesh. */
    @Override
    public void luxium$clearOthersFor(TerrainRenderPass pass) {
        luxium$clearOtherBatches(pass);
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void luxium$delete(CallbackInfo ci) {
        if (luxium$otherBatches != null) {
            luxium$otherBatches.values().forEach(MultiDrawBatch::delete);
            luxium$otherBatches.clear();
        }
    }
}
