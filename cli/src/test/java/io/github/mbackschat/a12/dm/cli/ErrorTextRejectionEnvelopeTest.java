package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rejection of a rule's <b>error text</b> is a kernel verdict, so it is reported through the result envelope
 * on stdout — the same channel a rejected <em>condition</em> already uses (dmtool feedback 2026-08-24, item 1).
 *
 * <p><b>The class, as measured before the fix.</b> The kernel runs two checkers over a persisted rule: the
 * condition parser, and {@code RuleErrortextChecker} over each localized message. The second one's verdict
 * reached callers through three different channels, none of them the documented one:
 *
 * <table><caption>the measured denominator</caption>
 * <tr><th>site</th><th>envelope</th><th>diagnostics</th><th>{@code code}</th><th>{@code where}</th></tr>
 * <tr><td>{@code rule add} (± {@code --dry-run})</td><td><b>none</b> — prose on stderr, exit 2</td>
 *     <td>—</td><td>—</td><td>—</td></tr>
 * <tr><td>{@code computation add} (± {@code --dry-run})</td><td><b>none</b> — prose on stderr, exit 2</td>
 *     <td>—</td><td>—</td><td>—</td></tr>
 * <tr><td>{@code apply} terminal gate (nested in {@code data.results[]})</td><td>yes</td><td>1</td>
 *     <td><b>absent</b></td><td><b>empty</b></td></tr>
 * <tr><td>{@code model check}</td><td>yes</td><td>1</td><td><b>absent</b></td><td><b>empty</b></td></tr>
 * <tr><td>{@code rule modify} / {@code computation modify}</td><td>yes</td><td>1, hand-rolled</td>
 *     <td><b>absent</b></td><td>{@code rule} ✔</td></tr>
 * </table>
 *
 * <p>The last row is the subtlest: those two verbs <em>did</em> publish an envelope, but built a single
 * {@code PRECHECK}-origin diagnostic wrapping the joined exception message and published it beside
 * {@code verification: KERNEL_CONFIRMED} — a rulekit-origin finding attributed to a kernel arbitration, with the
 * kernel's per-problem detail collapsed into prose. Its corrective told the caller to "use `rule check` for
 * diagnostics", which cannot reach an error text at all (the same note's item 2). Reporting through an envelope
 * was therefore not the same thing as reporting the gate's verdict, which is why the shape had to be shared
 * rather than repeated.
 *
 * <p><b>Why {@code code} was absent everywhere.</b> The extraction read {@code MVK_[A-Z0-9_]+} anywhere in the
 * message, but the error-text classes are not {@code MVK_}-prefixed ({@code INVALID_ENTITY},
 * {@code NO_BASE_YEAR}, {@code FIELD_NO_ENUM}, … — the kernel's {@code FehlertextErrorType}). The kernel appends
 * <em>every</em> class the same way: {@code MVKErrorLookup.getErrorMessage} returns
 * {@code getBareErrorMessage(…) + " [" + displayKey + "]"}, so the terminal bracket is the general grammar and
 * the {@code MVK_} scan was one family of it. {@link #aConditionRejectionStillCarriesItsMvkCode} is the control
 * that the general reading did not cost the specific one.
 */
class ErrorTextRejectionEnvelopeTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** A parameter naming an entity the model does not have — the kernel's {@code INVALID_ENTITY}. */
    private static final String BAD_PARAM_TEXT = "See $Nonexistent$";
    private static final String INVALID_ENTITY = "INVALID_ENTITY";

    // ── rule add: the reported case, both with and without --dry-run ────────────────────────────────

    @Test
    void ruleAddDryRunReportsTheErrorTextRejectionAsAnEnvelope(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", CliTestSupport.orderRuled(dir).toString(),
                "rule", "add", badMessageRuleSpec(dir).toString(), "--dry-run");

        assertRejectedByTheErrorTextGate(res, "/Order/BAD_TEXT");
    }

    @Test
    void ruleAddWithoutDryRunReportsTheSameEnvelopeAndWritesNothing(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        String before = Files.readString(model);

        var res = CliTestSupport.run("-m", model.toString(),
                "rule", "add", badMessageRuleSpec(dir).toString());

        assertRejectedByTheErrorTextGate(res, "/Order/BAD_TEXT");
        assertEquals(before, Files.readString(model), "a rejected rule must leave the model untouched");
    }

    // ── the siblings the sweep turned up, each its own channel before the fix ────────────────────────

    @Test
    void computationAddReportsTheErrorTextRejectionAsAnEnvelope(@TempDir Path dir) throws Exception {
        Path spec = dir.resolve("comp.json");
        Files.writeString(spec, """
                { "computedField": "/Order/Items/Count",
                  "alternatives": [ {"operation": "1"} ],
                  "messages": [ {"locale":"en_US","text":"%s"} ] }""".formatted(BAD_PARAM_TEXT));

        var res = CliTestSupport.run("-m", CliTestSupport.orderRuled(dir).toString(),
                "computation", "add", spec.toString(), "--dry-run");

        assertRejectedByTheErrorTextGate(res, "/Order/Items/CountComp");
    }

    /**
     * {@code apply}'s terminal gate already reported through the envelope, so this row exists for a different
     * reason than the two above: it fixes WHERE the finding lives. The aggregate's own {@code diagnostics} is
     * empty by design — the gate's verdict rides its nested {@code model validate} envelope in
     * {@code data.results[]}, and hoisting a copy would state one finding at two loci. What this row locks is
     * that the nested diagnostic gains the class and the rule path from the same extraction fix, so the
     * aggregate route reaches the same structured answer by its own shape.
     */
    @Test
    void theApplyTerminalGateCarriesTheKernelsOwnDiagnostics(@TempDir Path dir) throws Exception {
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ { "target": "rule", "op": "add", "field": "/Order/Items/Count", "group": "/Order",
                    "condition": "GroupFilled(Items) And [Count] <= 0", "code": "BAD_TEXT",
                    "messages": [ {"locale":"en_US","text":"%s"} ] } ]""".formatted(BAD_PARAM_TEXT));

        var res = CliTestSupport.run("-m", CliTestSupport.orderRuled(dir).toString(),
                "apply", ops.toString(), "--dry-run");

        JsonNode env = M.readTree(res.out());
        assertEquals("rejected", env.get("outcome").asText(), res.out());
        assertFalse(env.get("valid").asBoolean(), res.out());
        assertEquals(0, env.path("diagnostics").size(),
                () -> "the aggregate states the verdict; the gate's own envelope states why: " + res.out());

        JsonNode gate = env.path("data").path("results").path(1);
        assertEquals("rejected", gate.path("outcome").asText(), res.out());
        JsonNode d = theOnlyDiagnostic(gate);
        assertEquals(INVALID_ENTITY, d.path("code").asText(null), res.out());
        assertEquals("/Order/BAD_TEXT", d.path("where").path("rule").asText(null), res.out());
    }

    /** The stored-model route: an existing rule whose error text is the inconsistency. */
    @Test
    void modelCheckCarriesTheClassAndTheRulePath(@TempDir Path dir) throws Exception {
        Path model = modelWithAStoredBadErrorText(dir);

        var res = CliTestSupport.run("-m", model.toString(), "model", "check");

        JsonNode env = M.readTree(res.out());
        assertFalse(env.get("valid").asBoolean(), res.out());
        JsonNode d = theOnlyDiagnostic(env);
        assertEquals(INVALID_ENTITY, d.path("code").asText(null), res.out());
        assertEquals("/Order/BAD_TEXT", d.path("where").path("rule").asText(null), res.out());
    }

    // ── controls ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The positive control for the widened extraction: a rejected <em>condition</em> still reports its
     * {@code MVK_*} class. Without this row, reading the terminal bracket instead of scanning for
     * {@code MVK_} would be free to break the family that already worked.
     */
    @Test
    void aConditionRejectionStillCarriesItsMvkCode(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", CliTestSupport.orderRuled(dir).toString(),
                "rule", "check", "--field", "/Order/Items/Sku", "--condition", "FieldFilled(Sku) And",
                "--code", "C9");

        JsonNode env = M.readTree(res.out());
        assertFalse(env.get("valid").asBoolean(), res.out());
        assertTrue(theOnlyDiagnostic(env).path("code").asText("").startsWith("MVK_"), res.out());
    }

    /** …and the fixture can be clean: the same spec with a plain message is accepted and written. */
    @Test
    void aPlainMessageStillApplies(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("good.json");
        Files.writeString(spec, ruleSpec("Nothing interpolated here."));

        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());

        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The whole contract of item 1 in one place: the verdict is on <b>stdout</b> as the standard envelope, it
     * says the kernel arbitrated it, and it carries the kernel's own class and locus rather than prose.
     */
    private static void assertRejectedByTheErrorTextGate(CliTestSupport.Result res, String rulePath)
            throws IOException {
        assertFalse(res.out().isBlank(), () -> "the verdict must be on stdout, not stderr: " + res.err());
        JsonNode env = M.readTree(res.out());
        assertEquals("rejected", env.get("outcome").asText(), res.out());
        assertFalse(env.get("ok").asBoolean(), res.out());
        assertFalse(env.get("valid").asBoolean(), res.out());
        assertEquals("KERNEL_CONFIRMED", env.path("verification").asText(null), res.out());

        JsonNode d = theOnlyDiagnostic(env);
        assertEquals(INVALID_ENTITY, d.path("code").asText(null), res.out());
        assertEquals(rulePath, d.path("where").path("rule").asText(null), res.out());
        assertTrue(d.path("summary").asText().contains("Nonexistent"),
                () -> "the kernel's own words name the offending parameter: " + res.out());
    }

    private static JsonNode theOnlyDiagnostic(JsonNode envelope) {
        JsonNode diagnostics = envelope.path("diagnostics");
        assertEquals(1, diagnostics.size(), () -> "expected exactly one diagnostic in " + envelope);
        return diagnostics.get(0);
    }

    private static Path badMessageRuleSpec(Path dir) throws IOException {
        Path spec = dir.resolve("bad-message.json");
        Files.writeString(spec, ruleSpec(BAD_PARAM_TEXT));
        return spec;
    }

    private static String ruleSpec(String message) {
        return """
                { "field": "/Order/Items/Count", "group": "/Order",
                  "condition": "GroupFilled(Items) And [Count] <= 0", "code": "BAD_TEXT",
                  "messages": [ {"locale":"en_US","text":"%s"} ] }""".formatted(message);
    }

    /**
     * A model that already <em>stores</em> the invalid error text. It cannot be authored through the tool — the
     * gate this test is about refuses it — so the valid rule is persisted first and its message text swapped,
     * which is exactly how such a model reaches {@code model check} in practice (hand-edited, or written by an
     * older tool).
     */
    private static Path modelWithAStoredBadErrorText(Path dir) throws IOException {
        Path model = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("placeholder.json");
        String placeholder = "PLACEHOLDER_MESSAGE";
        Files.writeString(spec, ruleSpec(placeholder));
        var added = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertEquals(0, added.code(), added.err());

        String stored = Files.readString(model);
        assertTrue(stored.contains(placeholder), "the placeholder must be in the persisted model");
        Files.writeString(model, stored.replace(placeholder, BAD_PARAM_TEXT));
        return model;
    }
}
