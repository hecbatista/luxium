package io.github.hecbatista.metalshaders.pack;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Picks the pack to load. Without {@code LUXIUM_PACK} it's the first pack by name, as before. With
 * it set (for example {@code LUXIUM_PACK=bsl}), it's the pack whose name matches, so you can switch
 * packs for one run without moving files.
 */
public final class PackSelection {
    /** Environment variable naming the pack to load: its file name, or a unique part of it (any case). */
    public static final String ENV = "LUXIUM_PACK";

    private PackSelection() {
    }

    /** The pack {@code LUXIUM_PACK} names, else the first one; empty if there are no packs. */
    public static Optional<Path> choose(List<Path> packs) {
        return choose(packs, System.getenv(ENV));
    }

    /**
     * The pack {@code wanted} names, else the first one. An exact name (with or without .zip) wins;
     * otherwise the name must contain {@code wanted} and only one pack may match. A name that
     * matches nothing, or several packs, throws instead of loading some other pack without saying so.
     */
    public static Optional<Path> choose(List<Path> packs, String wanted) {
        if (packs.isEmpty()) {
            return Optional.empty();
        }
        if (wanted == null || wanted.isBlank()) {
            return Optional.of(packs.getFirst());
        }
        String key = wanted.strip().toLowerCase(Locale.ROOT);
        for (Path p : packs) {
            if (bareName(p).equals(key) || fileName(p).equals(key)) {
                return Optional.of(p);
            }
        }
        List<Path> matches = packs.stream().filter(p -> fileName(p).contains(key)).toList();
        if (matches.size() == 1) {
            return Optional.of(matches.getFirst());
        }
        List<String> names = (matches.isEmpty() ? packs : matches).stream()
                .map(p -> p.getFileName().toString()).toList();
        throw new IllegalArgumentException(ENV + "=" + wanted + (matches.isEmpty()
                ? " matches no pack. Packs: " : " matches more than one pack: ") + names);
    }

    private static String fileName(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static String bareName(Path p) {
        return fileName(p).replaceFirst("\\.zip$", "");
    }
}
