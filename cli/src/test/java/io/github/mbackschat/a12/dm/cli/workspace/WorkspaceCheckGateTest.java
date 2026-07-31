package io.github.mbackschat.a12.dm.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * {@code workspace check} is a <b>gate</b>: it must never exit 0 for a workspace it did not clear.
 *
 * <p>Written because it briefly did. When the verb was moved onto the typed result algebra, "no model reached the
 * consistency oracle" was routed through the QUERY family — and a query holds no verdict, so its exit code is
 * <em>structurally</em> 0. The per-model failures were still counted and still printed in {@code data}; only the
 * process status lied. That is the worst possible shape for this verb, whose entire purpose is to be called from a
 * pre-commit hook or a CI step that reads nothing but the exit code.
 *
 * <p>The reachable input is not the obvious one. An <em>unreadable</em> file cannot produce it: {@code Workspace.scan}
 * parses every candidate itself and skips anything that fails, so a broken file is never listed as a model and the
 * scan comes back empty — which is exit 0 both before and after, correctly. What reaches the branch is a model that
 * scans <em>fine</em> and then fails <em>before</em> the oracle: an {@code include} naming an id absent from the tree
 * raises {@code UnresolvedReferenceException}, a {@code RuntimeException} rather than a
 * {@code ModelConsistencyException}, so the check records it invalid with no gate receipt. A test built on the
 * unreadable-file guess would have passed against the defect.
 */
class WorkspaceCheckGateTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The reachable case: one model, scannable, whose include cannot be resolved — so nothing gates. */
    @Test
    void aWorkspaceWhereNoModelReachesTheGateDoesNotPass(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("order.dm.json"), CliTestSupport.orderRuledJson()
                .replaceFirst("\"includes\"\\s*:\\s*\\[", "\"includes\": [")
                .replaceFirst("\\{", "{"));
        // give it an include that no file in the tree provides
        String withDanglingInclude = Files.readString(dir.resolve("order.dm.json"))
                .replaceFirst("\"modelReferences\"\\s*:\\s*\\[[^]]*]", "\"modelReferences\": []");
        Files.writeString(dir.resolve("order.dm.json"), danglingInclude(withDanglingInclude));

        var res = CliTestSupport.run("workspace", "check", dir.toString());

        JsonNode j = M.readTree(res.out());
        assertNotEquals(0, res.code(),
                () -> "a gate must not report success for a workspace where NOTHING was checked: " + res.out());
        assertFalse(j.path("data").path("models").isEmpty(),
                () -> "precondition: the model was scanned, so this is the pre-gate branch: " + res.out());
        assertFalse(j.has("valid"),
                () -> "and no verdict may be fabricated — nothing produced one: " + res.out());
    }

    /** An EMPTY directory asked nothing of the gate, so exit 0 is right and must stay. */
    @Test
    void anEmptyWorkspacePasses(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("workspace", "check", dir.toString());
        assertEquals(0, res.code(), res::out);
        assertEquals(0, M.readTree(res.out()).path("data").path("count").asInt());
    }

    /** The ordinary path still works, so the fix above is not blanket pessimism. */
    @Test
    void aValidWorkspacePassesWithItsVerdictAndProvenance(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("order.dm.json"), CliTestSupport.orderRuledJson());

        var res = CliTestSupport.run("workspace", "check", dir.toString());

        JsonNode j = M.readTree(res.out());
        assertEquals(0, res.code(), res::out);
        assertEquals(true, j.path("valid").asBoolean(), res::out);
        assertEquals("KERNEL_CONFIRMED", j.path("verification").asText(), res::out);
    }

    /** Inject an include whose reference names a model id nothing in the scan provides. */
    private static String danglingInclude(String modelJson) {
        return modelJson.replaceFirst("\\{", """
                {"includes":[{"alias":"Nope","reference":"no-such-model"}],""");
    }
}
