package io.github.hecbatista.metalshaders.glsl;

import io.github.hecbatista.metalshaders.pack.PackPreprocessor;
import io.github.hecbatista.metalshaders.pack.PackReader;
import io.github.hecbatista.metalshaders.pack.StandardDefines;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The pack's sky programs, translated for each vertex format the game draws the sky with. */
class SkyTranslationTest {
    @Test
    void formatModeDeclaresExactlyTheUsedElements() throws Exception {
        ProgramTranslator.Result r = ProgramTranslator.translateForFormat("""
                attribute vec4 mc_Entity;
                out vec2 texCoord;
                out vec4 glColor;
                void main() {
                    gl_Position = ftransform();
                    texCoord = gl_MultiTexCoord0.xy;
                    glColor = gl_Color * mc_Entity;
                }
                """, "in vec2 texCoord; in vec4 glColor; void main() { gl_FragData[0] = glColor; }", Set.of("Position"));
        assertTrue(r.vertex().contains("in vec3 Position;"), r.vertex());
        assertFalse(r.vertex().contains(" UV0"), "the format has no UV0");
        assertTrue(r.vertex().contains("vec4 mc_Entity = vec4(0);"), r.vertex());
        assertTrue(r.vertex().contains("(luxium_ColorModulator)"), r.vertex());
        assertEquals(List.of("Position"), r.vertexInputs().stream().map(ProgramTranslator.Slot::name).toList());
    }

    @Test
    void complementarySkyCompilesForEveryFormat() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/");
        assumeTrue(new ProcessBuilder("which", "glslangValidator").start().waitFor() == 0);
        Path pack = packs.getFirst();
        Map<String, List<Set<String>>> formats = Map.of(
                "gbuffers_skybasic", List.of(Set.of("Position"), Set.of("Position", "Color")),
                "gbuffers_skytextured", List.of(Set.of("Position", "UV0")));
        Path dir = Files.createDirectories(Path.of("build/sky-check"));
        for (var e : formats.entrySet()) {
            var vsh = PackPreprocessor.preprocessProgram(pack, "/world0/" + e.getKey() + ".vsh", StandardDefines.forMac());
            var fsh = PackPreprocessor.preprocessProgram(pack, "/world0/" + e.getKey() + ".fsh", StandardDefines.forMac());
            for (Set<String> format : e.getValue()) {
                ProgramTranslator.Result r = ProgramTranslator.translateForFormat(vsh.result().text(), fsh.result().text(), format);
                for (String stage : List.of("vert", "frag")) {
                    Path src = dir.resolve(e.getKey() + "-" + String.join("_", format.stream().sorted().toList()) + "." + stage);
                    Files.writeString(src, stage.equals("vert") ? r.vertex() : r.fragment());
                    Process p = new ProcessBuilder("glslangValidator", "-V", "--target-env", "vulkan1.2", "--auto-map-bindings",
                            "-S", stage, src.toString(), "-o", src + ".spv").redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals(0, p.waitFor(), src + ":\n" + out);
                }
                System.out.println(e.getKey() + " for " + format + ": compiles; inputs "
                        + r.vertexInputs().stream().map(ProgramTranslator.Slot::name).toList());
            }
        }
    }
}
