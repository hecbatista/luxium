package io.github.hecbatista.metalshaders;

import io.github.hecbatista.metalshaders.glsl.ProgramTranslator;
import io.github.hecbatista.metalshaders.glsl.Std140Layout;
import io.github.hecbatista.metalshaders.pack.ItemIds;
import io.github.hecbatista.metalshaders.pack.PackInfo;
import io.github.hecbatista.metalshaders.pack.PackOptions;
import io.github.hecbatista.metalshaders.pack.ProgramSwitches;
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
import net.minecraft.world.level.dimension.DimensionType;
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
    public static final String MOD_ID = "luxium";
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

    /** Whether Luxium found a pack to use (a pack in shaderpacks/, read at startup and at load). */
    private static volatile boolean packPresent;

    /**
     * Step 7d (Iris MixinDisableFabulousGraphics): while a pack is in use, the game's Improved
     * Transparency is switched off and the graphics preset becomes Custom, as when a player
     * changes one setting by hand.
     */
    public static void keepImprovedTransparencyOff() {
        Minecraft mc = Minecraft.getInstance();
        if (!packPresent || mc == null || mc.options == null) {
            return;
        }
        if (mc.options.improvedTransparency().get()) {
            mc.options.improvedTransparency().set(false);
            mc.options.graphicsPreset().set(net.minecraft.client.GraphicsPreset.CUSTOM);
            LOGGER.info("Improved Transparency switched off: the shader pack does its own transparency (as Iris does)");
        }
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
            packPresent = !packs.isEmpty();
            if (packs.isEmpty()) {
                return false;
            }
            packOptions = loadOptions(packs.getFirst());
            String properties = PackReader.open(packs.getFirst(), (name, root, shaders) ->
                    PackReader.readText(shaders, "/shaders.properties").orElse(""));
            java.util.Properties props = new java.util.Properties();
            Map<String, String> shaderPropertyDefines = new LinkedHashMap<>(StandardDefines.forMac());
            shaderPropertyDefines.putAll(BiomeIds.defines());
            props.load(new java.io.StringReader(PropertiesPreprocessor.preprocess(properties,
                    packOptions.propertyDefines(shaderPropertyDefines))));
            boolean separateAo = props.getProperty("separateAo", "false").strip().equals("true");
            io.github.hecbatista.metalshaders.sodium.LuxiumChunkVertex.setSeparateAo(separateAo);
            LOGGER.info("Chunk meshes: separateAo={} (from {})", separateAo, packs.getFirst().getFileName());

            // block.properties -> mc_Entity, preprocessed like the other .properties files.
            Map<String, String> defines = packOptions.propertyDefines(StandardDefines.forMac());
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
            String key = packs.getFirst() + "|" + separateAo + "|" + states + "|" + packOptions.changed();
            boolean changed = !key.equals(meshKey);
            meshKey = key;
            return changed;
        } catch (Exception e) {
            LOGGER.error("Couldn't read the pack's mesh directives", e);
            return false;
        }
    }

    /** The first pack's settings: found in its files, with the user's settings file applied. */
    private static PackOptions packOptions;

    /**
     * Step 7: reads {@code shaderpacks/<pack>.txt} (Iris's settings file) and finds the pack's
     * options. Logs what changed and what was ignored, and writes the list of options next to
     * the pack so the names and allowed values can be looked up.
     */
    private static PackOptions loadOptions(Path pack) throws IOException {
        PackOptions options = PackOptions.load(pack);
        LOGGER.info("Pack settings for {}: {} options ({} on/off, {} with values), {} changed by {}: {}",
                pack.getFileName(), options.booleanOptions().size() + options.stringOptions().size(),
                options.booleanOptions().size(), options.stringOptions().size(), options.changed().size(),
                PackOptions.settingsFile(pack).getFileName(), options.changed());
        if (!options.ignored().isEmpty()) {
            LOGGER.warn("Pack settings ignored: {}", options.ignored());
        }
        if (!options.ambiguous().isEmpty()) {
            LOGGER.info("Pack settings dropped as ambiguous (two different defaults), as Iris does: {}", options.ambiguous());
        }
        try {
            java.nio.file.Files.writeString(PackOptions.optionListFile(pack), options.describe(pack.getFileName().toString()));
        } catch (IOException e) {
            LOGGER.warn("Couldn't write the option list: {}", e.toString());
        }
        return options;
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
        io.github.hecbatista.metalshaders.render.PerfStats.frame();
        long valuesStart = io.github.hecbatista.metalshaders.render.PerfStats.start();
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
            keepImprovedTransparencyOff();
        }
        // Step 7b: the folder whose programs draw this world; a different one is loaded in place
        // of the last (Iris keeps one pipeline per world; we keep only the current one).
        DimensionType.Skybox skybox = mc.level.dimensionType().skybox();
        io.github.hecbatista.metalshaders.render.SkyTakeover.setSkybox(skybox);
        if (packInfo != null) {
            String worldId = mc.level.dimension().identifier().toString();
            String folder = packInfo.folderFor(packInfo.worldKey(worldId, skybox.getSerializedName()));
            if (!folder.equals(loadedFolder)) {
                loadWorld(folder, worldId);
            }
        }
        if (customUniforms != null) {
            customUniforms.update(GAME_UNIFORMS::get, (Float) GAME_UNIFORMS.get("frameTime"));
        }
        io.github.hecbatista.metalshaders.render.PerfStats.end(io.github.hecbatista.metalshaders.render.PerfStats.Section.VALUES, valuesStart);
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

    /** The pack in use, its layout, and its preprocessed shaders.properties (null: no pack). */
    private static Path packPath;
    private static PackInfo packInfo;
    private static String packProperties;
    /** The world folder whose programs are loaded ("" is the shaders/ root; null: none yet). */
    private static String loadedFolder;

    /**
     * Once per session, for the first pack found: its settings, ids and formulas, which every
     * world shares. The world's own programs are loaded by {@link #loadWorld}.
     */
    private static void loadPackValues() {
        try {
            List<Path> packs = PackReader.findPacks(FabricLoader.getInstance().getGameDir().resolve("shaderpacks"));
            if (packs.isEmpty()) {
                return;
            }
            Path pack = packs.getFirst();
            if (packOptions == null) {
                packOptions = loadOptions(pack);
            }
            Map<String, String> gameDefines = new LinkedHashMap<>(StandardDefines.forMac());
            gameDefines.putAll(BiomeIds.defines());
            // The pack's settings, so `#if WORLD_BLUR == -1` and friends work in .properties (as in Iris).
            Map<String, String> propertyDefines = packOptions.propertyDefines(gameDefines);
            String properties = PackReader.open(pack, (name, root, shaders) ->
                    PackReader.readText(shaders, "/shaders.properties").orElse(""));
            String items = PackReader.open(pack, (name, root, shaders) ->
                    PackReader.readText(shaders, "/item.properties").orElse(""));
            ItemIds itemIds = ItemIds.parse(PropertiesPreprocessor.preprocess(items, propertyDefines));
            GAME_UNIFORMS.setItemIds(itemIds);
            io.github.hecbatista.metalshaders.render.DrawIds.setItemIds(itemIds);
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
            LOGGER.info("Pack formulas: {} loaded, {} dropped: {}",
                    customUniforms.size(), customUniforms.dropped().size(), customUniforms.dropped());
            packProperties = PropertiesPreprocessor.preprocess(properties, propertyDefines);
            packInfo = PackReader.read(pack);
            packPath = pack;
        } catch (Exception e) {
            LOGGER.error("Loading the pack failed", e);
        }
    }

    /**
     * Loads one world folder's programs, after unloading the last world's. Then translates that
     * folder's terrain program and reports which block values are still missing.
     */
    private static void loadWorld(String folder, String worldId) {
        boolean reload = loadedFolder != null;
        if (reload) {
            PackChain.unload();
        }
        loadedFolder = folder;
        io.github.hecbatista.metalshaders.render.PipelineCompiler.setDumpFolder(folder);
        long start = System.nanoTime();
        LOGGER.info("World {}: {} the pack's {} programs", worldId, reload ? "switching to" : "loading",
                folder.isEmpty() ? "root" : folder + "/");
        try {
            var defines = StandardDefines.forMac();
            // wetnessHalflife / drynessHalflife, in Iris's reading order (its bug depends on order).
            List<Map.Entry<String, Float>> halfLives = PackDirectives.wetnessHalfLives(packPath, folder, defines, packOptions);
            GAME_UNIFORMS.setWetnessDirectives(halfLives);
            prepareChain(packPath, folder, defines, packProperties);
            LOGGER.info("World {}: chain compiles, {}", worldId, io.github.hecbatista.metalshaders.render.PipelineCompiler.drainSummary());
            long prewarmStart = System.nanoTime();
            int prewarmed = io.github.hecbatista.metalshaders.render.EntityTakeover.prewarm();
            LOGGER.info("World {}: prewarm compiles, {}", worldId, io.github.hecbatista.metalshaders.render.PipelineCompiler.drainSummary());
            LOGGER.info("World {}: {}/ loaded in {} ms, of which {} ms compiling {} entity/particle pipelines ahead "
                            + "(wetness half-lives from pack: {})", worldId, folder, (System.nanoTime() - start) / 1_000_000,
                    (System.nanoTime() - prewarmStart) / 1_000_000, prewarmed, halfLives);

            var vsh = PackPreprocessor.preprocessProgram(packPath, path(folder, "gbuffers_terrain.vsh"), defines, packOptions);
            var fsh = PackPreprocessor.preprocessProgram(packPath, path(folder, "gbuffers_terrain.fsh"), defines, packOptions);
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
            Set<String> missing = new java.util.TreeSet<>(UniformWriter.write(layout, MetalShadersClient::value, defaults, bytes));
            Set<String> notInIris = new java.util.TreeSet<>(missing);
            notInIris.retainAll(GameUniforms.NOT_SUPPLIED_BY_IRIS);
            missing.removeAll(notInIris);
            LOGGER.info("Coverage for {} {}/gbuffers_terrain: {} of {} block values supplied ({} bytes). "
                            + "Missing: {}. Zero under Iris too: {}",
                    packPath.getFileName(), folder, layout.members().size() - missing.size() - notInIris.size(),
                    layout.members().size(), layout.size(), missing, notInIris);
        } catch (Exception e) {
            LOGGER.error("Loading world folder {} failed", folder, e);
        }
    }

    /** A pack path in a world folder ("" is the shaders/ root). */
    private static String path(String folder, String file) {
        return folder.isEmpty() ? "/" + file : "/" + folder + "/" + file;
    }

    /**
     * A program's preprocessed vertex and fragment source, or null if it's missing, fails to
     * preprocess, or shaders.properties switches it off ({@code program.<folder>/<name>.enabled}).
     */
    private static String[] program(Path pack, String folder, String name, Map<String, String> defines,
                                    java.util.Properties props) throws IOException {
        if (!ProgramSwitches.enabled(props, folder, name, packOptions::booleanValue)) {
            LOGGER.info("{} is switched off by shaders.properties", path(folder, name));
            return null;
        }
        var vsh = PackPreprocessor.preprocessProgram(pack, path(folder, name + ".vsh"), defines, packOptions);
        var fsh = PackPreprocessor.preprocessProgram(pack, path(folder, name + ".fsh"), defines, packOptions);
        if (vsh.ok() && fsh.ok()) {
            return new String[]{vsh.result().text(), fsh.result().text()};
        }
        String error = vsh.ok() ? fsh.error() : vsh.error();
        if (!error.startsWith("Missing file")) {
            LOGGER.warn("{} skipped: {}", path(folder, name), error);
        }
        return null;
    }

    /** The first of {@code names} the pack has (Iris's program fallbacks), as name -> source. */
    private static Map.Entry<String, String[]> firstOf(Path pack, String folder, Map<String, String> defines,
                                                       java.util.Properties props, String... names) throws IOException {
        for (String name : names) {
            String[] glsl = program(pack, folder, name, defines, props);
            if (glsl != null) {
                return Map.entry(name, glsl);
            }
        }
        return null;
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
            var p = PackPreprocessor.preprocessProgram(pack, path(folder, program + ".fsh"), defines, packOptions);
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
            String[] glsl = program(pack, folder, n, defines, props);
            if (glsl == null) {
                continue;
            }
            String scale = props.getProperty("scale." + n);
            if (scale != null) {
                scales.put(n, Float.parseFloat(scale.strip().split("\\s+")[0]));
            }
            programs.add(Map.entry(n, glsl));
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
        io.github.hecbatista.metalshaders.render.CustomTextures.load(pack, props);
        PackChain.prepare(plan, programs, scales, noise);

        // Step 6d-1: the sky, drawn by the pack's own sky programs.
        String[] skybasic = program(pack, folder, "gbuffers_skybasic", defines, props);
        String[] skytextured = program(pack, folder, "gbuffers_skytextured", defines, props);
        if (PackChain.ready() && skybasic != null && skytextured != null) {
            io.github.hecbatista.metalshaders.render.SkyTakeover.prepare(skybasic, skytextured);
        } else {
            LOGGER.warn("Sky takeover skipped (the pack lacks a sky program, or the chain isn't ready)");
        }

        // Step 6e: the pack's shadow directives (const directives, Iris PackShadowDirectives), read
        // from the programs that carry them.
        StringBuilder directiveText = new StringBuilder();
        for (String name : List.of("shadow", "composite", "composite1", "deferred1", "final", "gbuffers_terrain")) {
            String[] glsl = program(pack, folder, name, defines, props);
            if (glsl != null) {
                directiveText.append(glsl[0]).append('\n').append(glsl[1]).append('\n');
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
        String[] terrain = program(pack, folder, "gbuffers_terrain", defines, props);
        String[] water = program(pack, folder, "gbuffers_water", defines, props);
        if (PackChain.ready() && terrain != null) {
            io.github.hecbatista.metalshaders.render.TerrainTakeover.prepare(terrain, water, props);
        } else {
            LOGGER.warn("Terrain takeover skipped (the pack lacks gbuffers_terrain, or the chain isn't ready)");
        }
        // Step 6e: the shadow map, drawn by the pack's shadow program (Iris: no program, no shadow pass).
        String[] shadow = program(pack, folder, "shadow", defines, props);
        if (shadow != null) {
            io.github.hecbatista.metalshaders.render.TerrainTakeover.prepareShadow(shadow, props);
            io.github.hecbatista.metalshaders.render.EntityTakeover.prepareShadowEntities(shadow, props);
        }

        // Step 6d-4: the level's solid entities and block entities, drawn by the pack's gbuffers_entities.
        String[] entities = program(pack, folder, "gbuffers_entities", defines, props);
        String[] block = program(pack, folder, "gbuffers_block", defines, props);
        String[] entitiesTranslucent = program(pack, folder, "gbuffers_entities_translucent", defines, props);
        if (PackChain.ready() && entities != null) {
            io.github.hecbatista.metalshaders.render.EntityTakeover.prepare(entities, entitiesTranslucent, block, props);
            // Banner patterns on block entities (Iris ProgramId.BlockTrans: block_translucent -> block).
            var blockTranslucent = firstOf(pack, folder, defines, props, "gbuffers_block_translucent", "gbuffers_block", "gbuffers_entities");
            if (blockTranslucent != null) {
                LOGGER.info("Block entity patterns: {}", blockTranslucent.getKey());
                io.github.hecbatista.metalshaders.render.EntityTakeover.prepareBlockTranslucent(blockTranslucent.getKey(), blockTranslucent.getValue(), props);
            }
        } else {
            LOGGER.warn("Entity takeover skipped (the pack lacks gbuffers_entities, or the chain isn't ready)");
        }

        // Particles, through Iris's fallbacks (ProgramId: particles -> textured_lit -> textured -> basic).
        var particles = firstOf(pack, folder, defines, props, "gbuffers_particles", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        // The hand, through Iris's fallbacks (hand -> textured_lit -> ...; hand_water -> hand).
        var hand = firstOf(pack, folder, defines, props, "gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        String[] handWater = program(pack, folder, "gbuffers_hand_water", defines, props);
        if (PackChain.ready() && hand != null) {
            String waterName = handWater != null ? "gbuffers_hand_water" : hand.getKey();
            LOGGER.info("Hand: {} (translucent: {})", hand.getKey(), waterName);
            io.github.hecbatista.metalshaders.render.EntityTakeover.prepareHand(hand.getKey(), hand.getValue(),
                    waterName, handWater != null ? handWater : hand.getValue(), props);
        }

        // Programs Iris gives single pipelines, through its fallbacks (ProgramId): spider eyes and
        // beacon beam -> textured -> basic; lightning -> entities -> textured_lit -> textured -> basic.
        Map<String, String[]> byPipeline = new LinkedHashMap<>();
        byPipeline.put("gbuffers_spidereyes", new String[]{"gbuffers_spidereyes", "gbuffers_textured", "gbuffers_basic"});
        byPipeline.put("gbuffers_beaconbeam", new String[]{"gbuffers_beaconbeam", "gbuffers_textured", "gbuffers_basic"});
        byPipeline.put("gbuffers_lightning", new String[]{"gbuffers_lightning", "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"});
        for (Map.Entry<String, String[]> e : byPipeline.entrySet()) {
            var found = firstOf(pack, folder, defines, props, e.getValue());
            if (PackChain.ready() && found != null) {
                LOGGER.info("{}: {}", e.getKey(), found.getKey());
                io.github.hecbatista.metalshaders.render.EntityTakeover.prepareByPipeline(e.getKey(), found.getKey(), found.getValue(), props);
            }
        }

        // Weather, through Iris's fallbacks (weather -> textured_lit -> textured -> basic).
        var weather = firstOf(pack, folder, defines, props, "gbuffers_weather", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        if (PackChain.ready() && weather != null) {
            LOGGER.info("Weather: {}", weather.getKey());
            io.github.hecbatista.metalshaders.render.EntityTakeover.prepareWeather(weather.getKey(), weather.getValue(), props);
        }
        String[] particlesTranslucent = program(pack, folder, "gbuffers_particles_translucent", defines, props);
        if (PackChain.ready() && particles != null) {
            String translucentName = particlesTranslucent != null ? "gbuffers_particles_translucent" : particles.getKey();
            LOGGER.info("Particles: {} (translucent: {})", particles.getKey(), translucentName);
            io.github.hecbatista.metalshaders.render.EntityTakeover.prepareParticles(particles.getKey(), particles.getValue(),
                    translucentName, particlesTranslucent != null ? particlesTranslucent : particles.getValue(), props);
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
