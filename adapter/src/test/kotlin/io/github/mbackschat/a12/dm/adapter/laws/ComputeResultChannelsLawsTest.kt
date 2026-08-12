package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §11 five-channel supplement — the kernel's `IDocumentComputationResult` is a **five-channel** result, of
 * which VALUE/CLEARED/ERRORED is only the three-channel CHANGE PROJECTION our corpus/differential/`applyTo`
 * select (KERNEL-FINDINGS §11 "compute reporting granularity"; SPEC-2026-07-22-08). This locks the full
 * five-channel shape directly against the dynamic-Groovy kernel:
 *
 *  - `getComputedFieldInstancesWithoutErrors` carries every clean instance INCLUDING unchanged successes;
 *  - `getComputedFieldInstancesWithChanges` is a strict SUBSET of it (only the typed changes);
 *  - `getComputedFieldInstancesWithErrors`, `getClearedFieldInstances`, and `getFormalErrorsInOperands` are
 *    three distinct channels;
 *  - `noErrorOccurred() == getComputedFieldInstancesWithErrors().isEmpty() && getFormalErrorsInOperands().isEmpty()`.
 *
 * The narrower change-PROJECTION subtleties (string-kind typed equality, input-filled clears) are locked by
 * `ComputeGranularityTest` + `CorpusCaptureTest`; this test owns the full-result envelope.
 *
 * Own domain: an e-commerce line item — `DisplaySku`/`Label` denormalize `Sku`, `UnitPrice` = gross ÷ units.
 */
class ComputeResultChannelsLawsTest {

    // DisplaySku and Label copy Sku (denormalized display fields); UnitPrice = RoundAccounting(gross / units, 2).
    private val model = run {
        val base = DocumentModelDraft.named("compute-channels").locale("en_US")
            .rootGroup("LineItem") { group ->
                group.stringField("Sku") { }
                    .stringField("DisplaySku") { }
                    .stringField("Label") { }
                    .numberField("GrossAmount") { }
                    .numberField("Units") { }
                    .numberField("UnitPrice") { it.fractionDigits(2, 2) }
            }.build().toJson()
        val withDisplay = RuntimeLaws.withTieredComputation(base, "/LineItem", "DisplaySkuComp", "../DisplaySku",
            null, listOf(RuntimeLaws.CompAlt(null, "[Sku]")))
        val withLabel = RuntimeLaws.withTieredComputation(withDisplay, "/LineItem", "LabelComp", "../Label",
            null, listOf(RuntimeLaws.CompAlt(null, "[Sku]")))
        RuntimeLaws.withTieredComputation(withLabel, "/LineItem", "UnitPriceComp", "../UnitPrice",
            null, listOf(RuntimeLaws.CompAlt(null, "RoundAccounting([GrossAmount] / [Units], 2)")))
    }

    @Test
    fun cleanRunSeparatesUnchangedSuccessesFromTheChangeSubset() {
        // Sku = ACME-1. DisplaySku seeded "ACME-1" (== the computed copy → UNCHANGED success). Label seeded a
        // stale "OLD" (→ refreshed = a change). Units 4 so UnitPrice computes cleanly (10 / 4 → 2.50, a change
        // from the absent cell). All clean → noErrorOccurred true.
        val channels = RuntimeLaws.resultChannels(model,
            RuntimeLaws.doc().group("/LineItem", 1)
                .field("/LineItem/Sku", "ACME-1", 1, 1)
                .field("/LineItem/DisplaySku", "ACME-1", 1, 1)
                .field("/LineItem/Label", "OLD", 1, 1)
                .field("/LineItem/GrossAmount", "10", 1, 1)
                .field("/LineItem/Units", "4", 1, 1))

        // The unchanged success (DisplaySku) is in WithoutErrors but NOT in WithChanges.
        assertTrue(channels.withoutErrors().any { it.contains("DisplaySku") },
            "an unchanged success is still in getComputedFieldInstancesWithoutErrors: ${channels.withoutErrors()}")
        assertFalse(channels.withChanges().any { it.contains("DisplaySku") },
            "an unchanged success is NOT in getComputedFieldInstancesWithChanges: ${channels.withChanges()}")
        // A refreshed field (Label) is in both channels.
        assertTrue(channels.withChanges().any { it.contains("Label") },
            "a changed success is in getComputedFieldInstancesWithChanges: ${channels.withChanges()}")
        // WithChanges is a strict subset of WithoutErrors (the unchanged DisplaySku makes it strict).
        assertTrue(channels.withoutErrors().containsAll(channels.withChanges()),
            "WithChanges ⊆ WithoutErrors: changes=${channels.withChanges()} clean=${channels.withoutErrors()}")
        assertTrue(channels.withoutErrors().size > channels.withChanges().size,
            "an unchanged success makes WithoutErrors strictly larger than WithChanges: $channels")
        // Clean run → the error channels are empty and noErrorOccurred is true.
        assertTrue(channels.withErrors().isEmpty(), "no errored instances: ${channels.withErrors()}")
        assertTrue(channels.formalErrorsInOperands().isEmpty(),
            "no formal operand errors: ${channels.formalErrorsInOperands()}")
        assertTrue(channels.noErrorOccurred(), "a clean run reports noErrorOccurred")
        assertEquals(channels.withErrors().isEmpty() && channels.formalErrorsInOperands().isEmpty(),
            channels.noErrorOccurred(), "noErrorOccurred == errored∅ && formalOperand∅")
    }

    @Test
    fun applicationUsesSourceRelativeClassificationsAgainstACompatibleDestination() {
        val source = RuntimeLaws.doc().group("/LineItem", 1)
            .field("/LineItem/Sku", "ACME-1", 1, 1)
            .field("/LineItem/DisplaySku", "ACME-1", 1, 1) // successful but unchanged against SOURCE
            .field("/LineItem/Label", "OLD", 1, 1)         // successful and changed against SOURCE
            .field("/LineItem/GrossAmount", "10", 1, 1)
            .field("/LineItem/Units", "4", 1, 1)
        val destination = RuntimeLaws.doc().group("/LineItem", 1)
            .field("/LineItem/DisplaySku", "DESTINATION", 1, 1)
            .field("/LineItem/Label", "DESTINATION", 1, 1)
        val probes = listOf(
            RuntimeLaws.CellProbe("/LineItem/DisplaySku", listOf(1, 1)),
            RuntimeLaws.CellProbe("/LineItem/Label", listOf(1, 1)),
        )

        assertEquals(
            listOf(
                "/LineItem/DisplaySku|presentValue|DESTINATION",
                "/LineItem/Label|presentValue|ACME-1",
            ),
            RuntimeLaws.appliedStatesToDestination(model, source, destination, probes),
            "applyTo consumes classifications fixed against the source: unchanged successes are not writes, changed ones are",
        )
    }

    /**
     * What a CLEARED instance carries in `getStringValue()` — measured 2026-08-12, because the accessor's own
     * javadoc covers only a *computed* value ("can be empty in case of errors") and says nothing about an
     * instance that was filled in the input and deliberately NOT computed.
     *
     * **It is `Optional.of("")` — PRESENT and empty, not absent**, and it does not echo the input cell: the
     * stale `9.00` is gone. So the optional is not a has-value flag on this channel, and any consumer that
     * renders `getStringValue()` verbatim would publish `""` for every clear — aliasing a clear with a
     * genuinely computed empty string. That is why `KernelProbeArtifact.Outcome` normalizes a clear to an
     * absent value rather than passing this through: the kernel's `""` here is a constant, carrying no
     * information, and dropping it is what keeps the two states distinguishable.
     *
     * **Both codegen strategies are asserted**, added 2026-08-12 after a peer review observed that the helper
     * read the shared dynamic engine alone: a normalization applied to every artifact cannot rest on one
     * generator's behaviour, and "for every clear" was a claim wider than the run behind it.
     */
    @Test
    fun aClearedInstanceReportsAnEmptyStringValueRatherThanTheInputItErased() {
        val channels = RuntimeLaws.clearedInstanceValues(model,
            RuntimeLaws.doc().group("/LineItem", 1)
                .field("/LineItem/Sku", "ACME-1", 1, 1)
                .field("/LineItem/DisplaySku", "ACME-1", 1, 1)
                .field("/LineItem/Label", "ACME-1", 1, 1)
                .field("/LineItem/GrossAmount", "10", 1, 1)
                .field("/LineItem/Units", "0", 1, 1)
                .field("/LineItem/UnitPrice", "9.00", 1, 1))

        assertEquals(
            listOf(
                "groovy-dynamic|/LineItem[1]/UnitPrice|<present>|",
                "java-static|/LineItem[1]/UnitPrice|<present>|",
            ),
            channels,
            "the cleared UnitPrice reports a PRESENT empty string on BOTH strategies, not an absent value " +
                "and not the input 9.00",
        )
    }

    @Test
    fun anOperandDomainFailurePopulatesTheFormalOperandChannelAndFlipsNoErrorOccurred() {
        // Units 0 → RoundAccounting([GrossAmount] / 0, 2) is a domain failure: the UnitPrice target is invalid,
        // exposed as berechnungsWertFehler in getFormalErrorsInOperands, and its stale value is CLEARED — three
        // distinct channels (formal-operand, cleared, and an empty errored channel) drive noErrorOccurred false.
        val channels = RuntimeLaws.resultChannels(model,
            RuntimeLaws.doc().group("/LineItem", 1)
                .field("/LineItem/Sku", "ACME-1", 1, 1)
                .field("/LineItem/DisplaySku", "ACME-1", 1, 1)
                .field("/LineItem/Label", "ACME-1", 1, 1)
                .field("/LineItem/GrossAmount", "10", 1, 1)
                .field("/LineItem/Units", "0", 1, 1)
                .field("/LineItem/UnitPrice", "9.00", 1, 1))

        assertFalse(channels.formalErrorsInOperands().isEmpty(),
            "a division-by-zero operand yields a formal operand error: ${channels.formalErrorsInOperands()}")
        assertTrue(channels.cleared().any { it.contains("UnitPrice") },
            "the stale UnitPrice is cleared, a channel distinct from the formal operand error: ${channels.cleared()}")
        assertFalse(channels.noErrorOccurred(), "a formal operand error flips noErrorOccurred to false")
        assertEquals(channels.withErrors().isEmpty() && channels.formalErrorsInOperands().isEmpty(),
            channels.noErrorOccurred(), "noErrorOccurred == errored∅ && formalOperand∅")
    }
}
