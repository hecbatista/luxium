package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which folder draws a world (Iris getCurrentDimension / getProgramSet), and program switches. */
class WorldFoldersTest {
    private static PackInfo pack(Map<String, String> dimensions, String... folders) {
        TreeMap<String, List<String>> files = new TreeMap<>();
        files.put("", List.of());
        for (String f : folders) {
            files.put(f, List.of("final.fsh", "final.vsh"));
        }
        return new PackInfo("p", "shaders", true, new TreeMap<>(dimensions), files);
    }

    private static String folder(PackInfo info, String world, String skybox) {
        return info.folderFor(info.worldKey(world, skybox));
    }

    @Test
    void complementaryStyleMap() {
        PackInfo info = pack(Map.of("*", "world0", "minecraft:the_nether", "world-1", "minecraft:nether", "world-1",
                "minecraft:the_end", "world1", "minecraft:end", "world1"), "world0", "world-1", "world1");
        assertEquals("world0", folder(info, "minecraft:overworld", "overworld"));
        assertEquals("world-1", folder(info, "minecraft:the_nether", "none"));
        assertEquals("world1", folder(info, "minecraft:the_end", "end"));
        // A mod world with the End's sky counts as the End; one with no sky and no entry gets "*".
        assertEquals("world1", folder(info, "mymod:void", "end"));
        assertEquals("world0", folder(info, "mymod:caves", "none"));
    }

    @Test
    void namesWithoutNamespaceAndMissingFolders() {
        PackInfo info = pack(Map.of("the_nether", "world-1", "minecraft:the_end", "world1"), "world-1");
        assertEquals("world-1", folder(info, "minecraft:the_nether", "none"));
        // world1 has no programs and there's no "*": the shaders/ root, as Iris's base program set.
        assertEquals("", folder(info, "minecraft:the_end", "end"));
        assertEquals("", folder(info, "minecraft:overworld", "overworld"));
    }

    @Test
    void anExactEntryBeatsTheSkyRule() {
        PackInfo info = pack(Map.of("mymod:sky", "world7", "minecraft:the_end", "world1"), "world7", "world1");
        assertEquals("world7", folder(info, "mymod:sky", "end"));
    }

    @Test
    void programSwitches() {
        Properties p = new Properties();
        p.setProperty("program.world0/shadow.enabled", "false");
        p.setProperty("program.world0/composite3.enabled", "BLUR && !FAST || ALWAYS");
        p.setProperty("program.final.enabled", "(A || B) && C");
        Set<String> on = Set.of("BLUR", "C");
        assertFalse(ProgramSwitches.enabled(p, "world0", "shadow", on::contains));
        assertTrue(ProgramSwitches.enabled(p, "world-1", "shadow", on::contains), "keys are per folder");
        assertTrue(ProgramSwitches.enabled(p, "world0", "composite3", on::contains));
        assertFalse(ProgramSwitches.enabled(p, "world0", "composite3", Set.of("BLUR", "FAST")::contains));
        assertFalse(ProgramSwitches.enabled(p, "", "final", on::contains));
        assertTrue(ProgramSwitches.enabled(p, "", "final", Set.of("B", "C")::contains));
        assertTrue(ProgramSwitches.enabled(p, "world0", "gbuffers_terrain", on::contains), "no key: on");
    }

    @Test
    void unknownSwitchNamesCountAsOn() throws Exception {
        PackOptions o = PackOptions.discover(path -> java.util.Optional.ofNullable(
                        path.equals("/final.fsh") ? "//#define OFF\n#ifdef OFF\n#endif\n" : null),
                List.of("/final.fsh"), Map.of());
        assertFalse(o.booleanValue("OFF"));
        assertTrue(o.booleanValue("NOT_AN_OPTION"));
        assertFalse(o.booleanValue("0"));
    }
}
