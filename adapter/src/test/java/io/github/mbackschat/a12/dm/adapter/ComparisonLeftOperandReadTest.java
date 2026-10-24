package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * Left operands the reader did not dispatch: a numeric literal before a count (a12-kernel-lean's
 * `src-mandatory-information-distinct-comparisons` capture read six such rules back {@code Opaque}), a text
 * concatenation before a field or a text, and a text before a concatenation, a string coercion or a text first-filled
 * value. Each is authored, admitted by the kernel's consistency gate and converted back;
 * the read must equal the authored object, so the authored operand order survives.
 *
 * <p>Own domain (a web shop order).
 */
class ComparisonLeftOperandReadTest {

    private static final String ORDER = DocumentModelDraft.named("shop-order-left-operand").locale("en_US")
            .rootGroup("Order", o -> o
                    .stringField("FirstName", f -> { })
                    .stringField("LastName", f -> { })
                    .stringField("ShipTo", f -> { })
                    .group("Lines", l -> l.repeatable(5)
                            .stringField("Sku", f -> { })))
            .build().toJson();

    @Test
    void everyLeftOperandReadsBackAsAuthored() {
        BoundModel m = BoundModel.ofJson(ORDER);
        NumberExpr skus = Dsl.numberOfDifferentValues(m.flatten("/Order/Lines*/Sku"));
        Condition named = m.str("/Order/ShipTo").filled();
        Map<String, Condition> authored = new LinkedHashMap<>();
        authored.put("LiteralBelowCount", named.and(Dsl.number(0).lt(skus)));
        authored.put("LiteralAtMostCount", named.and(Dsl.number(1).le(skus)));
        authored.put("NegativeLiteralBelowCount", named.and(Dsl.number(-2).lt(skus)));
        authored.put("ConcatenationEqualsField", named.and(m.str("/Order/FirstName").plus(" ")
                .plus(m.str("/Order/LastName").asString()).eq(m.str("/Order/ShipTo").asString())));
        authored.put("ConcatenationDiffersFromText", named.and(m.str("/Order/FirstName").plus(" ")
                .plus(m.str("/Order/LastName").asString()).ne("Guest Buyer")));
        authored.put("TextDiffersFromConcatenation", named.and(Dsl.text("Guest Buyer")
                .ne(m.str("/Order/FirstName").plus(" ").plus(m.str("/Order/LastName").asString()))));
        authored.put("TextEqualsInitials", named.and(Dsl.text("Gu")
                .eq(Dsl.rangeAsString(m.field("/Order/FirstName"), 1, 2))));
        authored.put("TextDiffersFromFirstSku", named.and(Dsl.text("X1")
                .ne(Dsl.firstFilledString(m.flatten("/Order/Lines*/Sku")))));
        BoundModel withRules = m;
        for (Map.Entry<String, Condition> rule : authored.entrySet()) {
            withRules = withRules.add(m.rule(m.group("/Order")).name(rule.getKey())
                    .errorField(m.field("/Order/ShipTo")).when(rule.getValue())
                    .code("C").severity(Severity.ERROR).message(en("Check the order.")).build());
        }
        // the consistency gate is the admission oracle: it throws on a rule the kernel refuses
        KernelAdapter adapter = KernelAdapter.checkAndOpen(withRules);
        Map<String, Object> read = new LinkedHashMap<>();
        authored.keySet().forEach(name -> read.put(name, adapter.read("/Order/" + name) instanceof
                ReadResult.Converted converted ? converted.condition() : adapter.read("/Order/" + name)));
        assertEquals(authored, read);
    }
}
