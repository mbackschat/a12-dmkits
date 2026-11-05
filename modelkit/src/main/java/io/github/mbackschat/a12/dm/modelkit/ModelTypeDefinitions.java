package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;

/**
 * A model's reusable type definitions ({@code content.typeDefinitions}), read and edited on {@link ModelDocument}'s
 * tree together with the fields that point at them, since a typedef refactor rewrites both in step.
 */
final class ModelTypeDefinitions {

    /** The field-type discriminator, and wrapper key, of a field typed by a definition. */
    private static final String TYPE_DEF_TYPE = "TypeDefType";

    private final ModelDocument doc;
    private final ObjectNode root;

    ModelTypeDefinitions(ModelDocument doc, ObjectNode root) {
        this.doc = doc;
        this.root = root;
    }

    List<String> ids() {
        List<String> out = new ArrayList<>();
        for (JsonNode t : declared()) {
            out.add(t.path(DmKeys.ID).asText());
        }
        return out;
    }

    Map<String, ModelDocument.TypeDefinitionState> all() {
        Map<String, ModelDocument.TypeDefinitionState> definitions = new LinkedHashMap<>();
        for (JsonNode definition : declared()) {
            String id = definition.path(DmKeys.ID).asText();
            definitions.put(id, new ModelDocument.TypeDefinitionState(id,
                    FieldTypeSpecReader.read(definition.path(DmKeys.FIELD_TYPE)),
                    AuthoredStateReader.descriptive(definition)));
        }
        return Collections.unmodifiableMap(definitions);
    }

    Optional<String> type(String id) {
        return find(id).map(t -> t.path(DmKeys.FIELD_TYPE).path(DmKeys.TYPE).asText(null));
    }

    /** The definition's config in the schema-block shape a {@code typedef add} spec carries; empty when it has none. */
    Optional<ObjectNode> config(String id) {
        return find(id).map(t -> t.path(DmKeys.FIELD_TYPE)).filter(fieldType -> !fieldType.isMissingNode())
                .map(TypeConfigReader::read).filter(cfg -> !cfg.isEmpty());
    }

    void add(String id, FieldTypeSpec type) {
        ObjectNode node = editableDeclared().addObject();
        node.put(DmKeys.ID, id);
        node.put(DmKeys.NAME, id);
        DocumentModelDraft.writeFieldType(node.putObject(DmKeys.FIELD_TYPE), type, doc.locales());
    }

    void setType(String id, FieldTypeSpec type) {
        ObjectNode definition = (ObjectNode) find(id).orElseThrow(() -> absent(id));
        DocumentModelDraft.writeFieldType(definition.putObject(DmKeys.FIELD_TYPE), type, doc.locales());
    }

    List<String> fieldsUsing(String id) {
        List<String> out = new ArrayList<>();
        forEachFieldTypedBy(id, (path, field) -> out.add(path));
        return out;
    }

    void rename(String oldId, String newId) {
        boolean renamed = false;
        for (JsonNode td : declared()) {
            if (oldId.equals(td.path(DmKeys.ID).asText())) {
                ObjectNode node = (ObjectNode) td;
                node.put(DmKeys.ID, newId);
                if (oldId.equals(node.path(DmKeys.NAME).asText())) {
                    node.put(DmKeys.NAME, newId);   // a default name (== id) tracks the rename; a custom one stays
                }
                renamed = true;
            }
        }
        if (!renamed) {
            throw absent(oldId);
        }
        forEachFieldTypedBy(oldId, (path, field) ->
                ((ObjectNode) field.path(DmKeys.FIELD_TYPE).path(TYPE_DEF_TYPE)).put(DmKeys.TYPE_DEFINITION_ID, newId));
    }

    void extract(String newId, List<String> fieldPaths) {
        JsonNode shared = sharedConcreteFieldType(fieldPaths);
        ObjectNode td = editableDeclared().addObject();
        td.put(DmKeys.ID, newId);
        td.put(DmKeys.NAME, newId);
        td.set(DmKeys.FIELD_TYPE, shared.deepCopy());
        for (String fieldPath : fieldPaths) {
            doc.setFieldType(fieldPath, new FieldTypeSpec.TypeDefRef(newId));
        }
    }

    boolean fieldsShareConcreteType(List<String> fieldPaths) {
        try {
            sharedConcreteFieldType(fieldPaths);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private JsonNode sharedConcreteFieldType(List<String> fieldPaths) {
        if (fieldPaths.isEmpty()) {
            throw new IllegalArgumentException("no fields to extract");
        }
        JsonNode shared = null;
        for (String fieldPath : fieldPaths) {
            JsonNode field = doc.locate(fieldPath, false);
            if (field == null) {
                throw new IllegalArgumentException("no field at: " + fieldPath);
            }
            JsonNode fieldType = field.path(ElementType.FIELD.wireName()).path(DmKeys.FIELD_TYPE);
            if (TYPE_DEF_TYPE.equals(fieldType.path(DmKeys.TYPE).asText())) {
                throw new IllegalArgumentException(fieldPath + " is already typed by a definition");
            }
            if (shared == null) {
                shared = fieldType;
            } else if (!shared.equals(fieldType)) {
                throw new IllegalArgumentException("fields do not share an identical type: " + fieldPath);
            }
        }
        return shared;
    }

    void inline(String id) {
        JsonNode underlying = find(id).orElseThrow(() -> absent(id)).path(DmKeys.FIELD_TYPE);
        forEachFieldTypedBy(id, (path, field) -> field.set(DmKeys.FIELD_TYPE, underlying.deepCopy()));
        remove(id);
    }

    void remove(String id) {
        ArrayNode array = declared();
        for (int i = 0; i < array.size(); i++) {
            if (id.equals(array.get(i).path(DmKeys.ID).asText())) {
                array.remove(i);
                return;
            }
        }
        throw absent(id);
    }

    /** The first definition declaring {@code id}, the one every by-id read and edit addresses. */
    private Optional<JsonNode> find(String id) {
        for (JsonNode t : declared()) {
            if (id.equals(t.path(DmKeys.ID).asText())) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** Run {@code action} on the body of every field typed by the definition {@code id}, in document order. */
    private void forEachFieldTypedBy(String id, BiConsumer<String, ObjectNode> action) {
        doc.walk((path, node, isGroup) -> {
            JsonNode field = node.path(ElementType.FIELD.wireName());
            if (!isGroup && id.equals(field.path(DmKeys.FIELD_TYPE).path(TYPE_DEF_TYPE)
                    .path(DmKeys.TYPE_DEFINITION_ID).asText(null))) {
                action.accept(path, (ObjectNode) field);
            }
        });
    }

    /** The declared definitions, an empty array when the model declares none (never attached to the tree). */
    private ArrayNode declared() {
        JsonNode tds = root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS);
        return tds.isArray() ? (ArrayNode) tds : ModelDocument.MAPPER.createArrayNode();
    }

    /** The declared definitions, created on the tree if absent, for an edit that adds one. */
    private ArrayNode editableDeclared() {
        JsonNode tds = root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS);
        return tds.isArray() ? (ArrayNode) tds : ((ObjectNode) root.path(DmKeys.CONTENT)).putArray(DmKeys.TYPE_DEFINITIONS);
    }

    private static IllegalArgumentException absent(String id) {
        return new IllegalArgumentException("no type definition with id: " + id);
    }
}
