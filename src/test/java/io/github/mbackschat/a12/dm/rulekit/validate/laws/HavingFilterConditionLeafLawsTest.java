package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §4/§11 — can a GROUP-PRESENCE leaf appear inside the CONDITION of a {@code Having} filter that
 * is attached to a FIELD-list operand?
 *
 * <p><b>Why this file exists, and why it is a static check rather than a document run.</b> A runtime
 * discriminator was proposed for a12-kernel-lean's {@code SPEC-2026-09-02-03}: an <em>unknown</em> filter
 * condition should make the aggregate unavailable while a merely <em>false</em> one excludes the row, so the two
 * accounts predict different observables on one document. a12-kernel-lean then pointed out that the shape may
 * not be authorable at all, which would close the entry more strongly than any runtime measurement — as a fact
 * about A12 rather than about the reach of two instruments. Establishing admission first is therefore the cheap
 * step, and the accept/reject oracle is terminal.
 *
 * <p><b>This shape sits between two of their measured results and is neither of them.</b> Their {@code spec/07}
 * records a {@code Having} <em>site</em> refusing a group PATH outright ({@code MVK_NO_GROUPS_ALLOWED}, at static
 * arbitration, either operand position, both arms) — reproduced in this repository as
 * {@link GroupOperandFilterLawsTest}. It also records an adjacent, explicitly unmeasured refusal for a filter
 * naming a <em>different group's field</em>. Here the filter attaches to a field list, which is the shape a
 * filter belongs to, and the group path appears one level further in — inside the filter's condition, as the
 * operand of a group-presence operator. Neither of their rows is carried across.
 *
 * <p><b>The denominator, stated because a12-kernel-lean asked for it explicitly and because these are two
 * admissions rather than one.</b> The operand class carrying the filter is a <b>field list</b> — the flatten of
 * {@code Lines*} then {@code Sku} — on {@code NumberOfFilledFields}. The leaf is measured <b>both</b> as the
 * whole filter condition and as one conjunct of it, since admitting a shape as a sole condition says nothing
 * about admitting it beside a field predicate, and the reverse.
 *
 * <p><b>The gate that answers it is NOT new here, and this file does not claim it.</b> "A filter must reference
 * the iterated level" has been locked in this repository since 2026-07-25 as
 * {@link AggregateSlotLawsTest#whichFilterGateReports} (a filter body consisting only of
 * a {@code CustomCondition}), and it is recorded in {@code docs/MVK-LEDGER.md} as this code's second measured
 * trigger, and in KERNEL-FINDINGS §9 as the rule that a filter must reference a <em>field</em> in a starred
 * level. The rows below are scoped to what they add on top of it.
 *
 * <p><b>But a grep of those findings would NOT have answered the question, and this is the more useful half of
 * the lesson</b> (a12-kernel-lean's reading, 2026-09-03, and it corrects an over-correction that stood in this
 * comment for one commit). §9 said <em>field</em>. So searching our own record first would have returned a
 * confident, narrower answer and stopped the search — the leaf's admission would have read as covered when it
 * was not, and the word "field" would still be wrong. A record that answers is not the same as a record that
 * answers the question asked, and a narrow prior-art hit is more dangerous than no hit precisely because it
 * terminates the enquiry. Same shape as an empty log read as a measured absence.
 *
 * <p><b>What is new: the leaf clears that gate, so the discriminator is authorable.</b> A group-presence leaf is
 * admitted inside a field-list filter whenever the condition also carries an in-scope reference — as the SOLE
 * condition it meets the pre-existing gate and draws {@code MVK_NO_ITERATION_FOR_WILDCARD}, not the
 * {@code MVK_NO_GROUPS_ALLOWED} of the filter-SITE rule in {@link GroupOperandFilterLawsTest}. So the runtime
 * observation {@code SPEC-2026-09-02-03} wants can be authored, provided its filter binds the iteration.
 *
 * <p><b>Also new: a12-kernel-lean's adjacent refusal is an INSTANCE of that gate, not a rule of its own.</b>
 * Their {@code spec/07} records a filter naming a different group's field as having "a refusal of its own",
 * explicitly unmeasured. Measured here it is the same {@code MVK_NO_ITERATION_FOR_WILDCARD}, on a plain field
 * predicate with no group operator anywhere — so there is one gate to state, not two.
 *
 * <p><b>Four rows kill the rival readings.</b> <b>Two</b> out-of-scope conjuncts are still refused, so it is an
 * in-scope reference that admits the shape and not merely a second conjunct. The list's <b>other own field</b>
 * alone is admitted, so the requirement is the scope and not one privileged field. Reversing the conjunct order
 * changes nothing. And {@code GroupFilled} over the list's <b>OWN</b> group is admitted <em>as the sole
 * condition</em> — which is what shows a group operand binds the iteration as readily as a field one, so the
 * out-of-scope rows above are refused for their scope and not for being groups.
 *
 * <p><b>And the {@code $} marker changes nothing on this carrier, which is the second question and closes a
 * residue both estates carried as open</b> (a12-kernel-lean's {@code SPEC-2026-09-04-05}; their disposition on
 * {@code SPEC-2026-09-03-04} records it as "unmeasured on both sides"). A marked out-of-scope group leaf is
 * refused as the sole condition with the <em>same</em> code as the unmarked one, and admitted beside the same
 * in-scope conjunct. Measuring <b>both spellings at each position</b> is what isolates the marker: the refusal
 * alone would not have distinguished "the marker is rejected here" from "the missing in-scope reference is the
 * cause", and reading it the first way would wrongly refuse the admitted conjunct forms. So a group presence
 * leaf reads the captured environment as a field reference does, and on a bound-but-not-reopened level the
 * marker is redundant there too.
 *
 * <p><b>Our own answer to their "which do you do?" question.</b> This repository neither erases the marker
 * before a scope check nor inspects it: admission is delegated whole to the kernel's {@code checkConsistency},
 * and {@code ConditionReferences}/{@code Linter} treat a marked reference exactly as an unmarked one for every
 * carrier. The marker is preserved through render and through read-back
 * ({@code adapter.CorrelatedGroupPresenceReadTest}) rather than normalized away.
 *
 * <p><b>What stays a hypothesis.</b> "Binds the wildcard" is the code's own naming plus this pattern; the
 * observable claim is at least one reference into the filtered list's own iterated group. A filter referencing a
 * second, unrelated repeatable group would separate "binds THIS wildcard" from "iterates something", and is not
 * measured here.
 *
 * <p><b>Every condition is rendered from the typed builder</b> rather than hand-written, so each row measures two
 * things at once: whether this project can author the shape, and whether the kernel admits it. A shape our
 * builder refused but the kernel accepted would be an over-strict client-side refusal, which is a defect on
 * sight here.
 */
class HavingFilterConditionLeafLawsTest {

    private static final String GROUP = "/Order";
    private static final String ADMITTED = "admitted";
    private static final String NO_ITERATION = "MVK_NO_ITERATION_FOR_WILDCARD";

    /**
     * One repeatable group to carry the field list, and two non-repeatable address groups so a leaf can name a
     * group that is neither the filter's own nor the rule's. {@code Marker} is the error field and {@code Total}
     * the computation target, both outside the repeatable group so the arms' own gates cannot fire and mask the
     * filter answer.
     */
    private static final String MODEL = DocumentModelDraft.named("order-filter-leaf").locale("en_US")
            .rootGroup("Order", root -> root
                    .stringField("Marker", s -> { })
                    .field("Total", FieldKind.NUMBER)
                    .group("Lines", lines -> lines.repeatable(50)
                            .stringField("Sku", s -> { })
                            .field("Qty", FieldKind.NUMBER))
                    .group("Billing", billing -> billing
                            .stringField("Street", s -> { })
                            .stringField("City", s -> { }))
                    .group("Shipping", shipping -> shipping
                            .stringField("Street", s -> { })
                            .stringField("City", s -> { })))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /** The field-local filter predicate that every control rests on: admitted, and the harness's proof of life. */
    private static Condition fieldLocal() {
        return M.num("/Order/Lines/Qty").gt(1);
    }

    @Test
    void aGroupPresenceLeafInsideAFieldListFilterCondition() {
        Map<String, String> actual = new LinkedHashMap<>();

        actual.put("field-local filter only — well-formedness control", verdict(fieldLocal()));
        actual.put("GroupFilled(other group) as the WHOLE filter condition",
                verdict(Dsl.groupFilled(M.group("/Order/Billing"))));
        actual.put("GroupFilled(other group) as ONE CONJUNCT",
                verdict(fieldLocal().and(Dsl.groupFilled(M.group("/Order/Billing")))));
        actual.put("GroupNotFilled(other group) as the WHOLE filter condition",
                verdict(Dsl.groupNotFilled(M.group("/Order/Billing"))));
        actual.put("GroupNotFilled(other group) as ONE CONJUNCT",
                verdict(fieldLocal().and(Dsl.groupNotFilled(M.group("/Order/Billing")))));
        actual.put("a FIELD predicate naming a different group's field — their adjacent unmeasured row",
                verdict(M.str("/Order/Billing/City").eq("Aachen")));
        // The rows that separate "a group path is refused here" from "the filter must bind the wildcard".
        actual.put("TWO out-of-scope conjuncts — no in-scope reference anywhere in the filter",
                verdict(M.str("/Order/Billing/City").eq("Aachen")
                        .and(Dsl.groupFilled(M.group("/Order/Shipping")))));
        actual.put("sole condition on the list's OTHER own field — in scope, different field",
                verdict(Dsl.fieldFilled(M.field("/Order/Lines/Sku"))));
        actual.put("conjunct order reversed — the group leaf first",
                verdict(Dsl.groupFilled(M.group("/Order/Billing")).and(fieldLocal())));
        // Every group above is OUT of scope, which leaves open whether a group operand can bind the wildcard at
        // all. Naming the filtered list's OWN group is the row that separates "only a field reference binds"
        // from "any in-scope reference binds".
        actual.put("GroupFilled(the list's OWN group) as the WHOLE filter condition",
                verdict(Dsl.groupFilled(M.group("/Order/Lines"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("field-local filter only — well-formedness control", ADMITTED);
        expected.put("GroupFilled(other group) as the WHOLE filter condition", NO_ITERATION);
        expected.put("GroupFilled(other group) as ONE CONJUNCT", ADMITTED);
        expected.put("GroupNotFilled(other group) as the WHOLE filter condition", NO_ITERATION);
        expected.put("GroupNotFilled(other group) as ONE CONJUNCT", ADMITTED);
        expected.put("a FIELD predicate naming a different group's field — their adjacent unmeasured row",
                NO_ITERATION);
        expected.put("TWO out-of-scope conjuncts — no in-scope reference anywhere in the filter", NO_ITERATION);
        expected.put("sole condition on the list's OTHER own field — in scope, different field", ADMITTED);
        expected.put("conjunct order reversed — the group leaf first", ADMITTED);
        expected.put("GroupFilled(the list's OWN group) as the WHOLE filter condition", ADMITTED);
        assertEquals(expected, actual,
                "a group-presence leaf is admitted inside a field-list filter; what the filter must carry is a "
                        + "reference that binds the iterated wildcard");
    }

    @Test
    void theDollarMarkerOnTheGroupLeafIsAdmittedAndChangesNoVerdict() {
        Map<String, String> actual = new LinkedHashMap<>();
        // tag::correlated-group-presence[]
        // A $-marked group-presence leaf reads the group in the outer environment the Having captured.
        // It is admitted as long as the filter ALSO carries a reference into the filtered list's own
        // iterated group — here fieldLocal(), the predicate on /Order/Lines/Qty — which binds the wildcard.
        Condition markedPresence = Dsl.groupFilled(M.group("/Order/Billing"), true);
        Condition boundFilter = fieldLocal().and(markedPresence);
        // end::correlated-group-presence[]

        // Both spellings at BOTH positions: one spelling alone cannot separate the marker from the scope.
        actual.put("MARKED GroupFilled as the WHOLE filter condition", verdict(markedPresence));
        actual.put("UNMARKED GroupFilled as the WHOLE filter condition",
                verdict(Dsl.groupFilled(M.group("/Order/Billing"))));
        actual.put("MARKED GroupFilled beside the in-scope conjunct", verdict(boundFilter));
        actual.put("UNMARKED GroupFilled beside the in-scope conjunct",
                verdict(fieldLocal().and(Dsl.groupFilled(M.group("/Order/Billing")))));
        actual.put("the in-scope conjunct alone — live control", verdict(fieldLocal()));
        // The polarity sibling, so the answer is about the group-presence CARRIER and not about GroupFilled.
        actual.put("MARKED GroupNotFilled as the WHOLE filter condition",
                verdict(Dsl.groupNotFilled(M.group("/Order/Billing"), true)));
        // …and in the ADMITTED position too. KF242 says the polarity sibling "behaves identically", which was
        // measured in one of the two positions until a cold review pointed it out (2026-09-04): admitting a
        // shape as a sole condition says nothing about beside a conjunct, and this file's whole point is that
        // the two positions differ.
        actual.put("MARKED GroupNotFilled beside the in-scope conjunct",
                verdict(fieldLocal().and(Dsl.groupNotFilled(M.group("/Order/Billing"), true))));
        actual.put("UNMARKED GroupNotFilled beside the in-scope conjunct",
                verdict(fieldLocal().and(Dsl.groupNotFilled(M.group("/Order/Billing")))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MARKED GroupFilled as the WHOLE filter condition", NO_ITERATION);
        expected.put("UNMARKED GroupFilled as the WHOLE filter condition", NO_ITERATION);
        expected.put("MARKED GroupFilled beside the in-scope conjunct", ADMITTED);
        expected.put("UNMARKED GroupFilled beside the in-scope conjunct", ADMITTED);
        expected.put("the in-scope conjunct alone — live control", ADMITTED);
        expected.put("MARKED GroupNotFilled as the WHOLE filter condition", NO_ITERATION);
        expected.put("MARKED GroupNotFilled beside the in-scope conjunct", ADMITTED);
        expected.put("UNMARKED GroupNotFilled beside the in-scope conjunct", ADMITTED);
        assertEquals(expected, actual,
                "the marker is admitted on a group presence leaf and moves no verdict — the missing in-scope "
                        + "reference is what the sole-condition rows are refused for");
    }

    /** The marker must reach the kernel, so the rows above are about {@code $} rather than about a dropped one. */
    @Test
    void theRenderedFilterActuallyCarriesTheMarker() {
        assertEquals("NumberOfFilledFields(/Order/Lines*/Sku Having GroupFilled($/Order/Billing)) > 0",
                KernelLaws.render(Dsl.numberOfFilledFields(M.flatten("/Order/Lines*/Sku")
                        .having(Dsl.groupFilled(M.group("/Order/Billing"), true))).gt(0)),
                "a marked group operand renders its $ — without this the rows above would measure the "
                        + "unmarked spelling twice and agree vacuously");
    }

    @Test
    void theCOMPUTATIONArmOfTheSameShape() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("field-local filter only — control", computationVerdict(fieldLocal()));
        actual.put("GroupFilled(other group) as the WHOLE filter condition",
                computationVerdict(Dsl.groupFilled(M.group("/Order/Billing"))));
        actual.put("GroupFilled(other group) as ONE CONJUNCT",
                computationVerdict(fieldLocal().and(Dsl.groupFilled(M.group("/Order/Billing")))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("field-local filter only — control", ADMITTED);
        expected.put("GroupFilled(other group) as the WHOLE filter condition", NO_ITERATION);
        expected.put("GroupFilled(other group) as ONE CONJUNCT", ADMITTED);
        assertEquals(expected, actual, "the computation arm agrees with validation on all three");
    }

    /**
     * The same two positions for a group-operand LIST, which is the carrier {@code DG49} is about.
     *
     * <p><b>Why it is spelled by hand.</b> rulekit's {@code GroupQuantifierCondition} holds a bare
     * {@code List<GroupRef>} with no per-operand marker, so {@code AllGroupsFilled($A, $B)} is not authorable
     * through the typed surface at all — the same type-level erasure API-FINDINGS 135 closed for the scalar
     * pair, one carrier over. Until that record grows the component, a raw condition is the only way to ask the
     * kernel, exactly as {@code ShortNameTierLawsTest} does for a path form the renderer never emits.
     *
     * <p><b>Why it needs to exist.</b> This admission is the measured basis on which {@code DG49} calls our
     * bridge's refusal over-strict rather than protective, and it was asserted on three prose surfaces — the
     * gap row, API-FINDINGS 135, and {@code Operands.requireNotCorrelated}'s javadoc — with no test anywhere,
     * which a cold review found by grepping for it (2026-09-04). A client-side refusal needs a measured kernel
     * basis, and the measurement has to live somewhere a build can re-run.
     */
    @Test
    void aMarkedGroupOperandLISTObeysTheSameTwoPositions() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("MARKED list beside the in-scope conjunct", rawFilterVerdict(
                "[/Order/Lines/Qty] > 1 And AllGroupsFilled($/Order/Billing, $/Order/Shipping)"));
        actual.put("UNMARKED list beside the in-scope conjunct", rawFilterVerdict(
                "[/Order/Lines/Qty] > 1 And AllGroupsFilled(/Order/Billing, /Order/Shipping)"));
        actual.put("MARKED list as the SOLE filter condition",
                rawFilterVerdict("AllGroupsFilled($/Order/Billing, $/Order/Shipping)"));
        actual.put("UNMARKED list as the SOLE filter condition",
                rawFilterVerdict("AllGroupsFilled(/Order/Billing, /Order/Shipping)"));
        // The negative quantifier, so the answer is about the group-LIST carrier rather than about one operator.
        actual.put("MARKED negative list beside the in-scope conjunct", rawFilterVerdict(
                "[/Order/Lines/Qty] > 1 And NoGroupFilled($/Order/Billing, $/Order/Shipping)"));
        actual.put("the in-scope conjunct alone, hand-spelled — live control",
                rawFilterVerdict("[/Order/Lines/Qty] > 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MARKED list beside the in-scope conjunct", ADMITTED);
        expected.put("UNMARKED list beside the in-scope conjunct", ADMITTED);
        expected.put("MARKED list as the SOLE filter condition", NO_ITERATION);
        expected.put("UNMARKED list as the SOLE filter condition", NO_ITERATION);
        expected.put("MARKED negative list beside the in-scope conjunct", ADMITTED);
        expected.put("the in-scope conjunct alone, hand-spelled — live control", ADMITTED);
        assertEquals(expected, actual, "the group-LIST carrier obeys the scalar pair's gate, marked or not — so "
                + "the bridge's refusal of a marked list operand is over-strict, not protective (DG49)");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /**
     * The same carrier as {@link #filteredCount}, with the filter spelled by hand for a shape the typed surface
     * cannot express. The surrounding text is the ABSOLUTE form the renderer emits, so a raw row and a typed row
     * differ only in the filter.
     */
    private static String rawFilterVerdict(String rawFilter) {
        return codes(KernelLaws.checkModel(MODEL, GROUP, "../Marker",
                "FieldFilled(/Order/Marker) And NumberOfFilledFields(/Order/Lines*/Sku Having ("
                        + rawFilter + ")) > 0"));
    }

    /** The carrier: a filtered FIELD list on the fill count, guarded by the error field's own presence. */
    private static String filteredCount(Condition filter) {
        FieldListRef skus = M.flatten("/Order/Lines*/Sku");
        return KernelLaws.render(Dsl.fieldFilled(M.field("/Order/Marker"))
                .and(Dsl.numberOfFilledFields(skus.having(filter)).gt(0)));
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(Condition filter) {
        return codes(KernelLaws.checkModel(MODEL, GROUP, "../Marker", filteredCount(filter)));
    }

    private static String computationVerdict(Condition filter) {
        FieldListRef skus = M.flatten("/Order/Lines*/Sku");
        String operation =
                KernelLaws.render(Dsl.numberOfFilledFields(skus.having(filter)).expression());
        return codes(new RuleValidator().check(MODEL,
                new DraftComputation(GROUP, "TotalComp", "../Total", null,
                        List.of(new DraftComputation.Alternative(null, operation)))));
    }

    private static String codes(ValidationResult r) {
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(" + "));
    }
}
