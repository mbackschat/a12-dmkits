package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The `-GAPS` open-section marker guard.</b> "What remains?" is the one question an agent cannot answer
 * with a single grep, because the answer is spread over ten ledgers (2026-09-26) and most of them are empty. Before
 * this guard the empty state was written five different ways — {@code _None currently._}, {@code _None._},
 * {@code (none)}, {@code _None open._}, and twice as bare prose with no explicit statement at all — so
 * {@code grep -H '^_None open._' docs/*GAPS*.md} returned two of eight files and silently under-reported.
 *
 * <p>The contract: <b>every {@code docs/*-GAPS.md} open section either lists at least one item or carries
 * the exact marker {@code _None open._}</b>. That makes the roll-up grep exact and, just as importantly,
 * makes a drained ledger <i>positively assert</i> its emptiness instead of leaving a reader to infer it
 * from absence.
 *
 * <p>Item detection is deliberately permissive — the ledgers use three different row shapes ({@code - **IGn
 * — }, {@code ### IRG2 — }, and table rows) and a new ledger may invent a fourth. A section with any of
 * those is treated as non-empty and needs no marker; the failure mode is therefore a loud, correctable
 * "add the marker or the row", never a silent pass.
 *
 * <p>It reads repo docs at runtime, so it runs in the {@code :cli} {@code docGuardTest} task, whose input
 * set already declares the docs tree (TESTING-SPEC "Tests that read repo files at runtime") — a ledger
 * edit re-runs only this lane and cannot restore from the build cache into a false green.
 */
class GapLedgerTemplateGuardTest {

    private static final Path DOCS = Path.of("../docs");

    /** The positive assertion of emptiness. Exact text on its own line, so the roll-up grep is exact. */
    private static final String MARKER = "_None open._";

    /** Sanity floor: the estate has ten `-GAPS` ledgers (2026-09-26); a scan finding almost none checks nothing. */
    private static final int MIN_LEDGERS = 6;

    /**
     * <b>The shared template.</b> The ledgers do one job, but they had drifted apart accidentally —
     * eight H1 phrasings, three different labels for the lifecycle paragraph, and open sections that mixed
     * the marker with paragraphs of closure history. Uniform shape is what lets one grep answer "what
     * remains?" across the family, so the shape is checked rather than merely agreed:
     *
     * <ol>
     *   <li>the H1 ends with {@code — gaps};</li>
     *   <li>the preamble carries {@code > **What this is}, {@code > **Lifecycle.} and
     *       {@code > **Current state.} blockquote paragraphs;</li>
     *   <li>an open section is titled {@code Open} (a suffix after it is allowed only where a numbered
     *       section id is externally cited).</li>
     * </ol>
     *
     * <p>Section <b>numbers</b> are deliberately NOT normalised: {@code FEATURE-GAPS §4},
     * {@code DEV-API-GAPS §2/§3}, {@code INTERPRETER-GAPS §3} and {@code SCHEMAKIT-GAPS §3} are cited from
     * production sources and docs, so renumbering would break <b>27</b> live citations of those four ids
     * (2026-08-16; occurrences, repo-wide: 10 + 9 + 3 + 5). Uniformity therefore lives in the titles and the
     * preamble, not in the ordinals.
     */
    @Test
    void everyGapLedgerFollowsTheSharedTemplate() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path ledger : ledgers()) {
            String name = ledger.getFileName().toString();
            List<String> lines = Files.readAllLines(ledger);
            String h1 = lines.isEmpty() ? "" : lines.get(0);
            if (!h1.startsWith("# ") || !h1.endsWith(" — gaps")) {
                violations.add(name + " — H1 must read `# <scope> — gaps`, found: " + h1);
            }
            String body = String.join("\n", lines);
            for (String required : List.of("> **What this is", "> **Lifecycle.", "> **Current state.")) {
                if (!body.contains(required)) {
                    violations.add(name + " — preamble is missing a `" + required + "…` paragraph");
                }
            }
            if (lines.stream().noneMatch(GapLedgerTemplateGuardTest::isOpenSection)) {
                violations.add(name + " — no section titled `Open`");
            }
        }
        assertTrue(violations.isEmpty(), () -> "`-GAPS` ledgers that break the shared template (docs/CLAUDE.md "
                + "\"the -GAPS template\"):\n  " + String.join("\n  ", violations));
    }

    @Test
    void everyGapLedgerOpenSectionListsItemsOrAssertsEmptiness() throws IOException {
        List<Path> ledgers = ledgers();
        assertTrue(ledgers.size() >= MIN_LEDGERS,
                () -> "found only " + ledgers.size() + " `-GAPS` ledgers under " + DOCS + " (floor " + MIN_LEDGERS
                        + ") — wrong working dir or a renamed family? The guard would check nothing.");

        List<String> violations = new ArrayList<>();
        for (Path ledger : ledgers) {
            violations.addAll(audit(ledger.getFileName().toString(), Files.readString(ledger)));
        }
        assertTrue(violations.isEmpty(), () -> "`-GAPS` open sections that neither list an item nor assert "
                + "emptiness with the exact marker `" + MARKER + "` on its own line (add the marker, or the "
                + "item row it is missing):\n  " + String.join("\n  ", violations));
    }

    /**
     * <b>Every LIVE open item is citable by id.</b>
     *
     * <p>The addressing rule (docs/CLAUDE.md "Addressing — cite by id, never by title text") exists because a
     * heading or a title phrase moves the moment a claim sharpens, and two findings had already been cited into
     * rot that way. It was stated for the findings docs and the ledgers alike — and then went unchecked for the
     * ledgers, so {@code CLI-CONFORMANCE-GAPS} spent months as the one ledger carrying <em>live</em> rows with
     * no ids at all. Nothing caught it because the sibling bullet-style ledgers had all been drained in the
     * meantime, which made the convention look universal while it was only holding where rows happened to be
     * new. Its seven rows could be referred to only as "the patterns payload row" — exactly the citation the
     * rule forbids, in the ledger most actively being worked.
     *
     * <p>The check is deliberately shape-tolerant: an item may be an {@code ### IAG9 — } / {@code ### CG3 — }
     * heading, a {@code - **IG9 — …**} bullet, or a table row whose first cell is an id ({@code FEATURE-GAPS}
     * uses that). What it refuses is an open item with <em>no</em> id in any of those positions, because that
     * is the one shape nobody can cite. A new ledger inventing a fourth id-bearing shape adds a branch here;
     * it does not get to omit the id.
     */
    @Test
    void everyLiveOpenItemCarriesAnIdentifier() throws IOException {
        List<String> unidentified = new ArrayList<>();
        for (Path ledger : ledgers()) {
            unidentified.addAll(auditIds(ledger.getFileName().toString(), Files.readString(ledger)));
        }
        assertTrue(unidentified.isEmpty(), () -> "these OPEN gap items carry no identifier, so they can only be "
                + "cited by title text — the rot docs/CLAUDE.md's addressing rule exists to prevent. Give each an "
                + "`### <FAM><n> — <title> <a id=\"famN\"></a>` heading (or an id-first table cell) and register "
                + "the family in that table:\n  " + String.join("\n  ", unidentified));
    }

    /**
     * <b>No identifier appears twice in one ledger's Open section.</b>
     *
     * <p>A duplicated row is worse than a missing one: both copies look authoritative, and an agent updating
     * "the" row silently leaves a stale twin behind that still answers "what remains?" with the wrong answer.
     *
     * <p>Not hypothetical, and the mechanism is worth naming because it will recur: on 2026-08-22 a session
     * retired two gaps by slicing the file between the row being removed and the NEXT row's marker. The rows had
     * meanwhile been reordered, so the second slice had {@code end < start} — which in Python string slicing
     * <em>duplicates</em> the span instead of deleting it. The result was G61 twice and a shipped G60 still
     * listed, while the section's own "Current state" header said one gap was open. Every other guard passed,
     * including the one checking that shipped rows are retired, because it audits leads rather than counting ids.
     */
    @Test
    void noOpenSectionListsTheSameIdentifierTwice() throws IOException {
        List<String> duplicated = new ArrayList<>();
        for (Path ledger : ledgers()) {
            duplicated.addAll(auditDuplicateIds(
                    ledger.getFileName().toString(), Files.readString(ledger)));
        }
        assertTrue(duplicated.isEmpty(), () -> "a gap id is listed more than once, so one copy is stale and both "
                + "read as authoritative — delete the duplicate and reconcile the section's 'Current state' "
                + "count:\n  " + String.join("\n  ", duplicated));
    }

    /**
     * <b>A shipped gap must be RETIRED from the Open section, in every {@code -GAPS} ledger.</b>
     *
     * <p>The lifecycle every ledger states is that a gap lives there only while open: when it ships its row is
     * DELETED, its rationale graduates to the owning spec/finding, and git carries the what/when. The section
     * invariant in {@code docs/CLAUDE.md} says the same from the other side — §1 holds exactly one live status,
     * so a fully-shipped entry belongs in none of the sections and "a fully-FIXED entry anywhere in this ledger
     * is a bug in <em>this doc</em>".
     *
     * <p>Both were prose only, and prose did not hold: a 2026-08-14 session left three shipped rows sitting under
     * {@code ## 1. Open} annotated "FIXED", which is precisely the shape that makes a ledger stop answering
     * "what remains?" — the one question it exists for. The owner caught it, not the build. This is that rule
     * mechanized.
     *
     * <p><b>Deliberately narrow.</b> It fires on a shipped marker in an <em>item lead</em> only, because a row
     * legitimately says what a fix already landed when the REST of it is still open — CG15 and CG16 both do, and
     * flagging those would push the guard toward being disabled. The lead is where "this row is done" is
     * claimed, and a row whose lead says FIXED has nothing left to track.
     */
    @Test
    void noShippedItemRemainsInAnOpenSection() throws IOException {
        List<String> shipped = new ArrayList<>();
        for (Path ledger : ledgers()) {
            shipped.addAll(auditShipped(ledger.getFileName().toString(), Files.readString(ledger)));
        }
        assertTrue(shipped.isEmpty(), () -> "these OPEN items announce themselves as already shipped, so the "
                + "section no longer answers \"what remains?\". DELETE each row — its rationale graduates to the "
                + "owning spec/finding and git carries the what/when — or, if only PART of it shipped, rewrite "
                + "the lead to state the open remainder:\n  " + String.join("\n  ", shipped));
    }

    /** Does-fire proof for the shipped-marker check, including the partial-fix shape that must NOT fire. */
    @Test
    void theShippedGuardFiresOnARetiredRowLeftInOpen() {
        String fixedBullet = "# x — gaps\n\n## 1. Open\n\n- **CG14 — FIXED 2026-08-14.** it shipped\n";
        String doneHeading = "# x — gaps\n\n## 1. Open\n\n### IG9 — DONE 2026-08-14 <a id=\"ig9\"></a>\n\nbody\n";
        String stillOpen = "# x — gaps\n\n## 1. Open\n\n- **CG15 — the arms that still lose a star.** body\n";
        String partialLead = "# x — gaps\n\n## 1. Open\n\n- **CG16 — the diff surfaces still read exactly.**"
                + " The readers are FIXED; these three are not.\n";
        String deferredSection = "# x — gaps\n\n## 2. Deferred\n\n- **IG9 — FIXED 2026-08-14.** parked\n";

        assertTrue(!auditShipped("a.md", fixedBullet).isEmpty(), "a FIXED lead under Open must fire");
        assertTrue(!auditShipped("b.md", doneHeading).isEmpty(), "a DONE heading under Open must fire");
        assertTrue(auditShipped("c.md", stillOpen).isEmpty(), "an ordinary open row must not fire");
        assertTrue(auditShipped("d.md", partialLead).isEmpty(),
                "a row whose LEAD states the open remainder must not fire just because its body says what "
                        + "already landed — that is the shape CG15/CG16 legitimately have");
        assertTrue(auditShipped("e.md", deferredSection).isEmpty(),
                "this guard is scoped to the Open section; §2/§3 have their own lifecycle");
    }

    /** One violation per open item whose LEAD announces the item as already shipped. */
    private static List<String> auditShipped(String name, String markdown) {
        List<String> out = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        boolean inOpen = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("## ")) {
                inOpen = line.matches("^## \\d*\\.?\\s*Open\\b.*");
                continue;
            }
            if (!inOpen || !(line.startsWith("### ") || line.startsWith("- **"))) {
                continue;
            }
            // The LEAD only: up to the end of the bolded title, or the first sentence of a heading.
            int lead = line.startsWith("- **") ? line.indexOf("**", 4) : line.length();
            String head = line.substring(0, lead < 0 ? line.length() : Math.min(line.length(), lead + 2));
            if (SHIPPED.matcher(head).find()) {
                out.add(name + ":" + (i + 1) + "  " + head.substring(0, Math.min(96, head.length())));
            }
        }
        return out;
    }

    /** A marker that announces the item itself as complete. */
    private static final Pattern SHIPPED =
            Pattern.compile("\\b(FIXED|SHIPPED|DONE|RESOLVED|COMPLETED)\\b");

    /** Does-fire proof for the id check, on the exact shapes the estate uses. */
    @Test
    void theIdGuardFiresOnAnUnidentifiedOpenItem() {
        String bulletNoId = "# x — gaps\n\n## 1. Open\n\n- **The matrix cannot fail on a false description.** body\n";
        String bulletWithId = "# x — gaps\n\n## 1. Open\n\n- **IG9 — a real open gap** body\n";
        String headingWithId = "# x — gaps\n\n## 1. Open\n\n### CG3 — a titled row <a id=\"cg3\"></a>\n\nbody\n";
        String tableWithId = "# x — gaps\n\n## 1. Open\n\n| id | gap |\n|---|---|\n| F4 | something |\n";

        assertTrue(!auditIds("bare.md", bulletNoId).isEmpty(),
                "a bold-claim bullet with no id is the shape that went unchecked — it must fire");
        assertTrue(auditIds("b.md", bulletWithId).isEmpty(), "an id-led bullet is citable");
        assertTrue(auditIds("h.md", headingWithId).isEmpty(), "an id-led heading is citable");
        assertTrue(auditIds("t.md", tableWithId).isEmpty(), "an id-first table row is citable");
    }

    /** One violation per open item that carries no id in any of the three accepted positions. */
    private static List<String> auditIds(String name, String markdown) {
        List<String> out = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        boolean inOpen = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("## ")) {
                inOpen = line.matches("^## \\d*\\.?\\s*Open\\b.*");
                continue;
            }
            if (!inOpen) {
                continue;
            }
            boolean isItem = line.startsWith("### ") || line.startsWith("- **")
                    || (line.startsWith("| ") && !line.contains("---") && !line.matches("^\\|\\s*id\\s*\\|.*"));
            if (isItem && !ID.matcher(line).find()) {
                out.add(name + ":" + (i + 1) + "  " + line.substring(0, Math.min(88, line.length())));
            }
        }
        return out;
    }

    /**
     * Ids listed more than once in an Open section. Walks the same section boundary and the same item shapes as
     * {@link #auditIds}, so the two cannot disagree about what counts as an open item.
     */
    private static List<String> auditDuplicateIds(String name, String markdown) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        String[] lines = markdown.split("\n", -1);
        boolean inOpen = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                inOpen = line.matches("^## \\d*\\.?\\s*Open\\b.*");
                continue;
            }
            if (!inOpen) {
                continue;
            }
            java.util.regex.Matcher m = DUPLICATE_ID.matcher(line);
            if (m.find()) {
                counts.merge(m.group(1), 1, Integer::sum);
            }
        }
        List<String> out = new ArrayList<>();
        counts.forEach((id, n) -> {
            if (n > 1) {
                out.add(name + ": " + id + " is listed " + n + " times in an Open section");
            }
        });
        return out;
    }

    /** The id itself, captured, in the same three positions {@link #ID} accepts. */
    private static final Pattern DUPLICATE_ID =
            Pattern.compile("(?:^### |^- \\*\\*|^\\| ?)\\*?\\*?([A-Z]{1,4}\\d+)\\b");

    /** An id in any accepted position: a heading/bullet lead, or a table's first cell. */
    private static final Pattern ID =
            Pattern.compile("(^### |^- \\*\\*|^\\| ?)\\*?\\*?[A-Z]{1,4}\\d+\\b");

    /**
     * Does-fire proof against fabricated ledgers, exercising {@link #audit(String, String)} — the same
     * method the real check calls. An empty section without the marker must fire; a section with items and
     * a section with the marker must not.
     */
    @Test
    void theGuardFiresOnADrainedSectionThatDoesNotSaySo(@TempDir Path dir) throws IOException {
        String bare = "# x\n\n## 1. Open\n\nEverything shipped.\n\n## 2. Deferred\n\n- **X1 — y**\n";
        String markerPlusProse = "# x\n\n## 1. Open\n\n_None open._\n\nEverything shipped.\n\n## 2. Deferred\n";
        String markerOnly = "# x\n\n## 1. Open\n\n_None open._\n\n---\n\n## 2. Deferred\n";
        String withItems = "# x\n\n## 1. Open\n\n- **IG9 — a real open gap**\n\n## 2. Deferred\n";
        String emptyTable = "# x\n\n## 1. Open\n\n| id | gap |\n|---|---|\n\n## 2. Deferred\n";

        assertTrue(!audit("bare.md", bare).isEmpty(), "a drained open section with no marker must fire");
        assertTrue(audit("markerPlusProse.md", markerPlusProse).stream().anyMatch(v -> v.contains("still carries prose")),
                "a marked section that still buries the answer under prose must fire");
        assertTrue(audit("markerOnly.md", markerOnly).isEmpty(), "the marker alone must satisfy the guard");
        assertTrue(audit("items.md", withItems).isEmpty(), "a section that lists an item needs no marker");
        assertTrue(!audit("emptyTable.md", emptyTable).isEmpty(),
                "an empty table has no live item and must not satisfy the open-section guard");
    }

    /**
     * <b>No ledger line repeats a long span of itself</b> — the signature of a botched string replace.
     *
     * <p><b>Why this shape, and why within a LINE.</b> Editing these ledgers means editing one long paragraph,
     * and a scripted find-and-replace that matches once but composes wrongly silently DOUBLES part of it. It
     * happened twice in one session: a bad slice duplicated an open row, and a replaced `Current state.`
     * paragraph left both the new text and the old stitched together — invisible in review, because the file is
     * long and the damage is mid-line. Asserting `count(old) == 1` before a replace proves the MATCH, not the
     * RESULT; this checks the result.
     *
     * <p>The discriminator is principled rather than tuned: docs/CLAUDE.md mandates one paragraph per physical
     * line, so legitimate long repetition lands on DIFFERENT lines (two rows citing one trigger phrase, table
     * boilerplate) while duplication damage lands INSIDE one. Measured 2026-08-22 across the nine ledgers: the
     * longest legitimate within-line repeat is <b>76</b> chars; the real damage was <b>120</b>. The bar sits at
     * {@link #MAX_WITHIN_LINE_REPEAT} with margin either side. A whole-file check cannot work — the longest
     * legitimate repeat across lines is 147, longer than the damage, so it has no separation at all.
     */
    @Test
    void noLedgerLineRepeatsALongSpanOfItself() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path ledger : ledgers()) {
            String name = ledger.getFileName().toString();
            int lineNumber = 0;
            for (String line : Files.readAllLines(ledger)) {
                lineNumber++;
                String repeat = longestRepeatedSpan(line);
                if (repeat.length() > MAX_WITHIN_LINE_REPEAT) {
                    violations.add(name + ":" + lineNumber + " repeats " + repeat.length()
                            + " chars of itself — did a find-and-replace double this paragraph? span: \""
                            + repeat.strip().substring(0, Math.min(70, repeat.strip().length())) + "…\"");
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "ledger lines that duplicate a long span of themselves:\n  "
                + String.join("\n  ", violations));
    }

    /** Above this, a within-line repeat is duplication damage rather than prose — see the measurement above. */
    private static final int MAX_WITHIN_LINE_REPEAT = 100;

    /** The longest substring occurring twice in {@code line}; empty when none reaches the floor. */
    private static String longestRepeatedSpan(String line) {
        String best = "";
        for (int width = MAX_WITHIN_LINE_REPEAT + 1; width <= line.length() / 2; width++) {
            Set<String> seen = new java.util.HashSet<>();
            String found = null;
            for (int i = 0; i + width <= line.length(); i++) {
                String window = line.substring(i, i + width);
                if (!seen.add(window)) {
                    found = window;
                    break;
                }
            }
            if (found == null) {
                break;
            }
            best = found;
        }
        return best;
    }

    /** Does-fire proof, on the actual text a bad replace produced rather than on a synthetic doubling. */
    @Test
    void theDuplicationGuardFiresOnARealBotchedReplace() {
        String tail = "and enlarging a slice per finding is what the convergence gate exists to stop. "
                + "It was likewise empty as of 2026-08-04: ";
        String damaged = "> **Current state.** The ledger is empty. Each landed as its own slice, because each "
                + "was a distinct mechanism " + tail + "The nine gaps opened that day all shipped, " + tail
                + "the review's ergonomic gaps.";
        assertTrue(longestRepeatedSpan(damaged).length() > MAX_WITHIN_LINE_REPEAT,
                () -> "must fire on the doubling that actually shipped, found only "
                        + longestRepeatedSpan(damaged).length() + " chars");
        assertEquals("", longestRepeatedSpan("> **Current state.** The ledger is empty as of 2026-08-22. "
                        + "Ids in use so far: G1-G46 and G52-G62, with G47-G51 free; a retired id is never reused."),
                "and must stay quiet on ordinary prose");
    }

    /** One violation per open section that is neither populated nor explicitly empty. */
    private static List<String> audit(String name, String markdown) {
        List<String> violations = new ArrayList<>();
        String[] lines = markdown.split("\\R", -1);
        String section = null;
        boolean hasItem = false;
        boolean hasMarker = false;
        List<String> prose = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith("## ")) {
                close(name, section, hasItem, hasMarker, prose, violations);
                section = isOpenSection(line) ? line : null;
                hasItem = false;
                hasMarker = false;
                prose = new ArrayList<>();
                continue;
            }
            if (section == null) {
                continue;
            }
            if (line.strip().equals(MARKER)) {
                hasMarker = true;
            } else if (line.startsWith("- **") || line.startsWith("### ")
                    || (line.startsWith("| ") && ID.matcher(line).find())) {
                hasItem = true;
            } else if (!line.isBlank() && !line.strip().equals("---")) {
                // A drained section must say ONLY that it is drained. Closure history and lifecycle policy
                // belong in the `Current state.` / `Lifecycle.` preamble, where they do not bury the answer
                // to "what remains?" under paragraphs a reader has to triage first.
                prose.add(line.strip().substring(0, Math.min(70, line.strip().length())));
            }
        }
        close(name, section, hasItem, hasMarker, prose, violations);
        return violations;
    }

    /** Verdict for one finished open section: it must list items, or say ONLY that it is drained. */
    private static void close(String name, String section, boolean hasItem, boolean hasMarker,
            List<String> prose, List<String> violations) {
        if (section == null) {
            return;
        }
        String title = section.substring(3);
        if (!hasItem && !hasMarker) {
            violations.add(name + " — \"" + title + "\" neither lists an item nor carries the marker");
        } else if (hasMarker && !prose.isEmpty()) {
            violations.add(name + " — \"" + title + "\" is marked drained but still carries prose; move it to the "
                    + "`Current state.` / `Lifecycle.` preamble: \"" + prose.get(0) + "…\"");
        }
    }

    /** An "open" section by heading: `## 1. Open`, `## Open`, `## 3. Open backlog`, `## 1. Open (to fix)`. */
    private static boolean isOpenSection(String heading) {
        if (!heading.startsWith("## ")) {
            return false;
        }
        String text = heading.substring(3).toLowerCase();
        return text.matches("(\\d+\\.\\s*)?open\\b.*");
    }

    private static List<Path> ledgers() throws IOException {
        try (Stream<Path> files = Files.list(DOCS)) {
            return files.filter(p -> p.getFileName().toString().endsWith("-GAPS.md")).sorted().toList();
        }
    }
}
