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

/** Observation capture success is dispatch plus artifact write, independently of each captured child verdict. */
class BatchObservationNegativeControlTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** A capture pairing an accepted candidate with one the kernel rejects — the separating matrix. */
    private static Path matrix(Path dir, Path model) throws Exception {
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [
                  {"id":"positive","verb":"rule","args":["check","-m","%s","--field",
                    "/Subscription/Addons/MonthlyFee","--condition","[MonthlyFee] > 1000","--code","CAP"]},
                  {"id":"negative-control","verb":"rule","args":["check","-m","%s","--field",
                    "/Subscription/Addons/MonthlyFee","--condition","Abs(5) > 1","--code","CAP"]}
                ]
                """.formatted(model.toString(), model.toString()));
        return ops;
    }

    @Test
    void aRejectedCandidateStillProducesASuccessfulAuthoritativeCapture(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Path observations = dir.resolve("observations.json");

        CliTestSupport.Result res = CliTestSupport.run("batch", matrix(dir, model).toString(),
                "--observations", observations.toString());

        assertEquals(0, res.code(), () -> "full dispatch plus artifact write is a successful capture: " + res.out());
        JsonNode envelope = M.readTree(res.out());
        assertEquals("completed", envelope.path("outcome").asText(), res::out);
        assertTrue(envelope.path("ok").asBoolean(), () -> "the observation operation did its job: " + res.out());
        assertTrue(envelope.path("written").asBoolean(), () -> "the capture itself succeeded: " + res.out());
        assertEquals(observations.toAbsolutePath().normalize().toString(), envelope.path("output").asText());
        assertFalse(envelope.path("data").path("allExitedZero").asBoolean(),
                () -> "the independent child-exit aggregate retains the rejected candidate: " + res.out());
        assertFalse(envelope.path("data").has("allSucceeded"), "the ambiguous pre-CG9 key must be removed");

        JsonNode recorded = M.readTree(Files.readString(observations)).path("observations");
        assertEquals(2, recorded.size(), recorded::toPrettyString);
        assertTrue(recorded.get(0).path("valid").asBoolean(), recorded::toPrettyString);
        assertTrue(recorded.get(1).path("ok").asBoolean(),
                () -> "the gate RAN for the negative control — `ok` is execution: " + recorded.toPrettyString());
        assertFalse(recorded.get(1).path("valid").asBoolean(),
                () -> "…and rejected it — `valid` is the verdict, which is what the control measures: "
                        + recorded.toPrettyString());
        assertTrue(recorded.get(1).path("codes").toString().contains("MVK_NO_CONST"),
                () -> "the rejection reason is retained: " + recorded.toPrettyString());
    }

    /**
     * <b>The root cause is not observation-specific</b>, so neither is the fix: ordinary {@code batch} folds child
     * exits the same way, and a {@code rule check} child exiting 1 is a VERDICT (`ok:true`, `valid:false`), not a
     * failure. Saying "one or more failed" about it conflated the two axes the envelope contract exists to keep
     * apart, in the one line an operator reads first.
     */
    @Test
    void anOrdinaryBatchSeparatesAFailedChildFromANegativeVerdict(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());

        CliTestSupport.Result res = CliTestSupport.run("batch", matrix(dir, model).toString());

        assertEquals(1, res.code(), res::out);
        JsonNode envelope = M.readTree(res.out());
        assertFalse(envelope.path("data").path("allExitedZero").asBoolean(), res::out);
        assertFalse(envelope.path("data").has("allSucceeded"), res::out);
        String summary = envelope.path("summary").asText();
        assertTrue(summary.contains("0 failed"),
                () -> "nothing failed here — the rejected candidate is a verdict, and the summary must not call "
                        + "it a failure: " + summary);
        assertTrue(summary.contains("1 reported a negative verdict or comparison"),
                () -> "…and must say what the nonzero child actually reported: " + summary);

        JsonNode child = envelope.path("data").path("results").get(1);
        assertEquals(1, child.path("exit").asInt(), res::out);
        assertTrue(child.path("result").path("ok").asBoolean(),
                () -> "the discriminator is the child's own `ok`: it ran fine: " + child);
        assertFalse(child.path("result").path("valid").asBoolean(),
                () -> "…and reported an invalid verdict: " + child);
    }

    @Test
    void aGenuinelyFailedChildIsStillCalledAFailure(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Path ops = dir.resolve("bad-ops.json");
        // An unknown field: `rule check` refuses before the kernel gate (`ok:false`), a real failure.
        Files.writeString(ops, """
                [{"id":"broken","verb":"rule","args":["check","-m","%s","--field","/Subscription/Nope",
                  "--condition","[MonthlyFee] > 1000","--code","CAP"]}]
                """.formatted(model.toString()));

        CliTestSupport.Result res = CliTestSupport.run("batch", ops.toString());

        assertEquals(1, res.code(), res::out);
        String summary = M.readTree(res.out()).path("summary").asText();
        assertTrue(summary.contains("1 failed"),
                () -> "a refused child IS a failure — the positive control for the discriminator: " + summary);
        assertTrue(summary.contains("0 reported a negative verdict or comparison"), () -> summary);
    }

    /**
     * The other disposition, so "exit 1 means a rejected candidate" is not read as universal: a capture that never
     * ran writes nothing and says so with a different {@code outcome}.
     */
    @Test
    void aRefusedCaptureIsADifferentOutcomeAndWritesNothing(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Path ops = dir.resolve("untagged.json");
        // No `id`: observation capture cannot correlate the child, so it refuses before dispatching anything.
        Files.writeString(ops, """
                [{"verb":"rule","args":["check","-m","%s","--field","/Subscription/Addons/MonthlyFee",
                  "--condition","[MonthlyFee] > 1000","--code","CAP"]}]
                """.formatted(model.toString()));
        Path observations = dir.resolve("observations.json");

        CliTestSupport.Result res = CliTestSupport.run("batch", ops.toString(),
                "--observations", observations.toString());

        assertEquals(1, res.code(), res::out);
        assertEquals("rejected", M.readTree(res.out()).path("outcome").asText(), res::out);
        assertFalse(M.readTree(res.out()).path("written").asBoolean(), res::out);
        assertFalse(Files.exists(observations), "a refused capture leaves no artifact to mistake for one");
    }

    @Test
    void bothSelfDescribingSurfacesCarryTheModeSpecificSuccessRule() throws Exception {
        CliTestSupport.Result help = CliTestSupport.run("batch", "--help");
        assertEquals(0, help.code(), help::err);
        String helpText = help.out().replaceAll("\\s+", " ");
        assertTrue(helpText.contains("A nonzero child is NOT always a failed child"),
                () -> "`batch --help` must state the CLASS, not only the observation instance: " + help.out());
        assertTrue(helpText.contains("observation mode exits 0 after full dispatch and artifact write"),
                () -> "`batch --help` must state the observation-mode success rule: " + help.out());

        CliTestSupport.Result schema = CliTestSupport.run("schema", "batch");
        assertEquals(0, schema.code(), schema::err);
        String description = M.readTree(schema.out()).path("input").path("description").asText();
        assertTrue(description.contains("NONZERO CHILD IS NOT NECESSARILY A FAILED CHILD"),
                () -> "`schema batch` must carry the same class-level rule: " + description);
        assertTrue(description.contains("data.allExitedZero"),
                () -> "the independent child-exit aggregate must be named: " + description);
        assertTrue(description.contains("observation mode") && description.contains("exits 0"),
                () -> "the observation-mode exit rule must be explicit: " + description);
    }
}
