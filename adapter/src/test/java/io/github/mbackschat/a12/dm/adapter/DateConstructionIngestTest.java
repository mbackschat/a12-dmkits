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
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 ingest tail — {@code Date(day, month, year[, decade])} construction + {@code Valid}/{@code Invalid}
 * over it (the kernel's {@code IDateConstructionOperation} / {@code IDateValidityCondition}). The kernel
 * requires <em>bounded-integer</em> component fields (§6, {@code MVK_WRONG_DATE_FORMAT_FOR_OP}). Own
 * domain: a registration form whose birth-date parts are entered as separate day/month/year numbers.
 */
class DateConstructionIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String registrationJson() {
        return DocumentModelDraft.named("registration").locale("en_US")
                .rootGroup("Registration", r -> r
                        .numberField("BirthDay", n -> n.min("1").max("31"))
                        .numberField("BirthMonth", n -> n.min("1").max("12"))
                        .numberField("BirthYear", n -> n.min("1900").max("2100"))
                        .dateField("BirthDate", d -> d.format("yyyy-MM-dd"))
                        .dateFragmentField("ValidMonth", f -> f.fragmentFormat("yyyy-MM")))
                .build().toJson();
    }

    @Test
    void dateConstructionAndValidityAreKernelValid() {
        String model = registrationJson();
        var cases = new String[] {
            "FieldFilled(BirthDay) And [BirthDate] != Date(BirthDay, BirthMonth, BirthYear)",
            "FieldFilled(BirthDay) And Valid(Date(BirthDay, BirthMonth, BirthYear))",
            "FieldFilled(BirthDay) And Invalid(Date(BirthDay, BirthMonth, BirthYear))",
        };
        for (String dsl : cases) {
            ValidationResult r = new RuleValidator().check(model,
                    new DraftRule("/Registration", "R", "../BirthDay", dsl, "C", "ERROR"));
            assertTrue(r.ok(), () -> "expected kernel acceptance for: " + dsl + " — got: " + r.diagnostics());
        }
    }

    @Test
    void roundTripsAComponentExtractionFromADateFragment() {
        // the corpus shape: MonthFromDate/YearFromDate read a DATE_FRAGMENT source (a year-month partial date)
        BoundModel m = BoundModel.ofJson(registrationJson());
        Condition full = m.num("/Registration/BirthMonth").filled()
                .and(m.num("/Registration/BirthMonth").ne(Dsl.monthFromDate(m.dateFragment("/Registration/ValidMonth"))));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FragRule", "/Registration/BirthMonth", full)));
        assertTrue(RENDER.render(full).contains("MonthFromDate(/Registration/ValidMonth)"), RENDER.render(full));
        assertEquals(RENDER.render(full), RENDER.render(converted(adapter.ingest("/Registration/FragRule"))));
    }

    @Test
    void roundTripsDateConstructionAndValidity() {
        BoundModel m = BoundModel.ofJson(registrationJson());
        Dsl_ d = new Dsl_(m);
        Condition cmp = m.date("/Registration/BirthDate").filled().and(
                m.date("/Registration/BirthDate").ne(d.birthDate()));
        Condition valid = m.num("/Registration/BirthDay").filled().and(Dsl.valid(d.birthDate()));
        Condition invalid = m.num("/Registration/BirthDay").filled().and(Dsl.invalid(d.birthDate()));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "CmpRule", "/Registration/BirthDate", cmp))
                .add(rule(m, "ValidRule", "/Registration/BirthDay", valid))
                .add(rule(m, "InvalidRule", "/Registration/BirthDay", invalid)));
        assertEquals(RENDER.render(cmp), RENDER.render(converted(adapter.ingest("/Registration/CmpRule"))));
        assertEquals(RENDER.render(valid), RENDER.render(converted(adapter.ingest("/Registration/ValidRule"))));
        assertEquals(RENDER.render(invalid), RENDER.render(converted(adapter.ingest("/Registration/InvalidRule"))));
    }

    /** Tiny helper: the Date(BirthDay, BirthMonth, BirthYear) construction over the registration fixture. */
    private record Dsl_(BoundModel m) {
        io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr birthDate() {
            return Dsl.date(m.num("/Registration/BirthDay").asNumber(),
                    m.num("/Registration/BirthMonth").asNumber(), m.num("/Registration/BirthYear").asNumber());
        }
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Registration")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }
}
