package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * DM 29.4 removed the include alias (KERNEL-FINDINGS KF651), so the DM 28 vocabulary fails fast with the
 * reference-only replacement on every surface it could still arrive through (owner decision D3): the
 * {@code --alias} flag, the {@code include rename} verb, and the apply op's {@code alias} arg and
 * {@code include rename} op. Each row also proves that nothing was written, because a corrective that still
 * mutated would teach the caller the wrong form works.
 */
class IncludeAliasRetirementTest {

    private static Path modelWithCatalog(Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Files.writeString(dir.resolve("catalog.dm.json"),
                DocumentModelDraft.named("catalog").locale("en_US").locale("de_DE")
                        .rootGroup("Catalog", g -> g.field("Sku", FieldKind.STRING))
                        .build().toJson());
        return model;
    }

    @Test
    void anAliasFlagNamesTheReferenceInstead(@TempDir Path dir) throws Exception {
        Path model = modelWithCatalog(dir);
        String before = Files.readString(model);
        var res = CliTestSupport.run("-m", model.toString(), "include", "add", "--alias", "catalog",
                "--reference", "catalog", "--parent", "/Subscription", "--name", "Catalog", "-w", dir.toString());
        assertNotEquals(0, res.code(), res.out());
        assertTrue(res.err().contains("DM 29.4 has no include alias") && res.err().contains("--reference"),
                res.err());
        assertEquals(before, Files.readString(model));
    }

    @Test
    void theRetiredRenameVerbNamesSetReference(@TempDir Path dir) throws Exception {
        Path model = modelWithCatalog(dir);
        var res = CliTestSupport.run("-m", model.toString(), "include", "rename", "catalog", "--to", "products");
        assertNotEquals(0, res.code(), res.out());
        assertTrue(res.err().contains("Renaming an include is retired") && res.err().contains("include set-reference"),
                res.err());
    }

    @Test
    void anApplyAliasArgNamesTheReferenceInstead(@TempDir Path dir) throws Exception {
        Path model = modelWithCatalog(dir);
        String before = Files.readString(model);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [{"target":"include","op":"remove","alias":"catalog"}]""");
        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertNotEquals(0, res.code(), res.out());
        assertTrue(res.out().contains("DM 29.4 has no include alias") && res.out().contains("`reference`"),
                res.out());
        assertEquals(before, Files.readString(model));
    }

    @Test
    void anApplyRenameOpNamesSetReference(@TempDir Path dir) throws Exception {
        Path model = modelWithCatalog(dir);
        String before = Files.readString(model);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [{"target":"include","op":"rename","alias":"catalog","to":"products"}]""");
        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertNotEquals(0, res.code(), res.out());
        assertTrue(res.out().contains("Renaming an include is retired") && res.out().contains("{reference, to}"),
                res.out());
        assertEquals(before, Files.readString(model));
    }
}
