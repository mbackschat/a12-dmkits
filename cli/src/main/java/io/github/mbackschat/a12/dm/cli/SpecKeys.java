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
            JsonNode root = MAPPER.readTree(in);
            JsonNode schema = root;
            if (!pointer.isEmpty()) {
                schema = root.at(pointer);
                if (schema.isMissingNode()) {
                    throw new IllegalStateException(
                            "subschema not found: schema/" + resourceName + ".schema.json#" + pointer);
                }
            }
            return new SpecKeys(Node.from(schema, root, new LinkedHashMap<>()));
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
                Node child = root.children.get(entry.getKey());
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
    private static final class Node {

        private final String type;
        private final List<JsonNode> enumValues;
        private final boolean closed;
        // Populated immediately after the node is memoized, so a self-referential $ref (explain's tree node) can
        // point back at the node being built instead of recursing forever.
        private final Map<String, Node> children = new LinkedHashMap<>();
        private Node arrayItem;

        private Node(String type, List<JsonNode> enumValues, boolean closed) {
            this.type = type;
            this.enumValues = enumValues;
            this.closed = closed;
        }

        /**
         * The validation tree for {@code schema}, resolving any {@code $ref} against {@code root}.
         *
         * <p><b>An unresolvable {@code $ref} throws.</b> Before this, {@code $ref} was not handled at all, so a
         * node consisting only of one silently produced "nothing to enforce" — and since a {@code $ref} is how
         * every non-trivial schema factors out its item shape, the closed-keys walk stopped at the reference in 9
         * bundled schemas. That is how three undeclared keys shipped under {@code workspace-list}'s
         * {@code additionalProperties:false}: the guard was not lenient about them, it never saw them. A silent
         * degrade in a guard is worse than no guard, because it reads as coverage.
         *
         * @param memo pointer → node for every {@code $ref} already built, so a recursive shape shares one node
         */
        static Node from(JsonNode schema, JsonNode root, Map<String, Node> memo) {
            return build(schema, root, memo, null);
        }

        private static Node build(JsonNode schema, JsonNode root, Map<String, Node> memo, String memoKey) {
            if (schema == null || !schema.isObject()) {
                return null;
            }
            JsonNode ref = schema.get("$ref");
            if (ref != null && ref.isTextual()) {
                return fromRef(ref.asText(), root, memo);
            }
            String type = schema.path("type").isTextual() ? schema.get("type").asText() : null;
            List<JsonNode> enumValues = null;
            JsonNode en = schema.get("enum");
            if (en != null && en.isArray()) {
                enumValues = new ArrayList<>();
                en.forEach(enumValues::add);
            }
            Map<String, List<JsonNode>> declarations = new LinkedHashMap<>();
            collectProperties(schema, declarations);
            Map<String, JsonNode> props = new LinkedHashMap<>();
            declarations.forEach((key, declared) -> props.put(key, agreedDeclaration(declared)));
            JsonNode items = schema.get("items");
            boolean hasItems = items != null && items.isObject();
            if (type == null && enumValues == null && props.isEmpty() && !hasItems) {
                return null; // nothing to enforce — open, unrestricted
            }
            JsonNode ap = schema.path("additionalProperties");
            Node node = new Node(type, enumValues, !props.isEmpty() && ap.isBoolean() && !ap.booleanValue());
            if (memoKey != null) {
                // Published BEFORE the children are walked: that ordering is what lets a self-referential $ref
                // (explain's tree node) resolve to this very node instead of recursing without end.
                memo.put(memoKey, node);
            }
            props.forEach((key, sub) -> node.children.put(key, build(sub, root, memo, null)));
            if (hasItems) {
                node.arrayItem = build(items, root, memo, null);
            }
            return node;
        }

        /** Resolve a local {@code #/…} reference, sharing one node per pointer so a recursive shape terminates. */
        private static Node fromRef(String pointer, JsonNode root, Map<String, Node> memo) {
            if (!pointer.startsWith("#")) {
                throw new IllegalStateException("only local $ref pointers are supported, got: " + pointer);
            }
            String path = pointer.substring(1);
            if (memo.containsKey(path)) {
                return memo.get(path);
            }
            JsonNode target = root.at(path);
            if (target.isMissingNode() || !target.isObject()) {
                throw new IllegalStateException("unresolvable $ref in a bundled schema: " + pointer);
            }
            return build(target, root, memo, path);
        }

        /**
         * Every declaration of every property, this node's own plus each {@code oneOf}/{@code anyOf}/{@code allOf}
         * branch's — keyed by property, so a key declared differently per branch is <b>visible as such</b> rather
         * than collapsed onto whichever branch came first.
         */
        private static void collectProperties(JsonNode schema, Map<String, List<JsonNode>> into) {
            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fields().forEachRemaining(
                        e -> into.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
            }
            for (String branch : List.of("oneOf", "anyOf", "allOf")) {
                JsonNode arr = schema.get(branch);
                if (arr != null && arr.isArray()) {
                    arr.forEach(b -> collectProperties(b, into));
                }
            }
        }

        /**
         * The one declaration a property has across every union branch, or {@code null} when the branches disagree.
         *
         * <p>The union is over <b>allowed keys</b>, and that is all it can be: in a tagged union like
         * {@code model-diff}'s {@code deltaValue}, {@code value} is a {@code string} in the {@code TEXT} branch and a
         * {@code boolean}/{@code integer}/{@code number}/array in the others, so no single value-shape gate is
         * correct for it and applying the first branch's would reject four legal shapes. Which branch applies is
         * decided by a sibling {@code const} tag this walk does not interpret, so the honest answer is to enforce the
         * key and leave its value unconstrained. (Collapsing with {@code putIfAbsent} hid this until {@code $ref}
         * resolution made the union reachable at all.)
         */
        private static JsonNode agreedDeclaration(List<JsonNode> declared) {
            JsonNode first = declared.get(0);
            return declared.stream().allMatch(first::equals) ? first : null;
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
