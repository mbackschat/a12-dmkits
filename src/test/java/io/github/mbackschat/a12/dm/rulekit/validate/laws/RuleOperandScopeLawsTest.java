package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §11 — <b>the operand-scope law on the RULE carrier</b>, the transfer
 * {@link ComputationOperandScopeLawsTest} could not make.
 *
 * <p>That class measured, on the COMPUTATION carrier, that an operand is admitted by whether the target's
 * repetition scope <b>binds</b> every repeatable level the operand crosses — and that a SIBLING repeatable
 * operand is decided by the index-field names rather than by siblinghood: same-named index admitted,
 * differently-named index {@code MVK_ERROR_FIELD_INVALID_INDEX}, no index field at all {@code MVK_NO_WILDCARD},
 * starred aggregate admitted as the flattening route.
 *
 * <p><b>Whether a rule decides those the same way was an open scope transfer, and it is exactly the crossing
 * this estate keeps getting wrong.</b> The reuse needs no new branch, so nothing prompts the question; the
 * three-in-one-week count on one operator family is why [KF120] and [KF218] both exist. a12-kernel-lean's
 * {@code SG5} needed the answer and reframed its row around the boundary rather than assuming it, which is
 * what prompted this run.
 *
 * <p><b>The model is the computation law's, unchanged.</b> Same fixture, same loci, same operands — the ONLY
 * thing that varies is the carrier, because two models differ in every way nobody thought about. The
 * error-field locus takes the role the computation's target took.
 *
 * <p><b>The carriers are not symmetric, and the asymmetry is load-bearing rather than incidental.</b> A rule's
 * condition MUST reference its error field ({@code MVK_ERROR_FIELD_NOT_REFERENCED}), so every row below reads
 * the error field as well as the probe operand. A computation can pair a bare constant with a repeating
 * target; a rule cannot, so the rule rows always carry two operands where the computation rows carried one.
 * That is a property of the carrier and not a flaw in the transfer — it is the reason the transfer had to be
 * measured rather than reasoned about, since the extra operand is itself a scope participant.
 *
 * <p>Own domain (motor insurance claims), shared with the computation law by design.
 */
class RuleOperandScopeLawsTest {

    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String INVALID_INDEX = "MVK_ERROR_FIELD_INVALID_INDEX";

    /**
     * {@link ComputationOperandScopeLawsTest}'s fixture verbatim: a root scope, a repeatable {@code Claims}
     * group, a repeatable {@code Items} nested in it, and three repeatable siblings of {@code Claims} that
     * differ from one another in exactly one thing — whether they declare an index field, and under which
     * name. {@code Notes} carries the same key FIELD as its siblings and merely does not DECLARE it, so the
     * no-index row varies one thing rather than two.
     */
    private static final String CLAIMS = DocumentModelDraft.named("motor-claims").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("PolicyRef", s -> { })
                    .numberField("PolicyExcess", n -> n.fractionDigits(0))
                    .numberField("PolicyTotal", n -> n.fractionDigits(0))
                    .group("Claims", c -> c.repeatable(20).indexField("ClaimKey")
                            .field("ClaimKey", FieldKind.STRING)
                            .numberField("Amount", n -> n.fractionDigits(0))
                            .numberField("Net", n -> n.fractionDigits(0))
                            .group("Items", i -> i.repeatable(20).indexField("ItemKey")
                                    .field("ItemKey", FieldKind.STRING)
                                    .numberField("ItemAmount", n -> n.fractionDigits(0))
                                    .numberField("ItemNet", n -> n.fractionDigits(0))))
                    .group("Payments", pay -> pay.repeatable(20).indexField("PayKey")
                            .field("PayKey", FieldKind.STRING)
                            .numberField("PayAmount", n -> n.fractionDigits(0)))
                    .group("Adjustments", a -> a.repeatable(20).indexField("ClaimKey")
                            .field("ClaimKey", FieldKind.STRING)
                            .numberField("AdjAmount", n -> n.fractionDigits(0)))
                    .group("Notes", nt -> nt.repeatable(20)
                            .field("ClaimKey", FieldKind.STRING)
                            .numberField("NoteAmount", n -> n.fractionDigits(0))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(CLAIMS);

    private static final String CLAIM_NET = "/Policy/Claims/Net";
    private static final String ITEM_NET = "/Policy/Claims/Items/ItemNet";
    private static final String POLICY_TOTAL = "/Policy/PolicyTotal";

    /**
     * The verdict on one rule whose error field sits at {@code errorField} and whose condition reads that
     * field and the probe operand. The rule is declared in the error field's own group, so the declaring
     * group never becomes a second variable — that axis is {@link ComputationDeclaringGroupLawsTest}'s.
     */
    private static String verdict(String errorField, String operandPath) {
        String group = errorField.substring(0, errorField.lastIndexOf('/'));
        String errorRel = EntityPath.of(group + "/ProbeRule").relativize(EntityPath.of(errorField));
        String errorName = errorField.substring(errorField.lastIndexOf('/') + 1);
        String condition = "FieldFilled(" + errorName + ") And [" + operandPath + "] > 0";
        ValidationResult r = KernelLaws.checkModel(CLAIMS, group, errorRel, condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /**
     * The binding direction, transferred. An operand at or above the error field's scope is addressable from
     * its coordinates and is admitted; the own-level row is the control that keeps an all-admitted table from
     * being explained by "a rule admits every operand".
     */
    @Test
    void anOperandTheErrorFieldsScopeBindsIsAdmitted() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("form-level operand into a one-level error field", verdict(CLAIM_NET, POLICY_TOTAL));
        actual.put("form-level operand into a two-level error field", verdict(ITEM_NET, POLICY_TOTAL));
        actual.put("one level up into a two-level error field", verdict(ITEM_NET, "/Policy/Claims/Amount"));
        actual.put("own-level operand — control", verdict(CLAIM_NET, "/Policy/Claims/Amount"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("form-level operand into a one-level error field", KernelLaws.ADMITTED);
        expected.put("form-level operand into a two-level error field", KernelLaws.ADMITTED);
        expected.put("one level up into a two-level error field", KernelLaws.ADMITTED);
        expected.put("own-level operand — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "an operand the error field's coordinates address is admitted");
    }

    /**
     * The refusing direction, transferred: a level the error field's coordinates cannot address. The
     * form-level control is what stops the two refusals reading as "this rule shape is simply illegal".
     */
    @Test
    void aRepeatableLevelBelowTheErrorFieldIsRefused() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one level BELOW the error field", verdict(CLAIM_NET, ITEM_NET));
        actual.put("repeatable operand into a form-level error field", verdict(POLICY_TOTAL, CLAIM_NET));
        actual.put("form-level operand into a form-level error field — control",
                verdict(POLICY_TOTAL, "/Policy/PolicyExcess"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one level BELOW the error field", NO_WILDCARD);
        expected.put("repeatable operand into a form-level error field", NO_WILDCARD);
        expected.put("form-level operand into a form-level error field — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a level the error field cannot address is refused");
    }

    /**
     * The row the whole file exists for: whether a SIBLING repeatable operand is decided by index-field names
     * on this carrier the way it is on the computation one.
     *
     * <p>Three siblings differing only in their index declaration, so the verdict cannot be attributed to
     * siblinghood. Stated as a literal table rather than derived from "does the sibling declare a same-named
     * index", because that predicate is what an implementation would use and a test sharing it agrees with the
     * code instead of with the kernel.
     */
    @Test
    void aSiblingRepeatableOperandIsDecidedByTheIndexFieldNames() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("sibling whose index field has ANOTHER name", verdict(CLAIM_NET, "/Policy/Payments/PayAmount"));
        actual.put("sibling whose index field has the SAME name",
                verdict(CLAIM_NET, "/Policy/Adjustments/AdjAmount"));
        actual.put("sibling with NO index field", verdict(CLAIM_NET, "/Policy/Notes/NoteAmount"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("sibling whose index field has ANOTHER name", INVALID_INDEX);
        expected.put("sibling whose index field has the SAME name", KernelLaws.ADMITTED);
        expected.put("sibling with NO index field", NO_WILDCARD);
        assertEquals(expected, actual,
                "pinned as measured on the RULE carrier; a moved cell is a carrier divergence, not a refresh");
    }
}
