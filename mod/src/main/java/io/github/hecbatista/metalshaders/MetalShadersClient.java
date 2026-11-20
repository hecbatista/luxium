package io.github.hecbatista.metalshaders;

import io.github.hecbatista.metalshaders.glsl.ProgramTranslator;
import io.github.hecbatista.metalshaders.glsl.Std140Layout;
import io.github.hecbatista.metalshaders.pack.PackPreprocessor;
import io.github.hecbatista.metalshaders.pack.PackReader;
import io.github.hecbatista.metalshaders.pack.StandardDefines;
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
    private static long lastLogNanos;
    private static boolean coverageChecked;

    /** Called by GameRendererMixin at the end of GameRenderer.extract, once per frame. */
    public static void onFrameExtracted(DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        GAME_UNIFORMS.update(mc, deltaTracker.getGameTimeDeltaPartialTick(false), now);
        if (mc.level == null) {
            return;
        }
        if (!coverageChecked) {
            coverageChecked = true;
            logCoverage();
        }
        if (now - lastLogNanos > 5_000_000_000L) {
            lastLogNanos = now;
            LOGGER.info("Values: {}", describe(GAME_UNIFORMS.snapshot()));
        }
    }

    /**
     * Once per session: translate one real program and report which of its block values the
     * engine can't supply yet. Uses the first pack found and its Overworld terrain program.
     */
    private static void logCoverage() {
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
            ProgramTranslator.Result program = ProgramTranslator.translate(vsh.result().text(), fsh.result().text());
            Std140Layout.Layout layout = Std140Layout.of(program.uniforms());
            Map<String, String> defaults = new HashMap<>();
            for (ProgramTranslator.Uniform u : program.uniforms()) {
                if (u.defaultValue() != null) {
                    defaults.put(u.name(), u.defaultValue());
                }
            }
            ByteBuffer bytes = ByteBuffer.allocate(layout.size());
            Set<String> missing = UniformWriter.write(layout, GAME_UNIFORMS::get, defaults, bytes);
            LOGGER.info("Coverage for {} world0/gbuffers_terrain: {} of {} block values supplied ({} bytes). Missing: {}",
                    pack.getFileName(), layout.members().size() - missing.size(), layout.members().size(),
                    layout.size(), missing);
        } catch (Exception e) {
            LOGGER.error("Coverage check failed", e);
        }
    }

    /** A short, readable line of the values worth comparing with the F3 screen. */
    private static String describe(Map<String, Object> v) {
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("worldTime", "worldDay", "moonPhase", "sunAngle", "rainStrength", "cameraPosition",
                "eyeBrightness", "isEyeInWater", "skyColor", "viewWidth", "viewHeight", "far", "frameTime",
                "frameCounter", "nightVision", "blindness")) {
            Object value = v.get(name);
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
