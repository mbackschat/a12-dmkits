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
 * Strict, schema-sourced validation for a rich JSON spec against its <b>published schema</b> — the single
 * defense against the silent "false success" where a bad key/value was parsed-and-ignored yet the verb still
 * reported {@code applied}. Two sibling checks, both <b>top-level AND nested</b>, both derived from the schema:
 * <ul>
 *   <li><b>key names</b> — every input key (wherever the schema declares {@code additionalProperties:false})
 *       must be a declared property; an undeclared key yields an {@code RK_UNKNOWN_ARG} with a did-you-mean.
 *       Open maps (locale→text labels, name→value annotations, …) carry no {@code properties}, so their keys
 *       are correctly left unrestricted.</li>
 *   <li><b>key values</b> (Follow-on B) — a declared key's value must satisfy the schema's {@code type}/{@code enum}
 *       for that key, or it is an {@code RK_INVALID_VALUE}: a string where an object/array is required
 *       ({@code label:"x"}, {@code annotations:[…]}), or a value outside the declared set
 *       ({@code required:"MANDATORY"} vs the {@code [true,false,"ifParentPresent"]} enum). An open object
 *       (e.g. a locale map) still requires an <em>object</em> value while leaving its keys unrestricted — the
 *       two checks compose. This generalizes the per-parser fail-fast in {@code FieldSpec} to every served spec,
 *       with a structured diagnostic instead of a plain {@code fail}; the parsers keep their fail-fast invariant
 *       behind the gate (it also backstops the {@code apply} op-record's top-level scalars).</li>
 * </ul>
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

    /**
     * Build the validator from bundled {@code schema/<name>.schema.json}, optionally selecting a published
     * subschema with a JSON Pointer after {@code #} (for example {@code apply#/$defs/metaModify}).
     */
    public static SpecKeys forSchema(String name) {
        int hash = name.indexOf('#');
        String resourceName = hash < 0 ? name : name.substring(0, hash);
        String pointer = hash < 0 ? "" : name.substring(hash + 1);
        try (InputStream in = SpecKeys.class.getResourceAsStream("/schema/" + resourceName + ".schema.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "input schema not found on the classpath: schema/" + resourceName + ".schema.json");
            }
            JsonNode schema = MAPPER.readTree(in);
            if (!pointer.isEmpty()) {
                schema = schema.at(pointer);
                if (schema.isMissingNode()) {
                    throw new IllegalStateException(
                            "subschema not found: schema/" + resourceName + ".schema.json#" + pointer);
                }
            }
            return new SpecKeys(Node.from(schema));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read schema/" + resourceName + ".schema.json", e);
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
     * Validate only the <b>nested</b> keys — recurse into each declared property, but do not flag unknown
     * <em>top-level</em> keys. For {@code apply}/{@code batch}, where the op-record's top-level keys are
     * already checked tightly against the op's own key-set; this adds the nested coverage (e.g. a typo inside
     * a {@code string} config block, or a message-item key) those flat checks miss.
     */
    public List<Diagnostic> validateNested(JsonNode input) {
        List<Diagnostic> out = new ArrayList<>();
        if (root != null && input.isObject()) {
            input.fields().forEachRemaining(entry -> {
                Node child = root.children().get(entry.getKey());
                if (child != null) {
                    child.validate(entry.getValue(), entry.getKey(), Set.of(), out);
                }
            });
        }
        return out;
    }

    /**
     * A schema object as a validation tree, carrying everything {@link #validate} enforces:
     * <ul>
     *   <li>{@code type}/{@code enumValues} — the declared <b>value shape</b>: a present value whose JSON type
     *       doesn't match {@code type} (or isn't one of {@code enumValues}) is an {@code RK_INVALID_VALUE} —
     *       the value-shape gate (key <em>values</em>), the sibling of the unknown-<em>key</em> check below;</li>
     *   <li>{@code closed} — its {@code additionalProperties:false}: a key outside {@code children} is rejected;</li>
     *   <li>{@code children} — each declared property's own tree (a {@code null} child = an open sub-object with
     *       nothing to enforce — not recursed); {@code arrayItem} — the item tree for an array schema.</li>
     * </ul>
     * A {@code null} tree means "open — nothing to enforce". An OPEN object (declared {@code type:object} with no
     * {@code properties}, e.g. a locale→text map) is a NON-null node carrying only {@code type=object}: its value
     * must be an object, but its keys stay unrestricted (no {@code children}, not {@code closed}).
     */
    private record Node(String type, List<JsonNode> enumValues, boolean closed,
            Map<String, Node> children, Node arrayItem) {

        static Node from(JsonNode schema) {
            if (schema == null || !schema.isObject()) {
                return null;
            }
            String type = schema.path("type").isTextual() ? schema.get("type").asText() : null;
            List<JsonNode> enumValues = null;
            JsonNode en = schema.get("enum");
            if (en != null && en.isArray()) {
                enumValues = new ArrayList<>();
                en.forEach(enumValues::add);
            }
            Map<String, JsonNode> props = new LinkedHashMap<>();
            collectProperties(schema, props);
            boolean closed = false;
            Map<String, Node> children = new LinkedHashMap<>();
            if (!props.isEmpty()) {
                JsonNode ap = schema.path("additionalProperties");
                closed = ap.isBoolean() && !ap.booleanValue();
                props.forEach((key, sub) -> children.put(key, from(sub)));
            }
            JsonNode items = schema.get("items");
            Node arrayItem = items != null && items.isObject() ? from(items) : null;
            if (type == null && enumValues == null && props.isEmpty() && arrayItem == null) {
                return null; // nothing to enforce — open, unrestricted
            }
            return new Node(type, enumValues, closed, children, arrayItem);
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
            // value-shape gate: a PRESENT value must match the declared type/enum. An explicit null = "absent →
            // default" (the parsers treat isNull() as the default), so it is not flagged. A mis-shaped value is
            // reported and NOT recursed into (recursing would pile confusing child errors onto the real cause).
            if (!input.isNull() && !valueMatches(input)) {
                out.add(invalidValue(path, this));
                return;
            }
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

        /** Whether a present {@code input} satisfies this node's declared {@code enum} (preferred) or {@code type}. */
        private boolean valueMatches(JsonNode input) {
            if (enumValues != null) {
                return enumValues.stream().anyMatch(allowed -> allowed.equals(input));
            }
            if (type == null) {
                return true;
            }
            return switch (type) {
                case "object" -> input.isObject();
                case "array" -> input.isArray();
                case "string" -> input.isTextual();
                case "boolean" -> input.isBoolean();
                case "integer" -> input.isIntegralNumber();
                case "number" -> input.isNumber();
                default -> true; // an unrecognized type keyword — don't restrict
            };
        }

        /** What this node accepts, for the {@code RK_INVALID_VALUE} message/fix. */
        private String expected() {
            if (enumValues != null) {
                StringBuilder b = new StringBuilder("one of ");
                for (int i = 0; i < enumValues.size(); i++) {
                    b.append(i == 0 ? "" : ", ").append(enumValues.get(i));
                }
                return b.toString();
            }
            return "a value of type " + type;
        }
    }

    private static Diagnostic invalidValue(String path, Node node) {
        String expected = node.expected();
        return Diagnostic.precheck(Optional.of(RkCode.INVALID_VALUE.code()),
                "invalid value for '" + path + "' — expected " + expected,
                Diagnostic.Where.arg(null, path),
                Optional.of("provide " + expected),
                Optional.empty());
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
