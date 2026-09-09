package io.github.mbackschat.a12.dm.cli.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three field-config knobs an external consumer was hand-patching into raw DM JSON: a NUMBER's
 * {@code positivesOnly}, and the opt-in pre-1900 check on the {@code dateFragment} and {@code dateRange}
 * declarations, where our field spec exposed it only under {@code date}.
 *
 * <p><b>Why the read half matters as much as the write half.</b> Kernel-modelled but unauthorable is the same
 * defect one layer earlier and harder to see: there is no runtime refusal, just a missing slot, so the
 * property reads as "not a thing" rather than "refused". The consumer's acceptance condition was exactly
 * write-then-read, so each case authors through the tool and reads back through the tool, and the two
 * round-trip cases additionally feed the read block back into {@code field modify} — the channel that carried
 * an RK_UNKNOWN_ARG the last time a read block outran the input schemas.
 */
class SignAndPre1900AuthoringVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path model(Path dir) throws Exception {
        Path file = dir.resolve("Cover_DM.json");
        Files.writeString(file, CliTestSupport.orderRuledJson());
        return file;
    }

    private static Path spec(Path dir, String name, String body) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, body);
        return file;
    }

    @Test
    void fieldAddAcceptsPositivesOnlyAndFieldReadEchoesIt(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path spec = spec(dir, "spec.json", """
                {"group":"/Order","name":"Deposit","kind":"NUMBER",
                 "number":{"maxFractionalDigits":2,"positivesOnly":true}}""");

        var added = CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString());
        assertEquals(0, added.code(), added::err);

        var read = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/Deposit");
        assertEquals(0, read.code(), read::err);
        assertTrue(M.readTree(read.out()).path("data").path("number").path("positivesOnly").asBoolean(false),
                read::out);

        var checked = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(0, checked.code(), checked::err);
    }

    /** The default is signed and has no DM-JSON spelling, so an unset flag must be ABSENT from the read. */
    @Test
    void anUnsetSignFlagIsAbsentFromTheReadRatherThanReportedFalse(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path spec = spec(dir, "spec.json", """
                {"group":"/Order","name":"Balance","kind":"NUMBER","number":{"maxFractionalDigits":2}}""");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString()).code());

        var read = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/Balance");
        assertFalse(M.readTree(read.out()).path("data").path("number").has("positivesOnly"), read::out);
    }

    @Test
    void whatFieldReadReportsForTheSignFlagIsAcceptedBackByFieldModify(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path spec = spec(dir, "spec.json", """
                {"group":"/Order","name":"Deposit","kind":"NUMBER",
                 "number":{"maxFractionalDigits":2,"positivesOnly":true}}""");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString()).code());

        JsonNode readBack = M.readTree(
                CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/Deposit").out())
                .path("data");
        Path modify = spec(dir, "modify.json", "{\"group\":\"/Order\",\"name\":\"Deposit\",\"kind\":\"NUMBER\","
                + "\"number\":" + readBack.path("number").toString() + "}");

        var modified = CliTestSupport.run("-m", model.toString(), "field", "modify", modify.toString());
        assertEquals(0, modified.code(), modified::err);
        var reread = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/Deposit");
        assertTrue(M.readTree(reread.out()).path("data").path("number").path("positivesOnly").asBoolean(false),
                reread::out);
    }

    @Test
    void fieldAddAcceptsTheFragmentPre1900CheckAndFieldReadEchoesIt(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path spec = spec(dir, "spec.json", """
                {"group":"/Order","name":"BilledMonth","kind":"DATEFRAGMENT",
                 "dateFragment":{"format":"yyyy-MM","rejectBefore1900":true}}""");

        var added = CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString());
        assertEquals(0, added.code(), added::err);

        var read = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/BilledMonth");
        JsonNode fragment = M.readTree(read.out()).path("data").path("dateFragment");
        assertEquals("yyyy-MM", fragment.path("format").asText(), read::out);
        assertTrue(fragment.path("rejectBefore1900").asBoolean(false), read::out);

        var checked = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(0, checked.code(), checked::err);
    }

    @Test
    void fieldAddAcceptsTheRangePre1900CheckAndFieldReadEchoesIt(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path spec = spec(dir, "spec.json", """
                {"group":"/Order","name":"CoverWindow","kind":"DATERANGE",
                 "dateRange":{"format":"dd.MM.yyyy","separator":"-","rejectBefore1900":true}}""");

        var added = CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString());
        assertEquals(0, added.code(), added::err);

        var read = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/CoverWindow");
        JsonNode range = M.readTree(read.out()).path("data").path("dateRange");
        assertEquals("dd.MM.yyyy", range.path("format").asText(), read::out);
        assertTrue(range.path("rejectBefore1900").asBoolean(false), read::out);

        Path modify = spec(dir, "modify.json", "{\"group\":\"/Order\",\"name\":\"CoverWindow\","
                + "\"kind\":\"DATERANGE\",\"dateRange\":" + range.toString() + "}");
        var modified = CliTestSupport.run("-m", model.toString(), "field", "modify", modify.toString());
        assertEquals(0, modified.code(), modified::err);
    }

    /**
     * The co-requirement, delegated rather than pre-checked: a year-free FRAGMENT format with the check is
     * refused by the kernel's own consistency gate. `dateRange` is the control — the same year-free shape is
     * ADMITTED there, which is why no client-side refusal keyed on "a temporal declaration needs a year" may
     * be added.
     */
    @Test
    void aYearFreeFragmentWithTheCheckIsRefusedWhileTheSameShapeIsAdmittedOnARange(@TempDir Path dir)
            throws Exception {
        Path model = model(dir);
        Path fragment = spec(dir, "fragment.json", """
                {"group":"/Order","name":"BilledPeriod","kind":"DATEFRAGMENT",
                 "dateFragment":{"format":"MM-dd","rejectBefore1900":true}}""");

        var refused = CliTestSupport.run("-m", model.toString(), "field", "add", fragment.toString());
        assertFalse(refused.code() == 0, refused::out);
        assertTrue((refused.out() + refused.err()).contains("MVK_ADDITIONAL_CHECK_INVALID"),
                () -> refused.out() + refused.err());

        Path range = spec(dir, "range.json", """
                {"group":"/Order","name":"BilledWindow","kind":"DATERANGE",
                 "dateRange":{"format":"MM-dd","separator":"/","rejectBefore1900":true}}""");
        var admitted = CliTestSupport.run("-m", model.toString(), "field", "add", range.toString());
        assertEquals(0, admitted.code(), admitted::err);
    }
}
