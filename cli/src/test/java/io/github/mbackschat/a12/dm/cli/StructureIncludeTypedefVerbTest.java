package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The include/typedef structure-edit verbs — {@code add-typedef}, {@code add-field --typedef}, and
 * {@code add-include} (declare a header model-reference + mount it). They follow the {@code modify}
 * convention (in place; {@code -o}/{@code --dry-run}); {@code add-include} validates the mounted model
 * against {@code -w/--workspace} (the F-RES resolver).
 */
class StructureIncludeTypedefVerbTest {

    private static Path subscription(Path dir) throws Exception {
        Path f = dir.resolve("subscription.dm.json");
        Files.writeString(f, CliTestSupport.subscriptionJson());
        return f;
    }

    @Test
    void addsATypeDefinitionAndAFieldUsingIt(@TempDir Path dir) throws Exception {
        Path model = subscription(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "typedef", "add", "--id", "Money", "--kind", "NUMBER").code());
        var res = CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Subscription", "--name", "Credit", "--typedef", "Money");
        assertEquals(0, res.code(), res.err());

        String written = Files.readString(model);
        assertTrue(written.contains("\"Money\""));
        assertTrue(written.contains("TypeDefType"));
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate").code());
    }

    @Test
    void addFieldRequiresExactlyOneOfKindOrTypedef(@TempDir Path dir) throws Exception {
        Path model = subscription(dir);
        // neither
        assertEquals(2, CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Subscription", "--name", "X").code());
        // both
        assertEquals(2, CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Subscription", "--name", "X", "--kind", "STRING", "--typedef", "Money").code());
    }

    @Test
    void addsAnIncludeAndMountsItValidatedViaIncludeDir(@TempDir Path dir) throws Exception {
        Path model = subscription(dir);
        // a referenced model, authored with modelkit, sitting in the same -w/--workspace (resolved by header id)
        Files.writeString(dir.resolve("catalog.dm.json"),
                DocumentModelDraft.named("catalog").locale("en_US").locale("de_DE")
                        .rootGroup("Catalog", g -> g.field("Sku", FieldKind.STRING))
                        .build().toJson());

        var res = CliTestSupport.run("-m", model.toString(), "include", "add",
                "--alias", "catalog", "--reference", "catalog",
                "--parent", "/Subscription", "--name", "Catalog",
                "-w", dir.toString());
        assertEquals(0, res.code(), res.err());

        String written = Files.readString(model);
        assertTrue(written.contains("\"modelAlias\""));
        assertTrue(written.contains("\"reference\""));
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate", "-w", dir.toString()).code());
    }
}
