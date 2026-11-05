package io.github.mbackschat.a12.dm.cli.criterion;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.modelkit.UniquenessCriterion;

/**
 * The JSON form of a criterion at the CLI boundary, in both directions: one entry of A12's stored
 * {@code content.documentUniquenessCriteria} member, {@code {name, fields:[{fullName}], errorMessage:[{locale, text}]}}.
 * {@code criterion add} and its apply op read it, and {@code criterion read} writes it, so a read entry is a valid spec.
 * Reading goes through the kits' one reader ({@link UniquenessCriterion#fromJson}), which reads it as A12 does. Writing
 * builds the envelope's node from the typed criterion in the same shape the kits store, empty members left out
 * ({@code CriterionVerbTest} holds the two equal). Only the name is demanded, because remove and read address a
 * criterion by it; what A12 admits in the fields and texts is the kernel gate's to decide (KERNEL-FINDINGS KF671).
 */
public final class CriterionSpec {

    private static final String FIELDS = "fields";
    private static final String FULL_NAME = "fullName";

    /** The entry's members, which are also the apply op's keys. */
    public static final Set<String> KEYS = Set.of(DmKeys.NAME, FIELDS, DmKeys.ERROR_MESSAGE);

    private CriterionSpec() {
    }

    /** The criterion {@code spec} declares; refuses a spec without a textual name. */
    public static UniquenessCriterion parse(JsonNode spec) {
        if (!spec.path(DmKeys.NAME).isTextual()) {
            throw new IllegalArgumentException("a uniqueness criterion needs a `name` string");
        }
        return UniquenessCriterion.fromJson(spec.toString());
    }

    /** {@code criterion} as one stored entry, the inverse of {@link #parse}. */
    public static ObjectNode toJson(UniquenessCriterion criterion) {
        ObjectNode entry = JsonNodeFactory.instance.objectNode();
        if (!criterion.name().isEmpty()) {
            entry.put(DmKeys.NAME, criterion.name());
        }
        if (!criterion.fieldFullNames().isEmpty()) {
            ArrayNode fields = entry.putArray(FIELDS);
            criterion.fieldFullNames().forEach(field -> fields.addObject().put(FULL_NAME, field));
        }
        if (!criterion.errorMessage().isEmpty()) {
            ArrayNode texts = entry.putArray(DmKeys.ERROR_MESSAGE);
            criterion.errorMessage().forEach((locale, text) -> texts.addObject().put(DmKeys.LOCALE, locale).put(DmKeys.TEXT, text));
        }
        return entry;
    }
}
