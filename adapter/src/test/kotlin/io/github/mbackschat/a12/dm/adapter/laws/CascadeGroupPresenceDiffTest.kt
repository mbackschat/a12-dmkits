package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine differential — **a group's presence inside a computation cascade.** A computed value fills the groups
 * above it for every later computation of the same call, and a computed field's STORED value fills nothing, because
 * the kernel removes every computed target's stored value from the data a cascade reads before it starts.
 *
 * The rows split the mechanism into the parts a rival reading gets wrong: a value computed earlier in the cascade
 * (bare and keyed group operands), a stored value whose computation then clears or has not run, a poisoned
 * descendant, and a computed field whose own precondition reads its enclosing group. Stripping a stored value can
 * leave an instance with no content at all, so three rows also fix that a computation has no row-content gate: its
 * precondition is decided on an empty, absent or seed-only instance, where validation would not evaluate one that
 * cannot fire empty. Each case is one canonical A12 Document JSON string that both kernel strategies and the
 * interpreter read. Dynamic Groovy is normative, static Java must agree independently, and the interpreter's
 * report-all result is projected onto the kernel's change granularity first.
 *
 * Own domain (a parcel order).
 */
class CascadeGroupPresenceDiffTest {

    private val base = DocumentModelDraft.named("parcel-presence").locale("en_US")
        .rootGroup("Order") { order ->
            order.stringField("Reference") { }
                .numberField("Quantity") { it.fractionDigits(0, 0) }
                .numberField("Flag") { it.fractionDigits(0, 0) }
                .enumField("Service") { it.value("STANDARD").value("EXPRESS").category("Speed", "SLOW", "FAST") }
                .group("Extra") { extra ->
                    extra.numberField("Surcharge") { it.fractionDigits(0, 0) }
                        .stringField("Note") { }
                }
                .group("Packages") { packages ->
                    packages.repeatable(5).indexField("No")
                        .numberField("No") { it.fractionDigits(0, 0) }
                        .stringField("Carrier") { }
                        .group("Dims") { dims ->
                            dims.numberField("Length") { it.fractionDigits(0, 0) }
                                .numberField("Width") { it.fractionDigits(0, 0) }
                        }
                }
        }.build().toJson()

    private fun surcharge(model: String, precondition: String?, operation: String) =
        RuntimeLaws.withTieredComputation(model, "/Order/Extra", "SurchargeComp", "../Surcharge", precondition,
            listOf(RuntimeLaws.CompAlt(null, operation)))

    private fun flagWhen(model: String, guard: String, operation: String = "1") =
        RuntimeLaws.withTieredComputation(model, "/Order", "FlagComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt(guard, operation)))

    private fun service(model: String, precondition: String?) =
        RuntimeLaws.withTieredComputation(model, "/Order", "ServiceComp", "../Service", precondition,
            listOf(RuntimeLaws.CompAlt(null, "\"EXPRESS\"")))

    private fun width(model: String) =
        RuntimeLaws.withTieredComputation(model, "/Order/Packages/Dims", "WidthComp", "../Width", null,
            listOf(RuntimeLaws.CompAlt(null, "5")))

    private fun admitted(model: String): String {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model).filter { it.startsWith("ERROR") },
            "the kernel must admit the model, or an absent outcome is the gate's silence and not a compute answer")
        return model
    }

    /** Every reported outcome as `pointer|OUTCOME[|value]`, agreed by the three engines. */
    private fun outcomes(model: String, documentJson: String): List<String> {
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val fromDynamic = dynamic.computationSignaturesSorted(dynamic.instance(documentJson))
        assertEquals(fromDynamic, static.computationSignaturesSorted(static.instance(documentJson)),
            "static-Java vs dynamic-Groovy — $documentJson")
        val def = ModelLoader.load(model)
        val doc = def.readDocumentJson(documentJson)
        val signatures = DmInterpreter(def).compute(doc).map {
            val pointer = formatPointer(it.path, it.indices)
            when (it.outcome) {
                ComputeOutcome.VALUE -> "$pointer|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "$pointer|CLEARED"
                ComputeOutcome.ERRORED -> "$pointer|ERRORED|${it.value ?: ""}"
            }
        }
        assertEquals(fromDynamic, kernelComputeGranularity(signatures, doc, def).sorted(),
            "INTERPRETER vs dynamic-Groovy — $documentJson")
        return fromDynamic
    }

    /** The operand formal-error channel as `code@pointer`, agreed by the three engines. */
    private fun operandErrors(model: String, documentJson: String): List<String> {
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val fromDynamic = dynamic.resultChannels(dynamic.instance(documentJson))
            .formalErrorsInOperands().map { "${it.code()}@${it.field()}" }.sorted()
        assertEquals(fromDynamic, static.resultChannels(static.instance(documentJson))
            .formalErrorsInOperands().map { "${it.code()}@${it.field()}" }.sorted(),
            "static-Java vs dynamic-Groovy operand channel — $documentJson")
        val prepared = PreparedModel.loadExpandedJson(model)
        val report = prepared.createInterpreter().compute(prepared.documents.readDocument(documentJson))
        assertEquals(fromDynamic, report.formalErrorsInOperands
            .map { "${it.code}@${formatPointer(it.errorPath.fullName, it.errorPath.repetitionIndexes)}" }.sorted(),
            "INTERPRETER operand channel vs dynamic-Groovy — $documentJson")
        return fromDynamic
    }

    @Test
    fun aValueComputedEarlierInTheCascadeFillsItsGroup() {
        val model = admitted(flagWhen(surcharge(base, null, "7"), "NoGroupFilled(/Order/Extra)"))
        assertEquals(listOf("/Order[1]/Extra[1]/Surcharge|VALUE|7", "/Order[1]/Flag|CLEARED"),
            outcomes(model, """{"Order":{"Reference":"R","Flag":9}}"""))
    }

    /** The fill reaches every ancestor, not only the group holding the value: `Extra` holds `Code` two levels down. */
    @Test
    fun aValueComputedTwoLevelsDownFillsTheGrandparentGroup() {
        val nested = DocumentModelDraft.named("parcel-nested").locale("en_US")
            .rootGroup("Order") { order ->
                order.stringField("Reference") { }
                    .numberField("Quantity") { it.fractionDigits(0, 0) }
                    .numberField("Flag") { it.fractionDigits(0, 0) }
                    .group("Extra") { extra ->
                        extra.group("Detail") { detail -> detail.numberField("Code") { it.fractionDigits(0, 0) } }
                    }
            }.build().toJson()
        val code = { precondition: String? ->
            RuntimeLaws.withTieredComputation(nested, "/Order/Extra/Detail", "CodeComp", "../Code", precondition,
                listOf(RuntimeLaws.CompAlt(null, "5")))
        }
        val guard = "NoGroupFilled(/Order/Extra)"
        assertEquals(listOf("/Order[1]/Extra[1]/Detail[1]/Code|VALUE|5", "/Order[1]/Flag|CLEARED"),
            outcomes(admitted(flagWhen(code(null), guard)), """{"Order":{"Reference":"R","Flag":9}}"""))
        // the control: the same stored Code, its computation clearing, fills neither Detail nor Extra
        assertEquals(listOf("/Order[1]/Extra[1]/Detail[1]/Code|CLEARED", "/Order[1]/Flag|VALUE|1"),
            outcomes(admitted(flagWhen(code("FieldFilled(/Order/Quantity)"), guard)),
                """{"Order":{"Reference":"R","Flag":9,"Extra":{"Detail":{"Code":3}}}}"""))
    }

    @Test
    fun aStoredValueWhoseComputationClearsFillsNothing() {
        val model = admitted(flagWhen(surcharge(base, "FieldFilled(/Order/Quantity)", "7"), "NoGroupFilled(/Order/Extra)"))
        assertEquals(listOf("/Order[1]/Extra[1]/Surcharge|CLEARED", "/Order[1]/Flag|VALUE|1"),
            outcomes(model, """{"Order":{"Reference":"R","Flag":9,"Extra":{"Surcharge":3}}}"""))
    }

    @Test
    fun aPoisonedDescendantFillsNothingAndItsGroupReadDoesNotPoison() {
        val model = admitted(flagWhen(surcharge(base, null, "[/Order/Quantity]"), "NoGroupFilled(/Order/Extra)"))
        assertEquals(listOf("/Order[1]/Flag|VALUE|1"),
            outcomes(model, """{"Order":{"Reference":"R","Flag":9,"Quantity":"abc"}}"""))
    }

    @Test
    fun aComputationReadingItsOwnGroupDoesNotSeeItsStoredValue() {
        val model = admitted(surcharge(base, "GroupFilled(/Order/Extra)", "7"))
        assertEquals(listOf("/Order[1]/Extra[1]/Surcharge|CLEARED"),
            outcomes(model, """{"Order":{"Reference":"R","Extra":{"Surcharge":3}}}"""))
        // the control: a stored field no computation writes fills the group, and the value computes
        assertEquals(listOf("/Order[1]/Extra[1]/Surcharge|VALUE|7"),
            outcomes(model, """{"Order":{"Reference":"R","Extra":{"Surcharge":3,"Note":"gift"}}}"""))
    }

    @Test
    fun aValueComputedIntoTheKeyedRowFillsTheKeyedGroup() {
        val model = admitted(flagWhen(width(base), "AtLeastOneGroupFilled(/Order/Packages/Dims For \"2\")"))
        assertEquals(listOf("/Order[1]/Flag|VALUE|1", "/Order[1]/Packages[1]/Dims[1]/Width|VALUE|5",
                "/Order[1]/Packages[2]/Dims[1]/Width|VALUE|5"),
            outcomes(model, """{"Order":{"Reference":"R","Flag":9,"Packages":[{"No":1,"Carrier":"DHL","Dims":{"Length":3}},{"No":2}]}}"""))
    }

    @Test
    fun aValueComputedIntoEachRowFillsThatRowsGroup() {
        val model = admitted(RuntimeLaws.withTieredComputation(width(base), "/Order/Packages", "CarrierComp",
            "../Carrier", null, listOf(RuntimeLaws.CompAlt("NoGroupFilled(/Order/Packages/Dims)", "\"x\""))))
        assertEquals(listOf("/Order[1]/Packages[1]/Carrier|CLEARED", "/Order[1]/Packages[1]/Dims[1]/Width|VALUE|5",
                "/Order[1]/Packages[2]/Carrier|CLEARED", "/Order[1]/Packages[2]/Dims[1]/Width|VALUE|5"),
            outcomes(model, """{"Order":{"Reference":"R","Packages":[{"No":1,"Carrier":"DHL","Dims":{"Length":3}},{"No":2,"Carrier":"UPS"}]}}"""))
    }

    @Test
    fun aClearedStoredValueIsNotCountedByAGroupCount() {
        val model = admitted(flagWhen(surcharge(base, "FieldFilled(/Order/Quantity)", "7"), "FieldFilled(/Order/Reference)",
            "NumberOfFilledFields(/Order/Extra)"))
        assertEquals(listOf("/Order[1]/Extra[1]/Surcharge|CLEARED", "/Order[1]/Flag|VALUE|0"),
            outcomes(model, """{"Order":{"Reference":"R","Flag":9,"Extra":{"Surcharge":3}}}"""))
    }

    /** A non-repeatable group below a repeatable row, whose instance exists by the row whatever the group holds. */
    @Test
    fun aPreconditionIsDecidedInAGroupBelowARowWithoutContentOfItsOwn() {
        val model = admitted(RuntimeLaws.withTieredComputation(base, "/Order/Packages/Dims", "LengthComp", "../Length",
            null, listOf(RuntimeLaws.CompAlt("[Width] < 5", "5"))))
        for (packages in listOf("""{"No":1,"Dims":{}}""", """{"No":1}""", """{"No":1,"Dims":{"Length":9}}""")) {
            val document = """{"Order":{"Packages":[$packages]}}"""
            assertEquals(listOf("/Order[1]/Packages[1]/Dims[1]/Length|VALUE|5"), outcomes(model, document), document)
        }
    }

    /** The repeatable arm, where an instantiated row is content in itself — here with no index to supply any. */
    @Test
    fun aPreconditionAndAFilterAreDecidedOnARepeatableRowWithoutContent() {
        val lines = DocumentModelDraft.named("parcel-lines").locale("en_US")
            .rootGroup("Order") { order ->
                order.numberField("Total") { it.fractionDigits(0, 0) }
                    .group("Lines") { line ->
                        line.repeatable(3).numberField("Qty") { it.fractionDigits(0, 0) }
                            .numberField("Mark") { it.fractionDigits(0, 0) }
                    }
            }.build().toJson()
        val mark = RuntimeLaws.withTieredComputation(lines, "/Order/Lines", "MarkComp", "../Mark", null,
            listOf(RuntimeLaws.CompAlt("[/Order/Lines/Qty] < 5", "1")))
        val model = admitted(RuntimeLaws.withTieredComputation(mark, "/Order", "TotalComp", "../Total", null,
            listOf(RuntimeLaws.CompAlt(null, "Sum(Lines* / Mark Having ([Lines/Qty] < 5))"))))
        for (row in listOf("{}", """{"Mark":9}""")) {
            val document = """{"Order":{"Lines":[$row]}}"""
            assertEquals(listOf("/Order[1]/Lines[1]/Mark|VALUE|1", "/Order[1]/Total|VALUE|1"),
                outcomes(model, document), document)
        }
    }

    @Test
    fun aPreconditionIsDecidedOnAnInstanceWithoutContent() {
        val model = admitted(flagWhen(base, "[/Order/Quantity] < 5"))
        for (document in listOf("""{"Order":{"Reference":"R"}}""", """{"Order":{}}""", "{}", """{"Order":{"Flag":9}}""")) {
            assertEquals(listOf("/Order[1]/Flag|VALUE|1"), outcomes(model, document), document)
        }
    }

    @Test
    fun aValueComputedEarlierInTheCascadeIsReadOnAnInstanceWithoutContent() {
        for (guard in listOf("[/Order/Extra/Surcharge] > 5", "AtLeastOneFieldFilled(/Order/Extra)")) {
            val model = admitted(flagWhen(surcharge(base, null, "7"), guard))
            for (document in listOf("{}", """{"Order":{"Flag":9}}""", """{"Order":{"Flag":9,"Extra":{"Surcharge":3}}}""")) {
                assertEquals(listOf("/Order[1]/Extra[1]/Surcharge|VALUE|7", "/Order[1]/Flag|VALUE|1"),
                    outcomes(model, document), "$guard — $document")
            }
        }
    }

    @Test
    fun aValueComputedEarlierInTheCascadeIsCountedByAGroupCount() {
        val model = admitted(flagWhen(surcharge(base, null, "7"), "FieldFilled(/Order/Reference)",
            "NumberOfFilledFields(/Order/Extra)"))
        assertEquals(listOf("/Order[1]/Extra[1]/Surcharge|VALUE|7", "/Order[1]/Flag|VALUE|1"),
            outcomes(model, """{"Order":{"Reference":"R","Flag":9}}"""))
    }

    @Test
    fun aComputedEnumValueIsWhatItsCategoryReads() {
        val model = admitted(flagWhen(service(base, null), "[/Order/Service -> Speed] == \"FAST\""))
        assertEquals(listOf("/Order[1]/Flag|VALUE|1", "/Order[1]/Service|VALUE|EXPRESS"),
            outcomes(model, """{"Order":{"Reference":"R","Flag":9}}"""))
    }

    @Test
    fun aClearedStoredEnumValueHasNoCategory() {
        val model = admitted(flagWhen(service(base, "FieldFilled(/Order/Quantity)"), "[/Order/Service -> Speed] == \"SLOW\""))
        assertEquals(listOf("/Order[1]/Flag|CLEARED", "/Order[1]/Service|CLEARED"),
            outcomes(model, """{"Order":{"Reference":"R","Flag":9,"Service":"STANDARD"}}"""))
    }

    @Test
    fun aMalformedStoredValueOfAComputedOperandIsNeverChecked() {
        val model = admitted(flagWhen(surcharge(base, null, "7"), "FieldFilled(/Order/Reference)",
            "[/Order/Extra/Surcharge] + 1"))
        val document = """{"Order":{"Reference":"R","Extra":{"Surcharge":"abc"}}}"""
        assertEquals(emptyList<String>(), operandErrors(model, document))
        assertEquals(listOf("/Order[1]/Extra[1]/Surcharge|VALUE|7", "/Order[1]/Flag|VALUE|8"), outcomes(model, document))
    }
}
