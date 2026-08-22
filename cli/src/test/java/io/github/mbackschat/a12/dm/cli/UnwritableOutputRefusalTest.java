package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>An output that cannot be written refuses in coded form</b> (G62) — the mirror of G61's input side.
 *
 * <p><b>Why it is a separate slice from G61 rather than the same sweep.</b> A read that fails has produced
 * nothing, so a channel swap is the whole change. A write that fails has to answer two further questions, and
 * the answers are what this class pins. (1) {@code written} stays <b>false</b> even when bytes reached the disk,
 * because the published contract defines it as whether "this invocation wrote its <i>selected</i> model or
 * artifact destination" — a half-written bundle is not that destination. (2) A multi-file writer must therefore
 * say what it <i>did</i> leave behind, or the caller cannot clean up; that debris goes in {@code data.written}.
 *
 * <p><b>The source is ENVIRONMENT, not PRECHECK.</b> The model was fine and the checks passed; the filesystem
 * refused, after all the work. Classifying it as a precheck would say the input was bad, and classifying it as
 * INTERNAL would say the tool is broken — which is the mistake the one already-enveloped write failure made:
 * {@code batch --observations} carried {@code RK_INTERNAL_ERROR} ("this is a dmtool defect, not an invalid
 * input") beside its own {@code fix} telling the caller to choose a writable destination. Both cannot be true.
 *
 * <p><b>Recall limit, stated.</b> Unlike the read sweep, this cannot be driven from the live verb list: a write
 * is reached only at the END of an otherwise successful op, so every case needs valid preconditions. The cases
 * below are therefore hand-picked to cover the distinct <i>shapes</i> — the shared mutation tail, a single-file
 * artifact writer, a directory-creating writer, and a multi-file writer that fails part-way — and
 * {@link #noWriteFailureIsStillReportedOnBareStderr()} covers the remaining sites by absence, deliberately
 * over-triggering so a missed one cannot pass quietly.
 */
class UnwritableOutputRefusalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A destination whose parent component is a regular file, so no directory can exist there. */
    private static Path unwritable(Path dir, String name) throws IOException {
        Path blocker = dir.resolve("not-a-directory");
        if (!Files.exists(blocker)) {
            Files.writeString(blocker, "x");
        }
        return blocker.resolve(name);
    }

    /** The coded diagnostics on stdout, or an empty list when stdout carried no envelope at all. */
    private static List<JsonNode> diagnostics(String out) {
        List<JsonNode> found = new ArrayList<>();
        if (out.isBlank()) {
            return found;
        }
        try {
            MAPPER.readTree(out).path("diagnostics").forEach(found::add);
        } catch (Exception notAnEnvelope) {
            return List.of();
        }
        return found;
    }

    /** Assert the one shape every unwritable destination must produce, whatever verb reached it. */
    private static void assertRefusedAsUnwritable(CliTestSupport.Result res, String what) {
        assertEquals(2, res.code(), () -> what + ": an unwritable destination stays exit 2 — it is an "
                + "environment failure, not a rejected input (exit 1) and not a tool defect (exit 70): "
                + res.out() + res.err());
        List<JsonNode> diagnostics = diagnostics(res.out());
        assertFalse(diagnostics.isEmpty(),
                () -> what + ": stdout must carry the envelope, not leave the reason on stderr only: "
                        + res.err());
        JsonNode diagnostic = diagnostics.get(0);
        assertEquals(RkCode.OUTPUT_UNWRITABLE.code(), diagnostic.path("code").asText(),
                () -> what + ": " + diagnostic);
        assertEquals("ENVIRONMENT", diagnostic.path("source").asText(),
                () -> what + ": the model was fine and the disk was not — see this class's note: " + diagnostic);
        assertEquals("ERROR", diagnostic.path("severity").asText(), () -> what + ": " + diagnostic);
        assertTrue(diagnostic.path("fix").asText("").length() > 10,
                () -> what + ": a refusal an agent cannot act on is not corrective: " + diagnostic);
    }

    @Test
    void theSharedMutationTailRefusesInCodedForm(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Order", "--name", "PromoCode", "--kind", "STRING",
                "-o", unwritable(dir, "out.dm.json").toString());

        assertRefusedAsUnwritable(res, "field add -o (the tail every editing verb ends through)");
        assertFalse(MAPPER.readTree(res.out()).path("written").asBoolean(true),
                () -> "nothing was applied: " + res.out());
    }

    @Test
    void aSingleFileArtifactWriterRefusesInCodedForm(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "model", "expand", "-o", unwritable(dir, "expanded.dm.json").toString());

        assertRefusedAsUnwritable(res, "model expand -o (an artifact writer, not the mutation tail)");
    }

    @Test
    void aWriterThatCreatesItsOwnDirectoryRefusesInCodedForm(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "export", "--out-dir", unwritable(dir, "ctx").toString());

        assertRefusedAsUnwritable(res, "export --out-dir (the directory itself cannot be created)");
    }

    /**
     * The half-written case, arranged rather than hoped for: {@code export --out-dir} writes
     * {@code fields.jsonl} then {@code groups.jsonl}, so pre-creating the second name as a DIRECTORY lets the
     * first write land and the second fail. Without this the {@code data.written} contract would be advertised
     * and unpointed-at — the one thing a partial-failure claim must never be.
     */
    @Test
    void aMultiFileWriterNamesWhatItAlreadyLeftBehind(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path out = dir.resolve("ctx");
        Files.createDirectories(out.resolve("groups.jsonl"));   // a directory where a file must go

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "export", "--out-dir", out.toString());

        assertRefusedAsUnwritable(res, "export --out-dir failing on its second file");
        JsonNode envelope = MAPPER.readTree(res.out());
        assertFalse(envelope.path("written").asBoolean(true),
                () -> "the SELECTED destination — the whole bundle — was not produced: " + res.out());
        List<String> debris = new ArrayList<>();
        envelope.path("data").path("written").forEach(node -> debris.add(node.asText()));
        assertTrue(debris.stream().anyMatch(path -> path.endsWith("fields.jsonl")),
                () -> "the file that DID land must be named so the caller can clean up: " + res.out());
        assertTrue(Files.exists(out.resolve("fields.jsonl")), "and it really is on disk");
    }

    /**
     * The remaining sites, covered by ABSENCE of the old literal rather than by driving each verb.
     *
     * <p>Deliberately over-triggering: it does not strip comments, so a comment mentioning the phrase fails
     * too. That is the safe direction. The G61 sweep's first attempt stripped comments before matching and
     * silently lost a real site to a string literal containing a comment opener — under-triggering reports the
     * class as closed while members of it stay live, which is worse than a reworded comment.
     */
    @Test
    void noWriteFailureIsStillReportedOnBareStderr() throws IOException {
        Path commands = Path.of("src/main/java/io/github/mbackschat/a12/dm/cli");
        Map<String, List<Integer>> offenders = new LinkedHashMap<>();
        try (Stream<Path> sources = Files.walk(commands)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(source);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    // `cannot create --` and not `cannot create ` at large: `model new` legitimately fails with
                    // "cannot create a valid model from --id ..." for a bad id, which is an input rejection and
                    // not a write. Over-triggering is the safe direction, but not onto a different class.
                    if (line.contains("fail(\"cannot write") || line.contains("fail(\"cannot create --")) {
                        offenders.computeIfAbsent(commands.relativize(source).toString(),
                                key -> new ArrayList<>()).add(i + 1);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "a write failure must reach the caller as "
                + RkCode.OUTPUT_UNWRITABLE.code() + " through refuseUnwritableOutput, not as a bare stderr "
                + "line that leaves stdout empty: " + offenders);
    }

    /**
     * {@code batch --observations} is the one write failure that already produced an envelope, and it named the
     * wrong culprit: {@code RK_INTERNAL_ERROR} means "this is a dmtool defect, not an invalid input", while its
     * own {@code fix} told the caller to pick a writable destination. Its exit code and family are NOT changed
     * here — the children really did run and write, so this is a partial success rather than a refusal — only
     * the code, which was the part that lied.
     */
    @Test
    void theBatchObservationsWriteFailureNoLongerBlamesTheTool(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        Path ops = dir.resolve("ops.json");
        // --observations needs a caller id per item AND a supported static candidate verb — a field edit is
        // neither, and the artifact is keyed on the id.
        Files.writeString(ops, """
                [{"id":"x","verb":"rule","args":["check","--condition","FieldFilled(/Subscription/Reference)",
                  "--group","/Subscription","--field","../Reference","--code","E1","-m","%s"]}]"""
                .formatted(model.toString().replace("\\", "\\\\")));

        CliTestSupport.Result res = CliTestSupport.run(
                "batch", ops.toString(), "--observations", unwritable(dir, "obs.json").toString());

        List<JsonNode> diagnostics = diagnostics(res.out());
        assertFalse(diagnostics.isEmpty(), () -> "the batch envelope is still emitted: " + res.out() + res.err());
        assertTrue(diagnostics.stream().anyMatch(
                        d -> RkCode.OUTPUT_UNWRITABLE.code().equals(d.path("code").asText())),
                () -> "an unwritable observations artifact is an environment failure, not a tool defect: "
                        + res.out());
        assertTrue(diagnostics.stream().noneMatch(
                        d -> RkCode.INTERNAL_ERROR.code().equals(d.path("code").asText())),
                () -> "and it must no longer tell the caller to report a dmtool bug: " + res.out());
    }
}
