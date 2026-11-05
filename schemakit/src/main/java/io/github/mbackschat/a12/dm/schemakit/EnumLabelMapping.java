package io.github.mbackschat.a12.dm.schemakit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.modelkit.EnumFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;

/**
 * An enumeration's labels across the bridge. JSON Schema has no label keyword, so export carries an a12 field's
 * labels in {@link Carriage#X_ENUM_LABELS}, value by value in A12's own {@code [{locale, text}]} shape, and import
 * restores exactly that. A foreign schema has no carriage, and {@link MappingProfile.EnumLabels} decides.
 *
 * <p>Export leaves the carriage out when the labels are what the default import builds anyway, each value labelled
 * with itself in the import locale, so a foreign enum's import → export gains nothing (the {@link ImportSkeleton}
 * principle). An unlabelled a12 enum therefore carries an empty object: without it the default import would derive
 * labels the model never had.
 */
final class EnumLabelMapping {

    private EnumLabelMapping() {}

    static void export(FieldTypeSpec.EnumSpec spec, ObjectNode property, String fieldPath, TranscodeReport.Builder report) {
        if (spec.values().stream().allMatch(v -> v.labels().equals(Map.of(ImportSkeleton.LOCALE, v.value())))) {
            return;
        }
        ObjectNode carried = property.putObject(Carriage.X_ENUM_LABELS);
        for (FieldTypeSpec.EnumValue value : spec.values()) {
            if (!value.labels().isEmpty()) {
                ArrayNode entries = carried.putArray(value.value());
                value.labels().forEach((locale, text) -> entries.addObject().put(DmKeys.LOCALE, locale).put(DmKeys.TEXT, text));
            }
        }
        if (!carried.isEmpty()) {
            report.mapped(fieldPath, Carriage.X_ENUM_LABELS, TranscodeReport.Tier.CARRIED);
        }
    }

    /**
     * Add {@code values} to {@code e}, each with its labels: the carriage on {@code schema} when there is one, else
     * the strategy's. {@code schema} is where the values were read, so {@code x-enumNames} sits beside them.
     */
    static void addValues(EnumFieldBuilder e, List<String> values, JsonNode schema, String name,
                          MappingProfile.EnumLabels strategy, TranscodeReport.Builder report) {
        Map<String, Map<String, String>> labels = labels(values, schema, name, strategy, report);
        values.forEach(value -> e.value(value, l -> labels.getOrDefault(value, Map.of()).forEach(l::label)));
    }

    private static Map<String, Map<String, String>> labels(List<String> values, JsonNode schema, String name,
            MappingProfile.EnumLabels strategy, TranscodeReport.Builder report) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        JsonNode carried = schema.path(Carriage.X_ENUM_LABELS);
        if (carried.isObject()) {
            values.forEach(value -> {
                Map<String, String> byLocale = new LinkedHashMap<>();
                carried.path(value).forEach(entry -> byLocale.put(entry.path(DmKeys.LOCALE).asText(), entry.path(DmKeys.TEXT).asText()));
                out.put(value, byLocale);
            });
            return out;
        }
        List<String> texts = switch (strategy) {
            case DERIVE -> values;
            case X_ENUM_NAMES -> {
                JsonNode names = schema.path("x-enumNames");
                if (!names.isArray() || names.size() != values.size()) {
                    report.diagnostic("enum '" + name + "' has no x-enumNames array of " + values.size()
                            + " names beside its values — imported unlabelled (--enum-labels X_ENUM_NAMES); "
                            + "label the values by hand or choose DERIVE");
                    yield List.of();
                }
                List<String> read = new ArrayList<>();
                names.forEach(n -> read.add(n.asText()));
                yield read;
            }
            case BLANK -> List.of();
        };
        if (texts.isEmpty()) {
            return out;
        }
        String refusal = refusal(values, texts);
        if (refusal != null) {
            report.diagnostic("enum '" + name + "' cannot be labelled by --enum-labels " + strategy + ": " + refusal
                    + " — imported unlabelled; label the values by hand");
            return out;
        }
        for (int i = 0; i < values.size(); i++) {
            out.put(values.get(i), Map.of(ImportSkeleton.LOCALE, texts.get(i)));
        }
        return out;
    }

    /**
     * Why A12 would refuse these labels, or {@code null}. A labelled enumeration admits no empty token, no blank
     * text and no text twice in one language (KERNEL-FINDINGS KF321); an empty token labelled with itself is not
     * even buildable. Labelled or not is all-or-nothing, so one refused value leaves the whole field unlabelled.
     */
    private static String refusal(List<String> values, List<String> texts) {
        if (values.contains("")) {
            return "a labelled enumeration admits no empty value";
        }
        if (texts.stream().anyMatch(String::isBlank)) {
            return "a label text is blank";
        }
        if (texts.stream().distinct().count() < texts.size()) {
            return "two values would share one label text";
        }
        return null;
    }
}
