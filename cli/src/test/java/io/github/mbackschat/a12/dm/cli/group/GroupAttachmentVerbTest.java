package io.github.mbackschat.a12.dm.cli.group;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code group attachment} — expand an Attachment group template (FEATURE-GAPS): the canonical
 * 8-field + 4-rule file-attachment composition, kernel-validated by {@code AttachmentGroupRule}. Includes
 * the removal path: removing a composite group takes its internal rules with it, in one op, without forcing
 * {@code --cascade} for rules the author never sees.
 */
class GroupAttachmentVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void addsAnAttachmentGroup(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);   // en_US fixture
        var res = CliTestSupport.run("-m", model.toString(), "group", "attachment",
                "--parent", "/Order", "--name", "Invoice");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("/Order/Invoice", j.path("changed").path("added").asText());
        String written = Files.readString(model);
        assertTrue(written.contains("\"usageType\" : \"attachment\""), "the attachment marker is written");
        assertTrue(written.contains("\"internal_filename\"") && written.contains("\"content\""),
                "the canonical fields are present");
    }

    @Test
    void addsAListOfAttachmentsWithKnobs(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "attachment",
                "--parent", "/Order", "--name", "Invoices", "--repeatable", "5",
                "--max-size-bytes", "1000000", "--allowed-mime", "application/pdf,image/png");
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertTrue(Files.readString(model).contains("\"usageType\" : \"attachment\""));
    }

    @Test
    void anAttachmentOnANonEnDeModelIsRefusedWithACode(@TempDir Path dir) throws Exception {
        // the template generates en/de rules — a model declaring another locale can't be served (coded refusal)
        Path model = dir.resolve("fr.dm.json");
        Files.writeString(model, DocumentModelDraft.named("catalogue").locale("fr_FR")
                .rootGroup("Commande", g -> g.field("Reference", FieldKind.STRING)).build().toJson());
        var res = CliTestSupport.run("-m", model.toString(), "group", "attachment",
                "--parent", "/Commande", "--name", "Facture");
        assertEquals(2, res.code(), () -> "fr_FR model refuses the attachment template: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText(), () -> "a coded refusal, not a bare fail: " + res.out());
        assertEquals("RK_ATTACHMENT_LOCALE_UNSUPPORTED", j.path("diagnostics").path(0).path("code").asText());
    }

    @Test
    void removingAnAttachmentGroupTakesItsInternalRulesWithIt(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "group", "attachment",
                "--parent", "/Order", "--name", "Invoice").code());
        // the author never saw the 4 internal rules — `group remove` must take them with the subtree,
        // not refuse demanding --cascade for rules they can't name
        var res = CliTestSupport.run("-m", model.toString(), "group", "remove", "/Order/Invoice");
        assertEquals(0, res.code(), () -> "removing a composite group should just work: " + res.err() + res.out());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertTrue(!Files.readString(model).contains("\"usageType\" : \"attachment\""),
                "the attachment group (and its internal rules) is gone");
    }
}
