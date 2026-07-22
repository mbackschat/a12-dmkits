package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/** F10 roundtrip for operand-list {@code Min}/{@code Max} over every date-like value kind. */
class TemporalOperandMinMaxReadTest {

    private static final DslRenderer RENDER = new DslRenderer();

    private static String modelJson() {
        return DocumentModelDraft.named("temporal-extrema")
                .locale("en_US")
                .rootGroup("Schedule", s -> s
                        .dateField("DateA", d -> d.format("yyyy-MM-dd"))
                        .dateField("DateB", d -> d.format("yyyy-MM-dd"))
                        .dateField("DateLimit", d -> d.format("yyyy-MM-dd"))
                        .timeField("TimeA", t -> t.format("HH:mm:ss"))
                        .timeField("TimeB", t -> t.format("HH:mm:ss"))
                        .timeField("TimeLimit", t -> t.format("HH:mm:ss"))
                        .dateTimeField("StampA", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateTimeField("StampB", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateTimeField("StampLimit", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss")))
                .build().toJson();
    }

    @Test
    void roundTripsTemporalOperandListsOnEitherSideOfAComparison() {
        BoundModel m = BoundModel.ofJson(modelJson());
        Map<String, Condition> cases = new LinkedHashMap<>();
        cases.put("DateMinLeft", Dsl.min(m.date("/Schedule/DateA"), m.date("/Schedule/DateB"))
                .before(m.date("/Schedule/DateLimit")));
        cases.put("DateMaxRight", m.date("/Schedule/DateLimit")
                .after(Dsl.max(m.date("/Schedule/DateA"), m.date("/Schedule/DateB"))));
        cases.put("DateNested", Dsl.min(Dsl.min(Dsl.dateValue("01.01.2026")),
                m.date("/Schedule/DateA")).before(m.date("/Schedule/DateLimit")));
        cases.put("TimeMinLeft", Dsl.min(m.time("/Schedule/TimeA"), m.time("/Schedule/TimeB"))
                .before(m.time("/Schedule/TimeLimit")));
        cases.put("TimeMaxRight", m.time("/Schedule/TimeLimit")
                .after(Dsl.max(m.time("/Schedule/TimeA"), m.time("/Schedule/TimeB"))));
        cases.put("TimeConstructed", Dsl.min(m.time("/Schedule/TimeA"), Dsl.time("09", "00", "00"))
                .before(m.time("/Schedule/TimeLimit")));
        cases.put("StampMinLeft", Dsl.min(m.dateTime("/Schedule/StampA"), m.dateTime("/Schedule/StampB"))
                .before(m.dateTime("/Schedule/StampLimit")));
        cases.put("StampMaxRight", m.dateTime("/Schedule/StampLimit")
                .after(Dsl.max(m.dateTime("/Schedule/StampA"), m.dateTime("/Schedule/StampB"))));
        cases.put("StampConstructed", Dsl.max(m.dateTime("/Schedule/StampA"),
                Dsl.dateTime(m.date("/Schedule/DateA"), m.time("/Schedule/TimeA")))
                .before(m.dateTime("/Schedule/StampLimit")));

        BoundModel withRules = m;
        for (var entry : cases.entrySet()) {
            withRules = withRules.add(rule(m, entry.getKey(), errorField(entry.getKey()), entry.getValue()));
        }
        KernelAdapter adapter = KernelAdapter.of(withRules);

        cases.forEach((name, expected) -> assertEquals(RENDER.render(expected),
                RENDER.render(converted(adapter.read("/Schedule/" + name))),
                () -> "temporal operand-list roundtrip mismatch for " + name));
    }

    private static String errorField(String name) {
        if (name.startsWith("Date")) {
            return "/Schedule/DateLimit";
        }
        if (name.startsWith("Time")) {
            return "/Schedule/TimeLimit";
        }
        return "/Schedule/StampLimit";
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition condition) {
        return m.rule(m.group("/Schedule")).name(name).errorField(m.field(errorField)).when(condition)
                .code(name).severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
