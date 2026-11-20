package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Preprocesses every program of every real pack in run/shaderpacks/ and writes the results to
 * build/preprocessed/ so other tools (glslangValidator) can check them. Skipped when there are
 * no packs, because packs can't be committed (see CLAUDE.md, license).
 */
class RealPackTest {
    @Test
    void everyProgramPreprocesses() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/; skipping");

        int failures = 0;
        for (Path pack : packs) {
            long start = System.nanoTime();
            List<PackPreprocessor.Program> programs = PackPreprocessor.preprocessAll(pack, StandardDefines.forMac());
            long ms = (System.nanoTime() - start) / 1_000_000;

            String name = pack.getFileName().toString().replaceFirst("\\.zip$", "");
            Path outDir = Path.of("build/preprocessed", name);
            int ok = 0;
            int maxLines = 0;
            for (PackPreprocessor.Program program : programs) {
                if (program.ok()) {
                    ok++;
                    maxLines = Math.max(maxLines, program.expandedLines());
                    Path out = outDir.resolve(program.path().substring(1));
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, program.result().text());
                } else {
                    failures++;
                    System.out.println("FAILED " + name + program.path() + ": " + program.error());
                }
            }
            System.out.printf("%s: %d/%d programs preprocessed in %d ms (largest: %d lines before #if) -> %s%n",
                    name, ok, programs.size(), ms, maxLines, outDir);
        }
        assertEquals(0, failures, "Some programs failed; see the output above");
    }
}
