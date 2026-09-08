package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * KERNEL-FINDINGS §6 — {@code BaseYear} needs the model to DECLARE a base year, across every carrier.
 *
 * <p>{@code BaseYearArithmeticLawsTest} measures one carrier, the numeric role inside a computation, and
 * {@code BaseYear}'s own javadoc delegates the rule as "the kernel's call". Its sibling
 * {@code BaseYearExtractionFormatLawsTest} refutes a FORMAT gate over {@code BaseYear} — and every row of that
 * refutation declares a base year, so configuration is the axis it holds constant. This file varies it, over
 * every carrier the DSL publishes.
 *
 * <p>The control is within-model: the two fixtures differ in exactly one builder call, {@code .baseYear(2024)},
 * and each carrier is asserted admitted on the configured one, so a refusal below cannot be the shape's own.
 *
 * <p>Conditions are built through the typed DSL and rendered, never hand-spelled: a date compared to a
 * point-in-time constant has a canonical rendering that guesswork does not reproduce, and a mis-spelled
 * condition reports {@code MVK_UNEXPECTED_TOKEN}, which masks the verdict under test.
 */
class BaseYearDeclarationLawsTest {

    private static final String NO_BASE_YEAR = "MVK_NO_BASE_YEAR";

    private static String json(Integer baseYear) {
        var builder = DocumentModelDraft.named(
                baseYear == null ? "cover-unconfigured" : "cover-configured").locale("en_US");
        if (baseYear != null) {
            builder.baseYear(baseYear);
        }
        return builder
                .rootGroup("Cover", c -> c
                        .field("N", FieldKind.NUMBER)
                        .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))
                        .dateTimeField("Stamp", d -> d.format("yyyy-MM-dd'T'HH:mm:ss")))
                .build().toJson();
    }

    private static final String UNCONFIGURED_JSON = json(null);
    private static final String CONFIGURED_JSON = json(2024);
    private static final BoundModel UNCONFIGURED = BoundModel.ofJson(UNCONFIGURED_JSON);
    private static final BoundModel CONFIGURED = BoundModel.ofJson(CONFIGURED_JSON);

    /**
     * Each carrier as a function of its model, so the same expression is built twice — once per fixture —
     * rather than shared across them. The error field is referenced by the trailing conjunct, without which
     * {@code MVK_ERROR_FIELD_NOT_REFERENCED} masks every row.
     */
    private static final Map<String, BiFunction<BoundModel, String, Condition>> CARRIERS = carriers();

    private static Map<String, BiFunction<BoundModel, String, Condition>> carriers() {
        var carriers = new LinkedHashMap<String, BiFunction<BoundModel, String, Condition>>();
        carriers.put("date role", (m, p) -> Dsl.baseYear().eq(m.date(p + "/FiledOn")));
        carriers.put("numeric role", (m, p) -> Dsl.baseYearNumber().gt(2000));
        carriers.put("extraction over it", (m, p) -> Dsl.yearFromDate(Dsl.baseYear()).gt(2000));
        carriers.put("start of range", (m, p) -> Dsl.startOfBaseYear().eq(m.date(p + "/FiledOn")));
        carriers.put("end of range", (m, p) -> Dsl.endOfBaseYear().eq(m.date(p + "/FiledOn")));
        // The DATETIME role exists too: :adapter's converter reconstructs one, so the denominator must cover
        // it or the gate below would claim a carrier nothing measured.
        carriers.put("datetime role", (m, p) -> Dsl.baseYear().before(m.dateTime(p + "/Stamp")));
        return carriers;
    }

    private static Rule rule(BoundModel model, String name, Condition carrier) {
        return model.rule(model.group("/Cover"))
                .name(name)
                .errorField(model.field("/Cover/N"))
                .when(carrier.and(Dsl.fieldFilled(model.field("/Cover/N"))))
                .code("BASE_YEAR")
                .severity(Severity.ERROR)
                .message(Dsl.en("Base-year declaration probe."))
                .build();
    }

    private static String verdict(String modelJson, BoundModel model, String name,
            BiFunction<BoundModel, String, Condition> carrier) {
        var result = new io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator()
                .check(modelJson, rule(model, name, carrier.apply(model, "/Cover")).draft());
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /**
     * The COMPUTATION surface. The gate sits on the factory, so it fires for a computation as well as a rule,
     * and every row of the table below is a rule — this is the carrier that would otherwise be a scope
     * transfer.
     */
    @Test
    void theComputationSurfaceNeedsItToo() {
        var declared = CONFIGURED.computation(CONFIGURED.num("/Cover/N"))
                .name("YearOut").calc(Dsl.baseYearNumber().expression())
                .message(Dsl.en("Base year out.")).build();
        KernelLaws.acceptsModel(CONFIGURED_JSON, declared);
        KernelLaws.rejectsModel(UNCONFIGURED_JSON, declared, NO_BASE_YEAR);
    }

    /** One table, so a kernel shift shows the whole picture rather than aborting at the first cell. */
    @Test
    void everyBaseYearCarrierNeedsADeclaredBaseYear() {
        var actual = new LinkedHashMap<String, String>();
        CARRIERS.forEach((name, carrier) -> {
            actual.put(name + " / unconfigured", verdict(UNCONFIGURED_JSON, UNCONFIGURED, "Probe", carrier));
            actual.put(name + " / configured", verdict(CONFIGURED_JSON, CONFIGURED, "Probe", carrier));
        });

        Map<String, String> expected = new LinkedHashMap<>();
        CARRIERS.keySet().forEach(name -> {
            expected.put(name + " / unconfigured", NO_BASE_YEAR);
            expected.put(name + " / configured", KernelLaws.ADMITTED);
        });

        assertEquals(expected, actual);
    }
}
