package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every published surface that COUNTS the admitted DATERANGE {@code (format, separator)} pairs states the count the
 * derived vocabulary holds.
 *
 * <p>The vocabulary guard checks that a surface naming pairs names admitted ones, by containment, so it cannot see a
 * stale NUMBER: after kernel 31.1.1 raised the count from eight to twelve, three schema {@code format} descriptions
 * still said "the eight admitted pairs" beside a {@code separator} description saying twelve (cold review,
 * 2026-11-03). The count is read from the derived artifact's own heading, which {@code TemporalFormatVocabularyGuardTest}
 * binds to the kernel.
 */
class DateRangePairCountClaimTest {

    private static final Path ARTIFACT = Path.of("docs/TEMPORAL-FORMAT-VOCABULARY.md");
    private static final Pattern DERIVED = Pattern.compile("^## DATERANGE `\\(format, separator\\)` pairs admitted — (\\d+)$",
            Pattern.MULTILINE);
    private static final List<String> WORDS = List.of("zero", "one", "two", "three", "four", "five", "six", "seven",
            "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty");
    /** A count word or numeral, then at most a few words, then "pairs": "the twelve admitted pairs", "TWELVE (format, separator) pairs". */
    private static final Pattern CLAIM = Pattern.compile(
            "(?i)\\b(" + String.join("|", WORDS) + "|\\d+)\\b((?:\\s+(?:admitted|allowlisted|range|date-range|\\(format,|separator\\)))*)\\s+pairs\\b");
    private static final List<Path> SURFACES = List.of(
            Path.of("cli/src/main/resources/schema/field-add.schema.json"),
            Path.of("cli/src/main/resources/schema/field-modify.schema.json"),
            Path.of("cli/src/main/resources/schema/typedef-add.schema.json"),
            Path.of("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/StructuralKernelFix.java"));

    @Test
    void everyPublishedPairCountIsTheDerivedOne() throws IOException {
        Matcher derived = DERIVED.matcher(Files.readString(ARTIFACT));
        assertTrue(derived.find(), "the derived vocabulary carries its DATERANGE pair heading");
        int count = Integer.parseInt(derived.group(1));

        List<String> wrong = new ArrayList<>();
        List<Path> silent = new ArrayList<>();
        for (Path surface : SURFACES) {
            Matcher claim = CLAIM.matcher(Files.readString(surface));
            int claims = 0;
            while (claim.find()) {
                claims++;
                if (value(claim.group(1)) != count) {
                    wrong.add(surface + ": \"" + claim.group() + "\"");
                }
            }
            if (claims == 0) {
                silent.add(surface);
            }
        }
        assertEquals(List.of(), silent, "a registered surface states no pair count, so this guard reads nothing there");
        assertEquals(List.of(), wrong, "a published pair count differs from the derived " + count);
    }

    /** The positive control: the stale 2026-11-03 phrasing is a claim this guard reads, and reads as eight. */
    @Test
    void theStalePhrasingIsAClaim() {
        Matcher claim = CLAIM.matcher("see `separator` for the eight admitted pairs");
        assertTrue(claim.find());
        assertEquals(8, value(claim.group(1)));
        assertFalse(CLAIM.matcher("pairs of fields").find(), "a bare 'pairs' is no count");
    }

    private static int value(String token) {
        int word = WORDS.indexOf(token.toLowerCase(java.util.Locale.ROOT));
        return word >= 0 ? word : Integer.parseInt(token);
    }
}
