package io.github.hecbatista.metalshaders.pack;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Finds shader packs and reads their layout: where the "shaders" folder is, which folder
 * belongs to which world, and which program files each folder holds.
 * <p>
 * It doesn't read or change any shader code; that's a later step. Our own code, written to
 * match the behavior of Iris (branch 26.3, see "Matches Iris" notes). No Iris code is copied.
 */
public final class PackReader {
    /**
     * Extensions that make a file a program (an entry point). Everything else (.glsl, .inc, ...)
     * is only ever pulled in by #include. Matches Iris {@code ShaderPackSourceNames.java:76-96}.
     */
    static final Set<String> PROGRAM_EXTENSIONS = Set.of("vsh", "tcs", "tes", "gsh", "fsh", "csh");

    /** How deep to look for a "shaders" folder in a zip someone wrapped in an extra folder. */
    private static final int MAX_SEARCH_DEPTH = 4;

    private PackReader() {
    }

    /**
     * Lists the packs in a shaderpacks folder: every .zip file, and every folder that has a
     * "shaders" folder directly inside. Sorted by name. Returns an empty list if the folder
     * doesn't exist.
     */
    public static List<Path> findPacks(Path shaderpacksDir) throws IOException {
        if (!Files.isDirectory(shaderpacksDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(shaderpacksDir)) {
            return entries
                    .filter(p -> isZip(p) || Files.isDirectory(p.resolve("shaders")))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    /** Work to do while a pack is open. {@code shaders} is the pack's shaders/ folder. */
    @FunctionalInterface
    public interface OpenPackAction<T> {
        T run(String name, Path packRoot, Path shaders) throws IOException;
    }

    /**
     * Opens a pack (a .zip file or a folder), finds its shaders/ folder, runs {@code action},
     * and closes the pack again. Paths given to {@code action} stop working once it returns.
     */
    public static <T> T open(Path pack, OpenPackAction<T> action) throws IOException {
        String name = pack.getFileName().toString();
        if (isZip(pack)) {
            String zipName = name.substring(0, name.length() - ".zip".length());
            try (FileSystem zip = FileSystems.newFileSystem(pack)) {
                Path root = zip.getPath("/");
                return action.run(zipName, root, shadersFolderOrThrow(zipName, root));
            }
        }
        if (Files.isDirectory(pack)) {
            return action.run(name, pack, shadersFolderOrThrow(name, pack));
        }
        throw new IOException("Not a shader pack (neither a .zip nor a folder): " + name);
    }

    /** Opens a pack, reads its layout, and closes it again. */
    public static PackInfo read(Path pack) throws IOException {
        return open(pack, PackReader::readFrom);
    }

    /**
     * Reads a text file by its path relative to shaders/ ("/lib/common.glsl"). Empty if missing.
     * Refuses paths that would leave shaders/. Bad UTF-8 is replaced rather than failing.
     */
    public static Optional<String> readText(Path shaders, String packPath) throws IOException {
        Path file = shaders.resolve(IncludeExpander.normalize(packPath).substring(1)).normalize();
        if (!file.startsWith(shaders) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    private static Path shadersFolderOrThrow(String name, Path packRoot) throws IOException {
        return findShadersFolder(packRoot)
                .orElseThrow(() -> new IOException("No \"shaders\" folder in pack " + name));
    }

    private static PackInfo readFrom(String name, Path packRoot, Path shaders) throws IOException {
        String shadersRoot = relative(packRoot, shaders);

        SortedMap<String, String> dimensionFolders = loadProperties(shaders.resolve("dimension.properties"))
                .map(PackReader::parseDimensionMap)
                .orElseGet(TreeMap::new);
        boolean fromDimensionFile = !dimensionFolders.isEmpty();
        if (!fromDimensionFile) {
            dimensionFolders = guessDimensionMap(shaders);
        }

        // The root plus every folder some world points at.
        SortedMap<String, List<String>> programFiles = new TreeMap<>();
        programFiles.put("", listPrograms(shaders));
        for (String folder : dimensionFolders.values()) {
            Path dir = shaders.resolve(folder);
            if (Files.isDirectory(dir)) {
                programFiles.put(folder, listPrograms(dir));
            }
        }

        return new PackInfo(name, shadersRoot, fromDimensionFile, dimensionFolders, programFiles);
    }

    /**
     * "shaders" directly in the pack, or else the first folder named "shaders" found going down,
     * in name order, at most {@link #MAX_SEARCH_DEPTH} levels deep. Iris does the same search
     * ({@code Iris.java:393-410}) but in no fixed order and with no depth limit.
     */
    static Optional<Path> findShadersFolder(Path packRoot) throws IOException {
        Path direct = packRoot.resolve("shaders");
        if (Files.isDirectory(direct)) {
            return Optional.of(direct);
        }
        try (Stream<Path> walk = Files.walk(packRoot, MAX_SEARCH_DEPTH)) {
            return walk
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().equals("shaders"))
                    .min(Comparator.comparing(p -> relative(packRoot, p)));
        }
    }

    /**
     * Reads lines like {@code dimension.world-1=minecraft:the_nether minecraft:nether} into
     * world id -> folder. A value of "*" means "any other world".
     * <p>
     * Matches Iris {@code ShaderPack.java:395-418}. Limitation for now: Iris runs this file
     * through the preprocessor first (so "#if" lines work). We don't have a preprocessor yet
     * (step 3), so this reads it as plain properties. Complementary's file has no "#if" lines.
     */
    static SortedMap<String, String> parseDimensionMap(Properties properties) {
        SortedMap<String, String> map = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("dimension.")) {
                continue;
            }
            String folder = key.substring("dimension.".length());
            for (String world : properties.getProperty(key).trim().split("\\s+")) {
                if (!world.isEmpty()) {
                    map.put(world, folder);
                }
            }
        }
        return map;
    }

    /**
     * No dimension.properties (or an empty one): use the classic folder names if they exist.
     * Matches Iris {@code ShaderPack.java:132-147}: world0 is the Overworld and also any world
     * without its own folder; world-1 is the Nether; world1 is the End. If none exist, every
     * world uses the shaders/ root.
     */
    static SortedMap<String, String> guessDimensionMap(Path shaders) {
        SortedMap<String, String> map = new TreeMap<>();
        if (Files.isDirectory(shaders.resolve("world0"))) {
            map.put("minecraft:overworld", "world0");
            map.put("*", "world0");
        }
        if (Files.isDirectory(shaders.resolve("world-1"))) {
            map.put("minecraft:the_nether", "world-1");
        }
        if (Files.isDirectory(shaders.resolve("world1"))) {
            map.put("minecraft:the_end", "world1");
        }
        return map;
    }

    /** Program files directly inside one folder (not in its subfolders), sorted by name. */
    static List<String> listPrograms(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = new ArrayList<>(files
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> PROGRAM_EXTENSIONS.contains(extension(n)))
                    .toList());
            names.sort(null);
            return List.copyOf(names);
        }
    }

    private static Optional<Properties> loadProperties(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            properties.load(reader);
        }
        return Optional.of(properties);
    }

    private static boolean isZip(Path p) {
        return Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1);
    }

    /** A path relative to the pack, always with forward slashes, so logs never show a full disk path. */
    private static String relative(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }
}
