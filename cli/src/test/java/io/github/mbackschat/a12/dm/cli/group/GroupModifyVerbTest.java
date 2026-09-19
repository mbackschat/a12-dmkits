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
        // the change is real — group read now reports it repeatable AND its numeric max (report #121: the
        // tool reported only `repeatable:true`, so confirming max=10 meant parsing raw JSON — now surfaced).
        var read = CliTestSupport.run("-m", m.toString(), "group", "read", "/Subscription/Billing");
        JsonNode data = M.readTree(read.out()).path("data");
        assertTrue(data.path("repeatable").asBoolean(), () -> "the group is now repeatable: " + read.out());
        assertEquals(10, data.path("maxRepetitions").asInt(),
                () -> "group read surfaces the numeric max, not just the boolean: " + read.out());
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
    void setsAndReadsBackGroupMetadata(@TempDir Path dir) throws Exception {
        // G38 metadata remains readable from group read; G45 centralizes its post-creation write under meta.
        Path m = subscription(dir);
        // both declared locales are supplied to exercise the bilingual write; the kernel requires no coverage
        // from a display label (KF406), so a one-locale label would be legal here too.
        var res = CliTestSupport.run("-m", m.toString(), "meta", "/Subscription/Billing",
                "--label", "en_US=Billing details", "--label", "de_DE=Rechnungsdaten",
                "--annotation", "owner=billing-team",
                "--internal-description", "en_US=internal note",
                "--external-description", "en_US=shown to users");
        assertEquals(0, res.code(), res::err);
        assertEquals("applied", M.readTree(res.out()).path("outcome").asText());
        // the edit round-trips through group read (the read-side of G38)
        var read = CliTestSupport.run("-m", m.toString(), "group", "read", "/Subscription/Billing");
        JsonNode data = M.readTree(read.out()).path("data");
        assertEquals("Billing details", data.path("label").path("en_US").asText(), () -> read.out());
        assertEquals("billing-team", data.path("annotations").path("owner").asText());
        assertEquals("internal note", data.path("internalDescription").path("en_US").asText());
        assertEquals("shown to users", data.path("externalDescription").path("en_US").asText());
    }

    @Test
    void removesAGroupAnnotation(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "meta", "/Subscription/Billing",
                "--annotation", "owner=billing-team").code());
        var res = CliTestSupport.run("-m", m.toString(), "meta", "/Subscription/Billing",
                "--remove-annotation", "owner");
        assertEquals(0, res.code(), res::err);
        var read = CliTestSupport.run("-m", m.toString(), "group", "read", "/Subscription/Billing");
        assertTrue(M.readTree(read.out()).path("data").path("annotations").path("owner").isMissingNode(),
                "the annotation is gone after removal");
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
    void setsAGroupIndexField(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        // /Subscription/Addons is repeatable with child fields Name + MonthlyFee
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Addons",
                "--index-field", "Name");
        assertEquals(0, res.code(), res::err);
        assertEquals("applied", M.readTree(res.out()).path("outcome").asText());
        var read = CliTestSupport.run("-m", m.toString(), "group", "read", "/Subscription/Addons");
        assertEquals("Name", M.readTree(read.out()).path("data").path("indexField").asText(),
                () -> "group read echoes the index field: " + read.out());
    }

    @Test
    void clearsAGroupIndexField(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Addons", "--index-field", "Name");
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Addons", "--clear-index-field");
        assertEquals(0, res.code(), res::err);
        var read = CliTestSupport.run("-m", m.toString(), "group", "read", "/Subscription/Addons");
        assertTrue(M.readTree(read.out()).path("data").path("indexField").isMissingNode(),
                () -> "the index field is gone: " + read.out());
    }

    @Test
    void setsANonRepeatableGroupIndexField(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Billing",
                "--index-field", "BaseFee");
        assertEquals(0, res.code(), res::out);
        var read = CliTestSupport.run("-m", m.toString(), "group", "read", "/Subscription/Billing");
        assertEquals("BaseFee", M.readTree(read.out()).path("data").path("indexField").asText(), read::out);
    }

    @Test
    void indexFieldNamingANonChildIsRejected(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Addons",
                "--index-field", "Nope");
        assertNotEquals(0, res.code());
        assertTrue((res.out() + res.err()).contains("Nope"),
                () -> "names the missing child field: " + res.out() + res.err());
    }

    @Test
    void setsRowSortFieldsAndGroupReadEchoesThem(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        // /Subscription/Addons is repeatable with child fields Name + MonthlyFee (G28)
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Addons",
                "--sort-field", "MonthlyFee:desc", "--sort-field", "Name");
        assertEquals(0, res.code(), res::err);
        assertEquals("applied", M.readTree(res.out()).path("outcome").asText());

        var read = CliTestSupport.run("-m", m.toString(), "group", "read", "/Subscription/Addons");
        JsonNode sorts = M.readTree(read.out()).path("data").path("sortFields");
        assertEquals(2, sorts.size(), () -> "group read echoes both sort fields in order: " + read.out());
        assertEquals("MonthlyFee", sorts.get(0).path("name").asText());
        assertEquals("DESC", sorts.get(0).path("order").asText());
        assertEquals("Name", sorts.get(1).path("name").asText());
        assertEquals("ASC", sorts.get(1).path("order").asText(), "no :suffix defaults to ASC");
    }

    @Test
    void clearsRowSortFields(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Addons", "--sort-field", "Name");
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Addons", "--clear-sort-fields");
        assertEquals(0, res.code(), res::err);
        var read = CliTestSupport.run("-m", m.toString(), "group", "read", "/Subscription/Addons");
        assertTrue(M.readTree(read.out()).path("data").path("sortFields").isMissingNode(),
                () -> "the sort fields are gone: " + read.out());
    }

    @Test
    void sortFieldOnANonRepeatableGroupIsRejected(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        // /Subscription/Billing is a single group — a row sort there is meaningless
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Billing",
                "--sort-field", "BaseFee");
        assertNotEquals(0, res.code());
        assertTrue((res.out() + res.err()).toLowerCase().contains("repeat"),
                () -> "names the repeatability requirement: " + res.out() + res.err());
    }

    @Test
    void sortFieldWithABadOrderIsRejected(@TempDir Path dir) throws Exception {
        Path m = subscription(dir);
        var res = CliTestSupport.run("-m", m.toString(), "group", "modify", "/Subscription/Addons",
                "--sort-field", "Name:sideways");
        assertNotEquals(0, res.code());
        assertTrue((res.out() + res.err()).toLowerCase().contains("asc or desc"),
                () -> "names the legal orders: " + res.out() + res.err());
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
