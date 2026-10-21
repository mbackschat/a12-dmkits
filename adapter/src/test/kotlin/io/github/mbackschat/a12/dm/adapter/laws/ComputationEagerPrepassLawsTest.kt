package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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
 *
 * SPEC-2026-07-23-15 extends the lock with the inventory's SELECTION semantics: the eager formal-input set is
 * the union across checked computations of directly referenced fields, every field contained by
 * ordinarily/conditionally referenced groups, and the index fields required by semantic-index or
 * parallel-iteration use — traversing operations, preconditions, filters, and semantic-index keys, computed
 * fields excluded (kernel `CalculationOperandsCollector`/`CalculationDependencies`). The generated preliminary
 * mandatory/uniqueness rules run ONLY for index fields retained by that inventory (`CalculationCommand
 * .preliminaryValidationOfIndexFields`) — an unused bad index is silent, a direct sibling-field reference does
 * NOT select the index — and formal ingestion applies the SAME full-call index S-value staging as full
 * validation (SPEC-2026-07-23-14), so an eligible clean-empty enum index computes on its declaration-owned
 * default instead of drawing `mandatoryField`. The inventory is neither a runtime read trace nor a schedule.
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

        val dupDoc = RuntimeLaws.doc().group("/Inventory", 1)
            .group("/Inventory/Items", 1, 1).field("/Inventory/Items/Sku", "A", 1, 1, 1)
            .group("/Inventory/Items", 1, 2).field("/Inventory/Items/Sku", "A", 1, 2, 1)
        val duplicate = RuntimeLaws.resultChannels(model, dupDoc)
        assertTrue(duplicate.formalErrorsInOperands().any { it.code() == "uniqueIndex" && it.field().contains("Sku") },
            "a non-unique index operand is reported by the eager prepass: ${duplicate.formalErrorsInOperands()}")

        // The prepass-invalidated DUPLICATE cells poison their direct readers — the sibling instance of the
        // empty-index poison (SPEC-2026-07-23-15): the kernel's change projection is silent for the
        // never-source-filled targets, and the report-all interpreter documents each as CLEARED — never
        // VALUE|1 computed from the duplicated token.
        val kernelOutcomes = RuntimeLaws.compute(model, dupDoc)
            .filter { it.path().contains("SkuLength") }
            .map { if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}" }
        assertEquals(emptyList<String>(), kernelOutcomes, "the kernel's delta is silent for the unfilled poisoned targets")
        val loaded = ModelLoader.load(model)
        val interpOutcomes = DmInterpreter(loaded).compute(
            loaded.newDocument().group("/Inventory", 1)
                .group("/Inventory/Items", 1, 1).field("/Inventory/Items/Sku", "A", 1, 1, 1)
                .group("/Inventory/Items", 1, 2).field("/Inventory/Items/Sku", "A", 1, 2, 1))
            .filter { it.path.contains("SkuLength") }
            .map { when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}" } }
        assertEquals(listOf("CLEARED", "CLEARED"), interpOutcomes,
            "the report-all interpreter documents both poisoned duplicate readers as CLEARED")
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

    // ── SPEC-2026-07-23-15: which index fields the inventory SELECTS, and the shared default staging ──────

    /** The store fixture: Items (index Sku) is the SELECTED side, Bins (index Code) the structurally
     *  identical UNUSED twin; Fee/FeeDoubled carries the unrelated computation that must keep running. */
    private fun storeModel(): String = DocumentModelDraft.named("store-selection").locale("en_US")
        .rootGroup("Store") { root ->
            root.group("Items") { items ->
                items.repeatable(20).stringField("Sku") { }.numberField("Qty") { }
                    .numberField("SkuLength") { it.fractionDigits(0, 0) }.indexField("Sku")
            }.group("Bins") { bins ->
                bins.repeatable(20).stringField("Code") { }.numberField("Load") { }.indexField("Code")
            }.numberField("Fee") { }.numberField("FeeDoubled") { }
        }.build().toJson()

    private fun formalSigs(msgs: List<RuntimeLaws.Msg>) = msgs.map { "${it.code()}@${it.field()}" }.sorted()

    /** The dynamic-Groovy and static-Java strategies must agree on the eager formal-operand inventory. */
    private fun dualRouteFormalErrors(model: String, doc: RuntimeLaws.Doc): List<String> {
        val dynamic = RuntimeLaws.computeWithFormalErrors(model, doc)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val static = engine.computeWithFormalErrors(engine.instance(doc))
        assertEquals(formalSigs(dynamic.formalErrorsInOperands()), formalSigs(static.formalErrorsInOperands()),
            "static-Java's eager operand inventory must equal dynamic-Groovy's")
        return formalSigs(dynamic.formalErrorsInOperands())
    }

    @Test
    fun aSelectedEmptyIndexIsReportedAndAnOtherwiseIdenticalUnusedIndexIsSilent() {
        val model = RuntimeLaws.withTieredComputation(
            RuntimeLaws.withTieredComputation(storeModel(), "/Store/Items", "SkuLenComp", "../SkuLength",
                null, listOf(RuntimeLaws.CompAlt(null, "Length(Sku)"))),
            "/Store", "FeeComp", "../FeeDoubled", null, listOf(RuntimeLaws.CompAlt(null, "[Fee] * 2")))
        // Both index groups have one bare row (empty index); only Items' Sku is an operand (Length(Sku)).
        val doc = RuntimeLaws.doc().group("/Store", 1)
            .group("/Store/Items", 1, 1).group("/Store/Bins", 1, 1).field("/Store/Fee", "3", 1, 1)

        val errors = dualRouteFormalErrors(model, doc)
        assertTrue(errors.any { it.startsWith("mandatoryField@") && "Sku" in it },
            "the SELECTED empty index is reported by the prepass: $errors")
        assertTrue(errors.none { "Code" in it },
            "the otherwise identical UNUSED index is silent: $errors")

        val channels = RuntimeLaws.resultChannels(model, doc)
        assertTrue(channels.withoutErrors().any { it.contains("FeeDoubled") },
            "an unrelated computation still runs: ${channels.withoutErrors()}")
        assertTrue(channels.withErrors().isEmpty(),
            "the eager operand error is NOT also a computed-instance error: ${channels.withErrors()}")
        assertFalse(channels.noErrorOccurred(), "the eager channel flips noErrorOccurred")

        // The DIRECT reader of the prepass-invalidated empty index is POISONED: the kernel's change
        // projection is SILENT for the never-source-filled target (CLEARED only when source-filled), and the
        // report-all interpreter documents the same no-value instance as CLEARED — never a VALUE computed
        // from an empty-as-"" read (`kernelComputeGranularity` is the documented bridge between the two).
        val kernelOutcome = RuntimeLaws.compute(model, doc)
            .filter { it.path().contains("SkuLength") }
            .map { if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}" }
        assertEquals(emptyList<String>(), kernelOutcome, "the kernel's delta is silent for the unfilled poisoned target")
        val loaded = ModelLoader.load(model)
        val interpOutcome = DmInterpreter(loaded).compute(
            loaded.newDocument().group("/Store", 1)
                .group("/Store/Items", 1, 1).group("/Store/Bins", 1, 1).field("/Store/Fee", "3", 1, 1))
            .filter { it.path.contains("SkuLength") }
            .map { when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}" } }
        assertEquals(listOf("CLEARED"), interpOutcome,
            "the report-all interpreter documents the poisoned direct reader as CLEARED (no computed value)")
    }

    /** KERNEL-CONFIRMED induction (first differential run of this class): a computation ITERATING rows of
     *  its own index group selects that group's index even when no operand references it — the iteration IS
     *  the parallel-iteration machinery, so `[Qty] * 2` inside Items still requires Sku. */
    @Test
    fun iteratingAnIndexGroupSelectsItsIndexEvenWithoutReferencingIt() {
        val model = RuntimeLaws.withTieredComputation(storeModel(), "/Store/Items", "SkuLenComp", "../SkuLength",
            null, listOf(RuntimeLaws.CompAlt(null, "[Qty] * 2")))
        val doc = RuntimeLaws.doc().group("/Store", 1)
            .group("/Store/Items", 1, 1).field("/Store/Items/Qty", "5", 1, 1, 1)

        val errors = dualRouteFormalErrors(model, doc)
        assertTrue(errors.any { it.startsWith("mandatoryField@") && "Sku" in it },
            "the target-scope iteration induces the index's selection: $errors")

        // Outcome parity kernel ↔ interpreter on the documented projection (whatever the kernel computes).
        val loaded = ModelLoader.load(model)
        val kernelOutcomes = RuntimeLaws.compute(model, doc)
            .associate { it.path() to (if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}") }
        val interpOutcomes = DmInterpreter(loaded).compute(
            loaded.newDocument().group("/Store", 1).group("/Store/Items", 1, 1).field("/Store/Items/Qty", "5", 1, 1, 1))
            .associate { it.path to (when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}" }) }
        assertEquals(kernelOutcomes["/Store/Items/SkuLength"], interpOutcomes["/Store/Items/SkuLength"],
            "kernel and interpreter agree on the consuming instance's outcome")
    }

    @Test
    fun aGroupReferenceInAPreconditionSelectsTheContainedIndex() {
        val base = DocumentModelDraft.named("store-groupref").locale("en_US")
            .rootGroup("Store") { root ->
                root.group("Bins") { bins ->
                    bins.repeatable(20).stringField("Code") { }.numberField("Load") { }.indexField("Code")
                }.numberField("Fee") { }.numberField("Total") { }
            }.build().toJson()
        // AtLeastOneGroupFilled(Bins*) sits in the COMMON PRECONDITION; no Bins field is referenced
        // directly, yet the group reference expands to every contained field — the index Code included.
        val model = RuntimeLaws.withTieredComputation(base, "/Store", "TotalComp", "../Total",
            "AtLeastOneGroupFilled(Bins*)", listOf(RuntimeLaws.CompAlt(null, "[Fee] * 1")))
        val doc = RuntimeLaws.doc().group("/Store", 1)
            .group("/Store/Bins", 1, 1).field("/Store/Fee", "3", 1, 1)

        val errors = dualRouteFormalErrors(model, doc)
        assertTrue(errors.any { it.startsWith("mandatoryField@") && "Code" in it },
            "the group reference induces the contained index's selection: $errors")
    }

    @Test
    fun aSemanticIndexUseSelectsTheIndex() {
        val base = DocumentModelDraft.named("store-semidx").locale("en_US")
            .rootGroup("Store") { root ->
                root.group("Bins") { bins ->
                    bins.repeatable(20).stringField("Code") { }.numberField("Load") { }.indexField("Code")
                }.numberField("PickedLoad") { }
            }.build().toJson()
        // Only Load is read — but through a semantic index, which requires Bins' index field Code.
        val model = RuntimeLaws.withTieredComputation(base, "/Store", "PickComp", "../PickedLoad",
            null, listOf(RuntimeLaws.CompAlt(null, "[Bins/Load For \"B1\"]")))
        val doc = RuntimeLaws.doc().group("/Store", 1).group("/Store/Bins", 1, 1)

        val errors = dualRouteFormalErrors(model, doc)
        assertTrue(errors.any { it.startsWith("mandatoryField@") && "Code" in it },
            "semantic-index use induces the index's selection: $errors")
    }

    @Test
    fun aParallelIterationUseSelectsTheSiblingIndex() {
        val base = DocumentModelDraft.named("ledger-parallel").locale("en_US")
            .rootGroup("Ledger") { root ->
                for (name in listOf("Sales", "Totals")) {
                    root.group(name) { g ->
                        g.repeatable(20).stringField("Category") { }.numberField("Amount") { }.indexField("Category")
                    }
                }
            }.build().toJson()
        // Totals*/Amount = [../Sales/Amount] — the cross-group reference joins by the shared index field,
        // so BOTH sides' Category indexes are required by the parallel iteration.
        val model = RuntimeLaws.withTieredComputation(base, "/Ledger/Totals", "TotalComp", "../Amount",
            null, listOf(RuntimeLaws.CompAlt(null, "[../Sales/Amount]")))
        val doc = RuntimeLaws.doc().group("/Ledger", 1)
            .group("/Ledger/Sales", 1, 1).field("/Ledger/Sales/Amount", "4", 1, 1, 1)

        val errors = dualRouteFormalErrors(model, doc)
        assertTrue(errors.any { it.startsWith("mandatoryField@") && "Sales" in it && "Category" in it },
            "parallel-iteration use selects the sibling group's index: $errors")
    }

    @Test
    fun aFilterReferenceSelectsTheIndex() {
        val base = DocumentModelDraft.named("store-filter").locale("en_US")
            .rootGroup("Store") { root ->
                root.group("Items") { items ->
                    items.repeatable(20).stringField("Sku") { }.numberField("Qty") { }.indexField("Sku")
                }.numberField("TotalQty") { }
            }.build().toJson()
        // The operation reads Qty; Sku appears ONLY inside the Having filter — traversal still selects it.
        val model = RuntimeLaws.withTieredComputation(base, "/Store", "TotalComp", "../TotalQty",
            null, listOf(RuntimeLaws.CompAlt(null, "Sum(Items*/Qty Having ([Items/Sku] == \"A\"))")))
        val doc = RuntimeLaws.doc().group("/Store", 1)
            .group("/Store/Items", 1, 1).field("/Store/Items/Qty", "5", 1, 1, 1)

        val errors = dualRouteFormalErrors(model, doc)
        assertTrue(errors.any { it.startsWith("mandatoryField@") && "Sku" in it },
            "a filter-only reference still selects the index: $errors")
    }

    /** The enum-index fixture with the declared S-value default (SPEC-2026-07-23-14's model surface). */
    private fun enumIndexModel(sValue: String = "STD"): String = DocumentModelDraft.named("inventory-enum-index").locale("en_US")
        .rootGroup("Inventory") { root ->
            root.group("Items") { items ->
                items.repeatable(20)
                    .enumField("Sku") { e -> e.value("STD").value("EXPRESS").sValue(sValue) }
                    .stringField("SkuEcho") { }
                    .indexField("Sku")
            }
        }.build().toJson()

    @Test
    fun anEligibleSelectedAbsentIndexReceivesItsDefaultBeforeThePreliminaryRules() {
        val model = RuntimeLaws.withTieredComputation(enumIndexModel(), "/Inventory/Items", "SkuEchoComp",
            "../SkuEcho", null, listOf(RuntimeLaws.CompAlt(null, "[Sku]")))
        val doc = RuntimeLaws.doc().group("/Inventory", 1).group("/Inventory/Items", 1, 1)

        val errors = dualRouteFormalErrors(model, doc)
        assertEquals(emptyList<String>(), errors,
            "the staged S-value satisfies the selected index's preliminary mandatory rule")
        val computed = RuntimeLaws.compute(model, doc)
        assertTrue(computed.any { it.path().contains("SkuEcho") && it.value().orElse("") == "STD" },
            "the computation reads the staged token: $computed")

        // Interpreter parity: same staging, same computed value, on the documented projection.
        val loaded = ModelLoader.load(model)
        val outcomes = DmInterpreter(loaded).compute(
            loaded.newDocument().group("/Inventory", 1).group("/Inventory/Items", 1, 1))
            .associate { it.path to (it.outcome to it.value) }
        assertEquals(ComputeOutcome.VALUE to "STD", outcomes["/Inventory/Items/SkuEcho"],
            "the interpreter stages the same default")
    }

    /**
     * A padded S-value, which A12 admits by its trim (KERNEL-FINDINGS KF569), is staged as written for a computation
     * and read without a formal check, as validation stages it (`IndexDefaultStagingDiffTest`), and the interpreter
     * copies the same text (KF574; cold review 2026-10-21).
     */
    @Test
    fun aPaddedSValueIsStagedAsWrittenForAComputation() {
        val model = RuntimeLaws.withTieredComputation(enumIndexModel(" STD"), "/Inventory/Items", "SkuEchoComp",
            "../SkuEcho", null, listOf(RuntimeLaws.CompAlt(null, "[Sku]")))
        val doc = RuntimeLaws.doc().group("/Inventory", 1).group("/Inventory/Items", 1, 1)
        assertEquals(emptyList<String>() to listOf(" STD"), dualRouteFormalErrors(model, doc) to
            RuntimeLaws.compute(model, doc).filter { it.path().contains("SkuEcho") }.map { it.value().orElse("") })
        val loaded = ModelLoader.load(model)
        val outcomes = DmInterpreter(loaded).compute(loaded.newDocument().group("/Inventory", 1).group("/Inventory/Items", 1, 1))
            .associate { it.path to (it.outcome to it.value) }
        assertEquals(ComputeOutcome.VALUE to " STD", outcomes["/Inventory/Items/SkuEcho"], "the interpreter copies it as written")
    }

    @Test
    fun anEligibleSelectedPresentEmptyIndexReceivesItsDefaultToo() {
        val model = RuntimeLaws.withTieredComputation(enumIndexModel(), "/Inventory/Items", "SkuEchoComp",
            "../SkuEcho", null, listOf(RuntimeLaws.CompAlt(null, "[Sku]")))
        val doc = RuntimeLaws.doc().group("/Inventory", 1)
            .group("/Inventory/Items", 1, 1).emptyField("/Inventory/Items/Sku", 1, 1, 1)

        assertEquals(emptyList<String>(), dualRouteFormalErrors(model, doc),
            "a present-empty cell is clean-empty too — same staging")
        val loaded = ModelLoader.load(model)
        val outcomes = DmInterpreter(loaded).compute(
            loaded.newDocument().group("/Inventory", 1)
                .group("/Inventory/Items", 1, 1).emptyField("/Inventory/Items/Sku", 1, 1, 1))
            .associate { it.path to (it.outcome to it.value) }
        assertEquals(ComputeOutcome.VALUE to "STD", outcomes["/Inventory/Items/SkuEcho"],
            "the interpreter stages the present-empty cell's default")
    }

    @Test
    fun anIneligibleMultiRowSelectedIndexKeepsItsPrepassErrors() {
        val model = RuntimeLaws.withTieredComputation(enumIndexModel(), "/Inventory/Items", "SkuEchoComp",
            "../SkuEcho", null, listOf(RuntimeLaws.CompAlt(null, "[Sku]")))
        val doc = RuntimeLaws.doc().group("/Inventory", 1)
            .group("/Inventory/Items", 1, 1).group("/Inventory/Items", 1, 2)

        val errors = dualRouteFormalErrors(model, doc)
        assertEquals(2, errors.count { it.startsWith("mandatoryField@") },
            "two clean-empty rows are ineligible — ordinary preliminary errors, no injected duplicates: $errors")
        assertTrue(errors.none { it.startsWith("uniqueIndex@") }, "no phantom defaulted duplicates: $errors")
    }

    @Test
    fun anAdmittedExplicitSelectedIndexIsNotDefaulted() {
        val model = RuntimeLaws.withTieredComputation(enumIndexModel(), "/Inventory/Items", "SkuEchoComp",
            "../SkuEcho", null, listOf(RuntimeLaws.CompAlt(null, "[Sku]")))
        val doc = RuntimeLaws.doc().group("/Inventory", 1)
            .group("/Inventory/Items", 1, 1).field("/Inventory/Items/Sku", "EXPRESS", 1, 1, 1)

        assertEquals(emptyList<String>(), dualRouteFormalErrors(model, doc), "an admitted value needs no default")
        val computed = RuntimeLaws.compute(model, doc)
        assertTrue(computed.any { it.path().contains("SkuEcho") && it.value().orElse("") == "EXPRESS" },
            "the computation reads the ADMITTED value: $computed")
        val loaded = ModelLoader.load(model)
        val outcomes = DmInterpreter(loaded).compute(
            loaded.newDocument().group("/Inventory", 1)
                .group("/Inventory/Items", 1, 1).field("/Inventory/Items/Sku", "EXPRESS", 1, 1, 1))
            .associate { it.path to (it.outcome to it.value) }
        assertEquals(ComputeOutcome.VALUE to "EXPRESS", outcomes["/Inventory/Items/SkuEcho"],
            "the interpreter reads the admitted value")
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
