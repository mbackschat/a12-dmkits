package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OrderedDocumentMapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> LOCALES = List.of(
            "pl_PL", "en_US", "nl_NL", "de_DE", "pt_PT", "fr_FR", "es_ES", "it_IT");

    @Test
    void immutableDocumentMetadataRetainsAuthoredLocaleOrder() {
        Map<String, String> localized = localized();

        FieldMeta field = FieldMeta.builder()
                .labels(localized)
                .internalDescription(localized)
                .externalDescription(localized)
                .helperText(localized)
                .build();
        assertOrder(field.labels());
        assertOrder(field.internalDescription());
        assertOrder(field.externalDescription());
        assertOrder(field.helperText());

        GroupMeta group = GroupMeta.builder()
                .labels(localized)
                .internalDescription(localized)
                .externalDescription(localized)
                .build();
        assertOrder(group.labels());
        assertOrder(group.internalDescription());
        assertOrder(group.externalDescription());

        assertOrder(new ElementDraft.Rule("R", ".", "E", "true", "ERROR", localized).messages());
        assertOrder(new ModelDocument.FieldRequirements(Requiredness.OPTIONAL, localized).messages());
        ModelDocument.DescriptiveState described =
                new ModelDocument.DescriptiveState(localized, localized, List.of());
        assertOrder(described.internalDescription());
        assertOrder(described.externalDescription());

        FieldTypeSpec.EnumValue value = new FieldTypeSpec.EnumValue("A", localized);
        assertOrder(value.labels());
        FieldTypeSpec.EnumSpec enumeration =
                new FieldTypeSpec.EnumSpec(List.of(value), List.of(), false, null, localized);
        assertOrder(enumeration.invalidValueMessages());
    }

    @Test
    void authoredModelAndTypeDefinitionArraysRetainLocaleOrder() throws Exception {
        DocumentModelDraft.Builder builder = DocumentModelDraft.typeDefModel("ordered");
        LOCALES.forEach(locale -> builder.locale(locale).label(locale, "label-" + locale));
        builder.typeDef("Amount", type -> {
            type.number(number -> { });
            LOCALES.forEach(locale -> type
                    .internalDescription(locale, "internal-" + locale)
                    .externalDescription(locale, "external-" + locale));
        });

        JsonNode model = MAPPER.readTree(builder.build().toJson());
        assertLocaleArray(model.path("header").path("labels"));
        JsonNode typeDefinition = model.path("content").path("typeDefinitions").get(0);
        assertLocaleArray(typeDefinition.path("internalDescription"));
        assertLocaleArray(typeDefinition.path("externalDescription"));
    }

    @Test
    void orderedCopiesRetainMapCopyOfNullRejection() {
        Map<String, String> nullKey = new LinkedHashMap<>();
        nullKey.put(null, "text");
        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("en_US", null);

        assertThrows(NullPointerException.class, () -> OrderedMap.copyOf(nullKey));
        assertThrows(NullPointerException.class, () -> OrderedMap.copyOf(nullValue));
    }

    private static Map<String, String> localized() {
        Map<String, String> result = new LinkedHashMap<>();
        LOCALES.forEach(locale -> result.put(locale, "text-" + locale));
        return result;
    }

    private static void assertOrder(Map<String, String> actual) {
        assertEquals(LOCALES, List.copyOf(actual.keySet()));
    }

    private static void assertLocaleArray(JsonNode array) {
        assertEquals(LOCALES, java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(value -> value.path("locale").asText())
                .toList());
    }
}
