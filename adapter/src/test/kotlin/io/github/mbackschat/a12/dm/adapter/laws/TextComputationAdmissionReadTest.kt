package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelAdapter
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.*
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextComputationAdmissionReadTest {
    private enum class Source { TEXT_READ, CUSTOM_READ, LITERAL, TEXT_CONCAT, CUSTOM_CONCAT }
    private enum class Target { TEXT, CUSTOM }
    private data class Probe(val computation: Computation, val codes: List<String> = emptyList(),
        val portableCodes: List<String> = codes)

    private val json = DocumentModelDraft.named("parcel-text-computations").locale("en_US")
        .rootGroup("Parcel") { p ->
            p.stringField("Label") { }.customField("Identifier") { it.type("ParcelIdentifier") }
                .stringField("DerivedLabel") { }.customField("DerivedIdentifier") { it.type("CopiedIdentifier") }
                .numberField("Quantity") { it.fractionDigits(0) }
                .enumField("Status") { it.value("checked-verified") }
        }.build().toJson()
    private val model = BoundModel.ofJson(json)
    private val group = model.group("/Parcel")
    private val text = model.str("/Parcel/Label")
    private val custom = model.custom("/Parcel/Identifier")
    private val textTarget = model.str("/Parcel/DerivedLabel")
    private val customTarget = model.custom("/Parcel/DerivedIdentifier")
    private val quantity = model.num("/Parcel/Quantity")
    private val enumTarget = model.enumOf("/Parcel/Status")
    private val validator = RuleValidator()
    private val probes = buildList {
        for (source in Source.entries) for (target in Target.entries) {
            val operation = when (source) {
                Source.TEXT_READ -> text.asString().expression()
                Source.CUSTOM_READ -> custom.asString().expression()
                Source.LITERAL -> StringLiteral("checked")
                Source.TEXT_CONCAT -> text.plus("-verified").expression()
                Source.CUSTOM_CONCAT -> custom.plus("-verified").expression()
            }
            val field = when (target) {
                Target.TEXT -> textTarget.field()
                Target.CUSTOM -> customTarget.field()
            }
            add(Probe(candidate("Derive_${source}_TO_$target", field, operation)))
        }
        for (target in Target.entries) {
            val field = when (target) {
                Target.TEXT -> textTarget.field()
                Target.CUSTOM -> customTarget.field()
            }
            add(Probe(candidate("Number_TO_$target", field, quantity.asNumber().expression()),
                listOf("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING")))
        }
        // EnumComputationSourceTest locks this delegated composite case; the kernel still refuses it.
        add(Probe(candidate("TextConcat_TO_ENUM", enumTarget.field(), text.plus("-verified").expression()),
            listOf("MVK_ERROR_INVALID_ENUM_CALCULATION"), emptyList()))
    }

    private fun candidate(name: String, field: FieldRef, operation: Calculation): Computation =
        // Bypass only the client builder's target gate; the oracle receives a typed authored calculation.
        // ConfirmComputationSourceKindLawsTest uses the same independent-oracle discipline.
        Computation(group.path().value(), name, field, Optional.empty(),
            listOf(Computation.Alternative(Optional.empty(), operation)), listOf(Dsl.en(MESSAGE)),
            Comments.none(), emptyList(), RoundingMode.EXACT)

    @Test
    fun kernelAdmissionAndPortableCheckingRetainAllThirteenRows() {
        assertEquals(13, probes.size)
        assertEquals(13, probes.map { it.computation.name() }.distinct().size)
        val discrepancies = mutableListOf<String>()
        for (probe in probes) {
            val computation = probe.computation
            val kernel = validator.check(json, computation.draft())
            val codes = kernel.diagnostics().map { it.code().orElse("<codeless>") }.sorted()
            val portable = check(ModelSource(ModelWriter.addComputation(json, computation))).findings
                .filter { it.justifiesRejection }.map { it.code }.sorted()
            println("${computation.name()}|kernel=$codes|portable=$portable")
            assertEquals(probe.codes.isEmpty(), kernel.ok(), "${computation.name()}: ${kernel.diagnostics()}")
            assertEquals(probe.codes, codes, "${computation.name()} kernel admission")
            if (probe.portableCodes != portable) discrepancies += "${computation.name()}: expected=${probe.portableCodes} portable=$portable"
        }
        assertEquals(emptyList<String>(), discrepancies)
    }

    @Test
    fun publicBuilderAcceptsTheTenTextAssignmentsAndKeepsTheThreeTargetRefusals() {
        val failures = mutableListOf<String>()
        for (probe in probes) {
            val attempt = runCatching { rebuild(probe.computation) }
            if (probe.codes.isEmpty()) {
                if (attempt.isFailure) failures += "${probe.computation.name()}: ${attempt.exceptionOrNull()}"
                else assertEquals(probe.computation.draft(), attempt.getOrThrow().draft(), probe.computation.name())
            } else if (attempt.exceptionOrNull() !is PrecheckException) {
                failures += "${probe.computation.name()}: expected a target-kind refusal, got $attempt"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun allTenIndependentlyAdmittedCalculationsReadRebuildAndWriteBack() {
        val admitted = probes.filter { it.codes.isEmpty() }
        assertEquals(10, admitted.size)
        val failures = mutableListOf<String>()
        for (probe in admitted) {
            val original = probe.computation
            val fullName = "${original.groupPath()}/${original.name()}"
            val input = ModelWriter.addComputation(json, original)
            val read = KernelAdapter.checkAndOpen(BoundModel.ofJson(input)).readComputation(fullName)
            if (read !is ReadComputation.Converted) {
                failures += "$fullName: inbound read $read"
                continue
            }
            val converted = read.computation()
            assertEquals(original.computedField(), converted.computedField(), "$fullName target kind")
            assertEquals(original.alternatives(), converted.alternatives(), "$fullName typed operands")
            assertEquals(original.draft(), converted.draft(), "$fullName lossless inbound calculation")
            val attempt = runCatching { rebuild(converted) }
            if (attempt.isFailure) {
                failures += "$fullName: inbound calculation cannot be rebuilt: ${attempt.exceptionOrNull()}"
                continue
            }
            val rebuilt = attempt.getOrThrow()
            val output = BoundModel.ofJson(input).replaceComputation(fullName, rebuilt)
            val reread = KernelAdapter.checkAndOpen(output).readComputation(fullName)
            if (reread !is ReadComputation.Converted) failures += "$fullName: re-admitted write read $reread"
            else {
                assertEquals(original.computedField(), reread.computation().computedField(), "$fullName rebuilt target kind")
                assertEquals(original.alternatives(), reread.computation().alternatives(), "$fullName rebuilt typed operands")
                assertEquals(original.draft(), reread.computation().draft(), "$fullName read/rebuild/write/re-admit identity")
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    private fun rebuild(computation: Computation): Computation = model.computation(computation.computedField())
        .group(group).name(computation.name()).calc(computation.alternatives().single().operation())
        .message(*computation.messages().toTypedArray()).build()

    private data class ComparisonProbe(val id: String, val condition: Condition, val codes: List<String>)

    @Test
    fun customNumericEqualityIsRefusedOnEitherSideWhileTextEqualityIsAdmitted() {
        val rejection = listOf("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING")
        val probes = buildList {
            for (operator in listOf(CompareOp.EQ, CompareOp.NE)) for (customLeft in listOf(true, false)) {
                for ((source, calculation) in listOf("direct-number" to quantity.asNumber().expression(),
                    "numeric-constant" to NumberLiteral.of(1))) {
                    val left = if (customLeft) custom.asString().expression() else calculation
                    val right = if (customLeft) calculation else custom.asString().expression()
                    add(ComparisonProbe("$source/$operator/custom-${if (customLeft) "left" else "right"}",
                        ComparisonCondition(left, operator, right), rejection))
                }
                val left = if (customLeft) custom.asString().expression() else text.asString().expression()
                val right = if (customLeft) text.asString().expression() else custom.asString().expression()
                add(ComparisonProbe("direct-text/$operator/custom-${if (customLeft) "left" else "right"}",
                    ComparisonCondition(left, operator, right), emptyList()))
            }
        }
        assertEquals(12, probes.size)
        assertEquals(12, probes.map { it.id }.distinct().size)
        val checks = mutableListOf<Executable>()
        for ((index, probe) in probes.withIndex()) {
            val rule = model.rule(group).name("CompareIdentifier$index").code("COMPARE")
                .errorField(custom).`when`(probe.condition).message(Dsl.en("Review the parcel identifier.")).build()
            val kernel = validator.check(json, rule.draft())
            val kernelCodes = kernel.diagnostics().map { it.code().orElse("<codeless>") }.sorted()
            val portable = check(ModelSource(ModelWriter.addRule(json, rule))).findings
                .filter { it.justifiesRejection }.map { it.code }.sorted()
            println("${probe.id}|kernel=$kernelCodes|portable=$portable")
            checks += Executable {
                assertEquals(probe.codes.isEmpty(), kernel.ok(), "${probe.id}: ${kernel.diagnostics()}")
                assertEquals(probe.codes, kernelCodes, "${probe.id} kernel admission")
                assertEquals(kernelCodes, portable, "${probe.id} portable admission")
            }
        }
        assertAll("all twelve independent comparison admissions", checks)
    }

    private companion object { const val MESSAGE = "Review the derived parcel text." }
}
