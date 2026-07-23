package io.github.mbackschat.a12.dm.modelkit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.core.DmKeys;

/** Exhaustive inverse of {@link DocumentModelDraft#writeFieldType} for authored-state consumers. */
final class FieldTypeSpecReader {

    private FieldTypeSpecReader() {}

    static FieldTypeSpec read(JsonNode fieldType) {
        String discriminator = fieldType.path(DmKeys.TYPE).asText();
        JsonNode config = fieldType.path(discriminator);
        return switch (discriminator) {
            case "StringType" -> string(config);
            case "NumberType" -> number(config);
            case "DateType" -> new FieldTypeSpec.DateSpec(text(config, "format"), text(config, "datePrecision"));
            case "TimeType" -> new FieldTypeSpec.TimeSpec(text(config, "format"));
            case "DateTimeType" -> new FieldTypeSpec.DateTimeSpec(text(config, "format"));
            case "DateRangeType" -> new FieldTypeSpec.DateRangeSpec(
                    text(config, "format"), text(config, "rangeSeparator"));
            case "DateFragmentType" -> new FieldTypeSpec.DateFragmentSpec(text(config, "formatOfFragment"));
            case "TypeDefType" -> new FieldTypeSpec.TypeDefRef(config.path(DmKeys.TYPE_DEFINITION_ID).asText());
            case "BooleanType" -> new FieldTypeSpec.BooleanSpec();
            case "ConfirmType" -> new FieldTypeSpec.ConfirmSpec();
            case "EnumerationType" -> enumeration(config);
            case "CustomFieldType" -> new FieldTypeSpec.CustomSpec(
                    config.path(DmKeys.NAME).asText(), integer(config, "minLength"), integer(config, "maxLength"));
            default -> throw new IllegalArgumentException("unsupported field type: " + discriminator);
        };
    }

    private static FieldTypeSpec.StringSpec string(JsonNode config) {
        Map<String, String> messages = localized(config.path(DmKeys.ERROR_MESSAGE));
        String pattern = text(config, "pattern");
        String fallbackMessage = pattern == null || messages.isEmpty() ? null : messages.values().iterator().next();
        return new FieldTypeSpec.StringSpec(integer(config, "minLength"), integer(config, "maxLength"),
                pattern, fallbackMessage, config.path("lineBreaksPermitted").asBoolean(false),
                config.path("alphabeticalSorting").asBoolean(false), suggestions(config.path("hintList")),
                config.path("noValueValidation").asBoolean(false), messages);
    }

    private static FieldTypeSpec.NumberSpec number(JsonNode config) {
        return new FieldTypeSpec.NumberSpec(integer(config, "minFractionalDigits"),
                integer(config, "maxFractionalDigits"), decimal(config, "minValue"), decimal(config, "maxValue"),
                config.path("zeroNotAllowed").asBoolean(false),
                config.path("leadingZerosAllowed").asBoolean(false), numberTrait(text(config, "trait")));
    }

    private static FieldTypeSpec.EnumSpec enumeration(JsonNode config) {
        List<FieldTypeSpec.EnumValue> values = new ArrayList<>();
        for (JsonNode value : config.path(DmKeys.VALUES)) {
            values.add(new FieldTypeSpec.EnumValue(value.path(DmKeys.VALUE).asText(),
                    localized(value.path(DmKeys.LABEL))));
        }
        List<FieldTypeSpec.EnumCategory> categories = new ArrayList<>();
        for (JsonNode category : config.path("categories")) {
            List<String> classifications = new ArrayList<>();
            category.path(DmKeys.VALUES).forEach(value -> classifications.add(value.asText()));
            categories.add(new FieldTypeSpec.EnumCategory(category.path(DmKeys.NAME).asText(), classifications,
                    text(category, DmKeys.DESCRIPTION)));
        }
        Map<String, String> messages = localized(config.path(DmKeys.ERROR_MESSAGE));
        String fallbackMessage = messages.isEmpty() ? null : messages.values().iterator().next();
        return new FieldTypeSpec.EnumSpec(values, categories,
                config.path("alphabeticalSorting").asBoolean(false), fallbackMessage, messages,
                text(config, "sValue"));
    }

    private static Map<String, String> localized(JsonNode entries) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            values.put(entry.path(DmKeys.LOCALE).asText(), entry.path(DmKeys.TEXT).asText());
        }
        return values;
    }

    private static Map<String, List<String>> suggestions(JsonNode entries) {
        Map<String, List<String>> suggestions = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            List<String> values = new ArrayList<>();
            entry.path(DmKeys.VALUES).forEach(value -> values.add(value.asText()));
            suggestions.put(entry.path(DmKeys.LOCALE).asText(), values);
        }
        return suggestions;
    }

    private static String text(JsonNode node, String key) {
        return node.hasNonNull(key) ? node.get(key).asText() : null;
    }

    private static Integer integer(JsonNode node, String key) {
        return node.hasNonNull(key) ? node.get(key).asInt() : null;
    }

    private static BigDecimal decimal(JsonNode node, String key) {
        return node.hasNonNull(key) ? new BigDecimal(node.get(key).asText()) : null;
    }

    private static NumberTrait numberTrait(String json) {
        if (json == null) {
            return null;
        }
        for (NumberTrait trait : NumberTrait.values()) {
            if (trait.json().equals(json)) {
                return trait;
            }
        }
        throw new IllegalArgumentException("unsupported number trait: " + json);
    }
}
