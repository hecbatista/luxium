package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackSelectionTest {
    private static final List<Path> PACKS = List.of(
            Path.of("BSL_v10.1.8.zip"),
            Path.of("ComplementaryReimagined_r5.10.zip"),
            Path.of("ComplementaryUnbound_r5.10.zip"),
            Path.of("MyFolderPack"));

    private static String pick(String wanted) {
        return PackSelection.choose(PACKS, wanted).orElseThrow().getFileName().toString();
    }

    @Test
    void withoutANameItIsTheFirstPack() {
        assertEquals("BSL_v10.1.8.zip", pick(null));
        assertEquals("BSL_v10.1.8.zip", pick("  "));
    }

    @Test
    void aUniquePartOfTheNameInAnyCasePicksThePack() {
        assertEquals("ComplementaryUnbound_r5.10.zip", pick("unbound"));
        assertEquals("MyFolderPack", pick("folder"));
    }

    @Test
    void anExactNameWinsEvenWhenItIsAlsoPartOfAnother() {
        assertEquals("ComplementaryReimagined_r5.10.zip", pick("ComplementaryReimagined_r5.10"));
        assertEquals("ComplementaryReimagined_r5.10.zip", pick("complementaryreimagined_r5.10.zip"));
    }

    @Test
    void noMatchOrSeveralMatchesFailLoudly() {
        assertThrows(IllegalArgumentException.class, () -> pick("photon"));
        assertThrows(IllegalArgumentException.class, () -> pick("complementary"));
    }

    @Test
    void noPacksMeansNothing() {
        assertEquals(Optional.empty(), PackSelection.choose(List.of(), "bsl"));
    }
}
