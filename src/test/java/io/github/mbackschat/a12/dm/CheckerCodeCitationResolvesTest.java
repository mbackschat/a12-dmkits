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
 * <p><b>One direction only, deliberately.</b> A cited code must exist; an existing code need not be cited.
 * Requiring the reverse would force a doc paragraph for every code the moment it is minted, which is an
 * editorial decision rather than a drift check — and a code with no prose cannot strand a reader the way a
 * name that resolves to nothing does.
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
