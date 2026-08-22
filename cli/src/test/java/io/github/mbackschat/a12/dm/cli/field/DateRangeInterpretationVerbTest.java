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
 * G53's remaining half: a DATE_RANGE's {@code interpretationOfYear} authorable and readable through the CLI.
 *
 * <p>This is the surface an external consumer is blocked on — without it the wrapping-yearless-range
 * endpoint-year mechanism cannot be probed except by hand-editing raw DM JSON, which the tool's own guidance
 * forbids. The modelkit half already carries the property; these cases pin the CLI channels.
 */
class DateRangeInterpretationVerbTest {

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
    void fieldAddAcceptsTheInterpretationAndFieldReadEchoesIt(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path spec = spec(dir, "spec.json", """
                {"group":"/Order","name":"CoverWindow","kind":"DATERANGE",
                 "dateRange":{"format":"dd.MM","separator":"-","interpretationOfYear":"TO"}}""");

        var added = CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString());
        assertEquals(0, added.code(), added.err());

        var read = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/CoverWindow");
        assertEquals(0, read.code(), read.err());
        JsonNode range = M.readTree(read.out()).path("data").path("dateRange");
        assertEquals("dd.MM", range.path("format").asText(), read::out);
        assertEquals("-", range.path("separator").asText(), read::out);
        assertEquals("TO", range.path("interpretationOfYear").asText(), read::out);
    }

    /** The default has no spelling: omitting the key must leave `field read` silent about it, not report STANDARD. */
    @Test
    void anUndeclaredInterpretationIsAbsentFromTheReadRatherThanReportedAsStandard(@TempDir Path dir)
            throws Exception {
        Path model = model(dir);
        Path spec = spec(dir, "spec.json", """
                {"group":"/Order","name":"PlainWindow","kind":"DATERANGE",
                 "dateRange":{"format":"dd.MM","separator":"-"}}""");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString()).code());

        var read = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/PlainWindow");
        assertFalse(M.readTree(read.out()).path("data").path("dateRange").has("interpretationOfYear"), read::out);
    }

    /**
     * The round-trip that G53 exists for: what `field read` reports must be accepted back by `field modify`.
     * Before this slice the read block carried a key the input schemas rejected with RK_UNKNOWN_ARG.
     */
    @Test
    void whatFieldReadReportsIsAcceptedBackByFieldModify(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path spec = spec(dir, "spec.json", """
                {"group":"/Order","name":"CoverWindow","kind":"DATERANGE",
                 "dateRange":{"format":"dd.MM","separator":"-","interpretationOfYear":"FROM"}}""");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString()).code());

        JsonNode range = M.readTree(CliTestSupport.run(
                "-m", model.toString(), "field", "read", "/Order/CoverWindow").out()).path("data").path("dateRange");
        Path echoed = spec(dir, "echoed.json", M.writeValueAsString(M.createObjectNode()
                .put("group", "/Order").put("name", "CoverWindow").put("kind", "DATERANGE")
                .set("dateRange", range)));

        var modified = CliTestSupport.run("-m", model.toString(), "field", "modify", echoed.toString());
        assertEquals(0, modified.code(), modified.err());

        JsonNode after = M.readTree(CliTestSupport.run(
                "-m", model.toString(), "field", "read", "/Order/CoverWindow").out()).path("data").path("dateRange");
        assertEquals("FROM", after.path("interpretationOfYear").asText(),
                () -> "the re-applied read block must preserve the value: " + after);
    }

    /**
     * A refused spelling is rejected at the CLI boundary — as a CODED, located, fix-carrying precheck
     * diagnostic that names the admitted set, not a raw parse error and not a trip to the kernel. The check is
     * derived from the published schema's own {@code enum}, so the tool's contract and its enforcement cannot
     * drift apart. {@code SUBSEQUENT} is the trap worth locking: it is the tool API's spelling for the same
     * concept, so it reads as plausible and the kernel refuses it.
     */
    @Test
    void aRefusedSpellingIsRejectedWithACorrective(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path spec = spec(dir, "spec.json", """
                {"group":"/Order","name":"CoverWindow","kind":"DATERANGE",
                 "dateRange":{"format":"dd.MM","separator":"-","interpretationOfYear":"SUBSEQUENT"}}""");

        var res = CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString());
        assertTrue(res.code() != 0, res::out);
        JsonNode envelope = M.readTree(res.out());
        assertEquals("rejected", envelope.path("outcome").asText(), res::out);
        assertFalse(envelope.path("written").asBoolean(true), res::out);
        JsonNode d = envelope.path("diagnostics").path(0);
        assertEquals("RK_INVALID_VALUE", d.path("code").asText(), res::out);
        assertEquals("dateRange/interpretationOfYear", d.path("where").path("arg").asText(), res::out);
        assertTrue(d.path("fix").asText().contains("FROM") && d.path("fix").asText().contains("TO"),
                () -> "the corrective must name the admitted set: " + d);
    }

    @Test
    void typedefAddCarriesTheInterpretationToo(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path spec = spec(dir, "typedef.json", """
                {"kind":"DATERANGE",
                 "dateRange":{"format":"dd.MM","separator":"-","interpretationOfYear":"TO"}}""");

        var added = CliTestSupport.run("-m", model.toString(), "typedef", "add",
                "--id", "CoverWindowType", spec.toString());
        assertEquals(0, added.code(), added.err());

        var read = CliTestSupport.run("-m", model.toString(), "typedef", "read", "CoverWindowType");
        assertEquals(0, read.code(), read.err());
        assertEquals("TO", M.readTree(read.out()).path("data").path("dateRange")
                .path("interpretationOfYear").asText(), read::out);
    }

    /**
     * The {@code --text} channel carries the key too, with its absence meaning absence — pinned because a
     * consumer reported the opposite (a12-kernel-lean feedback note 03, 2026-08-22) and it did not reproduce at
     * the revision they pinned. Structurally it cannot: {@code field read} registers no bespoke
     * {@code VerbText}, so its {@code data} goes through the generic tree walker, which has no key allowlist to
     * fall out of. That is an argument, not a guarantee — a bespoke renderer added later would silently take
     * over this verb, and that is exactly the change this case would catch.
     */
    @Test
    void theTextChannelEchoesTheInterpretationAndStaysSilentWhenItIsAbsent(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "add",
                spec(dir, "declared.json", """
                        {"group":"/Order","name":"WrapFrom","kind":"DATERANGE",
                         "dateRange":{"format":"dd.MM","separator":"-","interpretationOfYear":"FROM"}}""")
                        .toString()).code());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "add",
                spec(dir, "plain.json", """
                        {"group":"/Order","name":"PlainWindow","kind":"DATERANGE",
                         "dateRange":{"format":"dd.MM","separator":"-"}}""").toString()).code());

        var declared = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/WrapFrom", "--text");
        assertEquals(0, declared.code(), declared.err());
        assertTrue(declared.out().contains("interpretationOfYear: FROM"),
                () -> "--text must render the key it just wrote: " + declared.out());

        var plain = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/PlainWindow", "--text");
        assertEquals(0, plain.code(), plain.err());
        assertFalse(plain.out().contains("interpretationOfYear"),
                () -> "an omitted interpretation must stay unspelled in --text as well: " + plain.out());
    }
}
