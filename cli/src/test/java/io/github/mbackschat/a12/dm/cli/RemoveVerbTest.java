package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The {@code remove} verb — delete a field/group/rule/computation, in place (the {@code modify}
 * convention: {@code -o} redirects, {@code --dry-run} previews). The safe-remove gate: removing a
 * field/group a rule still references is refused unless {@code --cascade} (which drops the referencing
 * rules too, via the adapter's whereUsed). Fixture: {@code order-ruled} (its `DeliveryNotBeforeOrder`
 * rule references `/Order/DeliveryDate`; `/Order/Quantity` is referenced by nothing).
 */
class RemoveVerbTest {

    @Test
    void removesAnUnreferencedField(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("remove", model.toString(), "--field", "/Order/Quantity");
        assertEquals(0, res.code(), res.err());
        assertFalse(Files.readString(model).contains("\"/Order/Quantity\""));
    }

    @Test
    void refusesToRemoveAReferencedFieldWithoutCascade(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("remove", model.toString(), "--field", "/Order/DeliveryDate");
        assertEquals(2, res.code());
        // the problem is surfaced STRUCTURED (agent-parseable), not buried in a flat message
        JsonNode refusal = new ObjectMapper().readTree(res.err());
        assertEquals("/Order/DeliveryDate", refusal.path("blocked").asText());
        JsonNode rules = refusal.path("referencedBy").path("rules");
        assertTrue(rules.isArray() && rules.toString().contains("/Order/DeliveryNotBeforeOrder"),
                "must list the referencing rule under referencedBy.rules: " + res.err());
        assertTrue(refusal.path("hint").asText().contains("--cascade"), "must hint at --cascade");
        assertTrue(Files.readString(model).contains("/Order/DeliveryDate"), "file must be untouched");
    }

    @Test
    void cascadeRemovesTheFieldAndItsReferencingRules(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("remove", model.toString(), "--field", "/Order/DeliveryDate", "--cascade");
        assertEquals(0, res.code(), res.err());
        String written = Files.readString(model);
        assertFalse(written.contains("DeliveryNotBeforeOrder"), "the referencing rule should cascade away");
        assertEquals(0, CliTestSupport.run("validate", model.toString()).code(), "result must stay kernel-valid");
    }

    @Test
    void removesARuleDirectly(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("remove", model.toString(), "--rule", "/Order/EligibilityCheck");
        assertEquals(0, res.code(), res.err());
        assertFalse(Files.readString(model).contains("EligibilityCheck"));
    }

    @Test
    void dryRunPreviewsWithoutWriting(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("remove", model.toString(), "--rule", "/Order/EligibilityCheck", "--dry-run");
        assertEquals(0, res.code(), res.err());
        assertTrue(Files.readString(model).contains("EligibilityCheck"), "--dry-run must write nothing");
    }

    @Test
    void unknownPathFailsFast(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("remove", model.toString(), "--field", "/Order/DoesNotExist");
        assertEquals(2, res.code());
    }

    @Test
    void removesATypeDefinition(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("add-typedef", model.toString(), "--id", "Money", "--kind", "NUMBER").code());
        var res = CliTestSupport.run("remove", model.toString(), "--typedef", "Money");
        assertEquals(0, res.code(), res.err());
        assertFalse(Files.readString(model).contains("\"Money\""));
    }

    @Test
    void removesAnInclude(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Files.writeString(dir.resolve("catalog.dm.json"),
                DocumentModelDraft.named("catalog").locale("en_US")
                        .rootGroup("Catalog", g -> g.field("Sku", FieldKind.STRING)).build().toJson());
        assertEquals(0, CliTestSupport.run("add-include", model.toString(), "--alias", "catalog",
                "--reference", "catalog", "--parent", "/Order", "--name", "Catalog",
                "--include-dir", dir.toString()).code());

        var res = CliTestSupport.run("remove", model.toString(), "--include", "catalog");
        assertEquals(0, res.code(), res.err());
        assertFalse(Files.readString(model).contains("modelAlias"));
    }
}
