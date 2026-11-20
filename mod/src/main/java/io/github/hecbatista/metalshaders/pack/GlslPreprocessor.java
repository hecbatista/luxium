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
import java.util.Map;

/**
 * Runs the C-style preprocessor over a program whose includes are already pasted in: decides
 * every {@code #if}, applies every {@code #define}, and drops the lines that are switched off.
 * <p>
 * Uses JCPP (org.anarres:jcpp, Apache-2.0), the same library Iris uses for this
 * ({@code ShaderPack.java:317}).
 * <p>
 * JCPP is a C preprocessor and doesn't know GLSL's {@code #version} or {@code #extension}.
 * Each one is turned into a {@code #warning} line with a marker before JCPP runs. JCPP only
 * reports a {@code #warning} when its line is switched on, so the markers that come back are
 * exactly the live {@code #version} and {@code #extension} lines. They are then put back at
 * the top of the output, where GLSL requires them. That technique is adapted from Iris's
 * {@code JcppProcessor} and {@code GlslCollectingListener}; see NOTICE.
 */
public final class GlslPreprocessor {
    private static final String VERSION_MARKER = "METALSHADERS_GLSL_VERSION";
    private static final String EXTENSION_MARKER = "METALSHADERS_GLSL_EXTENSION";

    /** The preprocessed program, with {@code #version} and {@code #extension} hoisted to the top. */
    public record Result(String version, List<String> extensions, String body) {
        /** The whole program as one text, ready for a GLSL compiler. */
        public String text() {
            StringBuilder out = new StringBuilder();
            if (version != null) {
                out.append("#version ").append(version).append('\n');
            }
            for (String extension : extensions) {
                out.append("#extension ").append(extension).append('\n');
            }
            return out.append(body).toString();
        }
    }

    /** JCPP reported an error, such as an unbalanced {@code #endif}. */
    public static final class PreprocessException extends Exception {
        public PreprocessException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private GlslPreprocessor() {
    }

    /**
     * @param source  the program with includes already pasted in
     * @param defines names the game defines before the program starts (value "" means just
     *                "defined", like {@code #define MC_OS_MAC})
     */
    public static Result preprocess(String source, Map<String, String> defines) throws PreprocessException {
        if (source.contains(VERSION_MARKER) || source.contains(EXTENSION_MARKER)) {
            throw new PreprocessException("Source contains a reserved marker name", null);
        }
        String marked = source
                .replace("#version", "#warning " + VERSION_MARKER)
                .replace("#extension", "#warning " + EXTENSION_MARKER);

        Collector listener = new Collector();
        StringBuilder body = new StringBuilder();
        try (Preprocessor pp = new Preprocessor()) {
            for (Map.Entry<String, String> define : defines.entrySet()) {
                if (define.getValue().isEmpty()) {
                    pp.addMacro(define.getKey());
                } else {
                    pp.addMacro(define.getKey(), define.getValue());
                }
            }
            pp.setListener(listener);
            pp.addFeature(Feature.KEEPCOMMENTS);
            pp.addInput(new StringLexerSource(marked, true));
            for (Token token = pp.token(); token != null && token.getType() != Token.EOF; token = pp.token()) {
                body.append(token.getText());
            }
        } catch (Exception e) {
            throw new PreprocessException("Preprocessing failed: " + e.getMessage(), e);
        }
        if (!listener.errors.isEmpty()) {
            throw new PreprocessException(String.join("\n", listener.errors), null);
        }
        body.append('\n');
        String version = listener.versions.isEmpty() ? null : listener.versions.getFirst();
        return new Result(version, List.copyOf(listener.extensions), body.toString());
    }

    /** Collects the markers, and errors, that JCPP reports. */
    private static final class Collector extends DefaultPreprocessorListener {
        final List<String> versions = new ArrayList<>();
        final List<String> extensions = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void handleWarning(Source source, int line, int column, String msg) throws LexerException {
            // JCPP passes the whole directive text, e.g. "#warning METALSHADERS_GLSL_VERSION 130".
            int at = msg.indexOf(VERSION_MARKER);
            if (at >= 0) {
                versions.add(msg.substring(at + VERSION_MARKER.length()).trim());
                return;
            }
            at = msg.indexOf(EXTENSION_MARKER);
            if (at >= 0) {
                extensions.add(msg.substring(at + EXTENSION_MARKER.length()).trim());
                return;
            }
            super.handleWarning(source, line, column, msg);
        }

        @Override
        public void handleError(Source source, int line, int column, String msg) throws LexerException {
            errors.add("line " + line + ": " + msg);
            super.handleError(source, line, column, msg);
        }
    }
}
