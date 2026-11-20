package io.github.hecbatista.metalshaders;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entry point. Fabric calls {@link #onInitializeClient()} once, while the game starts.
 * For now it only proves the mod loads next to Metallum.
 */
public final class MetalShadersClient implements ClientModInitializer {
    public static final String MOD_ID = "metalshaders";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();

        String metallumVersion = loader.getModContainer("metallum")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("NOT FOUND");

        // Where Iris and OptiFine look for packs: a "shaderpacks" folder in the game directory.
        Path shaderpacks = loader.getGameDir().resolve("shaderpacks");

        LOGGER.info("Metal Shaders loaded. Metallum version: {}. Shader packs folder: {}",
                metallumVersion, shaderpacks);
    }
}
