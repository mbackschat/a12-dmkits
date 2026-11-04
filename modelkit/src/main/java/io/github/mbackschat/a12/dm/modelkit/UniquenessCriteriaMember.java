package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;

/** Reads and edits {@code content.documentUniquenessCriteria}, the member that stores {@link UniquenessCriterion}s. */
final class UniquenessCriteriaMember {

    /** The content members A12's model class declares after the criteria ({@code DocumentModelContent}, kernel 31.1.1). */
    private static final List<String> DECLARED_AFTER = List.of(DmKeys.TYPE_DEFINITIONS, DmKeys.MODEL_ROOT);

    private UniquenessCriteriaMember() {
    }

    static List<UniquenessCriterion> read(JsonNode content) {
        List<UniquenessCriterion> criteria = new ArrayList<>();
        content.path(DmKeys.DOCUMENT_UNIQUENESS_CRITERIA).forEach(entry -> criteria.add(UniquenessCriterion.read(entry)));
        return List.copyOf(criteria);
    }

    /** The draft's write: the member in the order A12 declares it, so call it between modelConfig and typeDefinitions. */
    static void write(ObjectNode content, List<UniquenessCriterion> criteria) {
        if (!criteria.isEmpty()) {
            ArrayNode member = content.putArray(DmKeys.DOCUMENT_UNIQUENESS_CRITERIA);
            criteria.forEach(criterion -> criterion.writeTo(member));
        }
    }

    /**
     * Appends {@code criterion}, refusing a name the model already uses: A12 refuses two criteria of one name
     * (KERNEL-FINDINGS KF671), and a remove by name needs the name to pick out one.
     */
    static void add(ObjectNode content, UniquenessCriterion criterion) {
        if (read(content).stream().anyMatch(existing -> existing.name().equals(criterion.name()))) {
            throw new IllegalArgumentException("the model already has a uniqueness criterion named '"
                    + criterion.name() + "'");
        }
        criterion.writeTo(member(content));
    }

    /** Removes the criterion named {@code name}, and the member once it is empty; refuses a name no criterion has. */
    static void remove(ObjectNode content, String name) {
        JsonNode member = content.path(DmKeys.DOCUMENT_UNIQUENESS_CRITERIA);
        for (int i = 0; i < member.size(); i++) {
            if (name.equals(member.get(i).path(DmKeys.NAME).asText())) {
                ((ArrayNode) member).remove(i);
                if (member.isEmpty()) {
                    content.remove(DmKeys.DOCUMENT_UNIQUENESS_CRITERIA);
                }
                return;
            }
        }
        throw new IllegalArgumentException("the model has no uniqueness criterion named '" + name + "'");
    }

    /** The member array, created where A12's model class declares it when the model has none. */
    private static ArrayNode member(ObjectNode content) {
        if (content.get(DmKeys.DOCUMENT_UNIQUENESS_CRITERIA) instanceof ArrayNode existing) {
            return existing;
        }
        Map<String, JsonNode> after = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> m : content.properties()) {
            if (!after.isEmpty() || DECLARED_AFTER.contains(m.getKey())) {
                after.put(m.getKey(), m.getValue());
            }
        }
        after.keySet().forEach(content::remove);
        ArrayNode member = content.putArray(DmKeys.DOCUMENT_UNIQUENESS_CRITERIA);
        content.setAll(after);
        return member;
    }
}
