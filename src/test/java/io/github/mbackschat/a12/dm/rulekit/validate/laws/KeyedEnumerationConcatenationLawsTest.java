package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Whether a KEY-SELECTED enumeration cell in a text concatenation follows the display-label law of a direct read
 * (KERNEL-FINDINGS KF396): immediate and remapped is refused, braced or identity-labelled is admitted.
 *
 * <p><b>Typed-surface boundary.</b> The subject is the brace around the operand, which is concrete syntax with no
 * node of its own, so the rows are condition text.
 *
 * <p>Own domain (an insurance policy's claims, keyed by code).
 */
class KeyedEnumerationConcatenationLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-keyed-plan").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Key", f -> { })
                    .stringField("Note", f -> { })
                    .group("Claims", c -> c.repeatable(5).indexField("Code")
                            .stringField("Code", f -> { })
                            .enumField("Plan", e -> e.value("BASIC", l -> l.label("en_US", "Basic cover"))
                                    .value("GOLD", l -> l.label("en_US", "Gold cover")))
                            .enumField("Tier", e -> e.value("A").value("B"))))
            .build().toJson();

    private static String verdict(String concatenation) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Note", "FieldFilled(Note) And " + concatenation + " == [Note]");
    }

    @Test
    void aKeyedEnumerationCellFollowsTheDirectReadsLabelLaw() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("remapped, immediate", verdict("[Claims/Plan For Key] + [Note]"));
        actual.put("remapped, braced", verdict("{[Claims/Plan For Key]} + [Note]"));
        actual.put("identity, immediate", verdict("[Claims/Tier For Key] + [Note]"));
        actual.put("remapped, unkeyed immediate — the KF396 control", KernelLaws.verdictModel(POLICY,
                "/Policy/Claims", "../Code", "FieldFilled(Code) And [Plan] + [Code] == [Code]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("remapped, immediate", "MVK_NO_STRING_OR_NUMBER_FOR_ADD");
        expected.put("remapped, braced", KernelLaws.ADMITTED);
        expected.put("identity, immediate", KernelLaws.ADMITTED);
        expected.put("remapped, unkeyed immediate — the KF396 control", "MVK_NO_STRING_OR_NUMBER_FOR_ADD");
        assertEquals(expected, actual);
    }
}
