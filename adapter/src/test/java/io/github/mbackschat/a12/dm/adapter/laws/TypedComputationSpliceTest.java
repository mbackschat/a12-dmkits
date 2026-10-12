package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader;

/**
 * The typed computation splice: a {@link Computation} in, the model JSON the String form produces out.
 *
 * <p><b>Why this exists (DG58).</b> {@code RuntimeLaws} published a {@code Condition} entry point for
 * {@code withRule} only, so {@code TypedConditionAuthoringRatchetTest}'s failure message — "author through the
 * shared typed entry point" — was an instruction roughly 38 of the files it counts could not follow: their
 * splice helper had no typed twin. A guard prescribing a migration its own estate cannot take is the
 * affordance defect one layer above the one the ratchet was built to catch.
 *
 * <p><b>Byte equality is the contract, not a convenience.</b> A typed splice producing a DIFFERENT model would
 * move every law that migrated onto it, silently, and a migration is meant to rewrite the spelling and nothing
 * else. So each case pins the typed output against the exact String output it replaces — which is also why the
 * fixture declares both locales and spells the two placeholder texts the String form hardcodes.
 */
class TypedComputationSpliceTest {

    private static final String GROUP = "/Order";

    private static final String ORDER = DocumentModelDraft.named("order-typed-splice")
            .locale("en_US").locale("de_DE")
            .rootGroup("Order", order -> order
                    .numberField("Quantity", f -> f.fractionDigits(0))
                    .numberField("UnitPrice", f -> f.fractionDigits(2))
                    .numberField("Total", f -> f.fractionDigits(2)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(ORDER);
    private static final NumberRef QUANTITY = M.num("/Order/Quantity");
    private static final NumberRef UNIT_PRICE = M.num("/Order/UnitPrice");
    private static final NumberRef TOTAL = M.num("/Order/Total");

    /** What the String splice writes for every computation it builds, in both locales. */
    private static Computation.Builder computation(String name) {
        return M.computation(TOTAL).name(name)
                .message(new LocalizedText("en_US", "x"), new LocalizedText("de_DE", "x"));
    }

    @Test
    void theTypedRuleHelperCarriesMessagesAndEverySeverityToBothLegs() {
        var messages = Map.of("en_US", "Review the quantity.", "de_DE", "Menge pruefen.");
        for (Severity severity : Severity.values()) {
            Rule rule = M.rule(M.group(GROUP)).name("QuantityRule").code("QUANTITY")
                    .errorField(QUANTITY).when(QUANTITY.gt(0)).severity(severity)
                    .message(new LocalizedText("en_US", messages.get("en_US")),
                            new LocalizedText("de_DE", messages.get("de_DE"))).build();
            String json = TypedRules.INSTANCE.inject(ORDER, rule);
            var written = ModelLoader.load(json).getRules().get(0);
            assertEquals(severity.name(), written.getSeverity().name());
            assertEquals(messages, written.getMessages());
            assertEquals(written, TypedRules.INSTANCE.def(rule));
            assertEquals(List.of(), RuntimeLaws.consistencyMessages(json));
        }
    }

    @Test
    void aSingleAlternativeComputationSplicesAsTheStringFormDoes() {
        Computation typed = computation("TotalComp")
                .calc(QUANTITY.asNumber().times(UNIT_PRICE.asNumber()).expression())
                .build();

        assertEquals(
                RuntimeLaws.withComputation(ORDER, GROUP, "TotalComp", "../Total", "[Quantity] * [UnitPrice]"),
                RuntimeLaws.withComputation(ORDER, typed),
                "the typed splice must produce the model the String splice produces");
    }

    @Test
    void aSuppressListRidesThroughTheTypedForm() {
        Computation typed = computation("TotalComp")
                .calc(QUANTITY.asNumber().times(UNIT_PRICE.asNumber()).expression())
                .errorCodesToSuppress("MVK_INVALID_COMPARE_DEC_PLACES")
                .build();

        assertEquals(
                RuntimeLaws.withComputation(ORDER, GROUP, "TotalComp", "../Total", "[Quantity] * [UnitPrice]",
                        List.of("MVK_INVALID_COMPARE_DEC_PLACES")),
                RuntimeLaws.withComputation(ORDER, typed),
                "the suppress list belongs to the computation, so the typed form must carry it");
    }

    /**
     * A TIERED computation goes through the SAME entry point rather than a second one. The caller holds one
     * {@code Computation} either way, and which kernel shape it needs is derivable from its draft — so making
     * the call site choose would just move the affordance gap one step.
     *
     * <p>The kit refuses to mix an unconditional alternative with preconditioned ones, so a tiered computation
     * is all-preconditioned by construction; that is why both arms below carry a {@code when}.
     */
    @Test
    void aMultiAlternativeComputationTakesTheTieredShape() {
        Computation typed = computation("TieredComp")
                .when(QUANTITY.gt(10)).calc(QUANTITY.asNumber().times(UNIT_PRICE.asNumber()).expression())
                .when(QUANTITY.le(10)).calc(UNIT_PRICE.asNumber().expression())
                .build();

        assertEquals(2, typed.draft().alternatives().size(), "the fixture must actually be tiered");

        String spliced = RuntimeLaws.withComputation(ORDER, typed);
        assertTrue(spliced.contains("\"precondition\""),
                "a tiered computation must reach the kernel carrying its preconditions, got:\n" + spliced);
        assertEquals(List.of(), RuntimeLaws.consistencyMessages(spliced),
                "and the tiered model the typed form builds must be kernel-admitted");
    }

    /**
     * The third helper that had no typed twin. A localized-text law varies its MESSAGES, so its condition is
     * the part that should never have been hand-spelled — and the typed form must land the same bytes.
     */
    @Test
    void theLocalizedRuleSpliceAlsoTakesATypedCondition() {
        var condition = io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.fieldFilled(QUANTITY.field())
                .and(TOTAL.gt(0));

        assertEquals(
                RuntimeLaws.withRuleMessages(ORDER, GROUP, "TotalRule", "../Total", "TR",
                        "FieldFilled(Quantity) And [Total] > 0", "too big", "zu gross"),
                RuntimeLaws.withRuleMessages(ORDER, GROUP, "TotalRule", "../Total", "TR",
                        condition, "too big", "zu gross"),
                "the typed condition must render to the text the String form carries");
    }

    @Test
    void theSplicedModelIsKernelAdmitted() {
        Computation typed = computation("TotalComp")
                .calc(QUANTITY.asNumber().times(UNIT_PRICE.asNumber()).expression())
                .build();

        assertEquals(List.of(), RuntimeLaws.consistencyMessages(RuntimeLaws.withComputation(ORDER, typed)),
                "a typed splice producing an inadmissible model would be worse than the String form it replaces");
    }
}
