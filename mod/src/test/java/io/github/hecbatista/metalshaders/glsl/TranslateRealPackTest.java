package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.pack.PackPreprocessor;
import io.github.hecbatista.metalshaders.pack.PackReader;
import io.github.hecbatista.metalshaders.pack.StandardDefines;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The step 4 scoreboard. For every vertex+fragment program of every pack in run/shaderpacks/:
 * preprocess, translate, then run the same chain the game will use, with the same options:
 * glslang for Vulkan 1.2 with automatic bindings only (like Minecraft's GlslCompiler.java:79-83),
 * spirv-cross to MSL 4.0 (like Metallum's MetalCrossShaderCompiler), then Apple's compiler.
 * <p>
 * It prints how many programs get through each stage and the most common errors, and writes
 * everything to build/translated/. It only fails if the tokenizer isn't lossless; the pass
 * count is a measurement, not a requirement (yet).
 */
class TranslateRealPackTest {
    /** Programs that only run with mods we don't have, so a failure there means nothing yet. */
    static boolean needsMissingMod(String name) {
        // gbuffers_voxels and shadow_voxels are the Photonics mod's (Photon calls its RayJob API);
        // Iris has no such programs.
        return name.startsWith("dh_") || name.startsWith("clrwl_") || name.startsWith("voxy") || name.endsWith("_voxels");
    }

    private static final java.util.regex.Pattern SAMPLER_ARGUMENT =
            java.util.regex.Pattern.compile(",\\s*sampler (\\w+) \\[\\[sampler\\((\\d+)\\)\\]\\]");

    /**
     * What MetalSamplerSlotsMixin does inside the game, for the command-line chain: when a sampler
     * sits in slot 16 or up, every sampler becomes one built into the function (linear, clamped).
     */
    static String constexprSamplersAbove15(String msl) {
        java.util.regex.Matcher scan = SAMPLER_ARGUMENT.matcher(msl);
        boolean over = false;
        while (scan.find()) {
            over |= Integer.parseInt(scan.group(2)) >= 16;
        }
        if (!over) {
            return msl;
        }
        java.util.regex.Matcher m = SAMPLER_ARGUMENT.matcher(msl);
        StringBuilder body = new StringBuilder();
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            if (true) {
                body.append("    constexpr sampler ").append(m.group(1)).append("(filter::linear, address::clamp_to_edge);\n");
                m.appendReplacement(out, "");
            } else {
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(out);
        if (body.isEmpty()) {
            return msl;
        }
        java.util.regex.Matcher entry = java.util.regex.Pattern.compile("\\b(fragment|vertex) [^{;]*\\)\\s*\\{\\n").matcher(out);
        return entry.find() ? out.insert(entry.end(), body).toString() : out.toString();
    }

    record Outcome(String program, String failedAt, String error) {
    }

    @Test
    void scoreboard() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/; skipping");
        assumeTrue(toolExists("glslangValidator") && toolExists("spirv-cross"), "glslang/spirv-cross not installed");

        for (Path pack : packs) {
            String packName = pack.getFileName().toString().replaceFirst("\\.zip$", "");
            Path out = Path.of("build/translated", packName);
            Map<String, String> sources = new TreeMap<>();
            for (PackPreprocessor.Program p : PackPreprocessor.preprocessAll(pack, StandardDefines.forMac())) {
                if (p.ok()) {
                    String text = p.result().text();
                    assertEquals(text, GlslTokenizer.join(GlslTokenizer.tokenize(text)), "tokenizer lost text in " + p.path());
                    sources.put(p.path(), text);
                }
            }

            List<String> programs = new ArrayList<>();
            for (String path : sources.keySet()) {
                if (path.endsWith(".vsh") && sources.containsKey(path.replaceFirst("\\.vsh$", ".fsh"))) {
                    String program = path.substring(0, path.length() - 4);
                    if (!needsMissingMod(program.substring(program.lastIndexOf('/') + 1))) {
                        programs.add(program);
                    }
                }
            }

            List<Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
            programs.parallelStream().forEach(program -> outcomes.add(run(program, sources, out)));

            Map<String, Long> byStage = outcomes.stream()
                    .collect(Collectors.groupingBy(Outcome::failedAt, TreeMap::new, Collectors.counting()));
            System.out.printf("%s: %d programs (vertex+fragment pairs, mods we don't have excluded)%n", packName, programs.size());
            System.out.println("  furthest step reached: " + byStage);
            outcomes.stream().filter(o -> !o.failedAt().equals("6-ok"))
                    .collect(Collectors.groupingBy(o -> o.failedAt() + ": " + o.error(), TreeMap::new, Collectors.counting()))
                    .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(15)
                    .forEach(e -> System.out.println("  " + e.getValue() + " x " + e.getKey()));
            List<String> lines = outcomes.stream().sorted((a, b) -> a.program().compareTo(b.program()))
                    .map(o -> o.failedAt() + " " + o.program() + (o.error() == null ? "" : "  " + o.error())).toList();
            Files.createDirectories(out);
            Files.write(out.resolve("scoreboard.txt"), lines);
        }
    }

    static Outcome run(String program, Map<String, String> sources, Path out) {
        String name = program.substring(1);
        try {
            ProgramTranslator.Result r;
            try {
                r = ProgramTranslator.translate(sources.get(program + ".vsh"), sources.get(program + ".fsh"),
                        ProgramTranslator.modeFor(program.substring(program.lastIndexOf('/') + 1)));
            } catch (ProgramTranslator.TranslationException e) {
                return new Outcome(name, "1-translate", e.getMessage());
            }
            Path base = out.resolve(name);
            Files.createDirectories(base.getParent());
            for (String stage : List.of("vert", "frag")) {
                Path src = Path.of(base + "." + stage);
                Files.writeString(src, stage.equals("vert") ? r.vertex() : r.fragment());
                String err = exec(List.of("glslangValidator", "-V", "--target-env", "vulkan1.2", "--auto-map-bindings",
                        "-S", stage, src.toString(), "-o", base + "." + stage + ".spv"));
                if (err != null) {
                    return new Outcome(name, "2-glslang-" + stage, firstError(err));
                }
                List<String> cross = new ArrayList<>(List.of("spirv-cross", base + "." + stage + ".spv", "--msl",
                        "--msl-version", "40000", "--msl-decoration-binding", "--msl-texture-buffer-native"));
                if (stage.equals("vert")) {
                    cross.add("--flip-vert-y");
                }
                cross.addAll(List.of("--output", base + "." + stage + ".metal"));
                err = exec(cross);
                if (err != null) {
                    return new Outcome(name, "3-spirv-cross-" + stage, firstError(err));
                }
                Path metal = Path.of(base + "." + stage + ".metal");
                Files.writeString(metal, constexprSamplersAbove15(Files.readString(metal)));
                err = exec(List.of("xcrun", "metal", "-std=metal4.0", "-c", base + "." + stage + ".metal",
                        "-o", base + "." + stage + ".air"));
                if (err != null) {
                    return new Outcome(name, "4-metal-" + stage, firstError(err));
                }
            }
            return new Outcome(name, "6-ok", null);
        } catch (IOException | InterruptedException e) {
            return new Outcome(name, "0-harness", e.toString());
        }
    }

    /** Runs a command; returns null on success, else its output. */
    private static String exec(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = p.getInputStream().readAllBytes();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "timed out";
        }
        return p.exitValue() == 0 ? null : new String(output, StandardCharsets.UTF_8);
    }

    /**
     * The first error line, without file paths and line numbers so similar errors group together.
     * glslang's leading 'name' (the identifier the error is about) is kept, since that's what tells
     * one gap from another; later quoted parts are collapsed.
     */
    private static String firstError(String output) {
        for (String line : output.split("\n")) {
            if (line.contains("ERROR") || line.contains("error")) {
                String msg = line.replaceAll("^ERROR: .*?:\\d+: ", "").replaceAll("^.*?:\\d+:\\d+: error: ", "");
                int head = msg.startsWith("'") ? msg.indexOf('\'', 1) + 1 : 0;
                return (msg.substring(0, head) + msg.substring(head).replaceAll("'[^']*'", "'…'")).strip();
            }
        }
        return output.lines().findFirst().orElse("").strip();
    }

    static boolean toolExists(String tool) {
        try {
            return new ProcessBuilder("which", tool).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
