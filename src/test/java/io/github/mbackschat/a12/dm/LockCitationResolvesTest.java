package io.github.mbackschat.a12.dm;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every test class a findings document cites must exist — the mechanism that points a published claim at the
 * test bearing on it.
 *
 * <p><b>Why this guard exists.</b> a12-kernel-lean caught us citing `MixedScopeStarPreservationTest` in a
 * handback after the very revision we cited had deleted it, and observed the general shape: <em>"a test that
 * contradicted a published claim sat green for weeks, because nothing points a claim at the tests that bear on
 * it. Neither of us has a mechanism for that."</em> Within one week that cost a wrong published clause in each
 * direction — our index-keying conjunct, which `GroupListDiagnosticIdentityLawsTest` had been contradicting all
 * along, and our `FirstFilledValue` date-family row, which our own `FieldValuesNotUniqueAdmissionLawsTest`
 * already held the separating pair for. A citation that resolves to nothing is the cheap half of that problem,
 * and it is the half a machine can check — provided it checks the right names, which took one wrong version to
 * learn (see {@link #LOCK_CONTEXT}). Scoped to lock assertions it covers <b>460 distinct test classes across 837
 * citation occurrences</b> in these documents (2026-08-16), all of which resolve today; the three exemptions are
 * each a documented non-citation rather than a defect.
 *
 * <p><i>Corrected 2026-08-16:</i> this read <b>~640</b>, which matches <b>neither</b> quantity — the signature of a
 * figure taken once under a definition that was never written down. Both numbers are given here for that reason:
 * {@code seen} is a set of distinct names, so the floor below and this sentence would otherwise be about different
 * things. A derived count in a javadoc has no keeper (the recompute register covers docs, not source), so state
 * the quantity and the date, and re-derive before citing.
 *
 * <p><b>What this does NOT do, stated so the guard is not oversold.</b> It checks that a cited class
 * <em>exists</em>, never that the class <em>asserts the claim it is cited for</em> — that is not mechanically
 * checkable, and a guard implying otherwise would be worse than none. What it buys is that a rename or deletion
 * can no longer quietly strand the prose, which is exactly how the two incidents above began.
 *
 * <p><b>A glob is not a citation.</b> `*DiffTest`, `…DiffTest` and `--tests "*PerfTest"` are patterns describing
 * a family, so a name preceded by `*` or `…` is skipped. A package-qualified citation
 * (`adapter.laws.NumberRenderChannelKernelTest`) is a citation and is checked, so `.` is not a skip marker.
 */
class LockCitationResolvesTest {

    /** Docs whose prose names the locks. Kept explicit: a doc lands here when it starts citing tests. */
    private static final Checkout CHECKOUT = Checkout.locate();

    private static final List<String> CITING_DOCS = List.of(
            "docs/KERNEL-FINDINGS.md",
            "docs/INTERPRETER-FINDINGS.md",
            "docs/API-FINDINGS.md",
            // Joined 2026-09-04: the gap ledger cites the lock each open row rests on, and a cold
            // review found two of those citations standing outside every guard in the repo.
            "docs/DEV-API-GAPS.md",
            "docs/MVK-LEDGER.md",
            "docs/KERNEL-SEMANTICS.md",
            "docs/INTERPRETER-PERFORMANCE.md",
            "docs/INTERPRETER-PERFORMANCE-RESULTS.md",
            // The catalog is not prose but it IS a published claim surface — `dmtool operators <id>` serves its
            // constraints to any agent that asks, and a wrong line there is what started this. A constraint that
            // names its lock is covered by exactly the same rule.
            "rulekit/src/main/resources/catalog/operators.json");

    /**
     * Only names asserted as a LOCK are checked, and that scope is the finding rather than a convenience.
     *
     * <p>The first version checked every {@code *Test} name in these documents and reported five "dead"
     * citations that were all deliberate history — {@code "supersedes the quarantined X"}, {@code "X became Y"},
     * {@code "the retired Z"}. Those SHOULD not resolve; a findings doc narrates what changed, and forcing every
     * mention to exist would delete true statements. What must resolve is a name offered as the evidence a live
     * claim rests on, which is exactly the construct this pair extracts.
     */
    private static final Pattern LOCK_CONTEXT =
            Pattern.compile("\\(Locked[^)]*\\)|\\blocked (?:branch-for-branch )?by ([^.;)]*)",
                    Pattern.CASE_INSENSITIVE);

    /** A name preceded by a glob or ellipsis describes a FAMILY of tests, not one of them. */
    private static final Pattern CITATION = Pattern.compile("(?<!\\*)(?<!…)\\b([A-Z][A-Za-z0-9]*Test)\\b");

    private static final Map<String, String> ALLOWLIST = Map.ofEntries(
            entry("SomeTest", "the placeholder in the doc-discipline worked example of how to cite a lock — "
                    + "a template, not a claim about a real class"),
            entry("DiffTest", "the CATEGORY word used as a common noun (\"locked by the own-domain DiffTest\"), "
                    + "naming the family rather than a member; no class is called exactly this"),
            entry("DocInstanceNumberFidelityTest", "history, not a live lock: the sentence is ABOUT the retired "
                    + "DocInstance mapper and records what used to cover it. The class went with the mapper, so "
                    + "the citation is correct precisely by not resolving"));

    @org.junit.jupiter.api.Test
    void everyTestClassCitedByAFindingsDocExists() throws IOException {
        Set<String> live = liveTestClasses();
        Map<String, Set<String>> unresolved = new LinkedHashMap<>();
        Set<String> seen = new TreeSet<>();

        for (String doc : CITING_DOCS) {
            Path path = CHECKOUT.resolve(doc);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            Set<String> missing = new TreeSet<>();
            Matcher lock = LOCK_CONTEXT.matcher(Files.readString(path));
            while (lock.find()) {
                Matcher matcher = CITATION.matcher(lock.group());
                while (matcher.find()) {
                    String cited = matcher.group(1);
                    seen.add(cited);
                    if (!live.contains(cited) && !ALLOWLIST.containsKey(cited)) {
                        missing.add(cited);
                    }
                }
            }
            if (!missing.isEmpty()) {
                unresolved.put(doc, missing);
            }
        }

        assertEquals(Map.of(), unresolved,
                () -> "a findings doc cites a test class that does not exist, so the claim it supports now rests "
                        + "on nothing a reader can open. Either the class was renamed — update the prose — or it "
                        + "was deleted, in which case the claim needs a live lock or an explicit retraction. This "
                        + "is how a published clause and the test contradicting it drift apart unnoticed:\n  "
                        + unresolved);

        assertTrue(seen.size() > 300,
                "only " + seen.size() + " lock citations found — the context pattern has stopped matching, and "
                        + "the assertion above is vacuous until it does again");
        List<String> stale = ALLOWLIST.keySet().stream().filter(name -> !seen.contains(name)).sorted().toList();
        assertEquals(List.of(), stale,
                () -> "allowlisted name(s) no longer cited anywhere, so the exemption is dead weight and its "
                        + "reason can no longer be checked — remove the row:\n  " + stale);
    }

    /**
     * The control. This guard is a scanner over prose, and a scanner that stops matching reports a clean bill of
     * health forever. Both halves matter: the pattern must still find citations, and the live-class enumeration
     * must still find classes — an empty {@code live} set would make every citation "missing" rather than
     * vacuously fine, but an over-broad one would make every citation resolve.
     */
    @org.junit.jupiter.api.Test
    void theScannerAndTheEnumerationBothStillWork() throws IOException {
        assertTrue(CITATION.matcher("*(Locked: `validate.laws.SampleLawsTest`.)*").find(),
                "the citation pattern no longer matches a citation — every assertion here is vacuous until it does");
        assertTrue(!CITATION.matcher("the `*DiffTest` family").find()
                        || !CITATION.matcher("the `*DiffTest` family").group(1).equals("DiffTest"),
                "a globbed family name must not be read as a citation, or the guard reports prose as broken");

        Set<String> live = liveTestClasses();
        assertTrue(live.size() > 500, "only " + live.size() + " test classes found — the enumeration has stopped "
                + "seeing source sets, which would make every citation look dead");
        assertTrue(live.contains("LockCitationResolvesTest"),
                "the enumeration cannot see this very class, so it is not reading the test trees it claims to");
    }

    /** Every test source set, including the interpreter's KMP commonTest, jvmTest and jsTest trees. */
    private static Set<String> liveTestClasses() {
        return CHECKOUT.testSources().stream()
                .map(path -> {
                    String name = path.getFileName().toString();
                    return name.substring(0, name.lastIndexOf('.'));
                })
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

}
