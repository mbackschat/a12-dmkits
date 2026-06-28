package io.github.mbackschat.a12.dm.schemakit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.DmJson;

import java.util.List;

/**
 * Tier-B / Tier-C <em>carriage</em> (SCHEMAKIT-SPEC §4; JSON-SCHEMA-PROPOSAL §6): a rule or computation
 * that has no native JSON-Schema home is carried losslessly in {@code x-a12-rule} / {@code x-a12-computation}
 * so an a12 → JSON Schema → a12 round-trip preserves it exactly (a foreign reader ignores the unknown
 * {@code x-*}).
 *
 * <p>Carriage works at the DM-JSON <em>tree</em> level — it lifts a {@code Rule}/{@code Computation} element
 * verbatim, keyed by its owning group's id, and restores it. That is deliberate, not a dogfood shortcut:
 * carriage <em>preserves</em> an element rather than <em>authoring</em> one, so a verbatim copy is exactly
 * right (and lossless by construction). The tree surgery reuses the shared {@link DmJson} primitives
 * (reuse-first, §2) — {@code groupElements} finds a group's element list by id; the element shape is the
 * wrapper {@code {type, id, name, <Field|Group|Rule|Computation>}} under
 * {@code content.modelRoot.rootGroups[].Group.elements[]}.
 */
final class Carriage {

    static final String X_RULE = "x-a12-rule";
    static final String X_COMPUTATION = "x-a12-computation";

    private static final ObjectMapper JSON = new ObjectMapper();

    private Carriage() {}

    /** Lift every {@code Rule}/{@code Computation} element from the model's groups onto the schema as {@code x-a12-*}. */
    static void collect(JsonNode dmTree, List<String> groupPaths, ObjectNode schema, TranscodeReport.Builder report) {
        ArrayNode rules = JSON.createArrayNode();
        ArrayNode computations = JSON.createArrayNode();
        for (String groupPath : groupPaths) {
            ArrayNode elements = DmJson.groupElements(dmTree, groupPath);
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
    }

    private static void restore(ObjectNode dmTree, JsonNode carried, TranscodeReport.Builder report) {
        if (!carried.isArray()) {
            return;
        }
        for (JsonNode entry : carried) {
            String groupId = entry.path("group").asText();
            ArrayNode elements = DmJson.groupElements(dmTree, groupId);
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
}
