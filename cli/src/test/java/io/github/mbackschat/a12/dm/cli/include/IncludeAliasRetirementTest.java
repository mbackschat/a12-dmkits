package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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

    /**
     * The read-path counterpart of the refusals below: A12 31.1.1 ignores an {@code alias} left on the header
     * include entry (KF651, measured in {@code adapter.laws.IncludeConfigShapeLawsTest}), so a model still carrying
     * one checks exactly as its canonical form does. This row is also the only {@code :cli} path that binds that
     * header field, which keeps it in the traced native reachability metadata.
     */
    @Test
    void aStaleHeaderAliasChecksAsTheCanonicalModelDoes(@TempDir Path dir) throws Exception {
        String canonical = resource("/models/multifile/app/storefront.dm.json");
        String aliased = canonical.replace("\"purpose\": \"include\",", "\"alias\": \"catalog\", \"purpose\": \"include\",");
        assertNotEquals(canonical, aliased, "the fixture's header include entry moved; the alias was never planted");

        var expected = checkIn(dir.resolve("canonical"), canonical);
        var actual = checkIn(dir.resolve("aliased"), aliased);
        assertEquals(0, actual.code(), actual.out() + actual.err());
        assertEquals(expected.out(), actual.out());
    }

    private static CliTestSupport.Result checkIn(Path workspace, String storefront) throws Exception {
        Files.createDirectories(workspace);
        Path model = workspace.resolve("storefront.dm.json");
        Files.writeString(model, storefront);
        Files.writeString(workspace.resolve("catalog.dm.json"), resource("/models/multifile/lib/catalog.dm.json"));
        return CliTestSupport.run("-m", model.toString(), "-w", workspace.toString(), "model", "check");
    }

    private static String resource(String classpathPath) throws Exception {
        try (var in = IncludeAliasRetirementTest.class.getResourceAsStream(classpathPath)) {
            assertTrue(in != null, () -> "missing " + classpathPath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
