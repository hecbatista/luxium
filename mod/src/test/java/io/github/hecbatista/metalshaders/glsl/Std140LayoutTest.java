package io.github.hecbatista.metalshaders.glsl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hecbatista.metalshaders.pack.PackPreprocessor;
import io.github.hecbatista.metalshaders.pack.PackReader;
import io.github.hecbatista.metalshaders.pack.StandardDefines;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Std140LayoutTest {
    private static ProgramTranslator.Uniform u(String type, String name) {
        return new ProgramTranslator.Uniform(type, name, "", null);
    }

    @Test
    void floatFitsAfterAVec3() {
        Std140Layout.Layout l = Std140Layout.of(List.of(u("float", "a"), u("vec3", "b"), u("float", "c"), u("vec2", "d")));
        assertEquals(List.of(0, 16, 28, 32), l.members().stream().map(Std140Layout.Member::offset).toList());
        assertEquals(40, l.size());
    }

    @Test
    void matricesAndArrays() {
        Std140Layout.Layout l = Std140Layout.of(List.of(
                u("float", "a"),
                u("mat3", "b"),                                              // 3 columns x 16 = 48
                new ProgramTranslator.Uniform("mat4", "c", "[8]", null),     // 8 x 64
                new ProgramTranslator.Uniform("float", "d", "[2]", null)));  // each element padded to 16
        assertEquals(List.of(0, 16, 64, 576), l.members().stream().map(Std140Layout.Member::offset).toList());
        assertEquals(608, l.size());
    }

    /**
     * The answer key: compile every Complementary program and compare our offsets with the ones
     * glslang put in the SPIR-V (read back with spirv-cross --reflect). Skipped without packs/tools.
     */
    @Test
    void matchesTheCompilerOnEveryRealProgram() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/");
        assumeTrue(new ProcessBuilder("which", "spirv-cross").start().waitFor() == 0, "spirv-cross missing");

        Path dir = Files.createDirectories(Path.of("build/std140-check"));
        int programs = 0;
        int members = 0;
        for (Path pack : packs) {
            Map<String, String> sources = new HashMap<>();
            for (PackPreprocessor.Program p : PackPreprocessor.preprocessAll(pack, StandardDefines.forMac())) {
                if (p.ok()) {
                    sources.put(p.path(), p.result().text());
                }
            }
            for (String path : sources.keySet().stream().sorted().toList()) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!path.endsWith(".vsh") || name.startsWith("dh_") || name.startsWith("clrwl_")
                        || !sources.containsKey(path.replace(".vsh", ".fsh"))) {
                    continue;
                }
                ProgramTranslator.Result r = ProgramTranslator.translate(sources.get(path), sources.get(path.replace(".vsh", ".fsh")));
                Path src = dir.resolve("p.vert");
                Files.writeString(src, r.vertex());
                Path spv = dir.resolve("p.spv");
                assertEquals(0, new ProcessBuilder("glslangValidator", "-V", "--target-env", "vulkan1.2",
                        "--auto-map-bindings", "-S", "vert", src.toString(), "-o", spv.toString())
                        .redirectErrorStream(true).redirectOutput(dir.resolve("glslang.log").toFile()).start().waitFor(), path);
                Process reflect = new ProcessBuilder("spirv-cross", spv.toString(), "--reflect").start();
                JsonObject json = JsonParser.parseString(new String(reflect.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                reflect.waitFor();

                // A uniform block keeps all its members even if the stage reads only some, so compare every one.
                JsonObject ubo = json.getAsJsonArray("ubos").get(0).getAsJsonObject();
                JsonArray actual = json.getAsJsonObject("types").getAsJsonObject(ubo.get("type").getAsString()).getAsJsonArray("members");
                Std140Layout.Layout ours = Std140Layout.of(r.uniforms());
                assertEquals(actual.size(), ours.members().size(), "member count in " + path);
                for (int i = 0; i < actual.size(); i++) {
                    JsonObject m = actual.get(i).getAsJsonObject();
                    Std140Layout.Member mine = ours.members().get(i);
                    assertEquals(m.get("name").getAsString(), mine.name(), path);
                    assertEquals(m.get("offset").getAsInt(), mine.offset(), path + " " + mine.name());
                    members++;
                }
                assertEquals(ubo.get("block_size").getAsInt(), ours.size(), "block size in " + path);
                programs++;
            }
        }
        System.out.println("std140 layout matches the compiler: " + programs + " programs, " + members + " members");
    }
}
