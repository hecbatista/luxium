package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncludeExpanderTest {
    /** A fake pack: path -> file text. */
    private static IncludeExpander.FileSource pack(Map<String, String> files) {
        return path -> Optional.ofNullable(files.get(path));
    }

    private static List<String> expand(String path, Map<String, String> files)
            throws IncludeExpander.IncludeException, IOException {
        return IncludeExpander.expand(path, pack(files), IncludeExpander.Limits.DEFAULT);
    }

    @Test
    void absoluteAndRelativePaths() throws Exception {
        List<String> lines = expand("/world0/final.fsh", Map.of(
                "/world0/final.fsh", "a\n#include \"/lib/common.glsl\"\n  #include ../lib/other.glsl\nb",
                "/lib/common.glsl", "common",
                "/lib/other.glsl", "other"));

        assertEquals(List.of("a", "common", "other", "b"), lines);
    }

    @Test
    void includesInsideSwitchedOffIfAreStillPasted() throws Exception {
        // #if isn't evaluated yet, so this include happens anyway (same as Iris).
        List<String> lines = expand("/main.fsh", Map.of(
                "/main.fsh", "#if 0\n#include \"/a.glsl\"\n#endif",
                "/a.glsl", "from a"));

        assertEquals(List.of("#if 0", "from a", "#endif"), lines);
    }

    @Test
    void sameFileTwiceIsPastedTwice() throws Exception {
        List<String> lines = expand("/main.fsh", Map.of(
                "/main.fsh", "#include \"/a.glsl\"\n#include \"/a.glsl\"",
                "/a.glsl", "x"));

        assertEquals(List.of("x", "x"), lines);
    }

    @Test
    void cycleIsAnErrorEvenWithIncludeGuards() {
        IncludeExpander.IncludeException e = assertThrows(IncludeExpander.IncludeException.class,
                () -> expand("/main.fsh", Map.of(
                        "/main.fsh", "#include \"/a.glsl\"",
                        "/a.glsl", "#ifndef A\n#define A\n#include \"/b.glsl\"\n#endif",
                        "/b.glsl", "#include \"/a.glsl\"")));

        assertTrue(e.getMessage().contains("/main.fsh -> /a.glsl -> /b.glsl -> /a.glsl"), e.getMessage());
    }

    @Test
    void missingFileSaysWhoIncludedIt() {
        IncludeExpander.IncludeException e = assertThrows(IncludeExpander.IncludeException.class,
                () -> expand("/main.fsh", Map.of("/main.fsh", "ok\n#include \"/nope.glsl\"")));

        assertTrue(e.getMessage().contains("/nope.glsl"), e.getMessage());
        assertTrue(e.getMessage().contains("/main.fsh:2"), e.getMessage());
    }

    @Test
    void dotDotCannotClimbAboveTheShadersFolder() {
        assertEquals("/lib/a.glsl", IncludeExpander.normalize("/../../lib/./a.glsl"));
    }

    @Test
    void hugeExpansionIsStoppedByTheLineCap() {
        // Each level includes the next one twice: 2^30 copies of "x" if nothing stopped it.
        Map<String, String> files = new java.util.HashMap<>();
        for (int i = 0; i < 30; i++) {
            files.put("/f" + i + ".glsl", "#include \"/f" + (i + 1) + ".glsl\"\n#include \"/f" + (i + 1) + ".glsl\"");
        }
        files.put("/f30.glsl", "x");

        IncludeExpander.IncludeException e = assertThrows(IncludeExpander.IncludeException.class,
                () -> IncludeExpander.expand("/f0.glsl", pack(files), new IncludeExpander.Limits(64, 10_000)));

        assertTrue(e.getMessage().contains("10000 lines"), e.getMessage());
    }
}
