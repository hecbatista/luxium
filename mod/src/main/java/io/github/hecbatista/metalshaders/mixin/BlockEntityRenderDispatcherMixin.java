package io.github.hecbatista.metalshaders.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.hecbatista.metalshaders.render.DrawIds;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a block entity is submitted (BlockEntityRenderDispatcher.submit, 26.3), its pack blockEntityId is current. */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "submit", at = @At("HEAD"))
    private void luxium$enter(final CallbackInfo ci, @Local(argsOnly = true) final BlockEntityRenderState state) {
        DrawIds.enterBlockEntity(((BlockEntityRenderStateAccessor) state).luxium$blockState());
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void luxium$leave(final CallbackInfo ci) {
        DrawIds.leave();
    }
}
