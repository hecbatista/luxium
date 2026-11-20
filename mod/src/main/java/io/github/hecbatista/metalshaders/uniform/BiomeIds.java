package io.github.hecbatista.metalshaders.uniform;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Biome numbers, in vanilla registration order (filled by BiomesMixin), and the
 * {@code BIOME_<NAME>} defines packs use in shaders.properties. Same numbering as Iris.
 */
public final class BiomeIds {
    private static final Map<ResourceKey<Biome>, Integer> IDS = new LinkedHashMap<>();

    private BiomeIds() {
    }

    public static synchronized void add(ResourceKey<Biome> key) {
        IDS.putIfAbsent(key, IDS.size());
    }

    /** The biome's number, or 0 if unknown (Iris's map also answers 0 for a missing key). */
    public static synchronized int of(ResourceKey<Biome> key) {
        return key == null ? 0 : IDS.getOrDefault(key, 0);
    }

    /** {@code BIOME_PLAINS -> "1"} and so on, for the properties preprocessor. */
    public static synchronized Map<String, String> defines() {
        Map<String, String> out = new LinkedHashMap<>();
        IDS.forEach((key, id) -> out.put("BIOME_" + key.identifier().getPath().toUpperCase(Locale.ROOT), Integer.toString(id)));
        return out;
    }
}
