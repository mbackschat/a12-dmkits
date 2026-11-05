package io.github.mbackschat.a12.dm.cli.criterion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Only the name is demanded, because remove and read address a criterion by it; what A12 admits in the fields and
 * texts is the kernel gate's to decide (KERNEL-FINDINGS KF671). Every member is a string, so no number-fidelity
 * concern of the A12 codec arises here.
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
        JsonNode name = spec.path(DmKeys.NAME);
        if (!name.isTextual()) {
            throw new IllegalArgumentException("a uniqueness criterion needs a `name` string");
        }
        List<String> fields = new ArrayList<>();
        spec.path(FIELDS).forEach(field -> fields.add(field.path(FULL_NAME).asText()));
        Map<String, String> texts = new LinkedHashMap<>();
        spec.path(DmKeys.ERROR_MESSAGE).forEach(text -> texts.put(text.path(DmKeys.LOCALE).asText(),
                text.path(DmKeys.TEXT).asText()));
        return new UniquenessCriterion(name.asText(), fields, texts);
    }

    /** {@code criterion} as one stored entry, the inverse of {@link #parse}. */
    public static ObjectNode toJson(UniquenessCriterion criterion) {
        ObjectNode entry = JsonNodeFactory.instance.objectNode().put(DmKeys.NAME, criterion.name());
        ArrayNode fields = entry.putArray(FIELDS);
        criterion.fieldFullNames().forEach(field -> fields.addObject().put(FULL_NAME, field));
        ArrayNode texts = entry.putArray(DmKeys.ERROR_MESSAGE);
        criterion.errorMessage().forEach((locale, text) -> texts.addObject().put(DmKeys.LOCALE, locale).put(DmKeys.TEXT, text));
        return entry;
    }
}
