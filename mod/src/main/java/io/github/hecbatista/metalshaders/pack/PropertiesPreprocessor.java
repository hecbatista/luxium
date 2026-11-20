package io.github.hecbatista.metalshaders.pack;

import org.anarres.cpp.DefaultPreprocessorListener;
import org.anarres.cpp.Feature;
import org.anarres.cpp.LexerException;
import org.anarres.cpp.Preprocessor;
import org.anarres.cpp.Source;
import org.anarres.cpp.StringLexerSource;
import org.anarres.cpp.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs {@code #if}/{@code #define} over a .properties file (shaders.properties and friends), the
 * way Iris does before reading it ({@code PropertiesPreprocessor.java}, see NOTICE):
 * <ul>
 *   <li>Every line is trimmed and blank lines are dropped.</li>
 *   <li>In .properties files '#' also starts a comment, so a '#' line that isn't a preprocessor
 *       directive is removed, and a '#' inside any other line is deleted.</li>
 *   <li>Backslashes are hidden from JCPP and restored afterwards, so line continuations survive.</li>
 * </ul>
 * Not done yet: the pack's own settings (option values like {@code COLORED_LIGHTING}) should be
 * defined here too, as Iris does. Until the settings step, only the game's defines are.
 */
public final class PropertiesPreprocessor {
    private static final Set<String> DIRECTIVES = Set.of(
            "define", "undef", "if", "ifdef", "ifndef", "elif", "else", "endif", "error", "warning", "line", "pragma", "include");
    private static final String BACKSLASH = "LUXIUM_BACKSLASH";

    private PropertiesPreprocessor() {
    }

    public static String preprocess(String source, Map<String, String> defines) throws GlslPreprocessor.PreprocessException {
        if (source.contains(BACKSLASH)) {
            throw new GlslPreprocessor.PreprocessException("Properties file contains a reserved marker name", null);
        }
        List<String> lines = new ArrayList<>();
        for (String raw : source.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                String word = line.substring(1).stripLeading().split("[^A-Za-z]", 2)[0].toLowerCase(Locale.ROOT);
                lines.add(DIRECTIVES.contains(word) ? line : "");
            } else {
                lines.add(line.replace("#", ""));
            }
        }
        String text = String.join("\n", lines).replace("\\", BACKSLASH) + "\n";

        List<String> errors = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        try (Preprocessor pp = new Preprocessor()) {
            for (Map.Entry<String, String> d : defines.entrySet()) {
                if (d.getValue().isEmpty()) {
                    pp.addMacro(d.getKey());
                } else {
                    pp.addMacro(d.getKey(), d.getValue());
                }
            }
            pp.setListener(new DefaultPreprocessorListener() {
                @Override
                public void handleError(Source s, int line, int column, String msg) throws LexerException {
                    errors.add("line " + line + ": " + msg);
                }
            });
            pp.addFeature(Feature.KEEPCOMMENTS);
            pp.addInput(new StringLexerSource(text, true));
            for (Token t = pp.token(); t != null && t.getType() != Token.EOF; t = pp.token()) {
                out.append(t.getText());
            }
        } catch (Exception e) {
            throw new GlslPreprocessor.PreprocessException("Properties preprocessing failed: " + e.getMessage(), e);
        }
        if (!errors.isEmpty()) {
            throw new GlslPreprocessor.PreprocessException(String.join("\n", errors), null);
        }
        return out.toString().replace(BACKSLASH, "\\");
    }
}
