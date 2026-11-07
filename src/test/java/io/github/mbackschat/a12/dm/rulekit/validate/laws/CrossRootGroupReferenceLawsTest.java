package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS KF679 — <b>a relative condition reference may turn on the model root into a sibling root group</b>
 * (kernel 31.3.1, A12K-4276). The model root is a turning point and never a target: a name directly under it is
 * judged as a root group, and a path that stops on it or climbs past it keeps {@code MVK_TOO_MANY_PARENT_GROUPS}.
 *
 * <p>The discriminating rows: {@code ../Order/Code} re-enters the rule's own root group, which a reading that
 * special-cased "another root group" would not admit; {@code ../../Customer/Name} from a root-group rule climbs past
 * the model root, which a reading that clamped the climb at the root would admit; the repeatable sibling keeps the
 * starring law of an absolute path. The checker's agreement, the message, error-target and computed-target roles,
 * and the runtime are {@code adapter.laws.CrossRootGroupReferenceDiffTest}.
 *
 * <p>Own domain (an e-commerce order, its customer and its vouchers).
 */
class CrossRootGroupReferenceLawsTest {

    private static final String SHOP = DocumentModelDraft.named("shop").locale("en_US")
            .rootGroup("Order", o -> o
                    .stringField("Code", f -> { })
                    .group("Lines", g -> g.repeatable(10)
                            .numberField("Amount", f -> { })
                            .stringField("Sku", f -> { })))
            .rootGroup("Customer", c -> c
                    .stringField("Name", f -> { })
                    .stringField("Tier", f -> { }))
            .rootGroup("Voucher", v -> v.repeatable(3)
                    .numberField("Value", f -> { }))
            .build().toJson();

    @Test
    void fromARootGroupRuleTheModelRootIsATurningPoint() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("../Customer/Name", KernelLaws.ADMITTED);
        expected.put("../Order/Code", KernelLaws.ADMITTED);
        expected.put("/Customer/Name", KernelLaws.ADMITTED);
        expected.put("../Customer/Nope", "MVK_INVALID_ENTITY");
        expected.put("../Nope/Name", "MVK_INVALID_ROOT_GROUP");
        expected.put("../Voucher/Value", "MVK_NO_WILDCARD");
        expected.put("../Voucher*/Value", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("..*/Customer/Name", "MVK_INVALID_WILDCARD_REL");
        expected.put("../../Customer/Name", "MVK_TOO_MANY_PARENT_GROUPS");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(operand -> actual.put(operand,
                verdict("/Order", "../Code", "FieldFilled(Code) And FieldFilled(" + operand + ")")));
        assertEquals(expected, actual, "a field operand from a rule in /Order");
    }

    /** The model root is never a target, whether the path stops on it or climbs past it. */
    @Test
    void theModelRootIsNeverATarget() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("../Customer", KernelLaws.ADMITTED);
        expected.put("../Nope", "MVK_INVALID_ROOT_GROUP");
        expected.put("..", "MVK_TOO_MANY_PARENT_GROUPS");
        expected.put("..*", "MVK_INVALID_WILDCARD_REL");
        expected.put("../..", "MVK_TOO_MANY_PARENT_GROUPS");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(operand -> actual.put(operand,
                verdict("/Order", "../Code", "FieldFilled(Code) And GroupFilled(" + operand + ")")));
        assertEquals(expected, actual, "a group operand from a rule in /Order");
    }

    @Test
    void fromANestedRuleTheTurningPointIsOneStepFurther() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("../../Customer/Name", KernelLaws.ADMITTED);
        expected.put("../../Order/Lines/Sku", KernelLaws.ADMITTED);
        expected.put("../../Nope/Name", "MVK_INVALID_ROOT_GROUP");
        expected.put("../../Voucher*/Value", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("../../../Customer/Name", "MVK_TOO_MANY_PARENT_GROUPS");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(operand -> actual.put(operand,
                verdict("/Order/Lines", "../Amount", "FieldFilled(Amount) And FieldFilled(" + operand + ")")));
        assertEquals(expected, actual, "a field operand from a rule in /Order/Lines");
    }

    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(SHOP, group, errorField, condition);
        return r.ok() ? KernelLaws.ADMITTED : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).distinct().toList());
    }
}
