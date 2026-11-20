package io.github.hecbatista.metalshaders.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step 3 end to end: for every program file of a pack, paste in its includes, then run the
 * preprocessor with the game's defines. Uses the pack's default settings.
 */
public final class PackPreprocessor {
    /**
     * One program's outcome. Exactly one of {@code result} and {@code error} is set.
     *
     * @param path           e.g. "/world0/composite.fsh"
     * @param expandedLines  lines after includes were pasted in, before {@code #if} (0 on error)
     */
    public record Program(String path, int expandedLines, GlslPreprocessor.Result result, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private PackPreprocessor() {
    }

    /** Preprocesses every program in every world folder of a pack (and in shaders/ itself). */
    public static List<Program> preprocessAll(Path pack, Map<String, String> defines) throws IOException {
        PackInfo info = PackReader.read(pack);
        return PackReader.open(pack, (name, root, shaders) -> {
            IncludeExpander.FileSource files = packPath -> PackReader.readText(shaders, packPath);
            List<Program> out = new ArrayList<>();
            for (Map.Entry<String, List<String>> folder : info.programFiles().entrySet()) {
                for (String file : folder.getValue()) {
                    String path = folder.getKey().isEmpty() ? "/" + file : "/" + folder.getKey() + "/" + file;
                    out.add(preprocessOne(path, files, defines));
                }
            }
            return out;
        });
    }

    static Program preprocessOne(String path, IncludeExpander.FileSource files, Map<String, String> defines)
            throws IOException {
        List<String> lines;
        try {
            lines = IncludeExpander.expand(path, files, IncludeExpander.Limits.DEFAULT);
        } catch (IncludeExpander.IncludeException e) {
            return new Program(path, 0, null, e.getMessage());
        }
        try {
            GlslPreprocessor.Result result = GlslPreprocessor.preprocess(String.join("\n", lines), defines);
            return new Program(path, lines.size(), result, null);
        } catch (GlslPreprocessor.PreprocessException e) {
            return new Program(path, lines.size(), null, e.getMessage());
        }
    }
}
