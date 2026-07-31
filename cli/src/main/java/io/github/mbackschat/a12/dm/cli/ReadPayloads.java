package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * Shared {@code data}-payload builders for the read verbs (T3). The standalone {@code group read} /
 * {@code config read} / {@code typedef read} verbs and their {@code apply}-embedded {@code *_READ} op
 * peers used to build DIFFERENT (subset) shapes for the same read — group emitted 2 keys vs the
 * standalone's 8, config 4 vs ~10, typedef dropped {@code importedFrom}. These builders are the single
 * source of each shape, so both surfaces stay at parity by construction.
 */
public final class ReadPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReadPayloads() {
    }

    /** The full targeted {@code field read} payload, shared by the standalone and embedded apply routes. */
    public static ObjectNode field(ModelDocument doc, String field) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("field", field);
        data.put("kind", doc.fieldKind(field).orElseThrow(
                () -> new IllegalArgumentException("no field '" + field + "' in the model")));
        doc.requiredness(field).ifPresent(requiredness -> {
            switch (requiredness) {
                case OPTIONAL -> data.put("required", false);
                case REQUIRED -> data.put("required", true);
                case REQUIRED_IF_PARENT_PRESENT -> data.put("required", "ifParentPresent");
            }
        });
        doc.labels(field).ifPresent(labels -> putIfNotEmpty(data, "label", labels));
        putIfNotEmpty(data, "helperText", doc.helperText(field));
        putIfNotEmpty(data, "internalDescription", doc.internalDescription(field));
        putIfNotEmpty(data, "externalDescription", doc.externalDescription(field));
        putIfNotEmpty(data, "annotations", doc.annotations(field));
        doc.fieldConfigJson(field).ifPresent(config -> data.setAll(configNode(config)));
        if (data.hasNonNull("typedef")) {
            doc.typeDefinitionConfigJson(data.get("typedef").asText())
                    .ifPresent(config -> data.set("resolvedType", configNode(config)));
        }
        return data;
    }

    /** The full {@code group read} data for one group ({@code doc} must contain {@code group}). */
    public static ObjectNode group(ModelDocument doc, String group) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("group", group);
        boolean repeatable = doc.isRepeating(group);
        data.put("repeatable", repeatable);
        if (repeatable) {
            data.put("maxRepetitions", doc.maxRepetitions(group));   // the numeric max rows, not just the boolean
        }
        // the index field (repetition row-key) so a `group modify --index-field` edit round-trips
        doc.indexField(group).ifPresent(idx -> data.put("indexField", idx));
        // the declared row sort fields (display/USB ordering) so a `group modify --sort-field` round-trips
        List<ElementDraft.SortField> sorts = doc.sortFields(group);
        if (!sorts.isEmpty()) {
            ArrayNode sortFields = data.putArray("sortFields");
            sorts.forEach(sf -> {
                ObjectNode n = sortFields.addObject();
                n.put("name", sf.fieldName());
                n.put("order", sf.order().name());
            });
        }
        // the group's DIRECT child fields, so a just-built composite group is confirmable through the read
        // alone (probe 10). Direct children only (a deeper field belongs to a sub-group).
        String prefix = group + "/";
        ArrayNode fields = data.putArray("fields");
        doc.fieldPaths().stream()
                .filter(p -> p.startsWith(prefix) && p.indexOf('/', prefix.length()) < 0)
                .forEach(fields::add);
        // target-specific reads retain their metadata projection (G45); `meta` is the write owner
        doc.labels(group).ifPresent(m -> putIfNotEmpty(data, "label", m));
        putIfNotEmpty(data, "annotations", doc.annotations(group));
        putIfNotEmpty(data, "internalDescription", doc.internalDescription(group));
        putIfNotEmpty(data, "externalDescription", doc.externalDescription(group));
        return data;
    }

    /** The full {@code config read} data (the whole {@code modelInfo}/{@code modelConfig}/header surface). */
    public static ObjectNode config(ModelDocument doc) {
        ModelDocument.DocConfig c = doc.config();
        ObjectNode data = MAPPER.createObjectNode();
        data.put("decimalSeparator", c.decimalSeparator());
        data.put("timeZone", c.timeZone());
        data.put("conditionLanguage", c.conditionLanguage());
        data.put("fieldRefByShortNameAllowed", c.fieldRefByShortNameAllowed());
        // singleton/range/atomic-grapheme entries; empty selects the BMP default (never supplementary chars)
        ArrayNode supported = data.putArray("supportedCharacters");
        c.supportedCharacters().forEach(supported::add);
        // the model-level per-locale label (header.labels) — the read peer of `config modify --label`
        Map<String, String> modelLabels = doc.modelLabels();
        if (!modelLabels.isEmpty()) {
            ObjectNode label = data.putObject("label");
            modelLabels.forEach(label::put);
        }
        doc.modelComment().ifPresent(comment -> data.put("comment", comment));
        doc.baseYear().ifPresent(year -> data.put("baseYear", year));
        // the model-level header annotations (incl. the access-control `roles`, G26) so a roles edit round-trips
        Map<String, String> modelAnnotations = doc.modelAnnotations();
        if (!modelAnnotations.isEmpty()) {
            ObjectNode annotations = data.putObject("annotations");
            modelAnnotations.forEach(annotations::put);
        }
        // the declared message locales (header.locales) drive the per-locale-message law — surface the full set
        ArrayNode locales = data.putArray("locales");
        doc.locales().forEach(locales::add);
        return data;
    }

    /** The full {@code typedef read} (no-arg) data — the LOCAL ids plus the import sources ({@code importedFrom}). */
    public static ObjectNode typedefList(ModelDocument doc) {
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode arr = data.putArray("typedefs");
        doc.typeDefinitionIds().forEach(arr::add);
        List<String> imports = doc.typeDefImports();
        if (!imports.isEmpty()) {
            ArrayNode importsArr = data.putArray("importedFrom");
            imports.forEach(importsArr::add);
        }
        return data;
    }

    /**
     * A kit config-block read-back ({@code ModelDocument.fieldConfigJson}/{@code typeDefinitionConfigJson})
     * parsed into a merge-ready object node — the kits speak JSON text across the published boundary (Set W),
     * and the read verbs re-join it to their envelope here. The blocks carry only text/int/boolean leaves,
     * so the parse is lossless and the rendered envelope is byte-identical to the pre-Set-W node hand-off.
     */
    public static ObjectNode configNode(String configJson) {
        try {
            return (ObjectNode) MAPPER.readTree(configJson);
        } catch (IOException e) {
            throw new UncheckedIOException("unparseable config block from the kit read-back: " + configJson, e);
        }
    }

    /** Echo a per-locale/per-name metadata map under {@code key}, only when it carries something. */
    private static void putIfNotEmpty(ObjectNode data, String key, Map<String, String> m) {
        if (m != null && !m.isEmpty()) {
            data.set(key, MAPPER.valueToTree(m));
        }
    }
}
