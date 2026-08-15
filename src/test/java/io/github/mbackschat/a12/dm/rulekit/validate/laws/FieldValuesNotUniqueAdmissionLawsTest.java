package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * KERNEL-FINDINGS §4 — {@code FieldValuesNotUnique} <b>operand admission</b> (SPEC-2026-08-04-02, extended and
 * partly corrected by SPEC-2026-08-05-02, scoped by SPEC-2026-08-05-04). Three gates the canonical account did not
 * carry, all measured here against the real kernel:
 *
 * <ol>
 * <li><b>Arity.</b> The list needs more than one operand — a single <em>direct</em> field is rejected
 *     {@code MVK_PARAMSIZE_INVALIDN} — but a single <em>starred</em> slot is admitted, because it expands to a
 *     row stream and so already is many fields. The shared param law of the count aggregates
 *     (KERNEL-FINDINGS "Param law is shared with the count aggregates").</li>
 * <li><b>Which kinds at all.</b> Boolean, Confirm, and DateRange are refused outright with
 *     {@code MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED} — and a Boolean beside a String reports <em>that</em> code
 *     rather than the mixing one, so the kind gate runs first. Every other field kind is admissible, Custom
 *     included.</li>
 * <li><b>One comparability category, and one declared format inside the temporal one.</b> Mixing categories draws
 *     {@code MVK_VARYING_TYPES_NOT_ALLOWED}; the categories are {String, Custom}, {Enumeration}, {Number}, and the
 *     temporal group {Date, Time, DateTime, DateFragment}. Within the temporal category every operand must carry one
 *     <em>identical declared format string</em>, else {@code MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED} again.</li>
 * </ol>
 *
 * <p><b>Three discriminators carry this file</b>, because each gate above is asserted by an outcome a blunter kernel
 * would also produce.
 *
 * <ul>
 * <li>The category gate is STRICTER than token comparability: String beside stored Enumeration is refused here while
 *     {@code NumberOfDifferentValues} admits exactly that mix over the same two fields. Asserting the rejection
 *     alone would be satisfied by a kernel that refused the mix everywhere; the pair is what shows the two
 *     operators do not share one comparability rule.</li>
 * <li>The temporal gate keys on the <b>declared format, not the kind</b> — the case that decides between the two
 *     rival readings, and the one that corrected this file. A Date field declared {@code yyyy} beside a DateFragment
 *     declared {@code yyyy} is <em>admitted</em> across two different kinds, while two Date fields differing only in
 *     declared format are refused. A kind-equality rule gets both rows wrong.</li>
 * <li>That format rule is temporal-local, not a general same-declaration rule: two Number fields at different
 *     declared scales stay admitted. Without that control, "differing declared formats are refused" would be
 *     satisfied by a kernel that demanded identical declarations everywhere — and the canonical cross-scale equality
 *     claim (a scale-0 {@code 5} equals a scale-2 {@code 5.00}) would be unauthorable.</li>
 * <li>Each gate's SCOPE is the whole operand list, not the first offending operand — so authored position never
 *     selects the reported class. Three of the five three-operand rows at the bottom place a category mismatch
 *     <em>ahead</em> of the operand that offends the kind or format gate; a fail-fast implementation reports the
 *     mixing code on exactly those.</li>
 * </ul>
 */
class FieldValuesNotUniqueAdmissionLawsTest {

    /** The group every probe rule lives in, and the fields it may hang its error on. */
    private static final String GROUP = "/StockCount";

    /**
     * Assert acceptance of {@code list} as the operand list, with the error hung on {@code errorField}.
     *
     * <p>The condition carries a {@code FieldFilled(errorField) And} guard because the kernel requires a rule's
     * error field to be referenced by its own condition ({@code MVK_ERROR_FIELD_NOT_REFERENCED}) — the same shape
     * the catalog's published example uses. Without it every case here fails on that unrelated gate, which is how
     * this fixture was first written and what the run corrected.
     */
    private static void admits(String errorField, String list) {
        KernelLaws.acceptsModel(stockCount(), GROUP, "../" + errorField,
                "FieldFilled(" + errorField + ") And " + list);
    }

    /** As {@link #admits}, for a list the kernel must reject with exactly {@code code}. */
    private static void refuses(String errorField, String list, String code) {
        KernelLaws.rejectsModel(stockCount(), GROUP, "../" + errorField,
                "FieldFilled(" + errorField + ") And " + list, code);
    }

    /** As {@link #refuses}, additionally proving {@code preemptedCode} does <b>not</b> fire — a gate-order law. */
    private static void refusesInsteadOf(String errorField, String list, String code, String preemptedCode) {
        KernelLaws.rejectsModelInsteadOf(stockCount(), GROUP, "../" + errorField,
                "FieldFilled(" + errorField + ") And " + list, code, preemptedCode);
    }

    /**
     * A warehouse stock-count model carrying one pair per admission case: two Number fields at DIFFERENT declared
     * scales (the cross-scale control), two Strings, an Enumeration, two Custom fields of DIFFERENT registered type
     * names, the whole temporal family at controlled formats (two {@code dd.MM.yyyy} Dates, one {@code yyyy-MM-dd}
     * Date, one {@code yyyy} Date beside a {@code yyyy} DateFragment — the discriminating pair — two {@code MM}
     * DateFragments, two Times, two DateTimes), the two refused kinds (Boolean, DateRange), and a repeatable
     * {@code Counts} group for the starred forms.
     *
     * <p>{@code Bin} and {@code Tallies} exist for the <b>group-operand</b> rows at the bottom and are
     * deliberately <em>homogeneous</em> — two Strings and two same-scale Numbers. A mixed group such as
     * {@code Counts} cannot carry that measurement: its expansion would offend the category gate, so a
     * rejection would not distinguish "a group operand is refused" from "this group's contents are refused".
     *
     * <p>Time and DateTime carry no explicit format because the kernel admits exactly one declared format for Time (DateTime also admits the time-only HH:mm:ss)
     * ({@code HH:mm:ss} and {@code yyyy-MM-dd'T'HH:mm:ss}) — a probe declaring {@code HH:mm} was refused at the
     * model level, so their same-format rows cannot be separated from their kind rows and are not asserted as such.
     */
    private static String stockCount() {
        return DocumentModelDraft.named("stock-count").locale("en_US")
                .rootGroup("StockCount", root -> root
                        .numberField("UnitsOnHand", n -> n.fractionDigits(0))
                        .numberField("UnitsReserved", n -> n.fractionDigits(2))
                        .numberField("UnitsDamaged", n -> n.fractionDigits(2))
                        .stringField("BinCode", s -> { })
                        .stringField("AisleCode", s -> { })
                        .enumField("Condition", e -> e.value("SEALED").value("OPENED").value("DAMAGED"))
                        .customField("SupplierIban", c -> c.type("Iban"))
                        .customField("SupplierBic", c -> c.type("Bic"))
                        .dateField("CountedOn", d -> d.format("dd.MM.yyyy"))
                        .dateField("VerifiedOn", d -> d.format("dd.MM.yyyy"))
                        .dateField("ClosedOn", d -> d.format("yyyy-MM-dd"))
                        .dateField("StocktakeYear", d -> d.format("yyyy"))
                        .dateFragmentField("ReportingYear", f -> f.fragmentFormat("yyyy"))
                        .dateFragmentField("ReportingMonth", f -> f.fragmentFormat("MM"))
                        .dateFragmentField("AuditMonth", f -> f.fragmentFormat("MM"))
                        .timeField("ShiftStart", t -> { })
                        .timeField("ShiftEnd", t -> { })
                        .dateTimeField("SealedAt", dt -> { })
                        .dateTimeField("OpenedAt", dt -> { })
                        .dateRangeField("CountWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                        .dateRangeField("AuditWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                        .field("Recount", FieldKind.BOOLEAN)
                        .field("Discrepancy", FieldKind.BOOLEAN)
                        .field("CountConfirmed", FieldKind.CONFIRM)
                        .field("AuditConfirmed", FieldKind.CONFIRM)
                        .group("Counts", counts -> counts.repeatable(50)
                                .numberField("Counted", n -> n.fractionDigits(0))
                                .stringField("CounterInitials", s -> { }))
                        .group("Bin", bin -> bin
                                .stringField("Zone", s -> { })
                                .stringField("Rack", s -> { }))
                        .group("Tallies", t -> t.repeatable(50)
                                .numberField("FirstTally", n -> n.fractionDigits(0))
                                .numberField("SecondTally", n -> n.fractionDigits(0)))
                        .group("Slot", slot -> slot.stringField("SlotCode", s -> { })))
                .build().toJson();
    }

    // ── arity: more than one operand, unless one starred slot already supplies many ───────────────────

    @Test
    void aSingleDirectFieldIsRejected() {
        refuses("BinCode", "FieldValuesNotUnique(UnitsOnHand)", "MVK_PARAMSIZE_INVALIDN");
    }

    @Test
    void aSingleStarredSlotIsAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(Counts*/Counted)");
    }

    @Test
    void aMixedDirectAndStarredListOfOneKindIsAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(UnitsOnHand, Counts*/Counted)");
    }

    // ── one comparability category — but any scale within Number ─────────────────────────────────────

    @Test
    void twoNumberFieldsAtDifferentScalesAreAdmitted() {
        // The admission the canonical cross-scale equality claim rests on: without it, the scale-0 5 versus
        // scale-2 5.00 example would be unauthorable.
        admits("BinCode", "FieldValuesNotUnique(UnitsOnHand, UnitsReserved)");
    }

    @Test
    void threeNumberFieldsAreAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(UnitsOnHand, UnitsReserved, UnitsDamaged)");
    }

    @Test
    void twoStringFieldsAreAdmitted() {
        admits("UnitsOnHand", "FieldValuesNotUnique(BinCode, AisleCode)");
    }

    @Test
    void aNumberBesideAStringIsRejected() {
        refuses("UnitsDamaged", "FieldValuesNotUnique(UnitsOnHand, BinCode)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void aStringBesideAnEnumerationIsRejected() {
        refuses("UnitsOnHand", "FieldValuesNotUnique(BinCode, Condition)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void anEnumerationBesideAStringIsRejected() {
        // Both orders, because a gate that inspected only the first operand's kind would pass one of them.
        refuses("UnitsOnHand", "FieldValuesNotUnique(Condition, BinCode)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    // ── which kinds at all: Boolean, Confirm, and DateRange are refused outright ──────────────────────

    @Test
    void twoBooleanFieldsAreRejected() {
        refuses("BinCode", "FieldValuesNotUnique(Recount, Discrepancy)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
    }

    @Test
    void twoConfirmFieldsAreRejected() {
        refuses("BinCode", "FieldValuesNotUnique(CountConfirmed, AuditConfirmed)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
    }

    @Test
    void aConfirmBesideAStringReportsTheKindGateRatherThanTheMix() {
        refusesInsteadOf("AisleCode", "FieldValuesNotUnique(CountConfirmed, BinCode)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void aSingleConfirmStillReportsTheArityGateFirst() {
        refusesInsteadOf("BinCode", "FieldValuesNotUnique(CountConfirmed)",
                "MVK_PARAMSIZE_INVALIDN", "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
    }

    @Test
    void twoDateRangeFieldsAreRejected() {
        // DateRange is refused despite carrying a declared format, so the temporal format rule below is not what
        // excludes it — the kind is.
        refuses("BinCode", "FieldValuesNotUnique(CountWindow, AuditWindow)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
    }

    @Test
    void aBooleanBesideAStringReportsTheKindGateRatherThanTheMix() {
        // Gate order: an inadmissible kind pre-empts the category-mixing check, which would otherwise be the more
        // specific complaint about this list.
        refusesInsteadOf("AisleCode", "FieldValuesNotUnique(Recount, BinCode)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void twoCustomFieldsOfDifferentRegisteredTypesAreAdmitted() {
        // Custom is admissible, and its registered type name is not part of the gate.
        admits("BinCode", "FieldValuesNotUnique(SupplierIban, SupplierBic)");
    }

    @Test
    void aCustomFieldBesideAStringIsAdmitted() {
        // …and Custom shares String's comparability category, unlike Enumeration below.
        admits("UnitsOnHand", "FieldValuesNotUnique(SupplierIban, BinCode)");
    }

    // ── inside the temporal category: one identical declared FORMAT, across kinds ─────────────────────

    @Test
    void twoDateFieldsSharingOneDeclaredFormatAreAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(CountedOn, VerifiedOn)");
    }

    @Test
    void twoDateFieldsDifferingOnlyInDeclaredFormatAreRejected() {
        refuses("BinCode", "FieldValuesNotUnique(CountedOn, ClosedOn)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
    }

    @Test
    void twoTimeFieldsAreAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(ShiftStart, ShiftEnd)");
    }

    @Test
    void twoDateTimeFieldsAreAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(SealedAt, OpenedAt)");
    }

    @Test
    void twoDateFragmentFieldsSharingOneFragmentFormatAreAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(ReportingMonth, AuditMonth)");
    }

    @Test
    void twoDateFragmentFieldsDifferingOnlyInFragmentFormatAreRejected() {
        refuses("BinCode", "FieldValuesNotUnique(ReportingMonth, ReportingYear)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
    }

    @Test
    void aDateBesideATimeIsRejectedOnTheFormatGateNotTheCategoryMix() {
        // Both are temporal, so the categories match and the mixing gate stays silent; their declared formats differ,
        // which is what refuses the list.
        refusesInsteadOf("BinCode", "FieldValuesNotUnique(CountedOn, ShiftStart)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void aDateAndADateFragmentSharingOneFormatAreAdmittedAcrossKinds() {
        // THE DISCRIMINATOR. Two different field kinds, one identical declared format string — admitted. Paired with
        // the same-kind/differing-format rejection above, this is what shows the gate reads the format and not the
        // kind, and it is the case that corrected SPEC-2026-08-05-02's "DATE alone" reading.
        admits("BinCode", "FieldValuesNotUnique(StocktakeYear, ReportingYear)");
    }

    @Test
    void aDateBesideAStringIsRejectedAsACategoryMix() {
        refuses("UnitsOnHand", "FieldValuesNotUnique(CountedOn, BinCode)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void aDateBesideANumberIsRejectedAsACategoryMix() {
        refuses("BinCode", "FieldValuesNotUnique(CountedOn, UnitsOnHand)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    // ── the discriminator: NumberOfDifferentValues admits the very mix this operator refuses ──────────

    @Test
    void numberOfDifferentValuesAdmitsTheStringEnumerationMixThisOperatorRefuses() {
        admits("UnitsOnHand", "NumberOfDifferentValues(BinCode, Condition) < 2");
    }

    // ── each gate reads the WHOLE list, so authored position never selects the reported class ─────────

    /*
     * SPEC-2026-08-05-04. The pre-emption above says which gate wins; these say over what SCOPE each gate runs.
     * Both gates walk the entire operand list, so an operand that offends a LATER gate still reports that gate's
     * code even when an earlier-authored operand already offends the mixing one. The rival reading — refuse at the
     * first offending operand in authored order — is what an implementation naturally writes, and it reports the
     * mixing class on the three cases below marked THE DISCRIMINATOR.
     */

    @Test
    void anInadmissibleKindAfterACategoryMismatchStillReportsTheKindGate() {
        // THE DISCRIMINATOR. Operand 2 (String beside Date) already mixes categories, and operand 3 is an
        // inadmissible kind: fail-fast in authored order would stop at operand 2 and report the mix.
        refusesInsteadOf("AisleCode", "FieldValuesNotUnique(CountedOn, BinCode, Recount)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void theSameThreeOperandsReorderedReportTheSameKindGate() {
        // The control: with the Boolean moved ahead of the mismatched String, both readings agree — which is why
        // the pair is what carries the claim and neither row does alone.
        refusesInsteadOf("AisleCode", "FieldValuesNotUnique(CountedOn, Recount, BinCode)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void aConfirmAfterACategoryMismatchStillReportsTheKindGate() {
        refusesInsteadOf("AisleCode", "FieldValuesNotUnique(CountedOn, BinCode, CountConfirmed)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void theSameConfirmListReorderedReportsTheSameKindGate() {
        refusesInsteadOf("AisleCode", "FieldValuesNotUnique(CountedOn, CountConfirmed, BinCode)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void aTemporalFormatMismatchSplitAroundACategoryMismatchStillReportsTheFormatGate() {
        // THE DISCRIMINATOR for the same predicate's FORMAT half: the two Dates differ in declared format, and the
        // String authored between them mixes categories with both.
        refusesInsteadOf("AisleCode", "FieldValuesNotUnique(CountedOn, BinCode, ClosedOn)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void theSameFormatMismatchReorderedReportsTheSameGate() {
        refusesInsteadOf("AisleCode", "FieldValuesNotUnique(CountedOn, ClosedOn, BinCode)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    @Test
    void aNumberLedListReportsTheKindGateAcrossAnInterveningCategoryMismatch() {
        // The second instance, off the temporal family entirely — so the scope rule is not read as something the
        // temporal format gate does.
        refusesInsteadOf("AisleCode", "FieldValuesNotUnique(UnitsOnHand, BinCode, Recount)",
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    // ── a GROUP-scope operand: refused here, while the entity-list aggregates expand it ────────────────

    /*
     * Measured 2026-08-12, closing an unmeasured client-side refusal rather than adding a capability.
     *
     * `Dsl.fieldValuesNotUnique` takes a `FieldOperand`, which permits `FieldRef` and `FieldListRef` but NOT
     * `GroupListRef` — so a group operand is TYPE-LEVEL unrepresentable here, the quiet form of a refusal the
     * "a client-side refusal needs a measured kernel basis" rule names explicitly: no runtime error, just a
     * missing factory, so the shape reads as "not a thing" rather than "refused". `4b244b84` widened exactly
     * that slot for Sum/MaxValue/MinValue/NumberOfDifferentValues/NumberOfFilledFields, which made the omission
     * here a live question instead of a latent one: the two operators sit one `permits` clause apart.
     *
     * These rows are therefore authored as literals — the only legitimate reason to hand-write a condition in
     * this estate is to reach a shape the typed surface exists to prevent. Each rejection is paired with the
     * SAME fields as explicit operands, admitted, so the measurement cannot be satisfied by a kernel that
     * simply dislikes the fixture's groups.
     */

    /**
     * A NON-repeatable group operand is <b>ADMITTED</b> — the measurement that refuted the hypothesis this
     * section was written to confirm. It was written expecting {@code MVK_NO_GROUPS_ALLOWED}, by analogy with
     * {@code NumberOfFilledGroups}' mirror-image {@code MVK_NO_FIELDS_ALLOWED}; the kernel accepts.
     */
    @Test
    void aNonRepeatableGroupOperandIsAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(Bin)");
    }

    /** The control: {@code Bin}'s own two fields, listed explicitly, are admitted. */
    @Test
    void theSameTwoFieldsListedExplicitlyAreAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(Bin/Zone, Bin/Rack)");
    }

    @Test
    void aStarredRepeatableGroupOperandIsAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(Tallies*)");
    }

    /** The control for the starred row: the same group's fields as starred field lists are admitted. */
    @Test
    void theSameStarredGroupsFieldsAreAdmitted() {
        admits("BinCode", "FieldValuesNotUnique(Tallies*/FirstTally, Tallies*/SecondTally)");
    }

    /**
     * THE ARITY DISCRIMINATOR, and the two gates turn out to disagree about what a group operand <em>is</em>.
     * A group is one authored slot, so "a group is admitted" had two rival mechanisms: the arity gate counts the
     * group's <em>expansion</em>, or it treats any group as already-many the way it treats a starred slot. A
     * group containing exactly ONE field separates them — and it is <b>ADMITTED</b>, so the arity gate does not
     * count the expansion. A group slot satisfies the param law unconditionally, exactly as a starred slot does
     * ({@code AggregateGroupOperandLawsTest.aStarredGroupScopeSatisfiesTheParamLawAlone} is the same fact in the
     * aggregate family).
     *
     * <p>Read together with the category row below, the split is the finding: <b>arity</b> reads the authored
     * slots and treats a group as many, while <b>category</b> reads the expansion. Neither row alone shows it.
     */
    @Test
    void aGroupWhoseExpansionIsASingleFieldSatisfiesTheArityGateAnyway() {
        admits("BinCode", "FieldValuesNotUnique(Slot)");
    }

    /** The control that keeps the row above from being read as "one operand is always fine": a single FIELD is not. */
    @Test
    void thatSameSingleFieldAuthoredDirectlyStillFailsTheArityGate() {
        refuses("BinCode", "FieldValuesNotUnique(Slot/SlotCode)", "MVK_PARAMSIZE_INVALIDN");
    }

    /**
     * THE KIND DISCRIMINATOR. {@code Counts} expands to a Number beside a String, so if the category gate reads
     * the expansion this is the ordinary mixing refusal; if a group operand were waved past the type gates it
     * would be admitted. Pairs with the two homogeneous admissions above — those alone are equally consistent
     * with a kernel that type-checks nothing it reaches through a group.
     */
    @Test
    void aGroupWhoseExpansionMixesCategoriesReportsTheMixingGate() {
        refuses("BinCode", "FieldValuesNotUnique(Counts*)", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }

    /**
     * …and the TYPED form now authors it (DG33). This closes the loop the rows above opened: they measured that
     * the kernel admits a group operand, and this asserts the shape rulekit renders for it is the one the kernel
     * accepts — so the widened factory cannot drift from the measurement that justified it.
     */
    @Test
    void theTypedGroupScopedFactoryRendersAnAcceptedCondition() {
        BoundModel model = BoundModel.ofJson(stockCount());
        String condition = new DslRenderer().render(
                Dsl.fieldFilled(model.field("/StockCount/BinCode"))
                        .and(Dsl.fieldValuesNotUnique(model.flattenGroup("/StockCount/Bin"))));

        KernelLaws.acceptsModel(stockCount(), GROUP, "../BinCode", condition);
    }

    /** The starred repeatable peer, so the typed factory is measured on both group shapes rather than one. */
    @Test
    void theTypedGroupScopedFactoryAlsoRendersTheStarredRepeatableForm() {
        BoundModel model = BoundModel.ofJson(stockCount());
        String condition = new DslRenderer().render(
                Dsl.fieldFilled(model.field("/StockCount/BinCode"))
                        .and(Dsl.fieldValuesNotUnique(model.flattenGroup("/StockCount/Tallies*"))));

        KernelLaws.acceptsModel(stockCount(), GROUP, "../BinCode", condition);
    }
}
