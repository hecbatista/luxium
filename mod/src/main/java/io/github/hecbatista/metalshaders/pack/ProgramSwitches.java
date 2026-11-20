package io.github.hecbatista.metalshaders.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * shaders.properties' {@code program.<folder>/<name>.enabled=<expression>}: a program switched
 * off counts as missing, so Iris's fallbacks pick another (ShaderPack: a disabled program's
 * source is null).
 * <p>
 * Follows Iris (see NOTICE): the key's program part is everything between "program." and the
 * next '.' (ShaderProperties.handleProgramEnabledDirective), compared with the program's path
 * without extension, like "world0/shadow". The value is a boolean expression of on/off options
 * with {@code ! && || ( )} (BooleanParser): "true"/"1" and "false"/"0" are literals, AND binds
 * tighter than OR, and a name that isn't an on/off option counts as on.
 */
public final class ProgramSwitches {
    private ProgramSwitches() {
    }

    /** Whether {@code folder/name} (folder "" for the shaders/ root) is switched on. */
    public static boolean enabled(Properties properties, String folder, String name, Predicate<String> option) {
        String key = "program." + (folder.isEmpty() ? "" : folder + "/") + name + ".enabled";
        String value = properties.getProperty(key);
        return value == null || evaluate(value.strip(), option);
    }

    /** Iris BooleanParser semantics for a well-formed expression. */
    static boolean evaluate(String expression, Predicate<String> option) {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < expression.length(); ) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '&' || c == '|') {
                tokens.add(String.valueOf(c));
                i += i + 1 < expression.length() && expression.charAt(i + 1) == c ? 2 : 1;
            } else if (c == '!' || c == '(' || c == ')') {
                tokens.add(String.valueOf(c));
                i++;
            } else {
                int start = i;
                while (i < expression.length() && "&|!() \t".indexOf(expression.charAt(i)) < 0) {
                    i++;
                }
                tokens.add(expression.substring(start, i));
            }
        }
        Parser p = new Parser(tokens, option);
        return p.or();
    }

    private static final class Parser {
        final List<String> tokens;
        final Predicate<String> option;
        int at;

        Parser(List<String> tokens, Predicate<String> option) {
            this.tokens = tokens;
            this.option = option;
        }

        boolean or() {
            boolean value = and();
            while (at < tokens.size() && tokens.get(at).equals("|")) {
                at++;
                value |= and();
            }
            return value;
        }

        boolean and() {
            boolean value = unary();
            while (at < tokens.size() && tokens.get(at).equals("&")) {
                at++;
                value &= unary();
            }
            return value;
        }

        boolean unary() {
            if (at >= tokens.size()) {
                return false;
            }
            String t = tokens.get(at++);
            switch (t) {
                case "!":
                    return !unary();
                case "(":
                    boolean inner = or();
                    if (at < tokens.size() && tokens.get(at).equals(")")) {
                        at++;
                    }
                    return inner;
                case "true":
                case "1":
                    return true;
                case "false":
                case "0":
                    return false;
                default:
                    return option.test(t);
            }
        }
    }
}
