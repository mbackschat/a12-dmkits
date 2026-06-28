package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The structure-<b>add</b> verbs — {@code add-field} and {@code add-group} — over an existing model,
 * following the {@code modify} convention (in place; {@code -o} redirects, {@code --dry-run} previews).
 * Each re-binds through the kernel before writing.
 */
class StructureAddVerbTest {

    private static Path subscription(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("subscription.dm.json");
        Files.writeString(f, CliTestSupport.subscriptionJson());
        return f;
    }

    @Test
    void addsAField(@TempDir Path dir) throws Exception {
        Path model = subscription(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Subscription", "--name", "Discount", "--kind", "NUMBER");
        assertEquals(0, res.code(), res.err());
        assertTrue(Files.readString(model).contains("\"/Subscription/Discount\""));
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "check").code());
    }

    @Test
    void addFieldIntoAMissingGroupFailsFast(@TempDir Path dir) throws Exception {
        Path model = subscription(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Nope", "--name", "X", "--kind", "STRING");
        assertEquals(2, res.code());
    }

    @Test
    void addFieldRejectsAnUnknownKind(@TempDir Path dir) throws Exception {
        Path model = subscription(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Subscription", "--name", "X", "--kind", "MONEY");
        assertEquals(2, res.code());
    }

    @Test
    void addsAGroup(@TempDir Path dir) throws Exception {
        Path model = subscription(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "add", "--parent", "/Subscription", "--name", "Notes");
        assertEquals(0, res.code(), res.err());
        assertTrue(Files.readString(model).contains("\"/Subscription/Notes\""));
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "check").code());
    }

    @Test
    void addsARepeatableGroup(@TempDir Path dir) throws Exception {
        Path model = subscription(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "add",
                "--parent", "/Subscription", "--name", "History", "--repeatable", "10");
        assertEquals(0, res.code(), res.err());
        assertTrue(Files.readString(model).contains("\"/Subscription/History\""));
    }

    @Test
    void dryRunWritesNothing(@TempDir Path dir) throws Exception {
        Path model = subscription(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Subscription", "--name", "Discount", "--kind", "NUMBER", "--dry-run");
        assertEquals(0, res.code(), res.err());
        assertTrue(!Files.readString(model).contains("Discount"), "--dry-run must write nothing");
    }
}
