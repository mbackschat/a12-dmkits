package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr;

/**
 * Whether a KEY-SELECTED enumeration cell in a text concatenation follows the display-label law of a direct read
 * (KERNEL-FINDINGS KF396): immediate and remapped is refused, braced, unlabelled or identity-labelled is admitted,
 * whether a literal key or a key field selects it. A first-filled enumeration is not a field read at all, so the
 * label question is never asked of it.
 *
 * <p><b>Typed-surface boundary.</b> The subject is the brace around the operand, which is concrete syntax with no
 * node of its own, so the measuring rows are condition text; the typed {@code EnumExpr.plus} is checked separately
 * against the same kernel.
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
                            .enumField("Tier", e -> e.value("A").value("B"))
                            .enumField("Grade", e -> e.value("A", l -> l.label("en_US", "A"))
                                    .value("B", l -> l.label("en_US", "B")))))
            .build().toJson();

    private static String verdict(String concatenation) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Note", "FieldFilled(Note) And " + concatenation + " == [Note]");
    }

    @Test
    void aKeyedEnumerationCellFollowsTheDirectReadsLabelLaw() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("remapped, immediate", verdict("[Claims/Plan For Key] + [Note]"));
        actual.put("remapped, braced", verdict("{[Claims/Plan For Key]} + [Note]"));
        actual.put("remapped, literal key, immediate", verdict("[Claims/Plan For \"C1\"] + [Note]"));
        actual.put("remapped, literal key, braced", verdict("{[Claims/Plan For \"C1\"]} + [Note]"));
        actual.put("unlabelled, immediate", verdict("[Claims/Tier For Key] + [Note]"));
        actual.put("identity-labelled, immediate", verdict("[Claims/Grade For Key] + [Note]"));
        actual.put("identity-labelled, literal key, immediate", verdict("[Claims/Grade For \"C1\"] + [Note]"));
        actual.put("identity-labelled, braced", verdict("{[Claims/Grade For Key]} + [Note]"));
        actual.put("remapped, first filled", verdict("FirstFilledValue(Claims*/Plan) + [Note]"));
        actual.put("remapped, unkeyed immediate — the KF396 control", KernelLaws.verdictModel(POLICY,
                "/Policy/Claims", "../Code", "FieldFilled(Code) And [Plan] + [Code] == [Code]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("remapped, immediate", "MVK_NO_STRING_OR_NUMBER_FOR_ADD");
        expected.put("remapped, braced", KernelLaws.ADMITTED);
        expected.put("remapped, literal key, immediate", "MVK_NO_STRING_OR_NUMBER_FOR_ADD");
        expected.put("remapped, literal key, braced", KernelLaws.ADMITTED);
        expected.put("unlabelled, immediate", KernelLaws.ADMITTED);
        expected.put("identity-labelled, immediate", KernelLaws.ADMITTED);
        expected.put("identity-labelled, literal key, immediate", KernelLaws.ADMITTED);
        expected.put("identity-labelled, braced", KernelLaws.ADMITTED);
        expected.put("remapped, first filled", KernelLaws.ADMITTED);
        expected.put("remapped, unkeyed immediate — the KF396 control", "MVK_NO_STRING_OR_NUMBER_FOR_ADD");
        assertEquals(expected, actual);
    }

    /** What the typed {@code EnumExpr.plus} renders for a remapped key-selected cell and a first-filled value. */
    @Test
    void theTypedSurfaceRendersAnAdmittedShape() {
        BoundModel m = BoundModel.ofJson(POLICY);
        StringExpr note = m.str("/Policy/Note").asString();
        Map<String, EnumExpr> operands = new LinkedHashMap<>();
        operands.put("key field", m.enumOf("/Policy/Claims/Plan").forIndex(m.field("/Policy/Key")));
        operands.put("literal key", m.enumOf("/Policy/Claims/Plan").forIndex("C1"));
        operands.put("first filled", Dsl.firstFilledEnum(m.flatten("/Policy/Claims*/Plan")));
        Map<String, String> actual = new LinkedHashMap<>();
        operands.forEach((name, operand) -> actual.put(name, KernelLaws.verdictModel(POLICY, "/Policy", "../Note",
                m.str("/Policy/Note").filled().and(operand.plus(note).eq(note)))));
        Map<String, String> expected = new LinkedHashMap<>();
        operands.keySet().forEach(name -> expected.put(name, KernelLaws.ADMITTED));
        assertEquals(expected, actual);
    }
}
