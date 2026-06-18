package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The session {@code -w/--workspace} root, end to end: a model whose include lives in a <em>different</em>
 * directory resolves only once the workspace root points there — without a per-verb {@code -w/--workspace}.
 * The auto-resolving workspace root (set once, every verb honors it).
 */
class WorkspaceRootTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void workspaceRootResolvesIncludesFromAnotherDirectory(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectory(tmp.resolve("app"));
        Path lib = Files.createDirectory(tmp.resolve("lib"));
        Path sub = app.resolve("Sub.dm.json");
        Files.writeString(sub, DocumentModelDraft.named("Sub").locale("en_US")
                .include("base", "Base")
                .rootGroup("S", g -> g.field("X", FieldKind.STRING)).build().toJson());
        Files.writeString(lib.resolve("Base.dm.json"), DocumentModelDraft.named("Base").locale("en_US")
                .rootGroup("B", g -> g.field("Y", FieldKind.STRING)).build().toJson());

        // default include-dir = the model's own folder (app/) — Base isn't there, so the include is unresolved
        JsonNode dflt = info(CliTestSupport.run("-m", sub.toString(), "model", "info"));
        assertTrue(dflt.path("includes").path(0).path("resolvedPath").isNull(),
                () -> "without a workspace root, the cross-dir include doesn't resolve: " + dflt);

        // -w lib resolves it — no per-verb -w/--workspace needed
        JsonNode withRoot = info(CliTestSupport.run("-m", sub.toString(), "-w", lib.toString(), "model", "info"));
        assertEquals("Base.dm.json", withRoot.path("includes").path(0).path("resolvedPath").asText(),
                () -> "the -w workspace root resolves the include: " + withRoot);
    }

    private static JsonNode info(CliTestSupport.Result res) throws Exception {
        assertEquals(0, res.code(), res::err);
        return M.readTree(res.out()).path("data");
    }
}
