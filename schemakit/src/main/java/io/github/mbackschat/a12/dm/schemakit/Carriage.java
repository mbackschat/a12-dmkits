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
 * the model's document uniqueness criteria in {@code x-a12-document-uniqueness-criteria}, and the model's own
 * metadata in {@code x-a12-model}, so an a12 → JSON Schema → a12 round-trip preserves them exactly (a foreign
 * reader ignores the unknown {@code x-*}).
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
    /**
     * A12's model-level members a schema has no keyword for, each under its own A12 name and verbatim: the
     * header's locales, labels and annotations and the content's {@code modelInfo} and {@code modelConfig}. They
     * carry meaning, not decoration: the time zone decides date arithmetic, the base year decides {@code BaseYear},
     * and a carried rule's message needs the locale it was written for. The model id rides the native
     * {@code title}, which import already reads as the identity; the header's model references are rebuilt from
     * the includes, and its type and format version are the importer's own.
     */
    static final String X_MODEL = "x-a12-model";
    private static final List<String> HEADER_MEMBERS = List.of(DmKeys.LOCALES, DmKeys.LABELS, DmKeys.ANNOTATIONS);
    private static final List<String> CONTENT_MEMBERS = List.of(DmKeys.MODEL_INFO, DmKeys.MODEL_CONFIG);

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
        collectModel(dmTree, schema, report);
        JsonNode criteria = dmTree.path(DmKeys.CONTENT).path(DmKeys.DOCUMENT_UNIQUENESS_CRITERIA);
        if (criteria.isArray() && !criteria.isEmpty()) {
            schema.set(X_CRITERIA, criteria.deepCopy());
            criteria.forEach(c -> report.mapped(criterionSource(c), X_CRITERIA, TranscodeReport.Tier.CARRIED));
        }
    }

    /**
     * Lifts each model-level member import would not rebuild: an empty one, or one equal to the importer's default,
     * comes back unchanged without a carrier, so a foreign schema's import → export adds nothing to it.
     */
    private static void collectModel(JsonNode dmTree, ObjectNode schema, TranscodeReport.Builder report) {
        ObjectNode model = JSON.createObjectNode();
        JsonNode defaults = ImportSkeleton.tree(dmTree.path(DmKeys.HEADER).path(DmKeys.ID).asText());
        liftMembers(dmTree, defaults, DmKeys.HEADER, HEADER_MEMBERS, model, report);
        liftMembers(dmTree, defaults, DmKeys.CONTENT, CONTENT_MEMBERS, model, report);
        if (!model.isEmpty()) {
            schema.set(X_MODEL, model);
        }
    }

    private static void liftMembers(JsonNode dmTree, JsonNode defaults, String ownerName, List<String> members,
                                    ObjectNode model, TranscodeReport.Builder report) {
        for (String member : members) {
            JsonNode value = dmTree.path(ownerName).path(member);
            if (value.isContainerNode() && !value.isEmpty() && !value.equals(defaults.path(ownerName).path(member))) {
                model.set(member, value.deepCopy());
                report.mapped(ownerName + "/" + member, X_MODEL, TranscodeReport.Tier.CARRIED);
            }
        }
    }

    /** A criterion has no path, only its name (KF671), so its disposition names it under the member. */
    private static String criterionSource(JsonNode criterion) {
        return DmKeys.DOCUMENT_UNIQUENESS_CRITERIA + "/" + criterion.path(DmKeys.NAME).asText();
    }

    /** Whether {@code schema} carries anything {@link #inject} restores after the rule-synthesis gate. */
    static boolean carriesElements(JsonNode schema) {
        return schema.has(X_RULE) || schema.has(X_COMPUTATION) || schema.has(X_CRITERIA) || schema.has(X_MODEL);
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
        restoreModel(dmTree, schema.path(X_MODEL), report);
    }

    /**
     * Puts each carried model-level member back where A12 keeps it, replacing the importer's default. A member of
     * the wrong JSON kind is omitted on its own, so one bad member never costs the others.
     */
    private static void restoreModel(ObjectNode dmTree, JsonNode carried, TranscodeReport.Builder report) {
        if (carried.isMissingNode()) {
            return;
        }
        if (!carried.isObject()) {
            report.omit("/" + X_MODEL, X_MODEL + " must be an object of A12 model members — omitted; the model "
                    + "keeps the importer's locale and configuration");
            return;
        }
        restoreMembers((ObjectNode) dmTree.path(DmKeys.HEADER), DmKeys.HEADER, HEADER_MEMBERS, true, carried, report);
        restoreMembers((ObjectNode) dmTree.path(DmKeys.CONTENT), DmKeys.CONTENT, CONTENT_MEMBERS, false, carried,
                report);
    }

    private static void restoreMembers(ObjectNode owner, String ownerName, List<String> members, boolean arrays,
                                       JsonNode carried, TranscodeReport.Builder report) {
        for (String member : members) {
            JsonNode value = carried.path(member);
            if (value.isMissingNode()) {
                continue;
            }
            if (arrays ? !value.isArray() : !value.isObject()) {
                report.omit("/" + X_MODEL + "/" + member, X_MODEL + "." + member + " must be "
                        + (arrays ? "an array" : "an object") + " as A12 stores it — omitted; set it on the imported "
                        + "model by hand");
                continue;
            }
            owner.set(member, value.deepCopy());
            report.mapped(ownerName + "/" + member, "restored into " + ownerName, TranscodeReport.Tier.CARRIED);
        }
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
