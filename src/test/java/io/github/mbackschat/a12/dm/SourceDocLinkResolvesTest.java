package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>A markdown link in a source COMMENT that names a repository doc must resolve from that source file.</b>
 * {@code cli}'s {@code DocRelativeLinkGuardTest} rule, applied to the estate it deliberately excluded: its
 * scope is {@code docs/*.md}, so a citation written in Javadoc or KDoc stood outside every guard in the repo.
 *
 * <p><b>What it found when it landed (2026-09-15): 65 of the 66 such links were dead.</b> Not a drift — the
 * form was never right. The depth from a source file to the checkout root is eleven directory levels
 * ({@code adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws}), and the estate had settled on
 * eight and nine, each copied from the last file that used it. Three other spellings were live too
 * ({@code KERNEL-FINDINGS.md}, {@code docs/KERNEL-FINDINGS.md}, {@code ../docs/…}), all equally dead. A link
 * that resolves nowhere reads as a working citation forever, which is the same failure
 * {@link LockCitationResolvesTest} exists to stop one level up — there a class name that no longer exists,
 * here a document that was never reachable by the path offered.
 *
 * <p><b>Scope is the PATH half, deliberately.</b> Whether a {@code #fragment} still resolves inside its target
 * is the other half, and in the docs tree the two halves are separately owned ({@code DocAnchorResolveGuardTest}
 * in {@code :cli} takes fragments, {@code DocRelativeLinkGuardTest} takes paths). This mirrors that split rather
 * than merging it. The 37 fragments these links carry (2026-09-15) were all resolved by hand when this landed,
 * against the same rule that guard applies — explicit {@code id="…"} or a heading slug — so the gap left open
 * is future drift, not a known backlog.
 *
 * <p><b>Only COMMENT lines are scanned, and that is the denominator rather than a convenience.</b> A string
 * literal holds this same syntax for two reasons that are both correct and neither a citation. A guard test
 * building a synthetic markdown corpus writes links it must judge rather than follow — {@code
 * DocAnchorResolveGuardTest} has an {@code [outside]} link on {@code ../README.md#x} in a fixture naming a file
 * it exists to find unresolvable. And a test that GENERATES a document writes links relative to the OUTPUT, not its
 * own: {@code TemporalFormatVocabularyGuardTest}'s text block emits {@code (KERNEL-FINDINGS.md#kf206)}, correct
 * from {@code docs/} and wrong from anywhere else. Rewriting either would be the defect. A citation is prose
 * about the code and lives in a comment, so scanning comment lines separates the three by construction, with no
 * exemption list to maintain.
 *
 * <p><b>Only a target naming a real {@code docs/} file is checked</b>, so the rule never has to guess whether
 * {@code [a](target)} in a comment was meant as a path at all — a regex-shaped fragment or a prose parenthesis
 * cannot match, because its basename is not a document this repository has.
 *
 * <p><b>The sibling estate was swept and deliberately left uncovered, on its measurement (2026-09-15).</b>
 * Markdown OUTSIDE {@code docs/} is the other half of the class no guard reaches: 432 relative links, of which
 * <b>one</b> was dead — {@code eval/RESULTS.md} still naming a proposal since moved to {@code docs/archived/},
 * repaired with this. The other 15 apparent danglers are all in {@code plugin/README.md} and
 * {@code interpreter/public-release/README.md}, whose trees {@code scripts/assemble-release-repo.sh} copies to
 * a mirror ROOT where {@code LICENSE}, {@code examples/} and {@code codex/} do sit beside them — correct at the
 * destination and dead here, the generated-output case again. Covering that estate would therefore need a
 * publication-tree exemption list to hold a family already healthy at 1 in 432, where this one was broken at 65
 * in 66. That ratio is the reason, so re-measure it rather than inheriting the decision.
 */
class SourceDocLinkResolvesTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** A markdown link target with its fragment captured separately: a {@code [KF424]} label on the path. */
    private static final Pattern LINK = Pattern.compile("]\\(([^)\\s#]+)((?:#[^)\\s]*)?)\\)");

    /** Javadoc, KDoc, block and line comments — the lines a citation is written on. */
    private static final Pattern COMMENT_LINE = Pattern.compile("^\\s*(?:\\*|//|/\\*)");

    /**
     * Sanity floor on what the RULE parses, so a broken extractor reads as a failure rather than as a clean
     * sweep. The estate carries 66 such links across 49 source files (2026-09-15); re-derive rather than
     * increment.
     */
    private static final int MIN_LINKS = 50;

    @Test
    void everyDocLinkInASourceCommentResolvesFromItsOwnFile() throws IOException {
        Set<String> docs = new LinkedHashSet<>();
        for (Path doc : CHECKOUT.tracked("docs")) {
            docs.add(doc.getFileName().toString());
        }
        List<String> dead = new ArrayList<>();
        int seen = 0;
        for (Path source : CHECKOUT.sources()) {
            List<String> lines = Files.readAllLines(source);
            for (int number = 0; number < lines.size(); number++) {
                String line = lines.get(number);
                if (!COMMENT_LINE.matcher(line).find()) {
                    continue;
                }
                Matcher link = LINK.matcher(line);
                while (link.find()) {
                    String target = link.group(1);
                    if (!docs.contains(target.substring(target.lastIndexOf('/') + 1))) {
                        continue;
                    }
                    seen++;
                    if (!Files.exists(source.getParent().resolve(target).normalize())) {
                        dead.add(CHECKOUT.relative(source) + ":" + (number + 1) + " -> " + target);
                    }
                }
            }
        }

        assertTrue(seen >= MIN_LINKS,
                "the extractor found only " + seen + " doc links in source comments, below the " + MIN_LINKS
                        + " floor — a sweep that parses nothing passes everything");
        assertEquals(List.of(), dead,
                "a citation in a source comment points at a document that is not there. The path is relative to "
                        + "the SOURCE FILE's own directory, which is eight levels below the checkout root here and "
                        + "eleven in a module's package-nested laws tree — count them for the file you are in "
                        + "rather than copying a neighbour, which is how every one of these was wrong when this "
                        + "guard landed");
    }
}
