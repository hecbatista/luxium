package io.github.hecbatista.metalshaders.sodium;

import io.github.hecbatista.metalshaders.pack.BlockIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pack's block.properties matched against the game's block states, as Iris's
 * BlockMaterialMapping.addBlockStates does (see NOTICE): lines in file order, the first id that
 * matches a state wins, an unknown block or property is skipped. The result is the encoded word
 * LuxiumChunkVertex writes for each block: {@code (id + 1) << 1}, the low bit being Iris's
 * "fluid" flag (0 for blocks), so a block the pack doesn't list decodes to -1.
 */
public final class BlockStateIds {
    private static volatile Map<BlockState, Integer> encoded = Map.of();
    private static volatile int unknown;

    private BlockStateIds() {
    }

    /** Builds the map from the parsed file. Call after the block registry is ready. */
    public static int load(BlockIds ids) {
        Map<BlockState, Integer> map = new IdentityHashMap<>();
        int missing = 0;
        for (BlockIds.Line line : ids.lines()) {
            for (BlockIds.Entry entry : line.entries()) {
                Identifier name = Identifier.tryBuild(entry.namespace(), entry.name());
                Block block = name == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(name);
                if (block == Blocks.AIR) {
                    missing++;
                    continue;
                }
                StateDefinition<Block, BlockState> states = block.getStateDefinition();
                Map<Property<?>, String> filters = new LinkedHashMap<>();
                entry.properties().forEach((key, value) -> {
                    Property<?> p = states.getProperty(key);
                    if (p != null) {
                        filters.put(p, value);   // Iris warns and ignores an unknown property
                    }
                });
                for (BlockState state : states.getPossibleStates()) {
                    if (matches(state, filters)) {
                        map.putIfAbsent(state, encode(line.id()));
                    }
                }
            }
        }
        encoded = map;
        unknown = missing;
        return map.size();
    }

    /** For the log: the pack's id for a block's default state, or -1. */
    public static int idOf(String name) {
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(name));
        return (of(block.defaultBlockState()) >> 1) - 1;
    }

    public static int unknownNames() {
        return unknown;
    }

    private static boolean matches(BlockState state, Map<Property<?>, String> filters) {
        for (Map.Entry<Property<?>, String> f : filters.entrySet()) {
            if (!valueName(state, f.getKey()).equals(f.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    static int encode(int id) {
        return (id + 1) << 1;
    }

    /** The word for a fluid's own block state: the id with Iris's fluid bit (1 alone if unlisted). */
    public static int ofFluid(BlockState fluidBlock) {
        Integer id = encoded.get(fluidBlock);
        return (id == null ? 0 : id) | 1;
    }

    /** The word for a state, 0 (= -1 for the pack) if the pack doesn't list it. */
    public static int of(BlockState state) {
        Integer id = encoded.get(state);
        return id == null ? 0 : id;
    }
}
