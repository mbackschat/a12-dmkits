package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for the INDEX-FIELD STORED DEFAULT's phase-specific staging (SPEC-2026-07-23-14): a
 * clean-empty index cell eligible for its enum's declared S-value (nonmandatory index, EXACTLY ONE
 * instantiated index-group row under the current parent, index physically absent or present-empty) splits
 * by call kind. FULL validation stages the S-value into its transient evaluation cache — before the
 * generated index mandatory/uniqueness checks and before authored rules, without mutating the document
 * (kernel `ValidationCache.addDefaultValuesForIndexFieldsIfNecessary` → `setIndexFieldToDefaultValue`).
 * PARTIAL validation does NOT stage it: the eligible cell is silently marked unavailable (the kernel's
 * `markFieldAsInvalidForValidation` — no `mandatoryField`, no `uniqueIndex`, no formal message), dependent
 * authored conditions read UNKNOWN, and the containing group retains error state. Ineligible shapes
 * (multiple rows, admitted explicit value, explicitly mandatory, non-Enumeration index, formally invalid
 * explicit value) receive no default in either call kind.
 *
 * Every case compares dynamic-Groovy (normative), static-Java, and the interpreter over the full
 * (code @ field-pointer) message set. E-commerce domain (own fixtures): an order's lines are keyed by a
 * ServiceLevel enum whose S-value defaults the only line to standard shipping.
 */
class IndexDefaultStagingDiffTest {

    private companion object {
        const val ORDER = "/Order"
        const val NOTE = "/Order/Note"
        const val LINES = "/Order/Lines"
        const val SERVICE_LEVEL = "/Order/Lines/ServiceLevel"
        const val QTY = "/Order/Lines/Qty"
    }

    /** The base model: a repeatable Lines group keyed by an enum index carrying the S-value "STD". */
    private fun orderModel(sValue: String? = "STD", requiredIndex: Boolean = false): String =
        DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", Consumer { o ->
                o.stringField("Note", Consumer { })
                    .group("Lines", Consumer<GroupBuilder> { lines ->
                        lines.repeatable(10).indexField("ServiceLevel")
                            .enumField("ServiceLevel", Consumer { e ->
                                e.value("STD").value("EXPRESS")
                                if (sValue != null) e.sValue(sValue)
                                if (requiredIndex) e.required()
                            })
                            .numberField("Qty", Consumer { })
                    })
            })
            .build().toJson()

    /** The dependent-rule variant: SL_STD fires on a row whose ServiceLevel reads "STD" (error field Qty —
     *  referenced through the always-true `[Qty] >= 0` conjunct the MVK error-field gate requires), so a
     *  firing proves the staged default reached AUTHORED evaluation, not just the generated checks. */
    private fun withDependentRule(json: String): String {
        val m = BoundModel.ofJson(json)
        val rule = m.rule(m.group(LINES)).name("SlStd").code("SL_STD").errorField(m.num(QTY))
            .`when`(m.num(QTY).ge(0).and(m.enumOf(SERVICE_LEVEL).`is`("STD"))).severity(Severity.ERROR)
            .message(Dsl.en("standard service level selected")).build()
        return TypedRules.inject(json, rule)
    }

    /** SL_OTHER fires on a row whose ServiceLevel is anything but "STD", so it fires for a staged token that is not
     *  "STD" and is silent for one that is suppressed as formally invalid. */
    private fun withNotStdRule(json: String): String {
        val m = BoundModel.ofJson(json)
        val rule = m.rule(m.group(LINES)).name("SlOther").code("SL_OTHER").errorField(m.num(QTY))
            .`when`(m.num(QTY).ge(0).and(m.enumOf(SERVICE_LEVEL).isNot("STD"))).severity(Severity.ERROR)
            .message(Dsl.en("another service level selected")).build()
        return TypedRules.inject(json, rule)
    }

    /** The group-absence variant: GNF fires when NO Lines row is filled (error field Note, referenced
     *  through the `FieldFilled(Note)` conjunct the MVK error-field gate requires). */
    private fun withGroupNotFilledRule(json: String): String {
        val m = BoundModel.ofJson(json)
        val rule = m.rule(m.group(ORDER)).name("LinesGone").code("GNF").errorField(m.str(NOTE))
            .`when`(m.str(NOTE).filled().and(Dsl.noGroupFilled(m.flattenGroup("$LINES*"))))
            .severity(Severity.ERROR)
            .message(Dsl.en("no order lines")).build()
        return TypedRules.inject(json, rule)
    }

    /** Mirror one document build across the kernel [RuntimeLaws.Doc] and the interpreter document. */
    private inner class Build(model: String) {
        val kDoc = RuntimeLaws.doc().group(ORDER, 1)
        val iDoc = ModelLoader.load(model).newDocument().group(ORDER, 1)
        fun note(v: String) { kDoc.field(NOTE, v, 1, 1); iDoc.field(NOTE, v, 1, 1) }
        fun line(i: Int) { kDoc.group(LINES, 1, i); iDoc.group(LINES, 1, i) }
        fun qty(i: Int, v: String) { line(i); kDoc.field(QTY, v, 1, i, 1); iDoc.field(QTY, v, 1, i, 1) }
        fun serviceLevel(i: Int, v: String) { line(i); kDoc.field(SERVICE_LEVEL, v, 1, i, 1); iDoc.field(SERVICE_LEVEL, v, 1, i, 1) }
        fun emptyServiceLevel(i: Int) { line(i); kDoc.emptyField(SERVICE_LEVEL, 1, i, 1); iDoc.emptyField(SERVICE_LEVEL, 1, i, 1) }
    }

    private fun sigs(cs: List<Pair<String, String>>) = cs.map { "${it.first}@${it.second}" }.sorted()

    /** FULL validation: the (code @ pointer) sets of all three engines must agree; dynamic-Groovy is normative. */
    private fun agreeFull(label: String, model: String, build: Build.() -> Unit): List<String> {
        val b = Build(model).apply(build)
        val dynamic = sigs(RuntimeLaws.messages(model, b.kDoc).map { it.code() to it.field() })
        val java = RuntimeLaws.kernelJavaEngine(model).let { e -> sigs(e.messages(e.instance(b.kDoc)).map { it.code() to it.field() }) }
        val interp = sigs(DmInterpreter(ModelLoader.load(model)).validate(b.iDoc).messages.map { it.code to it.errorPath })
        assertEquals(dynamic, java, "$label — static-Java vs dynamic-Groovy")
        assertEquals(dynamic, interp, "$label — interpreter vs dynamic-Groovy  kernel=$dynamic interp=$interp")
        return dynamic
    }

    /** PARTIAL validation with the WHOLE root wildcard-relevant — partial staging differs from full even
     *  when everything is relevant, because the kernel keys the split on the CALL KIND, not the set's extent. */
    private fun agreePart(label: String, model: String, build: Build.() -> Unit): List<String> {
        val b = Build(model).apply(build)
        val relevant = setOf(RuntimeLaws.Relevant.of(ORDER, 0))
        val dynamic = sigs(RuntimeLaws.messagesPart(model, b.kDoc, relevant).map { it.code() to it.field() })
        val java = RuntimeLaws.kernelJavaEngine(model)
            .let { e -> sigs(e.messagesPart(b.kDoc, relevant).map { it.code() to it.field() }) }
        val interp = sigs(DmInterpreter(ModelLoader.load(model))
            .validatePart(b.iDoc, setOf(RelevantEntity.anyRep(ORDER))).messages.map { it.code to it.errorPath })
        assertEquals(dynamic, java, "$label — static-Java vs dynamic-Groovy (validatePart)")
        assertEquals(dynamic, interp, "$label — interpreter vs dynamic-Groovy (validatePart)  kernel=$dynamic interp=$interp")
        return dynamic
    }

    // ── FULL validation: the S-value is staged for the eligible clean-empty index ────────────────────────

    /** An ABSENT index on the parent's only row is defaulted: no mandatoryField/uniqueIndex, and the
     *  dependent authored rule fires — the staged S-value reached rule evaluation. */
    @Test fun fullAbsentEligibleIndexStagesTheDefault() {
        val model = withDependentRule(orderModel())
        val fired = agreeFull("absent eligible index, full", model) { qty(1, "5") }
        assertTrue(fired.any { it.startsWith("SL_STD@") }, "the dependent rule sees the staged default: $fired")
        assertFalse(fired.any { it.startsWith("mandatoryField@") }, "no mandatoryField for the defaulted index: $fired")
    }

    /** A PRESENT-EMPTY index cell is clean-empty too — same staging as the absent shape. */
    @Test fun fullPresentEmptyEligibleIndexStagesTheDefault() {
        val model = withDependentRule(orderModel())
        val fired = agreeFull("present-empty eligible index, full", model) { qty(1, "5"); emptyServiceLevel(1) }
        assertTrue(fired.any { it.startsWith("SL_STD@") }, "the dependent rule sees the staged default: $fired")
        assertFalse(fired.any { it.startsWith("mandatoryField@") }, "no mandatoryField for the defaulted index: $fired")
    }

    /**
     * A12 admits a padded or blank S-value by its trim (KERNEL-FINDINGS KF569), then stages the text as written and
     * reads it without a formal check: the index counts as filled, and a rule comparing it with `STD` sees another
     * value, on all three engines (KF574). The blank one is written past the builder, which refuses it as authoring
     * a no-op (cold review 2026-10-21).
     */
    @Test fun aPaddedOrBlankSValueIsStagedAsWritten() {
        val padded = withNotStdRule(withDependentRule(orderModel(sValue = " STD")))
        val blank = withNotStdRule(withDependentRule(orderModel())).replace("\"sValue\" : \"STD\"", "\"sValue\" : \" \"")
        assertTrue("\"sValue\" : \" \"" in blank, "the blank S-value was written")
        for ((label, model) in listOf("padded" to padded, "blank" to blank)) {
            assertEquals(listOf("SL_OTHER@/Order[1]/Lines[1]/Qty"), agreeFull(label, model) { qty(1, "5") },
                "$label: staged as written")
        }
        // The controls, on all three engines: the unpadded S-value stages `STD`, and the same text typed into the
        // cell is formally checked.
        val control = withNotStdRule(withDependentRule(orderModel()))
        assertEquals(
            mapOf("staged STD" to listOf("SL_STD@/Order[1]/Lines[1]/Qty"),
                "typed padded" to listOf("fuehrendesBlank@/Order[1]/Lines[1]/ServiceLevel")),
            mapOf("staged STD" to agreeFull("staged STD", control) { qty(1, "5") },
                "typed padded" to agreeFull("typed padded", control) { qty(1, "5"); serviceLevel(1, " STD") }),
        )
    }

    /** An ADMITTED EXPLICIT value is never overridden by the default. */
    @Test fun fullAdmittedExplicitValueIsNotDefaulted() {
        val model = withDependentRule(orderModel())
        val fired = agreeFull("admitted explicit EXPRESS, full", model) { qty(1, "5"); serviceLevel(1, "EXPRESS") }
        assertFalse(fired.any { it.startsWith("SL_STD@") }, "the explicit value is admitted, not defaulted: $fired")
    }

    /** A formally INVALID explicit value stays unavailable and never exposes the S-value to authored
     *  evaluation — the dependent rule is suppressed, and no mandatoryField appears. */
    @Test fun fullInvalidExplicitValueNeverExposesTheDefault() {
        val model = withDependentRule(orderModel())
        val fired = agreeFull("invalid explicit OVERNIGHT, full", model) { qty(1, "5"); serviceLevel(1, "OVERNIGHT") }
        assertFalse(fired.any { it.startsWith("SL_STD@") }, "the invalid cell never exposes the S-value: $fired")
        assertFalse(fired.any { it.startsWith("mandatoryField@") }, "an invalid explicit value is not 'empty': $fired")
        assertTrue(fired.any { it.contains("ServiceLevel") }, "the formal enum error is reported: $fired")
    }

    /** MULTIPLE rows under one parent get NO defaults — and no phantom duplicates: a wrong injection of
     *  "STD" into both empty cells would draw uniqueIndex; the kernel draws mandatoryField on each instead. */
    @Test fun fullMultiRowParentGetsNoDefaultsAndNoInjectedDuplicates() {
        val model = withDependentRule(orderModel())
        val fired = agreeFull("two clean-empty rows, full", model) { qty(1, "5"); qty(2, "7") }
        assertEquals(2, fired.count { it.startsWith("mandatoryField@") }, "each empty index is mandatory-flagged: $fired")
        assertFalse(fired.any { it.startsWith("uniqueIndex@") }, "no injected duplicate pair: $fired")
        assertFalse(fired.any { it.startsWith("SL_STD@") }, "no row was defaulted: $fired")
    }

    /** An EXPLICITLY MANDATORY index receives no default — the author's requiredness wins. */
    @Test fun fullExplicitlyMandatoryIndexGetsNoDefault() {
        val model = withDependentRule(orderModel(requiredIndex = true))
        val fired = agreeFull("explicitly mandatory index, full", model) { qty(1, "5") }
        assertTrue(fired.any { it.startsWith("mandatoryField@") }, "the mandatory index stays required: $fired")
        assertFalse(fired.any { it.startsWith("SL_STD@") }, "no default for a mandatory index: $fired")
    }

    /** EXPLICIT duplicates still draw uniqueIndex — the default machinery does not disturb uniqueness. */
    @Test fun fullExplicitDuplicatesStillDrawUniqueIndex() {
        val model = orderModel()
        val fired = agreeFull("explicit duplicate STD/STD, full", model) {
            serviceLevel(1, "STD"); serviceLevel(2, "STD")
        }
        assertEquals(2, fired.count { it.startsWith("uniqueIndex@") }, "every duplicate participant is flagged: $fired")
    }

    /** Per-parent scoping: shipment-style NESTED parents are independent — the single-row parent is
     *  defaulted while the two-row parent draws its two mandatoryField errors. */
    @Test fun fullPerParentScopingIsIndependent() {
        val model = DocumentModelDraft.named("warehouse").locale("en_US")
            .rootGroup("Warehouse", Consumer { w ->
                w.group("Shipments", Consumer<GroupBuilder> { s ->
                    s.repeatable(5)
                        .stringField("Carrier", Consumer { })
                        .group("Parcels", Consumer<GroupBuilder> { p ->
                            p.repeatable(9).indexField("SizeClass")
                                .enumField("SizeClass", Consumer { e -> e.value("S").value("M").value("L").sValue("M") })
                                .numberField("Weight", Consumer { })
                        })
                })
            })
            .build().toJson()
        val kDoc = RuntimeLaws.doc().group("/Warehouse", 1)
            .group("/Warehouse/Shipments", 1, 1)
            .group("/Warehouse/Shipments/Parcels", 1, 1, 1).field("/Warehouse/Shipments/Parcels/Weight", "2", 1, 1, 1, 1)
            .group("/Warehouse/Shipments", 1, 2)
            .group("/Warehouse/Shipments/Parcels", 1, 2, 1).field("/Warehouse/Shipments/Parcels/Weight", "3", 1, 2, 1, 1)
            .group("/Warehouse/Shipments/Parcels", 1, 2, 2).field("/Warehouse/Shipments/Parcels/Weight", "4", 1, 2, 2, 1)
        val iDoc = ModelLoader.load(model).newDocument().group("/Warehouse", 1)
            .group("/Warehouse/Shipments", 1, 1)
            .group("/Warehouse/Shipments/Parcels", 1, 1, 1).field("/Warehouse/Shipments/Parcels/Weight", "2", 1, 1, 1, 1)
            .group("/Warehouse/Shipments", 1, 2)
            .group("/Warehouse/Shipments/Parcels", 1, 2, 1).field("/Warehouse/Shipments/Parcels/Weight", "3", 1, 2, 1, 1)
            .group("/Warehouse/Shipments/Parcels", 1, 2, 2).field("/Warehouse/Shipments/Parcels/Weight", "4", 1, 2, 2, 1)

        val dynamic = sigs(RuntimeLaws.messages(model, kDoc).map { it.code() to it.field() })
        val java = RuntimeLaws.kernelJavaEngine(model).let { e -> sigs(e.messages(e.instance(kDoc)).map { it.code() to it.field() }) }
        val interp = sigs(DmInterpreter(ModelLoader.load(model)).validate(iDoc).messages.map { it.code to it.errorPath })
        assertEquals(dynamic, java, "per-parent scoping — static-Java vs dynamic-Groovy")
        assertEquals(dynamic, interp, "per-parent scoping — interpreter vs dynamic-Groovy  kernel=$dynamic interp=$interp")
        assertEquals(2, dynamic.count { it.startsWith("mandatoryField@") },
            "only the TWO-row shipment's parcels are mandatory-flagged: $dynamic")
        assertTrue(dynamic.filter { it.startsWith("mandatoryField@") }.all { "[2]" in it.substringAfter("Shipments") || "Shipments[2]" in it },
            "the flagged parcels belong to shipment 2: $dynamic")
    }

    /** NO PHANTOM ROW: with zero index-group rows nothing is staged — the group stays clean-empty and a
     *  GroupNotFilled rule still fires. */
    @Test fun fullZeroRowsCreateNoPhantomRow() {
        val model = withGroupNotFilledRule(orderModel())
        val fired = agreeFull("zero Lines rows, full", model) { note("n") }
        assertTrue(fired.any { it.startsWith("GNF@") }, "the group is genuinely empty — no phantom defaulted row: $fired")
        assertFalse(fired.any { it.startsWith("mandatoryField@") }, "no generated index check without a row: $fired")
    }

    // ── PARTIAL validation: the eligible cell is silently unavailable ────────────────────────────────────

    /** The SAME eligible document that full validation defaults is silently suppressed by partial validation
     *  — no mandatoryField, no uniqueIndex, and the dependent authored rule reads UNKNOWN (suppressed) —
     *  even though the ENTIRE document is relevant (the split keys on call kind, not set extent). */
    @Test fun partialEligibleIndexIsSilentlyUnavailable() {
        val model = withDependentRule(orderModel())
        val part = agreePart("absent eligible index, partial", model) { qty(1, "5") }
        assertFalse(part.any { it.startsWith("mandatoryField@") }, "no mandatoryField in partial: $part")
        assertFalse(part.any { it.startsWith("uniqueIndex@") }, "no uniqueIndex in partial: $part")
        assertFalse(part.any { it.startsWith("SL_STD@") }, "the dependent rule is suppressed in partial: $part")
    }

    /** The present-empty twin of the partial suppression. */
    @Test fun partialPresentEmptyEligibleIndexIsSilentlyUnavailable() {
        val model = withDependentRule(orderModel())
        val part = agreePart("present-empty eligible index, partial", model) { qty(1, "5"); emptyServiceLevel(1) }
        assertFalse(part.any { it.startsWith("mandatoryField@") || it.startsWith("SL_STD@") },
            "silent suppression for the present-empty cell: $part")
    }

    /** An ADMITTED EXPLICIT "STD" keeps ordinary behavior in partial — the dependent rule fires on the
     *  admitted value (no call-local suppression for a filled cell). */
    @Test fun partialAdmittedExplicitValueKeepsOrdinaryBehavior() {
        val model = withDependentRule(orderModel())
        val part = agreePart("admitted explicit STD, partial", model) { qty(1, "5"); serviceLevel(1, "STD") }
        assertTrue(part.any { it.startsWith("SL_STD@") }, "an admitted explicit value evaluates normally: $part")
    }

    /** Partial MULTI-ROW control: ineligible rows keep their ordinary generated mandatory errors. */
    @Test fun partialMultiRowParentKeepsOrdinaryMandatoryErrors() {
        val model = withDependentRule(orderModel())
        val part = agreePart("two clean-empty rows, partial", model) { qty(1, "5"); qty(2, "7") }
        assertEquals(2, part.count { it.startsWith("mandatoryField@") }, "ordinary generated checks for ineligible rows: $part")
    }

    /** The containing group RETAINS error state in partial: on a bare eligible row, `GroupNotFilled` must
     *  NOT fire (the suppressed cell is unavailable, not clean-absent) — while the control without an
     *  S-value shows the ordinary mandatoryField. No formal message rides the suppression either way. */
    @Test fun partialContainingGroupRetainsErrorState() {
        val withDefault = withGroupNotFilledRule(orderModel())
        val part = agreePart("bare eligible row, partial, S-value declared", withDefault) { note("n"); line(1) }
        assertFalse(part.any { it.startsWith("GNF@") },
            "the group retains error state — absence cannot be proven over the suppressed cell: $part")
        assertFalse(part.any { it.startsWith("mandatoryField@") }, "the suppression is silent: $part")

        val noDefault = withGroupNotFilledRule(orderModel(sValue = null))
        val control = agreePart("bare row, partial, NO S-value", noDefault) { note("n"); line(1) }
        assertTrue(control.any { it.startsWith("mandatoryField@") },
            "without an S-value the empty index is an ordinary mandatory error: $control")
    }

    /** FULL-call twin of the group-error scenario: the bare row's index is staged, so the group is FILLED
     *  by the staged value (the kernel's `addFeldToDatenCache` flips group fill) — GroupNotFilled stays
     *  un-fired and the mandatory check is satisfied. */
    @Test fun fullBareEligibleRowStagesAndFillsTheGroup() {
        val model = withGroupNotFilledRule(orderModel())
        val fired = agreeFull("bare eligible row, full", model) { note("n"); line(1) }
        assertFalse(fired.any { it.startsWith("mandatoryField@") }, "the staged default satisfies the mandatory check: $fired")
    }
}
