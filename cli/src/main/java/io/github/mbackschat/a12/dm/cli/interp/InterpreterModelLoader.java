package io.github.mbackschat.a12.dm.cli.interp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative;
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef;
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision;
import io.github.mbackschat.a12.dm.interpreter.eval.Document;
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef;
import io.github.mbackschat.a12.dm.interpreter.model.Kind;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness;
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef;
import io.github.mbackschat.a12.dm.interpreter.model.Severity;

/**
 * Loads a DM-JSON model into the kernel-free {@link ModelDef}, and a {@code {"fields":{…}}} instance tree
 * into a {@link Document} — the consumer-side bridge that lets {@code dmtool} drive the interpreter on a
 * REAL model (the engine behind the runtime verbs' default path; docs/INTERPRETER-ARCHITECTURE.md). Verified
 * by {@code InterpreterRealModelDiffTest} (the load→interpret path agrees with the live kernel).
 *
 * <p>The model walk is a flat scan-by-type (every node carries a {@code type} + full-path {@code id}), so
 * it doesn't depend on the exact element nesting. Handles a <b>self-contained</b> model (no includes — a
 * model with imports needs the kernel's expansion first, native-safe via the read tier).
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
        Set<String> indexFields = new LinkedHashSet<>();
        walk(root, fields, rules, comps, repeatable, declaredReps, indexFields);
        // The model's base year (content.modelInfo.baseYear, a string) — the value of the BaseYear constant.
        String by = root.path("content").path("modelInfo").path("baseYear").asText("");
        Integer baseYear = by.isEmpty() ? null : tryParseInt(by);
        return new ModelDef(fields, rules, comps, repeatable, declaredReps, indexFields, baseYear);
    }

    private static Integer tryParseInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void walk(JsonNode node, List<FieldDef> fields, List<RuleDef> rules,
            List<ComputationDef> comps, Set<String> repeatable, Map<String, Integer> declaredReps,
            Set<String> indexFields) {
        if (node.isObject()) {
            String type = node.path("type").asText(null);
            String id = node.path("id").asText(null);
            if (type != null && id != null) {
                switch (type) {
                    case "Field" -> fields.add(new FieldDef(id, kindOf(node), localizedTexts(node.path("labels")),
                            scaleOf(node), signed(node), requirednessOf(node), formatOf(node), rangeSeparatorOf(node),
                            datePrecisionOf(node), enumValuesOf(node), enumCategoriesOf(node)));
                    case "Group" -> {
                        JsonNode g = node.path("Group");
                        if (g.path("repeatability").asInt(0) > 1) {
                            repeatable.add(id);
                            declaredReps.put(id, g.get("repeatability").asInt());
                        }
                        // The group's index field (the kernel auto-requires it) — resolve its full path.
                        String indexField = g.path("indexFieldName").asText(null);
                        if (indexField != null && !indexField.isEmpty()) {
                            indexFields.add(id + "/" + indexField);
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
                        // A tiered computation: every alternative's (precondition?, operation), under an optional
                        // commonPrecondition — the kernel selects the first alternative whose precondition holds
                        // (IF21 / KERNEL-FINDINGS §2). The DSL text stays raw (relative to the group); the
                        // interpreter parses it with the computation's group.
                        String common = c.hasNonNull("commonPrecondition") ? c.path("commonPrecondition").asText() : null;
                        List<ComputationAlternative> alts = new ArrayList<>();
                        for (JsonNode alt : c.path("computationAlternatives")) {
                            String precond = alt.hasNonNull("precondition") ? alt.path("precondition").asText() : null;
                            alts.add(new ComputationAlternative(precond, alt.path("operation").asText()));
                        }
                        comps.add(new ComputationDef(target, parent(id), alts, common));
                    }
                    default -> { /* header / other nodes carry no rule surface */ }
                }
            }
            node.properties().forEach(e -> walk(e.getValue(), fields, rules, comps, repeatable, declaredReps, indexFields));
        } else if (node.isArray()) {
            node.forEach(c -> walk(c, fields, rules, comps, repeatable, declaredReps, indexFields));
        }
    }

    /** A field's requiredness ({@code Field.requirednessConfig.mode}); absent / {@code notRequired} → not required. */
    private static Requiredness requirednessOf(JsonNode fieldNode) {
        return switch (fieldNode.path("Field").path("requirednessConfig").path("mode").asText("")) {
            case "absoluteOrRelativeToNextRepAncestor" -> Requiredness.ABSOLUTE;
            case "relativeToParent" -> Requiredness.RELATIVE_TO_PARENT;
            default -> Requiredness.NOT_REQUIRED;
        };
    }

    /** Build a {@link Document} from a nested fields map (the {@code DocInstance.readFields} output). */
    public static Document document(Map<String, Object> fields, ModelDef model) {
        Document doc = new Document(model.getKinds(), model.getDeclaredReps(), model.getUnsignedFields(),
                model.getRangeConfig(), model.getDateFormats(), model.getScales(), model.getDateTimeFormats(),
                model.getDatePrecisions(), model.getEnumValues(), model.getEnumCategories(), model.getTimeFormats());
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
            case "DateTimeType" -> Kind.DATE_TIME;
            case "TimeType" -> Kind.TIME;
            case "DateRangeType" -> Kind.DATE_RANGE;
            case "BooleanType" -> Kind.BOOLEAN;
            case "ConfirmType" -> Kind.CONFIRM;
            default -> Kind.STRING;   // String / Enumeration — STRING-shaped for the operators modeled so far
        };
    }

    /** A date-ish field's rendering format ({@code <Type>.format}, e.g. {@code dd.MM.yyyy} for a date,
     *  {@code yyyy-MM-dd'T'HH:mm:ss} for a date-time) — read for DATE, DATE_TIME and DATE_RANGE so the
     *  interpreter can canonicalize the rendered value; else null. */
    private static String formatOf(JsonNode fieldNode) {
        JsonNode type = fieldNode.path("Field").path("fieldType");
        String typeName = type.path("type").asText("");
        return switch (typeName) {
            case "DateType", "DateTimeType", "DateRangeType" -> {
                String fmt = type.path(typeName).path("format").asText("");
                yield fmt.isEmpty() ? null : fmt;
            }
            default -> null;
        };
    }

    /** An ENUMERATION field's XML values in declaration order ({@code EnumerationType.values[].value}) — the
     *  positional key for {@code EnumCategory} and the membership domain. Empty for a non-enum field (and for
     *  a table-reference enum, whose values live in a table). */
    private static List<String> enumValuesOf(JsonNode fieldNode) {
        JsonNode e = fieldNode.path("Field").path("fieldType").path("EnumerationType");
        List<String> values = new ArrayList<>();
        e.path("values").forEach(v -> values.add(v.path("value").asText()));
        return values;
    }

    /** An ENUMERATION field's categories ({@code EnumerationType.categories[]}, each {@code {name, values[]}})
     *  as name → positional value list — the {@code EnumCategory} ({@code [Field -> Category]}) mapping. Empty
     *  when the field declares no categories. */
    private static Map<String, List<String>> enumCategoriesOf(JsonNode fieldNode) {
        JsonNode e = fieldNode.path("Field").path("fieldType").path("EnumerationType");
        Map<String, List<String>> categories = new LinkedHashMap<>();
        e.path("categories").forEach(c -> {
            List<String> values = new ArrayList<>();
            c.path("values").forEach(v -> values.add(v.asText()));
            categories.put(c.path("name").asText(), values);
        });
        return categories;
    }

    /** A DATE_RANGE field's two-date separator ({@code DateRangeType.rangeSeparator}, e.g. {@code -}), else null. */
    private static String rangeSeparatorOf(JsonNode fieldNode) {
        JsonNode type = fieldNode.path("Field").path("fieldType");
        if (!"DateRangeType".equals(type.path("type").asText(""))) {
            return null;
        }
        String sep = type.path("DateRangeType").path("rangeSeparator").asText("");
        return sep.isEmpty() ? null : sep;
    }

    /** A DATE field's precision ({@code DateType.datePrecision}); {@code DAY_OPTIONAL} permits a day-omitted
     *  {@code 00.MM.yyyy} value. Anything else (incl. absent / a non-date field) maps to {@code FULL}. */
    private static DatePrecision datePrecisionOf(JsonNode fieldNode) {
        JsonNode type = fieldNode.path("Field").path("fieldType");
        boolean dayOptional = "DateType".equals(type.path("type").asText(""))
                && "DAY_OPTIONAL".equals(type.path("DateType").path("datePrecision").asText(""));
        return dayOptional ? DatePrecision.DAY_OPTIONAL : DatePrecision.FULL;
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
