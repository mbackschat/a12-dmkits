package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Error-message interpolation tokens beyond {@code $Field.value$} (FEATURE-GAPS). {@link Dsl#fieldLabel}
 * renders the kernel's {@code $Field$} — the localized-label form (a bare path, no {@code .value} suffix) —
 * and {@link Dsl#repetitionIndex} renders {@code $#Group$}, the current repetition index of a repeatable
 * group. Both are message-rendering tokens, typed to a {@link FieldRef}/{@link GroupRef}, and verified
 * end-to-end: the kernel must accept a rule whose error message carries them.
 */
class MessageInterpolationTest {

    private static final BoundModel M = Fixtures.subscription();
    private static final String MODEL_JSON = Fixtures.subscriptionJson();

    @Test
    void fieldLabelRendersTheBarePathToken() {
        assertEquals("$/Subscription/Addons/MonthlyFee$",
                Dsl.fieldLabel(M.num("/Subscription/Addons/MonthlyFee").field()));
    }

    @Test
    void repetitionIndexRendersTheHashGroupToken() {
        assertEquals("$#/Subscription/Addons$", Dsl.repetitionIndex(M.group("/Subscription/Addons")));
    }

    @Test
    void indexedMessageHelpersReuseTypedKeysAndIndexFieldNames() {
        var base = DocumentModelDraft.named("warehouse-message-tokens").locale("en_US")
                .rootGroup("Warehouse", w -> w.stringField("Wanted", f -> { })
                        .group("Bins", b -> b.repeatable(3).indexField("Code")
                                .enumField("Code", e -> e.value("A").category("Band", "LOCAL"))
                                .numberField("Stock", f -> f.fractionDigits(0)))).build().toJson();
        var warehouse = EditSession.read(base).group("/Warehouse");
        var bins = warehouse.flattenGroup("Bins");
        var code = warehouse.enumOf("Bins/Code");
        assertEquals("$#/Warehouse/Bins For \"a\"\"b\"$", Dsl.repetitionIndex(bins.forIndex("a\"b")));
        assertEquals("$#/Warehouse/Bins For /Warehouse/Wanted$",
                Dsl.repetitionIndex(bins.forIndex(warehouse.field("Wanted"))));
        assertEquals("$index('Code').value$", Dsl.indexValue(code.field()));
        assertEquals("$index('Code')->'Band'$", Dsl.indexCategory(code.category("Band")));
        var stock = warehouse.num("Bins/Stock");
        var rule = warehouse.rule(r -> r.name("StockMessage").code("STOCK").errorField(stock)
                .when(stock.gt(0)).message(en(Dsl.indexValue(code.field()) + ": "
                        + Dsl.indexCategory(code.category("Band")))).build());
        var checked = new RuleValidator().check(base, rule.draft());
        assertTrue(checked.ok(), () -> "typed index tokens must be kernel-admitted: " + checked.diagnostics());
    }

    @Test
    void theKernelAcceptsARuleWhoseMessageUsesLabelAndRepetitionIndex() {
        NumberRef fee = M.num("/Subscription/Addons/MonthlyFee");
        String label = Dsl.fieldLabel(fee.field());
        String index = Dsl.repetitionIndex(M.group("/Subscription/Addons"));
        Rule rule = M.rule(M.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(fee)
                .when(fee.filled().and(fee.gt(1000)))
                .code("FEE_CAP")
                .severity(Severity.ERROR)
                .message(en("Addon #" + index + ": " + label + " exceeds the cap."),
                        de("Zusatz #" + index + ": " + label + " überschreitet das Limit."))
                .build();

        ValidationResult vr = new RuleValidator().check(MODEL_JSON, rule.draft());
        assertTrue(vr.ok(),
                () -> "the kernel should accept the $Field$ / $#Group$ message tokens, got: " + vr.diagnostics());
    }
}
