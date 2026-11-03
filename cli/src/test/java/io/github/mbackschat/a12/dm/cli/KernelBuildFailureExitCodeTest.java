package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The USER-VISIBLE half of the build-failure fix: a model the kernel cannot construct exits <b>1
 * {@code rejected}</b>, never <b>70 {@code error}</b>.
 *
 * <p><b>Why this file exists, and what writing it found.</b> The library side of that fix was well covered —
 * the adapter wraps the throw, {@code ModelLoader} folds it into a notification, the draft gate keeps
 * publishing no receipt — but nothing asserted that any of it reached the USER, so a cold review logged the
 * hole. Writing the assertion immediately turned up a second whole-model gate that had NOT been fixed:
 * {@code KernelModelPreparation.ExpandedModel.requireConsistent}, which every strict reader loads through,
 * let the typed exception escape into a CLI reader's {@code catch (RuntimeException)}. Being neither type
 * {@code failOrRethrowDomainRejection} rethrows, it degraded to an unstructured text line at exit 2 — while
 * the same verb gave an ordinary kernel-invalid model a JSON envelope at exit 1. One input class, two shapes.
 * The fix went to that shared gate rather than to the CLI seam, and both arms below now hold.
 *
 * <p><b>Two members, because one is a symptom and two are a class.</b> A blank temporal {@code format} is the
 * case that was reported; a duplicate child name in one group is the sibling that was predicted from the
 * mechanism and then found. Both are refused by the kernel THROWING inside {@code checkConsistency} rather
 * than by reporting a structured problem, which is what makes them one class and not two bugs
 * (KERNEL-FINDINGS KF259). <i>Corrected 2026-11-03:</i> kernel 31.1.1 admits an empty format, reading it as
 * absent (KF658), and reports a duplicate child name as a structured problem, so neither throws any more. The
 * two members are now a rule naming no error field and a computation naming no computed field, which 31.1.1
 * reads and then cannot build (KF654).
 *
 * <p><b>The negative assertions carry the claim.</b> Exit 1 alone would pass on a lucky mapping; what is
 * actually being locked is that the envelope is not the internal-defect one, so {@code RK_INTERNAL_ERROR},
 * outcome {@code error} and exit 70 are each excluded by name.
 */
class KernelBuildFailureExitCodeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private record BuildFailure(String name, String json) { }

    /** A model whose only defect is that the kernel's own converter refuses to build it. */
    private static String model(String elements) {
        return """
                { "header": { "id": "bf", "modelType": "document", "modelVersion": "29.4.0",
                    "locales": [ { "code": "en_US" } ], "labels": [], "annotations": [], "modelReferences": [] },
                  "content": {
                    "modelInfo": { "name": "bf", "variant": "cockpit", "revision": "r", "immutable": false },
                    "modelConfig": { "decimalSeparator": ".", "timeZone": "Europe/Berlin",
                      "conditionLanguage": { "code": "en_US" }, "fieldRefByShortNameAllowed": true },
                    "modelRoot": { "rootGroups": [ { "type": "Group", "id": "/R", "name": "R", "Group": {
                      "repeatability": 1, "elements": [ %s ] } } ] } } }
                """.formatted(elements);
    }

    private static final String LABEL =
            "{ \"type\": \"Field\", \"id\": \"/R/A\", \"name\": \"A\", "
                    + "\"Field\": { \"fieldType\": { \"type\": \"StringType\" } } }";

    /** A rule named {@code name}. */
    private static String rule(String name) {
        return """
                { "type": "Rule", "id": "/R/%s", "name": "%s", "Rule": {
                  "errorEntityRelPath": "../A", "errorCode": "%s",
                  "errorCondition": "FieldNotFilled(/R/A)", "severity": "ERROR",
                  "errorMessage": [ { "locale": "en_US", "text": "x" } ] } }
                """.formatted(name, name, name);
    }

    private static final List<BuildFailure> CASES = List.of(
            // Kernel 31.1.1 reads a rule with no error field and dereferences the missing reference while building.
            new BuildFailure("a rule naming no error field",
                    model(LABEL + ", " + rule("Lost").replace("\"errorEntityRelPath\": \"../A\", ", ""))),
            // The sibling on the other declaration kind that names a target by reference.
            new BuildFailure("a computation naming no computed field",
                    model(LABEL + ", { \"type\": \"Computation\", \"id\": \"/R/AComp\", \"name\": \"AComp\", "
                            + "\"Computation\": { \"computationAlternatives\": [ { \"operation\": \"\\\"x\\\"\" } ], "
                            + "\"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } }")));

    /**
     * {@code model check} REPORTS validity, so a build failure is {@code outcome: read} with {@code valid:
     * false} and the kernel diagnostic — exit 1 because the model is invalid, not because the call was refused.
     */
    @Test
    void theReportingVerbCallsItInvalidRatherThanRaisingAToolDefect(@TempDir Path dir) throws Exception {
        for (BuildFailure testCase : CASES) {
            CliTestSupport.Result res = run(dir, testCase, "check");
            String all = res.out() + "\n" + res.err();

            assertEquals(1, res.code(), () -> testCase.name()
                    + ": a model the kernel refuses to build is INVALID INPUT (exit 1), not an internal tool "
                    + "defect (exit 70):\n" + all);
            assertNoToolDefect(testCase, all);

            JsonNode env = M.readTree(res.out());
            assertEquals("read", env.path("outcome").asText(),
                    () -> testCase.name() + ": model check reports rather than refuses: " + res.out());
            assertFalse(env.path("valid").asBoolean(),
                    () -> testCase.name() + ": the model must be reported invalid: " + res.out());
            assertEquals("KERNEL", env.path("diagnostics").path(0).path("source").asText(),
                    () -> testCase.name() + ": the KERNEL is what refused: " + res.out());
        }
    }

    /**
     * {@code model read} LOADS strictly, so the same input is the central structured rejection — exactly what
     * an ordinary kernel-invalid model already got.
     *
     * <p><b>This arm is the one that was broken, and it is why the fix went to the shared gate.</b> Until
     * 2026-09-07 a build failure reached this verb as an unstructured text line at exit 2, while a model
     * rejected by an ordinary MVK rule got a JSON envelope at exit 1 — one input class, two shapes, on the same
     * verb. The cause was two whole-model gates disposing of the same event differently; only
     * {@code ModelLoader} folded it, and this verb loads through the adapter's gate instead.
     */
    @Test
    void theStrictLoadVerbGivesTheSameStructuredRejectionAsAnyOtherInvalidModel(@TempDir Path dir)
            throws Exception {
        for (BuildFailure testCase : CASES) {
            CliTestSupport.Result res = run(dir, testCase, "read");
            String all = res.out() + "\n" + res.err();

            assertEquals(1, res.code(), () -> testCase.name()
                    + ": a strict reader rejects an unbuildable model at exit 1, the same as any other "
                    + "kernel-invalid one — exit 2 would call the caller's input unreadable, and 70 would call "
                    + "it our defect:\n" + all);
            assertNoToolDefect(testCase, all);

            JsonNode env = M.readTree(res.out());
            assertEquals("rejected", env.path("outcome").asText(),
                    () -> testCase.name() + ": expected the central structured rejection: " + res.out());
            assertFalse(env.path("written").asBoolean(), () -> testCase.name() + ": nothing is written");
        }
    }

    private static CliTestSupport.Result run(Path dir, BuildFailure testCase, String op) throws Exception {
        Path model = dir.resolve(testCase.name().replaceAll("\\W+", "-") + "-" + op + ".json");
        Files.writeString(model, testCase.json());
        return CliTestSupport.run("-m", model.toString(), "model", op);
    }

    /** The kernel's own reason reaches the user, and the internal-defect envelope never does. */
    private static void assertNoToolDefect(BuildFailure testCase, String all) {
        assertFalse(all.contains("RK_INTERNAL_ERROR"),
                () -> testCase.name() + ": the internal-defect code must not appear:\n" + all);
        assertTrue(all.contains("the kernel could not build this model"),
                () -> testCase.name() + ": the kernel's own reason must reach the user:\n" + all);
    }

    /**
     * The control that keeps the assertions above from passing vacuously: the same shapes MINUS the defect
     * load cleanly. Without it, a `model check` broken in some unrelated way would satisfy every "is a
     * rejection" assertion for the wrong reason.
     */
    @Test
    void theSameModelWithoutTheDefectLoadsCleanly(@TempDir Path dir) throws Exception {
        Path ok = dir.resolve("ok.json");
        Files.writeString(ok, model(LABEL + ", "
                + "{ \"type\": \"Field\", \"id\": \"/R/D\", \"name\": \"D\", \"Field\": { \"fieldType\": "
                + "{ \"type\": \"DateType\", \"DateType\": { \"format\": \"yyyy-MM-dd\" } } } }, "
                + rule("One") + ", " + rule("Two")));

        CliTestSupport.Result res = CliTestSupport.run("-m", ok.toString(), "model", "check");
        assertEquals(0, res.code(), () -> "the control model must be accepted: " + res.out() + res.err());
    }
}
