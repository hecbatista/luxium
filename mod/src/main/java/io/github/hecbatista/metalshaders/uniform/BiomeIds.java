package io.github.hecbatista.metalshaders.uniform;

import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
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

    /**
     * Iris's biome categories (BiomeCategories), in its order: {@code biome_category} is the
     * index, {@code CAT_<NAME>} the define.
     */
    public enum Category {
        NONE, TAIGA, EXTREME_HILLS, JUNGLE, MESA, PLAINS, SAVANNA, ICY, THE_END, BEACH, FOREST, OCEAN, DESERT,
        RIVER, SWAMP, MUSHROOM, NETHER, MOUNTAIN, UNDERGROUND
    }

    /**
     * {@code BIOME_PLAINS -> "1"} and so on, then {@code CAT_<NAME>} and {@code PPT_NONE/RAIN/SNOW},
     * as Iris's IrisDefines.createIrisReplacements adds them for the preprocessors.
     */
    public static synchronized Map<String, String> defines() {
        Map<String, String> out = new LinkedHashMap<>();
        IDS.forEach((key, id) -> out.put("BIOME_" + key.identifier().getPath().toUpperCase(Locale.ROOT), Integer.toString(id)));
        for (Category c : Category.values()) {
            out.put("CAT_" + c.name(), Integer.toString(c.ordinal()));
        }
        out.put("PPT_NONE", "0");
        out.put("PPT_RAIN", "1");
        out.put("PPT_SNOW", "2");
        return out;
    }

    /** A biome's category, by its tags, in Iris's order of tests (BiomeUniforms.getBiomeCategory). */
    public static Category category(net.minecraft.core.Holder<Biome> holder) {
        if (holder.is(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS)) {
            return Category.NONE;   // only the void has it
        } else if (holder.is(BiomeTags.HAS_VILLAGE_SNOWY)) {
            return Category.ICY;
        } else if (holder.is(BiomeTags.IS_HILL)) {
            return Category.EXTREME_HILLS;
        } else if (holder.is(BiomeTags.IS_TAIGA)) {
            return Category.TAIGA;
        } else if (holder.is(BiomeTags.IS_OCEAN)) {
            return Category.OCEAN;
        } else if (holder.is(BiomeTags.IS_JUNGLE)) {
            return Category.JUNGLE;
        } else if (holder.is(BiomeTags.IS_FOREST)) {
            return Category.FOREST;
        } else if (holder.is(BiomeTags.IS_BADLANDS)) {
            return Category.MESA;
        } else if (holder.is(BiomeTags.IS_NETHER)) {
            return Category.NETHER;
        } else if (holder.is(BiomeTags.IS_END)) {
            return Category.THE_END;
        } else if (holder.is(BiomeTags.IS_BEACH)) {
            return Category.BEACH;
        } else if (holder.is(BiomeTags.HAS_DESERT_PYRAMID)) {
            return Category.DESERT;
        } else if (holder.is(BiomeTags.IS_RIVER)) {
            return Category.RIVER;
        } else if (holder.is(BiomeTags.ALLOWS_SURFACE_SLIME_SPAWNS)) {
            return Category.SWAMP;
        } else if (holder.is(BiomeTags.WITHOUT_ZOMBIE_SIEGES)) {
            return Category.MUSHROOM;
        } else if (holder.is(BiomeTags.IS_MOUNTAIN)) {
            return Category.MOUNTAIN;
        }
        return Category.PLAINS;
    }

    private static final java.lang.invoke.MethodHandle DOWNFALL = downfallHandle();

    /** Biome.climateSettings.downfall: both private in 26.3 (Iris reads it through a mixin). */
    private static java.lang.invoke.MethodHandle downfallHandle() {
        try {
            java.lang.reflect.Field field = Biome.class.getDeclaredField("climateSettings");
            field.setAccessible(true);
            java.lang.reflect.Method downfall = field.getType().getDeclaredMethod("downfall");
            downfall.setAccessible(true);
            java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
            return java.lang.invoke.MethodHandles.filterReturnValue(lookup.unreflectGetter(field), lookup.unreflect(downfall));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The biome's downfall (Iris's rainfall uniform), or 0 if it can't be read. */
    public static float downfall(Biome biome) {
        try {
            return DOWNFALL == null ? 0f : (float) DOWNFALL.invoke(biome);
        } catch (Throwable t) {
            return 0f;
        }
    }
}
