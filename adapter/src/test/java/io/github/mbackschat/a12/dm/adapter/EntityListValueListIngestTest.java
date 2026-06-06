package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 ingest tail — the field-list × value-list quantifiers (kernel {@code IEntityListAndValueListCondition}):
 * "(at least one / no / not all) of these fields' values are in this value list". The DSL already has the
 * family; this wires the read direction. Covered: plain string- and enum-field lists. (Entity-valued value
 * lists, mixed-kind lists, wildcard reps, and the unmodeled date-range-overlap / value-consistency names
 * stay opaque.) String variant uses the order fixture's two root strings; enum variant a modelkit-authored
 * model with two typeDef enums.
 */
class EntityListValueListIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    @Test
    void roundTripsStringFieldListValueListQuantifiers() {
        BoundModel m = BoundModel.ofJson(read("models/order.dm.json"));
        List<String> values = List.of("ACME", "BETA");
        StringRef name = m.str("/Order/CustomerName");
        StringRef code = m.str("/Order/ProductCode");

        Map<String, Condition> cases = new LinkedHashMap<>();
        BoundModel withRules = m
                .add(orderRule(m, "AtLeastOneInListRule",
                        Dsl.atLeastOneFieldValueIncludedInValueList(values, name, code), cases))
                .add(orderRule(m, "NoneInListRule",
                        Dsl.noFieldValueIncludedInValueList(values, name, code), cases))
                .add(orderRule(m, "NotAllInListRule",
                        Dsl.notAllFieldValuesIncludedInValueList(values, name, code), cases));

        KernelAdapter adapter = KernelAdapter.of(withRules);
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.ingest(fullName))),
                        () -> "entity+value-list round-trip mismatch for " + fullName));
    }

    @Test
    void roundTripsEnumFieldListValueListQuantifier() {
        BoundModel m = BoundModel.ofJson(approvalsJson());
        EnumRef requested = m.enumOf("/Approval/Requested");
        EnumRef granted = m.enumOf("/Approval/Granted");
        Condition c = Dsl.noFieldValueIncludedInValueList(List.of("FINAL"), requested, granted);

        Rule r = m.rule(m.group("/Approval")).name("NoFinalRule").errorField(m.field("/Approval/Requested"))
                .when(c).code("C").severity(Severity.ERROR).message(en("x")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(r));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.ingest("/Approval/NoFinalRule"))));
    }

    @Test
    void theFieldListValueListConditionIsKernelValid() {
        BoundModel m = BoundModel.ofJson(read("models/order.dm.json"));
        Condition c = Dsl.atLeastOneFieldValueIncludedInValueList(List.of("ACME", "BETA"),
                m.str("/Order/CustomerName"), m.str("/Order/ProductCode"));
        ValidationResult r = new RuleValidator().check(read("models/order.dm.json"),
                new DraftRule("/Order", "AtLeastOneInListRule", "../CustomerName", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static String approvalsJson() {
        return DocumentModelDraft.named("approvals").locale("en_US")
                .typeDef("Status", t -> t.enumeration(e -> e.value("DRAFT").value("FINAL")))
                .rootGroup("Approval", a -> a
                        .typedefField("Requested", "Status")
                        .typedefField("Granted", "Status"))
                .build().toJson();
    }

    private static Rule orderRule(BoundModel m, String name, Condition cond, Map<String, Condition> sink) {
        Rule r = m.rule(m.group("/Order")).name(name).errorField(m.field("/Order/CustomerName")).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
        sink.put("/Order/" + name, cond);
        return r;
    }

    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = EntityListValueListIngestTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
