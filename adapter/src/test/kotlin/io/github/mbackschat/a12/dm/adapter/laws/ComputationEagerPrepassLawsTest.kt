package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-07-22-12 — before scheduling, `compute()` eagerly formally-validates every non-computed OPERAND
 * field, WITHOUT globally aborting (kernel `IDocumentRtService.compute` javadoc: an invalid value is reported, a
 * required-empty is silent, a bad index is reported; then "regardless of the formal errors, computations will be
 * performed"). This reuses the entry-08 five-channel oracle
 * (`RuntimeLaws.resultChannels`) — no new harness.
 *
 * The eager operand-error INVENTORY is the kernel result-envelope channel `getFormalErrorsInOperands`; the
 * report-all interpreter does not surface that list. The focused direct-dependency witness below observes a
 * CLEARED consumer while an unrelated computation still runs; it does not turn the eager inventory into a general
 * scheduling claim. An erroneous computed instance is ERRORED (never also CLEARED). The distinct five channels
 * themselves are locked by `ComputeResultChannelsLawsTest`.
 */
class ComputationEagerPrepassLawsTest {

    @Test
    fun anInvalidOperandIsReportedAndUnrelatedComputationsStillRun() {
        val base = DocumentModelDraft.named("cart-invalid").locale("en_US")
            .rootGroup("Cart") { g ->
                g.numberField("Units") { }.numberField("PriceEach") { }.numberField("LineTotal") { }
                    .numberField("Fee") { }.numberField("FeeDoubled") { }
            }.build().toJson()
        val model = RuntimeLaws.withTieredComputation(
            RuntimeLaws.withTieredComputation(base, "/Cart", "LineTotalComp", "../LineTotal", null,
                listOf(RuntimeLaws.CompAlt(null, "[Units] * [PriceEach]"))),
            "/Cart", "FeeDoubledComp", "../FeeDoubled", null,
            listOf(RuntimeLaws.CompAlt(null, "[Fee] * 2")))
        val doc = RuntimeLaws.doc().group("/Cart", 1)
            .field("/Cart/Units", "abc", 1, 1).field("/Cart/PriceEach", "5", 1, 1)
            .field("/Cart/LineTotal", "99", 1, 1).field("/Cart/Fee", "3", 1, 1)

        val channels = RuntimeLaws.resultChannels(model, doc)
        assertTrue(channels.formalErrorsInOperands().any { it.field().contains("Units") },
            "the invalid filled operand Units is reported in the eager prepass: ${channels.formalErrorsInOperands()}")
        assertTrue(channels.withoutErrors().any { it.contains("FeeDoubled") },
            "an unrelated computation still runs — no global abort: ${channels.withoutErrors()}")
        assertFalse(channels.withoutErrors().any { it.contains("LineTotal") },
            "the direct consumer is absent from successful instances: ${channels.withoutErrors()}")
        assertTrue(channels.cleared().any { it.contains("LineTotal") },
            "this source-filled direct consumer is classified as CLEARED: ${channels.cleared()}")
        assertFalse(channels.noErrorOccurred(), "a formal operand error flips noErrorOccurred")

        // Interpreter parity: report-all, but continued processing + a single outcome per instance must match.
        val loaded = ModelLoader.load(model)
        val outcomes = DmInterpreter(loaded).compute(
            loaded.newDocument().group("/Cart", 1)
                .field("/Cart/Units", "abc", 1, 1).field("/Cart/PriceEach", "5", 1, 1)
                .field("/Cart/LineTotal", "99", 1, 1).field("/Cart/Fee", "3", 1, 1))
            .associate { it.path to it.outcome }
        assertEquals(ComputeOutcome.VALUE, outcomes["/Cart/FeeDoubled"], "interpreter still computes the unrelated field")
        assertEquals(ComputeOutcome.CLEARED, outcomes["/Cart/LineTotal"], "interpreter skips the dependent of the invalid operand")
    }

    @Test
    fun emptyAndNonUniqueIndexOperandsAreReportedInThePrepass() {
        val base = DocumentModelDraft.named("inventory-index").locale("en_US")
            .rootGroup("Inventory") { root ->
                root.group("Items") { items ->
                    items.repeatable(20).stringField("Sku") { }.numberField("SkuLength") { it.fractionDigits(0, 0) }
                        .indexField("Sku")
                }
            }.build().toJson()
        val model = RuntimeLaws.withTieredComputation(base, "/Inventory/Items", "SkuLengthComp", "../SkuLength",
            null, listOf(RuntimeLaws.CompAlt(null, "Length(Sku)")))

        val empty = RuntimeLaws.resultChannels(model,
            RuntimeLaws.doc().group("/Inventory", 1).group("/Inventory/Items", 1, 1))
        assertTrue(empty.formalErrorsInOperands().any { it.code() == "mandatoryField" && it.field().contains("Sku") },
            "an empty index operand is reported by the eager prepass: ${empty.formalErrorsInOperands()}")

        val duplicate = RuntimeLaws.resultChannels(model,
            RuntimeLaws.doc().group("/Inventory", 1)
                .group("/Inventory/Items", 1, 1).field("/Inventory/Items/Sku", "A", 1, 1, 1)
                .group("/Inventory/Items", 1, 2).field("/Inventory/Items/Sku", "A", 1, 2, 1))
        assertTrue(duplicate.formalErrorsInOperands().any { it.code() == "uniqueIndex" && it.field().contains("Sku") },
            "a non-unique index operand is reported by the eager prepass: ${duplicate.formalErrorsInOperands()}")
    }

    @Test
    fun aRequiredEmptyOperandIsSilentInThePrepass() {
        val base = DocumentModelDraft.named("cart-required-empty").locale("en_US")
            .rootGroup("Cart") { g ->
                g.numberField("Base") { }.numberField("Discount") { it.required() }.numberField("Net") { }
            }.build().toJson()
        val model = RuntimeLaws.withTieredComputation(base, "/Cart", "NetComp", "../Net", null,
            listOf(RuntimeLaws.CompAlt(null, "[Base] - [Discount]")))
        val doc = RuntimeLaws.doc().group("/Cart", 1).field("/Cart/Base", "10", 1, 1)   // Discount required + empty

        val channels = RuntimeLaws.resultChannels(model, doc)
        assertTrue(channels.formalErrorsInOperands().none { it.field().contains("Discount") },
            "a required-empty operand is NOT reported in the prepass: ${channels.formalErrorsInOperands()}")
        assertTrue(channels.withoutErrors().any { it.contains("Net") },
            "the consumer still computes (empty-as-0): ${channels.withoutErrors()}")
        assertTrue(channels.noErrorOccurred(), "no invalid operand and no computed error → noErrorOccurred")
    }

    @Test
    fun anErroneousComputedInstanceIsErroredNotAlsoCleared() {
        val base = DocumentModelDraft.named("cart-overflow").locale("en_US")
            .rootGroup("Cart") { g ->
                g.numberField("A") { }.numberField("B") { }.numberField("Capped") { it.max("99") }
            }.build().toJson()
        val model = RuntimeLaws.withTieredComputation(base, "/Cart", "CappedComp", "../Capped", null,
            listOf(RuntimeLaws.CompAlt(null, "[A] * [B]")))
        // 50 * 2 = 100, outside the target's max(99) → an errored computed value. Capped is seeded stale so a
        // double-report would ALSO surface it as CLEARED.
        val doc = RuntimeLaws.doc().group("/Cart", 1)
            .field("/Cart/A", "50", 1, 1).field("/Cart/B", "2", 1, 1).field("/Cart/Capped", "9", 1, 1)

        val channels = RuntimeLaws.resultChannels(model, doc)
        assertTrue(channels.withErrors().any { it.contains("Capped") },
            "the out-of-range computed value is an errored instance: ${channels.withErrors()}")
        assertFalse(channels.cleared().any { it.contains("Capped") },
            "the errored instance is NOT also cleared (removed from the source-filled residual): ${channels.cleared()}")
        assertFalse(channels.noErrorOccurred(), "an errored computed instance flips noErrorOccurred")
    }
}
