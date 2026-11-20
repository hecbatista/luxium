package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockIdsTest {
    @Test
    void entryShapes() {
        assertEquals(new BlockIds.Entry("minecraft", "stone", Map.of()), BlockIds.entry("stone"));
        assertEquals(new BlockIds.Entry("mod", "rock", Map.of()), BlockIds.entry("mod:rock"));
        assertEquals(new BlockIds.Entry("minecraft", "tall_grass", Map.of("half", "lower")), BlockIds.entry("tall_grass:half=lower"));
        assertEquals(new BlockIds.Entry("mod", "lamp", Map.of("lit", "true", "facing", "up")), BlockIds.entry("mod:lamp:lit=true:facing=up"));
    }

    @Test
    void fileOrderContinuationsAndRepeatedKeys() {
        BlockIds ids = BlockIds.parse("""
                # comment
                block.10009=oak_leaves \\
                    birch_leaves
                block.10005=short_grass %minecraft:flowers
                block.10009=spruce_leaves
                block.oops=stone
                """);
        // block.10009 keeps its first place but takes its last value (Iris's OrderBackedProperties).
        assertEquals(List.of(10009, 10005), ids.lines().stream().map(BlockIds.Line::id).toList());
        assertEquals(List.of("spruce_leaves"), ids.lines().get(0).entries().stream().map(BlockIds.Entry::name).toList());
        assertEquals(List.of("%minecraft:flowers", "block.oops"), ids.skipped());
    }

    @Test
    void commentThatReadsLikeADirectiveStaysAComment() throws Exception {
        // Complementary's block.properties opens with "# If you want to add modded blocks..."
        String out = PropertiesPreprocessor.preprocess("# If you want\n#if A\nblock.1=stone\n#endif\n", Map.of("A", "1"));
        assertEquals(List.of(1), BlockIds.parse(out).lines().stream().map(BlockIds.Line::id).toList());
    }

    @Test
    void backslashContinuationJoinsNames() {
        BlockIds ids = BlockIds.parse("block.1=a \\\n b\nblock.2=c\n");
        assertEquals(List.of("a", "b"), ids.lines().get(0).entries().stream().map(BlockIds.Entry::name).toList());
    }
}
