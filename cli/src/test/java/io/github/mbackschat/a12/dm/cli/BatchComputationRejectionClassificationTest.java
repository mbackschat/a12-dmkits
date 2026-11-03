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
 * A kernel-REJECTED child is an executed negative verdict, not a failed child — the {@code REJECTION} family
 * that {@link BatchObservationNegativeControlTest} cannot reach.
 *
 * <p>That test's negative control is a {@code rule check}, which reports a negative verdict as
 * {@code ok:true, valid:false}; the summary fold therefore passed while reading {@code ok} alone. A
 * {@code computation add --dry-run} the kernel refuses is the other shape: the gate RAN and arbitrated, so the
 * envelope carries {@code valid:false} plus {@code KERNEL_CONFIRMED} — but the operation did not succeed, so
 * {@code ok} is false by construction. Classifying on {@code ok} alone called that deliberate control a failure.
 */
class BatchComputationRejectionClassificationTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** A computation whose operation reads the field it computes — the kernel's self-reference rejection. */
    private static Path selfReferenceSpec(Path dir) throws Exception {
        Path spec = dir.resolve("self-reference.json");
        Files.writeString(spec, """
                {
                  "computedField": "/Subscription/Billing/EffectiveFee",
                  "name": "EffectiveFeeSelfReference",
                  "alternatives": [{"operation": "[/Subscription/Billing/EffectiveFee]"}],
                  "messages": [{"locale": "en_US", "text": "Effective fee self-reference."},
                               {"locale": "de_DE", "text": "Effektivgebühr verweist auf sich selbst."}]
                }
                """);
        return spec;
    }

    /** The accepted control that proves the fixture and the route are live. */
    private static Path otherFieldSpec(Path dir) throws Exception {
        Path spec = dir.resolve("control.json");
        Files.writeString(spec, """
                {
                  "computedField": "/Subscription/Billing/EffectiveFee",
                  "name": "EffectiveFeeFromBase",
                  "alternatives": [{"operation": "[/Subscription/Billing/BaseFee]"}],
                  "messages": [{"locale": "en_US", "text": "Effective fee from base fee."},
                               {"locale": "de_DE", "text": "Effektivgebühr aus der Basisgebühr."}]
                }
                """);
        return spec;
    }

    private static Path matrix(Path dir, Path model) throws Exception {
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [
                  {"id":"self-reference","verb":"computation",
                   "args":["add","-m","%s","%s","--dry-run"]},
                  {"id":"other-field-control","verb":"computation",
                   "args":["add","-m","%s","%s","--dry-run"]}
                ]
                """.formatted(model, selfReferenceSpec(dir), model, otherFieldSpec(dir)));
        return ops;
    }

    private static Path model(Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        return model;
    }

    @Test
    void aKernelRejectedChildIsANegativeVerdictNotAFailure(@TempDir Path dir) throws Exception {
        Path model = model(dir);

        CliTestSupport.Result res = CliTestSupport.run("batch", matrix(dir, model).toString());

        assertEquals(1, res.code(), res::out);
        JsonNode envelope = M.readTree(res.out());
        JsonNode rejected = envelope.path("data").path("results").get(0);
        assertEquals(1, rejected.path("exit").asInt(), res::out);
        assertEquals("rejected", rejected.path("result").path("outcome").asText(),
                () -> "the fixture must reach the real kernel gate: " + rejected);
        assertFalse(rejected.path("result").path("valid").asBoolean(true),
                () -> "…and be arbitrated negative there: " + rejected);
        assertEquals("KERNEL_CONFIRMED", rejected.path("result").path("verification").asText(), () -> "" + rejected);
        assertTrue(rejected.path("result").path("diagnostics").toString()
                        .contains("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"),
                () -> "the exact kernel code rides the child: " + rejected);

        JsonNode control = envelope.path("data").path("results").get(1);
        assertEquals(0, control.path("exit").asInt(),
                () -> "the different-field control proves the route is active: " + control);
        assertTrue(control.path("result").path("ok").asBoolean(), () -> "" + control);

        String summary = envelope.path("summary").asText();
        assertTrue(summary.contains("0 failed"),
                () -> "an executed kernel rejection is not a failed child: " + summary);
        assertTrue(summary.contains("1 reported a negative verdict or comparison"),
                () -> "…it is the verdict the control was written to capture: " + summary);
    }

    @Test
    void observationCaptureReportsTheSameSplit(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path observations = dir.resolve("observations.json");

        CliTestSupport.Result res = CliTestSupport.run("batch", matrix(dir, model).toString(),
                "--observations", observations.toString());

        assertEquals(0, res.code(), res::out);
        JsonNode envelope = M.readTree(res.out());
        assertTrue(envelope.path("ok").asBoolean(), res::out);
        assertTrue(envelope.path("written").asBoolean(), res::out);
        assertFalse(envelope.path("data").path("allExitedZero").asBoolean(), res::out);
        String summary = envelope.path("summary").asText();
        assertTrue(summary.contains("0 failed") && summary.contains("1 reported a negative verdict or comparison"),
                () -> summary);

        JsonNode recorded = M.readTree(Files.readString(observations)).path("observations");
        assertEquals(2, recorded.size(), recorded::toPrettyString);
        assertFalse(recorded.get(0).path("valid").asBoolean(true), recorded::toPrettyString);
        assertTrue(recorded.get(0).path("codes").toString().contains("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"),
                recorded::toPrettyString);
    }

    /**
     * The positive control for the discriminator: a rejection the CLI's own pre-flight raised holds NO subject
     * verdict (no {@code valid}, no {@code verification}), so it stays a failure.
     */
    @Test
    void aPreflightRejectionWithNoSubjectVerdictIsStillAFailure(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        // A spec missing its computed field: only the CLI's schema pre-flight can refuse it. An unknown computed
        // field no longer serves, because kernel 31.1.1 states it and so gives a verdict (KERNEL-FINDINGS KF652).
        Path spec = dir.resolve("unknown-field.json");
        Files.writeString(spec, """
                {
                  "alternatives": [{"operation": "[/Subscription/Billing/BaseFee]"}],
                  "messages": [{"locale": "en_US", "text": "Unknown computed field."},
                               {"locale": "de_DE", "text": "Unbekanntes berechnetes Feld."}]
                }
                """);
        Path ops = dir.resolve("preflight-ops.json");
        Files.writeString(ops, """
                [{"id":"unknown-field","verb":"computation","args":["add","-m","%s","%s","--dry-run"]}]
                """.formatted(model, spec));

        CliTestSupport.Result res = CliTestSupport.run("batch", ops.toString());

        assertEquals(1, res.code(), res::out);
        JsonNode child = M.readTree(res.out()).path("data").path("results").get(0);
        assertFalse(child.path("result").has("valid"),
                () -> "no gate arbitrated this input, so there is no verdict to report: " + child);
        String summary = M.readTree(res.out()).path("summary").asText();
        assertTrue(summary.contains("1 failed"), () -> summary);
        assertTrue(summary.contains("0 reported a negative verdict or comparison"), () -> summary);
    }
}
