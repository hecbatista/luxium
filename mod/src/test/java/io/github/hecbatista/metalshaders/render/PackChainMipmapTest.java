package io.github.hecbatista.metalshaders.render;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reading colortexNMipmapEnabled (and the legacy names) from a program's fragment source. */
class PackChainMipmapTest {
    @Test
    void readsColortexAndLegacyNames() {
        String fs = """
                const bool colortex0MipmapEnabled = true;
                const  bool  gaux2MipmapEnabled=true ;
                const bool colortex7MipmapEnabled = false;
                void main() {}
                """;
        assertEquals(Set.of(0, 5), PackChain.mipmappedBuffers(fs));
    }

    @Test
    void laterFalseWins() {
        String fs = "const bool colortex3MipmapEnabled = true;\nconst bool compositeMipmapEnabled = false;\n";
        assertEquals(Set.of(), PackChain.mipmappedBuffers(fs));
    }

    @Test
    void ignoresOtherConsts() {
        assertEquals(Set.of(), PackChain.mipmappedBuffers("const bool colortex0Clear = false;\nconst int colortex0Format = RGBA16F;"));
    }
}
