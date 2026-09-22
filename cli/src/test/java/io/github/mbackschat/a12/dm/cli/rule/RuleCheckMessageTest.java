package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code rule check --message <locale>=<text>} — the READ-ONLY route to the kernel's error-text checker
 * (dmtool feedback 2026-08-24, item 2).
 *
 * <p><b>Why the candidate route could not reach it before.</b> A rule must carry error text or the kernel
 * reports {@code MVK_ERROR_MESSAGE_MISSING}, so {@code RuleValidator} injects a placeholder per declared locale
 * — deliberately, so a missing message cannot mask the condition diagnostics. That placeholder is the rule's own
 * name, which is always a valid error text, so {@code RuleErrortextChecker} had nothing to reject and the only
 * way to ask the kernel about a message was the WRITE verb {@code rule add --dry-run}.
 *
 * <p><b>The option narrows to the text under test.</b> Locales the caller does not supply keep the placeholder,
 * so checking one locale on a bilingual model reports that locale's finding rather than a missing-message
 * finding for the other — which the caller never claimed to be authoring.
 * {@link #theUnsuppliedLocaleKeepsItsPlaceholderRatherThanReportingItMissing} is that row, and it is the one
 * that would silently invert if the placeholder were dropped instead of merged.
 */
class RuleCheckMessageTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Two declared locales, so the placeholder-merge row is reachable at all. */
    private static Path model(@TempDir Path dir) throws Exception {
        return CliTestSupport.modelWithRule(dir);
    }

    @Test
    void aMessageNamingAnUnknownEntityIsRejectedWithTheKernelsOwnClass(@TempDir Path dir) throws Exception {
        var res = check(dir, "en_US=See $Nonexistent$");

        JsonNode env = M.readTree(res.out());
        assertFalse(env.get("valid").asBoolean(), res.out());
        assertEquals("KERNEL_CONFIRMED", env.path("verification").asText(null), res.out());
        assertTrue(codes(env).contains("INVALID_ENTITY"), res.out());
    }

    @Test
    void aWellFormedMessageLeavesTheCandidateValid(@TempDir Path dir) throws Exception {
        var res = check(dir, "en_US=The monthly fee is too high.");

        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).get("valid").asBoolean(), res.out());
    }

    /** The default is unchanged: no --message means the placeholder, so no error-text finding can appear. */
    @Test
    void withoutTheOptionTheCandidateIsCheckedExactlyAsBefore(@TempDir Path dir) throws Exception {
        var res = check(dir, null);

        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).get("valid").asBoolean(), res.out());
    }

    @Test
    void theUnsuppliedLocaleKeepsItsPlaceholderRatherThanReportingItMissing(@TempDir Path dir) throws Exception {
        var res = check(dir, "en_US=The monthly fee is too high.");

        var codes = codes(M.readTree(res.out()));
        assertFalse(codes.contains("MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING")
                        || codes.contains("RULE_HAS_NO_ERRORTEXT"),
                () -> "the de_DE locale must keep its placeholder: " + res.out());
    }

    /**
     * An UNDECLARED locale is refused rather than checked, because the kernel would not check it either.
     * Measured on one model declaring {@code en_US} + {@code de_DE}: the identical {@code $Nonexistent$} text is
     * rejected {@code INVALID_ENTITY} in {@code en_US} and accepted with no diagnostic in {@code fr_FR}, so the
     * error-text checker runs per DECLARED locale only. Passing it through would therefore answer
     * {@code valid:true} about a text the kernel never examined — the one answer this verb must not give. An
     * advisory beside {@code valid:true} was the alternative and is worse: it discloses the hole instead of
     * closing it.
     */
    @Test
    void anUndeclaredLocaleIsRefusedRatherThanSilentlyUnchecked(@TempDir Path dir) throws Exception {
        var res = check(dir, "fr_FR=See $Nonexistent$");

        assertTrue(res.code() != 0, () -> "an unchecked locale must not report a verdict: " + res.out());
        String reported = res.out() + res.err();
        assertTrue(reported.contains("fr_FR"), () -> "names the offending locale: " + reported);
        assertTrue(reported.contains("en_US") && reported.contains("de_DE"),
                () -> "names the locales the model declares: " + reported);
    }

    private static CliTestSupport.Result check(Path dir, String message) throws Exception {
        Path model = model(dir);
        if (message == null) {
            return CliTestSupport.run("-m", model.toString(), "rule", "check",
                    "--field", "/Subscription/Addons/MonthlyFee",
                    "--condition", "[/Subscription/Addons/MonthlyFee] > 1000", "--code", "FEE_CAP");
        }
        return CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Subscription/Addons/MonthlyFee",
                "--condition", "[/Subscription/Addons/MonthlyFee] > 1000", "--code", "FEE_CAP",
                "--message", message);
    }

    private static List<String> codes(JsonNode envelope) {
        List<String> codes = new java.util.ArrayList<>();
        envelope.path("diagnostics").forEach(d -> codes.add(d.path("code").asText("")));
        return codes;
    }
}
