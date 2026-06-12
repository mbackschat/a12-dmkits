package io.github.mbackschat.a12.dm.cli;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Reads the {@link ParamKind} tags off a verb's command class by reflection, keyed by the op-record key
 * (the same key the {@code manifest} uses). Both the {@code manifest} (self-description) and the
 * diagnostic-conformance fuzzer read this — one derivation, no drift. Mixin fields (e.g. the include-dir
 * option, a non-reference param) live on a separate object and are not tagged here.
 */
final class ParamKinds {

    private ParamKinds() {
    }

    /** The {@code paramKey → kind} map for a command (walks the class hierarchy of its user object). */
    static Map<String, ParamKind.Kind> of(CommandSpec cmd) {
        Map<String, ParamKind.Kind> out = new HashMap<>();
        Object userObject = cmd.userObject();
        if (userObject == null) {
            return out;
        }
        for (Class<?> c = userObject.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            collectFields(c, out);
        }
        return out;
    }

    /** Tag-collect a class's declared fields, descending into {@code @ArgGroup} members (e.g. add's kind/typedef). */
    private static void collectFields(Class<?> c, Map<String, ParamKind.Kind> out) {
        for (Field f : c.getDeclaredFields()) {
            ParamKind pk = f.getAnnotation(ParamKind.class);
            if (pk != null) {
                String key = keyOf(f);
                if (key != null) {
                    out.putIfAbsent(key, pk.value());
                }
            }
            if (f.getAnnotation(ArgGroup.class) != null) {
                collectFields(f.getType(), out);
            }
        }
    }

    private static String keyOf(Field f) {
        Option o = f.getAnnotation(Option.class);
        if (o != null && o.names().length > 0) {
            String longest = "";
            for (String n : o.names()) {
                if (n.length() > longest.length()) {
                    longest = n;
                }
            }
            return paramKey(longest);
        }
        Parameters p = f.getAnnotation(Parameters.class);
        if (p != null) {
            return paramKey(p.paramLabel());
        }
        return null;
    }

    /** The op-record key for a flag/label: an option's {@code --name} → {@code name}; a {@code <label>} → {@code label}. */
    static String paramKey(String flag) {
        String s = flag.replaceFirst("^-+", "");
        if (s.startsWith("<") && s.endsWith(">")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }
}
