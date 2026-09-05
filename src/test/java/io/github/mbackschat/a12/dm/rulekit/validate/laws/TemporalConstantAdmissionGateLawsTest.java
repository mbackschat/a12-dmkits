package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §6/§11 — which temporal CONSTANTS a temporal target admits. a12-kernel-lean's
 * {@code SPEC-2026-09-03-08} states the gate reads two things and not a third: the literal's temporal FAMILY,
 * and YEAR AGREEMENT between constant and target. It does <b>not</b> compare component SETS — an account every
 * previously measured row was consistent with, and which a reimplementer could therefore pick.
 *
 * <p><b>{@code MM} is the row that separates the two accounts</b>, because it is the one target whose components
 * are a proper subset of a yearless date literal's. A component-set gate predicts {@code "05.03."} refused there;
 * it is admitted, and the runtime stores the month alone.
 *
 * <p><b>Two columns, because this project has two gates and only one of them is the kernel's.</b> The
 * <em>builder</em> column is what the typed rulekit surface does when a consumer authors the assignment; the
 * <em>kernel</em> column is {@code RuleValidator.check} against the real consistency check, reached through the
 * untyped draft so a client-side refusal cannot hide the kernel's answer. Reading them together is the point:
 * a shape the builder refuses and the kernel admits would be an over-strict client-side refusal, which is a
 * defect on sight here — and the table shows there is none.
 *
 * <p><b>The wrong-FAMILY row is REFUSED by the builder, not erased from the type system</b> — a distinction this
 * file used to get wrong, and it matters because the two carry different obligations. {@code Dsl.timeValue}
 * yields a {@code TimeExpr}, but {@code .expression()} erases that back to a {@code Calculation}, so the shape
 * IS expressible and {@code Computation.Builder.build()} refuses it at run time by comparing the operation's
 * kind against the computed field's. It is therefore a client-side refusal, and the standing rule applies in
 * full: its measured kernel basis is the {@code time literal -> MM} row below, and the refusal MESSAGE is
 * asserted rather than merely the fact of a throw, so an unrelated exception cannot satisfy the row.
 *
 * <p><b>One code covers both refusal grounds.</b> A wrong-family refusal and a year-disagreement refusal are
 * indistinguishable by code, so a consumer must re-derive which rule fired from the constant/target pair rather
 * than read it off the diagnostic — a corrective that says "supply a Base Year" is wrong on the family row.
 *
 * <p><b>The time-side mirror is unreachable rather than unrun</b>, and the last test records it: an hour-only
 * {@code HH} format is refused, so there is no time target whose components are a proper subset of a time
 * literal's. Measured here the refusal lands on the kernel-backed model check rather than on this project's
 * draft builder, which writes the declaration without consulting the kernel — the derived
 * {@code docs/TEMPORAL-FORMAT-VOCABULARY.md} lists {@code HH} under <i>Refused</i> and {@code MM}/{@code MM-dd}
 * under <i>Admitted</i>, which is the same fact from the generated side.
 *
 * <p>The runtime half of the {@code MM} row — that the store writes the month alone — is
 * {@code adapter.laws.YearlessConstantMonthStoreDiffTest}, since a stored value needs the document engines.
 */
class TemporalConstantAdmissionGateLawsTest {

    private static final String BOOKING = "/Booking";
    private static final String MONTH_ONLY = "/Booking/MonthOnly";
    private static final String MONTH_DAY = "/Booking/MonthDay";
    private static final String CLOCK = "/Booking/Clock";
    private static final String FULL_DATE = "/Booking/FullDate";
    private static final String ADMITTED = "admitted";
    private static final String INVALID_COMPARE = "MVK_INVALID_COMPARE_TO_DATE";
    private static final String WRONG_FORMAT_FOR_OP = "MVK_WRONG_DATE_FORMAT_FOR_OP";

    /** A yearless month target, a yearless month-day target, and a full clock target. Own domain (travel booking). */
    private static final String MODEL = DocumentModelDraft.named("travel-booking-temporal").locale("en_US")
            .rootGroup("Booking", root -> root
                    .stringField("Reference", s -> { })
                    .dateField("MonthOnly", d -> d.format("MM"))
                    .dateField("MonthDay", d -> d.format("MM-dd"))
                    .dateField("FullDate", d -> d.format("dd.MM.yyyy"))
                    .timeField("Clock", t -> t.format("HH:mm:ss")))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /**
     * The gate's complete row set. The three admitted rows are what a component-set account gets wrong; the two
     * refused rows are its two grounds, sharing one code.
     */
    @Test
    void theGateReadsFamilyAndYearAgreementRatherThanComponentSets() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("yearless date -> MM, no Base Year", kernelVerdict(MONTH_ONLY, "05.03."));
        actual.put("yearless date -> MM, day above 12", kernelVerdict(MONTH_ONLY, "13.07."));
        actual.put("yearless date -> MM-dd", kernelVerdict(MONTH_DAY, "05.03."));
        actual.put("year-carrying date -> MM", kernelVerdict(MONTH_ONLY, "05.03.2024"));
        actual.put("time literal -> MM (wrong family)", kernelTimeVerdict(MONTH_ONLY));
        actual.put("time literal -> HH:mm:ss (family control)", kernelTimeVerdict(CLOCK));
        // The literal must also BE a date. These three isolate that from everything above: same target, same
        // family, same year agreement, only the day-in-month varies.
        actual.put("29.02. -> MM-dd (needs a year to be a date)", kernelVerdict(MONTH_DAY, "29.02."));
        actual.put("31.04. -> MM-dd (no year makes April 31st)", kernelVerdict(MONTH_DAY, "31.04."));
        actual.put("30.04. -> MM-dd (the day-validity control)", kernelVerdict(MONTH_DAY, "30.04."));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("yearless date -> MM, no Base Year", ADMITTED);
        expected.put("yearless date -> MM, day above 12", ADMITTED);
        expected.put("yearless date -> MM-dd", ADMITTED);
        expected.put("year-carrying date -> MM", INVALID_COMPARE);
        expected.put("time literal -> MM (wrong family)", INVALID_COMPARE);
        expected.put("time literal -> HH:mm:ss (family control)", ADMITTED);
        expected.put("29.02. -> MM-dd (needs a year to be a date)", INVALID_COMPARE);
        expected.put("31.04. -> MM-dd (no year makes April 31st)", INVALID_COMPARE);
        expected.put("30.04. -> MM-dd (the day-validity control)", ADMITTED);
        assertEquals(expected, actual,
                "a yearless literal enters a month-only target even though its components are a strict superset; "
                        + "the refusals are family, year disagreement and an impossible day — all under one code");
    }

    /**
     * The typed builder's own answer beside the kernel's. It must never be stricter on a shape the kernel
     * admits — the three admitted rows go through untouched — while the wrong-family assignment has no
     * representation at all, which is a stronger guarantee than a refusal and is recorded rather than assumed.
     */
    @Test
    void theTypedBuilderIsNeverStricterThanTheGateItModels() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("yearless date -> MM, no Base Year", builderVerdict(MONTH_ONLY, "05.03."));
        actual.put("yearless date -> MM, day above 12", builderVerdict(MONTH_ONLY, "13.07."));
        actual.put("yearless date -> MM-dd", builderVerdict(MONTH_DAY, "05.03."));
        actual.put("year-carrying date -> MM", builderVerdict(MONTH_ONLY, "05.03.2024"));
        // The two rows this column used to omit. Both are kernel-ADMITTED, so an over-strict builder refusal on
        // either is exactly the defect the column exists to exclude, and it would have passed unnoticed.
        actual.put("30.04. -> MM-dd (the day-validity control)", builderVerdict(MONTH_DAY, "30.04."));
        actual.put("time literal -> HH:mm:ss (family control)", builderTimeIntoClock());
        actual.put("time literal -> MM (wrong family)", builderTimeVerdict(MONTH_ONLY));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("yearless date -> MM, no Base Year", ADMITTED);
        expected.put("yearless date -> MM, day above 12", ADMITTED);
        expected.put("yearless date -> MM-dd", ADMITTED);
        // The builder authors it and the kernel refuses it — the correct division of labour for a rule whose
        // basis is a value relation the type system cannot see.
        expected.put("year-carrying date -> MM", ADMITTED);
        expected.put("30.04. -> MM-dd (the day-validity control)", ADMITTED);
        expected.put("time literal -> HH:mm:ss (family control)", ADMITTED);
        expected.put("time literal -> MM (wrong family)",
                "refused: the operation '\"12:30:00\"' yields TIME but the computed field "
                        + "/Booking/MonthOnly declares format 'MM', which takes DATE values — the kernel's"
                        + " assignment gate reads the declared FORMAT, never the field's kind");
        assertEquals(expected, actual,
                "the builder admits everything the kernel admits — including the two controls this column "
                        + "previously skipped — and the one shape it refuses is refused with a message that "
                        + "names the DECLARED FORMAT, so an unrelated throw cannot pass as the refusal and an "
                        + "author is not sent to change the field's kind, which the kernel never reads here");
    }

    /**
     * The same gate on the COMPARISON carrier rather than the assignment one — the row a cold review asked for,
     * because this project's canonicalizer takes a different path there and a shape the kernel admits is a
     * shape our evaluator must handle. A yearless literal compared against a year-free DATE field, with no Base
     * Year declared, is the case: both operands are year-free, so the year-agreement gate has nothing to refuse.
     */
    @Test
    void theGateOnTheComparisonCarrier() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("yearless literal vs a MM field, no Base Year",
                comparisonVerdict(MONTH_ONLY, "05.03."));
        actual.put("year-carrying literal vs a MM field, no Base Year",
                comparisonVerdict(MONTH_ONLY, "05.03.2024"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("yearless literal vs a MM field, no Base Year", ADMITTED);
        expected.put("year-carrying literal vs a MM field, no Base Year", INVALID_COMPARE);
        assertEquals(expected, actual,
                "the gate reads the same two operands whichever carrier they meet on");
    }

    /**
     * The THIRD carrier a yearless literal can reach: an operand of a temporal OPERATION, rather than an
     * assignment target or a comparison counterpart. It is measured because this project's canonicalizer has
     * exactly three callers and the other two are covered above — leaving the operation carrier the one site
     * whose reachability was asserted rather than run, which is the shape of claim this project's own rules
     * forbid.
     *
     * <p><b>Two separate gates, and only reading both explains the table.</b> A year-free <em>field</em> is
     * refused as a temporal-operation operand outright — {@code MVK_WRONG_DATE_FORMAT_FOR_OP}, and it refuses
     * the year-carrying literal identically, so that row is about the FIELD's format and not the constant's.
     * A year-free <em>literal</em> is a different matter: with no year-free field in the operation it is
     * ADMITTED, both as a bare difference over two constants and when it reaches the operation through a shift.
     * So this carrier does admit a yearless literal, and the canonicalizer is reached here.
     *
     * <p>The {@code Min} row is a third gate again ({@code MVK_TOO_MANY_CONST_FOR_CALC_OP}): an aggregate over
     * two constants is refused for constant count, not for format. It is kept because it is what distinguishes
     * "the aggregate carrier refuses year-free operands" from "this particular aggregate call was malformed".
     */
    @Test
    void theGateOnATemporalOperationOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("yearless literal vs a MM field", operationVerdict("05.03."));
        actual.put("year-carrying literal vs a MM field", operationVerdict("05.03.2024"));
        actual.put("two yearless literals, no field at all", twoLiteralOperationVerdict());
        actual.put("yearless literal shifted by AddDays, vs a MM field", shiftedOperandVerdict());
        actual.put("yearless literal under Min, vs a MM field", aggregatedOperandVerdict());
        actual.put("the borrowed year read back with YearFromDate", extractedYearVerdict());
        // The axis every row above holds constant: all of them use a YEAR-FREE field. a12-kernel-lean reported
        // on 2026-09-04 that the operand rule is GRANULARITY rather than year-bearing-ness, and that a
        // year-carrying field against a yearless literal refuses under a different code. Measured here.
        actual.put("MM-dd field vs a yearless literal", operandVerdict(MONTH_DAY, "05.03."));
        actual.put("dd.MM.yyyy field vs a yearless literal", operandVerdict(FULL_DATE, "05.03."));
        actual.put("dd.MM.yyyy field vs a year-carrying literal", operandVerdict(FULL_DATE, "05.03.2024"));

        Map<String, String> expected = new LinkedHashMap<>();
        // the year-free FIELD is what this gate refuses — the year-carrying control is refused identically
        expected.put("yearless literal vs a MM field", WRONG_FORMAT_FOR_OP);
        expected.put("year-carrying literal vs a MM field", WRONG_FORMAT_FOR_OP);
        // …but the year-free LITERAL is admitted once no year-free field is in the operation
        expected.put("two yearless literals, no field at all", ADMITTED);
        expected.put("yearless literal shifted by AddDays, vs a MM field", ADMITTED);
        expected.put("yearless literal under Min, vs a MM field", "MVK_TOO_MANY_CONST_FOR_CALC_OP");
        // Records why the anchor YEAR stays unpinned while its parity is measured: the direct read-back is
        // refused, so the leap-year result in the differential is the strongest statement available.
        expected.put("the borrowed year read back with YearFromDate", "MVK_NO_CONST");
        expected.put("MM-dd field vs a yearless literal", ADMITTED);
        expected.put("dd.MM.yyyy field vs a yearless literal", "MVK_DATE_WITH_AND_WITHOUT_YEAR");
        expected.put("dd.MM.yyyy field vs a year-carrying literal", ADMITTED);
        assertEquals(expected, actual,
                "a year-free FIELD never reaches a temporal operation, but a year-free LITERAL does — so this "
                        + "carrier is reachable and the literal must be resolved here too");
    }

    /**
     * The two refusals this project cannot AUTHOR, measured against the kernel through a raw condition string so
     * the claim rests on our own run rather than on a peer's report. Both are shapes a hand-written condition can
     * still produce, and both are the measured basis for the corresponding client-side erasure.
     */
    @Test
    void theTwoShapesOurBuilderCannotAuthorAreRefusedByTheKernel() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("unbracketed And/Or mix", rawVerdict(
                "FieldFilled(/Booking/Reference) And FieldFilled(/Booking/MonthOnly)"
                        + " Or FieldFilled(/Booking/MonthDay)"));
        actual.put("the same mix, bracketed — the control", rawVerdict(
                "FieldFilled(/Booking/Reference) And (FieldFilled(/Booking/MonthOnly)"
                        + " Or FieldFilled(/Booking/MonthDay))"));
        actual.put("a cross-kind comparison leaf", rawVerdict(
                "FieldFilled(/Booking/Reference) And [/Booking/Reference] > 5"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("unbracketed And/Or mix", "MVK_BRACKET_MISSING");
        expected.put("the same mix, bracketed — the control", ADMITTED);
        expected.put("a cross-kind comparison leaf", "MVK_INVALID_TYPE_FOR_COMPARISON");
        assertEquals(expected, actual,
                "both erasures in the typed builder have a measured kernel basis of our own");
    }

    /**
     * The time-side mirror of the {@code MM} row cannot be built, so the account's asymmetry is a property of A12
     * rather than an untested case: no time target has components that are a proper subset of a time literal's.
     *
     * <p><b>The refusal locus is the KERNEL, not this project's draft builder</b>, and saying so is the point of
     * measuring it here. {@link DocumentModelDraft} writes the declaration without consulting the kernel, so the
     * hour-only model is authored and then refused when checked — which is also why the derived
     * {@code docs/TEMPORAL-FORMAT-VOCABULARY.md} lists {@code HH} under <i>Refused</i> while {@code MM} and
     * {@code MM-dd} are admitted. Asserting the model check rather than the builder keeps the claim on the
     * surface that actually decides it.
     */
    @Test
    void anHourOnlyTimeTargetIsRefusedByTheKernelSoTheMirrorIsUnreachable() {
        String hourModel = DocumentModelDraft.named("travel-booking-hour").locale("en_US")
                .rootGroup("Booking", root -> root
                        .stringField("Reference", s -> { })
                        .timeField("HourOnly", t -> t.format("HH")))
                .build().toJson();
        assertEquals(
                "model is not consistent: Field with path '/Booking/HourOnly': The date format is invalid.",
                refusalOf(() -> BoundModel.ofJson(hourModel)),
                "an hour-only time format is refused by the kernel-backed consistency check, so no such target "
                        + "exists to receive a time constant");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** The kernel's verdict on assigning the date literal, reached through the untyped draft. */
    private static String kernelVerdict(String target, String literal) {
        return codes(new RuleValidator().check(MODEL, draft(target,
                new DslRenderer().render(Dsl.dateValue(literal).expression()))));
    }

    private static String kernelTimeVerdict(String target) {
        return codes(new RuleValidator().check(MODEL, draft(target,
                new DslRenderer().render(Dsl.timeValue("12:30:00").expression()))));
    }

    /** The kernel's verdict on COMPARING the field against the literal, rather than assigning it. */
    private static String comparisonVerdict(String field, String literal) {
        return codes(KernelLaws.checkModel(MODEL, BOOKING, "../Reference",
                new DslRenderer().render(Dsl.fieldFilled(M.field("/Booking/Reference"))
                        .and(M.date(field).after(Dsl.dateValue(literal))))));
    }

    /** The kernel's verdict on the literal as an operand of a temporal OPERATION. */
    private static String operationVerdict(String literal) {
        return codes(KernelLaws.checkModel(MODEL, BOOKING, "../Reference",
                new DslRenderer().render(Dsl.fieldFilled(M.field("/Booking/Reference"))
                        .and(Dsl.differenceInDays(M.date(MONTH_ONLY), Dsl.dateValue(literal)).gt(0)))));
    }

    /** The same operation with NO field operand, so no declared format can be what the gate is reading. */
    private static String twoLiteralOperationVerdict() {
        return codes(KernelLaws.checkModel(MODEL, BOOKING, "../Reference",
                new DslRenderer().render(Dsl.fieldFilled(M.field("/Booking/Reference"))
                        .and(Dsl.differenceInDays(Dsl.dateValue("05.03."), Dsl.dateValue("07.04.")).gt(0)))));
    }

    /** The literal reaching the operation through a SHIFT rather than directly. */
    private static String shiftedOperandVerdict() {
        return codes(KernelLaws.checkModel(MODEL, BOOKING, "../Reference",
                new DslRenderer().render(Dsl.fieldFilled(M.field("/Booking/Reference"))
                        .and(M.date(MONTH_ONLY).after(Dsl.addDays(Dsl.dateValue("05.03."), 3))))));
    }

    /** The literal reaching the operation through an AGGREGATE rather than directly. */
    private static String aggregatedOperandVerdict() {
        return codes(KernelLaws.checkModel(MODEL, BOOKING, "../Reference",
                new DslRenderer().render(Dsl.fieldFilled(M.field("/Booking/Reference"))
                        .and(M.date(MONTH_ONLY).after(
                                Dsl.min(Dsl.dateValue("05.03."), Dsl.dateValue("07.04.")))))));
    }

    /** A DATE field of the given declared format as a DifferenceInDays operand, against the literal. */
    private static String operandVerdict(String field, String literal) {
        return codes(KernelLaws.checkModel(MODEL, BOOKING, "../Reference",
                new DslRenderer().render(Dsl.fieldFilled(M.field("/Booking/Reference"))
                        .and(Dsl.differenceInDays(M.date(field), Dsl.dateValue(literal)).gt(0)))));
    }

    /** Reading the borrowed year back out of the anchored literal — the direct pin on the anchor year. */
    private static String extractedYearVerdict() {
        return codes(KernelLaws.checkModel(MODEL, BOOKING, "../Reference",
                new DslRenderer().render(Dsl.fieldFilled(M.field("/Booking/Reference"))
                        .and(Dsl.yearFromDate(Dsl.dateValue("05.03.")).gt(1999)))));
    }

    /** A hand-written condition, for the two shapes the typed builder cannot render. */
    private static String rawVerdict(String condition) {
        return codes(KernelLaws.checkModel(MODEL, BOOKING, "../Reference", condition));
    }

    private static DraftComputation draft(String target, String operation) {
        String relative = "../" + target.substring(target.lastIndexOf('/') + 1);
        return new DraftComputation(BOOKING, "AssignComp", relative, null,
                List.of(new DraftComputation.Alternative(null, operation)));
    }

    /** What the typed builder does with the same assignment: builds it, or refuses at construction. */
    private static String builderVerdict(String target, String literal) {
        return builds(() -> M.computation(M.date(target).field()).name("AssignComp")
                .calc(Dsl.dateValue(literal).expression())
                .message(Dsl.en("The month this booking is anchored to.")).build());
    }

    private static String builderTimeVerdict(String target) {
        return builds(() -> M.computation(M.date(target).field()).name("AssignComp")
                .calc(Dsl.timeValue("12:30:00").expression())
                .message(Dsl.en("The month this booking is anchored to.")).build());
    }

    /** The family CONTROL: the same time literal aimed at the TIME target the kernel admits it into. */
    private static String builderTimeIntoClock() {
        return builds(() -> M.computation(M.time(CLOCK).field()).name("AssignComp")
                .calc(Dsl.timeValue("12:30:00").expression())
                .message(Dsl.en("The service window this booking opens in.")).build());
    }

    /** {@code admitted}, or {@code refused: <message>} — the message, so an unrelated throw cannot pass. */
    private static String builds(Supplier<Computation> build) {
        try {
            build.get();
            return ADMITTED;
        } catch (RuntimeException e) {
            return "refused: " + e.getMessage();
        }
    }

    /** The refusal message a supplier throws, or {@code admitted} when it does not throw. */
    private static String refusalOf(Supplier<?> build) {
        try {
            build.get();
            return ADMITTED;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String codes(ValidationResult r) {
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(" + "));
    }
}
