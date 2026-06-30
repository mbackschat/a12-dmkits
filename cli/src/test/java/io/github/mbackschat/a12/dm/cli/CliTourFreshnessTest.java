package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Local freshness guard for the showboat CLI tour ({@code examples/cli-tour.md}).
 *
 * <p>The {@code examples/*.md} demos are CI-gated (showboat re-runs each captured command and diffs), but
 * that gate runs <b>only in CI</b> — so a value the tour pins, derived from live code, can drift in a
 * <em>local</em> commit and sit there with CI red, unnoticed. It happened twice: the coded-refusals commit
 * shifted the {@code diagnostics} count 31 → 33 (two new {@link io.github.mbackschat.a12.dm.cli.RkCode}
 * entries); and a later commit added {@code typedef unimport} + the {@code allowDifferingDecimals} rule-spec
 * key — drifting the verb list and the {@code schema rule add} key set — with CI red but a green local build.
 * The trap each time: {@code EXPECTED_VERBS} locks the verb <em>set</em> in {@link ManifestVerbTest}, but
 * nothing tied that to the tour's <em>capture</em>, so the regeneration was simply forgotten.
 *
 * <p>This test reproduces, in the inner loop, every high-churn value the tour pins from live code — the
 * {@code diagnostics} count, the {@code manifest} verb list, and the {@code schema rule add} input keys —
 * running the real verb in-process and asserting the tour's capture still matches, failing fast and naming
 * the regen command. The rule (CLAUDE.md): when a demo starts pinning a new live-derived value, add its
 * inner-loop lock here.
 *
 * <p>All three captures are outputs of the <b>self-describing surface</b>, so they live in the
 * {@code cli-discover.md} demo (since the {@code cli-tour.md} catch-all was split by workflow). {@link #DEMO}
 * is the single source for that file — re-point it if these captures move again.
 */
class CliTourFreshnessTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The demo carrying the self-describing-surface captures these locks pin (post cli-tour split). */
    private static final String DEMO = "examples/cli-discover.md";

    private static final String REGEN =
            "`uvx showboat@0.6.1 verify " + DEMO + " --output " + DEMO + "`";

    /** The diagnostics-summary block the tour captures: {@code {"count":N,"severities":[...],...}}. */
    private static final Pattern DIAG_COUNT = Pattern.compile("\\{\"count\":(\\d+),\"severities\":");

    @Test
    void cliTourDiagnosticsCountMatchesTheLiveRegistry() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("diagnostics");
        assertEquals(0, res.code(), res.err());
        int live = M.readTree(res.out()).get("count").asInt();

        Path tour = repoFile(DEMO);
        Matcher m = DIAG_COUNT.matcher(Files.readString(tour));
        assertTrue(m.find(), () -> "no diagnostics-summary capture {\"count\":N,\"severities\":…} in " + tour);
        int captured = Integer.parseInt(m.group(1));

        assertEquals(live, captured, () ->
                DEMO + " pins a STALE diagnostics count (" + captured + " vs live " + live
                + ") — a RkCode change wasn't reflected in the CI-gated tour. Regenerate it: " + REGEN);
    }

    @Test
    void cliTourManifestVerbListMatchesTheLiveManifest() throws Exception {
        // The tour captures `manifest | jq -r ".verbs[].verb"` — every verb, in order. A verb
        // add/rename/remove drifts it; reproduce the projection live and compare.
        CliTestSupport.Result res = CliTestSupport.run("manifest");
        assertEquals(0, res.code(), res.err());
        List<String> live = new ArrayList<>();
        M.readTree(res.out()).get("verbs").forEach(v -> live.add(v.get("verb").asText()));

        List<String> captured = capturedOutputLines("dmtool manifest | jq -r \".verbs[].verb\"").stream()
                .map(String::strip).filter(s -> !s.isEmpty()).toList();

        assertEquals(live, captured, () ->
                DEMO + " pins a STALE verb list — a verb add/rename/remove wasn't reflected in the "
                + "CI-gated tour. Regenerate it: " + REGEN);
    }

    @Test
    void cliTourSchemaRuleAddKeysMatchTheLiveSchema() throws Exception {
        // The tour captures `schema rule add | jq '{… inputKeys: (.input.properties|keys)}'`; an add.schema
        // property add/rename drifts it. jq `keys` sorts, so compare sorted.
        CliTestSupport.Result res = CliTestSupport.run("schema", "rule", "add");
        assertEquals(0, res.code(), res.err());
        List<String> live = new ArrayList<>();
        M.readTree(res.out()).get("input").get("properties").fieldNames().forEachRemaining(live::add);
        Collections.sort(live);

        String capturedJson = String.join("\n", capturedOutputLines("dmtool schema rule add | jq"));
        List<String> captured = new ArrayList<>();
        M.readTree(capturedJson).get("inputKeys").forEach(k -> captured.add(k.asText()));

        assertEquals(live, captured, () ->
                DEMO + " pins STALE `rule add` schema keys — an add.schema property change wasn't "
                + "reflected in the CI-gated tour. Regenerate it: " + REGEN);
    }

    /** The lines inside the first {@code ```output} block that follows the command line containing {@code cmd}. */
    private static List<String> capturedOutputLines(String cmd) throws IOException {
        List<String> lines = Files.readAllLines(repoFile(DEMO));
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(cmd)) {
                at = i;
                break;
            }
        }
        assertTrue(at >= 0, () -> "no command line containing '" + cmd + "' in " + DEMO);
        int open = -1;
        for (int i = at + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("```output")) {
                open = i;
                break;
            }
        }
        assertTrue(open >= 0, () -> "no ```output block after '" + cmd + "' in " + DEMO);
        List<String> out = new ArrayList<>();
        for (int i = open + 1; i < lines.size() && !lines.get(i).startsWith("```"); i++) {
            out.add(lines.get(i));
        }
        return out;
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
