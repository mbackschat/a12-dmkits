package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The {@code schema} verb (CLI-SPEC §4) — on-demand JSON Schema of a verb's output, the CLI's
 * self-describing surface. The drift guard is the point (F11 "loud detection at build"): each schema
 * must declare every top-level key the corresponding verb actually emits, so a new output field can't
 * silently slip past the published contract.
 */
class SchemaVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listsTheAvailableSchemas() {
        CliTestSupport.Result res = CliTestSupport.run("schema");
        assertEquals(0, res.code(), res::err);
        for (String name : SchemaCommand.NAMES) {
            assertTrue(res.out().contains(name), () -> "lists " + name + ": " + res.out());
        }
    }

    @Test
    void unknownSchemaFails() {
        CliTestSupport.Result res = CliTestSupport.run("schema", "nope");
        assertEquals(2, res.code());
        assertTrue(res.err().contains("unknown schema"), res::err);
    }

    @Test
    void everyEmittedTopLevelKeyIsDeclaredInItsSchema(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);

        assertSchemaCovers("describe", CliTestSupport.run("describe", model.toString()));
        assertSchemaCovers("validate", CliTestSupport.run("validate", model.toString()));
        assertSchemaCovers("read",
                CliTestSupport.run("read", model.toString(), "--rule", CliTestSupport.RULE_FULL_NAME));
        assertSchemaCovers("explain",
                CliTestSupport.run("explain", model.toString(), "--rule", CliTestSupport.RULE_FULL_NAME));

        // the computation-aware modes (2026-06-08) — same drift guard over the new output shapes
        Path withComp = CliTestSupport.modelWithRuleAndComputation(dir);
        assertSchemaCovers("read", CliTestSupport.run("read", withComp.toString(), "--all"));
        assertSchemaCovers("explain",
                CliTestSupport.run("explain", withComp.toString(), "--computation", CliTestSupport.COMPUTATION_FULL_NAME));

        // the runtime verbs (2026-06-09) — eval-doc (with --rule so the `rule` key is exercised) + compute-doc
        Path evalDoc = dir.resolve("eval.json");
        Files.writeString(evalDoc, """
                { "fields": { "Subscription": { "Addons": [ { "MonthlyFee": "2000" } ] } } }""");
        assertSchemaCovers("eval-doc", CliTestSupport.run("eval-doc", model.toString(), "--doc", evalDoc.toString(),
                "--rule", CliTestSupport.RULE_FULL_NAME));
        Path computeDoc = dir.resolve("compute.json");
        Files.writeString(computeDoc, """
                { "fields": { "Subscription": { "Billing": { "BaseFee": "50" } } } }""");
        assertSchemaCovers("compute-doc", CliTestSupport.run("compute-doc", withComp.toString(), "--doc", computeDoc.toString()));

        // the structure-edit verbs (2026-06-10) — --dry-run so they emit their summary without writing
        assertSchemaCovers("remove",
                CliTestSupport.run("remove", model.toString(), "--rule", CliTestSupport.RULE_FULL_NAME, "--dry-run"));
        assertSchemaCovers("add-field", CliTestSupport.run("add-field", model.toString(),
                "--group", "/Subscription", "--name", "Discount", "--kind", "NUMBER", "--dry-run"));
        assertSchemaCovers("add-group", CliTestSupport.run("add-group", model.toString(),
                "--parent", "/Subscription", "--name", "Notes", "--dry-run"));
        assertSchemaCovers("add-typedef", CliTestSupport.run("add-typedef", model.toString(),
                "--id", "Money", "--kind", "NUMBER", "--dry-run"));
        // add-include validates the mounted model, so a referenced model must sit in --include-dir
        Files.writeString(dir.resolve("catalog.dm.json"),
                DocumentModelDraft.named("catalog").locale("en_US").locale("de_DE")
                        .rootGroup("Catalog", g -> g.field("Sku", FieldKind.STRING)).build().toJson());
        assertSchemaCovers("add-include", CliTestSupport.run("add-include", model.toString(),
                "--alias", "catalog", "--reference", "catalog", "--parent", "/Subscription", "--name", "Catalog",
                "--include-dir", dir.toString(), "--dry-run"));
    }

    /** Every top-level property of the verb's real output must be declared in the schema's `properties`. */
    private void assertSchemaCovers(String name, CliTestSupport.Result verb) throws Exception {
        assertEquals(0, verb.code(), () -> name + " verb failed: " + verb.err());
        JsonNode output = MAPPER.readTree(verb.out());

        CliTestSupport.Result schema = CliTestSupport.run("schema", name);
        assertEquals(0, schema.code(), schema::err);
        JsonNode declared = MAPPER.readTree(schema.out()).path("properties");
        assertTrue(declared.isObject() && declared.size() > 0, () -> name + " schema has no properties");

        for (Iterator<String> it = output.fieldNames(); it.hasNext();) {
            String key = it.next();
            assertTrue(declared.has(key),
                    () -> name + " emits key '" + key + "' not declared in " + name + ".schema.json");
        }
    }
}
