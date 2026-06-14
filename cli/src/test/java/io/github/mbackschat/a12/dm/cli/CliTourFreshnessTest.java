package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Local freshness guard for the showboat CLI tour ({@code examples/cli-tour.md}).
 *
 * <p>The {@code examples/*.md} demos are CI-gated (showboat re-runs each captured command and diffs), but
 * that gate runs <b>only in CI</b> — so a value the tour pins, derived from live code, can drift in a
 * <em>local</em> commit and sit there with CI red, unnoticed. It did exactly that: the coded-refusals commit
 * added two {@link io.github.mbackschat.a12.dm.cli.RkCode} entries — shifting the {@code diagnostics} count
 * 31 → 33 — without regenerating the tour, and nothing in {@code :cli:test} caught it.
 *
 * <p>This test reproduces, in the inner loop, the one high-churn capture that had no local lock: it runs the
 * real {@code diagnostics} verb in-process and asserts the tour's pinned count still matches, failing fast and
 * naming the regen command. (The tour's other live capture — the verb list — is already locked locally by
 * {@link ManifestVerbTest}'s {@code EXPECTED_VERBS}, which forces a human edit on every verb change.)
 */
class CliTourFreshnessTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The diagnostics-summary block the tour captures: {@code {"count":N,"severities":[...],...}}. */
    private static final Pattern DIAG_COUNT = Pattern.compile("\\{\"count\":(\\d+),\"severities\":");

    @Test
    void cliTourDiagnosticsCountMatchesTheLiveRegistry() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("diagnostics");
        assertEquals(0, res.code(), res.err());
        int live = M.readTree(res.out()).get("count").asInt();

        Path tour = repoFile("examples/cli-tour.md");
        Matcher m = DIAG_COUNT.matcher(Files.readString(tour));
        assertTrue(m.find(), () -> "no diagnostics-summary capture {\"count\":N,\"severities\":…} in " + tour);
        int captured = Integer.parseInt(m.group(1));

        assertEquals(live, captured, () ->
                "examples/cli-tour.md pins a STALE diagnostics count (" + captured + " vs live " + live
                + ") — a RkCode change wasn't reflected in the CI-gated tour. Regenerate it: "
                + "`uvx showboat@0.6.1 verify examples/cli-tour.md --output examples/cli-tour.md`");
    }

    /** Locate a repo-root file from the {@code :cli} module working dir (mirrors EvalGoldOracleTest). */
    private static Path repoFile(String relative) {
        for (String base : List.of("..", ".", "../..")) {
            Path p = Path.of(base, relative);
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }
}
