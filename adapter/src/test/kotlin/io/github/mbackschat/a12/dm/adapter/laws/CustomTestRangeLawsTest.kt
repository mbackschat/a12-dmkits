package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.interpreter.*
import io.github.mbackschat.a12.dm.adapter.testing.KernelMessageAddressProjector.address
import java.util.Locale
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-10-10-12: a custom validator answering the key `testIDNummer`. Kernel 30.8.1 reserved it as a test-range
 * finding that reported without excluding the value; kernel 31.1.1 dropped the reservation (`ValidType.Test` and
 * `TEST_ID_NUMMER` are gone at `eec9134`), so it is an ordinary rejection, measured here row for row against the
 * project-owned `PROJECT_REJECT` answer on every route and mode (KERNEL-FINDINGS KF669). The case, suffix and space
 * variants stay as the controls that it was never a prefix or case-insensitive match.
 */
class CustomTestRangeLawsTest {
    private enum class Answer(val code: String?) {
        ACCEPT(null), REJECT("PROJECT_REJECT"), TEST("testIDNummer"),
        CASE("TestIDNummer"), SUFFIX("testIDNummerX"), SPACE("testIDNummer "), ORDINARY_ID("ungueltigeIDNummer"),
    }
    private val measurementAnswers = listOf(Answer.ACCEPT, Answer.REJECT, Answer.TEST)
    private enum class Source(val value: String?) { PLAIN("ABCD"), CR("A\r\r\nB"), EMPTY(""), ABSENT(null) }
    private enum class Route { FULL, INCLUDED, EXCLUDED, COMPUTE }

    private class Recorder(private val answer: Answer, private val targetAnswer: Answer = Answer.ACCEPT) : ICustomFieldTypeFactory {
        val calls = mutableListOf<String>()
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            Optional.of(object : ICustomFieldValidator {
                override fun validate(value: String, param: ICustomFieldTypeValidationParam, display: Boolean): Optional<ICustomFieldTypeCheckError> {
                    calls += "$name:$value"
                    return (if (name == "ParcelIdentifier") answer.code else targetAnswer.code)?.let { code -> Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorKey() = code
                        override fun getErrorMessage() = "Review the supplied parcel identifier."
                    }) } ?: Optional.empty()
                }
            })
    }

    private fun model(unique: Boolean, observeMembership: Boolean = false): String {
        val base = DocumentModelDraft.named("parcel-custom-result").locale("en_US")
            .rootGroup("Parcel") { parcel ->
                parcel.customField("Identifier") { it.type("ParcelIdentifier") }
                    .stringField("Marker") { }
                    .customField("Copy") { it.type("CopiedIdentifier") }.customField("Same") { it.type("CopiedIdentifier") }.customField("Dependent") { it.type("CopiedIdentifier") }
                    .numberField("Size") { it.fractionDigits(0) }
                    .numberField("DependentSize") { it.fractionDigits(0) }
                    .numberField("Independent") { it.fractionDigits(0) }
                    .group("Tags") { tags ->
                        tags.repeatable(2).stringField("Key") { }
                        if (unique) tags.indexField("Key")
                    }
            }.build().toJson()
        val m = BoundModel.ofJson(base)
        val group = m.group("/Parcel")
        val source = m.custom("/Parcel/Identifier")
        val marker = m.str("/Parcel/Marker")
        val copy = m.custom("/Parcel/Copy")
        val same = m.custom("/Parcel/Same")
        val dependent = m.custom("/Parcel/Dependent")
        val size = m.num("/Parcel/Size")
        val dependentSize = m.num("/Parcel/DependentSize")
        val independent = m.num("/Parcel/Independent")
        val rules = (listOf(
            "VALUE" to source.eq("ABCD"),
            "LENGTH" to source.length().eq(4),
            "MARKER" to marker.filled(),
        ) + if (observeMembership) listOf("MEMBERSHIP" to marker.filled().and(Dsl.customCondition("ObserveMembership"))) else emptyList()).map { (code, condition) ->
            m.rule(group).name("Check$code").errorField(if (code == "MARKER" || code == "MEMBERSHIP") marker.field() else source.field())
                .`when`(condition).code(code).severity(Severity.ERROR).message(Dsl.en("Review this parcel.")).build()
        }
        val computations = listOf(
            m.computation(copy).name("CopyIdentifier").calc(source.asString()).message(Dsl.en("Review the copy.")).build(),
            m.computation(same).name("SameIdentifier").calc(source.asString()).message(Dsl.en("Review the copy.")).build(),
            m.computation(dependent).name("DependentIdentifier").calc(copy.asString()).message(Dsl.en("Review the copy.")).build(),
            m.computation(size).name("IdentifierSize").calc(source.length()).message(Dsl.en("Review the size.")).build(),
            m.computation(dependentSize).name("CopiedSize").calc(copy.length()).message(Dsl.en("Review the size.")).build(),
            m.computation(independent).name("IndependentValue").calc(io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral.of(7)).message(Dsl.en("Review the value.")).build(),
        )
        return computations.fold(rules.fold(base, TypedRules::inject), TypedRules::inject)
    }

    private fun document(source: Source): String {
        val fields = linkedMapOf<String, Any>(
            "Marker" to "ready", "Copy" to "stale", "Same" to (source.value?.replace("\r\n", "\n") ?: "stale"),
            "Dependent" to "stale", "Size" to 99, "DependentSize" to 99, "Independent" to 7,
            "Tags" to listOf(mapOf("Key" to "priority")),
        )
        source.value?.let { fields["Identifier"] = it }
        return ObjectMapper().writeValueAsString(mapOf("Parcel" to fields))
    }

    @Test
    fun coreModesSeparateReportAndAbort() {
        val observed = mutableListOf<List<Any>>()
        for (unique in listOf(false, true)) {
            val json = model(unique, observeMembership = true)
            val dynamic = RuntimeLaws.kernelEngine(json)
            val static = RuntimeLaws.kernelJavaEngine(json)
            val oracles = listOf(dynamic.coreModeOracle(), static.coreModeOracle())
            oracles.forEach { assertEquals(false, it.hasUniqueEntities(), "A12 index fields do not activate the legacy unique-entity profile") }
            for (answer in measurementAnswers) for (source in Source.entries) for (route in listOf(Route.FULL, Route.INCLUDED, Route.EXCLUDED)) {
                for (mode in listOf(com.mgmtp.a12.kernel.core.rt.a12internal.ValidationMode.ValidationType.fullExamination,
                    com.mgmtp.a12.kernel.core.rt.a12internal.ValidationMode.ValidationType.fullExaminationWithAbortOnFormalErrors)) {
                    val results = oracles.mapIndexed { strategy, oracle ->
                        val recorder = Recorder(answer)
                        val instance = if (strategy == 0) dynamic.instance(document(source)) else static.instance(document(source))
                        val relevant = if (route == Route.FULL) null else setOf(RuntimeLaws.Relevant("/Parcel/Marker", listOf(1, 1))) +
                            if (route == Route.INCLUDED) setOf(RuntimeLaws.Relevant("/Parcel/Identifier", listOf(1, 1))) else emptySet()
                        oracle.validate(instance, relevant, mode, recorder) + ("calls" to recorder.calls.toList())
                    }
                    observed += listOf(unique, answer.name, source.name, route.name, mode.name, results)
                    assertEquals(results[0], results[1], "$unique/$answer/$source/$route/$mode")
                }
            }
        }
        assertRetained("modes", observed)
    }

    @Test
    fun modernRoutesRetainAdmissionContinuationAndEveryComputationChannel() {
        val observed = mutableListOf<List<Any>>()
        for (unique in listOf(false, true)) {
            val json = model(unique)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json))
            val dynamic = RuntimeLaws.kernelEngine(json)
            val static = RuntimeLaws.kernelJavaEngine(json)
            for (answer in measurementAnswers) for (source in Source.entries) for (route in Route.entries) {
                val results = (0..1).map { strategy ->
                    val recorder = Recorder(answer)
                    val instance = if (strategy == 0) dynamic.instance(document(source)) else static.instance(document(source))
                    val relevant = setOf(RuntimeLaws.Relevant("/Parcel/Marker", listOf(1, 1))) +
                        if (route == Route.INCLUDED) setOf(RuntimeLaws.Relevant("/Parcel/Identifier", listOf(1, 1))) else emptySet()
                    val observation = when (route) {
                        Route.FULL -> mapOf("messages" to CustomTestRangeOracle.messages(
                            if (strategy == 0) dynamic.validationMessages(instance, null, Locale.US, recorder)
                            else static.validationMessages(instance, null, Locale.US, recorder)))
                        Route.INCLUDED, Route.EXCLUDED -> mapOf("messages" to CustomTestRangeOracle.messages(if (strategy == 0)
                            dynamic.partialValidationMessages(instance, relevant, null, Locale.US, recorder)
                            else static.partialValidationMessages(instance, relevant, null, Locale.US, recorder)))
                        Route.COMPUTE -> {
                            val changes = if (strategy == 0) dynamic.computation(instance, null, Locale.US, recorder)
                                else static.computation(instance, null, Locale.US, recorder)
                            val result = if (strategy == 0) dynamic.computeResults(instance, null, Locale.US, recorder)
                                else static.computeResults(instance, null, Locale.US, recorder)
                            mapOf(
                                "changes" to changes.outcomes().map { mapOf("path" to it.path(), "pointer" to it.pointer().toString(),
                                    "value" to it.value().orElse(null), "cleared" to it.cleared(), "errored" to it.errored()) },
                                "formal" to CustomTestRangeOracle.messages(changes.formalErrorsInOperands(), sorted = false),
                                "successes" to result.withoutErrors().map { mapOf("pointer" to it.pointer().toString(),
                                    "value" to it.value().orElse(null), "errorMessage" to it.errorMessage().map { message ->
                                        CustomTestRangeOracle.messages(listOf(message)) }.orElse(null)) },
                                "applied" to result.applied(),
                            )
                        }
                    }
                    observation + ("calls" to recorder.calls.toList())
                }
                observed += listOf(unique, answer.name, source.name, route.name, results)
                assertEquals(results[0], results[1], "$unique/$answer/$source/$route")
            }
        }
        assertRetained("modern", observed)
    }

    @Test
    fun publicInterpreterSeparatesStoredAdmissionFromComputedTargetErrors() {
        val json = model(false)
        val dynamic = RuntimeLaws.kernelEngine(json)
        val static = RuntimeLaws.kernelJavaEngine(json)
        val prepared = PreparedModel.loadExpandedJson(json)
        val failures = mutableListOf<String>()
        for (answer in Answer.entries) for (target in Answer.entries) for (source in Source.entries) {
            if ((answer !in measurementAnswers || target !in measurementAnswers) &&
                (source != Source.PLAIN || (answer != Answer.ACCEPT && target != Answer.ACCEPT))) continue
            val calls = mutableListOf<String>()
            val registry = CustomFieldTypeRegistry.builder()
            for ((name, selected) in listOf("ParcelIdentifier" to answer, "CopiedIdentifier" to target)) {
                registry.register(name) { value, _ ->
                    calls += "$name:$value"
                    selected.code?.let {
                    CustomFieldValidationResult.Rejected(it, "Review the supplied parcel identifier.")
                } ?: CustomFieldValidationResult.Accepted }
            }
            val interpreter = prepared.createInterpreter(InterpreterConfiguration.builder().customFieldTypes(registry.build()).build())
            val input = document(source)
            val snapshot = prepared.documents.readDocument(input)
            val computed = interpreter.compute(snapshot)
            val computeCalls = calls.toList()
            val actual = listOf(
                computed.actions.map { "${address(A12PartiallyKnownMultiPointer.of(it.pointer.path, it.pointer.coordinates))}|${it.outcome}|${it.value}" }.sorted(),
                computed.formalErrorsInOperands.map { "${it.code}|${it.type.name}_ERROR|${address(it.errorPath)}" }.sorted(),
                computed.results.filter { it.outcome == ComputationOutcome.VALUE }.map { "${address(A12PartiallyKnownMultiPointer.of(it.pointer.path, it.pointer.coordinates))}|${it.value}" }.sorted(),
                computed.results.filter { it.outcome == ComputationOutcome.ERRORED }.map { "${address(A12PartiallyKnownMultiPointer.of(it.pointer.path, it.pointer.coordinates))}|${it.value}|${it.diagnostic?.code}" }.sorted(),
                ObjectMapper().readTree(prepared.documents.encodeDocument(computed.applyTo(snapshot))),
            )
            for (strategy in 0..1) {
                val recorder = Recorder(answer, target)
                val instance = if (strategy == 0) dynamic.instance(input) else static.instance(input)
                val changes = if (strategy == 0) dynamic.computation(instance, null, Locale.US, recorder) else static.computation(instance, null, Locale.US, recorder)
                val expectedCalls = recorder.calls.toList()
                if (expectedCalls != computeCalls) failures += "$answer/$target/$source/$strategy/calls expected=$expectedCalls actual=$computeCalls"
                val result = if (strategy == 0) dynamic.computeResults(instance, null, Locale.US, recorder) else static.computeResults(instance, null, Locale.US, recorder)
                fun pointer(p: com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer) = address(A12PartiallyKnownMultiPointer.of(p.fullName(), p.repetitionIndexes()))
                val expected = listOf(
                    changes.outcomes().map { "${pointer(it.pointer())}|${if (it.errored()) "ERRORED" else if (it.cleared()) "CLEARED" else "VALUE"}|${it.value().orElse(null)}" }.sorted(),
                    changes.formalErrorsInOperands().map { "${it.errorCode}|${it.messageType}|${address(A12PartiallyKnownMultiPointer.of(it.errorFieldPointer.fullName(), it.errorFieldPointer.repetitionIndexes()))}" }.sorted(),
                    result.withoutErrors().map { "${pointer(it.pointer())}|${it.value().orElse(null)}" }.sorted(),
                    result.errored().map { "${pointer(it.pointer())}|${it.value().orElse(null)}|${it.errorMessage().orElseThrow().errorCode}" }.sorted(),
                    ObjectMapper().readTree(result.applied()),
                )
                if (expected != actual) failures += "$answer/$target/$source/$strategy expected=$expected actual=$actual"
                if (source.value?.isNotEmpty() == true) {
                    assertEquals(listOf("ParcelIdentifier:${source.value}"), computeCalls.filter { it.startsWith("ParcelIdentifier:") }, "raw source once per compute")
                } else assertEquals(emptyList<String>(), computeCalls.filter { it.startsWith("ParcelIdentifier:") })
                for (route in listOf(Route.FULL, Route.INCLUDED, Route.EXCLUDED)) {
                    val relevant = setOf(RuntimeLaws.Relevant("/Parcel/Marker", listOf(1, 1))) +
                        if (route == Route.INCLUDED) setOf(RuntimeLaws.Relevant("/Parcel/Identifier", listOf(1, 1))) else emptySet()
                    calls.clear()
                    val validationRecorder = Recorder(answer, target)
                    val findings = if (route == Route.FULL) interpreter.validateFull(snapshot).findings else interpreter.validatePart(snapshot,
                        relevant.map { prepared.model.relevantPointer(it.path(), it.reps().map(RelevantCoordinate::exact)) }).findings
                    val oracle = if (route == Route.FULL) {
                        if (strategy == 0) dynamic.validationMessages(instance, null, Locale.US, validationRecorder)
                        else static.validationMessages(instance, null, Locale.US, validationRecorder)
                    } else {
                        if (strategy == 0) dynamic.partialValidationMessages(instance, relevant, null, Locale.US, validationRecorder)
                        else static.partialValidationMessages(instance, relevant, null, Locale.US, validationRecorder)
                    }
                    if (validationRecorder.calls.sorted() != calls.sorted()) failures += "$answer/$target/$source/$strategy/$route callbacks expected=${validationRecorder.calls} actual=$calls"
                    val want = oracle.map { "${it.errorCode}|${it.messageType}|${address(A12PartiallyKnownMultiPointer.of(it.errorFieldPointer.fullName(), it.errorFieldPointer.repetitionIndexes()))}" }.sorted()
                    val got = findings.map { "${it.code}|${it.type.name}_ERROR|${address(it.errorPath)}" }.sorted()
                    if (want != got) failures += "$answer/$target/$source/$strategy/$route expected=$want actual=$got"
                }
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun everyAnswerReachesTheCustomConditionsInvalidPointerSetAsBothKernelsReport() {
        val json = model(false, observeMembership = true)
        val dynamic = RuntimeLaws.kernelEngine(json)
        val static = RuntimeLaws.kernelJavaEngine(json)
        val prepared = PreparedModel.loadExpandedJson(json)
        for (answer in measurementAnswers) for (source in Source.entries) for (route in listOf(Route.FULL, Route.INCLUDED, Route.EXCLUDED)) {
            val observed = mutableListOf<List<String>>()
            val fields = CustomFieldTypeRegistry.builder()
                .register("ParcelIdentifier") { _, _ -> answer.code?.let {
                    CustomFieldValidationResult.Rejected(it, "Review the supplied parcel identifier.")
                } ?: CustomFieldValidationResult.Accepted }
                .register("CopiedIdentifier") { _, _ -> CustomFieldValidationResult.Accepted }.build()
            val conditions = CustomConditionRegistry.builder().register("ObserveMembership", CustomCondition { invocation ->
                observed += invocation.formallyIncorrectEntities.map {
                    address(A12PartiallyKnownMultiPointer.of(it.path, it.coordinates))
                }.sorted()
                false
            }).build()
            val interpreter = prepared.createInterpreter(InterpreterConfiguration.builder().customFieldTypes(fields).customConditions(conditions).build())
            val input = document(source)
            val relevant = if (route == Route.FULL) null else setOf(RuntimeLaws.Relevant("/Parcel/Marker", listOf(1, 1))) +
                if (route == Route.INCLUDED) setOf(RuntimeLaws.Relevant("/Parcel/Identifier", listOf(1, 1))) else emptySet()
            val snapshot = prepared.documents.readDocument(input)
            if (relevant == null) interpreter.validateFull(snapshot)
            else interpreter.validatePart(snapshot, relevant.map { prepared.model.relevantPointer(it.path(), it.reps().map(RelevantCoordinate::exact)) })
            assertEquals(1, observed.size, "$answer/$source/$route continuation")
            for (strategy in 0..1) {
                val expected = mutableListOf<List<String>>()
                val oracle = if (strategy == 0) dynamic.coreModeOracle() else static.coreModeOracle()
                oracle.validate(if (strategy == 0) dynamic.instance(input) else static.instance(input), relevant,
                    com.mgmtp.a12.kernel.core.rt.a12internal.ValidationMode.ValidationType.fullExamination, Recorder(answer)) { pointers ->
                    expected += pointers.map { address(A12PartiallyKnownMultiPointer.of(it.fullName(), it.repetitionIndexes())) }.sorted()
                }
                assertEquals(expected, observed, "$answer/$source/$route/$strategy invalid pointers")
            }
        }
    }

    @Test
    fun computedAdmissionDoesNotDependOnTheTargetsStoredValue() {
        val json = model(false)
        val dynamic = RuntimeLaws.kernelEngine(json)
        val static = RuntimeLaws.kernelJavaEngine(json)
        val prepared = PreparedModel.loadExpandedJson(json)
        val failures = mutableListOf<String>()
        for (old in listOf(null, "", "stale")) for (answer in measurementAnswers) {
            val fields = linkedMapOf<String, Any>("Identifier" to "ABCD", "Marker" to "ready")
            if (old != null) for (field in listOf("Copy", "Same", "Dependent")) fields[field] = old
            val input = ObjectMapper().writeValueAsString(mapOf("Parcel" to fields))
            val calls = mutableListOf<String>()
            val registry = CustomFieldTypeRegistry.builder()
                .register("ParcelIdentifier") { value, _ -> calls += "ParcelIdentifier:$value"; CustomFieldValidationResult.Accepted }
                .register("CopiedIdentifier") { value, _ ->
                    calls += "CopiedIdentifier:$value"
                    answer.code?.let { CustomFieldValidationResult.Rejected(it, "Review the supplied parcel identifier.") }
                        ?: CustomFieldValidationResult.Accepted
                }.build()
            val interpreter = prepared.createInterpreter(InterpreterConfiguration.builder().customFieldTypes(registry).build())
            val result = interpreter.compute(prepared.documents.readDocument(input))
            val got = result.actions.map {
                "${address(A12PartiallyKnownMultiPointer.of(it.pointer.path, it.pointer.coordinates))}|${it.outcome}|${it.value}"
            }.sorted()
            for (strategy in 0..1) {
                val recorder = Recorder(Answer.ACCEPT, answer)
                val expected = if (strategy == 0) dynamic.computation(dynamic.instance(input), null, Locale.US, recorder)
                    else static.computation(static.instance(input), null, Locale.US, recorder)
                val want = expected.outcomes().map {
                    "${address(A12PartiallyKnownMultiPointer.of(it.pointer().fullName(), it.pointer().repetitionIndexes()))}|${if (it.errored()) "ERRORED" else if (it.cleared()) "CLEARED" else "VALUE"}|${it.value().orElse(null)}"
                }.sorted()
                if (want != got) failures += "old=$old/$answer/$strategy actions expected=$want actual=$got"
                if (recorder.calls != calls) failures += "old=$old/$answer/$strategy calls expected=${recorder.calls} actual=$calls"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun aRejectionBesideADuplicateIndexReportsBothOnEveryRow() {
        val base = DocumentModelDraft.named("parcel-custom-index").locale("en_US")
            .rootGroup("Parcel") { p -> p.group("Tags") { tags ->
                tags.repeatable(2).customField("Identifier") { it.type("ParcelIdentifier") }
                    .customField("Copy") { it.type("CopiedIdentifier") }.indexField("Identifier")
            } }.build().toJson()
        val m = BoundModel.ofJson(base)
        val tags = m.group("/Parcel/Tags")
        val identifier = m.custom("/Parcel/Tags/Identifier")
        val copy = m.custom("/Parcel/Tags/Copy")
        val rule = m.rule(tags).name("ReadableIdentifier").errorField(identifier.field())
            .`when`(identifier.filled()).code("READABLE").message(Dsl.en("Review the identifier.")).build()
        val computation = m.computation(copy).name("CopyIdentifier").calc(identifier.asString())
            .message(Dsl.en("Review the copy.")).build()
        val json = TypedRules.inject(TypedRules.inject(base, rule), computation)
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json))
        val dynamic = RuntimeLaws.kernelEngine(json)
        val static = RuntimeLaws.kernelJavaEngine(json)
        val prepared = PreparedModel.loadExpandedJson(json)
        for (answer in measurementAnswers) for (second in listOf("ABCD", "EFGH")) {
            val input = ObjectMapper().writeValueAsString(mapOf("Parcel" to mapOf("Tags" to
                listOf("ABCD", second).map { mapOf("Identifier" to it, "Copy" to "stale") })))
            val registry = CustomFieldTypeRegistry.builder().register("CopiedIdentifier") { _, _ -> CustomFieldValidationResult.Accepted }.register("ParcelIdentifier") { _, _ ->
                answer.code?.let { CustomFieldValidationResult.Rejected(it, "Review the supplied parcel identifier.") }
                    ?: CustomFieldValidationResult.Accepted
            }.build()
            val interpreter = prepared.createInterpreter(InterpreterConfiguration.builder().customFieldTypes(registry).build())
            val snapshot = prepared.documents.readDocument(input)
            val findings = interpreter.validateFull(snapshot).findings
            val computedAttempt = runCatching { interpreter.compute(snapshot) }
            for (strategy in 0..1) {
                val recorder = Recorder(answer)
                val instance = if (strategy == 0) dynamic.instance(input) else static.instance(input)
                val expected = if (strategy == 0) dynamic.validationMessages(instance, null, Locale.US, recorder)
                    else static.validationMessages(instance, null, Locale.US, recorder)
                assertEquals(expected.map { "${it.errorCode}|${it.messageType}|${address(A12PartiallyKnownMultiPointer.of(it.errorFieldPointer.fullName(), it.errorFieldPointer.repetitionIndexes()))}" }.sorted(),
                    findings.map { "${it.code}|${it.type.name}_ERROR|${address(it.errorPath)}" }.sorted(), "$answer/$second/$strategy/messages")
                val attempt = runCatching {
                    if (strategy == 0) dynamic.computation(instance, null, Locale.US, recorder)
                    else static.computation(instance, null, Locale.US, recorder)
                }
                val changes = attempt.getOrThrow()
                val computed = computedAttempt.getOrThrow()
                assertEquals(changes.outcomes().map { "${address(A12PartiallyKnownMultiPointer.of(it.pointer().fullName(), it.pointer().repetitionIndexes()))}|${it.errored()}|${it.value().orElse(null)}" }.sorted(),
                    computed.actions.map { "${address(A12PartiallyKnownMultiPointer.of(it.pointer.path, it.pointer.coordinates))}|${it.outcome == ComputationOutcome.ERRORED}|${it.value}" }.sorted(),
                    "$answer/$second/$strategy/actions")
            }
        }
    }

    private fun assertRetained(name: String, observed: List<List<Any>>) {
        val mapper = ObjectMapper()
        val expected = javaClass.getResourceAsStream("/custom-test-range/$name.json")!!.use { mapper.readTree(it) }
        // KF541's indexed control and both strategies share expectations, but each execution is checked separately.
        val variants = observed.groupBy { it.first() }
        assertEquals(setOf(false, true), variants.keys, "$name: indexed controls")
        for ((indexed, rows) in variants) for (strategy in 0..1) {
            val actual = rows.map { it.drop(1).dropLast(1) + listOf((it.last() as List<*>)[strategy]) }
            assertEquals(expected, mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(actual), "$name/indexed=$indexed/strategy=$strategy")
        }
    }
}
