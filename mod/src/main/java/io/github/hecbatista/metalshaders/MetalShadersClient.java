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
        readMeshDirectives(shaderpacks);
    }

    /** What the chunk meshes were last built with: separateAo and the block.properties map. */
    private static String meshKey = "";

    /**
     * What changes how chunks are meshed (separateAo, block.properties ids). Read at startup, so a
     * pack that's there from the start needs no rebuild, and again when the pack loads (a pack can
     * arrive later, as in the game test); sections keep what they were meshed with, so if the
     * answer changed, all chunks are rebuilt (the F3+A door, LevelExtractor.allChanged).
     *
     * @return whether the answer changed
     */
    private static boolean readMeshDirectives(Path shaderpacks) {
        try {
            List<Path> packs = PackReader.findPacks(shaderpacks);
            if (packs.isEmpty()) {
                return false;
            }
            String properties = PackReader.open(packs.getFirst(), (name, root, shaders) ->
                    PackReader.readText(shaders, "/shaders.properties").orElse(""));
            java.util.Properties props = new java.util.Properties();
            props.load(new java.io.StringReader(PropertiesPreprocessor.preprocess(properties, StandardDefines.forMac())));
            boolean separateAo = props.getProperty("separateAo", "false").strip().equals("true");
            io.github.hecbatista.metalshaders.sodium.LuxiumChunkVertex.setSeparateAo(separateAo);
            LOGGER.info("Chunk meshes: separateAo={} (from {})", separateAo, packs.getFirst().getFileName());

            // block.properties -> mc_Entity, preprocessed like the other .properties files.
            Map<String, String> defines = new LinkedHashMap<>(StandardDefines.forMac());
            Map<String, String> options = PackReader.open(packs.getFirst(), (name, root, shaders) -> OptionDefaults.read(shaders));
            options.forEach(defines::putIfAbsent);
            String blocks = PackReader.open(packs.getFirst(), (name, root, shaders) ->
                    PackReader.readText(shaders, "/block.properties").orElse(""));
            io.github.hecbatista.metalshaders.pack.BlockIds blockIds =
                    io.github.hecbatista.metalshaders.pack.BlockIds.parse(PropertiesPreprocessor.preprocess(blocks, defines));
            int states = io.github.hecbatista.metalshaders.sodium.BlockStateIds.load(blockIds);
            LOGGER.info("block.properties: {} ids, {} block states numbered, {} names not in this game, {} entries skipped (tags)",
                    blockIds.lines().size(), states, io.github.hecbatista.metalshaders.sodium.BlockStateIds.unknownNames(),
                    blockIds.skipped().size());
            LOGGER.info("block.properties ids: water={} glass={} stone={} oak_leaves={} gold_block={} oak_log={}",
                    io.github.hecbatista.metalshaders.sodium.BlockStateIds.idOf("minecraft:water"),
                    io.github.hecbatista.metalshaders.sodium.BlockStateIds.idOf("minecraft:glass"),
                    io.github.hecbatista.metalshaders.sodium.BlockStateIds.idOf("minecraft:stone"),
                    io.github.hecbatista.metalshaders.sodium.BlockStateIds.idOf("minecraft:oak_leaves"),
                    io.github.hecbatista.metalshaders.sodium.BlockStateIds.idOf("minecraft:gold_block"),
                    io.github.hecbatista.metalshaders.sodium.BlockStateIds.idOf("minecraft:oak_log"));
            String key = packs.getFirst() + "|" + separateAo + "|" + states;
            boolean changed = !key.equals(meshKey);
            meshKey = key;
            return changed;
        } catch (Exception e) {
            LOGGER.error("Couldn't read the pack's mesh directives", e);
            return false;
        }
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
            if (readMeshDirectives(FabricLoader.getInstance().getGameDir().resolve("shaderpacks"))) {
                LOGGER.info("Chunk mesh directives changed since startup: rebuilding all chunks");
                mc.levelExtractor.allChanged();
            }
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
            String entities = PackReader.open(pack, (name, root, shaders) ->
                    PackReader.readText(shaders, "/entity.properties").orElse(""));
            ItemIds entityIds = ItemIds.parse(PropertiesPreprocessor.preprocess(entities, propertyDefines), "entity.");
            io.github.hecbatista.metalshaders.render.DrawIds.setEntityIds(entityIds);
            LOGGER.info("entity.properties: {} entity types numbered (pig={}, slime={})", entityIds.size(),
                    entityIds.of("minecraft:pig"), entityIds.of("minecraft:slime"));
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
        String clouds = props.getProperty("clouds", "").strip();
        PackChain.setCloudsOff(clouds.equals("off"));
        LOGGER.info("Chain: clouds={} (the game's clouds are {})", clouds.isEmpty() ? "(not set)" : clouds, clouds.equals("off") ? "hidden" : "drawn");
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

        // Step 6e: the pack's shadow directives (const directives, Iris PackShadowDirectives), read
        // from the programs that carry them.
        StringBuilder directiveText = new StringBuilder();
        for (String program : List.of("shadow", "composite", "composite1", "deferred1", "final", "gbuffers_terrain")) {
            for (String ext : List.of(".vsh", ".fsh")) {
                var t = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + program + ext, defines);
                if (t.ok()) {
                    directiveText.append(t.result().text()).append('\n');
                }
            }
        }
        var sd = io.github.hecbatista.metalshaders.uniform.ShadowMatrices.Directives.DEFAULT;
        var shadowDirectives = new io.github.hecbatista.metalshaders.uniform.ShadowMatrices.Directives(
                (int) constDirective(directiveText, "shadowMapResolution", sd.resolution()),
                constDirective(directiveText, "shadowDistance", sd.distance()),
                constDirective(directiveText, "shadowNearPlane", sd.nearPlane()),
                constDirective(directiveText, "shadowFarPlane", sd.farPlane()),
                constDirective(directiveText, "shadowIntervalSize", sd.intervalSize()),
                constDirective(directiveText, "sunPathRotation", sd.sunPathRotation()));
        io.github.hecbatista.metalshaders.uniform.ShadowMatrices.setDirectives(shadowDirectives);
        LOGGER.info("Shadow directives: {}", shadowDirectives);

        // Steps 6d-2/6d-3: terrain drawn by the pack's gbuffers_terrain and gbuffers_water through Sodium.
        var terrainV = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_terrain.vsh", defines);
        var terrainF = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_terrain.fsh", defines);
        var waterV = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_water.vsh", defines);
        var waterF = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_water.fsh", defines);
        if (PackChain.ready() && terrainV.ok() && terrainF.ok()) {
            io.github.hecbatista.metalshaders.render.TerrainTakeover.prepare(
                    new String[]{terrainV.result().text(), terrainF.result().text()},
                    waterV.ok() && waterF.ok() ? new String[]{waterV.result().text(), waterF.result().text()} : null,
                    props);
        } else {
            LOGGER.warn("Terrain takeover skipped (the pack lacks gbuffers_terrain, or the chain isn't ready)");
        }
        // Step 6e: the shadow map, drawn by the pack's shadow program (Iris: no program, no shadow pass).
        var shadowV = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/shadow.vsh", defines);
        var shadowF = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/shadow.fsh", defines);
        if (shadowV.ok() && shadowF.ok()) {
            io.github.hecbatista.metalshaders.render.TerrainTakeover.prepareShadow(
                    new String[]{shadowV.result().text(), shadowF.result().text()}, props);
            io.github.hecbatista.metalshaders.render.EntityTakeover.prepareShadowEntities(
                    new String[]{shadowV.result().text(), shadowF.result().text()}, props);
        }

        // Step 6d-4: the level's solid entities and block entities, drawn by the pack's gbuffers_entities.
        var entitiesV = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_entities.vsh", defines);
        var entitiesF = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_entities.fsh", defines);
        var blockV = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_block.vsh", defines);
        var blockF = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_block.fsh", defines);
        var entitiesTV = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_entities_translucent.vsh", defines);
        var entitiesTF = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_entities_translucent.fsh", defines);
        if (PackChain.ready() && entitiesV.ok() && entitiesF.ok()) {
            io.github.hecbatista.metalshaders.render.EntityTakeover.prepare(
                    new String[]{entitiesV.result().text(), entitiesF.result().text()},
                    entitiesTV.ok() && entitiesTF.ok() ? new String[]{entitiesTV.result().text(), entitiesTF.result().text()} : null,
                    blockV.ok() && blockF.ok() ? new String[]{blockV.result().text(), blockF.result().text()} : null,
                    props);
        } else {
            LOGGER.warn("Entity takeover skipped (the pack lacks gbuffers_entities, or the chain isn't ready)");
        }

        // Particles, through Iris's program fallbacks (ProgramId: particles -> textured_lit -> textured -> basic).
        String particleName = null;
        String[] particles = null;
        for (String candidate : List.of("gbuffers_particles", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")) {
            var v = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + candidate + ".vsh", defines);
            var f = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + candidate + ".fsh", defines);
            if (v.ok() && f.ok()) {
                particleName = candidate;
                particles = new String[]{v.result().text(), f.result().text()};
                break;
            }
        }
        // The hand, through Iris's fallbacks (hand -> textured_lit -> textured -> basic; hand_water -> hand).
        String handName = null;
        String[] hand = null;
        for (String candidate : List.of("gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")) {
            var v = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + candidate + ".vsh", defines);
            var f = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + candidate + ".fsh", defines);
            if (v.ok() && f.ok()) {
                handName = candidate;
                hand = new String[]{v.result().text(), f.result().text()};
                break;
            }
        }
        var hwV = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_hand_water.vsh", defines);
        var hwF = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_hand_water.fsh", defines);
        if (PackChain.ready() && hand != null) {
            boolean ownWater = hwV.ok() && hwF.ok();
            LOGGER.info("Hand: {} (translucent: {})", handName, ownWater ? "gbuffers_hand_water" : handName);
            io.github.hecbatista.metalshaders.render.EntityTakeover.prepareHand(handName, hand,
                    ownWater ? "gbuffers_hand_water" : handName,
                    ownWater ? new String[]{hwV.result().text(), hwF.result().text()} : hand, props);
        }

        // Programs Iris gives single pipelines, through its fallbacks (ProgramId): spider eyes and
        // beacon beam -> textured -> basic; lightning -> entities -> textured_lit -> textured -> basic.
        Map<String, List<String>> byPipeline = new LinkedHashMap<>();
        byPipeline.put("gbuffers_spidereyes", List.of("gbuffers_spidereyes", "gbuffers_textured", "gbuffers_basic"));
        byPipeline.put("gbuffers_beaconbeam", List.of("gbuffers_beaconbeam", "gbuffers_textured", "gbuffers_basic"));
        byPipeline.put("gbuffers_lightning", List.of("gbuffers_lightning", "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"));
        for (Map.Entry<String, List<String>> e : byPipeline.entrySet()) {
            for (String candidate : e.getValue()) {
                var v = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + candidate + ".vsh", defines);
                var f = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + candidate + ".fsh", defines);
                if (PackChain.ready() && v.ok() && f.ok()) {
                    LOGGER.info("{}: {}", e.getKey(), candidate);
                    io.github.hecbatista.metalshaders.render.EntityTakeover.prepareByPipeline(e.getKey(), candidate,
                            new String[]{v.result().text(), f.result().text()}, props);
                    break;
                }
            }
        }

        // Weather, through Iris's fallbacks (weather -> textured_lit -> textured -> basic).
        for (String candidate : List.of("gbuffers_weather", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")) {
            var v = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + candidate + ".vsh", defines);
            var f = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/" + candidate + ".fsh", defines);
            if (PackChain.ready() && v.ok() && f.ok()) {
                LOGGER.info("Weather: {}", candidate);
                io.github.hecbatista.metalshaders.render.EntityTakeover.prepareWeather(candidate,
                        new String[]{v.result().text(), f.result().text()}, props);
                break;
            }
        }
        var ptV = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_particles_translucent.vsh", defines);
        var ptF = PackPreprocessor.preprocessProgram(pack, "/" + folder + "/gbuffers_particles_translucent.fsh", defines);
        boolean ownTranslucent = ptV.ok() && ptF.ok();
        if (PackChain.ready() && particles != null) {
            LOGGER.info("Particles: {} (translucent: {})", particleName, ownTranslucent ? "gbuffers_particles_translucent" : particleName);
            io.github.hecbatista.metalshaders.render.EntityTakeover.prepareParticles(particleName, particles,
                    ownTranslucent ? "gbuffers_particles_translucent" : particleName,
                    ownTranslucent ? new String[]{ptV.result().text(), ptF.result().text()} : particles, props);
        }
    }

    /** The first {@code const int|float name = value;} in the text (an Iris const directive), or the default. */
    private static float constDirective(CharSequence text, String name, float fallback) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "const\\s+(?:int|float)\\s+" + name + "\\s*=\\s*(-?[0-9.]+)[fF]?\\s*;").matcher(text);
        return m.find() ? Float.parseFloat(m.group(1)) : fallback;
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
