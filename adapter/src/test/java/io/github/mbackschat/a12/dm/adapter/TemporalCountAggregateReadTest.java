package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — {@code NumberOfDifferentValues} over <b>date-like</b> operands. The kernel admits DATE, TIME
 * and DATETIME here (its own gate is named {@code MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED}), and unlike
 * {@code MaxValue}/{@code MinValue} there is no separate date-family operator for a count of distinct values.
 *
 * <p><b>Why this file exists.</b> {@code CountAggregate} used to refuse every temporal operand, and
 * {@code ConditionConverter} builds the same record on the way IN — so a perfectly legal kernel model using this
 * form could not be READ, not merely not authored. That is the sharper half of the over-rejection fixed on
 * 2026-07-25 (MVK-LEDGER pre-checked audit), and a read test is the only thing that would have caught it.
 *
 * <p>Own domain: a clinic auditing whether a patient's appointment slots fall on distinct days.
 */
class TemporalCountAggregateReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    // Model JSON + bind are built ONCE for the class, not per case: this file shares :adapter:test with
    // timing-sensitive growth floors (CharsetPerfTest), and per-case kernel work inflates their medians —
    // it failed that floor at 10.74x against 9.0x before this was hoisted. See CLAUDE.md, "A new test must not
    // perturb timing-sensitive floors that share its task".
    private static final String CLINIC_JSON = DocumentModelDraft.named("clinic").locale("en_US")
            .rootGroup("Visit", v -> v
                    .field("SlotCount", io.github.mbackschat.a12.dm.modelkit.FieldKind.NUMBER)
                    .dateField("BookedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("ConfirmedOn", d -> d.format("yyyy-MM-dd")))
            .build().toJson();

    @Test
    void roundTripsACountOfDistinctDates() {
        BoundModel m = BoundModel.ofJson(CLINIC_JSON);
        Condition cond = Dsl.fieldFilled(m.field("/Visit/SlotCount"))
                .and(Dsl.numberOfDifferentValues(m.field("/Visit/BookedOn"), m.field("/Visit/ConfirmedOn")).gt(1));

        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "DistinctDayRule", "/Visit/SlotCount", cond)));
        assertEquals(RENDER.render(cond), RENDER.render(converted(adapter.read("/Visit/DistinctDayRule"))));
    }

    @Test
    void aCountOfDistinctDatesIsKernelValid() {
        ValidationResult r = new RuleValidator().check(CLINIC_JSON,
                new DraftRule("/Visit", "DistinctDayRule", "../SlotCount",
                        "FieldFilled(SlotCount) And NumberOfDifferentValues(BookedOn, ConfirmedOn) > 1",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Visit")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
