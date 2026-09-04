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
     * The same corrective on the SPEC side, where the key is {@code field} rather than {@code --field} and the
     * only base available is a declared {@code group} — the field is malformed, so no base can be derived from
     * it. Three verbs refused this shape with the code's generic "give the value the type the schema declares"
     * until 2026-09-04; a corrective that fits every rejected value describes none of them.
     */
    @Test
    void theSpecKeysCarryTheComputedCorrectiveToo(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ruleSpec = dir.resolve("rule.json");
        Files.writeString(ruleSpec, """
                {"field": "../CustomerName", "group": "/Order", "code": "C1",
                 "condition": "FieldFilled(CustomerName)",
                 "messages": [{"locale": "en_US", "text": "name it"}]}
                """);
        Path compSpec = dir.resolve("comp.json");
        // `messages` is required and is checked BEFORE the path shape, so omitting it hides the case under test.
        Files.writeString(compSpec, """
                {"computedField": "../Total", "group": "/Order", "name": "TotalComp",
                 "alternatives": [{"operation": "Sum(/Order/Quantity)"}],
                 "messages": [{"locale": "en_US", "text": "the order total"}]}
                """);

        var rule = CliTestSupport.run("-m", model.toString(), "rule", "add", ruleSpec.toString(), "--dry-run");
        var comp = CliTestSupport.run("-m", model.toString(), "computation", "add", compSpec.toString(),
                "--dry-run");

        // Exit 1, not 2: a spec-key path-shape problem is a REJECTED subject, where an option-value one is a
        // REFUSED request. Both are the same class of author mistake and both now carry the same corrective.
        assertEquals(1, rule.code(), rule.out());
        assertTrue(fixOf(rule).contains("did you mean /Order/CustomerName?"),
                () -> "rule add's `field` must name the path it meant: " + fixOf(rule));
        assertEquals(1, comp.code(), comp.out());
        assertTrue(fixOf(comp).contains("did you mean /Order/Total?"),
                () -> "computation add's `computedField` must too: " + fixOf(comp));
    }

    /**
     * The corrective must not hand back a spelling the tool refuses. An {@code apply} op-record names its op as
     * two keys, while the supported-op list is space-joined labels, so answering a bad {@code op} value with
     * the bare list confirms the mistake — measured here on 2026-09-04, when {@code {"op": "rule add"}} was
     * refused and then told {@code supported ops: …, rule add, …}. The summary also quoted
     * {@code ' rule add'}, a leading-space value the author never wrote.
     */
    @Test
    void anOpRecordsRefusalNeverEchoesBackAValueTheToolRejects(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("mislabelled.json");
        Files.writeString(ops, """
                [{"op": "rule add", "field": "/Order/CustomerName", "code": "C1",
                  "condition": "FieldFilled(CustomerName)",
                  "messages": [{"locale": "en_US", "text": "name it"}]}]
                """);

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString(), "--dry-run");

        JsonNode diagnostic = M.readTree(res.out()).path("data").path("results").get(0)
                .path("diagnostics").get(0);
        assertEquals("RK_UNKNOWN_OP", diagnostic.path("code").asText(), diagnostic.toString());
        assertFalse(diagnostic.path("summary").asText().contains("' rule add'"),
                () -> "the summary must quote what the author wrote, not target+\" \"+op: " + diagnostic);
        assertTrue(diagnostic.path("fix").asText().contains("\"target\": \"rule\", \"op\": \"add\""),
                () -> "and the corrective must split the label into the two keys: " + diagnostic);
    }

    /**
     * {@code apply}'s rule-add op reaches the author as a message and nothing else — its refusal is a thrown
     * {@code IllegalArgumentException}, not a diagnostic with a {@code fix} slot — so the corrective has to be
     * inside the message or it is simply absent on the batch path.
     */
    @Test
    void theApplyOpCarriesItInTheMessageBecauseItHasNoFixSlot(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [{"target": "rule", "op": "add", "field": "../CustomerName", "group": "/Order", "code": "C1",
                  "condition": "FieldFilled(CustomerName)",
                  "messages": [{"locale": "en_US", "text": "name it"}]}]
                """);

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString(), "--dry-run");

        assertTrue(res.out().contains("did you mean /Order/CustomerName?"),
                () -> "the batch path must not be the one place the corrective is lost: " + res.out());
    }

    private static String fixOf(CliTestSupport.Result res) {
        try {
            return M.readTree(res.out()).path("diagnostics").get(0).path("fix").asText();
        } catch (Exception e) {
            throw new AssertionError("no diagnostic in: " + res.out(), e);
        }
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
