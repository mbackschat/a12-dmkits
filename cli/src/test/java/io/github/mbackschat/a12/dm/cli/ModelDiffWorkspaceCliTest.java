package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/** Multi-model CLI diffs bind each side against the matching workspace state. */
class ModelDiffWorkspaceCliTest {

    @Test
    void twoFileModeUsesEachModelsOwnDirectory(@TempDir Path tmp) throws Exception {
        Path base = workspace(Files.createDirectory(tmp.resolve("base")), "pricing-v1");
        Path head = workspace(Files.createDirectory(tmp.resolve("head")), "pricing-v2");

        var result = CliTestSupport.run("model", "diff", base.toString(), head.toString());

        assertEquals(0, result.code(), result::err);
        assertTrue(result.out().contains("pricing-v1") && result.out().contains("pricing-v2"), result::out);
    }

    @Test
    void twoFileModeAcceptsDistinctWorkspaceRoots(@TempDir Path tmp) throws Exception {
        Path baseRoot = Files.createDirectory(tmp.resolve("base"));
        Path headRoot = Files.createDirectory(tmp.resolve("head"));
        Path base = splitWorkspace(baseRoot, "pricing-v1");
        Path head = splitWorkspace(headRoot, "pricing-v2");

        var result = CliTestSupport.run("model", "diff",
                "--base-workspace", baseRoot.toString(), "--head-workspace", headRoot.toString(),
                base.toString(), head.toString());

        assertEquals(0, result.code(), result::err);
        assertTrue(result.out().contains("pricing-v1") && result.out().contains("pricing-v2"), result::out);
    }

    @Test
    void selfContainedModelsDoNotIndexAnUnusedWorkspace(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("base.dm.json");
        Path head = tmp.resolve("head.dm.json");
        Files.writeString(base, provider("catalog"));
        Files.writeString(head, provider("catalog"));

        var result = CliTestSupport.run("-w", tmp.resolve("missing").toString(),
                "model", "diff", base.toString(), head.toString());

        assertEquals(0, result.code(), result::err);
    }

    @Test
    void sinceModeResolvesBaseDependenciesAtTheGitRef(@TempDir Path repo) throws Exception {
        Path model = workspace(repo, "pricing-v1");
        GitTestSupport.run(repo, "init", "-q");
        GitTestSupport.run(repo, "add", ".");
        GitTestSupport.run(repo, "-c", "user.email=t@example.com", "-c", "user.name=Test",
                "commit", "-qm", "base");

        workspace(repo, "pricing-v2");
        var result = CliTestSupport.run("-m", model.toString(), "model", "diff", "--since", "HEAD");

        assertEquals(0, result.code(), result::err);
        assertTrue(result.out().contains("pricing-v1") && result.out().contains("pricing-v2"), result::out);
    }

    private static Path workspace(Path dir, String pricingId) throws Exception {
        Files.writeString(dir.resolve("pricing.dm.json"), provider(pricingId));
        Path model = dir.resolve("store.dm.json");
        Files.writeString(model, store(pricingId));
        return model;
    }

    private static Path splitWorkspace(Path root, String pricingId) throws Exception {
        Path app = Files.createDirectory(root.resolve("app"));
        Path library = Files.createDirectory(root.resolve("lib"));
        Files.writeString(library.resolve("pricing.dm.json"), provider(pricingId));
        Path model = app.resolve("store.dm.json");
        Files.writeString(model, store(pricingId));
        return model;
    }

    private static String store(String pricingId) {
        return DocumentModelDraft.named("store").locale("en_US")
                .include("pricing", pricingId)
                .rootGroup("Store", store -> {
                    store.field("Sku", FieldKind.STRING);
                    store.mount("Prices", "pricing");
                })
                .build().toJson();
    }

    private static String provider(String id) {
        return DocumentModelDraft.named(id).locale("en_US")
                .rootGroup("Pricing", pricing -> pricing.field("Amount", FieldKind.NUMBER))
                .build().toJson();
    }
}
