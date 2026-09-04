package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mbackschat.a12.dm.rulekit.validate.StructuralKernelFix;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The two SHIPPED sentences that state which tier {@code fieldRefByShortNameAllowed} governs must keep naming
 * it. Both were corrected on 2026-09-04 from a tier-blind wording that an author reads as the flag governing
 * every bare name — and the behaviour they describe is locked by
 * {@code rulekit.validate.laws.ShortNameTierLawsTest}, while the sentences themselves were locked by nothing:
 * a cold review pointed out that neither string had an enforcement locus, so both could silently revert to the
 * claim the measurement refuted.
 *
 * <p><b>What is pinned is the load-bearing clause, not the sentence.</b> Rewording stays free; dropping the
 * tier fails. That is the whole point of pinning a fragment rather than a digest here — this is help text, it
 * will be edited, and a guard that fights every edit gets deleted.
 *
 * <p>Read off the LIVE surfaces — the picocli option spec and the enricher's own hint — rather than from a
 * copied literal, so the pin cannot pass against a stale duplicate of either.
 */
class ShortNameTierWordingTest {

    private static final String PRESENT = "present";

    @Test
    void theHelpTextAndEnricherHintKeepNamingTheTierTheFlagGoverns() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("--field-ref-by-short-name names the MODEL-WIDE tier",
                contains(configModifyDescription(), "MODEL-WIDE"));
        actual.put("…and says the rule's own group resolves regardless of the flag",
                contains(configModifyDescription(), "rule's OWN group declares resolves regardless of the flag"));
        actual.put("the MVK_INVALID_ENTITY hint binds against the own group first",
                contains(invalidEntityFix(), "binds against the rule's OWN group first"));
        actual.put("…and denies an upward search rather than implying one",
                contains(invalidEntityFix(), "there is no upward search"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("--field-ref-by-short-name names the MODEL-WIDE tier", PRESENT);
        expected.put("…and says the rule's own group resolves regardless of the flag", PRESENT);
        expected.put("the MVK_INVALID_ENTITY hint binds against the own group first", PRESENT);
        expected.put("…and denies an upward search rather than implying one", PRESENT);
        assertEquals(expected, actual, "the tier is what the measurement established, so the text must say it");
    }

    private static String contains(String text, String fragment) {
        return text.contains(fragment) ? PRESENT : "MISSING — the live text reads: " + text;
    }

    /** The live {@code config modify --field-ref-by-short-name} description, read off the option itself. */
    private static String configModifyDescription() {
        return String.join(" ", new CommandLine(new RootCommand()).getSubcommands().get("config")
                .getSubcommands().get("modify").getCommandSpec()
                .findOption("--field-ref-by-short-name").description());
    }

    /** The live enricher hint that rides every {@code MVK_INVALID_ENTITY} diagnostic. */
    private static String invalidEntityFix() {
        return StructuralKernelFix.forCode("MVK_INVALID_ENTITY").orElseThrow().fix();
    }
}
