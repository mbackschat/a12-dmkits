package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A key-selected cell in a temporal slot reads back as the typed operand that authored it: the source of
 * {@code ValueAsDate} and a component of a {@code Date} or {@code Time} construction (admission measured by
 * {@code validate.laws.KeyedTemporalOperandLawsTest}). Each shape is authored, injected into the kernel model and
 * converted back, and the read must equal the authored object, not only its rendering.
 *
 * <p>Own domain (an insurance claims register).
 */
class KeyedTemporalOperandReadTest {

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
                            .stringField("MonthText", f -> f.maxLength(2).pattern("[0-9]+", "Digits only."))
                            .stringField("YearText", f -> f.maxLength(4).pattern("[0-9]+", "Digits only."))
                            .stringField("HourText", f -> f.maxLength(2).pattern("[0-9]+", "Digits only."))))
            .build().toJson();

    @Test
    void aKeyedTemporalOperandReadsBackAsTheOperandThatAuthoredIt() {
        BoundModel m = BoundModel.ofJson(REGISTER);
        FieldRef key = m.field("/Register/Key");
        DateRef reported = m.date("/Register/Claims/ReportedAbout");
        StringRef dayText = m.str("/Register/Claims/DayText");
        DateRef limit = m.date("/Register/Limit");
        Condition held = m.str("/Register/Holder").filled();
        Map<String, Condition> authored = new LinkedHashMap<>();
        authored.put("FieldKeyedFirstDay", held.and(m.valueAsDateFirstDay(reported.semanticIndex(key)).after(limit)));
        authored.put("LiteralKeyedLastDay", held.and(m.valueAsDateLastDay(reported.semanticIndex("C1")).after(limit)));
        authored.put("FieldKeyedDay", held.and(
                Dsl.date(dayText.semanticIndex(key), Dsl.temporalComponent("01"), Dsl.temporalComponent("2024"))
                        .after(limit)));
        authored.put("FieldKeyedMonth", held.and(Dsl.date(Dsl.temporalComponent("01"),
                m.str("/Register/Claims/MonthText").semanticIndex(key), Dsl.temporalComponent("2024")).after(limit)));
        authored.put("FieldKeyedYear", held.and(Dsl.date(Dsl.temporalComponent("01"), Dsl.temporalComponent("01"),
                m.str("/Register/Claims/YearText").semanticIndex(key)).after(limit)));
        authored.put("FieldKeyedHour", held.and(Dsl.time(m.str("/Register/Claims/HourText").semanticIndex(key))
                .after(Dsl.timeValue("10:00:00"))));
        BoundModel withRules = m;
        for (Map.Entry<String, Condition> rule : authored.entrySet()) {
            withRules = withRules.add(m.rule(m.group("/Register")).name(rule.getKey())
                    .errorField(m.field("/Register/Holder")).when(rule.getValue())
                    .code("C").severity(Severity.ERROR).message(en("Check the claim.")).build());
        }
        KernelAdapter adapter = KernelAdapter.of(withRules);
        Map<String, Condition> read = new LinkedHashMap<>();
        authored.keySet().forEach(name -> read.put(name, assertInstanceOf(ReadResult.Converted.class,
                adapter.read("/Register/" + name)).condition()));
        assertEquals(authored, read);
    }
}
