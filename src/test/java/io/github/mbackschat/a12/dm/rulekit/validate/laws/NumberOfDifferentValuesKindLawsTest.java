package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * KERNEL-FINDINGS §9 — the KIND gate on {@code NumberOfDifferentValues}, over the two kinds that are neither
 * one of the five simple kinds nor part of the plain date trio: a <b>custom field type</b> and a
 * <b>date fragment</b>.
 *
 * <p>The kernel partitions this operator's operands into three comparable classes and names the class it was
 * given, so the class each kind belongs to is readable off the refusal code rather than inferred: a fragment
 * beside a textual or numeric operand is {@code MVK_DATE_AND_NONDATE} (so a fragment is <b>date-like</b>),
 * while a custom operand beside a number is {@code MVK_STRING_ENUM_AND_NON_STRING_ENUM} (so a custom type is
 * <b>textual</b>). Those two codes are what place the kinds, and both refusals are measured here beside the
 * admissions they bound — without them "admitted" would be consistent with the operator having no gate at all.
 *
 * <p>Format compatibility stays kernel-arbitrated: two fragments of DIFFERENT component sets are admitted by
 * the typed surface and refused by the kernel under {@code MVK_DATEFORMATS_NOT_COMPATIBLE}, which is the
 * delegation {@code CountAggregate} documents rather than a gap in it.
 *
 * <p>Neighbour: {@link TemporalAggregateComponentSetLawsTest} owns the component-set gate itself over the date
 * trio; this file owns which kinds reach that gate at all.
 */
class NumberOfDifferentValuesKindLawsTest {

    private static final String GROUP = "/Part";

    /**
     * A spare-parts model with two operands per class: two custom-typed codes beside the string/enum side they
     * must pair with, and two year fragments plus a month fragment, so a same-set and a differing-set fragment
     * pair are both authorable without a second variable moving.
     */
    private static final String MODEL = DocumentModelDraft.named("spare-parts").locale("en_US")
            .rootGroup("Part", p -> p
                    .field("PartNumber", FieldKind.STRING)
                    .field("Designation", FieldKind.STRING)
                    .field("NetWeight", FieldKind.NUMBER)
                    .enumField("Category", e -> e.value("WEAR").value("SPARE"))
                    .customField("ShelfCode", f -> f.type("SelectionCode"))
                    .customField("BinCode", f -> f.type("SelectionCode"))
                    .dateFragmentField("IntroYear", f -> f.fragmentFormat("yyyy"))
                    .dateFragmentField("PhaseOutYear", f -> f.fragmentFormat("yyyy"))
                    .dateFragmentField("ServiceMonth", f -> f.fragmentFormat("MM")))
            .build().toJson();

    private static final BoundModel PARTS = BoundModel.ofJson(MODEL);

    /** Guard the aggregate so the probe rule references its own error field (MVK_ERROR_FIELD_NOT_REFERENCED). */
    private static String guarded(Condition aggregate) {
        return new DslRenderer().render(Dsl.fieldFilled(PARTS.field(GROUP + "/PartNumber")).and(aggregate));
    }

    private static void admits(Condition aggregate) {
        KernelLaws.acceptsModel(MODEL, GROUP, "../PartNumber", guarded(aggregate));
    }

    /**
     * The kernel row behind a shape the typed surface refuses client-side, written out because the builder
     * will not construct it — a client-side refusal needs a measured kernel basis.
     */
    private static void refusesRaw(String aggregate, String code) {
        KernelLaws.rejectsModel(MODEL, GROUP, "../PartNumber",
                "FieldFilled(PartNumber) And " + aggregate, code);
    }

    private static Condition countOver(String a, String b) {
        return Dsl.numberOfDifferentValues(PARTS.field(GROUP + "/" + a), PARTS.field(GROUP + "/" + b)).gt(1);
    }

    // ── admitted: the two kinds this gate lets through ──────────────────────────────────────────────

    /** A custom field type is textual, so it pairs with another custom type and with the string/enum side. */
    @Test
    void customOperandsAreAdmittedAndPairWithTheStringSide() {
        admits(countOver("ShelfCode", "BinCode"));
        admits(countOver("Designation", "ShelfCode"));
        admits(countOver("Category", "ShelfCode"));
    }

    /** Two fragments declaring one component set are date-like peers. */
    @Test
    void dateFragmentOperandsSharingAComponentSetAreAdmitted() {
        admits(countOver("IntroYear", "PhaseOutYear"));
    }

    /** The same-class control, so the admissions above are not just "this operator admits everything". */
    @Test
    void theSimpleKindPairsStayAdmitted() {
        admits(countOver("PartNumber", "Designation"));
    }

    // ── refused: the class boundaries that place each kind ──────────────────────────────────────────

    /** A fragment beside a textual operand is a DATE mismatch — which is what makes a fragment date-like. */
    @Test
    void aFragmentBesideATextualOperandIsRefusedAsADateMismatch() {
        assertThrows(IllegalArgumentException.class, () -> countOver("IntroYear", "Designation"));
        refusesRaw("NumberOfDifferentValues(IntroYear, Designation) > 1", "MVK_DATE_AND_NONDATE");
        refusesRaw("NumberOfDifferentValues(IntroYear, ShelfCode) > 1", "MVK_DATE_AND_NONDATE");
    }

    /** A custom operand beside a number is a STRING/ENUM mismatch — which is what makes it textual. */
    @Test
    void aCustomOperandBesideANumberIsRefusedAsATextualMismatch() {
        assertThrows(IllegalArgumentException.class, () -> countOver("ShelfCode", "NetWeight"));
        refusesRaw("NumberOfDifferentValues(ShelfCode, NetWeight) > 1",
                "MVK_STRING_ENUM_AND_NON_STRING_ENUM");
    }

    // ── delegated: admitted by the typed surface, arbitrated by the kernel ──────────────────────────

    /** Two fragments of DIFFERING component sets pass the kind gate and are refused on format by the kernel. */
    @Test
    void twoFragmentsOfDifferingComponentSetsStayKernelArbitrated() {
        KernelLaws.rejectsModel(MODEL, GROUP, "../PartNumber",
                guarded(countOver("IntroYear", "ServiceMonth")), "MVK_DATEFORMATS_NOT_COMPATIBLE");
    }
}
