package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §13 — which of the four YES/NO declaration laws a stored A12 model can actually reach.
 *
 * <p>[MVK-LEDGER] carries all four as {@code n/a-structural}: the yes and no representations must each be
 * declared, must differ, and must form a sanctioned pair. A12's DM-JSON {@code BooleanType} carries them as
 * {@code notInDCustomTrueValue} / {@code notInDCustomFalseValue}, and {@code TypeConverter.convertBoolean}
 * DEFAULTS each to {@code "true"} / {@code "false"} when absent — so "missing" and "present" may not be
 * distinguishable downstream at all. That is a reachability question, and it is measured rather than
 * reasoned, because the sort block next door split four-to-one on exactly this question
 * ({@code GroupIndexAndSortReferenceLawsTest}).
 *
 * <p><b>The model is a DRAFT with the field's type PATCHED</b>, because modelkit's {@code BooleanSpec} is an
 * empty record and cannot author either member — recorded as a gap. A hand-written model was tried first and
 * every row read NOT BUILDABLE; the reason recorded then — that the header carries laws of its own — was
 * wrong, and {@code ModelHeaderSlotLawsTest} has since measured it: one absent member, {@code modelVersion},
 * refuses the build with no class at all, while {@code id} and {@code modelType} may both be omitted.
 *
 * <p>Own domain (insurance).
 */
class BooleanCustomValueLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("policy-boolean").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .stringField("Renewal", f -> { }))
            .build().toJson();

    /** The fixture with `Renewal` retyped as a BooleanType carrying [booleanBody]. */
    private static String policy(String booleanBody) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ArrayNode elements = (ArrayNode) root.at("/content/modelRoot/rootGroups/0/Group/elements");
            for (var element : elements) {
                if ("Renewal".equals(element.path("name").asText())) {
                    ((ObjectNode) element.path("Field")).set(
                            "fieldType",
                            MAPPER.readTree("{\"type\":\"BooleanType\",\"BooleanType\":{" + booleanBody + "}}"));
                }
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    @Test
    void whichYesNoDeclarationsAStoredModelCanReach() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("neither custom value — the control", KernelLaws.verdictOf(policy("")));
        actual.put("both, and distinct",
                KernelLaws.verdictOf(policy("\"notInDCustomTrueValue\":\"Y\",\"notInDCustomFalseValue\":\"N\"")));
        actual.put("both, and IDENTICAL",
                KernelLaws.verdictOf(policy("\"notInDCustomTrueValue\":\"Y\",\"notInDCustomFalseValue\":\"Y\"")));
        actual.put("only the TRUE value", KernelLaws.verdictOf(policy("\"notInDCustomTrueValue\":\"Y\"")));
        actual.put("only the FALSE value", KernelLaws.verdictOf(policy("\"notInDCustomFalseValue\":\"N\"")));
        actual.put("both, and BLANK",
                KernelLaws.verdictOf(policy("\"notInDCustomTrueValue\":\"\",\"notInDCustomFalseValue\":\"\"")));

        // THE POSITIVE CONTROL, and the grid is worthless without it: every row above is ADMITTED, so a
        // patch that silently failed to land would produce exactly the same table. This one proves the
        // retyping reaches the Kernel at all.
        actual.put("the same patch with a type A12 does not know — the positive control",
                KernelLaws.verdictOf(policy("").replace("\"BooleanType\"", "\"NotAType\"")));

        Map<String, String> expected = new LinkedHashMap<>();
        // NONE of the four laws is reachable from a stored model. `TypeConverter.convertBoolean` defaults
        // each member to "true"/"false" when absent, so "missing" never reaches a check; and the pair laws
        // do not fire on a stored model either, identical and blank both being admitted.
        expected.put("neither custom value — the control", KernelLaws.ADMITTED);
        expected.put("both, and distinct", KernelLaws.ADMITTED);
        expected.put("both, and IDENTICAL", KernelLaws.ADMITTED);
        expected.put("only the TRUE value", KernelLaws.ADMITTED);
        expected.put("only the FALSE value", KernelLaws.ADMITTED);
        expected.put("both, and BLANK", KernelLaws.ADMITTED);
        // One tier earlier than a build failure: an unknown `fieldType.type` is refused by A12's READER,
        // so the patched bytes never reach a consistency check at all. That is a stronger control than the
        // build failure predicted — it proves the patch reaches the deserializer, not merely the checker.
        expected.put("the same patch with a type A12 does not know — the positive control",
                KernelLaws.UNREADABLE);

        assertEquals(expected, actual, "which yes/no declaration laws a stored A12 model can reach");
    }
}
