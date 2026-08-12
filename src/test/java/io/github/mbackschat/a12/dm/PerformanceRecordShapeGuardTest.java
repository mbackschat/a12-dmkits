package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * <b>Every committed run under {@code perf-results/} is readable by the tools that read it.</b>
 *
 * <p><b>Why this exists.</b> The record is written by one producer ({@code PerfObservations}) and read by two
 * independent consumers ({@code scripts/perf-history.sh} renders it as a table, {@code scripts/perf-anchor.sh}
 * folds it into the committed anchor). Nothing connected the three, and three runs imported from hosted artifacts
 * were committed missing a field the writer emits.
 *
 * <p><b>What that field was, stated precisely, because the obvious framing overstates it.</b> The missing field was
 * {@code lane}, and <i>neither</i> consumer reads it — {@code perf-history.sh} derives the lane from the first
 * segment of {@code key}. So the imported files were readable; what they were not was <i>consistent</i> with what
 * the producer writes, and an inconsistent record is what a later reader trips over. Of the eight required
 * observation fields only {@code key}, {@code medianMs} and {@code status} are read by a consumer today; the rest
 * are required so the record stays queryable on its own terms rather than only through the packed key.
 *
 * <p><b>The strong assertion is agreement, not presence.</b> Each observation carries a packed {@code key} AND its
 * exploded components ({@code lane}, {@code scenario}, {@code n}, {@code engine}, {@code clock}), because the
 * anchor is addressed by the key while a later query wants the parts. Two representations of one fact can
 * disagree, so this guard reconstructs the key from the components and requires them to match — which no
 * presence check can see, and which is how a producer packing one lane while exploding another would surface.
 */
class PerformanceRecordShapeGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Header fields a consumer reads. {@code loadAverage}, {@code runId} and {@code runnerName} are deliberately
     * absent: an imported run predating the record cannot supply them, and both consumers already render "n/a".
     */
    private static final List<String> REQUIRED_RUN_FIELDS =
            List.of("environment", "capturedAt", "commit", "os", "arch", "jdk", "processors", "ci");

    /** Observation fields a consumer reads, or that the key-agreement check below reconstructs from. */
    private static final List<String> REQUIRED_OBSERVATION_FIELDS =
            List.of("environment", "key", "lane", "scenario", "n", "engine", "clock", "medianMs");

    /**
     * A warm outcome's fields. The first four are its join key onto an observation — the outcome's whole value is
     * that it QUALIFIES a median (only {@code flattened} means the instance reached steady state, and a
     * still-falling kernel arm makes our ratio read better than it is), so a row that cannot be joined says nothing.
     */
    private static final List<String> REQUIRED_WARM_FIELDS =
            List.of("environment", "lane", "scenario", "n", "engine", "runs", "stop");

    /** A lane's measurement cost. Coarser than a warm row: per lane and scenario, not per instance. */
    private static final List<String> REQUIRED_COST_FIELDS =
            List.of("environment", "lane", "scenario", "wallMs");

    /** Every {@code kind} a record line may carry after the header. An unknown one is a producer/guard drift. */
    private static final Set<String> KNOWN_KINDS = Set.of("observation", "warm", "cost");

    /**
     * How a warm ended. Only {@code flattened} is not a caveat on every ratio measured on that instance; the two
     * caps mean the budget ran out first, which is the state {@code IF282} set the 8 s cap to avoid and which
     * nothing could see historically because the outcome was printed and never recorded (IG121's fourth piece).
     */
    private static final Set<String> KNOWN_WARM_STOPS = Set.of("flattened", "run-cap", "time-cap");

    /**
     * What acceptance the observation was recorded under. Absent means an imported run written before the field
     * existed, which {@code perf-anchor.sh} treats as {@code accepted} — so the values must stay in step with that
     * script's filter, and an unknown one would be silently folded into an anchor as if it had been accepted.
     */
    private static final Set<String> KNOWN_STATUSES = Set.of("accepted", "breached", "unasserted", "unanchored");

    /**
     * How many runs one environment may carry before the record must be pruned. About a month of nightlies, and
     * far past {@code perf-history.sh}'s default 8-run window; beyond that horizon the durable references are the
     * committed anchor and the checkpoints rather than raw runs of superseded revisions. A committed tree that
     * grows without a stated bound is how a text record becomes the liability someone eventually deletes whole.
     */
    private static final int MAX_RUNS_PER_ENVIRONMENT = 30;

    /** The grain a warm outcome and the median it qualifies share: one engine instance at one scale in one lane. */
    private static String joinKey(JsonNode row) {
        return "%s|%s|n=%d|%s".formatted(row.path("lane").asText(), row.path("scenario").asText(),
                row.path("n").asInt(), row.path("engine").asText());
    }

    private static Path repoRoot() {
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot locate the repository root from " + Path.of("").toAbsolutePath());
    }

    private static List<Path> records() throws IOException {
        try (Stream<Path> walk = Files.walk(repoRoot().resolve("perf-results"))) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList();
        }
    }

    @Test
    void everyCommittedRunIsReadableByItsConsumers() throws IOException {
        List<Path> records = records();
        // The vacuity control: every assertion below is satisfied by an empty scan, so an emptied or moved tree
        // must fail loudly here rather than read as a clean record.
        assertTrue(!records.isEmpty(), "perf-results/ must hold at least one recorded run; with none, every "
                + "assertion in this guard is vacuously true");

        var problems = new ArrayList<String>();
        int observations = 0;
        int warms = 0;
        for (Path record : records) {
            String relative = repoRoot().relativize(record).toString();
            List<String> lines = Files.readAllLines(record).stream().map(String::strip)
                    .filter(line -> !line.isEmpty()).toList();
            if (lines.isEmpty()) {
                problems.add(relative + ": empty");
                continue;
            }
            JsonNode header;
            try {
                header = MAPPER.readTree(lines.get(0));
            } catch (IOException malformed) {
                problems.add(relative + " line 1: not JSON — " + malformed.getMessage());
                continue;
            }
            if (!"run".equals(header.path("kind").asText())) {
                problems.add(relative + " line 1: must be the run header, was kind="
                        + header.path("kind").asText("<absent>"));
                continue;
            }
            REQUIRED_RUN_FIELDS.stream().filter(field -> header.path(field).isMissingNode())
                    .forEach(field -> problems.add(relative + " line 1: run header lacks " + field));
            // The environment is how a runner class is addressed; a file filed under the wrong directory would be
            // folded into another machine's anchor, which is exactly the comparison the anchor exists to prevent.
            String directory = record.getParent().getFileName().toString();
            if (!directory.equals(header.path("environment").asText())) {
                problems.add(relative + ": filed under " + directory + " but reports environment="
                        + header.path("environment").asText("<absent>"));
            }
            // Per FILE: a warm outcome qualifies a median measured in the same run, never one from another.
            var warmKeys = new ArrayList<String>();
            var observedKeys = new java.util.HashSet<String>();
            String stamp = record.getFileName().toString().replace(".jsonl", "");
            if (!stamp.equals(header.path("capturedAt").asText())) {
                problems.add(relative + ": named " + stamp + " but reports capturedAt="
                        + header.path("capturedAt").asText("<absent>"));
            }

            for (int line = 1; line < lines.size(); line++) {
                String where = relative + " line " + (line + 1);
                JsonNode observation;
                try {
                    observation = MAPPER.readTree(lines.get(line));
                } catch (IOException malformed) {
                    problems.add(where + ": not JSON — " + malformed.getMessage());
                    continue;
                }
                String kind = observation.path("kind").asText("<absent>");
                if (!KNOWN_KINDS.contains(kind)) {
                    problems.add(where + ": only the first line may be a run header, and every later line must "
                            + "carry a known kind " + KNOWN_KINDS + ", was kind=" + kind);
                    continue;
                }
                if ("warm".equals(kind)) {
                    REQUIRED_WARM_FIELDS.stream().filter(field -> observation.path(field).isMissingNode())
                            .forEach(field -> problems.add(where + ": warm outcome lacks " + field));
                    String stop = observation.path("stop").asText("<absent>");
                    if (!observation.path("stop").isMissingNode() && !KNOWN_WARM_STOPS.contains(stop)) {
                        problems.add(where + ": unknown warm stop " + stop + "; a reader tells 'flattened' from a "
                                + "cap to know whether the medians it qualifies are on a settled curve");
                    }
                    warmKeys.add(joinKey(observation) + "  (" + where + ")");
                    warms++;
                    continue;
                }
                if ("cost".equals(kind)) {
                    REQUIRED_COST_FIELDS.stream().filter(field -> observation.path(field).isMissingNode())
                            .forEach(field -> problems.add(where + ": cost row lacks " + field));
                    continue;
                }
                observations++;
                var missing = REQUIRED_OBSERVATION_FIELDS.stream()
                        .filter(field -> observation.path(field).isMissingNode()).toList();
                missing.forEach(field -> problems.add(where + ": observation lacks " + field));
                if (!missing.isEmpty()) {
                    continue;
                }
                observedKeys.add(joinKey(observation));
                if (!observation.path("environment").asText().equals(header.path("environment").asText())) {
                    problems.add(where + ": observation environment disagrees with its run header");
                }
                String reconstructed = "%s|%s|n=%d|%s|%s".formatted(
                        observation.path("lane").asText(),
                        observation.path("scenario").asText(),
                        observation.path("n").asInt(),
                        observation.path("engine").asText(),
                        observation.path("clock").asText());
                if (!reconstructed.equals(observation.path("key").asText())) {
                    problems.add(where + ": key and its exploded components disagree\n      key = "
                            + observation.path("key").asText() + "\n      parts = " + reconstructed);
                }
                double median = observation.path("medianMs").asDouble(Double.NaN);
                if (!Double.isFinite(median) || median <= 0) {
                    problems.add(where + ": medianMs must be finite and positive, was "
                            + observation.path("medianMs").asText("<absent>"));
                }
                JsonNode status = observation.path("status");
                if (!status.isMissingNode() && !KNOWN_STATUSES.contains(status.asText())) {
                    problems.add(where + ": unknown status " + status.asText() + "; perf-anchor.sh folds only "
                            + "'accepted' and treats an ABSENT status as accepted, so an unrecognized one is "
                            + "either a typo or a value that script has not been taught");
                }
            }

            // A warm outcome that joins to nothing is the shape a renamed field or a changed label produces, and
            // it fails silently: the row is still well-formed, it simply stops qualifying anything.
            warmKeys.stream().filter(entry -> !observedKeys.contains(entry.substring(0, entry.indexOf("  ("))))
                    .forEach(entry -> problems.add(entry + ": warm outcome joins no observation in its own run; "
                            + "(lane, scenario, n, engine) must match a median measured in the same file"));
        }

        assertTrue(observations > 0, "the scan read " + records.size() + " file(s) but no observation at all, so "
                + "the per-observation assertions never ran");
        // The bound of what committed data can prove about a PRODUCER: this cannot see an emitter that was deleted
        // while old records keep their rows, but it does fail once no committed run carries a warm outcome — which
        // is the state that made IF282's cap justification unverifiable and is what IG121's fourth piece fixed.
        assertTrue(warms > 0, "no committed run carries a warm outcome, so every assertion about them above is "
                + "vacuous; a warm that ends on a budget cap rather than by flattening is a caveat on every "
                + "cross-engine ratio in its lane, and recording it is the only way a later run can compare");
        assertEquals(List.of(), problems, () -> "committed performance records must stay readable by "
                + "scripts/perf-history.sh and scripts/perf-anchor.sh:\n  " + String.join("\n  ", problems));
    }

    /**
     * The record is committed, so it grows every time someone keeps a run. Only two kinds of run are worth
     * keeping — an anchor-refresh input and a cited checkpoint measurement — and this bounds what happens when
     * that rule is not followed, since the alternative to a stated cap is an unbounded tree nobody prunes.
     */
    /**
     * {@link #KNOWN_STATUSES} is a hand-written mirror of the vocabulary {@code scripts/perf-anchor.sh} folds on,
     * and a mirror with nothing binding it is a claim without a locus. Read the script's own literals instead of
     * trusting the copy: a status the script learns and this guard does not would be reported as unknown, and one
     * this guard allows but the script drops would vanish from a refresh silently.
     */
    @Test
    void theFoldedStatusVocabularyMatchesTheScriptThatConsumesIt() throws IOException {
        String script = Files.readString(repoRoot().resolve("scripts/perf-anchor.sh"));
        var folded = new java.util.LinkedHashSet<String>();
        java.util.regex.Matcher filter = java.util.regex.Pattern
                .compile("status\", \"accepted\"\\) not in \\(([^)]*)\\)").matcher(script);
        assertTrue(filter.find(), "perf-anchor.sh must still decide which statuses may set a bar; if that filter "
                + "moved, this guard is comparing against nothing");
        java.util.regex.Matcher literal = java.util.regex.Pattern.compile("\"([a-z]+)\"").matcher(filter.group(1));
        while (literal.find()) {
            folded.add(literal.group(1));
        }
        assertTrue(!folded.isEmpty(), "the script's fold list parsed empty: " + filter.group(1));
        assertTrue(KNOWN_STATUSES.containsAll(folded), "perf-anchor.sh folds " + folded + " but this guard only "
                + "knows " + KNOWN_STATUSES + "; a status one side knows and the other does not is how an "
                + "observation silently stops reaching the anchor");
    }

    @Test
    void noEnvironmentOutgrowsTheStatedRetentionBound() throws IOException {
        var perEnvironment = new java.util.TreeMap<String, Integer>();
        for (Path record : records()) {
            perEnvironment.merge(record.getParent().getFileName().toString(), 1, Integer::sum);
        }
        assertTrue(!perEnvironment.isEmpty(), "perf-results/ must hold at least one environment");

        var over = perEnvironment.entrySet().stream()
                .filter(entry -> entry.getValue() > MAX_RUNS_PER_ENVIRONMENT)
                .map(entry -> entry.getKey() + " carries " + entry.getValue() + " runs")
                .toList();
        assertEquals(List.of(), over, () -> "the committed record has outgrown its retention bound of "
                + MAX_RUNS_PER_ENVIRONMENT + " runs per environment:\n  " + String.join("\n  ", over)
                + "\n\nDelete the oldest files: past this horizon the durable references are the committed anchor "
                + "and the checkpoints that cite a run, never the raw runs of superseded revisions. See "
                + "perf-results/README.md, \"What gets committed, and what does not\"");
    }
}
