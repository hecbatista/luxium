package io.github.hecbatista.metalshaders;

import io.github.hecbatista.metalshaders.glsl.ProgramTranslator;
import io.github.hecbatista.metalshaders.glsl.Std140Layout;
import io.github.hecbatista.metalshaders.pack.ItemIds;
import io.github.hecbatista.metalshaders.pack.PackDirectives;
import io.github.hecbatista.metalshaders.pack.PackPreprocessor;
import io.github.hecbatista.metalshaders.pack.PackReader;
import io.github.hecbatista.metalshaders.pack.PropertiesPreprocessor;
import io.github.hecbatista.metalshaders.pack.StandardDefines;
import io.github.hecbatista.metalshaders.render.FinalPass;
import io.github.hecbatista.metalshaders.uniform.BiomeIds;
import io.github.hecbatista.metalshaders.uniform.CustomUniforms;
import io.github.hecbatista.metalshaders.uniform.GameUniforms;
import io.github.hecbatista.metalshaders.uniform.UniformWriter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point. Fabric calls {@link #onInitializeClient()} once, while the game starts.
 * It lists the shader packs at startup and, every frame, updates the values packs read
 * (logging some of them every 5 seconds). Nothing is drawn yet.
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

    // ---- Step 5: game values, updated once per frame ----------------------------------------

    private static final GameUniforms GAME_UNIFORMS = new GameUniforms();
    private static CustomUniforms customUniforms;
    private static long lastLogNanos;
    private static boolean packLoaded;

    /** Called by GameRendererMixin at the end of GameRenderer.extract, once per frame. */
    public static void onFrameExtracted(DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        GAME_UNIFORMS.update(mc, deltaTracker.getGameTimeDeltaPartialTick(false), now);
        if (mc.level == null) {
            return;
        }
        if (!packLoaded) {
            packLoaded = true;
            loadPackValues();
        }
        if (customUniforms != null) {
            customUniforms.update(GAME_UNIFORMS::get, (Float) GAME_UNIFORMS.get("frameTime"));
        }
        if (now - lastLogNanos > 5_000_000_000L) {
            lastLogNanos = now;
            LOGGER.info("Values: {}", describe());
        }
    }

    /** Every value a pack can read this frame: its own formulas first, then the game's. */
    public static Object value(String name) {
        Object own = customUniforms == null ? null : customUniforms.get(name);
        return own != null ? own : GAME_UNIFORMS.get(name);
    }

    /**
     * Once per session, for the first pack found: load its formulas from shaders.properties,
     * then translate its Overworld terrain program and report which block values are still
     * missing.
     */
    private static void loadPackValues() {
        try {
            List<Path> packs = PackReader.findPacks(FabricLoader.getInstance().getGameDir().resolve("shaderpacks"));
            if (packs.isEmpty()) {
                return;
            }
            Path pack = packs.getFirst();
            var defines = StandardDefines.forMac();
            var vsh = PackPreprocessor.preprocessProgram(pack, "/world0/gbuffers_terrain.vsh", defines);
            var fsh = PackPreprocessor.preprocessProgram(pack, "/world0/gbuffers_terrain.fsh", defines);
            if (!vsh.ok() || !fsh.ok()) {
                LOGGER.warn("Coverage check skipped: {}", vsh.ok() ? fsh.error() : vsh.error());
                return;
            }

            // wetnessHalflife / drynessHalflife, in Iris's reading order (its bug depends on order).
            List<Map.Entry<String, Float>> halfLives = PackDirectives.wetnessHalfLives(pack, "world0", defines);
            GAME_UNIFORMS.setWetnessDirectives(halfLives);

            Map<String, String> propertyDefines = new LinkedHashMap<>(defines);
            propertyDefines.putAll(BiomeIds.defines());
            String properties = PackReader.open(pack, (name, root, shaders) ->
                    PackReader.readText(shaders, "/shaders.properties").orElse(""));
            String items = PackReader.open(pack, (name, root, shaders) ->
                    PackReader.readText(shaders, "/item.properties").orElse(""));
            ItemIds itemIds = ItemIds.parse(PropertiesPreprocessor.preprocess(items, propertyDefines));
            GAME_UNIFORMS.setItemIds(itemIds);
            LOGGER.info("item.properties: {} items numbered, {} entries skipped (block states aren't supported, as in Iris)",
                    itemIds.size(), itemIds.skipped().size());
            customUniforms = CustomUniforms.load(PropertiesPreprocessor.preprocess(properties, propertyDefines),
                    GAME_UNIFORMS.snapshot().keySet());
            customUniforms.update(GAME_UNIFORMS::get, (Float) GAME_UNIFORMS.get("frameTime"));
            // Step 6b: the pack's final program.
            var finalVsh = PackPreprocessor.preprocessProgram(pack, "/world0/final.vsh", defines);
            var finalFsh = PackPreprocessor.preprocessProgram(pack, "/world0/final.fsh", defines);
            if (finalVsh.ok() && finalFsh.ok()) {
                FinalPass.prepare(finalVsh.result().text(), finalFsh.result().text());
            } else {
                LOGGER.warn("Pack final program skipped: {}", finalVsh.ok() ? finalFsh.error() : finalVsh.error());
            }

            LOGGER.info("Pack formulas: {} loaded, {} dropped: {} (wetness half-lives from pack: {})",
                    customUniforms.size(), customUniforms.dropped().size(), customUniforms.dropped(), halfLives);

            ProgramTranslator.Result program = ProgramTranslator.translate(vsh.result().text(), fsh.result().text());
            Std140Layout.Layout layout = Std140Layout.of(program.uniforms());
            Map<String, String> defaults = new HashMap<>();
            for (ProgramTranslator.Uniform u : program.uniforms()) {
                if (u.defaultValue() != null) {
                    defaults.put(u.name(), u.defaultValue());
                }
            }
            ByteBuffer bytes = ByteBuffer.allocate(layout.size());
            Set<String> missing = new java.util.TreeSet<>(UniformWriter.write(layout, MetalShadersClient::value, defaults, bytes));
            Set<String> notInIris = new java.util.TreeSet<>(missing);
            notInIris.retainAll(GameUniforms.NOT_SUPPLIED_BY_IRIS);
            missing.removeAll(notInIris);
            LOGGER.info("Coverage for {} world0/gbuffers_terrain: {} of {} block values supplied ({} bytes). "
                            + "Missing: {}. Zero under Iris too: {}",
                    pack.getFileName(), layout.members().size() - missing.size() - notInIris.size(),
                    layout.members().size(), layout.size(), missing, notInIris);
        } catch (Exception e) {
            LOGGER.error("Coverage check failed", e);
        }
    }

    /** A short, readable line of the values worth comparing with the F3 screen. */
    private static String describe() {
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("worldTime", "sunAngle", "rainStrength", "wetness", "cameraPosition",
                "eyeBrightness", "isEyeInWater", "biome", "biome_precipitation", "frameTime",
                "eyePosition", "relativeEyePosition", "playerLookVector", "cloudHeight", "isElytraFlying",
                "heavyFog", "heldItemId", "heldBlockLightValue", "heldItemId2", "lightningBoltPosition",
                // the pack's own formulas:
                "eyeBrightnessM", "isEyeInCave", "inDry", "inRainy", "inSnowy", "rainFactor", "frameTimeSmooth",
                "starter", "framemod8")) {
            Object value = value(name);
            String text = value instanceof float[] f ? Arrays.toString(f)
                    : value instanceof int[] i ? Arrays.toString(i) : String.valueOf(value);
            sb.append(name).append('=').append(text).append(' ');
        }
        return sb.toString().strip();
    }

    // ---- Step 2: list packs at startup ------------------------------------------------------

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
