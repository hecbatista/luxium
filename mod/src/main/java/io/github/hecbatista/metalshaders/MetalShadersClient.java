package io.github.hecbatista.metalshaders;

import io.github.hecbatista.metalshaders.pack.PackReader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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

        LOGGER.info("Luxium loaded. Metallum version: {}. Shader packs folder: {}",
                metallumVersion, shaderpacks);

        logPacks(shaderpacks);
    }

    /** Step 2: find every pack and log its layout. Doesn't load or draw anything. */
    private static void logPacks(Path shaderpacks) {
        List<Path> packs;
        try {
            packs = PackReader.findPacks(shaderpacks);
        } catch (IOException e) {
            LOGGER.error("Couldn't list the shader packs folder", e);
            return;
        }
        LOGGER.info("Found {} shader pack(s)", packs.size());
        for (Path pack : packs) {
            try {
                LOGGER.info(PackReader.read(pack).summary());
            } catch (IOException | RuntimeException e) {
                // One broken pack must not stop the game from starting.
                LOGGER.error("Couldn't read shader pack {}: {}", pack.getFileName(), e.toString());
            }
        }
    }
}
