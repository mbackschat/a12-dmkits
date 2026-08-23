package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;

/**
 * KERNEL-FINDINGS §11 — a computation operand is admitted by whether the TARGET's repetition scope
 * <b>binds</b> every repeatable level the operand crosses, not by scope EQUALITY (SPEC-2026-08-23-06).
 *
 * <p>The distinction is not academic: a checker built on scope equality rejects every computation that reads
 * a form-level constant into a repeatable row, which is one of the commonest shapes there is. Binding is
 * directional — an operand at or above the target's scope is admitted, and one the target's coordinates
 * cannot address (a SIBLING repeatable group, or a level BELOW the target) is refused {@code MVK_NO_WILDCARD}.
 * For the below-the-target direction the starred aggregate is the separate, admitted flattening route, and
 * {@link #aStarredAggregateIsTheAdmittedRouteToADeeperGroup} is what stops the refusal being read as a
 * blanket ban on deeper operands.
 *
 * <p><b>Measured across five operand SHAPES, so the claim is about scope and not about operand kind.</b> A
 * bare field reference, {@code Length}, {@code MonthFromDate}, {@code Abs}, and a mixed outer-plus-row
 * arithmetic pair all behave the same way at the same scopes. Without that spread, the admitted rows are
 * equally well explained by "a constant-like operand is exempt".
 *
 * <p>Own domain (motor insurance claims).
 */
class ComputationOperandScopeLawsTest {

    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";

    /**
     * One model carrying every scope relation the law names: a root scope, a repeatable {@code Claims} group
     * with its own computed leaf, a repeatable {@code Items} group NESTED in it with a second computed leaf,
     * and a repeatable {@code Payments} group that is a SIBLING of {@code Claims}.
     */
    private static final String CLAIMS = DocumentModelDraft.named("motor-claims").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("PolicyRef", s -> { })
                    .dateField("PolicyStart", d -> d.format("yyyy-MM-dd"))
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
                    // Carries the same key FIELD as its siblings and differs from them in exactly one thing:
                    // it does not DECLARE it as the group's index. Without that field the row would vary two
                    // things at once and could not sit in the same table as the two index-declaring siblings.
                    .group("Notes", nt -> nt.repeatable(20)
                            .field("ClaimKey", FieldKind.STRING)
                            .numberField("NoteAmount", n -> n.fractionDigits(0))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(CLAIMS);

    private static final String CLAIM_NET = "/Policy/Claims/Net";
    private static final String ITEM_NET = "/Policy/Claims/Items/ItemNet";
    private static final String POLICY_TOTAL = "/Policy/PolicyTotal";

    private static String verdict(String target, Calculation operation) {
        Computation computation = M.computation(M.num(target))
                .name("ScopeProbe")
                .calc(operation)
                .message(Dsl.en("Operand scope probe."))
                .build();
        return KernelLaws.computationVerdict(CLAIMS, computation);
    }

    private static String verdict(String target, NumberExpr operation) {
        return verdict(target, operation.expression());
    }

    private static NumberExpr num(String path) {
        return M.num(path).asNumber();
    }

    /** An operand at or above the target's scope is bound by the target's coordinates, so it is admitted. */
    @Test
    void anOperandTheTargetsScopeBindsIsAdmitted() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("form-level operand into a one-level target", verdict(CLAIM_NET, num("/Policy/PolicyExcess")));
        actual.put("form-level operand into a two-level target", verdict(ITEM_NET, num("/Policy/PolicyExcess")));
        actual.put("one level up into a two-level target", verdict(ITEM_NET, num("/Policy/Claims/Amount")));
        actual.put("own-level operand — control", verdict(CLAIM_NET, num("/Policy/Claims/Amount")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("form-level operand into a one-level target", KernelLaws.ADMITTED);
        expected.put("form-level operand into a two-level target", KernelLaws.ADMITTED);
        expected.put("one level up into a two-level target", KernelLaws.ADMITTED);
        expected.put("own-level operand — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the target's scope binds every level an at-or-above operand crosses");
    }

    /** A repeatable level BELOW the target's scope is refused, whichever side of the target the target is on. */
    @Test
    void aRepeatableLevelBelowTheTargetIsRefused() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one level BELOW the target", verdict(CLAIM_NET, num("/Policy/Claims/Items/ItemAmount")));
        actual.put("repeatable operand into a form-level target", verdict(POLICY_TOTAL, num("/Policy/Claims/Amount")));
        actual.put("form-level operand into a form-level target — control",
                verdict(POLICY_TOTAL, num("/Policy/PolicyExcess")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one level BELOW the target", NO_WILDCARD);
        expected.put("repeatable operand into a form-level target", NO_WILDCARD);
        expected.put("form-level operand into a form-level target — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a repeatable level the target's coordinates cannot reach is refused");
    }

    /**
     * A SIBLING repeatable operand is <b>not one verdict</b>: it is the kernel's <b>parallel-iteration</b>
     * route, and which of three answers it gives is decided by the sibling group's INDEX-FIELD DECLARATION,
     * with the target and the operand shape held fixed across all three rows.
     *
     * <p>This is why the sibling case must not be stated as a flat refusal beside the below-the-target one.
     * They are different mechanisms that happen to share a diagnostic on the index-free reading — and a
     * fixture whose groups carry differently-named index fields measures the index gate while looking exactly
     * like a measurement of siblinghood ([KF186] is the same trap on the `RepetitionNotUnique` carrier).
     */
    @Test
    void aSiblingRepeatableOperandIsDecidedByTheIndexFieldNames() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("sibling whose index field has ANOTHER name",
                verdict(CLAIM_NET, num("/Policy/Payments/PayAmount")));
        actual.put("sibling whose index field has the SAME name",
                verdict(CLAIM_NET, num("/Policy/Adjustments/AdjAmount")));
        actual.put("sibling with NO index field", verdict(CLAIM_NET, num("/Policy/Notes/NoteAmount")));
        actual.put("starred aggregate over a sibling — the flattening route",
                verdict(CLAIM_NET, Dsl.sum(M.flatten("/Policy/Payments*/PayAmount"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("sibling whose index field has ANOTHER name", "MVK_ERROR_FIELD_INVALID_INDEX");
        expected.put("sibling whose index field has the SAME name", KernelLaws.ADMITTED);
        expected.put("sibling with NO index field", NO_WILDCARD);
        expected.put("starred aggregate over a sibling — the flattening route", KernelLaws.ADMITTED);
        assertEquals(expected, actual,
                "the sibling verdict is decided by the index-field names, not by siblinghood alone");
    }

    /** The route that exists instead: flatten the deeper group with a star and aggregate it. */
    @Test
    void aStarredAggregateIsTheAdmittedRouteToADeeperGroup() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("starred aggregate over the deeper group",
                verdict(CLAIM_NET, Dsl.sum(M.flatten("/Policy/Claims/Items*/ItemAmount"))));
        actual.put("the same deeper field unflattened — control",
                verdict(CLAIM_NET, num("/Policy/Claims/Items/ItemAmount")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("starred aggregate over the deeper group", KernelLaws.ADMITTED);
        expected.put("the same deeper field unflattened — control", NO_WILDCARD);
        assertEquals(expected, actual, "the refusal is about the unflattened crossing, not about depth");
    }

    /**
     * The same scope verdicts under five operand shapes. If any admitted row here depended on the operand
     * being a bare constant-like read, this table would separate it.
     */
    @Test
    void theVerdictIsTheSameAcrossOperandShapes() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Length of a form-level string", verdict(CLAIM_NET, Dsl.length(M.str("/Policy/PolicyRef"))));
        actual.put("MonthFromDate of a form-level date",
                verdict(CLAIM_NET, Dsl.monthFromDate(M.date("/Policy/PolicyStart"))));
        actual.put("Abs of a form-level number", verdict(CLAIM_NET, Dsl.abs(num("/Policy/PolicyExcess"))));
        actual.put("outer plus row arithmetic",
                verdict(CLAIM_NET, num("/Policy/PolicyExcess").plus(num("/Policy/Claims/Amount"))));
        actual.put("outer plus index-free SIBLING-group arithmetic",
                verdict(CLAIM_NET, num("/Policy/PolicyExcess").plus(num("/Policy/Notes/NoteAmount"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Length of a form-level string", KernelLaws.ADMITTED);
        expected.put("MonthFromDate of a form-level date", KernelLaws.ADMITTED);
        expected.put("Abs of a form-level number", KernelLaws.ADMITTED);
        expected.put("outer plus row arithmetic", KernelLaws.ADMITTED);
        expected.put("outer plus index-free SIBLING-group arithmetic", NO_WILDCARD);
        assertEquals(expected, actual, "the gate reads each operand's scope, whatever operation wraps it");
    }
}
