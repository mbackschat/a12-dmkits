package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>A corpus count stated in a source comment must say WHEN it was measured.</b> This is the one place a
 * derived figure has no keeper: {@code docs/CLAUDE.recompute.md} registers figures stated in <i>docs</i>, and a
 * count living in a {@code *.java}/{@code *.kt} comment is a source file, so nothing recomputes it, no guard
 * fails on it, and it drifts silently for as long as nobody happens to look.
 *
 * <p><b>Measured 2026-08-16, which is why this is a guard rather than a preference.</b> Run over the pre-slice tree
 * ({@code f6f0af0d^}) this rule matched <b>16</b> figures, of which <b>12</b> were non-exempt and undated; the
 * sweep changed the value of <b>11</b> and <b>10</b> of those were genuinely stale. Examples — a relative-link
 * count reading 1 513 against a
 * live 2 432 <i>while contradicting its own floor comment twelve lines below</i>; {@code ## §9 supplement}
 * reading 25 against 32; the §-mirror reading ~110 against 144; API-FINDINGS reading ~100 findings and ~113
 * citing sites against 125 and 147; lock citations reading ~640 against 460 distinct classes in 837
 * occurrences; the doc tree reading ~50 and ~55 against 66. Every one had been correct when written.
 *
 * <p><i>The eleventh correction was itself wrong</i>, and it is recorded here because it is the sharpest evidence
 * for this guard's own thesis: {@code OperatorIndexGuardTest}'s floor rationale was changed from a correct ~110
 * to <b>106</b> — the count of operators this index lists, not the catalog count the floor is asserted on. The
 * unnamed quantity claimed a victim inside the very commit that set out to name quantities. A cold review caught
 * it; no rule here could have, which is exactly why the claim below is DATING and not correctness.
 *
 * <p><b>What this guard claims, and what it does not.</b> It enforces <i>dating</i>, never <i>correctness</i>:
 * no rule here can know what an arbitrary comment's number was supposed to count. That limit is the point
 * rather than a weakness, because the deeper defect the sweep exposed was not drift but the <b>unnamed
 * quantity</b> — not one stale figure said whether it counted occurrences, distinct ids, or files, or over what
 * scope, so a first attempt to check them produced four confident comparisons that were all worthless (raw
 * greps returning 295/77/110/427 against claims of ~250/~40/~66/~20, each counting a different thing; one of
 * those claims was <i>exactly right</i>). A dated figure is a checkable claim. An undated one is not a fact.
 *
 * <p><b>Scope is deliberately narrow, because a guard that fails honest content gets disabled.</b> Only
 * <i>corpus-inventory</i> nouns count — things enumerated over this repository. Fixture sizes are mostly excluded
 * by construction: under a looser list admitting {@code rows}, the clear majority of hits were test-fixture sizes
 * and every one of them was honest, which is how a guard earns being switched off. The noun list is the only
 * filter, though, so a fixture that happens to be counted in a corpus noun does reach the rule — exactly one does
 * ({@code 100 random docs}), and it is exempt below rather than pretended away.
 *
 * <p><b>The qualifier slot was widened on 2026-08-16, and the measurement that had justified rejecting it was
 * wrong.</b> The first version admitted only a closed {@code distinct|live|bare} set, which meant an arbitrary
 * adjective between the number and the noun hid the figure — so this guard could not see <b>its own founding
 * case</b>, {@code 1513 relative targets}. The note rejecting the widening claimed its extra hits were
 * "narratives about a past change or fixture sizes, <i>not live inventories</i>". Measured over the tree, the open
 * slot adds <b>21</b> matches: <b>10</b> already dated, <b>5</b> live inventories, and <b>6</b> non-inventory
 * figures now exempt below. The claim was false in both directions — the widening does reach live inventories,
 * and one of them was <b>rotting</b>.
 *
 * <p><i>That rotting figure is the sharpest evidence this guard has.</i> {@code PerfGateTest} read "this class's
 * 19 call sites"; 19 is the count of <b>occurrences</b> of the helper's token, one of which is its own
 * declaration, so the live call-site count is <b>18</b>. It was mis-certified <b>while being verified for this
 * very edit</b> — a {@code grep -c} was read as a call-site count — by the identical occurrences-vs-instances
 * slip recorded three paragraphs above. Two independent confusions of the same unnamed quantity, inside the one
 * change that exists to name quantities, is why the remedy here is dating rather than trust.
 *
 * <p><b>Six exemptions against fifteen policed figures, and that ratio is the thing to watch.</b> A guard whose
 * exemption list grows faster than its findings is a permissions list wearing a guard's clothes. Two properties
 * keep it a classification instead: entries are keyed by <i>file <b>and</b> figure text</i> rather than by file,
 * because one file may hold both an exempt narrative and a policed inventory ({@code VerbDemonstrationTest} does);
 * and they are asserted fully used, so an entry cannot outlive the line it excuses. Five of the six share one
 * reason — {@code N call sites} in this estate is nearly always a <i>past-tense</i> account of a landed refactor
 * ("35 call sites duly passed a literal valid", "the other 52 had not", "what makes converting 26 a
 * representation change"), which has no live count to re-derive. A <i>new</i> undated {@code N call sites} still
 * fires.
 *
 * <p><b>Two further recall limits, stated rather than implied.</b> (1) The scan reads {@code .java}/{@code .kt} only, so a
 * corpus figure in a {@code .gradle}, {@code .sh} or {@code .py} comment is out of scope — four live ones sit in
 * the build files. (2) The noun list is closed, so a figure phrased with a noun outside it is invisible; widening
 * the list is a deliberate edit, and both limits mean this guard bounds the problem from one side only.
 *
 * <p><b>The date must sit in the SAME comment block</b>, not within a fixed line window. A ±3-line window was
 * tried first and mis-reported a correctly dated figure whose date sat four lines up. More importantly, the
 * sweep that motivated this guard <b>missed every single-line {@code /** … *}{@code /} comment</b> because it
 * filtered for lines beginning with {@code *} — which is exactly how three stale figures survived inside a file
 * whose other two were being fixed at the time. Both comment shapes are scanned here for that reason.
 *
 * <p>Rides {@code referentGuardTest}, which already declares the repo-wide source surface as its inputs.
 */
class DerivedFigureDatingGuardTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    /**
     * Nouns denoting something enumerated over this repository. A count of these is derived and rots; a count
     * of fixture rows or test cases does not. Kept as a closed list rather than a heuristic so the guard's
     * denominator is readable, and so widening it is a deliberate edit with its own measurement.
     */
    private static final String CORPUS_NOUNS =
            "entries|docs|sites|findings|citations|occurrences|operators|headings|ledgers|verbs|snippets"
                    + "|targets|records|sections|checkpoints|cites|scripts|demos|anchors";

    /**
     * A count over the corpus: an optional tilde, a number of two or more digits (or one grouped with thin
     * spaces), then one of the nouns above — with up to two lowercase qualifier words between them.
     *
     * <p><b>Why the slot is bounded at two.</b> Unbounded, the rule stops being a figure rule and becomes a digit
     * rule, matching across a whole clause to any noun that happens to follow. One word is what the founding case
     * needs, two leaves room for the estate's {@code N of the <noun>} form, and the bound is held by a control
     * below so it cannot quietly grow.
     *
     * <p>Deliberately described rather than illustrated. A literal example of the shape here would make this
     * guard report <b>itself</b>, and the first run did exactly that; the repo's discipline is to write around
     * the shape (as {@code ReferentDriftGuardTest} and {@code QuotedTitleCitationRatchetTest} both do) rather
     * than to exclude the guard's own file, which would be a hole of precisely the kind it exists to close.
     */
    private static final Pattern FIGURE = Pattern.compile(
            "(?:~\\s*)?\\b(?:\\d{1,3}(?:[  ]\\d{3})+|\\d{2,})\\s+(?:[a-z][a-z-]*\\s+){0,2}(?:"
                    + CORPUS_NOUNS + ")\\b");

    /**
     * Inline markup, removed before matching. This estate wraps a figure's number in bold tags and writes
     * identifiers in {@code @code} spans, so a rule reading the raw line sees no digit adjacent to the noun
     * and silently passes. That was not hypothetical: the first version of this guard went blind to <b>five</b>
     * figures the instant they were corrected, because the correction added the emphasis — and it could not see
     * its own founding case ({@code DocRelativeLinkGuardTest}'s 1513) for the same reason. Caught by cold review.
     */
    private static final Pattern MARKUP = Pattern.compile("</?[A-Za-z][^>]*>|\\{@\\w+\\s+|\\}");

    /** An ISO measurement date. Any of the estate's forms — bare, parenthesized, or "measured YYYY-MM-DD". */
    private static final Pattern MEASURED_ON = Pattern.compile("20\\d\\d-\\d\\d-\\d\\d");

    /**
     * Figures that are NOT live-corpus claims, each with the reason it is exempt. Asserted <b>fully used</b>
     * below, so an entry cannot outlive the line it excuses and quietly widen the rule.
     *
     * <p><b>Keyed by {@code <file>|<figure text>}, not by file.</b> A file may hold both an exempt narrative and a
     * policed inventory — {@code VerbDemonstrationTest} holds a past-tense call-site narrative <i>and</i> a live
     * preview-demo inventory — so a filename key would silently excuse the live one too. That is the failure mode
     * an exemption list is most prone to: it looks like one row and grants two permissions.
     */
    private static final Map<String, String> NOT_A_LIVE_CORPUS_CLAIM = Map.ofEntries(
            Map.entry("NotFoundRefusalTest.java|13 verbs",
                    "historical narrative: \"the bare stderr string 11 of 13 verbs returned BEFORE\" describes "
                            + "the pre-fix behaviour, so there is no live count to re-derive"),
            Map.entry("Operators.kt|53 entries",
                    "a source-code fact, not a doc-corpus one: `FunctionOp` has 53 entries and the compiler keeps "
                            + "that honest, unlike a count over markdown that nothing recomputes"),
            Map.entry("EntityPathTest.java|26 call sites",
                    "past-tense refactor narrative: \"what makes converting 26 call sites a representation change "
                            + "instead of a behaviour change\" describes one landed change; the number never moves "
                            + "again, and re-deriving it today would measure the wrong thing"),
            Map.entry("PerfNumberLocaleContractTest.kt|52 call sites",
                    "past-tense refactor narrative: \"the other 52 call sites had not\" is precisely the pre-fix "
                            + "state this guard removed, so there is deliberately no live 52 left to count"),
            Map.entry("ResultAlgebraTest.java|35 call sites",
                    "past-tense refactor narrative: the 35 sites that passed a literal `valid` are the defect the "
                            + "typed factories made unrepresentable, so the live count is zero by construction"),
            Map.entry("VerbDemonstrationTest.java|35 call sites",
                    "the same past-tense narrative as ResultAlgebraTest, restated on the behavioural backstop for "
                            + "that structural property — and the reason this map is keyed by figure text, since "
                            + "the same file's \"31 preview demos\" IS live and is policed"),
            Map.entry("WorkspaceOption.java|~60 call sites",
                    "counterfactual design rationale: \"no per-verb flag to thread through ~60 call sites\" counts "
                            + "the sites of a flag that was deliberately never added, so nothing derives it"),
            Map.entry("PerfCatalog.kt|100 random docs",
                    "a fixture size, not a corpus count: the fuzz generates 100 documents, a number set in the "
                            + "code beside the comment and changed with it"));

    /**
     * Vacuity floor. Measured at <b>46</b> matching figures on 2026-08-16, the day the qualifier slot was widened
     * from a closed three-word set (which matched 25). Set well below the measurement so ordinary editing does
     * not trip it, and well above the pre-widening total so a silent revert of the slot fails here.
     */
    private static final int MIN_FIGURES = 30;

    @Test
    void everyCorpusFigureInASourceCommentCarriesItsMeasurementDate() throws IOException {
        List<String> undated = new ArrayList<>();
        Set<String> exemptionsUsed = new TreeSet<>();
        Set<String> filesWithAnExemption = new TreeSet<>();
        Set<String> filesWithAPolicedFigure = new TreeSet<>();
        int figures = 0;

        for (Path file : sources()) {
            List<String> lines = List.of(Files.readString(file).split("\n", -1));
            for (int i = 0; i < lines.size(); i++) {
                if (!isComment(lines.get(i))) {
                    continue;
                }
                Matcher m = FIGURE.matcher(plain(lines.get(i)));
                if (!m.find()) {
                    continue;
                }
                figures++;
                String name = file.getFileName().toString();
                String figure = m.group().trim();
                if (NOT_A_LIVE_CORPUS_CLAIM.containsKey(name + "|" + figure)) {
                    exemptionsUsed.add(name + "|" + figure);
                    filesWithAnExemption.add(name);
                    continue;
                }
                filesWithAPolicedFigure.add(name);
                if (!MEASURED_ON.matcher(block(lines, i)).find()) {
                    undated.add(name + ":" + (i + 1) + "  \"" + figure + "\"");
                }
            }
        }

        int parsed = figures;
        assertTrue(parsed >= MIN_FIGURES,
                () -> "the rule matched only " + parsed + " corpus figures (floor " + MIN_FIGURES + ") — the "
                        + "pattern or the walk stopped working, and this guard is checking nothing");

        assertEquals(NOT_A_LIVE_CORPUS_CLAIM.keySet(), exemptionsUsed,
                "an exemption no longer matches any figure, so its written reason can no longer be checked — "
                        + "remove the row rather than leaving a permission nobody reviews");

        // The keying decision, made checkable. Exemptions are keyed by file AND figure text, and the javadoc calls
        // that load-bearing — but a javadoc calling something load-bearing is not evidence that anything holds it.
        // This asserts the estate still contains the shape that makes filename keying WRONG: a file carrying an
        // exempt figure alongside a policed one. Re-key by filename and this fails, naming the figure that would
        // have been silently excused.
        Set<String> mixed = new TreeSet<>(filesWithAnExemption);
        mixed.retainAll(filesWithAPolicedFigure);
        assertTrue(!mixed.isEmpty(), () -> "no file carries both an exempt figure and a policed one, so nothing "
                + "here distinguishes keying by <file>|<figure> from keying by <file> alone. Either the estate "
                + "changed and the finer key is now unjustified, or the key was quietly coarsened. Exempt files: "
                + filesWithAnExemption + "; policed files: " + filesWithAPolicedFigure.size());

        assertTrue(undated.isEmpty(), () -> "corpus figure(s) in a source comment with no measurement date in "
                + "the same comment block. A derived count in source has no keeper — the recompute register "
                + "covers docs, and no guard recomputes a comment — so an undated one rots invisibly (nine of "
                + "thirteen were stale when this landed). State what it counts and when it was measured, e.g. "
                + "\"460 distinct classes across 837 occurrences (2026-08-16)\":\n  "
                + String.join("\n  ", undated));
    }

    /**
     * Does-fire proof for the rule, over fabricated text rather than the live tree, so it keeps proving the
     * rule after the estate is clean. The negative cases matter as much: a guard that fired on a fixture size
     * or on a dated figure would fail honest content, and would be switched off rather than satisfied.
     */
    @Test
    void theRuleFiresOnAnUndatedCorpusFigureAndOnNothingElse() {
        List<String> dated = List.of("    /** the tree carries 66 docs (2026-08-16). */");
        List<String> undated = List.of("    /** the tree carries 66 docs. */");
        List<String> farDate = List.of(
                "    /**", "     * Measured 2026-08-16 through the guard's own rule.", "     *",
                "     * <p>The record holds 299 entries today.", "     */");
        List<String> fixture = List.of("    /** builds 50 rows and 4 cases. */");
        List<String> code = List.of("    int docs = countOf(66);");

        assertTrue(fires(undated).size() == 1, () -> "an undated corpus figure must fire, got " + fires(undated));
        assertTrue(fires(dated).isEmpty(), "a figure dated on its own line must not fire");
        assertTrue(fires(farDate).isEmpty(),
                "the date may sit anywhere in the SAME comment block — a fixed line window mis-reported exactly "
                        + "this shape, which is why the block is the unit");
        assertTrue(fires(fixture).isEmpty(),
                "a fixture size is not a corpus count; firing here is how a guard earns being disabled");
        assertTrue(fires(code).isEmpty(), "a figure in CODE is not a prose claim and is not in scope");
    }

    /**
     * The controls a cold review had to supply, each for a rule that was live and uncontrolled. A guard's
     * javadoc calling a design choice load-bearing is not evidence that anything holds it.
     */
    @Test
    void theBlockBoundaryTheSlashSlashShapeAndBothNumberFormsAreAllHeld() {
        // A1.1 — the block boundary must WIDEN no further than the comment. The prior fixture only proved it
        // does not narrow, so a block() returning the whole file passed both tests while making the rule
        // near-vacuous on a live tree, where almost every file carries a date somewhere.
        List<String> dateOutsideTheBlock = List.of(
                "    /** Measured 2026-08-16. */",
                "    int unrelated = 1;",
                "    /** the record carries 299 entries. */");
        assertTrue(fires(dateOutsideTheBlock).equals(List.of(3)),
                () -> "a date in a DIFFERENT comment block must not satisfy the figure, got "
                        + fires(dateOutsideTheBlock));

        // A1.2 — `//` is scanned, and is the dominant comment form in the Kotlin estate.
        assertTrue(fires(List.of("    // the tree carries 66 docs.")).size() == 1,
                "a `//` line comment is in scope; dropping that shape was invisible to every other control");
        assertTrue(fires(List.of("    // the tree carries 66 docs (2026-08-16).")).isEmpty(),
                "and a dated `//` figure must not fire");

        // A1.3 — both number forms and the qualifier group, none of which any live figure exercises.
        assertTrue(fires(List.of("    /** 2 432 targets. */")).size() == 1,
                "a thin-space-grouped number is a figure; that branch was dead and uncontrolled");
        assertTrue(fires(List.of("    /** 460 distinct entries and 81 bare sites. */")).size() == 1,
                "a one-word qualifier is inside the slot");

        // A3.5 — THE FOUNDING CASE, which this guard could not see until 2026-08-16. The qualifier slot was a
        // closed distinct|live|bare set, so `1513 relative targets` — the exact stale figure that motivated the
        // whole guard — was invisible to it. Flipping this assertion is what the widening bought.
        assertTrue(fires(List.of("    /** Measured when it landed: <b>1513</b> relative targets. */")).size() == 1,
                "the widened qualifier slot must catch the founding case's ACTUAL wording");

        // A3.6 — and the slot is BOUNDED at two words. Unbounded it stops being a figure rule and becomes a digit
        // rule, reaching across a whole clause to any noun that happens to follow.
        assertTrue(fires(List.of("    /** 66 of the docs. */")).size() == 1,
                "two qualifier words are inside the slot — the estate's `N of the <noun>` form");
        assertTrue(fires(List.of("    /** 66 of the shipped docs. */")).isEmpty(),
                "three are outside it; without this control the bound could quietly grow to any clause");

        // A3.3/A3.4 — emphasis must not hide a figure. Five corrected figures went invisible to the first
        // version of this rule precisely because the correction added <b>…</b>.
        assertTrue(fires(List.of("    /** carries <b>32</b> entries. */")).size() == 1,
                "markup around the number must not hide the figure — this is how the rule lost five of them");
        assertTrue(fires(List.of("    /** Measured when it landed: <b>1513</b> targets. */")).size() == 1,
                "markup must not hide the founding case's SHAPE");
    }

    /** A comment line with its inline markup removed, so emphasis cannot hide a figure. */
    private static String plain(String line) {
        return MARKUP.matcher(line).replaceAll("");
    }

    /** The rule, over one file's lines, returning the 1-based lines that would be reported. */
    private static List<Integer> fires(List<String> lines) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (isComment(lines.get(i)) && FIGURE.matcher(plain(lines.get(i))).find()
                    && !MEASURED_ON.matcher(block(lines, i)).find()) {
                hits.add(i + 1);
            }
        }
        return hits;
    }

    /**
     * The contiguous comment block containing {@code index}. Walking outward — rather than taking a fixed
     * window — is what lets a block state its date once in a preamble and carry several figures beneath it.
     */
    private static String block(List<String> lines, int index) {
        int from = index;
        while (from > 0 && isComment(lines.get(from - 1))) {
            from--;
        }
        int to = index;
        while (to + 1 < lines.size() && isComment(lines.get(to + 1))) {
            to++;
        }
        return String.join("\n", lines.subList(from, to + 1));
    }

    /**
     * Both comment shapes, and the second one is the whole reason this guard exists: a sweep that filtered for
     * lines starting with {@code *} saw only multi-line continuations, so every single-line {@code /** … *}{@code /}
     * was structurally invisible to it and three stale figures survived in a file being edited at the time.
     */
    private static boolean isComment(String line) {
        String s = line.stripLeading();
        return s.startsWith("*") || s.startsWith("//") || s.startsWith("/*");
    }

    /**
     * {@code .claude} carries this project's own review worktrees ({@code .claude/worktrees/<name>/}), which are
     * FULL checkouts of this repository at other revisions. Descending into one makes the primary checkout go red
     * on a sibling's content, and — worse — lets a worktree copy satisfy {@link #MIN_FIGURES} and the fully-used
     * exemption assertion on its own, which is precisely the vacuity the floor exists to prevent.
     * {@code DocRelativeLinkGuardTest} had already been bitten by worktrees; that lesson was not applied here
     * until a cold review re-found it from a worktree it was itself running in.
     */
    /** Files the walk could not resolve (dangling symlinks). Reported, never silently dropped. */
    private static final Set<String> unreadableSources = new TreeSet<>();

    private static List<Path> sources() throws IOException {
        Set<Path> found = new LinkedHashSet<>();
        for (Path file : RepoWalk.trackedFiles(ROOT)) {
            String name = file.getFileName().toString();
            if (name.endsWith(".java") || name.endsWith(".kt")) {
                try {
                    found.add(file.toRealPath());
                } catch (IOException unreadable) {
                    // A dangling symlink is not content this guard can judge, and crashing the walk on one
                    // reports a broken link as a figure defect. Sibling guards still throw here; that is a
                    // separate fix.
                    unreadableSources.add(file.toString());
                }
            }
        }
        return List.copyOf(found);
    }
}
