package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Iris's option rules (OptionAnnotatedSource, ShaderPackOptions, MutableOptionValues) on small packs. */
class PackOptionsTest {
    private static IncludeExpander.FileSource pack(Map<String, String> files) {
        return path -> Optional.ofNullable(files.get(path));
    }

    private static PackOptions discover(Map<String, String> files, Map<String, String> settings) throws Exception {
        List<String> starts = files.keySet().stream().filter(p -> p.endsWith(".fsh") || p.endsWith(".vsh")).toList();
        return PackOptions.discover(pack(files), starts, settings);
    }

    private static String applied(PackOptions options, Map<String, String> files, String path) throws Exception {
        return options.apply(pack(files)).read(path).orElseThrow();
    }

    @Test
    void valueOptionIsFoundAndChanged() throws Exception {
        Map<String, String> files = Map.of(
                "/final.fsh", "#include \"/lib/settings.glsl\"\nvoid main() {}",
                "/lib/settings.glsl", "    #define SATURATION 1.00 //[0.00 0.50 1.00]\n#define NO_LIST 3\n");
        PackOptions o = discover(files, Map.of("SATURATION", "0.00"));
        assertEquals(List.of("0.00", "0.50", "1.00"), o.stringOptions().get("SATURATION").allowedValues());
        assertFalse(o.stringOptions().containsKey("NO_LIST"), "a value needs a [list] to be an option");
        assertEquals("#define SATURATION 0.00 // Luxium: changed option\n#define NO_LIST 3\n",
                applied(o, files, "/lib/settings.glsl"));
        assertEquals(Map.of("SATURATION", "0.00"), o.changed());
    }

    @Test
    void valueOutsideTheListIsStillApplied() throws Exception {
        // MutableOptionValues: values typed into the .txt aren't checked against the list.
        Map<String, String> files = Map.of("/final.fsh", "#define Q 2 // [1 2]\n");
        assertEquals(Map.of("Q", "7"), discover(files, Map.of("Q", "7")).changed());
    }

    @Test
    void switchCountsOnlyWhenTestedWithIfdef() throws Exception {
        Map<String, String> files = Map.of(
                "/final.fsh", "#include \"/lib/a.glsl\"\n#ifdef USED\n#endif\n#if defined(IF_ONLY)\n#endif\n",
                "/lib/a.glsl", "#define USED\n#define IF_ONLY\n//#define OFF_USED\n",
                "/composite.fsh", "#ifndef OFF_USED\n#endif\n");
        PackOptions o = discover(files, Map.of());
        assertTrue(o.booleanOptions().get("USED").defaultValue());
        assertFalse(o.booleanOptions().containsKey("IF_ONLY"), "#if defined() doesn't count (OptiFine)");
        assertFalse(o.booleanOptions().containsKey("OFF_USED"), "tested only in another include group");
    }

    @Test
    void switchesTurnOnAndOff() throws Exception {
        Map<String, String> files = Map.of(
                "/final.fsh", "  #define ON_BY_DEFAULT // a comment\n  //#define OFF_BY_DEFAULT\n"
                        + "#ifdef ON_BY_DEFAULT\n#endif\n#ifdef OFF_BY_DEFAULT\n#endif\n");
        PackOptions o = discover(files, Map.of("ON_BY_DEFAULT", "false", "OFF_BY_DEFAULT", "true"));
        assertEquals("//  #define ON_BY_DEFAULT // a comment\n#define OFF_BY_DEFAULT\n"
                        + "#ifdef ON_BY_DEFAULT\n#endif\n#ifdef OFF_BY_DEFAULT\n#endif\n",
                applied(o, files, "/final.fsh"));
    }

    @Test
    void badSwitchValueAndUnknownNamesAreIgnored() throws Exception {
        Map<String, String> files = Map.of("/final.fsh", "#define S\n#ifdef S\n#endif\n");
        PackOptions o = discover(files, Map.of("S", "yes", "NOPE", "1", "S2", "true"));
        assertTrue(o.changed().isEmpty());
        assertEquals(3, o.ignored().size());
    }

    @Test
    void sameValueAsDefaultIsNotAChange() throws Exception {
        Map<String, String> files = Map.of("/final.fsh", "#define Q 2 // [1 2]\n#define S\n#ifdef S\n#endif\n");
        PackOptions o = discover(files, Map.of("Q", "2", "S", "true"));
        assertTrue(o.changed().isEmpty());
        assertTrue(o.ignored().isEmpty());
        assertEquals(files.get("/final.fsh"), applied(o, files, "/final.fsh"));
    }

    @Test
    void twoDefaultsMakeAnOptionAmbiguous() throws Exception {
        Map<String, String> files = Map.of(
                "/final.fsh", "#define Q 1 // [1 2]\n",
                "/composite.fsh", "#define Q 2 // [1 2]\n");
        PackOptions o = discover(files, Map.of("Q", "2"));
        assertFalse(o.stringOptions().containsKey("Q"));
        assertEquals(List.of("Q"), o.ambiguous());
        assertEquals(Map.of("Q", "not an option of this pack"), o.ignored());
    }

    @Test
    void constOptions() throws Exception {
        Map<String, String> files = Map.of("/shadow.fsh",
                "const int shadowMapResolution = 2048; //[1024 2048 4096]\n"
                        + "const float notADirective = 1.0; //[1.0 2.0]\n"
                        + "const bool shadowHardwareFiltering = false;\n");
        PackOptions o = discover(files, Map.of("shadowMapResolution", "4096", "shadowHardwareFiltering", "true"));
        assertFalse(o.stringOptions().containsKey("notADirective"));
        // Iris's getOptionSet filters every on/off option by #ifdef references, const bools too.
        assertEquals(Map.of("shadowHardwareFiltering", "not an option of this pack"), o.ignored());
        assertEquals("const int shadowMapResolution = 4096; //[1024 2048 4096]\n"
                        + "const float notADirective = 1.0; //[1.0 2.0]\n"
                        + "const bool shadowHardwareFiltering = false;\n",
                applied(o, files, "/shadow.fsh"));

        Map<String, String> referenced = Map.of("/shadow.fsh",
                "const bool shadowHardwareFiltering = false;\n#ifdef shadowHardwareFiltering\n#endif\n");
        assertEquals("const bool shadowHardwareFiltering = true;\n#ifdef shadowHardwareFiltering\n#endif\n",
                applied(discover(referenced, Map.of("shadowHardwareFiltering", "true")), referenced, "/shadow.fsh"));
    }

    @Test
    void numbersAsIrisReadsThem() throws Exception {
        Map<String, String> files = Map.of("/final.fsh",
                "#define NEG -1 //[-1 0 1]\n#define F 0.5f //[0.5f]\n#define W WORD //[WORD OTHER]\n");
        PackOptions o = discover(files, Map.of());
        assertEquals("-1", o.stringOptions().get("NEG").defaultValue());
        assertEquals("0.5f", o.stringOptions().get("F").defaultValue());
        assertEquals("WORD", o.stringOptions().get("W").defaultValue());
    }

    @Test
    void propertyDefinesFollowIrisOrder() throws Exception {
        Map<String, String> files = Map.of("/final.fsh",
                "#define S\n//#define T\n#ifdef S\n#endif\n#ifdef T\n#endif\n#define Q 2 // [1 2]\n");
        Map<String, String> d = discover(files, Map.of("T", "true", "Q", "1"))
                .propertyDefines(Map.of("MC_VERSION", "260300"));
        assertEquals(Map.of("S", "", "T", "", "MC_VERSION", "260300", "Q", "1"), d);
    }

    @Test
    void irisStartNames() {
        for (String yes : List.of("composite.fsh", "composite1.vsh", "composite99.fsh", "deferred1_b.csh",
                "shadow.csh", "shadow_a.csh", "final.csh", "gbuffers_terrain.vsh", "dh_water.fsh", "begin.gsh")) {
            assertTrue(PackOptions.isIrisStart(yes), yes);
        }
        for (String no : List.of("composite0.fsh", "composite100.fsh", "clrwl_shadow.vsh", "gbuffers_terrain.csh",
                "voxy_opaque.glsl", "gbuffers_foo.vsh", "shadow_entities.csh")) {
            assertFalse(PackOptions.isIrisStart(no), no);
        }
    }

    /** On the real pack: settings reach the preprocessed program and the .properties defines. */
    @Test
    void realPack() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/; skipping");
        Path pack = packs.getFirst();
        PackInfo info = PackReader.read(pack);
        PackOptions defaults = PackReader.open(pack, (n, r, shaders) ->
                PackOptions.discover(p -> PackReader.readText(shaders, p), PackOptions.starts(info), Map.of()));
        PackOptions grey = PackReader.open(pack, (n, r, shaders) ->
                PackOptions.discover(p -> PackReader.readText(shaders, p), PackOptions.starts(info),
                        Map.of("T_SATURATION", "0.00")));
        System.out.printf("%s: %d on/off options, %d value options, ambiguous %s%n", pack.getFileName(),
                defaults.booleanOptions().size(), defaults.stringOptions().size(), defaults.ambiguous());
        if (defaults.stringOptions().containsKey("T_SATURATION")) {
            assertEquals(Map.of("T_SATURATION", "0.00"), grey.changed());
            var before = PackPreprocessor.preprocessProgram(pack, "/world0/final.fsh", StandardDefines.forMac(), defaults);
            var after = PackPreprocessor.preprocessProgram(pack, "/world0/final.fsh", StandardDefines.forMac(), grey);
            assertTrue(before.ok() && after.ok());
            assertNotEquals(before.result().text(), after.result().text(), "the change reaches the program");
        }
        Map<String, String> props = defaults.propertyDefines(Map.of());
        assertFalse(props.isEmpty());
        java.nio.file.Files.createDirectories(Path.of("build"));
        java.nio.file.Files.writeString(Path.of("build/pack-options.txt"), defaults.describe(pack.getFileName().toString()));
    }
}
