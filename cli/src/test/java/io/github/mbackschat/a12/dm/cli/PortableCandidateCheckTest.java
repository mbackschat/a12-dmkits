package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code rule check --engine portable} — a candidate judged by what IT introduced, not by the model it lands in
 * (F1-b).
 *
 * <p><b>The property this verb has and {@code model check} does not.</b> A candidate is spliced into a model
 * before a whole-model checker can see it, so the checker's answer is about the whole spliced model. Publishing
 * that as the candidate's verdict would reject a perfectly good candidate the moment the model already carried
 * an ERROR of its own — a verdict about another declaration, delivered as a verdict about this candidate, which
 * is an over-rejection. {@link #aSoundCandidateIsNotRejectedForTheModelsOwnPreExistingDefect} is that control,
 * and it is the reason the attribution exists at all.
 */
class PortableCandidateCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The fixture's own sound condition, and the same condition pointed at a field it does not declare. */
    private static final String SOUND = "FieldFilled(PostalCode)";
    private static final String DANGLING = "FieldFilled(NoSuchFieldHere)";

    /** Kernel-valid against this fixture — the demo's own candidate. */
    private static final String CANDIDATE_FIELD = "/Order/Quantity";
    private static final String CANDIDATE_CONDITION = "[/Order/Quantity] < 1";

    private static Path soundModel(Path dir) throws IOException {
        return CliTestSupport.orderRuled(dir);
    }

    /** The same fixture with one STORED rule naming an undeclared entity — a defect the candidate did not cause. */
    private static Path brokenModel(Path dir) throws IOException {
        String sound = CliTestSupport.orderRuledJson();
        assertEquals(1, countOf(sound, SOUND),
                "the fixture must carry that condition exactly once, or this mutation is not the one described");
        Path file = dir.resolve("order-broken.dm.json");
        Files.writeString(file, sound.replace(SOUND, DANGLING));
        return file;
    }

    private static int countOf(String haystack, String needle) {
        return (haystack.length() - haystack.replace(needle, "").length()) / needle.length();
    }

    private static CliTestSupport.Result check(Path model, String condition, String... extra) {
        String[] head = {"-m", model.toString(), "rule", "check", "--field", CANDIDATE_FIELD,
                "--condition", condition, "--code", "RK_DEMO"};
        String[] args = new String[head.length + extra.length];
        System.arraycopy(head, 0, args, 0, head.length);
        System.arraycopy(extra, 0, args, head.length, extra.length);
        return CliTestSupport.run(args);
    }

    private static JsonNode envelopeOf(CliTestSupport.Result result) throws IOException {
        return MAPPER.readTree(result.out());
    }

    @Test
    void theEnginesDisagreeOnASoundCandidateAndThePortableAnswerIsNotReplaced(@TempDir Path dir) throws Exception {
        Path model = soundModel(dir);

        var kernel = check(model, CANDIDATE_CONDITION);
        var portable = check(model, CANDIDATE_CONDITION, "--engine", "portable");

        // The disagreement is the control: the kernel accepts this candidate, so a portable path that fell back
        // would exit 0 with valid:true. Exit 3 is reachable only by the portable answer surviving.
        assertEquals(0, kernel.code(), () -> "the kernel accepts the candidate: " + kernel.out());
        assertEquals(3, portable.code(), () -> "portable ran and concluded nothing: " + portable.out());

        JsonNode env = envelopeOf(portable);
        assertTrue(env.path("valid").isMissingNode(),
                () -> "an inconclusive candidate must publish NO valid key: " + portable.out());
        assertEquals("PORTABLE_CHECKED", env.path("verification").asText());
        assertEquals("PORTABLE_CHECKER", env.path("engineExecuted").asText());
        assertEquals("INCONCLUSIVE", env.path("data").path("status").asText());
        assertEquals(0, env.path("data").path("introduced").size(), "a sound candidate introduces nothing");
    }

    /**
     * The over-rejection control, and F1-b's whole reason for existing: the model is broken, the candidate is
     * not, and the candidate's verdict must not inherit the model's defect.
     */
    @Test
    void aSoundCandidateIsNotRejectedForTheModelsOwnPreExistingDefect(@TempDir Path dir) throws Exception {
        Path model = brokenModel(dir);

        var portable = check(model, CANDIDATE_CONDITION, "--engine", "portable");

        assertEquals(3, portable.code(),
                () -> "the candidate added nothing, so its verdict cannot be a rejection: " + portable.out());
        JsonNode data = envelopeOf(portable).path("data");
        assertEquals("INCONCLUSIVE", data.path("status").asText(), "the EDIT's status, not the model's");
        assertEquals(0, data.path("introduced").size());
        // The model's own defect is disclosed rather than discarded — an author checking a candidate against an
        // already-broken model needs to know that, and the baseline report is where it belongs.
        assertEquals("INVALID", data.path("baseline").path("status").asText(),
                () -> "the baseline's own verdict stays visible: " + portable.out());
        assertTrue(data.path("baseline").path("findings").size() > 0,
                "and so do the findings that justify it");
    }

    @Test
    void aDanglingCandidateIsRejectedByEitherEngineWithItsOwnProvenance(@TempDir Path dir) throws Exception {
        Path model = soundModel(dir);

        var kernel = check(model, DANGLING);
        var portable = check(model, DANGLING, "--engine", "portable");

        assertEquals(1, kernel.code(), () -> "the kernel rejects a dangling reference: " + kernel.out());
        assertEquals("KERNEL_CONFIRMED", envelopeOf(kernel).path("verification").asText());

        assertEquals(1, portable.code(), () -> "the portable checker rejects it too: " + portable.out());
        JsonNode env = envelopeOf(portable);
        assertFalse(env.path("valid").asBoolean());
        assertEquals("PORTABLE_CHECKED", env.path("verification").asText());
        assertEquals("INVALID", env.path("data").path("status").asText());
        // Attributed to the candidate, which is the half a whole-model report could not state.
        assertTrue(env.path("data").path("introduced").size() > 0,
                () -> "the rejection must name what the CANDIDATE introduced: " + portable.out());
    }

    @Test
    void theDefaultAndAnExplicitKernelRequestBothRecordAKernelReceipt(@TempDir Path dir) throws Exception {
        Path model = soundModel(dir);

        var implicit = check(model, CANDIDATE_CONDITION);
        var explicit = check(model, CANDIDATE_CONDITION, "--engine", "kernel");

        assertEquals(implicit.out(), explicit.out(), "naming the default engine must change no output");
        assertEquals("A12_KERNEL", envelopeOf(implicit).path("engineRequested").asText());
        assertEquals("A12_KERNEL", envelopeOf(implicit).path("engineExecuted").asText());
    }
}
