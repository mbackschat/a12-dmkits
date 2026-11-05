package io.github.mbackschat.a12.dm.schemakit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier-B / Tier-C <em>carriage</em> (SCHEMAKIT-SPEC §4; JSON-SCHEMA-PROPOSAL §6): a rule or computation
 * that has no native JSON-Schema home is carried losslessly in {@code x-a12-rule} / {@code x-a12-computation},
 * and the model's document uniqueness criteria in {@code x-a12-document-uniqueness-criteria}, so an a12 → JSON
 * Schema → a12 round-trip preserves them exactly (a foreign reader ignores the unknown {@code x-*}).
 *
 * <p>Carriage works at the DM-JSON <em>tree</em> level — it lifts a {@code Rule}/{@code Computation} element
 * verbatim, keyed by its owning group's id, and restores it. That is deliberate, not a dogfood shortcut:
 * carriage <em>preserves</em> an element rather than <em>authoring</em> one, so a verbatim copy is exactly
 * right (and lossless by construction). {@code groupElements} finds a group's element list by id (a
 * schemakit-private replica of the traversal — since Set W a Jackson-typed signature may not cross a
 * published kit boundary, so the formerly shared {@code core.DmJson} left the public surface; siblings:
 * {@code rulekit.write.DmJson}, {@code modelkit.DmNodes}); the element shape is the wrapper
 * {@code {type, id, name, <Field|Group|Rule|Computation>}} under
 * {@code content.modelRoot.rootGroups[].Group.elements[]}.
 */
final class Carriage {

    static final String X_ROOT_GROUP_NAME = "x-a12-root-group-name";
    static final String X_INDEX_FIELD_NAME = "x-a12-index-field-name";
    static final String X_RULE = "x-a12-rule";
    static final String X_COMPUTATION = "x-a12-computation";
    /** A12's stored {@code content.documentUniquenessCriteria}, verbatim: a cross-document constraint one
     *  document's JSON Schema cannot state (KERNEL-FINDINGS KF671). */
    static final String X_CRITERIA = "x-a12-document-uniqueness-criteria";

    private static final ObjectMapper JSON = new ObjectMapper();

    private Carriage() {}

    static JsonNode groupObject(JsonNode carrier) {
        return "array".equals(carrier.path("type").asText()) ? carrier.path("items") : carrier;
    }

    private static String rootName(JsonNode carrier) {
        JsonNode object = groupObject(carrier);
        JsonNode name = object.path(X_ROOT_GROUP_NAME);
        return "object".equals(object.path("type").asText()) && name.isTextual() && !name.asText().isEmpty()
                ? name.asText() : null;
    }

    /** Only explicitly marked root objects distinguish an A12 root wrapper from a foreign object (§4). */
    static Map<String, JsonNode> rootCarriers(JsonNode schema) {
        Map<String, JsonNode> roots = new LinkedHashMap<>();
        String single = rootName(schema);
        if (single != null) {
            roots.put(single, schema);
        } else if ("object".equals(schema.path("type").asText()) && schema.path("properties").isObject()) {
            for (var it = schema.path("properties").fields(); it.hasNext(); ) {
                var entry = it.next();
                if (!entry.getKey().equals(rootName(entry.getValue()))) return Map.of();
                roots.put(entry.getKey(), entry.getValue());
            }
        }
        return roots;
    }

    /** Lift every {@code Rule}/{@code Computation} element from the model's groups onto the schema as {@code x-a12-*}. */
    static void collect(JsonNode dmTree, List<String> groupPaths, ObjectNode schema, TranscodeReport.Builder report) {
        ArrayNode rules = JSON.createArrayNode();
        ArrayNode computations = JSON.createArrayNode();
        for (String groupPath : groupPaths) {
            ArrayNode elements = groupElements(dmTree, groupPath);
            if (elements == null) {
                continue;
            }
            for (JsonNode element : elements) {
                if (element.has("Rule")) {
                    rules.add(entry(groupPath, element));
                    report.mapped(element.path("id").asText(), X_RULE, TranscodeReport.Tier.CARRIED);
                } else if (element.has("Computation")) {
                    computations.add(entry(groupPath, element));
                    report.mapped(element.path("id").asText(), X_COMPUTATION, TranscodeReport.Tier.COMPUTATION);
                }
            }
        }
        if (!rules.isEmpty()) {
            schema.set(X_RULE, rules);
        }
        if (!computations.isEmpty()) {
            schema.set(X_COMPUTATION, computations);
        }
        JsonNode criteria = dmTree.path(DmKeys.CONTENT).path(DmKeys.DOCUMENT_UNIQUENESS_CRITERIA);
        if (criteria.isArray() && !criteria.isEmpty()) {
            schema.set(X_CRITERIA, criteria.deepCopy());
            criteria.forEach(c -> report.mapped(criterionSource(c), X_CRITERIA, TranscodeReport.Tier.CARRIED));
        }
    }

    /** A criterion has no path, only its name (KF671), so its disposition names it under the member. */
    private static String criterionSource(JsonNode criterion) {
        return DmKeys.DOCUMENT_UNIQUENESS_CRITERIA + "/" + criterion.path(DmKeys.NAME).asText();
    }

    /** Whether {@code schema} carries anything {@link #inject} restores after the rule-synthesis gate. */
    static boolean carriesElements(JsonNode schema) {
        return schema.has(X_RULE) || schema.has(X_COMPUTATION) || schema.has(X_CRITERIA);
    }

    private static ObjectNode entry(String groupId, JsonNode element) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("group", groupId);
        entry.set("element", element.deepCopy());
        return entry;
    }

    /** Restore carried {@code x-a12-*} elements from a schema back into the built DM-JSON tree, by group id. */
    static void inject(ObjectNode dmTree, JsonNode schema, TranscodeReport.Builder report) {
        restore(dmTree, schema.path(X_RULE), report);
        restore(dmTree, schema.path(X_COMPUTATION), report);
        restoreCriteria(dmTree, schema.path(X_CRITERIA), report);
    }

    /** Restores the carried criteria member verbatim; admission stays with the consistency check, as for rules. */
    private static void restoreCriteria(ObjectNode dmTree, JsonNode carried, TranscodeReport.Builder report) {
        if (carried.isMissingNode()) {
            return;
        }
        if (!carried.isArray()) {
            report.omit("/" + X_CRITERIA, X_CRITERIA + " must be an array of A12 criteria — omitted; declare the "
                    + "model's document uniqueness criteria with `dmtool criterion add`");
            return;
        }
        ((ObjectNode) dmTree.path(DmKeys.CONTENT)).set(DmKeys.DOCUMENT_UNIQUENESS_CRITERIA, carried.deepCopy());
        carried.forEach(c -> report.mapped(criterionSource(c), "restored into content", TranscodeReport.Tier.CARRIED));
    }

    private static void restore(ObjectNode dmTree, JsonNode carried, TranscodeReport.Builder report) {
        if (!carried.isArray()) {
            return;
        }
        for (JsonNode entry : carried) {
            String groupId = entry.path("group").asText();
            ArrayNode elements = groupElements(dmTree, groupId);
            if (elements == null) {
                report.omit(groupId, "carried element targets group '" + groupId + "', which is not in the "
                        + "imported structure");
                continue;
            }
            elements.add(entry.path("element").deepCopy());
            report.mapped(entry.path("element").path("id").asText(), "restored into " + groupId,
                    TranscodeReport.Tier.CARRIED);
        }
    }

    /** Preserve the owner's relative scalar before rule synthesis binds the structure (SCHEMAKIT-SPEC §4). */
    static void restoreIndexFields(ObjectNode dmTree, Map<String, String> indexFields,
                                   TranscodeReport.Builder report) {
        for (var entry : indexFields.entrySet()) {
            String groupPath = entry.getKey();
            ObjectNode body = groupBody(dmTree, groupPath);
            if (body == null) {
                report.omit(groupPath + "/" + X_INDEX_FIELD_NAME,
                        "carried index field targets missing group '" + groupPath + "' — restore it by hand");
                continue;
            }
            body.put(DmKeys.INDEX_FIELD_NAME, entry.getValue());
            report.mapped(groupPath + "/" + X_INDEX_FIELD_NAME, groupPath + "/indexFieldName",
                    TranscodeReport.Tier.CARRIED);
        }
    }

    /** The group's element list, materialized for the existing verbatim element-carriage contract. */
    private static ArrayNode groupElements(JsonNode root, String groupPath) {
        ObjectNode body = groupBody(root, groupPath);
        if (body == null) return null;
        JsonNode elements = body.path(DmKeys.ELEMENTS);
        return elements instanceof ArrayNode array ? array : body.putArray(DmKeys.ELEMENTS);
    }

    private static ObjectNode groupBody(JsonNode root, String groupPath) {
        return bodyOf(root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS), groupPath);
    }

    private static ObjectNode bodyOf(JsonNode groups, String groupPath) {
        if (!groups.isArray()) return null;
        for (JsonNode node : groups) {
            if (!ElementType.GROUP.wireName().equals(node.path(DmKeys.TYPE).asText())) continue;
            if (!(node.path(ElementType.GROUP.wireName()) instanceof ObjectNode body)) continue;
            if (groupPath.equals(node.path(DmKeys.ID).asText())) return body;
            ObjectNode nested = bodyOf(body.path(DmKeys.ELEMENTS), groupPath);
            if (nested != null) return nested;
        }
        return null;
    }
}
