package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A path-shape refusal names the path the author MEANT, not the rule the summary already stated.
 *
 * <p><b>Why this exists.</b> These refusals carried {@code RkCode.INVALID_VALUE.fix()} — "give the value the
 * type the schema declares for this key" — which is true of every rejected value and so says nothing about
 * this one. The value that lands here is almost always a RELATIVE path, because the typed Java rule API
 * spells the same slot relatively ({@code DraftRule.errorEntityRelPath} takes {@code ../Field}) while every
 * CLI path option is absolute. Two live conventions for one concept is the actual source of the mistake, and
 * the corrective now says so.
 *
 * <p><b>Measured, and the reason it is worth a test.</b> One session hit this four times in a row on
 * 2026-09-04 while probing, because its own envelope filter kept {@code diagnostics[].code} and discarded the
 * summary — so it read {@code RK_INVALID_VALUE} as a kernel rejection of the model. The instrument fix is
 * {@code scripts/typescript/probe-verdicts.ts}; this is the other half, for a reader who does see the fix.
 */
class AbsolutePathCorrectiveTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void aRelativeFieldIsToldTheAbsolutePathItMeant(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--group", "/Order", "--field", "../CustomerName", "--code", "C1",
                "--condition", "FieldFilled(CustomerName)");

        assertEquals(2, res.code(), res.out());
        JsonNode diagnostic = M.readTree(res.out()).path("diagnostics").get(0);
        String fix = diagnostic.path("fix").asText();
        assertTrue(fix.contains("did you mean /Order/CustomerName?"),
                () -> "the corrective must compute the path, not restate the rule: " + fix);
        assertTrue(fix.contains("errorEntityRelPath"),
                () -> "and must name the convention the author reached for: " + fix);
        assertFalse(fix.contains("give the value the type the schema declares"),
                () -> "the generic fix says nothing about this value and must not be what ships here: " + fix);
    }

    /**
     * The channel is what a probe reads, and it must stay unambiguous: this is a REFUSAL of the request, not a
     * verdict about the model, so it carries {@code outcome:refused}, {@code source:PRECHECK} and the offending
     * {@code where.arg} — the three fields {@code probe-verdicts.ts} keys on.
     */
    @Test
    void theRefusalIsSelfDescribingEnoughToTellItFromAVerdict(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--group", "/Order", "--field", "../CustomerName", "--code", "C1",
                "--condition", "FieldFilled(CustomerName)");

        JsonNode envelope = M.readTree(res.out());
        assertEquals("refused", envelope.path("outcome").asText(), envelope.toString());
        assertTrue(envelope.path("valid").isMissingNode(),
                "a refused request establishes no subject verdict, so `valid` must be absent");
        JsonNode diagnostic = envelope.path("diagnostics").get(0);
        assertEquals("PRECHECK", diagnostic.path("source").asText(), diagnostic.toString());
        assertEquals("--field", diagnostic.path("where").path("arg").asText(), diagnostic.toString());
    }

    /**
     * With no {@code --group} to resolve against, the corrective says so rather than inventing a base.
     *
     * <p>{@code rule eval} is the verb without a {@code --group} option, and its {@code --instance} is
     * {@code required}: omitting it fails in picocli before {@code call()} runs, so the envelope never appears.
     * That is how the first version of this test failed — it was measuring argument parsing, not the refusal.
     */
    @Test
    void aBareNameWithNoGroupIsToldWhyNoPathCanBeComputed(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("order.json");
        Files.writeString(instance, "{\"Order\":{\"Quantity\":\"150\"}}");

        var res = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "--field", "CustomerName", "--code", "C1", "--condition", "FieldFilled(CustomerName)",
                "--instance", instance.toString());

        assertEquals(2, res.code(), res.out());
        String fix = M.readTree(res.out()).path("diagnostics").get(0).path("fix").asText();
        assertTrue(fix.contains("no --group here"),
                () -> "the corrective must say why it cannot compute the path: " + fix);
    }

    /**
     * A value that climbs past every group resolves to something no {@code --field} can be, and the corrective
     * must say what it resolved to rather than propose it. The old hand-written resolver reported this for
     * {@code ../Field} as well, because it read the first {@code ..} as leaving the group.
     */
    @Test
    void aValueThatClimbsPastEveryGroupIsToldWhatItResolvedTo(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--group", "/Order", "--field", "../../../CustomerName", "--code", "C1",
                "--condition", "FieldFilled(CustomerName)");

        assertEquals(2, res.code(), res.out());
        String fix = M.readTree(res.out()).path("diagnostics").get(0).path("fix").asText();
        assertTrue(fix.contains("names /CustomerName, which is not a field path"),
                () -> "the corrective must name what the value resolved to: " + fix);
        assertFalse(fix.contains("did you mean"),
                () -> "and must not propose a value the gate would refuse again: " + fix);
    }
}
