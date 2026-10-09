package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * A {@code Date}/{@code Time} component is what A12's grammar admits there and nothing else: a field, a keyed cell,
 * a quoted constant or a component extract operation. Every other numeric expression is refused when the
 * construction is built, with the class A12 draws for it ({@code adapter.laws.CheckerConstructorComponentAgreementTest},
 * KERNEL-FINDINGS KF532).
 *
 * <p>Own domain (an insurance cover term).
 */
class TemporalComponentGrammarTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("cover-term").locale("en_US")
            .rootGroup("Term", t -> t
                    .stringField("Key", f -> { })
                    .numberField("RenewalHour", n -> n.fractionDigits(0).min("0").max("23"))
                    .dateField("StartOn", d -> d.format("yyyy-MM-dd"))
                    .timeField("RenewalClock", c -> c.format("HH:mm:ss"))
                    .dateTimeField("SignedAt", c -> c.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .group("Claims", g -> g.repeatable(5).indexField("Code")
                            .stringField("Code", f -> { })
                            .numberField("HourNo", n -> n.fractionDigits(0).min("0").max("23"))))
            .build().toJson());

    private static final NumberRef HOUR = M.num("/Term/RenewalHour");

    @Test
    void aComponentOutsideTheGrammarIsRefusedWithA12sClass() {
        Map<String, Supplier<Object>> built = new LinkedHashMap<>();
        built.put("an aggregate", () -> Dsl.time(Dsl.maxValue(M.flatten("/Term/Claims*/HourNo"))));
        built.put("arithmetic", () -> Dsl.time(HOUR.plus(1)));
        built.put("a rounding", () -> Dsl.date(Dsl.temporalComponent("01"), Dsl.temporalComponent("01"),
                Dsl.roundUp(HOUR.asNumber())));
        Map<String, String> actual = new LinkedHashMap<>();
        built.forEach((name, build) -> actual.put(name,
                assertThrows(KernelCodeRefusal.class, build::get, name).code().name()));
        assertEquals(Map.of("an aggregate", "MVK_UNEXPECTED_TOKEN", "arithmetic", "MVK_UNEXPECTED_TOKEN",
                "a rounding", "MVK_UNEXPECTED_TOKEN"), actual);
    }

    /**
     * A stamp-splitting extract operation is grammatical, so its refusal is the slot's: no position of either
     * construction takes one, and the four-component form's year slots answer with their own class. A parse error
     * anywhere answers first, in both text orders and in both constructions.
     */
    @Test
    void aStampSplitIsTheSlotsRefusalAndAParseErrorAnswersFirst() {
        Calculation dateOfStamp = Dsl.dateFromDateTime(M.dateTime("/Term/SignedAt")).expression();
        Calculation timeOfStamp = Dsl.timeFromDateTime(M.dateTime("/Term/SignedAt")).expression();
        Calculation aggregate = Dsl.maxValue(M.flatten("/Term/Claims*/HourNo")).expression();
        Calculation constant = new StringLiteral("01");
        Map<String, Supplier<Object>> built = new LinkedHashMap<>();
        built.put("a day slot", () -> M.dateOf(dateOfStamp, constant, new StringLiteral("2024")));
        built.put("a century slot", () -> M.dateOf(constant, constant, timeOfStamp, new StringLiteral("24")));
        built.put("a decade slot", () -> M.dateOf(constant, constant, new StringLiteral("20"), dateOfStamp));
        built.put("a time slot", () -> new TimeConstruction(List.of(constant, timeOfStamp)));
        built.put("Date, the split first", () -> M.dateOf(dateOfStamp, constant, aggregate));
        built.put("Date, the parse error first", () -> M.dateOf(aggregate, constant, dateOfStamp));
        built.put("Time, a misplaced extractor first", () -> new TimeConstruction(
                List.of(Dsl.minutesFromTime(M.time("/Term/RenewalClock")).expression(), aggregate)));
        Map<String, String> actual = new LinkedHashMap<>();
        built.forEach((name, build) -> actual.put(name,
                assertThrows(KernelCodeRefusal.class, build::get, name).code().name()));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a day slot", "MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED");
        expected.put("a century slot", "MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS");
        expected.put("a decade slot", "MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS");
        expected.put("a time slot", "MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED");
        expected.put("Date, the split first", "MVK_UNEXPECTED_TOKEN");
        expected.put("Date, the parse error first", "MVK_UNEXPECTED_TOKEN");
        expected.put("Time, a misplaced extractor first", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual);
    }

    /** The admitted arms, so the refusal above cannot be passing on a construction that refuses everything. */
    @Test
    void theGrammarsOwnArmsAreBuilt() {
        Dsl.time(HOUR.field());
        Dsl.time(Dsl.temporalComponent("10"));
        Dsl.time(Dsl.number("10"));
        Dsl.time(Dsl.hoursFromTime(M.time("/Term/RenewalClock")));
        Dsl.time(M.str("/Term/Claims/Code").semanticIndex(M.field("/Term/Key")));
        Dsl.date(Dsl.dayFromDate(M.date("/Term/StartOn")), Dsl.temporalComponent("01"), Dsl.temporalComponent("2024"));
        // any typed literal renders as a quoted constant, which the grammar takes; its value is the slot gate's call
        M.dateOf(new DateLiteral("2024-01-01"), new StringLiteral("01"), new StringLiteral("2024"));
    }
}
