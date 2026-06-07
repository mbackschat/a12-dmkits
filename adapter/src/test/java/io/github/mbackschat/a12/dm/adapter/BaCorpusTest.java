package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The <b>BA-derived DSL corpus</b> — working rules transferred from the kernel-team BA reference's worked
 * examples to our own unified {@link BaCorpusModel Shipment} domain (mechanism preserved, text never copied;
 * license-safe rule). Two guarantees:
 * <ul>
 *   <li><b>Working rules</b> — every rule is accepted by the real kernel ({@code everyRuleIsKernelValid}).</li>
 *   <li><b>Honest ingest coverage</b> — each rule is tagged {@code modeled} (must convert to a typed AST) or
 *       {@code OPAQUE} (a construct rulekit doesn't model yet); {@code ingestCoverage} prints the split and
 *       holds the line. As each unmodeled family lands, its rules flip {@code OPAQUE → modeled}.</li>
 * </ul>
 * This is the measurement instrument a text/keyword estimate can't be: a rule only counts as ingestable when
 * the real converter turns it into a typed condition.
 */
class BaCorpusTest {

    /** A transferred rule: a unique name, the (top-level) error field, the condition text, and whether it should convert. */
    record R(String name, String errorField, String cond, boolean modeled) {
        R(String name, String errorField, String cond) {
            this(name, errorField, cond, true);
        }
    }

    private static final List<R> RULES = List.of(
            // --- presence -------------------------------------------------------------------------------
            new R("Presence1", "ShipmentRef", "FieldFilled(ShipmentRef) And FieldNotFilled(Recipient)"),
            new R("Presence2", "Weight", "AtLeastOneFieldFilled(Weight, DeclaredValue)"),
            new R("Presence3", "ShipmentRef", "FieldFilled(ShipmentRef) And GroupNotFilled(BillingAddress)"),
            new R("Presence4", "Weight", "NoFieldFilled(Weight, DeclaredValue, ParcelCount)"),
            // --- comparisons ----------------------------------------------------------------------------
            new R("NumConst", "Weight", "FieldFilled(Weight) And [Weight] > 100"),
            new R("NumFields", "Weight", "AllFieldsFilled(Weight, DeclaredValue) And [Weight] > [DeclaredValue]"),
            new R("StrConst", "Recipient", "FieldFilled(Recipient) And [Recipient] != \"Unknown\""),
            new R("EnumIs", "Priority", "FieldFilled(Priority) And [Priority] == \"HIGH\""),
            new R("EnumCat", "Channel", "FieldFilled(Channel) And [Channel -> Speed] == \"FAST\""),
            new R("BoolCmp", "IsExpress", "FieldFilled(IsExpress) And [IsExpress] == True"),
            new R("ConfirmCmp", "TermsAccepted", "[TermsAccepted] == True"),
            // --- strings --------------------------------------------------------------------------------
            new R("Pattern", "ShipmentRef", "FieldFilled(ShipmentRef) And [ShipmentRef] PatternMatched \"[A-Z]{3}.*\""),
            new R("LengthOf", "ShipmentRef", "FieldFilled(ShipmentRef) And Length(ShipmentRef) > 5"),
            // --- dates ----------------------------------------------------------------------------------
            new R("DateFields", "ArrivalDate", "AllFieldsFilled(DispatchDate, ArrivalDate) And [ArrivalDate] > [DispatchDate]"),
            new R("DateToday", "DispatchDate", "FieldFilled(DispatchDate) And [DispatchDate] > Today"),
            new R("DateDiff", "ArrivalDate", "AllFieldsFilled(DispatchDate, ArrivalDate) And DifferenceInDays(ArrivalDate, DispatchDate) > 7"),
            new R("DateAdd", "ArrivalDate", "AllFieldsFilled(DispatchDate, ArrivalDate) And [ArrivalDate] > AddDays(DispatchDate, 3)"),
            new R("YearFrom", "DispatchDate", "FieldFilled(DispatchDate) And YearFromDate(DispatchDate) < 2020"),
            // --- aggregates -----------------------------------------------------------------------------
            new R("SumList", "Weight", "FieldFilled(Weight) And [Weight] != Sum(Parcels*/ParcelWeight)"),
            new R("MaxList", "DeclaredValue", "FieldFilled(DeclaredValue) And MaxValue(Parcels*/ParcelValue) > 1000"),
            new R("CountFilled", "ParcelCount", "FieldFilled(ParcelCount) And [ParcelCount] == NumberOfFilledFields(Parcels*/ParcelWeight)"),
            new R("HavingFilter", "DeclaredValue", "FieldFilled(DeclaredValue) And MaxValue(Parcels*/ParcelValue Having [Parcels/ParcelWeight] > 10) > 100"),
            // --- value lists ----------------------------------------------------------------------------
            new R("ValueList", "Priority", "FieldFilled(Priority) And FieldValueIncludedInValueList(Priority, \"HIGH\", \"NORMAL\")"),
            // --- arithmetic / TIME / DATE_TIME ----------------------------------------------------------
            new R("Arithmetic", "Weight", "AllFieldsFilled(Weight, DeclaredValue) And [Weight] > [DeclaredValue] * 2"),
            new R("Rounding", "Weight", "FieldFilled(Weight) And RoundDown([Weight], 0) < 1"),
            new R("TimeOrder", "WindowStart", "AllFieldsFilled(WindowStart, WindowEnd) And [WindowStart] >= [WindowEnd]"),
            new R("HoursFrom", "ScheduledAt", "FieldFilled(ScheduledAt) And HoursFromTime(ScheduledAt) > 17"),
            new R("DateConstruct", "Day", "FieldFilled(Day) And Valid(Date(Day, Month, Year))"),
            new R("DateRangeStart", "DispatchDate", "FieldFilled(DispatchDate) And YearFromDate(StartOfDateRange(CoverPeriod)) > 2020"),
            new R("DateRangeEndDay", "ParcelCount", "FieldFilled(ParcelCount) And [ParcelCount] != DayFromDate(EndOfDateRange(CoverPeriod))"),
            new R("RangesOverlap", "DispatchDate", "FieldFilled(DispatchDate) And DateRangesOverlap(CoverPeriod, Parcels*/ParcelCoverage)"),
            new R("AnyRangeOverlaps", "DispatchDate", "FieldFilled(DispatchDate) And AtLeastOneDateRangeOverlaps(CoverPeriod IN Parcels*/ParcelCoverage)"),
            // --- date / time constant literals (family 1) -----------------------------------------------
            new R("DateLiteral", "DispatchDateLocal", "FieldFilled(DispatchDateLocal) And [DispatchDateLocal] > \"01.01.2022\""),
            new R("TimeLiteral", "PickupTime", "FieldFilled(PickupTime) And [PickupTime] > \"17:00:00\""),
            new R("DateLiteralDiff", "DispatchDate", "FieldFilled(DispatchDate) And DifferenceInDays(DispatchDate, \"01.01.2022\") > 7"),

            // --- known-opaque: unmodeled families (kernel-valid, ingest preserved verbatim) --------------
            new R("NumValueInFields", "TermsAccepted", "FieldFilled(TermsAccepted) And NumberOfValueInFields(True IN OptInA, OptInB, OptInC) >= 2", false));

    private static final String MODEL = BaCorpusModel.json();
    private static final RuleValidator VALIDATOR = new RuleValidator();

    @Test
    void everyRuleIsKernelValid() {
        assertAll(RULES.stream().map(r -> () -> {
            ValidationResult res = VALIDATOR.check(MODEL,
                    new DraftRule("/Shipment", r.name(), "../" + r.errorField(), r.cond(), "C", "ERROR"));
            assertTrue(res.ok(), () -> r.name() + " rejected by kernel: " + res.diagnostics());
        }));
    }

    @Test
    void ingestCoverageMatchesExpectations() {
        var adapter = KernelAdapter.ofJson(inject(MODEL, RULES));
        int converted = 0;
        StringBuilder mismatches = new StringBuilder();
        for (R r : RULES) {
            Ingest ing = adapter.ingest("/Shipment/" + r.name());
            boolean isConverted = ing instanceof Ingest.Converted;
            if (isConverted) {
                converted++;
            }
            if (isConverted != r.modeled()) {
                mismatches.append("\n  ").append(r.name()).append(": expected ")
                        .append(r.modeled() ? "Converted" : "Opaque").append(", got ")
                        .append(isConverted ? "Converted" : "Opaque");
            }
        }
        System.out.printf("%n=== BA-corpus ingest coverage: %d/%d converted (%.0f%%) ===%n",
                converted, RULES.size(), 100.0 * converted / RULES.size());
        assertTrue(mismatches.length() == 0, () -> "ingest outcome mismatches:" + mismatches);
    }

    /** Inject the corpus rules as raw Rule elements into the /Shipment group. */
    private static String inject(String modelJson, List<R> rules) {
        StringBuilder block = new StringBuilder();
        for (R r : rules) {
            String escaped = r.cond().replace("\\", "\\\\").replace("\"", "\\\"");
            block.append("{ \"type\": \"Rule\", \"id\": \"/Shipment/").append(r.name())
                    .append("\", \"name\": \"").append(r.name()).append("\", \"Rule\": {")
                    .append(" \"errorEntityRelPath\": \"../").append(r.errorField()).append("\",")
                    .append(" \"errorCode\": \"C_").append(r.name()).append("\",")
                    .append(" \"errorCondition\": \"").append(escaped).append("\", \"severity\": \"ERROR\",")
                    .append(" \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } },\n");
        }
        int br = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, br + 1) + "\n" + block + modelJson.substring(br + 1);
    }
}
