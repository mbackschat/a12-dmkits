package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every {@code CK_*} code the docs name must be one the portable checker actually defines.
 *
 * <p><b>Why this exists.</b> A {@code CK_*} code is the narrow set the checker OWNS — minted only where A12
 * defines none, so unlike an {@code MVK_*} it has no external authority to check it against and its only
 * definition is a constant in {@code :consistency}. The plan named {@code CK_COMPUTED_FIELD_NOT_FOUND} for a
 * day after the constant was renamed to {@code CK_MODEL_NOT_BUILDABLE}, and nothing could see it: the code
 * lives in a Kotlin multiplatform module, the prose lives in a doc, and the two meet nowhere. This is the
 * same mechanism {@link LockCitationResolvesTest} applies to cited test classes, on the other kind of name a
 * published claim rests on.
 *
 * <p><b>Across the docs, one direction only.</b> A cited code must exist; an existing code need not be cited
 * in every doc. Requiring that would force a paragraph wherever a code is minted, which is an editorial
 * decision rather than a drift check. <b>The published API reference is the exception</b>
 * ({@link #everyCodeTheCheckerEmitsIsInItsApiReference}): a code the checker can put in a report is part of
 * its contract, and a consumer who receives one must find it in {@code docs/CONSISTENCY-API.md}. Two
 * {@code RK_*} codes reached reports without it (CONSISTENCY-GAPS DG135, 2026-10-13).
 */
class CheckerCodeCitationResolvesTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** The prefix the portable checker mints under, as `ReferenceDiagnostic` declares it. */
    private static final Pattern CITED = Pattern.compile("\\bCK_[A-Z][A-Z0-9_]*");

    private static final Pattern DEFINED = Pattern.compile("\\bconst val (CK_[A-Z][A-Z0-9_]*)\\b");

    /**
     * History is not a citation. {@code docs/archived/} holds superseded proposals whose codes are a record of
     * what was once proposed, so forcing them to resolve would delete true statements about the past — the
     * distinction {@link LockCitationResolvesTest} learned from its own first version.
     */
    private static boolean isLiveProse(Path markdown) {
        return !CHECKOUT.relative(markdown).startsWith("docs/archived/");
    }

    private static Set<String> definedCodes() throws IOException {
        Set<String> defined = new TreeSet<>();
        for (Path source : CHECKOUT.sources()) {
            if (!CHECKOUT.relative(source).startsWith("consistency/src/commonMain/")) {
                continue;
            }
            Matcher declaration = DEFINED.matcher(Files.readString(source));
            while (declaration.find()) {
                defined.add(declaration.group(1));
            }
        }
        return defined;
    }

    /** A code the checker emits: a string literal in {@code :consistency}'s main source, of either prefix it mints. */
    private static final Pattern EMITTED = Pattern.compile("\"((?:CK|RK)_[A-Z][A-Z0-9_]*)\"");

    @Test
    void everyCodeTheCheckerEmitsIsInItsApiReference() throws IOException {
        Set<String> emitted = new TreeSet<>();
        for (Path source : CHECKOUT.sources()) {
            if (CHECKOUT.relative(source).startsWith("consistency/src/commonMain/")) {
                Matcher literal = EMITTED.matcher(Files.readString(source));
                while (literal.find()) {
                    emitted.add(literal.group(1));
                }
            }
        }
        assertFalse(emitted.isEmpty(), "no RK_* or CK_* literal was found in consistency/src/commonMain — the scan "
                + "lost its denominator, which would make the reference pass for the wrong reason");
        String reference = Files.readString(CHECKOUT.resolve("docs/CONSISTENCY-API.md"));
        Set<String> undocumented = new TreeSet<>();
        for (String code : emitted) {
            if (!Pattern.compile("\\b" + code + "\\b").matcher(reference).find()) {
                undocumented.add(code);
            }
        }
        assertEquals(Set.of(), undocumented, "the checker emits codes its API reference never names");
    }

    @Test
    void everyCheckerOwnedCodeNamedInLiveProseIsDefined() throws IOException {
        Set<String> defined = definedCodes();
        assertFalse(defined.isEmpty(),
                "no CK_* constant was found in consistency/src/commonMain — the scan lost its denominator, "
                        + "which would make every citation below pass for the wrong reason");

        Map<String, Set<String>> unresolved = new LinkedHashMap<>();
        for (Path markdown : CHECKOUT.markdown()) {
            if (!isLiveProse(markdown)) {
                continue;
            }
            Matcher citation = CITED.matcher(Files.readString(markdown));
            while (citation.find()) {
                if (!defined.contains(citation.group())) {
                    unresolved.computeIfAbsent(CHECKOUT.relative(markdown), doc -> new TreeSet<>())
                            .add(citation.group());
                }
            }
        }

        assertEquals(Map.of(), unresolved,
                "a doc names a checker-owned code no constant defines: " + unresolved + " (defined: " + defined
                        + ")");
    }
}
