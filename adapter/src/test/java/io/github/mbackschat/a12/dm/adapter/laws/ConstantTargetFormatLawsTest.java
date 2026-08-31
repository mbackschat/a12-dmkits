package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §6 — what decides whether a <b>bare constant</b> may be assigned to a computation target,
 * and what the target then stores: the target's declared <b>format string</b>, at both stages, with the
 * declared <b>kind</b> reaching neither.
 *
 * <p><b>Why this exists.</b> a12-kernel-lean's {@code SPEC-2026-08-31-02/-03/-04} state the rule as three
 * entries and ask this project to confirm or contradict it on its own fixtures. Nothing here answered any of
 * them: the laws tiers held no constant-into-temporal-target lock at all, so these are measurements rather
 * than revisions to cite.
 *
 * <p><b>The discriminating cell is a DATE field declared {@code HH:mm:ss}.</b> A kind-keyed rule and a
 * format-keyed rule agree on every cell where the declared format is the kind's natural one, which is every
 * cell a same-kind fixture can produce. Crossing them is what separates the readings, and both crossings are
 * measured here in both directions — admission <em>and</em> store — because a gate that admits and then
 * refuses to render would look identical at the first stage alone.
 *
 * <p><b>Two gates, and the one used here is named.</b> Admission is read through
 * {@link RuntimeLaws#consistencyMessages} — deserialize, expand, {@code checkConsistency}, i.e. the MVK gate.
 * The load gate alone answers differently for other model-level domains (measured in
 * {@link GroupRepeatabilityDomainLawsTest}), so a verdict that does not name its gate is not a verdict.
 *
 * <p><b>Raw operation text on purpose.</b> The computation is injected as DM-JSON rather than built through
 * {@code Computation.Builder}, because that builder requires {@code operation.kind() == computedField.kind()}
 * and would refuse the two cross-kind cells before the kernel ever saw them — the client-side refusal this
 * measurement exists to give a basis to. {@link #ourTypedBuilderIsStricterThanTheKernelOnTheCrossKindCells}
 * records that gap rather than hiding it.
 *
 * <p>Own domain (parcel fulfilment).
 */
class ConstantTargetFormatLawsTest {

    private enum Kind { DATE, TIME, DATE_TIME, DATE_FRAGMENT, STRING }

    private static final String TARGET = "/Shipment/Milestone";
    private static final String GROUP = "/Shipment";

    /** The kernel's date-constant spelling: a real calendar date, zero-padded, German order. */
    private static final String DATE_LITERAL = "05.03.2024";
    private static final String CLOCK_LITERAL = "12:30:00";

    private static final String ADMITTED = "ADMITTED";
    private static final String CLEARED = "CLEARED";
    private static final String ERRORED = "ERRORED";
    private static final String ABSENT = "no outcome for the computed field";
    private static final String FAMILY_MISMATCH = "MVK_INVALID_COMPARE_TO_DATE";

    private static final Pattern CODE = Pattern.compile("\\[(MVK_[A-Z_0-9]+)]");

    // ---- the family gate: the declared format decides, the kind does not (SPEC-2026-08-31-03) ----

    /**
     * The complete 2×2×2. Each kind appears on both sides of both outcomes, which is what excludes the kind
     * as the cause rather than leaving it merely unvaried — and the String row is a live control, so the
     * refusals cannot be read as a gate that refuses everything.
     */
    @Test
    void theDeclaredFormatDecidesAConstantsAdmissionAndTheDeclaredKindDoesNot() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("TIME · HH:mm:ss ← clock", admission(Kind.TIME, "HH:mm:ss", CLOCK_LITERAL));
        actual.put("TIME · HH:mm:ss ← date", admission(Kind.TIME, "HH:mm:ss", DATE_LITERAL));
        actual.put("TIME · yyyy-MM-dd ← clock", admission(Kind.TIME, "yyyy-MM-dd", CLOCK_LITERAL));
        actual.put("TIME · yyyy-MM-dd ← date", admission(Kind.TIME, "yyyy-MM-dd", DATE_LITERAL));
        actual.put("DATE · HH:mm:ss ← clock", admission(Kind.DATE, "HH:mm:ss", CLOCK_LITERAL));
        actual.put("DATE · HH:mm:ss ← date", admission(Kind.DATE, "HH:mm:ss", DATE_LITERAL));
        actual.put("DATE · yyyy-MM-dd ← clock", admission(Kind.DATE, "yyyy-MM-dd", CLOCK_LITERAL));
        actual.put("DATE · yyyy-MM-dd ← date", admission(Kind.DATE, "yyyy-MM-dd", DATE_LITERAL));
        actual.put("STRING ← a word — live control", admission(Kind.STRING, null, "PICKED"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("TIME · HH:mm:ss ← clock", ADMITTED);
        expected.put("TIME · HH:mm:ss ← date", FAMILY_MISMATCH);
        expected.put("TIME · yyyy-MM-dd ← clock", FAMILY_MISMATCH);
        expected.put("TIME · yyyy-MM-dd ← date", ADMITTED);
        expected.put("DATE · HH:mm:ss ← clock", ADMITTED);
        expected.put("DATE · HH:mm:ss ← date", FAMILY_MISMATCH);
        expected.put("DATE · yyyy-MM-dd ← clock", FAMILY_MISMATCH);
        expected.put("DATE · yyyy-MM-dd ← date", ADMITTED);
        expected.put("STRING ← a word — live control", ADMITTED);

        assertEquals(expected, actual,
                "the two DATE·HH:mm:ss rows and the two TIME·yyyy-MM-dd rows are the whole finding: reading "
                        + "the declared kind predicts the opposite of both");
    }

    /**
     * The cross-kind cells <b>store</b>, not merely admit. Without this the format could still be a purely
     * static gate whose runtime falls back to the kind — which would leave a reimplementer free to key the
     * renderer on the kind and stay green on the admission table above.
     */
    @Test
    void theCrossKindCellsAlsoStoreTheConstantUnerrored() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DATE · HH:mm:ss ← clock", stored(Kind.DATE, "HH:mm:ss", CLOCK_LITERAL));
        actual.put("TIME · yyyy-MM-dd ← date", stored(Kind.TIME, "yyyy-MM-dd", DATE_LITERAL));
        actual.put("DATE · yyyy-MM-dd ← date — control", stored(Kind.DATE, "yyyy-MM-dd", DATE_LITERAL));
        actual.put("TIME · HH:mm:ss ← clock — control", stored(Kind.TIME, "HH:mm:ss", CLOCK_LITERAL));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DATE · HH:mm:ss ← clock", CLOCK_LITERAL);
        expected.put("TIME · yyyy-MM-dd ← date", "2024-03-05");
        expected.put("DATE · yyyy-MM-dd ← date — control", "2024-03-05");
        expected.put("TIME · HH:mm:ss ← clock — control", CLOCK_LITERAL);

        assertEquals(expected, actual, "the declared format renders the store too, on both kinds");
    }

    // ---- the store: the format's own components, dropped silently (SPEC-2026-08-31-02) ----

    /**
     * One date constant into three complete-or-year-leading formats. The {@code yyyy} row is the consequence
     * worth locking: day and month are discarded with nothing cleared, nothing errored, and no message — the
     * most likely authoring mistake in this family produces no signal at all.
     */
    @Test
    void aDateConstantIsStoredInTheTargetsDeclaredFormatDroppingOmittedComponentsSilently() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("dd.MM.yyyy", stored(Kind.DATE, "dd.MM.yyyy", DATE_LITERAL));
        actual.put("yyyy-MM-dd", stored(Kind.DATE, "yyyy-MM-dd", DATE_LITERAL));
        actual.put("yyyy — day and month discarded", stored(Kind.DATE, "yyyy", DATE_LITERAL));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("dd.MM.yyyy", "05.03.2024");
        expected.put("yyyy-MM-dd", "2024-03-05");
        expected.put("yyyy — day and month discarded", "2024");

        assertEquals(expected, actual, "the constant is one temporal value; the declared format renders it");
    }

    // ---- the Base Year splits the component-omitting formats (SPEC-2026-08-31-04) ----

    /**
     * Not every component-omitting date format behaves alike, and the split is the <b>Base Year</b>: the
     * year-leading three admit a full-date constant outright, the two yearless ones refuse it until the model
     * declares a Base Year. Measured on DATE and on DATE_FRAGMENT so the kind is varied here too.
     *
     * <p><b>This is one quadrant of a larger rule, and the rest of it is next door.</b> Read alone, the table
     * invites "a yearless target needs a Base Year", which is false in the other direction — a <em>yearless
     * constant</em> into a yearless target needs none, and into a complete target needs one.
     * {@code TemporalConstantLiteralWitnessLawsTest#aBaseYearIsRequiredPreciselyWhereTheConstantAndTheTargetDisagreeAboutTheYear}
     * runs the whole grid: the Base Year is required exactly where constant and target disagree about
     * carrying a year. That test needs the yearless literal, which is witnessed there, which is why the grid
     * lives there and not here.
     */
    @Test
    void aYearlessFormatRefusesAFullDateConstantUntilTheModelDeclaresABaseYear() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (Kind kind : List.of(Kind.DATE, Kind.DATE_FRAGMENT)) {
            for (String format : List.of("yyyy", "yyyy-MM", "yyyyMM", "MM", "MM-dd")) {
                actual.put(kind + " · " + format + " · no Base Year", admission(kind, format, DATE_LITERAL));
                actual.put(kind + " · " + format + " · Base Year 2019",
                        admission(kind, format, DATE_LITERAL, 2019));
            }
        }

        Map<String, String> expected = new LinkedHashMap<>();
        for (Kind kind : List.of(Kind.DATE, Kind.DATE_FRAGMENT)) {
            for (String format : List.of("yyyy", "yyyy-MM", "yyyyMM")) {
                expected.put(kind + " · " + format + " · no Base Year", ADMITTED);
                expected.put(kind + " · " + format + " · Base Year 2019", ADMITTED);
            }
            for (String format : List.of("MM", "MM-dd")) {
                expected.put(kind + " · " + format + " · no Base Year", FAMILY_MISMATCH);
                expected.put(kind + " · " + format + " · Base Year 2019", ADMITTED);
            }
        }

        assertEquals(expected, actual,
                "the year-leading rows are the control: they are unchanged by the Base Year, so the two "
                        + "yearless rows moving is the Base Year and not the declaration");
    }

    /**
     * A declared Base Year lifts the yearless refusal and then reaches no part of the rendering — the year
     * it supplied is nowhere in the store. A peer that completes a yearless store from the Base Year writes
     * {@code 2024-03} where the kernel writes {@code 03}, and the admitted constant is not evidence that the
     * year survived.
     */
    @Test
    void aDeclaredBaseYearContributesNothingToTheStoredText() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String format : List.of("MM", "MM-dd", "yyyy-MM", "yyyy", "yyyy-MM-dd")) {
            actual.put(format, stored(Kind.DATE, format, DATE_LITERAL, 2019));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MM", "03");
        expected.put("MM-dd", "03-05");
        expected.put("yyyy-MM", "2024-03");
        expected.put("yyyy", "2024");
        expected.put("yyyy-MM-dd", "2024-03-05");

        assertEquals(expected, actual,
                "2019 appears in no row — the Base Year is an authoring gate, never a rendering input");
    }

    // ---- our own surface, measured against that domain ----

    /**
     * The client-side refusal this file gives a basis to. {@code Computation.Builder} requires the operation's
     * value kind to equal the computed field's declared kind, so it refuses both cross-kind cells the kernel
     * admits and stores above. Recorded as a measurement rather than fixed here: the kernel's input is the
     * declared FORMAT, which {@code FieldRef.kind()} does not carry, so conforming is a typed-surface change
     * and not a loosened predicate.
     */
    @Test
    void ourTypedBuilderIsStricterThanTheKernelOnTheCrossKindCells() {
        assertEquals(ADMITTED, admission(Kind.DATE, "HH:mm:ss", CLOCK_LITERAL),
                "precondition: the kernel admits the cell our builder refuses");
        assertEquals(
                "the operation '\"12:30:00\"' yields TIME but the computed field /Shipment/Milestone is DATE"
                        + " — kinds must match",
                typedBuilderRefusal(),
                "a change to this message is a change to a refusal the kernel does not make");
    }

    private static String typedBuilderRefusal() {
        String modelJson = baseModel(Kind.DATE, "HH:mm:ss", null);
        var bound = io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(modelJson);
        try {
            bound.computation(bound.field(TARGET)).name("Assign")
                    .calc(io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.timeValue(CLOCK_LITERAL).expression())
                    .message(io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en("Milestone."))
                    .build();
            return ADMITTED;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    // ---- instruments ----

    /** The MVK gate's verdict: {@link #ADMITTED} or the distinct {@code MVK_*} codes it raised. */
    private static String admission(Kind kind, String format, String literal) {
        return admission(kind, format, literal, null);
    }

    private static String admission(Kind kind, String format, String literal, Integer baseYear) {
        List<String> notifications =
                RuntimeLaws.consistencyMessages(model(kind, format, literal, baseYear));
        if (notifications.isEmpty()) {
            return ADMITTED;
        }
        return notifications.stream().map(ConstantTargetFormatLawsTest::codeOf).distinct().sorted()
                .collect(Collectors.joining(", "));
    }

    private static String codeOf(String notification) {
        Matcher m = CODE.matcher(notification);
        return m.find() ? m.group(1) : notification;
    }

    private static String stored(Kind kind, String format, String literal) {
        return stored(kind, format, literal, null);
    }

    /**
     * What {@code compute()} puts in the target: the rendered value, or which of the other two buckets it
     * landed in. A model the MVK gate refuses throws rather than reporting {@link #ABSENT} — an unadmitted
     * model computes nothing, and reading that silence as a store answer is the vacuity this guards.
     */
    private static String stored(Kind kind, String format, String literal, Integer baseYear) {
        String modelJson = model(kind, format, literal, baseYear);
        List<String> refusals = RuntimeLaws.consistencyMessages(modelJson);
        if (!refusals.isEmpty()) {
            throw new IllegalStateException("the kernel refused this model, so its silence is not a store "
                    + "reading: " + kind + " · " + format + " ← " + literal + " — " + refusals);
        }
        List<RuntimeLaws.Computed> outcomes =
                RuntimeLaws.compute(modelJson, RuntimeLaws.doc().group(GROUP, 1));
        return outcomes.stream().filter(o -> TARGET.equals(o.path())).findFirst()
                .map(o -> o.errored() ? ERRORED : o.cleared() ? CLEARED : o.value().orElse(ABSENT))
                .orElse(ABSENT);
    }

    /** The model with its constant-assigning computation, as DM-JSON. */
    private static String model(Kind kind, String format, String literal, Integer baseYear) {
        return RuntimeLaws.withComputation(baseModel(kind, format, baseYear), GROUP, "Assign", "../Milestone",
                '"' + literal + '"');
    }

    /**
     * The target beside a String sibling. The sibling is not decoration: a one-field group cannot tell
     * "the rule reads the target" from "the rule reads the group's only field", and this project has already
     * shipped one over-general clause built on exactly that fixture shape.
     */
    private static String baseModel(Kind kind, String format, Integer baseYear) {
        DocumentModelDraft.Builder draft = DocumentModelDraft.named("fulfilment").locale("en_US");
        if (baseYear != null) {
            draft.baseYear(baseYear);
        }
        return draft.rootGroup("Shipment", shipment -> {
            shipment.stringField("Carrier", carrier -> { });
            switch (kind) {
                case DATE -> shipment.dateField("Milestone", f -> f.format(format));
                case TIME -> shipment.timeField("Milestone", f -> f.format(format));
                case DATE_TIME -> shipment.dateTimeField("Milestone", f -> f.format(format));
                case DATE_FRAGMENT -> shipment.dateFragmentField("Milestone", f -> f.fragmentFormat(format));
                case STRING -> shipment.stringField("Milestone", f -> { });
            }
        }).build().toJson();
    }
}
