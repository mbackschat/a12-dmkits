package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The intra-doc anchor guard.</b> A {@code [label](#slug)} link inside a doc silently 404s the moment the
 * heading it names is retitled — and these docs are retitled routinely, because a finding's heading carries
 * its claim and gets amended as the claim sharpens. That is not hypothetical: {@code IF96} was amended
 * ("an invalid index anywhere" → "an invalid index in any joined SIBLING group", plus a trailing
 * "narrowed by IF222"), which broke the one link that pointed at its generated slug. Nothing caught it; it
 * surfaced only in a manual audit.
 *
 * <p>The fix that makes the class extinct is a <b>stable explicit anchor</b> — {@code <a id="if96"></a>} on
 * the heading, linked as {@code #if96} — because a retitle then cannot move it. This guard enforces that a
 * fragment resolves to <i>either</i> an explicit {@code <a id>}/{@code <a name>} or a current heading slug,
 * so both styles are legal and only a dangling one fails.
 *
 * <p>Both directions are covered, because both rotted: the <b>intra-doc</b> fragment ({@code ](#…)}) that
 * broke on IF96, and the <b>cross-doc</b> fragment ({@code ](OTHER.md#…)}) — where the same retitle rot had
 * silently broken a link to {@code IAG4} after its heading gained a word. Together with the per-record
 * anchor checks below, the durable fix is that every cited entry owns an explicit id, so a heading may be
 * amended freely. {@code docs/archived/} is excluded — a frozen record, not a maintained surface.
 *
 * <p>The slug rule mirrors GitHub's: strip inline HTML, drop every character that is not alphanumeric,
 * space, hyphen or underscore (so backticks, emphasis, {@code §}, em dashes and commas vanish), lowercase,
 * then map spaces to hyphens.
 *
 * <p>Runs in the {@code :cli} {@code docGuardTest} task, whose input set already declares the docs tree, so
 * a docs edit re-runs only this lane and cannot restore from the build cache into a false green.
 */
class DocAnchorResolveGuardTest {

    private static final Path DOCS = Path.of("../docs");

    /** An intra-doc fragment link: {@code [label](#slug)}. Cross-file targets are out of scope (see above). */
    private static final Pattern FRAGMENT = Pattern.compile("\\]\\(#([^)\\s]+)\\)");

    /** A cross-doc fragment link: {@code [label](OTHER.md#slug)}. */
    private static final Pattern CROSS_DOC_FRAGMENT = Pattern.compile("\\]\\(([A-Za-z0-9._-]+\\.md)#([^)\\s]+)\\)");

    /** An explicit anchor a retitle cannot move. */
    private static final Pattern EXPLICIT = Pattern.compile("<a\\s+(?:id|name)=\"([^\"]+)\"");

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*$", Pattern.MULTILINE);

    /**
     * An interpreter finding's heading, whose {@code IF<n>} id is the cited address. <b>Both title separators</b>
     * — the space and the colon — because the doc uses both and requiring the space silently skipped the colon
     * form: 259 of 299 entries checked (2026-08-16), so <b>40 (13 %) were never guarded for their anchor</b>. All 40
     * happened to carry one, so nothing was red and the floor could not see it either (259 clears a floor of 200).
     * A guard that omits part of its population reports a clean bill of health for the part it cannot see.
     */
    private static final Pattern FINDING_HEADING = Pattern.compile("^### IF(\\d+)[: ].*$", Pattern.MULTILINE);

    /**
     * A prose citation of an instruction-file section: {@code CLAUDE.md "Quoted Title"}, optionally with the
     * file name in backticks or as a link target, and up to a few characters of punctuation between.
     */
    private static final Pattern INSTRUCTION_CITATION = Pattern.compile(
            "(?:CLAUDE|AGENTS)\\.md[^\\n\"\u201c]{0,6}[\"\u201c]([^\"\u201d\\n]{4,120})[\"\u201d]");

    /** Sanity floor: 15 live citations across docs/ (2026-09-05), archived excluded. */
    private static final int MIN_INSTRUCTION_CITATIONS = 10;

    /** Sanity floor: the live tree has 66 docs (2026-08-16). A scan finding almost none is checking nothing. */
    private static final int MIN_DOCS = 30;

    /**
     * Sanity floor, asserted on what {@link #FINDING_HEADING} parses: 299 headings (2026-08-16), which is now the
     * WHOLE population — the pattern was widened to admit the colon form it had been skipping.
     *
     * <p><b>Ratcheted past the pre-widening population, which is the whole point of the number.</b> At 200 this
     * floor could not see the defect the widening fixed: the narrow pattern parsed 259 headings and 259 clears
     * 200, so reverting the widening would restore the 13 % blind spot with a green build — the floor would be
     * measuring that the file exists rather than that the pattern reaches all of it. Set above 259 so a revert
     * fails here, and below 299 so ordinary deletion does not.
     */
    private static final int MIN_FINDINGS = 280;

    /** A kernel finding's heading — h2, plus the h3s of the date-family subtree. */
    private static final Pattern KERNEL_FINDING_HEADING = Pattern.compile("^#{2,3} .*$", Pattern.MULTILINE);

    /** Its assigned address. Trailing, so the §-tag stays at the start of the heading (see the test). */
    private static final Pattern KF_ANCHOR = Pattern.compile("<a id=\"kf(\\d+)\">");

    /** Sanity floor: the kernel-findings record carries 188 entries (2026-08-16). */
    private static final int MIN_KERNEL_FINDINGS = 140;

    @Test
    void everyIntraDocAnchorResolves() throws IOException {
        List<Path> docs = docs();
        assertTrue(docs.size() >= MIN_DOCS,
                () -> "found only " + docs.size() + " docs under " + DOCS + " (floor " + MIN_DOCS
                        + ") — wrong working dir or an emptied tree? The guard would check nothing.");

        List<String> violations = new ArrayList<>();
        for (Path doc : docs) {
            violations.addAll(audit(doc.getFileName().toString(), Files.readString(doc)));
        }
        assertTrue(violations.isEmpty(), () -> "intra-doc anchors that do NOT resolve — a retitled heading "
                + "breaks a generated slug, so give the heading an explicit `<a id=\"...\"></a>` and link that "
                + "instead:\n  " + String.join("\n  ", violations));
    }

    /**
     * <b>Every {@code IF<n>} finding carries its stable anchor.</b> The findings are cited by id from the
     * docs, the tests, and production sources, and their headings are amended as claims sharpen — so the
     * generated slug is not a durable address. Requiring {@code <a id="ifNN"></a>} on the heading makes
     * {@code #ifNN} permanent, and makes a <i>new</i> finding that forgets one fail here rather than
     * silently shipping an unlinkable entry.
     */
    @Test
    void everyInterpreterFindingHeadingCarriesItsStableAnchor() throws IOException {
        String markdown = Files.readString(DOCS.resolve("INTERPRETER-FINDINGS.md"));
        Matcher heading = FINDING_HEADING.matcher(markdown);

        List<String> missing = new ArrayList<>();
        int total = 0;
        while (heading.find()) {
            total++;
            String number = heading.group(1);
            if (!heading.group(0).contains("<a id=\"if" + number + "\">")) {
                missing.add("IF" + number);
            }
        }

        int seen = total;
        assertTrue(seen >= MIN_FINDINGS,
                () -> "found only " + seen + " `### IF<n>` headings (floor " + MIN_FINDINGS
                        + ") — wrong working dir or a changed heading shape? The guard would check nothing.");
        assertTrue(missing.isEmpty(), () -> "findings whose heading lacks its stable anchor — append "
                + "`<a id=\"ifNN\"></a>` so a retitle cannot move the address:\n  " + String.join(", ", missing));
    }

    /**
     * <b>Every kernel finding carries a unique {@code KF<n>} anchor.</b> KERNEL-FINDINGS was the one findings
     * record with <i>no</i> per-entry address: it is cited by quoted heading text (which a retitle breaks) or
     * by {@code §n}, which is a section-<i>class</i> — {@code ## §9 supplement} alone matches <b>32</b> entries
     * (2026-08-16). The ids are assigned once in document order and are <b>dense</b>, which is what makes a
     * silent deletion visible; the contract is that a finding is never deleted or renumbered.
     *
     * <p>The anchor is <b>trailing</b>, never a title prefix, and that is load-bearing:
     * {@code KernelDocsFoldContractTest} and {@code SemanticsMapResolveTest} both pin the §-tag with
     * {@code ^#{2,3} §(\d+)}, so a prefix would silently blind the §-mirror on <b>143</b> headings (2026-08-16 —
     * those classes require a trailing space, which is what separates 143 from the 144 the looser form matches).
     */
    @Test
    void everyKernelFindingHeadingCarriesAUniqueStableAnchor() throws IOException {
        String markdown = Files.readString(DOCS.resolve("KERNEL-FINDINGS.md"));

        List<String> unanchored = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        Matcher heading = KERNEL_FINDING_HEADING.matcher(markdown);
        while (heading.find()) {
            Matcher id = KF_ANCHOR.matcher(heading.group(0));
            if (id.find()) {
                ids.add(Integer.parseInt(id.group(1)));
            } else {
                unanchored.add(heading.group(0).replaceAll("^#+ ", "").substring(0, 60));
            }
        }

        int seen = ids.size() + unanchored.size();
        assertTrue(seen >= MIN_KERNEL_FINDINGS,
                () -> "found only " + seen + " KERNEL-FINDINGS headings (floor " + MIN_KERNEL_FINDINGS
                        + ") — wrong working dir or a changed heading shape? The guard would check nothing.");
        assertTrue(unanchored.isEmpty(), () -> "kernel findings whose heading lacks a `KF<n>` anchor — append "
                + "`<a id=\"kfN\"></a>` (TRAILING, never before the §-tag):\n  "
                + String.join("\n  ", unanchored));

        List<Integer> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        List<String> broken = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i) != i + 1) {
                broken.add("expected kf" + (i + 1) + " but the sorted ids jump to kf" + sorted.get(i));
                break;
            }
        }
        assertTrue(broken.isEmpty(), () -> "KF ids must be dense 1..N and unique (a finding is never deleted "
                + "or renumbered):\n  " + String.join("\n  ", broken));
    }

    /**
     * <b>Cross-doc fragments resolve too.</b> The canonical KERNEL-SEMANTICS prose cites its verification as
     * {@code §n / IFm}, whose two halves live in <i>different</i> docs — the link named KERNEL-FINDINGS while
     * the precise {@code IFm} address resolves in INTERPRETER-FINDINGS, so following it landed a reader in
     * the wrong file. Those cites now link explicitly — 120 of them (2026-08-16) — which is only safe if
     * something checks them.
     */
    @Test
    void everyCrossDocAnchorResolves() throws IOException {
        Map<String, String> byName = new LinkedHashMap<>();
        for (Path doc : docs()) {
            byName.put(doc.getFileName().toString(), Files.readString(doc));
        }
        assertTrue(byName.size() >= MIN_DOCS,
                () -> "found only " + byName.size() + " docs (floor " + MIN_DOCS + ") — the guard would check nothing.");
        assertTrue(crossDocViolations(byName).isEmpty(),
                () -> "cross-doc anchors that do NOT resolve in the target doc — give the target heading an "
                        + "explicit `<a id=\"...\"></a>` and link that:\n  "
                        + String.join("\n  ", crossDocViolations(byName)));
    }

    /**
     * Does-fire proof for the cross-doc arm, against a fabricated pair of docs. A stale slug into a peer must
     * fire; a live slug, an explicit id, and a link to a doc outside the scanned set must not.
     */
    @Test
    void theCrossDocArmFiresOnlyOnAStaleFragmentIntoAScannedPeer() {
        Map<String, String> docs = Map.of(
                "A.md", """
                        [stale](B.md#gone) [live](B.md#kept-heading) [pinned](B.md#anchored) [outside](../README.md#x)
                        """,
                "B.md", """
                        ## Kept Heading

                        ### Something renamed <a id="anchored"></a>
                        """);

        List<String> found = crossDocViolations(docs);
        assertTrue(found.size() == 1 && found.get(0).contains("#gone"),
                () -> "expected only the stale cross-doc fragment to fire, got " + found);
    }

    /** One violation per cross-doc fragment that does not resolve in its (scanned) target doc. */
    private static List<String> crossDocViolations(Map<String, String> docsByName) {
        Map<String, Set<String>> targetsByDoc = new LinkedHashMap<>();
        docsByName.forEach((name, markdown) -> targetsByDoc.put(name, targets(markdown)));

        List<String> violations = new ArrayList<>();
        docsByName.forEach((name, markdown) -> {
            Matcher link = CROSS_DOC_FRAGMENT.matcher(withoutCode(markdown));
            while (link.find()) {
                Set<String> targets = targetsByDoc.get(link.group(1));
                if (targets == null) {
                    continue;   // not a scanned docs/*.md peer (e.g. ../README.md) — file existence is another guard
                }
                if (!targets.contains(link.group(2).toLowerCase())) {
                    violations.add(name + " → " + link.group(1) + "#" + link.group(2));
                }
            }
        });
        return violations;
    }

    /**
     * Does-fire proof against a fabricated doc, exercising {@link #audit(String, String)} — the same method
     * the real check calls. A stale slug must fire; a live slug and an explicit anchor must not.
     */
    @Test
    void theGuardFiresOnAStaleSlugButNotOnALiveOneOrAnExplicitAnchor(@TempDir Path dir) throws IOException {
        String doc = """
                # t

                See [a](#the-old-title) and [b](#the-new-title) and [c](#pinned).

                Prose about the syntax: every `](#…)` link must resolve, and `[x](#never-existed)` is code.

                ## The New Title

                ### Renamed heading <a id="pinned"></a>
                """;
        Files.writeString(dir.resolve("t.md"), doc);

        List<String> found = audit("t.md", doc);
        assertTrue(found.size() == 1 && found.get(0).contains("the-old-title"),
                () -> "expected exactly the stale slug to fire (a live slug, an explicit anchor, and a "
                        + "fragment inside a code span must all stay silent), got " + found);
    }

    /** Every address a doc offers: its explicit anchors plus its current heading slugs. */
    private static Set<String> targets(String markdown) {
        Set<String> targets = new LinkedHashSet<>();
        Matcher explicit = EXPLICIT.matcher(markdown);
        while (explicit.find()) {
            targets.add(explicit.group(1).toLowerCase());
        }
        Matcher heading = HEADING.matcher(markdown);
        while (heading.find()) {
            targets.add(slug(heading.group(1)));
        }
        return targets;
    }

    /** One violation per fragment that names neither an explicit anchor nor a current heading slug. */
    private static List<String> audit(String name, String markdown) {
        Set<String> targets = targets(markdown);

        // Fragments are scanned WITHOUT code spans: several docs discuss the link syntax itself (a literal
        // `](#…)` inside backticks in DOC-DISCIPLINE / TESTING-SPEC), which is prose about a link, not a
        // link. Targets above are still collected from the raw text, because a heading keeps the words
        // inside its backticks — only the backtick characters drop out of the slug.
        List<String> violations = new ArrayList<>();
        Matcher fragment = FRAGMENT.matcher(withoutCode(markdown));
        while (fragment.find()) {
            String slug = fragment.group(1).toLowerCase();
            if (!targets.contains(slug)) {
                violations.add(name + " → #" + slug);
            }
        }
        return violations;
    }

    /** Blank out fenced blocks and inline code spans, so prose *about* a link is not read as one. */
    /**
     * Fenced blocks only, keeping INLINE code spans. The citation arm needs this: two docs write the citation
     * as {@code `CLAUDE.md` "Title"}, and {@link #withoutCode}'s inline-span strip would delete the marker and
     * silently drop those from the scanned population — the blind-spot shape this class already carries a
     * story about on {@code FINDING_HEADING}.
     */
    private static String withoutFencedCode(String markdown) {
        StringBuilder out = new StringBuilder(markdown.length());
        boolean fenced = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
                out.append('\n');
                continue;
            }
            out.append(fenced ? "" : line).append('\n');
        }
        return out.toString();
    }

    private static String withoutCode(String markdown) {
        StringBuilder out = new StringBuilder(markdown.length());
        boolean fenced = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
                out.append('\n');
                continue;
            }
            out.append(fenced ? "" : line.replaceAll("`+[^`]*`+", " ")).append('\n');
        }
        return out.toString();
    }

    /** GitHub's heading-slug rule, enough of it: strip HTML, keep word/space/-/_, lowercase, spaces → '-'. */
    private static String slug(String headingText) {
        // Strip inline HTML, then TRIM: an explicit `<a id>` anchor is written after a space, and leaving
        // that space behind would append a phantom trailing hyphen to the slug (it briefly did, and broke
        // two links that GitHub resolves fine — GitHub trims the heading text before slugging).
        String text = headingText.replaceAll("<[^>]*>", "").strip();
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                out.append(c);
            } else if (c == ' ') {
                out.append('-');
            } else if (c == '-' || c == '_') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * <b>Every prose citation of an instruction-file SECTION resolves.</b> The docs cite the project
     * instructions by quoted section title — {@code CLAUDE.md "Self-explaining surface"} — rather than by
     * link, because the instruction files carry no per-heading anchors. That address is therefore only as
     * durable as the title, and unlike the fragment arms above nothing checked it: pruning a section left the
     * citer pointing at nothing, silently. Two had already rotted when this arm was written (2026-09-05),
     * which is one per major prune.
     *
     * <p><b>Why a phrase match and not a heading match.</b> The citations quote a title's HEAD, elide its
     * middle ({@code "Interpreter … #1 rule"}), and sometimes name a sentence rather than a heading — so the
     * check is that the quoted text still occurs SOMEWHERE in some instruction file, which is exactly the
     * property a reader needs and the weakest claim that catches a prune. {@link #instructionProbe} trims at
     * the first elision or separator for the same reason.
     *
     * <p>Deliberately NOT ratcheted against new bare citations: forcing {@code [link](../CLAUDE.md#anchor)}
     * would mean anchoring every cited heading in four instruction files, which is a prose-style decision for
     * the owner rather than a defect this guard found.
     */
    @Test
    void everyInstructionFileSectionCitationResolves() throws IOException {
        Map<String, String> byName = new LinkedHashMap<>();
        for (Path doc : docs()) {
            byName.put(doc.getFileName().toString(), Files.readString(doc));
        }
        String instructions = instructionFiles();

        List<String> violations = unresolvedInstructionCitations(byName, instructions);
        int seen = 0;
        for (String markdown : byName.values()) {
            Matcher cite = INSTRUCTION_CITATION.matcher(withoutFencedCode(markdown));
            while (cite.find()) {
                seen++;
            }
        }
        int total = seen;
        assertTrue(total >= MIN_INSTRUCTION_CITATIONS,
                () -> "found only " + total + " instruction-file section citations (floor "
                        + MIN_INSTRUCTION_CITATIONS + ") — the pattern has stopped matching and this arm is "
                        + "vacuous until it does again.");
        assertTrue(violations.isEmpty(), () -> "docs cite an instruction-file section that no longer exists, so "
                + "the reader is sent to nothing. Either the section was retitled — update the citation — or it "
                + "was pruned, in which case the citing doc must carry the rule itself or point at its new "
                + "home:\n  " + String.join("\n  ", violations));
    }

    /** Does-fire proof: a pruned section must fire, a live one and an elided-title one must not. */
    @Test
    void theCitationArmFiresOnlyOnAPrunedSection() {
        Map<String, String> docs = Map.of(
                "A.md", """
                        see CLAUDE.md "Build" and CLAUDE.md "Interpreter … the two #1 rules"
                        and CLAUDE.md "a section nobody kept"
                        """);
        List<String> found = unresolvedInstructionCitations(docs, """
                ## Build
                ## Interpreter — the two #1 rules (never scoped down)
                """);
        assertTrue(found.size() == 1 && found.get(0).contains("a section nobody kept"),
                () -> "expected only the pruned citation to fire, got " + found);
    }

    /** One violation per quoted instruction-file section that no longer occurs in any instruction file. */
    private static List<String> unresolvedInstructionCitations(Map<String, String> docsByName, String instructions) {
        String haystack = normalizeForCitation(instructions);
        List<String> violations = new ArrayList<>();
        docsByName.forEach((name, markdown) -> {
            Matcher cite = INSTRUCTION_CITATION.matcher(withoutFencedCode(markdown));
            while (cite.find()) {
                String probe = instructionProbe(cite.group(1));
                if (probe.length() >= 4 && !haystack.contains(probe)) {
                    violations.add(name + ": \"" + cite.group(1) + "\"");
                }
            }
        });
        return violations;
    }

    /** The citation's durable head — quotes elide a middle and append a qualifier, neither of which is an address. */
    private static String instructionProbe(String quoted) {
        String head = quoted;
        for (String separator : List.of("…", "...", " / ", " — ")) {
            head = head.split(Pattern.quote(separator))[0];
        }
        return normalizeForCitation(head);
    }

    /** Emphasis and code marks are rendering, not address; whitespace differs across a wrap. */
    private static String normalizeForCitation(String text) {
        return text.replaceAll("[`*_]", "").replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The four instruction files an agent actually loads — root, docs, interpreter, plugin. */
    private static String instructionFiles() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String relative : List.of("CLAUDE.md", "docs/CLAUDE.md", "interpreter/CLAUDE.md", "plugin/CLAUDE.md")) {
            Path path = DOCS.resolve("..").resolve(relative);
            if (Files.isRegularFile(path)) {
                all.append(Files.readString(path)).append('\n');
            }
        }
        assertTrue(all.length() > 20_000,
                () -> "the instruction files read as " + all.length() + " chars — wrong working dir? Every "
                        + "citation would report as dangling.");
        return all.toString();
    }

    private static List<Path> docs() throws IOException {
        try (Stream<Path> files = Files.list(DOCS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        }
    }
}
