package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemIdsTest {
    @Test
    void parsesNamesWithAndWithoutNamespace() throws Exception {
        ItemIds ids = ItemIds.parse("""
                item.40000=spider_eye fermented_spider_eye
                item.44001=minecraft:torch othermod:glow_stick
                item.45000=lantern[hanging=true] lantern:hanging=true
                item.bad=stone
                """);
        assertEquals(40000, ids.of("minecraft:spider_eye"));
        assertEquals(40000, ids.of("minecraft:fermented_spider_eye"));
        assertEquals(44001, ids.of("minecraft:torch"));
        assertEquals(44001, ids.of("othermod:glow_stick"));
        assertEquals(-1, ids.of("minecraft:dirt"));              // not listed
        assertEquals(Set.of("lantern[hanging=true]", "lantern:hanging=true", "item.bad"), Set.copyOf(ids.skipped()));
    }
}
