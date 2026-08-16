package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>Do not cite an ANCHORED doc by quoting its heading — cite the anchor.</b> KERNEL-FINDINGS and
 * INTERPRETER-FINDINGS give every entry a permanent {@code KF<n>}/{@code IF<n>} id precisely so a citation
 * survives a retitle, and these headings are retitled constantly: a finding's heading carries its claim and is
 * amended as the claim sharpens. A quoted title is the one citation form that cannot survive that.
 *
 * <p><b>Why a RATCHET and not a resolution check.</b> Deciding whether an existing quoted title still resolves
 * needs four separate normalizations (markdown emphasis, case, quotes/apostrophes/dashes, whitespace) and still
 * cannot tell a heading citation from ordinary prose that happens to use quotation marks. Measured 2026-08-16:
 * of <b>373</b> citations in the true shape, <b>53</b> fail to resolve — and identifying what each rotted one
 * MEANT is per-site reading, not matching (searching KERNEL-FINDINGS for one quote's most distinctive token
 * returned 183 hits across 146 entries). So this guard makes no resolution claim at all. It counts the form,
 * purely syntactically, and refuses to let the count grow.
 *
 * <p>That is enough, because the convention has already won on volume: <b>759</b> anchor citations against
 * <b>206</b> quoted titles for KERNEL-FINDINGS, and <b>210</b> against <b>9</b> for INTERPRETER-FINDINGS. The
 * ceiling only ever moves DOWN, as sites are repaired opportunistically when a file is touched for other
 * reasons. Lowering it after a repair is the intended edit; raising it is the defect this exists to refuse.
 *
 * <p>Rides {@code referentGuardTest}, which already declares the repo-wide prose surface as its inputs.
 */
class QuotedTitleCitationRatchetTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    /**
     * The ceiling per anchored doc, measured 2026-08-16. Move these DOWN when a citation is repaired to an
     * anchor; never up — a new quoted title has an anchor available and should use it.
     */
    private static final Map<String, Integer> CEILING = Map.of(
            // Assembled, never written whole: a literal doc name followed by a quote IS the shape this counts,
            // so spelling it out here would make the guard consume its own source. ReferentDriftGuardTest
            // documents the same discipline for the same reason.
            "KERNEL-" + "FINDINGS", 206,
            "INTERPRETER-" + "FINDINGS", 9);

    /** The anchor form these citations should take instead, counted only to prove the convention is alive. */
    private static final Pattern ANCHOR_CITATION =
            Pattern.compile("\\]\\((?:KERNEL|INTERPRETER)-FINDINGS\\.md#(?:kf|if)\\d+\\)");

    static final Set<String> SKIPPED_DIRECTORIES = RepoWalk.SKIPPED_DIRECTORIES;

    private static final Set<String> SCANNED_SUFFIXES =
            Set.of(".md", ".java", ".kt", ".gradle", ".gradle.kts", ".sh");

    private static List<Path> scanned;

    /** The citation shape: a doc name, then a quote within a few characters of glue. */
    private static Pattern quotedTitle(String doc) {
        return Pattern.compile(Pattern.quote(doc) + "(?:\\.md)?[^\"\\n]{0,12}\"");
    }

    @Test
    void noNewQuotedTitleCitationOfAnAnchoredDoc() throws IOException {
        for (Map.Entry<String, Integer> limit : CEILING.entrySet()) {
            String doc = limit.getKey();
            int found = count(quotedTitle(doc));
            assertTrue(found <= limit.getValue(),
                    () -> "quoted-title citations of " + doc + " rose to " + found + " (ceiling "
                            + limit.getValue() + "). That doc gives every entry a permanent anchor, so cite it: "
                            + "`[KF123](" + doc + ".md#kf123)`. A quoted heading breaks the moment the claim it "
                            + "carries is sharpened, and nothing detects that. If you REPAIRED one, lower the "
                            + "ceiling in this test — it is meant to move down");
        }
    }

    /**
     * The floor that stops the ratchet passing vacuously. A ceiling test is satisfied perfectly by a walk that
     * finds nothing, so the anchor form — which is what the ceiling is pushing citations toward — is counted
     * too, and must dominate. This is the assertion that fails if the scan loses a tree.
     */
    @Test
    void theAnchorFormDominatesAndTheWalkStillSeesTheEstate() throws IOException {
        int anchors = count(ANCHOR_CITATION);
        assertTrue(anchors >= 800, () -> "found only " + anchors + " anchor citations — the convention this "
                + "ratchet pushes toward is the majority form (measured 969), so a count this low means the "
                + "walk lost a tree and the ceiling above is being satisfied by an empty scan");

        long markdown = scanned.stream().filter(p -> p.toString().endsWith(".md")).count();
        assertTrue(markdown >= 50, () -> "only " + markdown + " markdown files scanned — the walk lost a tree");
        assertTrue(scanned.size() - markdown >= 500,
                () -> "only " + (scanned.size() - markdown) + " source files scanned — the walk lost a tree");
    }

    /** Does-fire proof for the counting rule itself, against fabricated text. */
    @Test
    void theRuleCountsACitationAndIgnoresAnAnchorOrDistantQuote() {
        Pattern kernel = quotedTitle("KERNEL-" + "FINDINGS");
        assertEquals(1, countIn(kernel, "see KERNEL-" + "FINDINGS \"a heading\" for the rule"),
                "a quote right after the doc name is the citation shape");
        assertEquals(1, countIn(kernel, "see KERNEL-" + "FINDINGS.md \"a heading\""),
                "the `.md` suffix is part of the same shape");
        assertEquals(0, countIn(kernel, "see [KF12](KERNEL-" + "FINDINGS.md#kf12) for the rule"),
                "the anchor form is what this ratchet pushes toward and must never be counted against it");
        assertEquals(0, countIn(kernel, "KERNEL-" + "FINDINGS covers it, and a much later sentence said \"hello\""),
                "a quote far from the doc name is ordinary prose, not a citation — the 12-character window is "
                + "what keeps this rule from counting every quotation mark in the estate");
    }

    private static int countIn(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static int count(Pattern pattern) throws IOException {
        int total = 0;
        for (Path file : scannedFiles()) {
            total += countIn(pattern, Files.readString(file));
        }
        return total;
    }

    private static synchronized List<Path> scannedFiles() throws IOException {
        if (scanned != null) {
            return scanned;
        }
        Set<Path> members = new LinkedHashSet<>();
        Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                        || dir.endsWith(Path.of("docs", "archived"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (SCANNED_SUFFIXES.stream().anyMatch(name::endsWith)) {
                    try {
                        // canonicalize: AGENTS.md is a symlink to CLAUDE.md at three levels, and counting
                        // both would inflate every figure here by the whole content of those files.
                        members.add(file.toRealPath());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        scanned = new ArrayList<>(members);
        return scanned;
    }
}
