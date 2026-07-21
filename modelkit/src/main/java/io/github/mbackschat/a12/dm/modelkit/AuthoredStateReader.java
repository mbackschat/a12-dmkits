package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;

/** Single-pass JSON-tree reader behind {@link ModelDocument#authoredState()}. */
final class AuthoredStateReader {

    private AuthoredStateReader() {}

    static ModelDocument.AuthoredState read(ObjectNode root) {
        List<ModelDocument.AuthoredElement> elements = new ArrayList<>();
        Map<String, ModelDocument.FieldState> fields = new LinkedHashMap<>();
        Map<String, ModelDocument.GroupState> groups = new LinkedHashMap<>();
        Map<String, ModelDocument.RuleState> rules = new LinkedHashMap<>();
        for (JsonNode rootGroup : root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS)) {
            collect(rootGroup, "", elements, fields, groups, rules);
        }
        return new ModelDocument.AuthoredState(elements, fields, groups, rules);
    }

    private static void collect(JsonNode node, String parentPath, List<ModelDocument.AuthoredElement> elements,
            Map<String, ModelDocument.FieldState> fields, Map<String, ModelDocument.GroupState> groups,
            Map<String, ModelDocument.RuleState> rules) {
        ElementType type = ElementType.fromWire(node.path(DmKeys.TYPE).asText(null));
        if (type == null) {
            return;
        }
        String path = parentPath + "/" + node.path(DmKeys.NAME).asText();
        elements.add(new ModelDocument.AuthoredElement(type, path));
        if (type == ElementType.FIELD) {
            fields.put(path, field(path, node));
            return;
        }
        if (type == ElementType.RULE) {
            rules.put(path, new ModelDocument.RuleState(path, descriptive(node)));
            return;
        }
        if (type != ElementType.GROUP) {
            return;
        }
        ObjectNode body = (ObjectNode) node.path(ElementType.GROUP.wireName());
        groups.put(path, new ModelDocument.GroupState(path, structure(body), metadata(node, body)));
        for (JsonNode child : body.path(DmKeys.ELEMENTS)) {
            collect(child, path, elements, fields, groups, rules);
        }
    }

    private static ModelDocument.FieldState field(String path, JsonNode node) {
        JsonNode body = node.path(ElementType.FIELD.wireName());
        JsonNode required = body.path(DmKeys.REQUIREDNESS_CONFIG);
        var requirements = new ModelDocument.FieldRequirements(
                Requiredness.fromMode(required.path("mode").asText(null)), localized(required, DmKeys.ERROR_MESSAGE));
        var meta = new FieldMeta(localized(body, DmKeys.LABEL), annotations(node),
                body.path("global").asBoolean(false), body.path("transient").asBoolean(false),
                localized(node, DmKeys.INTERNAL_DESCRIPTION), localized(node, DmKeys.EXTERNAL_DESCRIPTION),
                localized(body, "helperText"));
        return new ModelDocument.FieldState(path, FieldTypeSpecReader.read(body.path(DmKeys.FIELD_TYPE)),
                requirements, meta);
    }

    private static ModelDocument.GroupStructure structure(ObjectNode body) {
        List<ElementDraft.SortField> sortFields = new ArrayList<>();
        for (JsonNode sort : body.path(DmKeys.SORT_FIELDS)) {
            sortFields.add(new ElementDraft.SortField(sort.path(DmKeys.NAME).asText(),
                    SortOrder.valueOf(sort.path(DmKeys.ORDER).asText(SortOrder.ASC.name()))));
        }
        int repeatability = body.path(DmKeys.REPEATABILITY).asInt(1);
        String alias = body.path(DmKeys.MODEL_ALIAS).asText(null);
        Optional<ModelDocument.MountState> mount = alias == null || alias.isBlank() ? Optional.empty()
                : Optional.of(new ModelDocument.MountState(alias, new MountOptions(
                        repeatability,
                        body.path("excludeRules").asBoolean(false),
                        body.path("excludeComputations").asBoolean(false),
                        "MODEL_ROOT".equals(body.path("includeLevel").asText())
                                ? IncludeLevel.MODEL_ROOT : IncludeLevel.SINGLE_RG)));
        return new ModelDocument.GroupStructure(repeatability,
                Optional.ofNullable(body.path(DmKeys.INDEX_FIELD_NAME).asText(null)), sortFields, mount);
    }

    private static GroupMeta metadata(JsonNode node, ObjectNode body) {
        ModelDocument.DescriptiveState descriptive = descriptive(node);
        return new GroupMeta(localized(body, DmKeys.LABEL), descriptive.annotations(),
                descriptive.internalDescription(), descriptive.externalDescription());
    }

    static ModelDocument.DescriptiveState descriptive(JsonNode node) {
        return new ModelDocument.DescriptiveState(localized(node, DmKeys.INTERNAL_DESCRIPTION),
                localized(node, DmKeys.EXTERNAL_DESCRIPTION), annotations(node));
    }

    private static List<ElementDraft.Annotation> annotations(JsonNode node) {
        List<ElementDraft.Annotation> annotations = new ArrayList<>();
        for (JsonNode annotation : node.path(DmKeys.ANNOTATIONS)) {
            annotations.add(new ElementDraft.Annotation(
                    annotation.path(DmKeys.NAME).asText(), annotation.path(DmKeys.VALUE).asText()));
        }
        return annotations;
    }

    private static Map<String, String> localized(JsonNode node, String key) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode localized : node.path(key)) {
            values.put(localized.path(DmKeys.LOCALE).asText(), localized.path(DmKeys.TEXT).asText());
        }
        return values;
    }
}
