package io.github.hecbatista.metalshaders;

import io.github.hecbatista.metalshaders.glsl.ProgramTranslator;
import io.github.hecbatista.metalshaders.glsl.Std140Layout;
import io.github.hecbatista.metalshaders.pack.ItemIds;
import io.github.hecbatista.metalshaders.pack.OptionDefaults;
import io.github.hecbatista.metalshaders.pack.RenderTargetPlan;
import io.github.hecbatista.metalshaders.pack.PackDirectives;
import io.github.hecbatista.metalshaders.pack.PackPreprocessor;
import io.github.hecbatista.metalshaders.pack.PackReader;
import io.github.hecbatista.metalshaders.pack.PropertiesPreprocessor;
import io.github.hecbatista.metalshaders.pack.StandardDefines;
import io.github.hecbatista.metalshaders.render.PackChain;
import io.github.hecbatista.metalshaders.uniform.BiomeIds;
import io.github.hecbatista.metalshaders.uniform.CustomUniforms;
import io.github.hecbatista.metalshaders.uniform.GameUniforms;
import io.github.hecbatista.metalshaders.uniform.RenderMatrices;
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
        if (own != null) {
            return own;
        }
        Object matrix = RENDER_MATRICES.get(name);
        return matrix != null ? matrix : GAME_UNIFORMS.get(name);
    }

    private static final RenderMatrices RENDER_MATRICES = new RenderMatrices();

    /** Called by GameRendererRenderMixin with this frame's camera rotation and projection. */
    public static void captureMatrices(org.joml.Matrix4fc viewRotation, org.joml.Matrix4fc projection) {
        RENDER_MATRICES.capture(viewRotation, projection,
                com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
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
            // The pack's setting defaults, so `#if WORLD_BLUR == -1` and friends work in .properties.
            Map<String, String> options = PackReader.open(pack, (name, root, shaders) -> OptionDefaults.read(shaders));
            options.forEach(propertyDefines::putIfAbsent);
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
            prepareChain(pack, "world0", defines, PropertiesPreprocessor.preprocess(properties, propertyDefines));

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

    /**
     * Step 6c: plan the buffers, pick the full-screen passes in run order (deferred, composite,
     * then final; numbered ones by number), drop the ones shaders.properties switches off, read
     * their scale, load noisetex, and compile everything.
     */
    private static void prepareChain(Path pack, String folder, Map<String, String> defines, String properties) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.load(new java.io.StringReader(properties));

        List<String> fragmentSources = new java.util.ArrayList<>();
        for (String program : PackDirectives.foldOrder(pack, folder)) {
            var p = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + program + ".fsh", defines);
            if (p.ok()) {
                fragmentSources.add(p.result().text());
            }
        }
        RenderTargetPlan plan = RenderTargetPlan.fold(fragmentSources);
        plan.warnings().forEach(w -> LOGGER.warn("Buffer plan: {}", w));

        var info = PackReader.read(pack);
        List<String> names = info.programFiles().getOrDefault(folder, List.of()).stream()
                .filter(f -> f.endsWith(".fsh"))
                .map(f -> f.substring(0, f.length() - 4))
                .filter(n -> n.matches("(deferred|composite)\\d*|final"))
                .filter(n -> info.programFiles().get(folder).contains(n + ".vsh"))
                .sorted(java.util.Comparator.comparingInt((String n) -> n.startsWith("deferred") ? 0 : n.startsWith("composite") ? 1 : 2)
                        .thenComparingInt(n -> n.replaceAll("\\D", "").isEmpty() ? 0 : Integer.parseInt(n.replaceAll("\\D", ""))))
                .toList();

        List<Map.Entry<String, String[]>> programs = new java.util.ArrayList<>();
        Map<String, Float> scales = new HashMap<>();
        for (String n : names) {
            String enabled = props.getProperty("program." + folder + "/" + n + ".enabled", props.getProperty("program." + n + ".enabled", "true")).strip();
            if (enabled.equals("false")) {
                LOGGER.info("Chain: {} is switched off by shaders.properties", n);
                continue;
            }
            String scale = props.getProperty("scale." + n);
            if (scale != null) {
                scales.put(n, Float.parseFloat(scale.strip().split("\\s+")[0]));
            }
            var vsh = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + n + ".vsh", defines);
            var fsh = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + n + ".fsh", defines);
            if (!vsh.ok() || !fsh.ok()) {
                LOGGER.warn("Chain: {} skipped: {}", n, vsh.ok() ? fsh.error() : vsh.error());
                continue;
            }
            programs.add(Map.entry(n, new String[]{vsh.result().text(), fsh.result().text()}));
        }

        com.mojang.blaze3d.platform.NativeImage noise = null;
        String noisePath = props.getProperty("texture.noise");
        if (noisePath != null) {
            String path = "/" + noisePath.strip().replaceFirst("^/", "");
            byte[] bytes = PackReader.open(pack, (name, root, shaders) -> {
                Path file = shaders.resolve(path.substring(1)).normalize();
                return file.startsWith(shaders) && java.nio.file.Files.isRegularFile(file) ? java.nio.file.Files.readAllBytes(file) : null;
            });
            if (bytes != null) {
                noise = com.mojang.blaze3d.platform.NativeImage.read(bytes);
            }
        }
        LOGGER.info("Chain: {} passes in order {}, scales {}", programs.size(), programs.stream().map(Map.Entry::getKey).toList(), scales);
        PackChain.prepare(plan, programs, scales, noise);

        // Step 6d-1: the sky, drawn by the pack's own sky programs.
        var skybasicV = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_skybasic.vsh", defines);
        var skybasicF = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_skybasic.fsh", defines);
        var skytexV = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_skytextured.vsh", defines);
        var skytexF = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_skytextured.fsh", defines);
        if (PackChain.ready() && skybasicV.ok() && skybasicF.ok() && skytexV.ok() && skytexF.ok()) {
            io.github.hecbatista.metalshaders.render.SkyTakeover.prepare(
                    new String[]{skybasicV.result().text(), skybasicF.result().text()},
                    new String[]{skytexV.result().text(), skytexF.result().text()});
        } else {
            LOGGER.warn("Sky takeover skipped (the pack lacks a sky program, or the chain isn't ready)");
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
