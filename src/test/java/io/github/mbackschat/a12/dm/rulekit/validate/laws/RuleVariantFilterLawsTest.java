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
        assertEquals(expected, actual, "what A12 does with each shape of a rule's variantFilter");
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
