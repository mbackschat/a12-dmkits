package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;

/**
 * A document uniqueness criterion: a named list of fields whose values no two stored documents may share, with an
 * error text per locale. The shape is A12's {@code IDocumentUniquenessCriterion} ({@code name},
 * {@code fieldFullNames}, {@code errorMessage}), stored as one entry of {@code content.documentUniquenessCriteria}
 * as {@code {name, fields:[{fullName}], errorMessage:[{locale, text}]}}.
 *
 * <p>The record holds what is written and refuses nothing a stored model can carry. Whether a model admits it (a
 * required, persisted, non-repeatable field named by its full path, a text for every model locale whose
 * {@code $path$} parameters name only the criterion's fields) is A12's consistency check to decide, in its own
 * sentence (KERNEL-FINDINGS KF671). So a criterion read back from an inadmissible model still reads.
 *
 * <p>The fields are a set, as A12 holds them ({@code SequencedSet} in {@code IDocumentUniquenessCriterion}): a
 * repeated path is kept once, at its first position.
 *
 * @param name the criterion's name, unique within the model
 * @param fieldFullNames the fields' full paths, in authored order, each once
 * @param errorMessage the error text by locale code (for example {@code en_US}), in authored order
 */
public record UniquenessCriterion(String name, List<String> fieldFullNames, Map<String, String> errorMessage) {

    private static final String FIELDS = "fields";
    private static final String FULL_NAME = "fullName";

    public UniquenessCriterion {
        Objects.requireNonNull(name, "name");
        fieldFullNames = List.copyOf(new LinkedHashSet<>(fieldFullNames));
        errorMessage = OrderedMap.copyOf(errorMessage);
    }

    /**
     * The criterion one stored entry declares, read as A12 reads it: an absent member is its empty default, and a
     * field item without {@code fullName} names no field ({@code JsonToFieldFullNamesConverter} in A12's
     * {@code KernelJsonMapper}, kernel 31.1.1).
     *
     * @param entryJson one entry of {@code content.documentUniquenessCriteria}
     * @throws IllegalArgumentException when {@code entryJson} is not JSON
     */
    public static UniquenessCriterion fromJson(String entryJson) {
        try {
            return read(ModelDocument.MAPPER.readTree(entryJson));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("a uniqueness criterion entry is not JSON: " + e.getOriginalMessage(), e);
        }
    }

    static UniquenessCriterion read(JsonNode entry) {
        List<String> fields = new ArrayList<>();
        entry.path(FIELDS).forEach(field -> {
            if (field.hasNonNull(FULL_NAME)) {
                fields.add(field.get(FULL_NAME).asText());
            }
        });
        Map<String, String> text = new LinkedHashMap<>();
        entry.path(DmKeys.ERROR_MESSAGE).forEach(e -> text.put(e.path(DmKeys.LOCALE).asText(), e.path(DmKeys.TEXT).asText()));
        return new UniquenessCriterion(entry.path(DmKeys.NAME).asText(), fields, text);
    }

    /**
     * Append this criterion to {@code criteria} in the stored shape, leaving out an empty member as A12's model
     * writer does (its base mapper includes only non-empty values, {@code KernelJsonMapper}, kernel 31.1.1).
     */
    void writeTo(ArrayNode criteria) {
        ObjectNode entry = criteria.addObject();
        if (!name.isEmpty()) {
            entry.put(DmKeys.NAME, name);
        }
        if (!fieldFullNames.isEmpty()) {
            ArrayNode fields = entry.putArray(FIELDS);
            fieldFullNames.forEach(field -> fields.addObject().put(FULL_NAME, field));
        }
        if (!errorMessage.isEmpty()) {
            ArrayNode text = entry.putArray(DmKeys.ERROR_MESSAGE);
            LocalizedEntries.appendAll(text, errorMessage);
        }
    }
}
