package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Probe 01-de Finding 4 — a child element added into an <b>include mount</b> group silently shadowed the
 * mount (the kernel uses the mount's local {@code elements} and stops expanding the include, valid but wrong;
 * KERNEL-FINDINGS). The fix refuses the edit; this guard proves the refusal <em>fires</em> and that the
 * mounted content <em>survives</em> (the "applied ⇒ effected" inverse: a falsely-applied shadow must not land).
 */
class MountEditRefusalTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** A {@code person} model mounting an {@code address} include at {@code /Person/Addr} (resolving Street, City). */
    private static Path personMountingAddress(Path dir) throws Exception {
        Path address = dir.resolve("address.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "address", "--locale", "en_US",
                "--root", "Address", "-o", address.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", address.toString(), "field", "add",
                "--group", "/Address", "--name", "Street", "--kind", "STRING").code());
        assertEquals(0, CliTestSupport.run("-m", address.toString(), "field", "add",
                "--group", "/Address", "--name", "City", "--kind", "STRING").code());
        Path person = dir.resolve("person.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "person", "--locale", "en_US",
                "--root", "Person", "-o", person.toString()).code());
        assertEquals(0, CliTestSupport.run("-w", dir.toString(), "-m", person.toString(), "include", "add",
                "--reference", "address", "--name", "Addr", "--parent", "/Person").code());
        return person;
    }

    private static List<String> resolvedFields(Path person, Path dir) throws Exception {
        CliTestSupport.Result d = CliTestSupport.run("-w", dir.toString(), "-m", person.toString(), "model", "describe");
        assertEquals(0, d.code(), d::err);
        java.util.List<String> paths = new java.util.ArrayList<>();
        M.readTree(d.out()).path("data").path("fields").forEach(f -> paths.add(f.path("path").asText()));
        return paths;
    }

    @Test
    void fieldAddIntoAMountGroupIsRefusedAndTheMountSurvives(@TempDir Path dir) throws Exception {
        Path person = personMountingAddress(dir);
        // precondition: the mount resolves the included fields
        assertTrue(resolvedFields(person, dir).contains("/Person/Addr/Street"), "the include resolves before the edit");

        CliTestSupport.Result res = CliTestSupport.run("-w", dir.toString(), "-m", person.toString(),
                "field", "add", "--group", "/Person/Addr", "--name", "Extra", "--kind", "STRING");
        assertNotEquals(0, res.code(), () -> "adding into a mount must be refused, not applied: " + res.out());
        JsonNode env = M.readTree(res.out());
        assertEquals("refused", env.path("outcome").asText(), () -> "a structured refusal: " + res.out());
        assertTrue(res.out().contains("RK_MOUNT_NOT_EDITABLE"),
                () -> "refused with the structured code: " + res.out());

        // the mounted fields must still resolve — the refusal wrote nothing
        List<String> after = resolvedFields(person, dir);
        assertTrue(after.contains("/Person/Addr/Street") && after.contains("/Person/Addr/City"),
                () -> "the include still resolves after the refused edit (no silent shadow): " + after);
    }

    @Test
    void theApplyOpIntoAMountGroupAlsoFailsLoudly(@TempDir Path dir) throws Exception {
        Path person = personMountingAddress(dir);
        Path ops = dir.resolve("ops.json");
        java.nio.file.Files.writeString(ops, """
                [ { "target":"field", "op":"add", "group":"/Person/Addr", "name":"Extra", "kind":"STRING" } ]""");
        CliTestSupport.Result res = CliTestSupport.run("-w", dir.toString(), "-m", person.toString(),
                "apply", ops.toString());
        assertNotEquals(0, res.code(), () -> "an apply op adding into a mount must fail, not silently shadow: " + res.out());
        // atomic rollback → the mount still resolves
        assertTrue(resolvedFields(person, dir).contains("/Person/Addr/Street"),
                () -> "the include survives the rolled-back apply");
    }
}
