package io.github.hecbatista.metalshaders.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pack's block numbers from block.properties ({@code block.10005=short_grass fern
 * tall_grass:half=lower}), which reach the terrain program as mc_Entity. Rules follow Iris
 * (IdMap.parseBlockMap, BlockEntry.parse, BlockMaterialMapping; see NOTICE):
 * <ul>
 *   <li>Keys keep the order they first appear in, and a key written twice keeps its first place
 *       with its last value (Iris's OrderBackedProperties). Order matters: the first id that
 *       matches a block state wins.</li>
 *   <li>An entry is {@code name}, {@code namespace:name}, {@code name:prop=value...} or
 *       {@code namespace:name:prop=value...}; no namespace means minecraft. Property filters
 *       match every state that has those values, whatever its other properties.</li>
 *   <li>Tag entries ({@code %tag}) aren't supported yet and are counted as skipped.</li>
 * </ul>
 * This class only parses; {@code sodium.BlockStateIds} matches the entries against the game's blocks.
 */
public final class BlockIds {
    /** One name in a block.N line. */
    public record Entry(String namespace, String name, Map<String, String> properties) {
    }

    /** One block.N line, in file order. */
    public record Line(int id, List<Entry> entries) {
    }

    private final List<Line> lines;
    private final List<String> skipped;

    private BlockIds(List<Line> lines, List<String> skipped) {
        this.lines = lines;
        this.skipped = skipped;
    }

    public static final BlockIds EMPTY = new BlockIds(List.of(), List.of());

    /** Parses an already preprocessed block.properties. */
    public static BlockIds parse(String properties) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : readOrdered(properties).entrySet()) {
            ordered.put(e.getKey(), e.getValue());
        }
        List<Line> lines = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, String> e : ordered.entrySet()) {
            if (!e.getKey().startsWith("block.")) {
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(e.getKey().substring("block.".length()));
            } catch (NumberFormatException ex) {
                skipped.add(e.getKey());
                continue;
            }
            List<Entry> entries = new ArrayList<>();
            for (String part : e.getValue().strip().split("\\s+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (part.startsWith("%")) {
                    skipped.add(part);
                    continue;
                }
                Entry entry = entry(part);
                if (entry == null) {
                    skipped.add(part);
                } else {
                    entries.add(entry);
                }
            }
            lines.add(new Line(id, List.copyOf(entries)));
        }
        return new BlockIds(List.copyOf(lines), List.copyOf(skipped));
    }

    /** BlockEntry.parse's shapes; null if a filter isn't key=value. */
    static Entry entry(String part) {
        String[] split = part.split(":");
        if (split.length == 1) {
            return new Entry("minecraft", part, Map.of());
        }
        if (split.length == 2 && !split[1].contains("=")) {
            return new Entry(split[0], split[1], Map.of());
        }
        int statesStart = split[1].contains("=") ? 1 : 2;
        String namespace = statesStart == 1 ? "minecraft" : split[0];
        String name = statesStart == 1 ? split[0] : split[1];
        Map<String, String> filters = new LinkedHashMap<>();
        for (int i = statesStart; i < split.length; i++) {
            String[] kv = split[i].split("=");
            if (kv.length != 2) {
                return null;
            }
            filters.put(kv[0], kv[1]);
        }
        return new Entry(namespace, name, Map.copyOf(filters));
    }

    /**
     * A small .properties reader that keeps key order: {@code #}/{@code !} comments, {@code =} or
     * {@code :} separators, and lines continued with a trailing backslash.
     */
    static Map<String, String> readOrdered(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        StringBuilder logical = new StringBuilder();
        for (String raw : text.split("\r?\n", -1)) {
            String line = logical.isEmpty() ? raw.stripLeading() : raw.strip();
            if (logical.isEmpty() && (line.isEmpty() || line.startsWith("#") || line.startsWith("!"))) {
                continue;
            }
            int slashes = 0;
            for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
                slashes++;
            }
            if (slashes % 2 == 1) {
                logical.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            logical.append(line);
            String whole = logical.toString();
            logical.setLength(0);
            int sep = -1;
            for (int i = 0; i < whole.length(); i++) {
                char c = whole.charAt(i);
                if (c == '=' || c == ':') {
                    sep = i;
                    break;
                }
            }
            if (sep < 0) {
                out.put(whole.strip(), "");
            } else {
                out.put(whole.substring(0, sep).strip(), whole.substring(sep + 1).strip());
            }
        }
        return out;
    }

    public List<Line> lines() {
        return lines;
    }

    public List<String> skipped() {
        return skipped;
    }
}
