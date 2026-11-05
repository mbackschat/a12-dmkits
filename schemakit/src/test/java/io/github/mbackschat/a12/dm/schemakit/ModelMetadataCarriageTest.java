package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * A model's own metadata survives an a12 → JSON Schema → a12 round trip: the id rides the schema's native
 * {@code title}, which import already reads as the model's identity, and the rest rides {@code x-a12-model}
 * verbatim, A12's {@code header} locales, labels and annotations beside {@code content.modelInfo} and
 * {@code content.modelConfig}. Each is reported CARRIED both ways. Losing them changes meaning, not cosmetics: the
 * time zone decides date arithmetic, the base year decides {@code BaseYear}, and a carried rule's German message
 * needs the German locale it was written for. The control is a schema without the carrier, whose import keeps the
 * importer's defaults. Own domain (insurance policies).
 */
class ModelMetadataCarriageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CARRIER = "x-a12-model";

    private static String policy() {
        String drafted = DocumentModelDraft.named("policy").locale("en_US").locale("de_DE")
                .label("en_US", "Policy").label("de_DE", "Police").annotation("owner", "underwriting").baseYear(2024)
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> { })
                        .stringField("Holder", f -> { }))
                .build().toJson();
        EditSession edit = EditSession.read(drafted);
        edit.config().timeZone("America/New_York").decimalSeparator(",");
        var policy = edit.group("/Policy");
        policy.addRule(r -> r.name("HolderRequired").code("HOLDER").errorField(policy.field("Holder"))
                .when(policy.str("Holder").notFilled()).message(Dsl.en("A holder is required."),
                        Dsl.de("Ein Versicherungsnehmer ist erforderlich.")).build());
        return edit.toJson();
    }

    @Test
    void theModelsIdentityLocalesInfoAndConfigSurviveARoundTrip() throws Exception {
        String source = policy();
        assertKernelAccepts(source);

        var exported = new JsonSchemaExporter().export(source);
        JsonNode schema = MAPPER.readTree(exported.jsonSchema());
        assertEquals("policy", schema.path("title").asText());
        assertEquals(List.of("header/locales", "header/labels", "header/annotations", "content/modelInfo",
                "content/modelConfig"), carried(exported.report()));

        var imported = new JsonSchemaImporter().importSchema(exported.jsonSchema());
        assertKernelAccepts(imported.dmJson());
        JsonNode before = MAPPER.readTree(source);
        JsonNode after = MAPPER.readTree(imported.dmJson());
        for (String member : List.of("id", "locales", "labels", "annotations")) {
            assertEquals(before.path("header").path(member), after.path("header").path(member), "header." + member);
        }
        for (String member : List.of("modelInfo", "modelConfig")) {
            assertEquals(before.path("content").path(member), after.path("content").path(member), "content." + member);
        }
        assertEquals(List.of("header/locales", "header/labels", "header/annotations", "content/modelInfo",
                "content/modelConfig"), carried(imported.report()));
        assertFalse(imported.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED),
                imported.report().toString());
    }

    /** The control: a foreign schema carries no metadata, so import keeps its own defaults and reports nothing. */
    @Test
    void aSchemaWithoutTheCarrierKeepsTheImportersDefaults() throws Exception {
        var imported = new JsonSchemaImporter().importSchema("""
                {"type":"object","properties":{"PolicyNo":{"type":"string"}}}
                """);

        JsonNode tree = MAPPER.readTree(imported.dmJson());
        assertEquals("[{\"code\":\"en_US\"}]", tree.path("header").path("locales").toString());
        assertEquals(List.of(), carried(imported.report()));
    }

    /** Import never blocks: a carrier member of the wrong shape is omitted with a corrective, the rest restored. */
    @Test
    void aMalformedCarrierMemberIsOmittedAndTheRestIsRestored() throws Exception {
        var imported = new JsonSchemaImporter().importSchema("""
                {"type":"object","x-a12-root-group-name":"Policy","properties":{"PolicyNo":{"type":"string"}},
                 "x-a12-model":{"modelInfo":"policy","modelConfig":{"decimalSeparator":".","timeZone":"Europe/Vienna",
                 "conditionLanguage":{"code":"en_US"},"fieldRefByShortNameAllowed":true}}}
                """);

        assertKernelAccepts(imported.dmJson());
        assertEquals("Europe/Vienna",
                MAPPER.readTree(imported.dmJson()).path("content").path("modelConfig").path("timeZone").asText());
        assertTrue(imported.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED
                && d.source().equals("/" + CARRIER + "/modelInfo")), imported.report().toString());
    }

    private static List<String> carried(TranscodeReport report) {
        return report.dispositions().stream()
                .filter(d -> d.tier() == TranscodeReport.Tier.CARRIED
                        && (d.source().startsWith("header/") || d.source().startsWith("content/")))
                .map(TranscodeReport.Disposition::source).toList();
    }
}
