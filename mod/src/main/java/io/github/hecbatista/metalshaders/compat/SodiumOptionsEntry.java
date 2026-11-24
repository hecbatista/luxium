package io.github.hecbatista.metalshaders.compat;

import io.github.hecbatista.metalshaders.gui.ShaderPackScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Sodium 0.9.2 replaces the game's Video Settings with its own screen (its OptionsScreenMixin), so
 * a button added to the vanilla one is never seen. This puts a "Shader Packs" page in Sodium's
 * screen instead, through Sodium's config API ({@code sodium:config_api_user} entrypoint), the way
 * Iris 26.3 adds its own (IrisConfig: an external page that opens its Shader Packs screen).
 */
public final class SodiumOptionsEntry implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        String version = FabricLoader.getInstance().getModContainer("luxium")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
        builder.registerOwnModOptions()
                .setName("Luxium")
                .setVersion(version)
                .setNonTintedIcon(Identifier.fromNamespaceAndPath("luxium", "icon.png"))
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xFFB57CF2))
                .addPage(builder.createExternalPage()
                        .setName(Component.translatable("luxium.shaderPacks.title"))
                        .setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(new ShaderPackScreen(parent))));
    }
}
