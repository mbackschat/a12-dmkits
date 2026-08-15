package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>Dated evidence belongs in a results log, never in a findings doc.</b> The two homes are set by their own
 * preambles: {@code INTERPRETER-PERFORMANCE-RESULTS.md} is "the dated evidence for interpreter performance: one
 * dated checkpoint entry per measurement session, newest first", while {@code INTERPRETER-FINDINGS.md} is "a
 * rationale record, not a status surface — the timeless fact, the decision, the why". DOC-DISCIPLINE §5 states
 * the same split for every spec: "Specs hold the stable method; measured results live in a dated log."
 *
 * <p><b>Why a guard.</b> This was violated on 2026-08-11: three hosted runs' medians, wall times, run ids and
 * exceedance counts were written into IF280 rather than the results log, and it took the owner noticing to catch
 * it. The misfile is attractive precisely because the finding is where the reasoning lives, so the numbers that
 * motivated it feel like they belong beside it — they do not, because they go stale on the next machine or the
 * next day, and a findings doc is read as durable.
 *
 * <p><b>What is asserted here — the STRUCTURAL forms only:</b> a results-log entry heading
 * ({@code ### Checkpoint <date> …}) must not appear in a findings doc (0 in all three); a CI run URL must not
 * appear in one (0 in all three); and every dated checkpoint sits in the RESULTS half, none in the METHOD half
 * (0 and 35 as of 2026-08-16), so dated evidence cannot drift into the method sections. The last assertion was
 * the weaker within-file form — "below the {@code ## Results log} heading" — until the 2026-08-15 split made
 * the two halves separate files; that heading no longer exists in either.
 *
 * <p>Placement is all this guards. Whether the addresses <i>inside</i> those two files resolve — the catalog
 * ids the results log cites, and the newest-first order a reader relies on to stop — is
 * {@link PerformancePairAddressingGuardTest}, and both were broken by the same split without failing here.
 *
 * <p><b>What this deliberately does NOT guard, measured rather than assumed.</b> The judgment itself — is this
 * paragraph dated evidence or a timeless argument? — has no reliable textual marker, and two candidates were
 * tested and rejected: a <em>run-id-shaped number</em> is legitimate in a findings doc (a 17-digit precision
 * literal in the number-fidelity finding, a public build id cited as provenance), and a <em>table of
 * measurements</em> is too (IF279's {@code 4 - 3f} derivation table is an argument, and a guard that fails honest
 * content gets disabled, which is worse than no guard — the reasoning
 * {@link KernelDocsBasisContractTest} already records). That judgment is carried by the decision test in
 * {@code docs/CLAUDE.md} instead: <i>would this sentence still be true if the same code were measured tomorrow, or
 * on another machine?</i> If not, it is dated evidence.
 *
 * <p>Rides {@code docGuardTest}, whose input set already declares the whole {@code docs/*.md} tree, so a doc edit
 * re-runs this lane alone and cannot restore from the build cache into a false green.
 */
class DocsEvidencePlacementGuardTest {

    /** The rationale records. Each is read as durable knowhow, so a machine- or day-dependent number rots there. */
    private static final List<Path> FINDINGS = List.of(
            Path.of("../docs/INTERPRETER-FINDINGS.md"),
            Path.of("../docs/KERNEL-FINDINGS.md"),
            Path.of("../docs/API-FINDINGS.md"));

    private static final Path METHOD = Path.of("../docs/INTERPRETER-PERFORMANCE.md");

    private static final Path RESULTS = Path.of("../docs/INTERPRETER-PERFORMANCE-RESULTS.md");

    /** The results-log entry shape: `### Checkpoint 2026-08-11 — …`. */
    private static final Pattern CHECKPOINT_HEADING =
            Pattern.compile("(?m)^#{2,4} +Checkpoint +\\d{4}-\\d{2}-\\d{2}");

    /** A link to one CI run — provenance for a measurement, so it belongs with the measurement. */
    private static final Pattern RUN_URL = Pattern.compile("actions/runs/\\d+");

    @Test
    void aFindingsDocCarriesNoResultsLogEntry() throws IOException {
        for (Path doc : FINDINGS) {
            Matcher found = CHECKPOINT_HEADING.matcher(read(doc));
            assertTrue(!found.find(), () -> doc.getFileName()
                    + " carries a results-log entry heading (\"" + found.group() + "\"). A dated checkpoint is "
                    + "evidence, and evidence lives in INTERPRETER-PERFORMANCE-RESULTS.md — this doc is "
                    + "a rationale record, so a reader takes what it says as durable. Move the numbers, keep the "
                    + "reasoning, and link the checkpoint");
        }
    }

    @Test
    void aFindingsDocDoesNotSourceItselfFromOneCiRun() throws IOException {
        for (Path doc : FINDINGS) {
            Matcher found = RUN_URL.matcher(read(doc));
            assertTrue(!found.find(), () -> doc.getFileName() + " cites a CI run URL (" + found.group()
                    + "). A single run's identity is the provenance of a measurement, so it belongs beside that "
                    + "measurement in the results log, not in the doc that states the timeless conclusion");
        }
    }

    /**
     * The method doc carries NO dated checkpoint, and the results doc carries them all.
     *
     * <p>Until 2026-08-15 both halves were one file and this asserted the weaker within-file form — every
     * checkpoint below the {@code ## Results log} heading. The split makes the invariant simpler and stronger:
     * a dated measurement in the method doc is now not merely misplaced within a file, it is in the wrong file.
     * The second assertion is what stops the first passing vacuously — without it, deleting or renaming the
     * results doc satisfies "the method doc has no checkpoints" perfectly.
     */
    @Test
    void everyDatedCheckpointSitsInTheResultsDocAndNoneInTheMethodDoc() throws IOException {
        Matcher strayed = CHECKPOINT_HEADING.matcher(read(METHOD));
        assertFalse(strayed.find(), () -> "INTERPRETER-PERFORMANCE.md carries a dated checkpoint (\""
                + strayed.group() + "\"), but it is the METHOD half — the stable contract, harness and gate "
                + "mechanics. Dated evidence belongs in INTERPRETER-PERFORMANCE-RESULTS.md (DOC-DISCIPLINE: "
                + "specs hold the method, a dated log holds the numbers)");

        assertTrue(CHECKPOINT_HEADING.matcher(read(RESULTS)).find(),
                "INTERPRETER-PERFORMANCE-RESULTS.md must carry the checkpoints — it is the home every findings "
                + "doc points dated evidence at, and an empty one makes the assertion above vacuous");
    }

    private static String read(Path relative) throws IOException {
        return Files.readString(locate(relative));
    }

    private static Path locate(Path relative) {
        if (Files.exists(relative)) {
            return relative;
        }
        Path fromRoot = Path.of(relative.toString().replaceFirst("^\\.\\./", ""));
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
