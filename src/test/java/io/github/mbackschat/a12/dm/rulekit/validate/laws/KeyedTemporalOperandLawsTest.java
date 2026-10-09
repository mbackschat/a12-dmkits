package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;

/**
 * Whether a key-selected cell stands where a temporal operation reads one field: the operand of
 * {@code ValueAsDate} and a component of a {@code Date} construction. Each keyed row has its unkeyed control
 * read from the same rule, which crosses the repetition without a key, and a keyed Number component is the
 * control the typed surface already expressed before `SemanticIndex` became a component and a `ValueAsDate` source.
 *
 * <p>Own domain (an insurance claims register).
 */
class KeyedTemporalOperandLawsTest {

    private static final String REGISTER = DocumentModelDraft.named("claims-register").locale("en_US")
            .rootGroup("Register", r -> r
                    .stringField("Holder", f -> { })
                    .stringField("Key", f -> { })
                    .dateField("Limit", f -> f.format("yyyy-MM-dd"))
                    .group("Claims", g -> g.repeatable(5).indexField("Code")
                            .stringField("Code", f -> { })
                            .dateField("ReportedAbout",
                                    f -> f.format("yyyy-MM-dd").datePrecision(DatePrecision.MONTH_OPTIONAL))
                            .stringField("DayText", f -> f.maxLength(2).pattern("[0-9]+", "Digits only."))
                            .numberField("DayNumber", f -> f.fractionDigits(0).min("1").max("31"))))
            .build().toJson();

    private static String verdict(String comparison) {
        return KernelLaws.verdictModel(REGISTER, "/Register", "../Holder",
                "FieldFilled(Holder) And " + comparison + " > [Limit]");
    }

    @Test
    void aKeySelectedCellStandsWhereATemporalOperationReadsOneField() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("ValueAsDate over a field-keyed cell", verdict("ValueAsDate(Claims/ReportedAbout For Key, FirstDay)"));
        actual.put("ValueAsDate over a literal-keyed cell", verdict("ValueAsDate(Claims/ReportedAbout For \"C1\", LastDay)"));
        actual.put("ValueAsDate over the unkeyed list", verdict("ValueAsDate(Claims/ReportedAbout, FirstDay)"));
        actual.put("a field-keyed text day", verdict("Date(Claims/DayText For Key, \"01\", \"2024\")"));
        actual.put("a literal-keyed text day", verdict("Date(Claims/DayText For \"C1\", \"01\", \"2024\")"));
        actual.put("the unkeyed text day", verdict("Date(Claims/DayText, \"01\", \"2024\")"));
        actual.put("a field-keyed number day", verdict("Date(Claims/DayNumber For Key, \"01\", \"2024\")"));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ValueAsDate over a field-keyed cell", KernelLaws.ADMITTED);
        expected.put("ValueAsDate over a literal-keyed cell", KernelLaws.ADMITTED);
        expected.put("ValueAsDate over the unkeyed list", "MVK_NO_WILDCARD");
        expected.put("a field-keyed text day", KernelLaws.ADMITTED);
        expected.put("a literal-keyed text day", KernelLaws.ADMITTED);
        expected.put("the unkeyed text day", "MVK_NO_WILDCARD");
        expected.put("a field-keyed number day", KernelLaws.ADMITTED);
        assertEquals(expected, actual);
    }

    /** The admitted keyed shapes, authored through the typed surface: each renders the text measured above. */
    @Test
    void theTypedSurfaceAuthorsEveryAdmittedKeyedShape() {
        BoundModel m = BoundModel.ofJson(REGISTER);
        FieldRef key = m.field("/Register/Key");
        DateRef reported = m.date("/Register/Claims/ReportedAbout");
        StringRef dayText = m.str("/Register/Claims/DayText");
        Condition held = m.str("/Register/Holder").filled();
        DateRef limit = m.date("/Register/Limit");
        Map<String, Condition> shapes = new LinkedHashMap<>();
        shapes.put("ValueAsDate(Claims/ReportedAbout For Key, FirstDay)",
                m.valueAsDateFirstDay(reported.semanticIndex(key)).after(limit));
        shapes.put("ValueAsDate(Claims/ReportedAbout For \"C1\", LastDay)",
                m.valueAsDateLastDay(reported.semanticIndex("C1")).after(limit));
        shapes.put("Date(Claims/DayText For Key, \"01\", \"2024\")",
                Dsl.date(dayText.semanticIndex(key), Dsl.temporalComponent("01"), Dsl.temporalComponent("2024"))
                        .after(limit));
        shapes.put("Date(Claims/DayText For \"C1\", \"01\", \"2024\")",
                Dsl.date(dayText.semanticIndex("C1"), Dsl.temporalComponent("01"), Dsl.temporalComponent("2024"))
                        .after(limit));
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        shapes.forEach((operand, comparison) -> {
            Condition condition = held.and(comparison);
            actual.put(operand, KernelLaws.render("/Register", condition) + " | "
                    + KernelLaws.verdictModel(REGISTER, "/Register", "../Holder", condition));
            expected.put(operand, "FieldFilled(Holder) And " + operand + " > [Limit] | " + KernelLaws.ADMITTED);
        });
        assertEquals(expected, actual);
    }
}
