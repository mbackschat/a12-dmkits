package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — the repetition wildcard's legality across the SEVEN group-condition operators.
 *
 * <p>A12 checks the whole family in one {@code switch} ({@code CheckKontextListenBedingungImpl.check}), and
 * that switch splits it three ways rather than two: the two scalar predicates run {@code checkNoWildCards},
 * three of the five list predicates run {@code checkNoWildCardsInGroup}, and the remaining two run no
 * wildcard gate at all. A checker that generalizes from any one arm gets the other two wrong — either a
 * wrong class on a correct rejection, or an over-rejection of a shape A12 admits. Which arm each operator
 * sits on is a source read, so the grid below is what turns it into a measurement.
 *
 * <p>The discriminating rows are {@code AtLeastOneGroupFilled} and {@code NoGroupFilled}: they are named
 * exactly like the three that refuse, and they take the same operand list, so nothing but this measurement
 * separates "the group quantifiers bar a star" from "these three do".
 *
 * <p><b>The control is a NON-repeatable group, not the same group unstarred.</b> A repeatable group named
 * from a non-repeating rule context has no legal unstarred spelling at all — every operator here answers it
 * with {@code MVK_NO_WILDCARD}, measured below — so the obvious control would have compared a refusal
 * against a refusal.
 *
 * <p>Own domain (an e-commerce order).
 */
class GroupQuantifierWildcardLawsTest {

    private static final String SCALAR = "MVK_NO_WILDCARDS_ALLOWED";
    private static final String LIST = "MVK_NO_WILDCARDS_G_ALLOWED";
    private static final String STAR_REQUIRED = "MVK_NO_WILDCARD";

    private static final String ORDER = DocumentModelDraft.named("order-quantifier").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Lines", l -> l.repeatable(50)
                            .field("Sku", FieldKind.STRING)
                            .field("Quantity", FieldKind.NUMBER)
                            // A repeatable group INSIDE a repeatable one, so a reference can carry two stars
                            // and a field can sit below a starred level that is not its own.
                            .group("Discounts", d -> d.repeatable(5)
                                    .field("Code", FieldKind.STRING)))
                    .group("Shipping", s -> s.field("Method", FieldKind.STRING))
                    .group("Billing", b -> b.field("Account", FieldKind.STRING)))
            // A REPEATABLE second root group, so the root gate and the wildcard gate can be ordered.
            .rootGroup("Broker", b -> b.repeatable(4).field("BrokerName", FieldKind.STRING))
            .build().toJson();

    /**
     * All seven operators over the same starred repeatable group, each beside a non-repeatable control.
     *
     * <p>Without the control an {@code admitted} row would say nothing: an operator refusing every operand
     * would be refusing for a reason that has nothing to do with the star.
     */
    @Test
    void whichGroupConditionOperatorsBarARepetitionWildcard() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("GroupFilled — starred", guarded("GroupFilled(Lines*)"));
        actual.put("GroupFilled — the control", guarded("GroupFilled(Shipping)"));
        actual.put("GroupNotFilled — starred", guarded("GroupNotFilled(Lines*)"));
        actual.put("GroupNotFilled — the control", guarded("GroupNotFilled(Shipping)"));
        actual.put("AllGroupsFilled — starred", guarded("AllGroupsFilled(Lines*, Shipping)"));
        actual.put("AllGroupsFilled — the control", guarded("AllGroupsFilled(Billing, Shipping)"));
        actual.put("GroupsNotCollectivelyFilled — starred",
                guarded("GroupsNotCollectivelyFilled(Lines*, Shipping)"));
        actual.put("GroupsNotCollectivelyFilled — the control",
                guarded("GroupsNotCollectivelyFilled(Billing, Shipping)"));
        actual.put("NotAllGroupsFilled — starred", guarded("NotAllGroupsFilled(Lines*, Shipping)"));
        actual.put("NotAllGroupsFilled — the control", guarded("NotAllGroupsFilled(Billing, Shipping)"));
        actual.put("AtLeastOneGroupFilled — starred", guarded("AtLeastOneGroupFilled(Lines*, Shipping)"));
        actual.put("AtLeastOneGroupFilled — the control",
                guarded("AtLeastOneGroupFilled(Billing, Shipping)"));
        actual.put("NoGroupFilled — starred", guarded("NoGroupFilled(Lines*, Shipping)"));
        actual.put("NoGroupFilled — the control", guarded("NoGroupFilled(Billing, Shipping)"));
        actual.put("NumberOfFilledGroups — starred", guarded("NumberOfFilledGroups(Lines*) > 0"));
        // Two groups, because the count's own arity gate demands a list and answers a lone unstarred group
        // with `MVK_PARAMSIZE_INVALIDGN` before any wildcard question is asked.
        actual.put("NumberOfFilledGroups — the control",
                guarded("NumberOfFilledGroups(Shipping, Billing) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("GroupFilled — starred", SCALAR);
        expected.put("GroupFilled — the control", "admitted");
        expected.put("GroupNotFilled — starred", SCALAR);
        expected.put("GroupNotFilled — the control", "admitted");
        expected.put("AllGroupsFilled — starred", LIST);
        expected.put("AllGroupsFilled — the control", "admitted");
        expected.put("GroupsNotCollectivelyFilled — starred", LIST);
        expected.put("GroupsNotCollectivelyFilled — the control", "admitted");
        expected.put("NotAllGroupsFilled — starred", LIST);
        expected.put("NotAllGroupsFilled — the control", "admitted");
        // The two the switch leaves ungated, and the whole reason this grid exists.
        expected.put("AtLeastOneGroupFilled — starred", "admitted");
        expected.put("AtLeastOneGroupFilled — the control", "admitted");
        expected.put("NoGroupFilled — starred", "admitted");
        expected.put("NoGroupFilled — the control", "admitted");
        expected.put("NumberOfFilledGroups — starred", "admitted");
        expected.put("NumberOfFilledGroups — the control", "admitted");
        assertEquals(expected, actual, "which group-condition operators bar a repetition wildcard");
    }

    /**
     * A repeatable operand named unstarred, which is what makes the three refusing operators unusable on one.
     *
     * <p>{@code MVK_NO_WILDCARD} comes from the reference reader and so runs ahead of every operator gate —
     * it answers even the two operators that have no wildcard gate, and even the arity gate that a
     * single-operand call would otherwise trip. So on a non-repeating rule context a repeatable group is
     * refused starred by three of these operators and unstarred by all seven: there is no spelling that
     * works, which is a stronger statement than the wildcard law alone.
     */
    @Test
    void whatARepeatableOperandDrawsWhenItIsNotStarred() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("AllGroupsFilled — the refusing arm", guarded("AllGroupsFilled(Lines, Shipping)"));
        actual.put("AtLeastOneGroupFilled — the ungated arm",
                guarded("AtLeastOneGroupFilled(Lines, Shipping)"));
        actual.put("GroupFilled — the scalar arm", guarded("GroupFilled(Lines)"));
        actual.put("NumberOfFilledGroups — the count", guarded("NumberOfFilledGroups(Lines) > 0"));
        actual.put("a single unstarred operand, where the arity gate would otherwise answer",
                guarded("AllGroupsFilled(Lines)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("AllGroupsFilled — the refusing arm", STAR_REQUIRED);
        expected.put("AtLeastOneGroupFilled — the ungated arm", STAR_REQUIRED);
        expected.put("GroupFilled — the scalar arm", STAR_REQUIRED);
        expected.put("NumberOfFilledGroups — the count", STAR_REQUIRED);
        expected.put("a single unstarred operand, where the arity gate would otherwise answer",
                STAR_REQUIRED);
        assertEquals(expected, actual, "what a repeatable operand draws when it is not starred");
    }

    /**
     * What the refusing arm counts as a wildcard, and where in the reference it looks.
     *
     * <p>{@code EntityRef.hasWildCardsInGroup} branches on the operand's KIND — a group operand is refused
     * for any star, a field operand only for a star above its own repetition index — so a checker reading
     * that name could plausibly conclude that a field one level down escapes. It does not: the row below
     * measures {@code Lines*&#47;Sku} refused, which is the discriminating case and settles that the family
     * may test the reference rather than the operand's kind.
     *
     * <p>A single STARRED operand is admitted past the arity gate, because {@code isMoreThanOneParameter}
     * reads a star as standing for a list — so the wildcard class, not the arity class, is what a
     * one-operand starred call draws.
     */
    @Test
    void whatTheListArmCountsAsAWildcardInAGroup() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a group operand, one starred level", guarded("AllGroupsFilled(Lines*, Shipping)"));
        actual.put("a group operand, two starred levels",
                guarded("AllGroupsFilled(Lines*/Discounts*, Shipping)"));
        actual.put("a FIELD operand whose only star is its own level",
                guarded("AllGroupsFilled(Lines*/Sku, Shipping)"));
        actual.put("a FIELD operand with a star on an ANCESTOR level too",
                guarded("AllGroupsFilled(Lines*/Discounts*/Code, Shipping)"));
        actual.put("a single starred operand — the arity gate reads the star as a list",
                guarded("AllGroupsFilled(Lines*)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a group operand, one starred level", LIST);
        expected.put("a group operand, two starred levels", LIST);
        expected.put("a FIELD operand whose only star is its own level", LIST);
        expected.put("a FIELD operand with a star on an ANCESTOR level too", LIST);
        expected.put("a single starred operand — the arity gate reads the star as a list", LIST);
        assertEquals(expected, actual, "what the list arm counts as a wildcard in a group");
    }

    /**
     * …and from a rule anchored INSIDE the repeatable group, which the rows above say nothing about.
     *
     * <p>The gate reads only the reference, so the class should not move — but "the gate reads only the
     * reference" is a source claim, and a rule context that already iterates is exactly where an earlier gate
     * could answer first, as {@code MVK_NO_WILDCARD} does on the outer carrier. Measured rather than
     * transferred.
     */
    @Test
    void theSameStarFromARuleInsideTheRepeatableGroup() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a starred nested repeatable in the refusing arm",
                nested("AllGroupsFilled(Discounts*, ../Shipping)"));
        actual.put("…unstarred — the control", nested("AllGroupsFilled(Discounts, ../Shipping)"));
        actual.put("a starred nested repeatable in the scalar arm", nested("GroupFilled(Discounts*)"));
        actual.put("a starred nested repeatable in the ungated arm",
                nested("AtLeastOneGroupFilled(Discounts*, ../Shipping)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a starred nested repeatable in the refusing arm", LIST);
        // The rule iterates `Lines`, so the nested group's own repetition is still a list here and the star
        // is still required — the same pre-emption as on the outer carrier, one level down.
        expected.put("…unstarred — the control", STAR_REQUIRED);
        expected.put("a starred nested repeatable in the scalar arm", SCALAR);
        expected.put("a starred nested repeatable in the ungated arm", "admitted");
        assertEquals(expected, actual, "the same star from a rule inside the repeatable group");
    }

    /**
     * The same operators nested inside a {@code Having} filter, which is a position the grids above miss.
     *
     * <p>The portable checker reads a condition's calls with one reader, so a group quantifier written inside
     * a filter arrives looking exactly like a top-level one. Whether A12 answers it with the filter's own
     * wildcard class, with the quantifier's, or with neither decides whether the family may report there at
     * all — and a wrong class on a correct rejection is the defect this whole grid exists to avoid.
     */
    @Test
    void theSameOperatorsInsideAHavingFilter() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the refusing arm inside a filter",
                guarded("Sum(Lines*/Quantity Having AllGroupsFilled(Lines*, Shipping)) > 0"));
        actual.put("the scalar arm inside a filter",
                guarded("Sum(Lines*/Quantity Having GroupFilled(Lines*)) > 0"));
        actual.put("…the same scalar arm over a non-repeatable group — the control",
                guarded("Sum(Lines*/Quantity Having GroupFilled(Shipping)) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The DISCRIMINATING row is the first one: `MVK_NO_WILDCARDS_G_ALLOWED` is a class no filter gate
        // can produce, so the quantifier's own gate demonstrably runs inside a filter and answers first. The
        // second row alone could not settle that — the filter's own gate draws the scalar class too.
        expected.put("the refusing arm inside a filter", LIST);
        expected.put("the scalar arm inside a filter", SCALAR);
        // A filter that names no starred level is refused outright, so there is no admitted group-predicate
        // filter to use as the usual control; this row records why the discriminator above had to carry it.
        expected.put("…the same scalar arm over a non-repeatable group — the control",
                "MVK_NO_ITERATION_FOR_WILDCARD");
        assertEquals(expected, actual, "the same operators inside a Having filter");
    }

    /**
     * A starred ROOT group, where a second gate competes for the answer.
     *
     * <p>The refusing list arm runs {@code checkNotRootGroupReferenced} BEFORE its wildcard gate, so a root
     * operand should never reach the wildcard class there — while the scalar arm has no root gate at all in
     * an A12 model and should answer with the wildcard class. Both halves are measured, because a family
     * that reported the wildcard class on a root operand would be naming a class A12 never draws on a model
     * it does reject.
     */
    @Test
    void aStarredRootGroupInEachArm() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the refusing arm, starred root", guarded("AllGroupsFilled(/Broker*, Shipping)"));
        actual.put("the refusing arm, unstarred root — the control",
                guarded("AllGroupsFilled(/Broker, Shipping)"));
        actual.put("the scalar arm, starred root", guarded("GroupFilled(/Broker*)"));
        actual.put("the scalar arm, unstarred root — the control", guarded("GroupFilled(/Broker)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the refusing arm, starred root", "MVK_ROOT_GROUP_REFERENCED");
        // The control lands on a THIRD gate: unstarred, the repeatable root is answered by the reference
        // reader's star-required class, which outruns the root gate as it outruns every operator gate.
        expected.put("the refusing arm, unstarred root — the control", STAR_REQUIRED);
        expected.put("the scalar arm, starred root", SCALAR);
        expected.put("the scalar arm, unstarred root — the control", STAR_REQUIRED);
        assertEquals(expected, actual, "a starred root group in each arm");
    }

    /** The model-level anchoring: the error field sits outside every repeatable group. */
    private static String guarded(String condition) {
        return verdict("/Order", "../Reference", "FieldFilled(Reference) And " + condition);
    }

    /** The same law from a rule that already iterates, its error field on the iterated row. */
    private static String nested(String condition) {
        return verdict("/Order/Lines", "../Sku", "FieldFilled(Sku) And " + condition);
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(ORDER, group, errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList());
    }
}
