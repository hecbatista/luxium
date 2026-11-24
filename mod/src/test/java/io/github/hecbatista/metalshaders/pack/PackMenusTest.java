package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackMenusTest {
    private static final List<String> BOOLEANS = List.of("SHADOWS", "BLOOM", "FOG", "RAIN");
    private static final List<String> VALUES = List.of("QUALITY", "DISTANCE", "STYLE");

    private static PackMenus parse(String text) {
        return PackMenus.parse(text, BOOLEANS, VALUES);
    }

    @Test
    void elementsLinksAndSliders() {
        PackMenus m = parse("""
                screen=<empty> <profile> [LIGHT] QUALITY DISTANCE NOPE
                screen.LIGHT=SHADOWS BLOOM [MISSING]
                screen.LIGHT.columns=1
                sliders=DISTANCE SHADOWS
                profile.LOW=!SHADOWS QUALITY=1
                """);
        List<PackMenus.Element> main = m.main().elements();
        assertEquals(6, main.size());
        assertInstanceOf(PackMenus.Empty.class, main.get(0));
        assertInstanceOf(PackMenus.ProfileSlot.class, main.get(1));
        assertEquals(new PackMenus.Link("LIGHT"), main.get(2));
        assertEquals(new PackMenus.OptionSlot("QUALITY", false), main.get(3));
        assertEquals(new PackMenus.OptionSlot("DISTANCE", true), main.get(4));
        assertInstanceOf(PackMenus.Empty.class, main.get(5));   // unknown name: an empty slot and a warning
        assertTrue(m.warnings().stream().anyMatch(w -> w.contains("NOPE")));
        assertEquals(2, m.main().columns());

        PackMenus.Screen light = m.screen("LIGHT");
        assertEquals(1, light.columns());
        assertEquals("LIGHT", light.id());
        // On/off options never get sliders; a link to a missing screen opens the main one.
        assertEquals(new PackMenus.OptionSlot("SHADOWS", false), light.elements().get(0));
        assertEquals(m.main(), m.screen("MISSING"));
        assertEquals(2, m.screenLines());
        assertFalse(m.subScreens().containsKey("LIGHT.columns"));
    }

    @Test
    void noScreenLineMeansEveryOptionAndProfileSlotsNeedProfiles() {
        PackMenus m = parse("sliders=QUALITY\n");
        assertEquals(7, m.main().elements().size());
        assertEquals(new PackMenus.OptionSlot("QUALITY", true), m.main().elements().get(4));

        PackMenus noProfiles = parse("screen=<profile> FOG\n");
        assertEquals(List.of(new PackMenus.OptionSlot("FOG", false)), noProfiles.main().elements());
    }

    @Test
    void starTakesWhatNoScreenNamesAndColumnsGrowPast18() {
        PackMenus m = parse("""
                screen=FOG * [A]
                screen.A=QUALITY
                screen.B=*
                """);
        List<PackMenus.Element> main = m.main().elements();
        // FOG, then the rest except QUALITY (named on A), then the link.
        assertEquals(List.of(new PackMenus.OptionSlot("FOG", false), new PackMenus.OptionSlot("SHADOWS", false),
                new PackMenus.OptionSlot("BLOOM", false), new PackMenus.OptionSlot("RAIN", false),
                new PackMenus.OptionSlot("DISTANCE", false), new PackMenus.OptionSlot("STYLE", false),
                new PackMenus.Link("A")), main);
        assertTrue(m.screen("B").elements().isEmpty());   // the first "*" took them all

        String many = "screen=" + "<empty> ".repeat(19) + "\n";
        assertEquals(3, parse(many).main().columns());
        assertEquals(2, parse("screen=" + "<empty> ".repeat(18) + "\n").main().columns());
        assertEquals(4, parse("screen=FOG\nscreen.columns=4\n").main().columns());
    }

    @Test
    void continuationLinesAndIndentedKeys() {
        PackMenus m = parse("""
                    screen=FOG \\
                      BLOOM
                """);
        assertEquals(List.of(new PackMenus.OptionSlot("FOG", false), new PackMenus.OptionSlot("BLOOM", false)), m.main().elements());
    }

    @Test
    void profilesIncludesAndCycles() {
        PackMenus m = parse("""
                profile.LOW=!SHADOWS QUALITY=1 !program.composite2
                profile.MID=profile.LOW QUALITY:2 BLOOM
                profile.HIGH=profile.MID SHADOWS DISTANCE=8 UNKNOWN
                profile.LOOP=profile.LOOP2
                profile.LOOP2=profile.LOOP
                """);
        Map<String, PackMenus.Profile> byName = new HashMap<>();
        m.profiles().forEach(p -> byName.put(p.name(), p));
        assertEquals(Map.of("SHADOWS", "false", "QUALITY", "1"), byName.get("LOW").values());
        assertEquals(List.of("composite2"), byName.get("LOW").disabledPrograms());
        assertEquals(Map.of("SHADOWS", "false", "QUALITY", "2", "BLOOM", "true"), byName.get("MID").values());
        assertEquals(Map.of("SHADOWS", "true", "QUALITY", "2", "BLOOM", "true", "DISTANCE", "8"), byName.get("HIGH").values());
        assertEquals(List.of("composite2"), byName.get("HIGH").disabledPrograms());
        assertFalse(byName.containsKey("LOOP"));
        assertTrue(m.warnings().stream().anyMatch(w -> w.contains("includes itself")));
        assertTrue(m.warnings().stream().anyMatch(w -> w.contains("UNKNOWN")));
    }

    @Test
    void scanFindsTheProfileWithMostEntriesFirstAndWraps() {
        PackMenus m = parse("""
                profile.A=QUALITY=1
                profile.B=QUALITY=1 FOG
                profile.C=QUALITY=3
                """);
        Map<String, String> values = new HashMap<>(Map.of("QUALITY", "1", "FOG", "true", "SHADOWS", "true"));
        // B has more entries, so it's checked (and found) before A.
        PackMenus.ProfileScan scan = m.scan(values::get, n -> BOOLEANS.contains(n) || VALUES.contains(n));
        assertEquals("B", scan.current().orElseThrow().name());
        assertEquals("A", scan.next().name());       // order: B (2), A (1), C (1, after A in the file)
        assertEquals("C", scan.previous().name());
        values.put("FOG", "false");
        assertEquals("A", m.scan(values::get, n -> true).current().orElseThrow().name());
        values.put("QUALITY", "2");
        PackMenus.ProfileScan custom = m.scan(values::get, n -> true);
        assertTrue(custom.current().isEmpty());
        assertEquals("B", custom.next().name());
        assertEquals("C", custom.previous().name());
        // Options the pack doesn't have aren't compared.
        PackMenus other = parse("profile.X=QUALITY=2 GONE=5\n");
        assertEquals("X", other.scan(values::get, n -> !n.equals("GONE")).current().orElseThrow().name());
        assertNull(parse("screen=FOG\n").scan(values::get, n -> true).next());
    }

    @Test
    void langPrefersThePlayersLanguageAndSurvivesBadBytes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Path pack = dir.resolve("Pack");
        java.nio.file.Path lang = java.nio.file.Files.createDirectories(pack.resolve("shaders/lang"));
        java.nio.file.Files.writeString(pack.resolve("shaders/composite.fsh"), "void main() {}");
        java.nio.file.Files.writeString(lang.resolve("en_US.lang"), "option.FOG=Fog\noption.BLOOM=Bloom\n");
        // Latin-1 "é" (0xE9) is not valid UTF-8.
        java.nio.file.Files.write(lang.resolve("fr_fr.lang"), new byte[]{'o', 'p', 't', 'i', 'o', 'n', '.', 'F', 'O', 'G', '=', 'B', 'r', 'o', 'u', 'i', 'l', 'l', 'a', 'r', 'd', ' ', (byte) 0xE9, '\n'});
        PackLang fr = PackLang.load(pack, "fr_fr");
        assertTrue(fr.get("option.FOG").orElseThrow().startsWith("Brouillard"));
        assertEquals("Bloom", fr.get("option.BLOOM").orElseThrow());   // falls back to English
        assertEquals("Fog", PackLang.load(pack, "de_de").get("option.FOG").orElseThrow());
    }
}
