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
 * F10 ingest tail — the string coercions {@code FieldValueAsString(field)} and
 * {@code RangeAsString(field, start, end)} (kernel {@code FieldIdOperationName}). They appear on the
 * left of a comparison ({@code RangeAsString(Code, 1, 3) != "BD_"}), on the right
 * ({@code [AsString] != FieldValueAsString(Field)}), and as concatenation parts. Own domain: a product
 * with an ERP code prefix and a generated string form of its quantity.
 */
class StringCoercionIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String productJson() {
        return DocumentModelDraft.named("product").locale("en_US")
                .rootGroup("Product", p -> p
                        .stringField("ErpCode", s -> { })
                        .stringField("Prefix", s -> { })
                        .numberField("Quantity", n -> { })
                        .stringField("QuantityText", s -> { }))
                .build().toJson();
    }

    @Test
    void roundTripsRangeAsStringOnTheLeftAndFieldValueAsStringOnTheRight() {
        BoundModel m = BoundModel.ofJson(productJson());
        // RangeAsString on the LEFT vs a constant
        Condition rangeLeft = Dsl.rangeAsString(m.field("/Product/ErpCode"), 1, 3).ne("BD_");
        // FieldValueAsString on the RIGHT of a string-field comparison
        Condition asStringRight = m.str("/Product/QuantityText").ne(Dsl.fieldValueAsString(m.field("/Product/Quantity")));
        // RangeAsString as a concatenation part
        Condition concatPart = m.str("/Product/ErpCode")
                .ne(Dsl.rangeAsString(m.field("/Product/Prefix"), 1, 2).plus(m.str("/Product/ErpCode").asString()));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "RangeLeftRule", "/Product/ErpCode", rangeLeft))
                .add(rule(m, "AsStringRule", "/Product/QuantityText", asStringRight))
                .add(rule(m, "ConcatPartRule", "/Product/ErpCode", concatPart)));
        assertEquals(RENDER.render(rangeLeft), RENDER.render(converted(adapter.ingest("/Product/RangeLeftRule"))));
        assertEquals(RENDER.render(asStringRight), RENDER.render(converted(adapter.ingest("/Product/AsStringRule"))));
        assertEquals(RENDER.render(concatPart), RENDER.render(converted(adapter.ingest("/Product/ConcatPartRule"))));
    }

    @Test
    void stringCoercionsAreKernelValid() {
        ValidationResult r = new RuleValidator().check(productJson(),
                new DraftRule("/Product", "RangeLeftRule", "../ErpCode",
                        "FieldFilled(ErpCode) And RangeAsString(ErpCode, 1, 3) != \"BD_\"", "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Product")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }
}
