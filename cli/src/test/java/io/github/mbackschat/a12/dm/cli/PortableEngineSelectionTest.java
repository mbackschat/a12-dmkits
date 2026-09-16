package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.consistency.CheckerIdentityKt;

/**
 * {@code model check --engine} — the caller selects the checking engine, and nothing else ever does (F1-a).
 *
 * <p><b>The two engines are held to DIFFERENT contracts on purpose, which is what makes these tests
 * meaningful.</b> On a model A12 accepts, the kernel answers {@code valid:true} at exit 0 and the portable
 * checker answers INCONCLUSIVE at exit 3 — it cannot report a model VALID at all. So the engines visibly
 * disagree on the same input, and a fallback in either direction would show up here as the other engine's
 * answer rather than as a subtle difference. A test suite where both engines agreed could not see that.
 *
 * <p>Substitution has a second, stronger control that is not a test in this file: the envelope refuses to be
 * built when the executed engine is not the requested one
 * ({@code ConsistencyEvidence.requestedBy}), so a dispatch that ran the wrong engine cannot publish anything
 * at all — not even a correct-looking verdict.
 */
class PortableEngineSelectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The fixture's own condition, and the same condition pointed at a field the model does not declare. */
    private static final String SOUND_CONDITION = "FieldFilled(PostalCode)";
    private static final String DANGLING_CONDITION = "FieldFilled(NoSuchFieldHere)";

    private static Path soundModel(Path dir) throws IOException {
        return CliTestSupport.orderRuled(dir);
    }

    /**
     * The same fixture with one condition naming an undeclared entity — a model both engines reject.
     *
     * <p>Mutated as TEXT rather than authored through the kits, because the kits' gate would refuse to build
     * it: a dangling reference is exactly what the kernel rejects. {@code model check} reads hand-written
     * models tolerantly, which is the only way either engine gets to see one.
     */
    private static Path danglingModel(Path dir) throws IOException {
        String sound = CliTestSupport.orderRuledJson();
        assertEquals(1, countOf(sound, SOUND_CONDITION),
                "the fixture must carry that condition exactly once, or this mutation is not the one described");
        Path file = dir.resolve("order-dangling.dm.json");
        Files.writeString(file, sound.replace(SOUND_CONDITION, DANGLING_CONDITION));
        return file;
    }

    private static int countOf(String haystack, String needle) {
        return (haystack.length() - haystack.replace(needle, "").length()) / needle.length();
    }

    private static JsonNode envelopeOf(CliTestSupport.Result result) throws IOException {
        return MAPPER.readTree(result.out());
    }

    @Test
    void theDefaultAndAnExplicitKernelRequestBothRecordAKernelReceipt(@TempDir Path dir) throws Exception {
        Path model = soundModel(dir);

        var implicit = CliTestSupport.run("-m", model.toString(), "model", "check");
        var explicit = CliTestSupport.run("-m", model.toString(), "model", "check", "--engine", "kernel");

        assertEquals(0, implicit.code(), () -> "the fixture is kernel-valid: " + implicit.out());
        assertEquals(0, explicit.code(), () -> "an explicit kernel request is the same request: " + explicit.out());
        // Byte-identical, which is the strongest available statement that naming the default changed nothing.
        assertEquals(implicit.out(), explicit.out(), "naming the default engine must change no output");

        JsonNode env = envelopeOf(implicit);
        assertTrue(env.path("valid").asBoolean(), () -> "kernel verdict on a valid model: " + implicit.out());
        assertEquals("KERNEL_CONFIRMED", env.path("verification").asText());
        // The absent option IS a request for the kernel, so the receipt is present on the default path too —
        // otherwise the default shape would be the one shape no control could assert.
        assertEquals("A12_KERNEL", env.path("engineRequested").asText());
        assertEquals("A12_KERNEL", env.path("engineExecuted").asText());
    }

    @Test
    void theEnginesDisagreeOnASoundModelAndThePortableAnswerIsNotReplaced(@TempDir Path dir) throws Exception {
        Path model = soundModel(dir);

        var kernel = CliTestSupport.run("-m", model.toString(), "model", "check", "--engine", "kernel");
        var portable = CliTestSupport.run("-m", model.toString(), "model", "check", "--engine", "portable");

        // The disagreement is the control: the kernel calls this model valid, so a portable path that fell
        // back would exit 0 with valid:true. Exit 3 is only reachable by the portable answer surviving.
        assertEquals(0, kernel.code(), () -> "kernel: " + kernel.out());
        assertEquals(3, portable.code(), () -> "portable ran and concluded nothing: " + portable.out());

        JsonNode env = envelopeOf(portable);
        assertTrue(env.path("ok").asBoolean(), "the operation succeeded — the checker ran and reported");
        assertTrue(env.path("valid").isMissingNode(),
                () -> "an inconclusive result must publish NO valid key, in either direction: " + portable.out());
        assertEquals("PORTABLE_CHECKED", env.path("verification").asText());
        assertEquals("PORTABLE_CHECKER", env.path("engineRequested").asText());
        assertEquals("PORTABLE_CHECKER", env.path("engineExecuted").asText());
        // The checker's own published report, unaltered — including the two members it publishes precisely so
        // a consumer never has to infer why a check concluded nothing.
        assertEquals("INCONCLUSIVE", env.path("data").path("status").asText());
        assertFalse(env.path("data").path("complete").asBoolean(), "an incomplete checker says so");
        assertTrue(env.path("data").path("gaps").size() > 0, "and discloses what it did not check");
    }

    @Test
    void aDanglingReferenceIsRejectedByEitherEngineWithItsOwnProvenance(@TempDir Path dir) throws Exception {
        Path model = danglingModel(dir);

        var kernel = CliTestSupport.run("-m", model.toString(), "model", "check", "--engine", "kernel");
        var portable = CliTestSupport.run("-m", model.toString(), "model", "check", "--engine", "portable");

        assertEquals(1, kernel.code(), () -> "the kernel rejects a dangling entity: " + kernel.out());
        JsonNode kernelEnv = envelopeOf(kernel);
        assertFalse(kernelEnv.path("valid").asBoolean());
        // The kernel's failure stays the kernel's: it is never re-answered by the portable checker, which is
        // the no-fallback rule in the direction that would look most helpful.
        assertEquals("KERNEL_CONFIRMED", kernelEnv.path("verification").asText());
        assertEquals("A12_KERNEL", kernelEnv.path("engineExecuted").asText());

        assertEquals(1, portable.code(), () -> "the portable checker rejects it too: " + portable.out());
        JsonNode portableEnv = envelopeOf(portable);
        assertFalse(portableEnv.path("valid").asBoolean());
        assertEquals("PORTABLE_CHECKED", portableEnv.path("verification").asText());
        assertEquals("INVALID", portableEnv.path("data").path("status").asText());
        boolean namesTheA12Code = false;
        for (JsonNode finding : portableEnv.path("data").path("findings")) {
            namesTheA12Code |= "MVK_INVALID_ENTITY".equals(finding.path("code").asText());
        }
        assertTrue(namesTheA12Code, () -> "the portable finding carries A12's own code: " + portable.out());
    }

    @Test
    void theReceiptTakesEachEnginesVersionFromThatEngine(@TempDir Path dir) throws Exception {
        Path model = soundModel(dir);

        JsonNode portable = envelopeOf(
                CliTestSupport.run("-m", model.toString(), "model", "check", "--engine", "portable"));
        JsonNode kernel = envelopeOf(
                CliTestSupport.run("-m", model.toString(), "model", "check", "--engine", "kernel"));

        // Equal to the checker artifact's OWN baked version. A literal in CLI source would pass an equality
        // against itself, so the value is compared against the other module's constant rather than a string.
        assertEquals(CheckerIdentityKt.CHECKER_VERSION, portable.path("engineVersion").asText(),
                "the portable engine's version must come from the checker that answered");
        // And the two engines do not report the same version, which is what shows the value tracks the engine
        // rather than the tool: one tool build, two differently-versioned engines.
        assertNotEquals(portable.path("engineVersion").asText(), kernel.path("engineVersion").asText(),
                "a receipt reporting one version for both engines is describing dmtool, not the engine");
    }

    @Test
    void anUnknownEngineIsAUsageErrorThatNamesBothSpellings(@TempDir Path dir) throws Exception {
        Path model = soundModel(dir);

        var result = CliTestSupport.run("-m", model.toString(), "model", "check", "--engine", "a12");

        assertEquals(2, result.code(), () -> "a bad option value is a usage error, not a tool failure: "
                + result.out() + result.err());
        String said = result.out() + result.err();
        assertTrue(said.contains("kernel") && said.contains("portable"),
                () -> "the refusal must teach the vocabulary it rejected: " + said);
    }
}
