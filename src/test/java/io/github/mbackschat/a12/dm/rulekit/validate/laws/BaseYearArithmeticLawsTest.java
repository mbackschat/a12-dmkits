package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/** Kernel authoring lock: numeric BaseYear still requires model configuration. */
class BaseYearArithmeticLawsTest {

    @Test
    void numericBaseYearIsRejectedWithoutConfiguredBaseYear() {
        String json = DocumentModelDraft.named("forecast").locale("en_US")
                .rootGroup("Forecast", group -> group.numberField("Projected", field -> field.fractionDigits(0)))
                .build().toJson();
        BoundModel model = BoundModel.ofJson(json);
        var computation = model.computation(model.num("/Forecast/Projected"))
                .name("ProjectedBaseYear")
                .calc(Dsl.baseYearNumber().expression())
                .message(Dsl.en("Projected base year."))
                .build();

        var result = new RuleValidator().check(json, computation.draft());

        assertFalse(result.ok());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().filter("MVK_NO_BASE_YEAR"::equals).isPresent()));
    }

    @Test
    void numericWrapperAcceptsImmediateBaseYearAndItsDerivedYearComponent() {
        String json = DocumentModelDraft.named("configured-forecast").locale("en_US").baseYear(2020)
                .rootGroup("Forecast", group -> group.numberField("Projected", field -> field.fractionDigits(0)))
                .build().toJson();
        BoundModel model = BoundModel.ofJson(json);
        var direct = model.computation(model.num("/Forecast/Projected"))
                .name("RoundedBaseYear")
                .calc(Dsl.roundDown(Dsl.baseYearNumber()).expression())
                .message(Dsl.en("Rounded base year."))
                .build();
        var derived = model.computation(model.num("/Forecast/Projected"))
                .name("RoundedDerivedYear")
                .calc(Dsl.roundDown(Dsl.yearFromDate(Dsl.baseYear())).expression())
                .message(Dsl.en("Rounded derived year."))
                .build();
        var validator = new RuleValidator();

        assertTrue(validator.check(json, direct.draft()).ok());
        assertTrue(validator.check(json, derived.draft()).ok());
    }
}
