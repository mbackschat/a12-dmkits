package io.github.mbackschat.a12.dm.modelkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;

/**
 * A model's references to other models, edited on {@link ModelDocument}'s tree: its header {@code modelReferences}
 * (includes and type-definition imports) and the mount groups an include places. Kept apart because the two are
 * edited in step, a mount and its header entry, and nothing else in the document needs that bookkeeping.
 */
final class ModelIncludes {

    /** The {@code modelReferences.purpose} value for a type-def import (kernel {@code PURPOSE_4_ONLY_TYPE_DEFS}). */
    private static final String IMPORT_PURPOSE = "typeDefinitions";
    /** A direct, authored mount (the classic include). Absent/blank purpose is treated as this. */
    private static final String INCLUDE_PURPOSE = "include";
    /** A reference the kernel adds for an include pulled in <em>transitively</em> (DG6/A12K-3234). */
    private static final String TRANSITIVE_INCLUDE_PURPOSE = "transitiveInclude";

    private final ModelDocument doc;
    private final ObjectNode root;

    ModelIncludes(ModelDocument doc, ObjectNode root) {
        this.doc = doc;
        this.root = root;
    }

    List<ModelDocument.Include> includes() {
        Set<String> references = new LinkedHashSet<>();
        for (JsonNode r : root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            if (isIncludeEntry(r)) {
                references.add(r.path(DmKeys.REFERENCE).asText());
            }
        }
        doc.walk((path, node, isGroup) -> {
            if (isGroup) {
                mountReference(node.path(ElementType.GROUP.wireName())).ifPresent(references::add);
            }
        });
        return references.stream().map(ModelDocument.Include::new).toList();
    }

    List<ModelDocument.Include> transitiveIncludes() {
        List<ModelDocument.Include> out = new ArrayList<>();
        for (JsonNode r : root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            if (TRANSITIVE_INCLUDE_PURPOSE.equals(r.path(DmKeys.PURPOSE).asText())) {
                out.add(new ModelDocument.Include(r.path(DmKeys.REFERENCE).asText()));
            }
        }
        return out;
    }

    private static boolean isIncludeEntry(JsonNode reference) {
        String purpose = reference.path(DmKeys.PURPOSE).asText();
        return INCLUDE_PURPOSE.equals(purpose) || purpose.isEmpty();
    }

    List<String> typeDefImports() {
        List<String> out = new ArrayList<>();
        for (JsonNode r : root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            if (IMPORT_PURPOSE.equals(r.path(DmKeys.PURPOSE).asText())) {
                out.add(r.path(DmKeys.REFERENCE).asText());
            }
        }
        return out;
    }

    /** The model a group body mounts: its {@code includeConfig.reference}, the empty text when that is absent. */
    static Optional<String> mountReference(JsonNode groupBody) {
        JsonNode include = groupBody.path(DmKeys.INCLUDE_CONFIG);
        return include.isObject() ? Optional.of(include.path(DmKeys.REFERENCE).asText("")) : Optional.empty();
    }

    /** Write the header include entry for {@code reference} unless one is present: one per distinct mounted model,
     *  without alias, as A12 Tools and the migrator write it (KF651). */
    private void declareInclude(String reference) {
        for (JsonNode r : modelReferences()) {
            if (isIncludeEntry(r) && reference.equals(r.path(DmKeys.REFERENCE).asText())) {
                return;
            }
        }
        modelReferences().addObject()
                .put(DmKeys.MODEL_TYPE, DmKeys.DOCUMENT)
                .put(DmKeys.PURPOSE, INCLUDE_PURPOSE)
                .put(DmKeys.REFERENCE, reference);
    }

    /** Drop every header include entry naming {@code reference}; whether one was present. */
    private boolean undeclareInclude(String reference) {
        JsonNode refs = root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES);
        boolean removed = false;
        if (refs.isArray()) {
            ArrayNode array = (ArrayNode) refs;
            for (int i = array.size() - 1; i >= 0; i--) {
                if (isIncludeEntry(array.get(i)) && reference.equals(array.get(i).path(DmKeys.REFERENCE).asText())) {
                    array.remove(i);
                    removed = true;
                }
            }
        }
        return removed;
    }

    void addImport(String reference) {
        modelReferences().addObject()
                .put(DmKeys.MODEL_TYPE, DmKeys.DOCUMENT)
                .put(DmKeys.PURPOSE, IMPORT_PURPOSE)
                .put(DmKeys.REFERENCE, reference);
    }

    void removeImport(String reference) {
        boolean present = false;
        for (JsonNode r : root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            if (IMPORT_PURPOSE.equals(r.path(DmKeys.PURPOSE).asText()) && reference.equals(r.path(DmKeys.REFERENCE).asText())) {
                present = true;
                break;
            }
        }
        if (!present) {
            throw new IllegalArgumentException("no type-definition import for '" + reference + "'");
        }
        JsonNode header = root.path(DmKeys.HEADER);
        ArrayNode kept = ModelDocument.MAPPER.createArrayNode();
        for (JsonNode r : header.path(DmKeys.MODEL_REFERENCES)) {
            boolean isThisImport = IMPORT_PURPOSE.equals(r.path(DmKeys.PURPOSE).asText())
                    && reference.equals(r.path(DmKeys.REFERENCE).asText());
            if (!isThisImport) {
                kept.add(r);
            }
        }
        ((ObjectNode) header).set(DmKeys.MODEL_REFERENCES, kept);
    }

    /** The header's {@code modelReferences} array, created if absent. */
    private ArrayNode modelReferences() {
        JsonNode refs = root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES);
        return refs.isArray() ? (ArrayNode) refs
                : ((ObjectNode) root.path(DmKeys.HEADER)).putArray(DmKeys.MODEL_REFERENCES);
    }

    void mount(String parentPath, String name, String reference, MountOptions options) {
        ArrayNode container = parentPath.isEmpty() ? doc.rootGroups() : doc.containerElements(parentPath);
        container.add(DocumentModelDraft.mountNode(
                new ElementDraft.Mount(name, reference, options, List.of()), parentPath));
        declareInclude(reference);
    }

    String extractGroupAsInclude(String groupPath, String reference, String rootName) {
        JsonNode group = doc.locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at: " + groupPath);
        }
        String mountName = group.path(DmKeys.NAME).asText();   // the base mount keeps this — preserves composed paths
        String subRoot = (rootName != null && !rootName.isBlank()) ? rootName : mountName;

        // the sub-model: the base envelope, its own id, no inherited includes, one root = the rebased subtree.
        ObjectNode sub = root.deepCopy();
        ObjectNode subHeader = (ObjectNode) sub.path(DmKeys.HEADER);
        subHeader.put(DmKeys.ID, reference);
        subHeader.putArray(DmKeys.MODEL_REFERENCES);   // empty (not absent — the kernel expects a list), no inherited includes
        // the parent's uniqueness criteria stay with the parent: copied, they name fields the sub-model lacks and
        // refuse it, while A12's expansion would drop an included model's criteria anyway (KF671)
        ((ObjectNode) sub.path(DmKeys.CONTENT)).remove(DmKeys.DOCUMENT_UNIQUENESS_CRITERIA);
        // the deep-copy carried the parent's modelInfo.name — rename it for the new sub-model (else an
        // extracted "address" sub-model stays named "person"; probe P4).
        if (sub.path(DmKeys.CONTENT).path(DmKeys.MODEL_INFO) instanceof ObjectNode subInfo) {
            subInfo.put(DmKeys.NAME, reference);
        }
        ObjectNode subGroup = group.deepCopy();
        subGroup.put(DmKeys.NAME, subRoot);   // the sub-model root's own name (= the group's, unless rootName overrides)
        SubtreeReprefix.ids(subGroup, groupPath, "/" + subRoot);
        ((ObjectNode) sub.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT)).putArray(DmKeys.ROOT_GROUPS).add(subGroup);
        String subModelJson;
        try {
            subModelJson = ModelDocument.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(FloatTokens.forWrite(sub));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException("failed to serialize the extracted sub-model", e));
        }

        // the base: drop the group and mount the sub-model at the same name (path preserved).
        doc.removeGroup(groupPath);
        doc.mount(groupPath.substring(0, groupPath.lastIndexOf('/')), mountName, reference);
        return subModelJson;
    }

    void inlineInclude(String reference, String includedModelJson) {
        ModelDocument included = ModelDocument.read(includedModelJson);
        JsonNode includedRoot = included.tree().path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS).path(0);
        if (includedRoot.isMissingNode()) {
            throw new IllegalArgumentException("included model has no root group: " + reference);
        }
        String includedRootId = includedRoot.path(DmKeys.ID).asText();
        JsonNode includedElements = includedRoot.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS);
        boolean[] folded = {false};
        inlineMounts(doc.rootGroups(), reference, includedRootId, includedElements, folded);
        if (!folded[0]) {
            throw new IllegalArgumentException("no group mounts the include: " + reference);
        }
        undeclareInclude(reference);
    }

    private void inlineMounts(JsonNode elements, String reference, String includedRootId, JsonNode includedElements,
            boolean[] folded) {
        for (JsonNode element : elements) {
            if (!DmNodes.is(ElementType.GROUP, element)) {
                continue;
            }
            ObjectNode body = DmNodes.groupBody(element);
            if (mountReference(body).filter(reference::equals).isPresent()) {
                String mountPath = element.path(DmKeys.ID).asText();
                body.remove(DmKeys.INCLUDE_CONFIG);
                ArrayNode destination = body.putArray(DmKeys.ELEMENTS);
                for (JsonNode child : includedElements) {
                    JsonNode copy = child.deepCopy();
                    SubtreeReprefix.ids(copy, includedRootId, mountPath);
                    destination.add(copy);
                }
                folded[0] = true;
            } else if (body.path(DmKeys.ELEMENTS).isArray()) {
                inlineMounts(body.path(DmKeys.ELEMENTS), reference, includedRootId, includedElements, folded);
            }
        }
    }

    List<String> mountsOfInclude(String reference) {
        List<String> out = new ArrayList<>();
        doc.walk((path, node, isGroup) -> {
            if (isGroup && mountReference(node.path(ElementType.GROUP.wireName())).filter(reference::equals).isPresent()) {
                out.add(path);
            }
        });
        return out;
    }

    void removeInclude(String reference) {
        boolean[] mountRemoved = {false};
        removeMounts(doc.rootGroups(), reference, mountRemoved);
        if (!undeclareInclude(reference) && !mountRemoved[0]) {
            throw new IllegalArgumentException("no include of model: " + reference);
        }
    }

    void setIncludeReference(String reference, String newReference) {
        boolean[] repointed = {false};
        repointMounts(doc.rootGroups(), reference, newReference, repointed);
        if (!undeclareInclude(reference) && !repointed[0]) {
            throw new IllegalArgumentException("no include of model: " + reference);
        }
        declareInclude(newReference);
    }

    private void repointMounts(ArrayNode elements, String reference, String newReference, boolean[] any) {
        for (JsonNode el : elements) {
            if (!DmNodes.is(ElementType.GROUP, el)) {
                continue;
            }
            ObjectNode body = DmNodes.groupBody(el);
            if (mountReference(body).filter(reference::equals).isPresent()) {
                ((ObjectNode) body.path(DmKeys.INCLUDE_CONFIG)).put(DmKeys.REFERENCE, newReference);
                any[0] = true;
            } else if (body.path(DmKeys.ELEMENTS).isArray()) {
                repointMounts((ArrayNode) body.path(DmKeys.ELEMENTS), reference, newReference, any);
            }
        }
    }

    private void removeMounts(ArrayNode elements, String reference, boolean[] any) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            JsonNode el = elements.get(i);
            if (!DmNodes.is(ElementType.GROUP, el)) {
                continue;
            }
            JsonNode body = el.path(ElementType.GROUP.wireName());
            if (mountReference(body).filter(reference::equals).isPresent()) {
                elements.remove(i);
                any[0] = true;
            } else if (body.path(DmKeys.ELEMENTS).isArray()) {
                removeMounts((ArrayNode) body.path(DmKeys.ELEMENTS), reference, any);
            }
        }
    }
}
