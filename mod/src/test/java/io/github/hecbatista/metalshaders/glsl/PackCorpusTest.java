package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.pack.PackDirectives;
import io.github.hecbatista.metalshaders.pack.PackInfo;
import io.github.hecbatista.metalshaders.pack.PackOptions;
import io.github.hecbatista.metalshaders.pack.PackPreprocessor;
import io.github.hecbatista.metalshaders.pack.PackReader;
import io.github.hecbatista.metalshaders.pack.ProgramSwitches;
import io.github.hecbatista.metalshaders.pack.PropertiesPreprocessor;
import io.github.hecbatista.metalshaders.pack.RenderTargetPlan;
import io.github.hecbatista.metalshaders.pack.StandardDefines;
import io.github.hecbatista.metalshaders.uniform.BiomeIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The multi-pack scoreboard: every pack in run/shaderpacks/ and run/testpacks/&lt;slug&gt;/ (fetched by
 * scripts/fetch-testpacks.sh), or only the packs in LUXIUM_TEST_PACKS=&lt;dir&gt;, through every stage that runs without the game. The stages are
 * reading the pack, preprocessing, the world0 buffer plan, and translate → glslang → spirv-cross →
 * Apple's compiler, as in {@link TranslateRealPackTest}.
 * <p>
 * It's a measurement and never fails because a pack does badly. Run it with
 * {@code ./gradlew packCorpus} (LUXIUM_TEST_PACKS=run/testpacks/&lt;slug&gt; for one pack). Results go to
 * build/corpus/scoreboard.md, with one detail file per pack beside it. Plain {@code ./gradlew test}
 * skips it, because the whole corpus takes minutes.
 */
@EnabledIfSystemProperty(named = "luxium.corpus", matches = "true")
class PackCorpusTest {
    private static final Path OUT = Path.of("build/corpus");

    private record Row(String pack, String worlds, int preprocessOk, int preprocessTotal, int pairs,
                       Map<String, Long> byStage, String inGameEntry, String bufferPlan,
                       String unsupported, String topError) {
    }

    @Test
    void scoreboard() throws Exception {
        List<Path> packs = packsToScore();
        assumeFalse(packs.isEmpty(), "No packs in LUXIUM_TEST_PACKS, run/shaderpacks/ or run/testpacks/");
        assumeTrue(TranslateRealPackTest.toolExists("glslangValidator")
                && TranslateRealPackTest.toolExists("spirv-cross"), "glslang/spirv-cross not installed");
        Files.createDirectories(OUT);

        List<Row> rows = new ArrayList<>();
        for (Path pack : packs) {
            long start = System.nanoTime();
            Row row = score(pack);
            rows.add(row);
            System.out.printf("%-40s %5.1fs  ok %s/%d%n", row.pack(), (System.nanoTime() - start) / 1e9,
                    row.byStage().getOrDefault("6-ok", 0L), row.pairs());
        }
        Files.writeString(OUT.resolve("scoreboard.md"), table(rows));
        System.out.println(table(rows));
    }

    /**
     * LUXIUM_TEST_PACKS=&lt;dir&gt; (the game tests' switch): the packs in that folder. Otherwise
     * run/shaderpacks/ plus every folder under run/testpacks/ (one per pack, see fetch-testpacks.sh).
     */
    private static List<Path> packsToScore() throws Exception {
        String only = System.getenv("LUXIUM_TEST_PACKS");
        if (only != null && !only.isBlank()) {
            return PackReader.findPacks(Path.of(only));
        }
        List<Path> packs = new ArrayList<>(PackReader.findPacks(Path.of("run/shaderpacks")));
        Path testpacks = Path.of("run/testpacks");
        if (Files.isDirectory(testpacks)) {
            try (var dirs = Files.list(testpacks)) {
                for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                    packs.addAll(PackReader.findPacks(dir));
                }
            }
        }
        return packs;
    }

    private static Row score(Path pack) throws Exception {
        String name = pack.getFileName().toString().replaceFirst("\\.zip$", "");
        Path out = OUT.resolve(name);
        List<String> detail = new ArrayList<>();

        // Stage 0: read the pack.
        PackInfo info;
        try {
            info = PackReader.read(pack);
        } catch (Exception e) {
            return new Row(name, "-", 0, 0, 0, Map.of(), "-", "-", "-", "read: " + e.getMessage());
        }
        String worlds = info.programFiles().keySet().stream().map(k -> k.isEmpty() ? "(root)" : k)
                .collect(Collectors.joining(" "));
        // Program kinds the translator doesn't handle yet (compute, geometry, tessellation).
        Map<String, Long> unsupportedKinds = info.programFiles().values().stream().flatMap(List::stream)
                .map(f -> f.substring(f.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT))
                .filter(ext -> ext.equals("csh") || ext.equals("gsh") || ext.equals("tcs") || ext.equals("tes"))
                .collect(Collectors.groupingBy(e -> e, TreeMap::new, Collectors.counting()));
        String unsupported = unsupportedKinds.isEmpty() ? "none" : unsupportedKinds.entrySet().stream()
                .map(e -> e.getValue() + " ." + e.getKey()).collect(Collectors.joining(", "));
        // The game currently starts from world0/gbuffers_terrain (MetalShadersClient.loadPackValues).
        List<String> world0 = info.programFiles().getOrDefault("world0", List.of());
        String inGameEntry = world0.contains("gbuffers_terrain.vsh") && world0.contains("gbuffers_terrain.fsh")
                ? "yes" : "no world0/gbuffers_terrain";

        // Stage 1: preprocess every program file.
        List<PackPreprocessor.Program> preprocessed = PackPreprocessor.preprocessAll(pack, StandardDefines.forMac());
        Map<String, String> sources = new TreeMap<>();
        int ok = 0;
        for (PackPreprocessor.Program p : preprocessed) {
            if (p.ok()) {
                ok++;
                sources.put(p.path(), p.result().text());
            } else {
                detail.add("preprocess-failed " + p.path() + "  " + p.error());
            }
        }

        // Stage 2: the world0 buffer plan, as the game folds it.
        String bufferPlan;
        try {
            if (world0.isEmpty()) {
                bufferPlan = "no world0";
            } else {
                List<String> fragments = new ArrayList<>();
                for (String program : PackDirectives.foldOrder(pack, "world0")) {
                    String text = sources.get("/world0/" + program + ".fsh");
                    if (text != null) {
                        fragments.add(text);
                    }
                }
                RenderTargetPlan plan = RenderTargetPlan.fold(fragments);
                plan.warnings().forEach(w -> detail.add("buffer-plan-warning " + w));
                bufferPlan = plan.warnings().isEmpty() ? "ok" : plan.warnings().size() + " warnings";
            }
        } catch (Exception e) {
            bufferPlan = "failed";
            detail.add("buffer-plan-failed " + e);
        }

        // Stages 3-6: translate each vertex+fragment pair and compile it to Metal.
        // Programs shaders.properties switches off with the default options are skipped, as the
        // game skips them (MetalShadersClient.program, ProgramSwitches).
        PackOptions defaults = loadOptions(pack, detail);
        java.util.Properties switches = defaults == null ? new java.util.Properties() : programSwitches(pack, defaults, detail);
        List<String> programs = new ArrayList<>();
        int switchedOff = 0;
        for (String path : sources.keySet()) {
            if (path.endsWith(".vsh") && sources.containsKey(path.replaceFirst("\\.vsh$", ".fsh"))) {
                String program = path.substring(0, path.length() - 4);
                String file = program.substring(program.lastIndexOf('/') + 1);
                String folder = program.substring(1, program.lastIndexOf('/') + 1).replaceFirst("/$", "");
                if (TranslateRealPackTest.needsMissingMod(file)) {
                    continue;
                }
                if (defaults != null && !ProgramSwitches.enabled(switches, folder, file, defaults::booleanValue)) {
                    detail.add("switched-off " + program);
                    switchedOff++;
                    continue;
                }
                programs.add(program);
            }
        }
        if (switchedOff > 0) {
            unsupported = (unsupported.equals("none") ? "" : unsupported + ", ") + switchedOff + " switched off";
        }
        List<TranslateRealPackTest.Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        programs.parallelStream().forEach(program -> outcomes.add(TranslateRealPackTest.run(program, sources, out)));
        Map<String, Long> byStage = outcomes.stream().collect(Collectors.groupingBy(
                TranslateRealPackTest.Outcome::failedAt, TreeMap::new, Collectors.counting()));
        Map<String, Long> errors = outcomes.stream().filter(o -> !o.failedAt().equals("6-ok"))
                .collect(Collectors.groupingBy(o -> o.failedAt() + ": " + o.error(), TreeMap::new, Collectors.counting()));
        String topError = errors.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> e.getValue() + " x " + e.getKey()).orElse("");
        if (topError.isEmpty() && ok < preprocessed.size()) {
            topError = detail.stream().filter(d -> d.startsWith("preprocess-failed")).findFirst().orElse("");
        }

        errors.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> detail.add("error " + e.getValue() + " x " + e.getKey()));
        outcomes.stream().sorted((a, b) -> a.program().compareTo(b.program()))
                .forEach(o -> detail.add(o.failedAt() + " " + o.program() + (o.error() == null ? "" : "  " + o.error())));
        Files.createDirectories(out);
        Files.write(OUT.resolve(name + ".txt"), detail);
        return new Row(name, worlds, ok, preprocessed.size(), programs.size(), byStage, inGameEntry, bufferPlan,
                unsupported, topError);
    }

    /** shaders.properties preprocessed as the game does (MetalShadersClient), with the pack's default options. */
    private static java.util.Properties programSwitches(Path pack, PackOptions options, List<String> detail) {
        java.util.Properties props = new java.util.Properties();
        try {
            String text = PackReader.open(pack, (name, root, shaders) ->
                    PackReader.readText(shaders, "/shaders.properties").orElse(""));
            Map<String, String> defines = new java.util.LinkedHashMap<>(StandardDefines.forMac());
            defines.putAll(BiomeIds.defines());
            props.load(new java.io.StringReader(PropertiesPreprocessor.preprocess(text, options.propertyDefines(defines))));
        } catch (Exception e) {
            detail.add("properties-failed " + e);
        }
        return props;
    }

    private static PackOptions loadOptions(Path pack, List<String> detail) {
        try {
            return PackOptions.load(pack);
        } catch (Exception e) {
            detail.add("options-failed " + e);
            return null;
        }
    }

    private static String table(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Pack corpus scoreboard\n\n");
        sb.append("Stages that run without the game. \"Metal ok\" = translated, through glslang, spirv-cross and ")
                .append("`xcrun metal`. Programs for mods we don't have (dh_, clrwl_, voxy) are left out, and so are programs ")
                .append("shaders.properties switches off with the default options (\"Not translated\"). ")
                .append("Details per pack: build/corpus/<pack>.txt.\n\n");
        sb.append("| Pack | Preprocess | Metal ok | Translate fail | glslang fail | spirv-cross fail | metal fail "
                + "| In-game entry | Buffer plan | Not translated | Folders | Most common error |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| ").append(r.pack())
                    .append(" | ").append(r.preprocessOk()).append('/').append(r.preprocessTotal())
                    .append(" | **").append(r.byStage().getOrDefault("6-ok", 0L)).append('/').append(r.pairs()).append("**")
                    .append(" | ").append(count(r, "1-"))
                    .append(" | ").append(count(r, "2-"))
                    .append(" | ").append(count(r, "3-"))
                    .append(" | ").append(count(r, "4-"))
                    .append(" | ").append(r.inGameEntry())
                    .append(" | ").append(r.bufferPlan())
                    .append(" | ").append(r.unsupported())
                    .append(" | ").append(r.worlds())
                    .append(" | ").append(r.topError().replace("|", "\\|"))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private static long count(Row r, String prefix) {
        return r.byStage().entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                .mapToLong(Map.Entry::getValue).sum();
    }
}
