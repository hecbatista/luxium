package io.github.hecbatista.metalshaders.pack;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pastes {@code #include} files into a program, the way Iris does, before any other directive
 * is looked at.
 * <p>
 * Matches Iris (branch 26.3) behavior; no Iris code is copied:
 * <ul>
 *   <li>A line counts as an include if, after trimming, it starts with {@code #include}. The
 *       path may be in quotes. ({@code FileNode.java:37-61})</li>
 *   <li>A path starting with "/" is relative to the shaders/ folder; anything else is relative
 *       to the including file's folder. "." and ".." are resolved, and ".." can't climb above
 *       shaders/. ({@code AbsolutePackPath.java:22-82})</li>
 *   <li>Includes are expanded <b>everywhere</b>, even inside an {@code #if} that is off, because
 *       {@code #if} isn't evaluated yet. So there's no include-once, and include guards don't
 *       stop a cycle: a file that includes itself, directly or not, is an error.
 *       ({@code IncludeGraph.java:136-214})</li>
 * </ul>
 * Our addition (Vitrail's lesson, {@code docs/translation.md:41-67}): a pack is downloaded
 * content, so the total work is capped, not just the depth.
 */
public final class IncludeExpander {
    /** Reads a pack file by its path relative to shaders/, like "/lib/common.glsl". */
    @FunctionalInterface
    public interface FileSource {
        Optional<String> read(String packPath) throws IOException;
    }

    /** Caps on one program's expansion. */
    public record Limits(int maxDepth, long maxLines) {
        /** Generous: real packs stay far below this. */
        public static final Limits DEFAULT = new Limits(64, 2_000_000);
    }

    /** Something in the include graph was wrong. The message says which file and line. */
    public static final class IncludeException extends Exception {
        public IncludeException(String message) {
            super(message);
        }
    }

    private final FileSource files;
    private final Limits limits;
    /** Files already expanded, so a file included from many places is only read once. */
    private final Map<String, List<String>> cache = new HashMap<>();
    private final Deque<String> stack = new ArrayDeque<>();
    private long linesWritten;

    private IncludeExpander(FileSource files, Limits limits) {
        this.files = files;
        this.limits = limits;
    }

    /** Expands one program file (e.g. "/world0/composite.fsh") into the full list of lines. */
    public static List<String> expand(String packPath, FileSource files, Limits limits)
            throws IncludeException, IOException {
        return new IncludeExpander(files, limits).expandFile(normalize(packPath), null);
    }

    private List<String> expandFile(String path, String includedFrom) throws IncludeException, IOException {
        if (stack.contains(path)) {
            List<String> chain = new ArrayList<>(stack);
            chain.add(path);
            throw new IncludeException("#include cycle: " + String.join(" -> ", chain)
                    + " (#include runs before #if, so include guards can't break a cycle)");
        }
        if (stack.size() >= limits.maxDepth()) {
            throw new IncludeException("#include nested deeper than " + limits.maxDepth() + " at " + path);
        }
        List<String> cached = cache.get(path);
        if (cached != null) {
            count(cached.size(), path);
            return cached;
        }

        String text = files.read(path).orElseThrow(() -> new IncludeException(
                "Missing file " + path + (includedFrom == null ? "" : " (included from " + includedFrom + ")")));

        stack.addLast(path);   // addLast/removeLast keep the stack in include order for error messages
        List<String> out = new ArrayList<>();
        String[] lines = text.replace("\u0000", "").split("\\R", -1);
        String folder = parentOf(path);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("#include")) {
                String target = unquote(trimmed.substring("#include".length()).trim());
                String resolved = target.startsWith("/") ? normalize(target) : normalize(folder + "/" + target);
                out.addAll(expandFile(resolved, path + ":" + (i + 1)));
            } else {
                out.add(line);
                count(1, path);
            }
        }
        stack.removeLast();

        List<String> result = List.copyOf(out);
        cache.put(path, result);
        return result;
    }

    private void count(long lines, String path) throws IncludeException {
        linesWritten += lines;
        if (linesWritten > limits.maxLines()) {
            throw new IncludeException("Program grew past " + limits.maxLines() + " lines while expanding " + path);
        }
    }

    private static String unquote(String s) {
        if (s.startsWith("\"")) {
            s = s.substring(1);
        }
        if (s.endsWith("\"")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** "/a/./b/../c.glsl" -> "/a/c.glsl". Never climbs above "/". */
    static String normalize(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(part);
            }
        }
        return "/" + String.join("/", parts);
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }
}
