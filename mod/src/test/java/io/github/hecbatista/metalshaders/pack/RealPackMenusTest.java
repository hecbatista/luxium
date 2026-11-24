package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The menus of the real Complementary Unbound r5.10 in run/shaderpacks/ (skipped without it; its
 * license forbids committing it): 51 screen lines, 8 profiles, every option a screen names exists,
 * every link leads to a screen, and each profile is found again once its values are applied.
 */
class RealPackMenusTest {
    private static final Path PACK = Path.of("run/shaderpacks/ComplementaryUnbound_r5.10.zip");

    @Test
    void complementaryMenus() throws IOException {
        assumeTrue(Files.isRegularFile(PACK), "Complementary Unbound r5.10 isn't in run/shaderpacks/; skipping");
        PackOptions options = PackOptions.load(PACK);
        String properties = PackReader.open(PACK, (n, r, shaders) -> PackReader.readText(shaders, "/shaders.properties").orElseThrow());
        PackMenus menus = PackMenus.parse(properties, options.booleanOptions().keySet(), options.stringOptions().keySet());
        System.out.println("Complementary menus: " + menus.screenLines() + " screen lines, " + menus.subScreens().size()
                + " sub-screens, " + menus.profiles().size() + " profiles, warnings: " + menus.warnings());

        assertEquals(51, menus.screenLines());
        assertEquals(8, menus.profiles().size());
        assertEquals(List.of("POTATO", "VERYLOW", "LOW", "MEDIUM", "HIGH", "VERYHIGH", "ULTRA", "COMPLEMENTARY"),
                menus.profiles().stream().map(PackMenus.Profile::name).toList());

        // Every option a screen names exists (unknown names would be warnings and empty slots).
        assertTrue(menus.warnings().stream().noneMatch(w -> w.startsWith("screen")), () -> "screen warnings: " + menus.warnings());
        // Every link leads to a real sub-screen, and every sub-screen is reachable from the main one.
        Set<String> reached = new java.util.HashSet<>();
        java.util.ArrayDeque<PackMenus.Screen> todo = new java.util.ArrayDeque<>(List.of(menus.main()));
        while (!todo.isEmpty()) {
            for (PackMenus.Element e : todo.pop().elements()) {
                if (e instanceof PackMenus.Link link) {
                    assertTrue(menus.subScreens().containsKey(link.target()), "link to a missing screen: " + link.target());
                    if (reached.add(link.target())) {
                        todo.add(menus.screen(link.target()));
                    }
                }
            }
        }
        assertEquals(menus.subScreens().keySet(), reached, "sub-screens no link reaches");
        // Sliders are value options.
        for (String s : menus.sliders()) {
            assertTrue(options.stringOptions().containsKey(s), "slider isn't a value option: " + s);
        }
        // Profile entries name real options, with allowed values.
        for (PackMenus.Profile p : menus.profiles()) {
            p.values().forEach((name, value) -> {
                assertTrue(options.has(name), "profile." + p.name() + " names " + name);
                PackOptions.StringOption s = options.stringOptions().get(name);
                if (s != null) {
                    assertTrue(s.allowedValues().contains(value) || s.defaultValue().equals(value),
                            "profile." + p.name() + ": " + name + "=" + value + " not allowed " + s.allowedValues());
                }
            });
        }

        // Applying a profile's values makes the scan find a profile with exactly those values
        // (the first such, in precedence order, as Iris).
        for (PackMenus.Profile p : menus.profiles()) {
            Map<String, String> values = new HashMap<>();
            options.booleanOptions().keySet().forEach(n -> values.put(n, options.defaultValue(n)));
            options.stringOptions().keySet().forEach(n -> values.put(n, options.defaultValue(n)));
            values.putAll(p.values());
            PackMenus.Profile found = menus.scan(values::get, options::has).current().orElseThrow(
                    () -> new AssertionError("profile " + p.name() + " not found after applying it"));
            Map<String, String> foundValues = new HashMap<>(found.values());
            assertEquals(p.values(), foundValues, "applying " + p.name() + " finds " + found.name());
        }
        // The pack's plain defaults: which profile do they match? (Iris shows the same.)
        Map<String, String> defaults = new HashMap<>();
        options.booleanOptions().keySet().forEach(n -> defaults.put(n, options.defaultValue(n)));
        options.stringOptions().keySet().forEach(n -> defaults.put(n, options.defaultValue(n)));
        System.out.println("Complementary defaults match profile: "
                + menus.scan(defaults::get, options::has).current().map(PackMenus.Profile::name).orElse("Custom"));

        PackLang lang = PackLang.load(PACK, "en_us");
        assertTrue(lang.size() > 100);
        assertTrue(lang.get("screen.PERFORMANCE_SETTINGS").isPresent());
        assertFalse(lang.get("option.SHADOW_QUALITY").orElse("").isEmpty());
    }

    @Test
    void settingsFileKeepsOnlyChangedValuesAndIsDeletedWhenEmpty(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        assumeTrue(Files.isRegularFile(PACK), "Complementary Unbound r5.10 isn't in run/shaderpacks/; skipping");
        Path pack = dir.resolve(PACK.getFileName());
        Files.copy(PACK, pack);
        PackOptions options = PackOptions.load(pack);
        Map<String, String> values = new HashMap<>();
        values.put("T_SATURATION", "0.00");
        values.put("SHADOW_QUALITY", options.defaultValue("SHADOW_QUALITY"));   // default: not written
        values.put("NOT_AN_OPTION", "1");                                       // unknown: not written
        values.put("BLOOM_FOG", "false");
        assertEquals(Map.of("T_SATURATION", "0.00", "BLOOM_FOG", "false"), options.writeSettings(pack, values));
        assertEquals(Map.of("BLOOM_FOG", "false", "T_SATURATION", "0.00"), PackOptions.readSettings(PackOptions.settingsFile(pack)));
        PackOptions reread = PackOptions.load(pack);
        assertEquals("0.00", reread.value("T_SATURATION"));
        assertEquals("false", reread.value("BLOOM_FOG"));
        options.writeSettings(pack, Map.of("T_SATURATION", options.defaultValue("T_SATURATION")));
        assertFalse(Files.exists(PackOptions.settingsFile(pack)));
    }
}
