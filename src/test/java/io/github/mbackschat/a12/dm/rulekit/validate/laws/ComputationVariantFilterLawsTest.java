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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A computation's {@code variantFilter} — the SECOND CARRIER of the law {@code RuleVariantFilterLawsTest}
 * measured on a rule, run here because reuse across a carrier is a claim rather than an inheritance.
 *
 * <p>{@link RuleVariantFilterLawsTest} established that an {@code ONLY} filter takes a rule out of what
 * {@code checkConsistency} judges, so a BROKEN rule carrying one is reported clean, and that the FILTER TYPE
 * alone decides it. The mechanism read from source — {@code consistency.rules.CodeGenerationRule.checkModel}
 * generating validation code for the default variant and then only {@code LOG.warn}-ing — is stated at the
 * MODEL level and names no declaration kind, which predicts the same outcome for a computation. That
 * prediction is exactly the kind that has been wrong here before, and it costs one probe to settle, so it is
 * settled rather than carried across: {@code variantFilter} is a member of the computation envelope too, and
 * the checker's coverage gap has to know which declarations it covers.
 *
 * <p>The break is a computation's {@code operation} naming a field that does not exist, which is the
 * computation-side analogue of the rule probe's unresolvable condition reference: reliably refused when the
 * declaration IS judged, and invisible when it is not.
 *
 * <p>Own domain (the shared subscription fixture).
 */
class ComputationVariantFilterLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GROUP = "/Subscription/Billing";

    /** An operation naming a field that exists nowhere — reliably refused when the computation IS judged. */
    private static final String BROKEN = "[NoSuchFieldAnywhere]";

    /** A constant operation, valid against any model — the liveness control. */
    private static final String SOUND = "42.00";

    private static final String ONLY_PREMIUM = "{\"filterType\":\"ONLY\",\"variants\":[\"premium\"]}";

    /**
     * The same 2×2 the rule carrier was measured on, with the same two controls.
     *
     * <p>Without the unfiltered BROKEN row an ADMITTED below could mean the filter suppressed the check OR
     * that the operation was never broken; without the sound rows it could mean the probe never validates at
     * all. The two rows that separate "ANY filter suppresses" from "EXCLUSION from the default variant
     * suppresses" are the {@code ALL_EXCEPT premium} and {@code ONLY ""} cells, because both INCLUDE the
     * default variant.
     */
    @Test
    void aBrokenComputationIsStillJudgedWhenAVariantFilterExcludesIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("sound, no filter — the liveness control", verdict(SOUND, null));
        actual.put("sound, filtered to a non-default variant", verdict(SOUND, ONLY_PREMIUM));
        actual.put("BROKEN, no filter — proves the break is detectable", verdict(BROKEN, null));
        actual.put("BROKEN, filtered to a non-default variant", verdict(BROKEN, ONLY_PREMIUM));
        actual.put("BROKEN, ALL_EXCEPT a non-default variant — default still included",
                verdict(BROKEN, "{\"filterType\":\"ALL_EXCEPT\",\"variants\":[\"premium\"]}"));
        actual.put("BROKEN, ONLY the default variant", verdict(BROKEN, "{\"filterType\":\"ONLY\",\"variants\":[\"\"]}"));
        actual.put("BROKEN, ALL_EXCEPT the default variant — the 2x2's fourth cell",
                verdict(BROKEN, "{\"filterType\":\"ALL_EXCEPT\",\"variants\":[\"\"]}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("sound, no filter — the liveness control", KernelLaws.ADMITTED);
        expected.put("sound, filtered to a non-default variant", KernelLaws.ADMITTED);
        expected.put("BROKEN, no filter — proves the break is detectable", "MVK_INVALID_ENTITY");
        expected.put("BROKEN, filtered to a non-default variant", KernelLaws.ADMITTED);
        expected.put("BROKEN, ALL_EXCEPT a non-default variant — default still included", "MVK_INVALID_ENTITY");
        expected.put("BROKEN, ONLY the default variant", KernelLaws.ADMITTED);
        expected.put("BROKEN, ALL_EXCEPT the default variant — the 2x2's fourth cell", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "whether a variant filter takes a computation out of A12's check");
    }

    /** What A12 does with each shape of the member, so the checker judges the shapes and not only the type. */
    @Test
    void whatA12DoesWithEachVariantFilterShapeOnAComputation() {
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
        assertEquals(expected, actual, "what A12 does with each shape of a computation's variantFilter");
    }

    private static String verdict(String operation, String filterJson) {
        return KernelLaws.verdictOf(probe(operation, filterJson));
    }

    private static String probe(String operation, String filterJson) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath(GROUP).name("VariantProbe").computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(new DraftComputation.Alternative(null, operation, null)))
                .messages(List.of(en("x"), de("y")))
                .build();
        String written = ModelWriter.addComputation(Fixtures.subscriptionJson(), draft,
                List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(written);
            ObjectNode computation = computationBody(root);
            if (filterJson != null) {
                computation.set("variantFilter", MAPPER.readTree(filterJson));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output and the raw fragment must parse", unreadable);
        }
    }

    private static ObjectNode computationBody(ObjectNode root) {
        for (var top : root.path("content").path("modelRoot").path("rootGroups")) {
            ObjectNode found = findProbe(top);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalStateException("the probe computation is no longer where this test writes it");
    }

    private static ObjectNode findProbe(JsonNode node) {
        if ("VariantProbe".equals(node.path("name").asText()) && node.has("Computation")) {
            return (ObjectNode) node.path("Computation");
        }
        for (var child : node.path("Group").path("elements")) {
            ObjectNode found = findProbe(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
