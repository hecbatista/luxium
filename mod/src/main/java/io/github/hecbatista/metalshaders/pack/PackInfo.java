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
