package io.github.hecbatista.metalshaders.pack;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The pack's item numbers from item.properties ({@code item.40000=spider_eye fermented_spider_eye}),
 * used for {@code heldItemId}. Rules follow Iris's IdMap.parseIdMap (see NOTICE): a name without
 * a namespace means "minecraft:", entries with block-state properties ({@code a=b}) are skipped,
 * and an item the pack doesn't list is -1.
 */
public final class ItemIds {
    private final Map<String, Integer> ids;
    private final List<String> skipped;

    private ItemIds(Map<String, Integer> ids, List<String> skipped) {
        this.ids = ids;
        this.skipped = skipped;
    }

    public static final ItemIds EMPTY = new ItemIds(Map.of(), List.of());

    /** Parses an already preprocessed item.properties. */
    public static ItemIds parse(String properties) throws IOException {
        return parse(properties, "item.");
    }

    /**
     * Parses an already preprocessed id file with the given key prefix: "item." for
     * item.properties, "entity." for entity.properties (Iris IdMap.parseEntityIdMap uses the
     * same rules as items).
     */
    public static ItemIds parse(String properties, String prefix) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(properties));
        Map<String, Integer> ids = new HashMap<>();
        List<String> skipped = new ArrayList<>();
        for (String key : p.stringPropertyNames().stream().sorted().toList()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(key.substring(prefix.length()));
            } catch (NumberFormatException e) {
                skipped.add(key);
                continue;
            }
            for (String part : p.getProperty(key).strip().split("\\s+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (part.contains("=")) {
                    skipped.add(part);
                    continue;
                }
                ids.put(part.contains(":") ? part : "minecraft:" + part, id);
            }
        }
        return new ItemIds(Map.copyOf(ids), List.copyOf(skipped));
    }

    /** The pack's number for an item like "minecraft:torch", or -1. */
    public int of(String namespacedId) {
        return ids.getOrDefault(namespacedId, -1);
    }

    /** Whether the pack lists this name. */
    public boolean has(String namespacedId) {
        return ids.containsKey(namespacedId);
    }

    public int size() {
        return ids.size();
    }

    public List<String> skipped() {
        return skipped;
    }
}
