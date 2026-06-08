package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * F10 ingest for the custom-field-type validity condition: a rule using {@code Valid(field, "Type")} /
 * {@code Invalid(field, "Type")} round-trips (author → render → kernel parse → convert → render is the
 * identity) — proving {@code IPredefinedTypeValidityCondition} converts to the typed
 * {@code CustomTypeValidity} node.
 */
class CustomTypeValidityIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);
    private static final String NAME = "/Order/CustomerName";

    private static BoundModel order() {
        return BoundModel.ofJson(read("models/order.dm.json"));
    }

    @Test
    void ingestsInvalidTypeAsAnIdentityRoundTrip() {
        BoundModel m = order();
        Condition cond = m.field(NAME).filled().and(Dsl.invalidType(m.field(NAME), "Steuernummer"));
        Rule rule = m.rule(m.group("/Order")).name("TaxIdRule").errorField(m.field(NAME))
                .when(cond).code("TAX_ID").severity(Severity.ERROR)
                .message(en("Customer name is not a valid Steuernummer.")).build();

        KernelAdapter adapter = KernelAdapter.of(m.add(rule));
        Ingest ingest = adapter.ingest("/Order/TaxIdRule");
        Condition converted = assertInstanceOf(Ingest.Converted.class, ingest,
                () -> "expected a converted ingest, got: " + ingest).condition();
        assertEquals(RENDER.render(cond), RENDER.render(converted));
    }

    @Test
    void ingestsValidTypeAsAnIdentityRoundTrip() {
        BoundModel m = order();
        Condition cond = Dsl.validType(m.field(NAME), "EMail");
        Rule rule = m.rule(m.group("/Order")).name("NameTypeRule").errorField(m.field(NAME))
                .when(cond).code("NAME_TYPE").severity(Severity.ERROR)
                .message(en("Customer name must be a valid EMail.")).build();

        KernelAdapter adapter = KernelAdapter.of(m.add(rule));
        Condition converted = assertInstanceOf(Ingest.Converted.class, adapter.ingest("/Order/NameTypeRule"),
                () -> "expected a converted ingest").condition();
        assertEquals(RENDER.render(cond), RENDER.render(converted));
    }

    private static String read(String path) {
        try (InputStream in = CustomTypeValidityIngestTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
