package io.github.mbackschat.a12.dm.cli.interp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef;
import io.github.mbackschat.a12.dm.interpreter.eval.Document;
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef;
import io.github.mbackschat.a12.dm.interpreter.model.Kind;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef;
import io.github.mbackschat.a12.dm.interpreter.model.Severity;

/**
 * Loads a DM-JSON model into the kernel-free {@link ModelDef}, and a {@code {"fields":{…}}} instance tree
 * into a {@link Document} — the consumer-side bridge that lets {@code dmtool} drive the interpreter on a
 * REAL model (the engine behind the runtime verbs' default path; INTERPRETER-BUILDOUT.md). Verified by
 * {@code InterpreterRealModelDiffTest} (the load→interpret path agrees with the live kernel).
 *
 * <p>The model walk is a flat scan-by-type (every node carries a {@code type} + full-path {@code id}), so
 * it doesn't depend on the exact element nesting. Handles a <b>self-contained</b> model (no includes — a
 * model with imports needs the kernel's expansion first, native-safe via the read tier). The field-source
 * mapping is recorded in INTERPRETER-BUILDOUT.md "DM-JSON → ModelDef loader".
 */
public final class InterpreterModelLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InterpreterModelLoader() {
    }

    public static ModelDef load(String modelJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(modelJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("not valid DM-JSON", e);
        }
        List<FieldDef> fields = new ArrayList<>();
        List<RuleDef> rules = new ArrayList<>();
        List<ComputationDef> comps = new ArrayList<>();
        Set<String> repeatable = new LinkedHashSet<>();
        Map<String, Integer> declaredReps = new LinkedHashMap<>();
        walk(root, fields, rules, comps, repeatable, declaredReps);
        return new ModelDef(fields, rules, comps, repeatable, declaredReps);
    }

    private static void walk(JsonNode node, List<FieldDef> fields, List<RuleDef> rules,
            List<ComputationDef> comps, Set<String> repeatable, Map<String, Integer> declaredReps) {
        if (node.isObject()) {
            String type = node.path("type").asText(null);
            String id = node.path("id").asText(null);
            if (type != null && id != null) {
                switch (type) {
                    case "Field" -> fields.add(new FieldDef(id, kindOf(node), localizedTexts(node.path("labels")), scaleOf(node), signed(node)));
                    case "Group" -> {
                        JsonNode g = node.path("Group");
                        if (g.path("repeatability").asInt(0) > 1) {
                            repeatable.add(id);
                            declaredReps.put(id, g.get("repeatability").asInt());
                        }
                    }
                    case "Rule" -> {
                        JsonNode r = node.path("Rule");
                        rules.add(new RuleDef(id, parent(id), r.path("errorCondition").asText(),
                                r.path("errorCode").asText(), r.path("errorEntityRelPath").asText(),
                                Severity.valueOf(r.path("severity").asText("ERROR")), localizedTexts(r.path("errorMessage"))));
                    }
                    case "Computation" -> {
                        JsonNode c = node.path("Computation");
                        String target = resolveRel(id, c.path("computedFieldRelPath").asText());
                        String op = c.path("computationAlternatives").path(0).path("operation").asText();
                        comps.add(new ComputationDef(target, parent(id), op));
                    }
                    default -> { /* header / other nodes carry no rule surface */ }
                }
            }
            node.properties().forEach(e -> walk(e.getValue(), fields, rules, comps, repeatable, declaredReps));
        } else if (node.isArray()) {
            node.forEach(c -> walk(c, fields, rules, comps, repeatable, declaredReps));
        }
    }

    /** Build a {@link Document} from a nested fields map (the {@code DocInstance.readFields} output). */
    public static Document document(Map<String, Object> fields, ModelDef model) {
        Document doc = new Document(model.getKinds(), model.getDeclaredReps(), model.getUnsignedFields());
        walkInstance(doc, fields, new ArrayList<>(), new ArrayList<>());
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static void walkInstance(Document doc, Object node, List<String> segs, List<Integer> reps) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            List<String> childSegs = append(segs, (String) e.getKey());
            String childPath = "/" + String.join("/", childSegs);
            Object child = e.getValue();
            if (child instanceof List<?> rows) {                       // a repeatable group: one row per element
                for (int i = 0; i < rows.size(); i++) {
                    doc.group(childPath, toIntArray(append(reps, i + 1)));   // instantiate the row (even if empty)
                    walkInstance(doc, rows.get(i), childSegs, append(reps, i + 1));
                }
            } else if (child instanceof Map) {                         // a non-repeatable nested group
                doc.group(childPath, toIntArray(append(reps, 1)));           // instantiate it (even if {} — empty-operand compute)
                walkInstance(doc, child, childSegs, append(reps, 1));
            } else {                                                   // a field value
                String path = "/" + String.join("/", childSegs);
                int[] r = toIntArray(append(reps, 1));                 // + the field's own index
                if (child == null || child.toString().isEmpty()) {
                    doc.emptyField(path, r);
                } else {
                    doc.field(path, child.toString(), r);
                }
            }
        }
    }

    private static Kind kindOf(JsonNode fieldNode) {
        return switch (fieldNode.path("Field").path("fieldType").path("type").asText("")) {
            case "NumberType" -> Kind.NUMBER;
            case "DateType" -> Kind.DATE;
            case "TimeType" -> Kind.TIME;
            case "BooleanType" -> Kind.BOOLEAN;
            case "ConfirmType" -> Kind.CONFIRM;
            default -> Kind.STRING;   // String / Enumeration / DateTime — STRING-shaped for the operators modeled so far
        };
    }

    /** A NUMBER field's scale ({@code NumberType.maxFractionalDigits}), or null for non-number fields. */
    private static Integer scaleOf(JsonNode fieldNode) {
        JsonNode type = fieldNode.path("Field").path("fieldType");
        if (!"NumberType".equals(type.path("type").asText(""))) {
            return null;
        }
        JsonNode max = type.path("NumberType").path("maxFractionalDigits");
        return max.isInt() ? max.asInt() : null;
    }

    /** Whether a NUMBER field is signed (the kernel's {@code hatVorzeichen}): unsigned iff
     *  {@code NumberType.positivesOnly} is true. Non-number fields default to signed (the value is unused). */
    private static boolean signed(JsonNode fieldNode) {
        return !fieldNode.path("Field").path("fieldType").path("NumberType").path("positivesOnly").asBoolean(false);
    }

    /** Read a {@code [{locale, text}]} array into a locale→text map — the shape shared by a rule's
     *  {@code errorMessage} and a field's {@code labels} (the {@code $field$} display-name source). */
    private static Map<String, String> localizedTexts(JsonNode arr) {
        Map<String, String> m = new LinkedHashMap<>();
        if (arr.isArray()) {
            arr.forEach(e -> m.put(e.path("locale").asText(), e.path("text").asText()));
        }
        return m;
    }

    /** The parent group path of a full id, e.g. {@code /Order/X} → {@code /Order}. */
    private static String parent(String id) {
        int i = id.lastIndexOf('/');
        return i <= 0 ? "" : id.substring(0, i);
    }

    /** Resolve a relpath against the NODE's own id ({@code ../F} pops the node name, then appends). */
    private static String resolveRel(String nodeId, String rel) {
        String base = nodeId;
        while (rel.startsWith("../")) {
            base = base.substring(0, base.lastIndexOf('/'));
            rel = rel.substring(3);
        }
        return rel.isEmpty() ? base : base + "/" + rel;
    }

    private static List<String> append(List<String> base, String s) {
        List<String> out = new ArrayList<>(base);
        out.add(s);
        return out;
    }

    private static List<Integer> append(List<Integer> base, int n) {
        List<Integer> out = new ArrayList<>(base);
        out.add(n);
        return out;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }
}
