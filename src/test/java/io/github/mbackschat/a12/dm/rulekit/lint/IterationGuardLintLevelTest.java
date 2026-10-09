package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexFieldValue;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * {@code RK_NEG_CONDITION_IN_ITERATION} counts a positive guard only when it references the negative's OWN
 * repeatable level, and not when it is a direct {@code Or} sibling — the two shapes measured in
 * {@code validate.laws.IterationGuardLevelLawsTest} and {@code …IterationGuardCompositionLawsTest}, where the
 * kernel refuses each.
 *
 * <p>Both were silent before: the flag was set by any positive presence anywhere in the condition.
 *
 * <p>The nested-{@code Or} composition is measured too since 2026-09-07, and it goes the same way: an
 * {@code Or} lends only the levels every arm guards, at any depth (corrected 2026-10-09 from "lends nothing";
 * the all-guarding row is {@code NonIteratingGuardLintTest}'s). What keeps that from becoming "warn whenever an
 * Or appears" is the conjunctive-guard-beside-an-Or control.
 *
 * <p><b>Every row lints against one error field</b>, and that is the only legal choice rather than an
 * unvaried axis: an error field outside the rule group is refused once the condition iterates, under a code
 * that pre-empts the one this lint mirrors ({@code validate.laws.IterationGuardErrorFieldLevelLawsTest}). So
 * the rows below hold the advisory's input fixed at the shape a kernel-legal model can actually have.
 */
class IterationGuardLintLevelTest {

    private static final BoundModel M = BoundModel.ofJson(
            DocumentModelDraft.named("lint-iteration-levels").locale("en_US")
                    .rootGroup("Order", o -> o
                            .field("Reference", FieldKind.STRING)
                            .group("Items", i -> i.repeatable(20)
                                    .field("Sku", FieldKind.STRING)
                                    .field("Count", FieldKind.NUMBER))
                            .group("Notes", n -> n.repeatable(20)
                                    .field("Text", FieldKind.STRING)))
                    .build().toJson());

    private static List<Diagnostic> lint(Condition condition) {
        return new Linter().lint(M, "/Order/Items/Sku", condition);
    }

    private static boolean warns(Condition condition) {
        return lint(condition).stream()
                .anyMatch(d -> d.code().map("RK_NEG_CONDITION_IN_ITERATION"::equals).orElse(false));
    }

    /** Unchanged: a same-level guard suppresses, and an unguarded negative warns. */
    @Test
    void theEstablishedShapesAreUnchanged() {
        assertTrue(warns(Dsl.fieldNotFilled(M.field("/Order/Items/Sku"))), "unguarded");
        assertEquals(false, warns(Dsl.fieldFilled(M.field("/Order/Items/Count"))
                .and(Dsl.fieldNotFilled(M.field("/Order/Items/Sku")))), "same-level guard");
    }

    /**
     * An UNSTARRED read into another repeatable group DOES suppress, and this row asserted the opposite until
     * 2026-09-07. The level law it mirrored measured a STARRED quantifier, and the kernel admits the unstarred
     * form ({@code validate.laws.IterationGuardAdmissionRoutesLawsTest}) — so warning here was a false
     * positive on a legal model, the one direction an advisory cannot afford. An independent differential over
     * real models found it; this lock had pinned it as correct.
     */
    @Test
    void anUnstarredGuardOnAnotherLevelSuppresses() {
        assertEquals(false, warns(Dsl.fieldFilled(M.field("/Order/Notes/Text"))
                .and(Dsl.fieldNotFilled(M.field("/Order/Items/Sku")))));
    }

    /**
     * FALSE POSITIVE (cold review F3): a negative on a path with NO repeatable ancestor, guarded at the
     * iterating level. The kernel admits it — under {@code And} one admitted iterating child admits the whole
     * condition — and the pre-widening lint suppressed it. The {@code ANY_LEVEL} sentinel carries two
     * meanings ("no repeatable ancestor" and "guard level unresolvable"), and the negative side reads the
     * first through a test written for the second.
     */
    @Test
    void aNegativeOnANonRepeatingPathDoesNotWarn() {
        assertEquals(false, warns(Dsl.fieldFilled(M.field("/Order/Items/Count"))
                .and(Dsl.fieldNotFilled(M.field("/Order/Reference")))));
    }

    /**
     * FALSE POSITIVE (cold review F4): a group negative gets its level from {@code levelOf} (strict ancestor)
     * while the mirrored group positive gets it from {@code groupLevelOf} (the group itself when repeating),
     * so {@code GroupNotFilled(X)} and {@code GroupFilled(X)} disagree about X's level.
     */
    @Test
    void aGuardedGroupNegativeDoesNotWarn() {
        assertEquals(false, warns(Dsl.fieldFilled(M.field("/Order/Items/Count"))
                .and(Dsl.groupNotFilled(M.group("/Order/Items")))));
    }

    /**
     * An INDEX-SELECTED negative must not warn: it pins a row rather than iterating, and the kernel admits it
     * ({@code validate.laws.IndexedOperandIterationLawsTest}). Flagging it is a false positive on a legal
     * model — the one direction an advisory cannot afford.
     */
    @Test
    void anIndexSelectedNegativeDoesNotWarn() {
        Condition indexed = Dsl.fieldNotFilled(
                new io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexValue(M.field("/Order/Items/Sku"), "K1"));
        assertEquals(false, warns(indexed));
    }

    /**
     * NEW: a positive inside a NESTED {@code Or} no longer suppresses either. The kernel refuses
     * {@code (same-level positive Or anything) And negative} exactly as it refuses the unguarded form
     * ({@code validate.laws.IterationGuardNestedCompositionLawsTest}), so suppressing here was a false
     * negative — an {@code Or} with an arm that guards nothing lends nothing to a negative conjoined with it.
     */
    @Test
    void aGuardInsideANestedOrNoLongerSuppresses() {
        assertTrue(warns(Dsl.fieldFilled(M.field("/Order/Items/Count"))
                .or(Dsl.fieldFilled(M.field("/Order/Reference")))
                .and(Dsl.fieldNotFilled(M.field("/Order/Items/Sku")))));
        assertTrue(warns(Dsl.fieldFilled(M.field("/Order/Reference"))
                .or(Dsl.fieldFilled(M.field("/Order/Items/Count")))
                .and(Dsl.fieldNotFilled(M.field("/Order/Items/Sku")))));
    }

    /**
     * THE FALSE-POSITIVE CONTROL for that widening, and the direction an advisory cannot afford: a same-level
     * positive still reached through {@code And} keeps suppressing, however much {@code Or} sits beside it.
     * Without this row the widening above is indistinguishable from "warn whenever an Or appears".
     */
    @Test
    void aConjunctiveGuardStillSuppressesBesideAnOr() {
        assertEquals(false, warns(Dsl.fieldFilled(M.field("/Order/Items/Count"))
                .and(Dsl.fieldFilled(M.field("/Order/Reference"))
                        .or(Dsl.fieldFilled(M.field("/Order/Notes/Text"))))
                .and(Dsl.fieldNotFilled(M.field("/Order/Items/Sku")))));
    }

    /**
     * A GROUP negative guarded by a GROUP positive over the SAME group. The kernel admits it
     * ({@code validate.laws.IterationGuardOwnGroupLawsTest}), and it is the shape an independent differential
     * reported the advisory warning on — the two sides read their level through different helpers, so they can
     * disagree about the very group they both name.
     */
    @Test
    void aGroupNegativeGuardedByTheSameGroupDoesNotWarn() {
        assertEquals(false, warns(Dsl.groupNotFilled(M.group("/Order/Items"))
                .and(Dsl.groupFilled(M.group("/Order/Items")))));
    }

    /**
     * NEW: a FIELD-KEYED index selection is not a pinned row. The key is read at its own repeatable level, so
     * the selection iterates and the kernel refuses an unguarded negative over it
     * ({@code validate.laws.IndexedNegativeKeyFormLawsTest}) — the suppression had to turn on the key's FORM
     * rather than on the selection.
     */
    @Test
    void aFieldKeyedIndexedNegativeWarns() {
        assertTrue(warns(Dsl.fieldNotFilled(new SemanticIndexFieldValue(
                M.field("/Order/Items/Sku"), M.field("/Order/Notes/Text")))));
    }

    /**
     * THE FALSE-POSITIVE CONTROL for that: the same field-keyed negative guarded at the KEY's level. It is the
     * key that iterates, so a guard there is what bounds it — and without this row the widening above is
     * indistinguishable from "warn on any indexed negative".
     */
    @Test
    void aFieldKeyedIndexedNegativeGuardedAtTheKeysLevelDoesNotWarn() {
        assertEquals(false, warns(Dsl.fieldFilled(M.field("/Order/Notes/Text"))
                .and(Dsl.fieldNotFilled(new SemanticIndexFieldValue(
                        M.field("/Order/Items/Sku"), M.field("/Order/Notes/Text"))))));
    }

    /**
     * Admission for a bare negative over a repeatable group needs BOTH an index declaration and a maximum of
     * two ({@code validate.laws.IterationGuardAdmissionRoutesLawsTest}), so the advisory suppresses on the
     * conjunction. Each cell below moves one of the two axes, which is what a count-only or index-only
     * reading could not see — both shipped and both cost agreeing refusals.
     */
    @Test
    void aBareNegativeIsSuppressedOnlyWithAnIndexAndAMaximumOfTwo() {
        var verdicts = new java.util.LinkedHashMap<String, Boolean>();
        for (int repeats : new int[] {2, 3}) {
            for (boolean indexed : new boolean[] {true, false}) {
                BoundModel m = BoundModel.ofJson(
                        DocumentModelDraft.named("lint-" + repeats + "-" + indexed).locale("en_US")
                                .rootGroup("Order", o -> o.group("Pair", p -> {
                                    p.repeatable(repeats).field("Key", FieldKind.STRING)
                                            .field("Sku", FieldKind.STRING);
                                    if (indexed) {
                                        p.indexField("Key");
                                    }
                                }))
                                .build().toJson());
                verdicts.put("R(" + repeats + ")" + (indexed ? " idx" : " no-idx"),
                        new Linter().lint(m, "/Order/Pair/Sku", Dsl.groupNotFilled(m.group("/Order/Pair")))
                                .stream()
                                .anyMatch(d -> d.code()
                                        .map("RK_NEG_CONDITION_IN_ITERATION"::equals).orElse(false)));
            }
        }

        assertEquals(java.util.Map.of(
                "R(2) idx", false,
                "R(2) no-idx", true,
                "R(3) idx", true,
                "R(3) no-idx", true), verdicts);
    }

    /** NEW: a direct Or sibling no longer suppresses — the kernel refuses this too. */
    @Test
    void aDirectOrSiblingNoLongerSuppresses() {
        assertTrue(warns(Dsl.fieldFilled(M.field("/Order/Items/Count"))
                .or(Dsl.fieldNotFilled(M.field("/Order/Items/Sku")))));
    }
}
