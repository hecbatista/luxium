package io.github.hecbatista.metalshaders.mixin;

import io.github.hecbatista.metalshaders.uniform.BiomeIds;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Numbers the vanilla biomes in the order {@code Biomes} registers them, which is how Iris
 * numbers them for the {@code biome} uniform and the {@code BIOME_*} names (Iris MixinBiomes).
 */
@Mixin(Biomes.class)
abstract class BiomesMixin {
    @Inject(method = "register", at = @At("TAIL"))
    private static void luxium$numberBiome(final String name, final CallbackInfoReturnable<ResourceKey<Biome>> cir) {
        BiomeIds.add(cir.getReturnValue());
    }
}
