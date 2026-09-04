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
     * A resolvable value with no group to resolve it against: the corrective says so rather than inventing a
     * base. {@code rule eval} is the verb with no {@code --group} option.
     *
     * <p>Two things this test got wrong before, both worth keeping written down. Its {@code --instance} is
     * {@code required}, so omitting it fails in picocli before {@code call()} runs and no envelope appears at
     * all — the first version was measuring argument parsing, not the refusal. And it passed a BARE name,
     * which is ambiguous with or without a group, so it never reached this branch once the corrective stopped
     * guessing; only a {@code ../}-prefixed value does.
     */
    @Test
    void aResolvableValueWithNoGroupIsToldWhyNoPathCanBeComputed(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("order.json");
        Files.writeString(instance, "{\"Order\":{\"Quantity\":\"150\"}}");

        var res = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "--field", "../CustomerName", "--code", "C1", "--condition", "FieldFilled(CustomerName)",
                "--instance", instance.toString());

        assertEquals(2, res.code(), res.out());
        String fix = M.readTree(res.out()).path("diagnostics").get(0).path("fix").asText();
        assertTrue(fix.contains("there is no group here"),
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

        JsonNode diagnostic = unknownOp(res);
        assertEquals("RK_UNKNOWN_OP", diagnostic.path("code").asText(), diagnostic.toString());
        assertFalse(diagnostic.path("summary").asText().contains("' rule add'"),
                () -> "the summary must quote what the author wrote, not target+\" \"+op: " + diagnostic);
        assertTrue(diagnostic.path("fix").asText().contains("\"target\": \"rule\", \"op\": \"add\""),
                () -> "and the corrective must split the label into the two keys: " + diagnostic);
    }

    /**
     * The same invariant in every other direction, because fixing it in ONE was itself the defect: a cold
     * review found `{"target":"rule"}` still reported {@code op 'rule '} — a trailing-space value the author
     * never wrote — and that `{"target":"rule","op":"rule add"}` reported the synthesized
     * {@code op 'rule rule add'} while WITHHOLDING the split hint, precisely where the author had demonstrably
     * confused the two spellings.
     */
    @Test
    void theSummaryQuotesWhatWasWrittenInEveryDirection(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        JsonNode noOp = unknownOp(applyOne(dir, model, "{\"target\": \"rule\"}"));
        assertFalse(noOp.path("summary").asText().contains("'rule '"),
                () -> "a trailing space is the same fabrication as a leading one: " + noOp);
        assertTrue(noOp.path("summary").asText().contains("target 'rule' with no op"), noOp::toString);

        JsonNode both = unknownOp(applyOne(dir, model, "{\"target\": \"rule\", \"op\": \"rule add\"}"));
        assertFalse(both.path("summary").asText().contains("rule rule add"),
                () -> "never quote a value assembled from two keys: " + both);
        assertTrue(both.path("fix").asText().contains("\"target\": \"rule\", \"op\": \"add\""),
                () -> "…and the split hint is MOST needed here, not withheld: " + both);

        JsonNode empty = unknownOp(applyOne(dir, model, "{}"));
        assertTrue(empty.path("summary").asText().contains("names no target/op pair"), empty::toString);
    }

    private static CliTestSupport.Result applyOne(Path dir, Path model, String opRecord) throws Exception {
        Path ops = Files.createTempFile(dir, "ops", ".json");
        Files.writeString(ops, "[" + opRecord + "]");
        return CliTestSupport.run("-m", model.toString(), "apply", ops.toString(), "--dry-run");
    }

    private static JsonNode unknownOp(CliTestSupport.Result res) throws Exception {
        return M.readTree(res.out()).path("data").path("results").get(0).path("diagnostics").get(0);
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

    /**
     * The corrective proposes a path for ONE value shape and refuses to guess for the rest — the correction of
     * a defect the first version of this class did not cover.
     *
     * <p>That version resolved every relative value against a placeholder element name, and for anything not
     * starting with {@code ../} the placeholder SURVIVED into the answer: {@code --field CustomerName} under
     * {@code /Order} was told <em>"did you mean /Order/TheRuleBeingAuthored/CustomerName?"</em>, at all six
     * call sites. No test caught it because none exercised a relative value without the {@code ../} prefix —
     * the round-trip lock in {@code EntityPathTest} constrains the algebra, not a caller's choice of base.
     *
     * <p>A corrective is copied without being checked, so a confident wrong path is strictly worse than the
     * generic text it replaced. Every shape below is genuinely ambiguous without consulting the model.
     */
    @Test
    void anAmbiguousRelativeValueGetsNoGuessAndNoPlaceholder(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        for (String ambiguous : java.util.List.of(
                "CustomerName", "./CustomerName", "Order/CustomerName", "Items*/Amount",
                "examples/models/order-ruled.dm.json")) {
            var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                    "--group", "/Order", "--field", ambiguous, "--code", "C1",
                    "--condition", "FieldFilled(CustomerName)");
            String fix = fixOf(res);

            assertFalse(fix.contains("TheRuleBeingAuthored"),
                    () -> ambiguous + ": a placeholder element name must never reach the author: " + fix);
            assertFalse(fix.contains("did you mean"),
                    () -> ambiguous + ": the value does not say what it meant, so no path may be proposed: " + fix);
            assertTrue(fix.contains("model describe"),
                    () -> ambiguous + ": point at where the model's own paths can be read: " + fix);
        }
    }

    /** …and no branch synthesizes an example from the rejected value, which produced an unusable path. */
    @Test
    void noCorrectiveProposesAPathTheGateWouldRefuseAgain(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("order.json");
        Files.writeString(instance, "{\"Order\":{\"Quantity\":\"150\"}}");

        // `rule eval` has no --group, so this is the branch that used to concatenate: e.g. /Order/../CustomerName
        var noGroup = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "--field", "../CustomerName", "--code", "C1", "--condition", "FieldFilled(CustomerName)",
                "--instance", instance.toString());
        // `/../` and not `..`: the convention sentence legitimately names the `../Field` spelling. What must not
        // appear is a PATH with a climb inside it, which isAbsoluteEntityPath admits and the model rejects.
        assertFalse(fixOf(noGroup).contains("/../"),
                () -> "an example path containing a climb passes the shape gate and fails at the model: "
                        + fixOf(noGroup));

        // `/` and the empty string name the root LEVEL, which holds groups — no base for a field path.
        for (String rootish : java.util.List.of("/", "")) {
            var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                    "--group", rootish, "--field", "../CustomerName", "--code", "C1",
                    "--condition", "FieldFilled(CustomerName)");
            String fix = fixOf(res);
            assertFalse(fix.contains("//"),
                    () -> "'" + rootish + "': a doubled separator is not a path anyone can use: " + fix);
            assertTrue(fix.contains("root LEVEL"),
                    () -> "'" + rootish + "': say why there is no base, rather than printing a hole: " + fix);
        }
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
