package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

/**
 * Strict key validation for a rich JSON spec against its <b>published schema</b> — the single, schema-sourced
 * defense against the silent "false success" where a typo'd or out-of-scope key was parsed-and-ignored and
 * the verb still reported {@code applied}. Every input key, <b>top-level AND nested</b> (wherever the schema
 * declares {@code additionalProperties:false}), must be a declared property; an undeclared key yields an
 * {@code RK_UNKNOWN_ARG} diagnostic with a did-you-mean. Open maps (locale→text labels, name→value
 * annotations, …) carry no {@code properties}, so their keys are correctly left unrestricted.
 *
 * <p>The allowed keys are <b>read from</b> {@code schema/<name>.schema.json} (the same file {@code dmtool
 * schema <target> <op>} serves), not a second hand-maintained list — so the contract an agent reads and the
 * enforcement cannot drift. Used by the standalone spec verbs (field add/modify, rule/computation add) and
 * by {@code apply}/{@code batch} (which also allows the op-record's {@code target}/{@code op} at the root).
 */
public final class SpecKeys {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Node root;

    private SpecKeys(Node root) {
        this.root = root;
    }

    /** Build the validator from the bundled {@code schema/<name>.schema.json} (e.g. {@code "field-add"}, {@code "add"}). */
    public static SpecKeys forSchema(String name) {
        try (InputStream in = SpecKeys.class.getResourceAsStream("/schema/" + name + ".schema.json")) {
            if (in == null) {
                throw new IllegalStateException("input schema not found on the classpath: schema/" + name + ".schema.json");
            }
            return new SpecKeys(Node.from(MAPPER.readTree(in)));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read schema/" + name + ".schema.json", e);
        }
    }

    /** Validate {@code input}; one {@code RK_UNKNOWN_ARG} per undeclared key (recursively). Empty = clean. */
    public List<Diagnostic> validate(JsonNode input) {
        return validate(input, Set.of());
    }

    /** As {@link #validate(JsonNode)}, additionally allowing {@code alsoAllowedAtRoot} keys at the top level
     *  (the {@code target}/{@code op} an {@code apply} op-record carries around the spec). */
    public List<Diagnostic> validate(JsonNode input, Set<String> alsoAllowedAtRoot) {
        List<Diagnostic> out = new ArrayList<>();
        if (root != null) {
            root.validate(input, "", alsoAllowedAtRoot, out);
        }
        return out;
    }

    /**
     * A schema object as an allowed-key tree: {@code closed} = its {@code additionalProperties:false} (so any
     * key outside {@code children} is rejected); {@code children} maps each declared property to its own tree
     * (a {@code null} child = an open sub-object, e.g. a locale map — not recursed). {@code arrayItem} carries
     * the item tree for an array schema. A {@code null} tree anywhere means "open — do not restrict".
     */
    private record Node(boolean closed, Map<String, Node> children, Node arrayItem) {

        static Node from(JsonNode schema) {
            if (schema == null || !schema.isObject()) {
                return null;
            }
            Map<String, JsonNode> props = new LinkedHashMap<>();
            collectProperties(schema, props);
            if (!props.isEmpty()) {
                JsonNode ap = schema.path("additionalProperties");
                boolean closed = ap.isBoolean() && !ap.booleanValue();
                Map<String, Node> children = new LinkedHashMap<>();
                props.forEach((key, sub) -> children.put(key, from(sub)));
                return new Node(closed, children, null);
            }
            JsonNode items = schema.get("items");
            if (items != null && items.isObject()) {
                return new Node(false, Map.of(), from(items));
            }
            return null; // a scalar, or an open object (no declared properties) — unrestricted
        }

        /** Properties of this node plus any {@code oneOf}/{@code anyOf}/{@code allOf} branch (the union of allowed keys). */
        private static void collectProperties(JsonNode schema, Map<String, JsonNode> into) {
            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fields().forEachRemaining(e -> into.putIfAbsent(e.getKey(), e.getValue()));
            }
            for (String branch : List.of("oneOf", "anyOf", "allOf")) {
                JsonNode arr = schema.get(branch);
                if (arr != null && arr.isArray()) {
                    arr.forEach(b -> collectProperties(b, into));
                }
            }
        }

        void validate(JsonNode input, String path, Set<String> ignore, List<Diagnostic> out) {
            if (arrayItem != null && input.isArray()) {
                input.forEach(element -> arrayItem.validate(element, path + "[]", Set.of(), out));
                return;
            }
            if (!input.isObject()) {
                return;
            }
            input.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (ignore.contains(key)) {
                    return;
                }
                if (children.containsKey(key)) {
                    Node child = children.get(key);
                    if (child != null) {
                        child.validate(entry.getValue(), path.isEmpty() ? key : path + "/" + key, Set.of(), out);
                    }
                } else if (closed) {
                    out.add(unknownArg(key, path, children.keySet()));
                }
            });
        }
    }

    private static Diagnostic unknownArg(String key, String path, Set<String> allowed) {
        String near = Suggestions.nearest(key, allowed);
        String where = path.isEmpty() ? key : path + "/" + key;
        String fix = near != null
                ? "did you mean '" + near + "'?"
                : "allowed keys" + (path.isEmpty() ? "" : " under '" + path + "'") + ": " + String.join(", ", new TreeSet<>(allowed));
        return Diagnostic.precheck(Optional.of(RkCode.UNKNOWN_ARG.code()),
                "unknown key '" + key + "'" + (path.isEmpty() ? "" : " in '" + path + "'"),
                Diagnostic.Where.arg(null, where),
                Optional.of(fix),
                Optional.empty());
    }
}
