package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.CategoryRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * Enum CATEGORY projections ({@code [field -> Cat]}) in <b>value-expression</b> positions — a computation's
 * operation and a value-list entry. {@link EnumCategoryValueListReadTest} covers the quantifier-member side,
 * which SPEC-2026-07-22-05 already threaded; these are the positions it did not.
 *
 * <p>Every one of them built a {@code FieldValue} from the path (and sometimes {@code isOuter()}) alone, so
 * {@code getCategoryName()} was dropped and the read-back reported a <em>converted</em> {@code [Requested]} —
 * a different operation, whose kernel static diagnostics against the computed field differ from the authored
 * one's (field report 2026-08-10). The {@code FirstFilledValue} control shares the model and the same category,
 * so a failure isolates to value-expression conversion rather than to authoring, persistence, or category
 * extraction generally.
 *
 * <p>The last test is the class lock: an operand position that is still not category-threaded degrades the
 * subject to a verbatim {@code Opaque} instead of silently converting it into something else.
 */
class EnumCategoryValueExpressionReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);
    private static final String SELECTED = "/Fulfilment/SelectedTier";
    private static final String REQUESTED = "/Fulfilment/RequestedTier";
    private static final String FALLBACK = "/Fulfilment/FallbackTier";

    /**
     * A fulfilment model whose service tier is chosen from the customer's request with a warehouse fallback.
     * Both sources carry the {@code ServiceTier} classification over the computed field's own value domain, so
     * a projection of either is assignable to it. {@link BoundModel#ofJson} is the strict kernel consistency
     * gate, so every read-back below starts from a model the kernel accepts.
     */
    private static BoundModel fulfilment() {
        return BoundModel.ofJson(DocumentModelDraft.named("fulfilment").locale("en_US")
                .rootGroup("Fulfilment", root -> root
                        .stringField("OrderReference", field -> { })
                        .enumField("SelectedTier", field -> field.value("STANDARD").value("PRIORITY"))
                        .enumField("RequestedTier", field -> field.value("STANDARD").value("PRIORITY")
                                .category("ServiceTier", "STANDARD", "PRIORITY"))
                        .enumField("FallbackTier", field -> field.value("STANDARD").value("PRIORITY")
                                .category("ServiceTier", "STANDARD", "PRIORITY")))
                .build().toJson());
    }

    private static CategoryRef serviceTier(BoundModel model, String path) {
        return model.enumOf(path).category("ServiceTier");
    }

    @Test
    void aDirectCategoryProjectionSurvivesTheComputationReadBack() {
        BoundModel m = fulfilment();
        // tag::category-value-expression[]
        // A category projection is a value expression too, not only a quantifier member: `value()` is the
        // Calculation peer of listOperand()/firstFilledOperand(), so a computation can assign one directly.
        Computation direct = m.computation(m.enumOf("/Fulfilment/SelectedTier").field())
                .name("SelectedTierFromRequest")
                .calc(m.enumOf("/Fulfilment/RequestedTier").category("ServiceTier").value())
                .message(en("The selected tier follows the requested service tier.")).build();
        // end::category-value-expression[]

        assertEquals("[/Fulfilment/RequestedTier -> ServiceTier]", operation(direct));
        assertEquals(operation(direct), operation(readBack(m, direct)),
                "the authored category projection must survive conversion, not degrade to the bare field");
        assertEquals(direct.draft(), readBack(m, direct).draft());
    }

    /** The control that proves the shared category representation is intact — it round-tripped before the fix. */
    @Test
    void theFirstFilledValueCategoryControlStillRoundTripsExactly() {
        BoundModel m = fulfilment();
        Computation listed = m.computation(m.enumOf(SELECTED).field())
                .name("SelectedTierFirstFilled")
                .calc(Dsl.firstFilledEnum(serviceTier(m, REQUESTED).firstFilledOperand(),
                        serviceTier(m, FALLBACK).firstFilledOperand()).expression())
                .message(en("The selected tier is the first supplied service tier.")).build();

        assertEquals("FirstFilledValue(/Fulfilment/RequestedTier -> ServiceTier, "
                + "/Fulfilment/FallbackTier -> ServiceTier)", operation(listed));
        assertEquals(listed.draft(), readBack(m, listed).draft());
    }

    /**
     * The sibling value-expression site the same helper reaches: a value-list ENTRY that is a category
     * projection. Kernel-measured as accepted, so the shape is reachable and used to lose its projection.
     */
    @Test
    void aCategoryProjectionInAValueListEntrySurvivesTheRuleReadBack() {
        String condition = "FieldValueIncludedInValueList(" + SELECTED + ", [" + REQUESTED + " -> ServiceTier])";

        ReadResult.Converted read = assertInstanceOf(ReadResult.Converted.class, readRule("ListedTier", condition),
                "a category-bearing value-list entry converts");
        assertEquals(condition, RENDER.render(read.condition()));
    }

    /**
     * The backstop DOES FIRE. {@code [bare] == [other -> Cat]} is kernel-accepted (measured), and the enum
     * comparison's right operand is not category-threaded — so instead of silently reading back
     * {@code [SelectedTier] == [RequestedTier]}, a different condition, the rule stays verbatim and says why.
     */
    @Test
    void anUnthreadedCategoryPositionDegradesToOpaqueRatherThanLosingTheProjection() {
        String condition = "[" + SELECTED + "] == [" + REQUESTED + " -> ServiceTier]";

        ReadResult.Opaque opaque = assertInstanceOf(ReadResult.Opaque.class, readRule("ComparedTier", condition),
                "an unthreaded category position must never report itself converted");
        assertTrue(opaque.reason().contains("silently drop an enum category projection"),
                () -> "…and must name the dropped projection: " + opaque.reason());
        assertEquals(condition, opaque.canonicalText(), "the rule is preserved verbatim, not lost");
    }

    private static String operation(Computation computation) {
        return RENDER.render(computation.alternatives().getFirst().operation());
    }

    /** Persist through the kernel gate, then read it back through the adapter's converted computation path. */
    private static Computation readBack(BoundModel model, Computation computation) {
        BoundModel persisted = BoundModel.ofJson(model.add(computation).toJson());
        ReadComputation result = KernelAdapter.of(persisted)
                .readComputation("/Fulfilment/" + computation.name());
        return assertInstanceOf(ReadComputation.Converted.class, result,
                () -> "expected a converted computation read, got: " + result).computation();
    }

    /** Author a rule from raw DSL text (the spec path), gate the model, and read it back. */
    private static ReadResult readRule(String name, String condition) {
        EditSession session = EditSession.read(fulfilment().toJson());
        session.addRule("/Fulfilment", name, "../SelectedTier", "TIER_MISMATCH", condition, "ERROR",
                List.of(en("The selected tier does not match the requested one.")));
        return KernelAdapter.of(BoundModel.ofJson(session.toJson())).read("/Fulfilment/" + name);
    }
}
