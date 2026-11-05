package io.github.mbackschat.a12.dm.modelkit;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;

/** Reads a stored field type's configuration back into the blocks the field verbs write. */
final class TypeConfigReader {

    private TypeConfigReader() {
    }

    /**
     * The inverse of {@code DocumentModelDraft.writeTypeConfig}: project a stored kernel {@code fieldType} node back into the
     * SAME per-kind block that {@code field add}/{@code field modify} accept (the {@code number}/{@code
     * string}/{@code enum}/{@code date}/{@code time}/{@code dateRange}/{@code dateFragment}/{@code custom}
     * blocks, or a {@code typedef} ref — see {@code schema field add}). So a config an agent SET round-trips
     * on read: it confirms a constraint persisted through {@code field read} alone, never the raw model JSON
     * (probe 05/07 number, 02 string). Returns a merge-ready object; empty for a bare BOOLEAN/CONFIRM.
     */
    static ObjectNode read(JsonNode fieldType) {
        ObjectNode out = DocumentModelDraft.MAPPER.createObjectNode();
        String type = fieldType.path(DmKeys.TYPE).asText("");
        JsonNode c = fieldType.path(type);   // the config lives under the discriminator key
        switch (type) {
            case "StringType" -> {
                ObjectNode b = DocumentModelDraft.MAPPER.createObjectNode();
                copyText(c, "pattern", b, "pattern");
                copyInt(c, "minLength", b, "minLength");
                copyInt(c, "maxLength", b, "maxLength");
                if (A12Booleans.isSet(c.path("lineBreaksPermitted"))) b.put("lineBreaks", true);
                if (A12Booleans.isSet(c.path("noValueValidation"))) b.put("noValueValidation", true);
                if (A12Booleans.isSet(c.path("alphabeticalSorting"))) b.put("alphabeticalSorting", true);
                localizedMap(c.path(DmKeys.ERROR_MESSAGE)).ifPresent(m -> b.set("patternMessages", m));
                suggestionsMap(c.path("hintList")).ifPresent(m -> b.set("suggestions", m));
                if (!b.isEmpty()) out.set("string", b);
            }
            case "NumberType" -> {
                ObjectNode b = DocumentModelDraft.MAPPER.createObjectNode();
                if (c.hasNonNull("minValue")) b.put("min", c.get("minValue").asText());   // string, as the write schema
                if (c.hasNonNull("maxValue")) b.put("max", c.get("maxValue").asText());
                if (c.hasNonNull("trait")) b.put("unit", c.get("trait").asText().toUpperCase(java.util.Locale.ROOT));
                copyInt(c, "minFractionalDigits", b, "minFractionalDigits");
                copyInt(c, "maxFractionalDigits", b, "maxFractionalDigits");
                copyInt(c, "minLength", b, "minLength");
                copyInt(c, "maxLength", b, "maxLength");
                copyInt(c, "maxIntegerDigits", b, "maxIntegerDigits");
                if (A12Booleans.isSet(c.path("zeroNotAllowed"))) b.put("zeroNotAllowed", true);
                if (A12Booleans.isSet(c.path("leadingZerosAllowed"))) b.put("leadingZerosAllowed", true);
                if (A12Booleans.isSet(c.path("positivesOnly"))) b.put("positivesOnly", true);
                if (!b.isEmpty()) out.set("number", b);
            }
            case "EnumerationType" -> {
                ObjectNode b = DocumentModelDraft.MAPPER.createObjectNode();
                ArrayNode values = b.putArray(DmKeys.VALUES);
                for (JsonNode v : c.path(DmKeys.VALUES)) {
                    ObjectNode valueNode = values.addObject();
                    valueNode.put(DmKeys.VALUE, v.path(DmKeys.VALUE).asText());
                    localizedMap(v.path(DmKeys.LABEL)).ifPresent(m -> valueNode.set(DmKeys.LABELS, m));
                }
                JsonNode categories = c.path("categories");
                if (categories.isArray() && !categories.isEmpty()) {
                    ArrayNode catsOut = b.putArray("categories");
                    for (JsonNode cat : categories) {
                        ObjectNode catOut = catsOut.addObject();
                        catOut.put(DmKeys.NAME, cat.path(DmKeys.NAME).asText());
                        if (cat.hasNonNull(DmKeys.DESCRIPTION)) catOut.put(DmKeys.DESCRIPTION, cat.get(DmKeys.DESCRIPTION).asText());
                        ArrayNode vals = catOut.putArray(DmKeys.VALUES);
                        cat.path(DmKeys.VALUES).forEach(val -> vals.add(val.asText()));
                    }
                }
                if (A12Booleans.isSet(c.path("alphabeticalSorting"))) b.put("alphabeticalSorting", true);
                copyText(c, "sValue", b, "sValue");   // the index-default stored token — must survive a read→modify round-trip
                localizedMap(c.path(DmKeys.ERROR_MESSAGE)).ifPresent(m -> b.set("errorMessages", m));
                out.set("enum", b);
            }
            case "DateType" -> {
                ObjectNode b = DocumentModelDraft.MAPPER.createObjectNode();
                copyText(c, "format", b, "format");
                copyText(c, "datePrecision", b, "precision");
                if (A12Booleans.isSet(c.path("youngerThan1900Check"))) b.put("rejectBefore1900", true);
                if (!b.isEmpty()) out.set("date", b);
            }
            case "TimeType" -> {
                ObjectNode b = DocumentModelDraft.MAPPER.createObjectNode();
                copyText(c, "format", b, "format");
                if (!b.isEmpty()) out.set("time", b);
            }
            case "DateTimeType" -> {
                ObjectNode b = DocumentModelDraft.MAPPER.createObjectNode();
                copyText(c, "format", b, "format");
                if (!b.isEmpty()) out.set("dateTime", b);
            }
            case "DateRangeType" -> {
                ObjectNode b = DocumentModelDraft.MAPPER.createObjectNode();
                copyText(c, "format", b, "format");
                copyText(c, "rangeSeparator", b, "separator");
                // Absent when the declaration omits it, which is how STANDARD is expressed — so the read block
                // stays a legal `field add`/`field modify` input in both cases.
                copyText(c, "interpretationOfYear", b, "interpretationOfYear");
                if (A12Booleans.isSet(c.path("youngerThan1900Check"))) b.put("rejectBefore1900", true);
                if (!b.isEmpty()) out.set("dateRange", b);
            }
            case "DateFragmentType" -> {
                ObjectNode b = DocumentModelDraft.MAPPER.createObjectNode();
                copyText(c, "formatOfFragment", b, "format");
                if (A12Booleans.isSet(c.path("youngerThan1900Check"))) b.put("rejectBefore1900", true);
                if (!b.isEmpty()) out.set("dateFragment", b);
            }
            case "CustomFieldType" -> {
                ObjectNode b = DocumentModelDraft.MAPPER.createObjectNode();
                copyText(c, DmKeys.NAME, b, "typeName");
                copyInt(c, "minLength", b, "minLength");
                copyInt(c, "maxLength", b, "maxLength");
                if (!b.isEmpty()) out.set("custom", b);
            }
            case "TypeDefType" -> out.put("typedef", c.path(DmKeys.TYPE_DEFINITION_ID).asText());
            default -> { /* a bare type (BOOLEAN/CONFIRM) carries no surfaceable config */ }
        }
        return out;
    }

    /** Copy a text slot under a (possibly renamed) key, only when present. */
    private static void copyText(JsonNode from, String fromKey, ObjectNode to, String toKey) {
        if (from.hasNonNull(fromKey)) {
            to.put(toKey, from.get(fromKey).asText());
        }
    }

    /** Copy an integer slot under a (possibly renamed) key, only when present. */
    private static void copyInt(JsonNode from, String fromKey, ObjectNode to, String toKey) {
        if (from.hasNonNull(fromKey)) {
            to.put(toKey, from.get(fromKey).asInt());
        }
    }

    /** A kernel {@code [{locale, text}]} array back into a {@code {locale: text}} map (empty → none). */
    private static Optional<ObjectNode> localizedMap(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode map = DocumentModelDraft.MAPPER.createObjectNode();
        for (JsonNode entry : array) {
            map.put(entry.path(DmKeys.LOCALE).asText(), entry.path(DmKeys.TEXT).asText());
        }
        return Optional.of(map);
    }

    /** A kernel {@code [{locale, values:[…]}]} hint list back into a {@code {locale: [values]}} map. */
    private static Optional<ObjectNode> suggestionsMap(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode map = DocumentModelDraft.MAPPER.createObjectNode();
        for (JsonNode entry : array) {
            ArrayNode values = map.putArray(entry.path(DmKeys.LOCALE).asText());
            entry.path(DmKeys.VALUES).forEach(v -> values.add(v.asText()));
        }
        return Optional.of(map);
    }
}
