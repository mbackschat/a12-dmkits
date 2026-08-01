package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * A demo's captured output must be a <b>successful demonstration</b>, not merely a stable capture.
 *
 * <p><b>Why the demo gate could not catch this.</b> {@code scripts/verify-demos.sh} runs
 * {@code showboat verify}, which re-runs each block and diffs the result against what is committed. That
 * proves the capture still reproduces — and a captured <em>error</em> reproduces perfectly. So
 * {@code cli-edit-loop.md} shipped {@code jq: error (at <stdin>:66)} as its expected output beneath prose
 * describing the verdicts it never printed, and the gate was green over it for a full phase. Re-nesting the
 * result payload under {@code data} (Phase 2) invalidated four demo {@code jq} filters at once; every one of
 * them kept passing.
 *
 * <p><b>The two shapes, and why nulls need a ledger.</b> A stale filter fails in one of two ways: the tool
 * itself errors ({@link #TOOL_FAILURE}), or the projection resolves to {@code null} because the key moved.
 * The second cannot be judged from the text alone — a {@code null} is sometimes the honest answer, for a key
 * the envelope legitimately omits. So every {@code null} in a demo must be named in {@link #NULL_IS_THE_POINT}
 * with the reason it is real. That is deliberately a little tedious: four stale filters hid among five genuine
 * nulls, and nothing distinguished them but knowing what each demo meant to show.
 *
 * <p>This is a source scan over the committed demos, so it is cheap and runs in {@code docGuardTest} with
 * {@code examples/*.md} as its input. It does not replace {@code verify-demos.sh} — that one proves the
 * capture is current, this one proves the capture is worth having.
 */
class DemoOutputIsRealTest {

    private static final Path DEMOS = Path.of("..", "examples");

    /** A block's captured output, with the command above it and enough position to fix it. */
    private record Capture(String demo, int line, String command, String output) {}

    /**
     * Signatures of the pipeline's own tooling failing — the shell, {@code jq}, the JVM launcher. Distinct
     * from {@code dmtool} reporting a rejection, which is structured output a demo may legitimately show.
     */
    private static final List<Pattern> TOOL_FAILURE = List.of(
            Pattern.compile("jq: error"),
            Pattern.compile("jq: syntax error"),
            Pattern.compile("parse error: "),
            Pattern.compile("command not found"),
            Pattern.compile("Exception in thread"),
            Pattern.compile("Could not find or load main class"),
            Pattern.compile("Traceback \\(most recent call last\\)"),
            Pattern.compile("^\\s+at [a-z]+\\.[a-z]+\\.", Pattern.MULTILINE));

    /**
     * Every {@code null} a demo publishes on purpose: the key really is absent, and the absence is the point.
     * Keyed {@code <demo>#<jsonKey>}. A stale projection reads exactly like these, so a new row is a claim
     * that someone checked which one it is.
     */
    private static final Map<String, String> NULL_IS_THE_POINT = new LinkedHashMap<>(Map.of(
            "cli-workspace.md#username",
            "UNDEFINED_ROLE is about a role on a model, so the finding carries no user",
            "cli-workspace.md#modelId",
            "UNDEFINED_AUTHORITY is about a user, so the finding carries no model — the mirror of #username",
            "cli-custom-types.md#unsupported",
            "the passing run has nothing unsupported, and the envelope omits the key rather than emitting []",
            "cli-apply.md#failedAt",
            "apply always emits failedAt so a consumer can read it unconditionally: null when every op committed, "
                    + "the failing index otherwise — the same demo shows `failedAt: 1` for the rejected batch",
            "cli-discover.md#valid",
            "profile compare reaches no consistency verdict, so `valid` is omitted — that IS the demonstration"));

    private static List<Capture> captures() throws IOException {
        Pattern block = Pattern.compile("```bash\\n(.*?)```\\s*\\n+```output\\n(.*?)```", Pattern.DOTALL);
        List<Capture> all = new ArrayList<>();
        try (var files = Files.list(DEMOS)) {
            for (Path demo : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                if (demo.getFileName().toString().equals("README.md")) {
                    continue;
                }
                String src = Files.readString(demo);
                Matcher m = block.matcher(src);
                while (m.find()) {
                    int line = (int) src.substring(0, m.start(2)).lines().count() + 1;
                    all.add(new Capture(demo.getFileName().toString(), line, m.group(1), m.group(2)));
                }
            }
        }
        return all;
    }

    /** The scan must actually be reading demos — an empty corpus would make everything below vacuously green. */
    @Test
    void theDemoCorpusIsBeingRead() throws Exception {
        int found = captures().size();
        assertTrue(found > 50,
                () -> "expected the examples/ demos to yield many captured blocks, got " + found
                        + " — if the tree moved, this guard is passing over nothing");
    }

    @Test
    void noDemoCapturesItsOwnToolingFailing() throws Exception {
        List<String> broken = new ArrayList<>();
        for (Capture c : captures()) {
            for (Pattern p : TOOL_FAILURE) {
                if (p.matcher(c.output()).find()) {
                    broken.add(c.demo() + ":" + c.line() + " (" + p.pattern() + ")");
                }
            }
        }
        assertTrue(broken.isEmpty(),
                () -> "these demos record a FAILURE as their expected output: " + broken
                        + ". verify-demos.sh is green on them because a captured error re-runs identically — "
                        + "it proves the capture is stable, not that the demo works. Fix the command, re-capture, "
                        + "and check the prose beneath it still describes what is printed");
    }

    @Test
    void everyNullInADemoIsThereOnPurpose() throws Exception {
        Pattern nullValue = Pattern.compile("\"(\\w+)\"\\s*:\\s*null");
        List<String> unexplained = new ArrayList<>();
        for (Capture c : captures()) {
            Matcher m = nullValue.matcher(c.output());
            while (m.find()) {
                String key = c.demo() + "#" + m.group(1);
                if (!NULL_IS_THE_POINT.containsKey(key)) {
                    unexplained.add(key + " (" + c.demo() + ":" + c.line() + ")");
                }
            }
        }
        assertTrue(unexplained.isEmpty(),
                () -> "these demo outputs contain a null nobody has vouched for: " + unexplained
                        + ". A projection whose key moved looks EXACTLY like a key that is absent on purpose — "
                        + "re-run the command and decide which it is. If the null is the demonstration, add a row "
                        + "to NULL_IS_THE_POINT saying why; if the filter is stale, fix it and re-capture");
    }

    /** No stale exemptions: every excused null still appears in the demo that claims it. */
    @Test
    void theNullLedgerIsHonest() throws Exception {
        List<Capture> all = captures();
        for (var entry : NULL_IS_THE_POINT.entrySet()) {
            String demo = entry.getKey().substring(0, entry.getKey().indexOf('#'));
            String key = entry.getKey().substring(entry.getKey().indexOf('#') + 1);
            Pattern expected = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*null");
            boolean present = all.stream()
                    .filter(c -> c.demo().equals(demo))
                    .anyMatch(c -> expected.matcher(c.output()).find());
            assertTrue(present,
                    () -> entry.getKey() + " is excused but no longer appears — drop the row rather than leaving "
                            + "it to excuse a future stale projection on the same key");
            assertTrue(!entry.getValue().isBlank(), () -> entry.getKey() + " needs a reason");
        }
    }
}
