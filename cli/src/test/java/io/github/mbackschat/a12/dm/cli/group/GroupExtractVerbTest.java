package io.github.mbackschat.a12.dm.cli.group;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Tier-3 refactor verb {@code group extract <path> --reference <id>} (CLI-SPEC §6): lift a subtree into a
 * sub-model (written beside the base, resolved by id) and mount it at the same name. The gate refuses a
 * repeatable group ({@code RK_REPEATABLE_INCLUDE_ROOT}) and a subtree whose rule reaches outside
 * ({@code RK_OUTWARD_REFERENCE}); the composed pair is kernel-revalidated before any file is written.
 */
class GroupExtractVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path write(Path dir, String name, String json) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, json);
        return f;
    }

    @Test
    void extractsAGroupIntoAMountedSubModelThatRevalidates(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("OrderDate", FieldKind.DATE)
                        .group("Shipping", sh -> sh.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING)))
                .build()
                .toJson();
        String withRules = EditSession.read(base)
                .addRule(m -> m.rule(m.group("/Order/Shipping")).name("CityNeedsZip")
                        .errorField(m.str("/Order/Shipping/Zip"))
                        .when(m.str("/Order/Shipping/City").filled().and(m.str("/Order/Shipping/Zip").notFilled()))
                        .code("CITY_ZIP").severity(Severity.ERROR).message(en("A zip is required with a city.")).build())
                .addRule(m -> m.rule(m.group("/Order")).name("ZipFormat")
                        .errorField(m.str("/Order/Shipping/Zip"))
                        .when(m.str("/Order/Shipping/Zip").filled().and(m.str("/Order/Shipping/Zip").violates("[0-9]{5}")))
                        .code("ZIP_FMT").severity(Severity.ERROR).message(en("The zip must be five digits.")).build())
                .toJson();
        Path model = write(dir, "order.dm.json", withRules);

        var res = CliTestSupport.run("-m", model.toString(), "group", "extract", "/Order/Shipping", "--reference", "shipping");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("shipping", j.path("changed").path("reference").asText());

        assertTrue(Files.exists(dir.resolve("shipping.dm.json")), "the sub-model file was written beside the base");
        // the extracted sub-model is named for its reference — not left carrying the parent's name (probe P4)
        JsonNode subModel = M.readTree(Files.readString(dir.resolve("shipping.dm.json")));
        assertEquals("shipping", subModel.path("header").path("id").asText(), "sub-model header.id");
        assertEquals("shipping", subModel.path("content").path("modelInfo").path("name").asText(),
                "sub-model modelInfo.name is the reference, not the parent's ('order')");
        String baseAfter = Files.readString(model);
        assertTrue(baseAfter.contains("\"modelAlias\""), "the base now mounts the sub-model");
        assertFalse(baseAfter.contains("\"name\" : \"City\""), "Shipping's fields left the base");
        // the composed pair re-validates (the sub-model resolves by id from the same dir)
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate").code(), "composed model is valid");
    }

    @Test
    void extractedSubModelIsValidStandalone_carriedRuleAbsolutePathsAreReBased(@TempDir Path dir) throws Exception {
        // probe 01-de Finding 6: a carried rule's ABSOLUTE operands (/Order/Shipping/*) were copied verbatim
        // into the sub-model (root /Shipping) → invalid STANDALONE (MVK_INVALID_ROOT_GROUP), though the
        // composed pair validated (masking it). The operands must re-base to the sub-model root on extract.
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("OrderDate", FieldKind.DATE)
                        .group("Shipping", sh -> sh.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING)))
                .build().toJson();
        String withRule = EditSession.read(base)
                .addRule(m -> m.rule(m.group("/Order/Shipping")).name("CityNeedsZip")
                        .errorField(m.str("/Order/Shipping/Zip"))
                        .when(m.str("/Order/Shipping/City").filled().and(m.str("/Order/Shipping/Zip").notFilled()))
                        .code("CITY_ZIP").severity(Severity.ERROR).message(en("A zip is required with a city.")).build())
                .toJson();
        Path model = write(dir, "order.dm.json", withRule);

        var res = CliTestSupport.run("-m", model.toString(), "group", "extract", "/Order/Shipping", "--reference", "shipping");
        assertEquals(0, res.code(), res.err());

        // THE GUARD: the produced sub-model is valid ON ITS OWN, not only inside the composed pair
        assertEquals(0, CliTestSupport.run("-m", dir.resolve("shipping.dm.json").toString(), "model", "validate").code(),
                "the extracted sub-model must be valid standalone (carried rule operands re-based to its root)");
        // and the carried rule's operands were rewritten from /Order/Shipping/* to /Shipping/*
        String sub = Files.readString(dir.resolve("shipping.dm.json"));
        assertTrue(sub.contains("/Shipping/Zip"), () -> "operands re-based to the sub-model root: " + sub);
        assertFalse(sub.contains("/Order/Shipping"), () -> "no stale /Order/Shipping operand survives: " + sub);
    }

    @Test
    void refusesExtractingARepeatableGroup(@TempDir Path dir) throws Exception {
        String json = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Ref", FieldKind.STRING)
                        .group("Lines", l -> l.repeatable(99).field("Sku", FieldKind.STRING)))
                .build()
                .toJson();
        Path model = write(dir, "order.dm.json", json);

        var res = CliTestSupport.run("-m", model.toString(), "group", "extract", "/Order/Lines", "--reference", "lines");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_REPEATABLE_INCLUDE_ROOT"));
        assertFalse(Files.exists(dir.resolve("lines.dm.json")), "nothing written");
    }

    @Test
    void refusesASubtreeWithAnOutwardReference(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("OrderDate", FieldKind.DATE)
                        .group("Shipping", sh -> sh.field("Zip", FieldKind.STRING)))
                .build()
                .toJson();
        // an internal rule that reaches OUT of Shipping (references /Order/OrderDate) — it can't move out
        String withRule = EditSession.read(base)
                .addRule(m -> m.rule(m.group("/Order/Shipping")).name("NeedsDate")
                        .errorField(m.str("/Order/Shipping/Zip"))
                        .when(m.date("/Order/OrderDate").notFilled().and(m.str("/Order/Shipping/Zip").filled()))
                        .code("NEEDS_DATE").severity(Severity.ERROR).message(en("An order date is required.")).build())
                .toJson();
        Path model = write(dir, "order.dm.json", withRule);

        var res = CliTestSupport.run("-m", model.toString(), "group", "extract", "/Order/Shipping", "--reference", "shipping");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_OUTWARD_REFERENCE"));
        assertTrue(j.path("data").path("escapingRules").toString().contains("/Order/Shipping/NeedsDate"));
    }
}
