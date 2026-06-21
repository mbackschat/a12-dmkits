package io.github.mbackschat.a12.dm.cli.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * {@code group modify} (G15) — change an existing group's repeatability in place, the core modelling op a
 * real authoring session had to do by hand-editing the JSON (the tool reported {@code repeatable} but gave
 * no verb to change it). The library substrate (`EditSession.setRepeatable`) already existed; this is the
 * missing CLI verb. Re-bound to the kernel gate, so a change that breaks the model fails loudly.
 */
class GroupModifyVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path subscription(Path dir) throws Exception {
        Path m = dir.resolve("subscription.dm.json");
        Files.writeString(m, CliTestSupport.subscriptionJson());
        return m;
    }

    @Test
    void makesAnExistingGroupRepeatable(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Billing", "--repeatable", "10");
        assertEquals(0, res.code(), res::err);
        JsonNode env = M.readTree(res.out());
        assertEquals("applied", env.path("outcome").asText(), () -> "applied: " + res.out());
        assertTrue(env.path("changed").path("repeatable").asBoolean(), () -> "reports the new repeatability: " + res.out());
        // the change is real — group read now reports it repeatable
        var read = CliTestSupport.run("-m", m.toString(), "group", "read", "/Subscription/Billing");
        assertTrue(M.readTree(read.out()).path("data").path("repeatable").asBoolean(),
                () -> "the group is now repeatable: " + read.out());
    }

    @Test
    void makesARepeatableGroupNonRepeatable(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        // /Subscription/Addons is repeatable in the fixture
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Addons", "--non-repeatable");
        assertEquals(0, res.code(), res::err);
        assertEquals("applied", M.readTree(res.out()).path("outcome").asText());
        var read = CliTestSupport.run("-m", m.toString(), "group", "read", "/Subscription/Addons");
        assertFalse(M.readTree(read.out()).path("data").path("repeatable").asBoolean(),
                () -> "the group is now a single group: " + read.out());
    }

    @Test
    void requiresExactlyOneDirective(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        assertNotEquals(0, CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Billing").code(),
                "neither --repeatable nor --non-repeatable is a usage error");
        assertNotEquals(0, CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Billing",
                "--repeatable", "5", "--non-repeatable").code(), "both at once is a usage error");
    }

    @Test
    void unknownGroupIsRefused(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Nope", "--repeatable", "5");
        assertNotEquals(0, res.code());
        assertTrue(res.out().contains("RK_NO_SUCH_GROUP") || res.err().contains("Nope"),
                () -> "names the missing group: " + res.out() + res.err());
    }

    @Test
    void dryRunWritesNothing(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        String before = Files.readString(m);
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Billing",
                "--repeatable", "10", "--dry-run");
        assertEquals(0, res.code(), res::err);
        assertEquals("preview", M.readTree(res.out()).path("outcome").asText());
        assertEquals(before, Files.readString(m), "--dry-run writes nothing");
    }
}
