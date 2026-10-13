package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A rule's {@code variantFilter} — and the fact that matters far more than its shape: an {@code ONLY} filter
 * takes the declaration OUT of what A12 checks, so a BROKEN rule carrying one is reported clean.
 *
 * <p><b>The Kernel says its own check is incomplete for a variant model, and says it only to a LOG.</b>
 * {@code consistency.rules.CodeGenerationRule.checkModel} generates validation code for the default variant,
 * then, if {@code extractUsedVariants(model)} is non-empty, calls {@code LOG.warn("… A complete check was done
 * only for the default variant (empty string). Therefore, the check may not be complete.")}. It never reports
 * a problem, and no {@code MVK_*} code for variants exists at all — so a consumer of {@code checkConsistency}
 * is told nothing whatsoever.
 *
 * <p><b>The FILTER TYPE alone decides, and the variant names are irrelevant</b> — measured as a within-model
 * 2×2 over {@code {ONLY, ALL_EXCEPT} × {"premium", ""}} on one broken rule:
 *
 * <table><caption>is the broken rule caught?</caption>
 * <tr><td>{@code ONLY ["premium"]}</td><td>no — ADMITTED</td></tr>
 * <tr><td>{@code ONLY [""]}</td><td>no — ADMITTED, though it names the default variant itself</td></tr>
 * <tr><td>{@code ALL_EXCEPT ["premium"]}</td><td>yes — {@code MVK_INVALID_ENTITY}</td></tr>
 * <tr><td>{@code ALL_EXCEPT [""]}</td><td>yes — {@code MVK_INVALID_ENTITY}</td></tr>
 * </table>
 *
 * <p>The grid is what makes the claim safe, and it took three attempts to get right. A first version varied
 * only the filter's PRESENCE, which cannot separate "the filter is inert" from "the filter suppresses". A
 * second added {@code ALL_EXCEPT} and looked like it showed default-variant MEMBERSHIP deciding — until
 * {@code ONLY [""]} named the default variant and was still unchecked, which no membership account survives.
 * Two controls hold the rest fixed: a sound rule admits either way (liveness), and the same broken rule
 * WITHOUT a filter is refused (the break is detectable at all).
 *
 * <p>Own domain (the shared subscription fixture).
 */
class RuleVariantFilterLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A condition naming a field that does not exist — reliably refused when the rule IS judged. */
    private static final String BROKEN = "FieldFilled(NoSuchFieldAnywhere)";

    /** A condition over a real field of the probe's group. */
    private static final String SOUND = "FieldFilled(Tier)";

    private static final String ONLY_PREMIUM = "{\"filterType\":\"ONLY\",\"variants\":[\"premium\"]}";

    @Test
    void aBrokenRuleIsStillJudgedWhenAVariantFilterExcludesIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("sound, no filter — the liveness control", verdict(SOUND, null));
        actual.put("sound, filtered to a non-default variant", verdict(SOUND, ONLY_PREMIUM));
        actual.put("BROKEN, no filter — proves the break is detectable", verdict(BROKEN, null));
        actual.put("BROKEN, filtered to a non-default variant", verdict(BROKEN, ONLY_PREMIUM));
        // The two rows that separate "ANY filter suppresses" from "EXCLUSION from the default variant
        // suppresses". Both of these INCLUDE the default variant, so a filter that is merely present cannot
        // explain them: ALL_EXCEPT premium covers everything that is not premium, and ONLY "" names the
        // default variant itself.
        actual.put("BROKEN, ALL_EXCEPT a non-default variant — default still included",
                verdict(BROKEN, "{\"filterType\":\"ALL_EXCEPT\",\"variants\":[\"premium\"]}"));
        actual.put("BROKEN, ONLY the default variant", verdict(BROKEN, "{\"filterType\":\"ONLY\",\"variants\":[\"\"]}"));
        actual.put("BROKEN, ALL_EXCEPT the default variant — the 2x2's fourth cell",
                verdict(BROKEN, "{\"filterType\":\"ALL_EXCEPT\",\"variants\":[\"\"]}"));
        // The shape rows below are measured SOUND in the other test, where ADMITTED cannot distinguish "checked
        // and clean" from "never checked". Carrying the broken condition through them is what settles whether an
        // ONLY filter naming NO variant still suppresses — which decides whether the checker owes a coverage gap.
        actual.put("BROKEN, ONLY an empty variants list", verdict(BROKEN, "{\"filterType\":\"ONLY\",\"variants\":[]}"));
        actual.put("BROKEN, ONLY with no variants member", verdict(BROKEN, "{\"filterType\":\"ONLY\"}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("sound, no filter — the liveness control", KernelLaws.ADMITTED);
        expected.put("sound, filtered to a non-default variant", KernelLaws.ADMITTED);
        // Without this row the broken/filtered row below proves nothing: an ADMITTED there could mean the
        // filter suppressed the check OR that the condition was never broken in the first place.
        expected.put("BROKEN, no filter — proves the break is detectable", "MVK_INVALID_ENTITY");
        // The row the whole test exists for: a variant filter removes the declaration from A12's verdict, so
        // a model carrying a BROKEN rule is reported clean.
        expected.put("BROKEN, filtered to a non-default variant", KernelLaws.ADMITTED);
        expected.put("BROKEN, ALL_EXCEPT a non-default variant — default still included", "MVK_INVALID_ENTITY");
        expected.put("BROKEN, ONLY the default variant", KernelLaws.ADMITTED);
        expected.put("BROKEN, ALL_EXCEPT the default variant — the 2x2's fourth cell", "MVK_INVALID_ENTITY");
        expected.put("BROKEN, ONLY an empty variants list", KernelLaws.ADMITTED);
        expected.put("BROKEN, ONLY with no variants member", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "whether a variant filter takes a rule out of A12's check");
    }

    /**
     * WHICH KINDS of break an {@code ONLY} filter hides — the scope question the checker has to answer.
     *
     * <p>The grid above measured one break: a condition naming an entity that does not exist. A checker that
     * suppresses its whole report for an {@code ONLY}-filtered declaration is transferring that one row to
     * every other family it runs, and the transfer is exactly the kind this repository has got wrong before —
     * it needs no new branch, so nothing prompts the question. So each break is measured on its own, twice:
     * unfiltered, which proves the break is detectable at all, and under {@code ONLY}.
     *
     * <p><b>The answer is that it hides every VERDICT, and the exception is what a checker must be built
     * around.</b> Six of the seven breaks go silent — the condition's references, all three ways its text can
     * fail to parse, the error target, and the error messages — so there is no surviving subset a checker
     * could go on judging. The seventh does not: an empty {@code errorCode} still refuses the model, and it
     * does so with NO {@code MVK_*} code at all, where unfiltered it reports {@code MVK_ERROR_CODE_MISSING}.
     *
     * <p>That row draws the line the fix rests on: a filter suppresses what A12 would SAY ABOUT a
     * declaration, and cannot rescue a model that fails to BUILD. Three predictions were wrong here, all in
     * the same direction — the declaration's own slots were expected to be judged regardless, on the theory
     * that a consistency rule reading them never consults variants — which is why the scope was measured per
     * break instead of transferred from the one reference row that was already in hand.
     */
    @Test
    void whichKindsOfBreakAnOnlyFilterHides() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a sound rule — the liveness control", verdict(Break.NOTHING, null));
        actual.put("a sound rule under ONLY — the filter alone breaks nothing", verdict(Break.NOTHING, ONLY_PREMIUM));
        for (Break broken : Break.values()) {
            if (broken != Break.NOTHING) {
                actual.put(broken.label + ", unfiltered", verdict(broken, null));
                actual.put(broken.label + ", ONLY", verdict(broken, ONLY_PREMIUM));
            }
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a sound rule — the liveness control", KernelLaws.ADMITTED);
        expected.put("a sound rule under ONLY — the filter alone breaks nothing", KernelLaws.ADMITTED);
        // The anchor row, repeated from the grid above so this grid carries its own reference case.
        expected.put("a condition naming no entity, unfiltered", "MVK_INVALID_ENTITY");
        expected.put("a condition naming no entity, ONLY", KernelLaws.ADMITTED);
        // The three condition-TEXT breaks: all three go silent, so the filter covers the whole syntax layer
        // and not merely the reference family.
        expected.put("a condition that cannot be parsed, unfiltered", "MVK_EXPECTED_TOKEN_NOT_FOUND");
        expected.put("a condition that cannot be parsed, ONLY", KernelLaws.ADMITTED);
        expected.put("a condition that ends early, unfiltered", "MVK_INCOMPLETE_INPUT");
        expected.put("a condition that ends early, ONLY", KernelLaws.ADMITTED);
        expected.put("a condition that cannot be tokenized, unfiltered", "MVK_LEXER_STANDARD_ERROR");
        expected.put("a condition that cannot be tokenized, ONLY", KernelLaws.ADMITTED);
        // The declaration's own slots go silent too — so the filter is not scoped to the condition, and a
        // checker cannot keep judging "the parts A12 still looks at", because there are none.
        expected.put("an error target naming no field, unfiltered", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("an error target naming no field, ONLY", KernelLaws.ADMITTED);
        expected.put("no error message at all, unfiltered",
                "MVK_ERROR_MESSAGE_MISSING,RULE_HAS_NO_ERRORTEXT,RULE_HAS_NO_ERRORTEXT");
        expected.put("no error message at all, ONLY", KernelLaws.ADMITTED);
        // THE EXCEPTION, and the row that shapes the fix: an empty error code still refuses the model, with
        // no MVK code at all rather than the one it carries unfiltered. What a filter suppresses is A12's
        // CLASSED verdicts about a declaration; it does not reach a refusal A12 states without naming a
        // class. (This row was read as a BUILD failure until 2026-09-15 — the "it cannot rescue a model that
        // fails to build" account was the harness collapsing two codeless tiers, and the two UNREADABLE rows
        // below are the only ones here that genuinely never load.) So a checker must go silent about what A12
        // would have judged while keeping the findings that say the model is refused anyway — and it must not
        // reproduce `MVK_ERROR_CODE_MISSING` here, which A12 stops reporting.
        expected.put("an empty error code, unfiltered", "MVK_ERROR_CODE_MISSING");
        expected.put("an empty error code, ONLY", KernelLaws.REFUSED_CODELESS);
        // The two shapes A12's READER refuses, which is a tier earlier than any variant logic — and with the
        // row above re-tiered, these two are the whole of "the filter cannot rescue a model that does not
        // load". They are what the checker's CK_MODEL_NOT_BUILDABLE exemption rests on.
        expected.put("an error code that is JSON null, unfiltered", KernelLaws.UNREADABLE);
        expected.put("an error code that is JSON null, ONLY", KernelLaws.UNREADABLE);
        expected.put("an error message whose locale the reader refuses, unfiltered", KernelLaws.UNREADABLE);
        expected.put("an error message whose locale the reader refuses, ONLY", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "which kinds of break an ONLY variant filter hides");
    }

    @Test
    void whatA12DoesWithEachVariantFilterShape() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("absent — the control", verdict(SOUND, null));
        actual.put("ONLY one variant", verdict(SOUND, ONLY_PREMIUM));
        actual.put("ALL_EXCEPT one variant", verdict(SOUND, "{\"filterType\":\"ALL_EXCEPT\",\"variants\":[\"premium\"]}"));
        actual.put("an unknown filterType", verdict(SOUND, "{\"filterType\":\"SOMETIMES\",\"variants\":[\"premium\"]}"));
        actual.put("no filterType", verdict(SOUND, "{\"variants\":[\"premium\"]}"));
        actual.put("an empty variants list", verdict(SOUND, "{\"filterType\":\"ONLY\",\"variants\":[]}"));
        actual.put("no variants member", verdict(SOUND, "{\"filterType\":\"ONLY\"}"));
        actual.put("an explicit null", verdict(SOUND, "null"));
        actual.put("a string instead of an object", verdict(SOUND, "\"ONLY\""));
        // Three shapes the checker answers with three DIFFERENT messages while only two arms existed, so two
        // of them named the wrong defect. Measuring them is what lets the messages be specific.
        actual.put("a filterType that is JSON null", verdict(SOUND, "{\"filterType\":null}"));
        actual.put("a filterType that is an object", verdict(SOUND, "{\"filterType\":{}}"));
        actual.put("a filterType that is a number", verdict(SOUND, "{\"filterType\":9}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("absent — the control", KernelLaws.ADMITTED);
        expected.put("ONLY one variant", KernelLaws.ADMITTED);
        expected.put("ALL_EXCEPT one variant", KernelLaws.ADMITTED);
        expected.put("an unknown filterType", KernelLaws.UNREADABLE);
        expected.put("no filterType", KernelLaws.NOT_BUILDABLE);
        expected.put("an empty variants list", KernelLaws.ADMITTED);
        expected.put("no variants member", KernelLaws.ADMITTED);
        expected.put("an explicit null", KernelLaws.ADMITTED);
        expected.put("a string instead of an object", KernelLaws.UNREADABLE);
        expected.put("a filterType that is JSON null", KernelLaws.UNREADABLE);
        expected.put("a filterType that is an object", KernelLaws.UNREADABLE);
        expected.put("a filterType that is a number", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "what A12 does with each shape of a rule's variantFilter");
    }

    /**
     * One way of breaking the probe rule, each detectable on its own when the rule carries no filter.
     *
     * <p>An enum rather than four string parameters: the slots are all {@code String}, so a positional helper
     * would be silently swap-prone, and each constant names the defect a reader has to hold in mind.
     */
    private enum Break {
        NOTHING("a sound rule"),
        CONDITION_ENTITY("a condition naming no entity"),
        UNPARSEABLE_CONDITION("a condition that cannot be parsed"),
        INCOMPLETE_CONDITION("a condition that ends early"),
        UNLEXABLE_CONDITION("a condition that cannot be tokenized"),
        ERROR_TARGET("an error target naming no field"),
        ERROR_MESSAGES("no error message at all"),
        // Last because these are the breaks the filter does NOT hide, and the grid reads as its punchline.
        ERROR_CODE("an empty error code"),
        NULL_ERROR_CODE("an error code that is JSON null"),
        REFUSED_MESSAGE_LOCALE("an error message whose locale the reader refuses");

        private final String label;

        Break(String label) {
            this.label = label;
        }

        private void applyTo(ObjectNode rule) {
            switch (this) {
                case NOTHING -> { }
                case CONDITION_ENTITY -> rule.put("errorCondition", BROKEN);
                case UNPARSEABLE_CONDITION -> rule.put("errorCondition", "FieldFilled(Tier");
                case INCOMPLETE_CONDITION -> rule.put("errorCondition", "FieldFilled(Tier) And");
                case UNLEXABLE_CONDITION -> rule.put("errorCondition", "FieldFilled(\"Tier)");
                case ERROR_TARGET -> rule.put("errorEntityRelPath", "../NoSuchFieldAnywhere");
                case ERROR_CODE -> rule.put("errorCode", "");
                case ERROR_MESSAGES -> rule.putArray("errorMessage");
                case NULL_ERROR_CODE -> rule.putNull("errorCode");
                case REFUSED_MESSAGE_LOCALE -> rule.putArray("errorMessage").addObject()
                        .put("locale", "not a locale").put("text", "x");
            }
        }
    }

    /** What A12 says about the probe model whose rule is broken in one way and filtered (or not). */
    private static String verdict(Break broken, String filterJson) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(probe(SOUND, filterJson));
            broken.applyTo(ruleBody(root));
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the probe's own output must parse", unreadable);
        }
    }

    /** What A12 says about the probe model whose rule carries [condition] and [filterJson] (absent when null). */
    private static String verdict(String condition, String filterJson) {
        return KernelLaws.verdictOf(probe(condition, filterJson));
    }

    private static String probe(String condition, String filterJson) {
        String written = ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription", "VariantProbe",
                "../Tier", "PROBE", condition, "ERROR", List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(written);
            ObjectNode rule = ruleBody(root);
            if (filterJson != null) {
                rule.set("variantFilter", MAPPER.readTree(filterJson));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output and the raw fragment must parse", unreadable);
        }
    }

    private static ObjectNode ruleBody(ObjectNode root) {
        for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if ("VariantProbe".equals(element.path("name").asText())) {
                return (ObjectNode) element.path("Rule");
            }
        }
        throw new IllegalStateException("the probe rule is no longer where this test writes it");
    }
}
