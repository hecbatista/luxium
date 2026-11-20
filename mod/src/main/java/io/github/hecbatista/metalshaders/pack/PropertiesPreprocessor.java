package io.github.hecbatista.metalshaders.pack;

import org.anarres.cpp.DefaultPreprocessorListener;
import org.anarres.cpp.Feature;
import org.anarres.cpp.LexerException;
import org.anarres.cpp.Preprocessor;
import org.anarres.cpp.PreprocessorCommand;
import org.anarres.cpp.Source;
import org.anarres.cpp.StringLexerSource;
import org.anarres.cpp.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs {@code #if}/{@code #define} over a .properties file (shaders.properties and friends), the
 * way Iris does before reading it ({@code PropertiesPreprocessor.java}, see NOTICE):
 * <ul>
 *   <li>Every line is trimmed and blank lines are dropped.</li>
 *   <li>In .properties files '#' also starts a comment, so a '#' line that isn't a preprocessor
 *       directive (by Iris's test: {@link #isDirective}) is removed, and a '#' inside any other
 *       line is deleted.</li>
 *   <li>Backslashes are hidden from JCPP and restored afterwards, so line continuations survive.</li>
 * </ul>
 * Not done yet: the pack's own settings (option values like {@code COLORED_LIGHTING}) should be
 * defined here too, as Iris does. Until the settings step, only the game's defines are.
 */
public final class PropertiesPreprocessor {
    private static final String BACKSLASH = "LUXIUM_BACKSLASH";

    private PropertiesPreprocessor() {
    }

    /**
     * Iris's test, exactly: the line starts with '#' and a JCPP command name in lower case, with no
     * space between (PropertiesPreprocessor.preprocessSource). So "# If you want..." in a comment
     * header stays a comment.
     */
    static boolean isDirective(String line) {
        for (PreprocessorCommand command : PreprocessorCommand.values()) {
            if (line.startsWith("#" + command.name().replace("PP_", "").toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
                lines.add(isDirective(line) ? line : "");
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
