package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.hecbatista.metalshaders.render.DrawIds;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While an entity is submitted (EntityRenderDispatcher.submit, 26.3), its pack entityId is current (DrawIds). */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Inject(method = "submit", at = @At("HEAD"))
    private void luxium$enter(final CallbackInfo ci, @Local(argsOnly = true) final EntityRenderState state) {
        DrawIds.enterEntity(state.entityType);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void luxium$leave(final CallbackInfo ci) {
        DrawIds.leave();
    }
}
