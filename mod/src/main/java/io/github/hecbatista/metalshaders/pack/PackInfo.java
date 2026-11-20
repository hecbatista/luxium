package io.github.hecbatista.metalshaders.pack;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * What {@link PackReader} found in one shader pack. Plain data: it holds no open files
 * or paths into the pack, so it is safe to keep after the pack's zip is closed.
 *
 * @param name               the folder name, or the zip's file name without ".zip"
 * @param shadersRoot        where the "shaders" folder sits inside the pack, e.g. "shaders"
 *                           or "SomePack-main/shaders"
 * @param fromDimensionFile  true if the world-to-folder map came from dimension.properties,
 *                           false if it was guessed from world0 / world-1 / world1 folders
 * @param dimensionFolders   world id (like "minecraft:the_nether", or "*" for any world)
 *                           mapped to the folder that holds its programs
 * @param programFiles       folder (relative to shaders/, "" for the root) mapped to the
 *                           program files directly inside it, sorted by name
 */
public record PackInfo(
        String name,
        String shadersRoot,
        boolean fromDimensionFile,
        SortedMap<String, String> dimensionFolders,
        SortedMap<String, List<String>> programFiles
) {
    public PackInfo {
        dimensionFolders = java.util.Collections.unmodifiableSortedMap(dimensionFolders);
        programFiles = java.util.Collections.unmodifiableSortedMap(programFiles);
    }

    /**
     * The folder whose programs draw a world, by Iris's rules: the world's id (after
     * {@link #worldKey}) if the pack names it, else the folder of "*", else the shaders/ root
     * (ShaderPack.getProgramSet and its {@code base}). A named folder that holds no programs also
     * falls back, as Iris's {@code dimensionIds} check does.
     */
    public String folderFor(String worldId) {
        String folder = lookup(worldId);
        if (folder != null && programFiles.containsKey(folder)) {
            return folder;
        }
        String star = lookup("*");
        return star != null && programFiles.containsKey(star) ? star : "";
    }

    private String lookup(String id) {
        // dimension.properties may leave out "minecraft:" (Iris NamespacedId(String)).
        for (Map.Entry<String, String> e : dimensionFolders.entrySet()) {
            String key = e.getKey().equals("*") || e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
            if (key.equals(id)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Iris.getCurrentDimension: a world the pack names keeps its own id; otherwise a world with
     * the End's sky counts as the End and one with the Overworld's sky as the Overworld, so mod
     * worlds get matching programs. {@code skybox} is "end", "overworld" or "none".
     */
    public String worldKey(String worldId, String skybox) {
        if (lookup(worldId) != null) {
            return worldId;
        }
        return switch (skybox) {
            case "end" -> "minecraft:the_end";
            case "overworld" -> "minecraft:overworld";
            default -> worldId;
        };
    }

    /** A few lines for the game log. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append("Pack \"").append(name).append("\" (shaders folder: ").append(shadersRoot).append(")\n");
        out.append("  Worlds, from ")
                .append(fromDimensionFile ? "dimension.properties" : "world folders")
                .append(":\n");
        for (Map.Entry<String, String> entry : dimensionFolders.entrySet()) {
            out.append("    ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append("/\n");
        }
        out.append("  Program files:\n");
        for (Map.Entry<String, List<String>> entry : programFiles.entrySet()) {
            String folder = entry.getKey().isEmpty() ? "(root)" : entry.getKey() + "/";
            out.append("    ").append(folder).append(": ").append(entry.getValue().size()).append(" files\n");
        }
        return out.toString().stripTrailing();
    }
}
