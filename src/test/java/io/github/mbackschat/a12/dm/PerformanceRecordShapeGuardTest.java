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

    private static final Checkout CHECKOUT = Checkout.locate();

    /** The grain a warm outcome and the median it qualifies share: one engine instance at one scale in one lane. */
    private static String joinKey(JsonNode row) {
        return "%s|%s|n=%d|%s".formatted(row.path("lane").asText(), row.path("scenario").asText(),
                row.path("n").asInt(), row.path("engine").asText());
    }

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

    /**
     * Environment-quality fields every observation carries (IG121's third piece). GC and JIT come from JVM beans
     * and are always present; the Linux-only counters are deliberately absent-or-null off Linux rather than 0, so
     * they are not required here — {@code null} means "not observable on this platform", which a 0 would misreport
     * as "none observed".
     */
    private static final List<String> REQUIRED_QUALITY_FIELDS =
            List.of("windowSince", "gcCountInWindow", "gcMsInWindow");

    /** How a quality window is defined. The first observation of a run measures from JVM start. */
    private static final Set<String> KNOWN_QUALITY_WINDOWS = Set.of("jvm-start", "previous-observation");

    /**
     * The quality fields exist to make a historic outlier interpretable and must NEVER reach a bar — they are
     * post-treatment variables, so filtering on them would censor the very evidence a regression produces (an
     * allocation regression causes GC). {@link #noQualityFieldIsReadOutsideTheRecorder()} holds that structurally;
     * this is the list it sweeps for.
     */
    private static final List<String> QUALITY_FIELD_NAMES = List.of(
            "gcCountInWindow", "gcMsInWindow", "jitMsInWindow", "stealJiffiesInWindow",
            "cgroupThrottledUsecInWindow", "cgroupThrottledPeriodsInWindow");

    /**
     * The machine-demand fields (IG154a), which are the OPPOSITE case to the quality fields above and must not be
     * added to that list.
     *
     * <p>Foreign CPU demand is <em>pre-treatment</em>: no change to our code can make another process busy, so
     * judging a comparison on it removes a confound rather than censoring one. These are therefore <em>required</em>
     * to have a consumer — {@link #theMachineDemandFieldsHaveAnEnforcementLocus()} — which is the inverse of the
     * sweep that forbids the quality fields one.
     *
     * <p>All three travel together or none does, so a reader never mistakes an absent key for a quiet machine;
     * the window they describe can legitimately produce no reading at all, which is why they are not in
     * {@link #REQUIRED_OBSERVATION_FIELDS}.
     *
     * <p><b>The per-row checks on these are a BACKSTOP and are dormant until a record carries them</b> — the same
     * state the Linux-only quality counters are in on darwin. The all-or-nothing rule is locked where it is
     * produced, by {@code PerfMachineDemandTest}, so what the row arms add is cover for a hand-edited or imported
     * record. Stated rather than left to be discovered, because a dormant branch reads exactly like a checked one.
     */
    private static final List<String> DEMAND_FIELD_NAMES =
            List.of("systemCpuUtilisation", "processCpuUtilisation", "foreignCpuUtilisation");

    /** Every {@code kind} a record line may carry after the header. An unknown one is a producer/guard drift. */
    private static final Set<String> KNOWN_KINDS = Set.of("observation", "warm", "cost", "margin");

    /** A margin row's own shape: the cross-engine headroom and the verdict it was given (IF292). */
    private static final List<String> REQUIRED_MARGIN_FIELDS =
            List.of("environment", "key", "scenario", "lane", "n", "engine", "clock", "margin", "status");

    /** The band's vocabulary. A row outside it means the producer and this guard disagree about the contract. */
    private static final Set<String> KNOWN_MARGIN_STATUSES = Set.of("lost", "unbanded", "warn", "ok");

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
    private static final Set<String> KNOWN_STATUSES = Set.of("accepted", "breached", "flagged", "unasserted", "unanchored");

    /**
     * How many runs one environment may carry before the record must be pruned. About a month of nightlies, and
     * far past {@code perf-history.sh}'s default 8-run window; beyond that horizon the durable references are the
     * committed anchor and the checkpoints rather than raw runs of superseded revisions. A committed tree that
     * grows without a stated bound is how a text record becomes the liability someone eventually deletes whole.
     *
     * <p><b>This is now a BACKSTOP, not the enforcement.</b> {@code PerfObservations} prunes to the same bound as
     * it writes, because this guard structurally cannot see the breach it guards: a perf lane must run alone and
     * therefore runs last, its record is written by that run, and the record is committed <em>after</em> the
     * session's estate check — so the run that crosses the horizon shipped red by construction, and did
     * (2026-08-14, {@code darwin-aarch64} at 31). The writer owns the number; if the two ever disagree this fires
     * on the very next run, so the duplication is self-correcting rather than silent.
     */
    private static final int MAX_RUNS_PER_ENVIRONMENT = 30;

    private static List<Path> records() throws IOException {
        try (Stream<Path> walk = Files.walk(CHECKOUT.resolve("perf-results"))) {
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
            String relative = CHECKOUT.relative(record);
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
                if ("margin".equals(kind)) {
                    REQUIRED_MARGIN_FIELDS.stream().filter(field -> observation.path(field).isMissingNode())
                            .forEach(field -> problems.add(where + ": margin row lacks " + field));
                    String status = observation.path("status").asText("<absent>");
                    if (!observation.path("status").isMissingNode() && !KNOWN_MARGIN_STATUSES.contains(status)) {
                        problems.add(where + ": unknown margin status " + status + "; the band's vocabulary is "
                                + KNOWN_MARGIN_STATUSES);
                    }
                    // `floor` is absent exactly when the row is unbanded, and that pairing is the contract: a
                    // floor with no verdict, or an `ok` with no floor, would both read as coverage that is not there.
                    boolean banded = !observation.path("floor").isMissingNode();
                    if (banded == "unbanded".equals(status)) {
                        problems.add(where + ": a margin row must carry a floor iff its status is not 'unbanded', "
                                + "was status=" + status + " floor=" + (banded ? "present" : "absent"));
                    }
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
                // The quality fields arrived after 30 runs were already committed, exactly as `machine` did, so
                // they are validated WHEN PRESENT rather than required of history — a row written before the
                // producer existed is legitimately without them, and rewriting old records to satisfy a new guard
                // would forge measurements. `windowSince` is the marker that a row came from the new producer;
                // the PRODUCER's own shape is held in :adapter by PerfEnvironmentQualityTest, which is where it
                // can actually be called.
                if (!observation.path("windowSince").isMissingNode()) {
                    String window = observation.path("windowSince").asText("<absent>");
                    if (!KNOWN_QUALITY_WINDOWS.contains(window)) {
                        problems.add(where + ": unknown quality window " + window + "; a reader must be able to "
                                + "tell a first-observation window from a between-observations one, since they "
                                + "measure different spans");
                    }
                    REQUIRED_QUALITY_FIELDS.stream().filter(field -> observation.path(field).isMissingNode())
                            .forEach(field -> problems.add(where + ": a row carrying windowSince lacks " + field
                                    + " — a partial quality window is worse than none, since a reader cannot tell "
                                    + "an absent counter from an unwritten one"));
                }
                QUALITY_FIELD_NAMES.stream()
                        .filter(field -> observation.path(field).isNumber()
                                && observation.path(field).asLong() < 0)
                        .forEach(field -> problems.add(where + ": " + field + " is negative; a counter that went "
                                + "backwards must be recorded as null, because a negative delta is not a smaller "
                                + "measurement but an unusable one"));
                long demandPresent = DEMAND_FIELD_NAMES.stream()
                        .filter(field -> !observation.path(field).isMissingNode()).count();
                if (demandPresent != 0 && demandPresent != DEMAND_FIELD_NAMES.size()) {
                    problems.add(where + ": " + demandPresent + " of " + DEMAND_FIELD_NAMES.size() + " machine-demand"
                            + " fields are present; they travel together or not at all, because a reader given one"
                            + " of them would have to reconstruct the others and would read an absent key as a"
                            + " quiet machine");
                }
                DEMAND_FIELD_NAMES.stream()
                        .filter(field -> observation.path(field).isNumber()
                                && (observation.path(field).asDouble() < 0 || observation.path(field).asDouble() > 1))
                        .forEach(field -> problems.add(where + ": " + field + " is "
                                + observation.path(field).asDouble() + ", outside 0..1; these are fractions of the"
                                + " WHOLE machine, and a value outside that range means the sampler is being read"
                                + " as something it is not"));
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
        String script = Files.readString(CHECKOUT.resolve("scripts/perf-anchor.sh"));
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

    /**
     * The environment-quality fields are <b>recorded and never used to reject a sample</b>, and this is what makes
     * that claim checkable instead of a comment. They are post-treatment variables: an allocation regression causes
     * GC, so a bar or filter keyed on them would censor exactly the evidence that proves the regression — and steal
     * time correlates with the load a run competed against, so excluding busy windows quietly selects the
     * flattering ones.
     *
     * <p>The sweep is over the perf estate's sources: each field name may appear only where it is <em>produced</em>
     * ({@code PerfEnvironmentQuality}, which defines it, and this guard, which lists it). Any other mention is a
     * consumer, and the point of failing here is that a consumer is exactly what a future gate author would add.
     * This is the "advertise nothing you cannot point at" rule applied to a policy rather than to an invariant.
     */
    @Test
    void noQualityFieldIsReadOutsideTheRecorder() throws IOException {
        Path perf = CHECKOUT.resolve("adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf");
        assertTrue(Files.isDirectory(perf), () -> "the perf source tree moved: " + perf
                + " — this guard would otherwise sweep nothing and pass vacuously");

        List<String> offenders = new ArrayList<>();
        try (var walk = Files.walk(perf)) {
            for (Path source : walk.filter(p -> p.toString().endsWith(".kt")).toList()) {
                // The producer, and the producer's own shape test which asserts the exact key set. Both are
                // named individually rather than matched by a `*Test.kt` pattern: a blanket test exemption
                // would let a future perf TEST assert on a quality field, which is a gate in all but name.
                String name = source.getFileName().toString();
                if (name.equals("PerfEnvironmentQuality.kt") || name.equals("PerfEnvironmentQualityTest.kt")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(source);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    for (String field : QUALITY_FIELD_NAMES) {
                        if (line.contains(field)) {
                            offenders.add(source.getFileName() + ":" + (i + 1) + " mentions " + field + " — "
                                    + line.trim());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, () -> "an environment-quality field is referenced outside its recorder:\n  "
                + String.join("\n  ", offenders)
                + "\n\nThese fields are recorded to make a historic outlier interpretable and must never gate: they "
                + "are post-treatment variables, so rejecting samples on them censors the evidence a regression "
                + "produces. If a lane genuinely needs one, that is a decision to take deliberately and document "
                + "in INTERPRETER-GAPS IG121 — not by adding a read here.");
    }

    /**
     * The machine-demand fields have an enforcement locus, which is the exact inverse of the sweep above.
     *
     * <p>The quality fields are forbidden a consumer; these are <em>required</em> one, because a pre-treatment
     * reading that nothing rejects on is the "parsed but never consumed" shape this estate has already shipped
     * once. So this names the locus — {@code TimingEnforcement.forAbsoluteAnchor} reading the floor — rather than
     * trusting that IG154a's row says so. {@code ForeignDemandEnforcementTest} is the test that fails when the
     * rule itself changes; this is the one that fails when the wiring disappears.
     */
    @Test
    void theMachineDemandFieldsHaveAnEnforcementLocus() throws IOException {
        Path perf = CHECKOUT.resolve("adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf");
        String enforcement = Files.readString(perf.resolve("TimingEnforcement.kt"));
        String producer = Files.readString(perf.resolve("PerfMachineDemand.kt"));

        assertTrue(enforcement.contains("PerfMachineDemand.MAX_FOREIGN_CPU_UTILISATION"),
                "TimingEnforcement no longer reads the foreign-demand floor, so the recorded field gates nothing "
                        + "and an absolute anchor can again be levelled from a contended capture (IG154a)");
        assertTrue(enforcement.contains("foreignDemand"),
                "the absolute-anchor rule no longer takes a foreign-demand argument");
        assertTrue(producer.contains("foreignCpuUtilisation"),
                "the recorder no longer writes the field the gate above is keyed on");
    }

    /**
     * Both load accessors live in one file, because each computes its delta from its own previous call.
     *
     * <p>A second caller anywhere in this process would silently shorten the window an observation is credited
     * with, and there is no runtime symptom: the reading stays well-formed and describes a span nobody intended.
     * That is exactly the class a guard has to carry, since no test can see it.
     */
    @Test
    void theMachineDemandAccessorsAreCentralised() throws IOException {
        Path perf = CHECKOUT.resolve("adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf");
        assertTrue(Files.isDirectory(perf), () -> "the perf source tree moved: " + perf);

        List<String> offenders = new ArrayList<>();
        try (var walk = Files.walk(perf)) {
            for (Path source : walk.filter(p -> p.toString().endsWith(".kt")).toList()) {
                if (source.getFileName().toString().equals("PerfMachineDemand.kt")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(source);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    // COMMENTS ARE EXEMPT, and narrowing to code is the point rather than a convenience: the
                    // hazard is a second CALL consuming the delta, and prose naming the accessor — which the
                    // sibling tests do, because the absence rules cannot be explained without it — consumes
                    // nothing. Both comment shapes, since a javadoc continuation and a `//` line are different
                    // prefixes and filtering for one silently exempts the other.
                    String code = line.trim();
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                        continue;
                    }
                    // `os.cpuLoad` / `os.processCpuLoad` in Kotlin property form, `getCpuLoad()` /
                    // `getProcessCpuLoad()` in Java spelling — both, so a call written either way is caught.
                    if (code.contains("cpuLoad") || code.contains("CpuLoad")) {
                        offenders.add(source.getFileName() + ":" + (i + 1) + " " + code);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, () -> "a CPU-load accessor is called outside PerfMachineDemand:\n  "
                + String.join("\n  ", offenders)
                + "\n\nBoth accessors measure from their OWN previous call, so a second caller shortens the window "
                + "every observation is credited with — well-formed, wrong, and invisible at runtime. Route the "
                + "reading through PerfMachineDemand.reading() and pass it where it is needed.");
    }
}
