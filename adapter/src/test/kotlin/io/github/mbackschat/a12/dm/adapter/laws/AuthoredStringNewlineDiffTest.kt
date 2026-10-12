package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.*
import io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.Optional

class AuthoredStringNewlineDiffTest {
    private enum class Break(val text: String) {
        CR("A\rB"), LF("A\nB"), CRLF("A\r\nB"), REPEATED_CR("A\r\r\nB"), QUOTE("A\"B")
    }

    private val factory = object : ICustomFieldTypeFactory {
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            Optional.of(ICustomFieldValidator { _, _, _ -> Optional.empty() })
    }
    private val registry = CustomFieldTypeRegistry.builder()
        .register("DispatchText") { _, _ -> CustomFieldValidationResult.Accepted }.build()

    private fun model(custom: Boolean): String {
        val base = DocumentModelDraft.named("dispatch-note-literals").locale("en_US")
            .rootGroup("Dispatch") { group ->
                for (name in listOf("Note", "Copy") + Break.entries.map { "Literal${it.name}" }) {
                    if (custom && name in listOf("Note", "Copy")) group.customField(name) { it.type("DispatchText") }
                    else group.stringField(name) { it.lineBreaks() }
                }
                for (literal in Break.entries) group.numberField("Guard${literal.name}") { it.fractionDigits(0) }
            }.build().toJson()
        val bound = BoundModel.ofJson(base)
        val group = bound.group("/Dispatch")
        fun text(name: String): StringExpr = if (custom) bound.custom("/Dispatch/$name").asString()
            else StringExpr(bound.str("/Dispatch/$name").field().value())
        val source = text("Note")
        val copy = text("Copy")
        var json = TypedRules.inject(base, bound.computation(bound.field("/Dispatch/Copy"))
            .name("CopyNote").calc(source).message(Dsl.en("Review the dispatch note.")).build())
        for (literal in Break.entries) {
            json = TypedRules.inject(json, bound.rule(group).name("Compare${literal.name}")
                .errorField(bound.field("/Dispatch/Note")).`when`(source.eq(literal.text))
                .code(literal.name).message(Dsl.en("Review the dispatch note.")).build())
            json = TypedRules.inject(json, bound.computation(bound.field("/Dispatch/Literal${literal.name}"))
                .name("Write${literal.name}").calc(StringLiteral(literal.text))
                .message(Dsl.en("Review the assigned note.")).build())
            json = TypedRules.inject(json, bound.computation(bound.num("/Dispatch/Guard${literal.name}"))
                .name("Assess${literal.name}").`when`(copy.eq(literal.text)).calc(NumberLiteral.of(1))
                .message(Dsl.en("Review the copied note.")).build())
        }
        return json
    }

    @Test
    fun sourceAndLiteralDecodingRemainSeparateAcrossValidationAndComputation() {
        val failures = mutableListOf<String>()
        for (custom in listOf(false, true)) {
            val json = model(custom)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json))
            val dynamic = RuntimeLaws.kernelEngine(json)
            val static = RuntimeLaws.kernelJavaEngine(json)
            val prepared = PreparedModel.loadExpandedJson(json)
            val interpreter = prepared.createInterpreter(InterpreterConfiguration.builder().customFieldTypes(registry).build())
            for (raw in Break.entries) {
                val input = ObjectMapper().writeValueAsString(mapOf("Dispatch" to mapOf("Note" to raw.text)))
                val snapshot = prepared.documents.readDocument(input)
                val gotMessages = interpreter.validateFull(snapshot).findings.map {
                    MessageSignature(it.code, "${it.type}_ERROR", pointerComparisonKey(it.errorPath.fullName, it.errorPath.repetitionIndexes),
                        it.referenced.map { p -> pointerComparisonKey(p.fullName, p.repetitionIndexes) }.sorted(),
                        it.fillToFix.map { p -> pointerComparisonKey(p.fullName, p.repetitionIndexes) }.sorted())
                }.sorted()
                val computed = interpreter.compute(snapshot)
                val gotValues = computed.results.filter { it.outcome != ComputationOutcome.CLEARED }.map { "${it.pointer.path}|${it.outcome}|${it.value}" }.sorted()
                for (strategy in 0..1) {
                    val instance = if (strategy == 0) dynamic.instance(input) else static.instance(input)
                    val messages = if (strategy == 0) dynamic.validationMessages(instance, null, Locale.US, factory)
                        else static.validationMessages(instance, null, Locale.US, factory)
                    fun pointer(p: com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer) =
                        pointerComparisonKey(p.fullName(), p.repetitionIndexes())
                    val wantMessages = messages.map {
                        MessageSignature(it.errorCode, it.messageType.name, pointer(it.errorFieldPointer),
                            it.referencedFieldsPointers.map(::pointer).sorted(), it.refOmissionErrorResponsiblePointers.map(::pointer).sorted())
                    }.sorted()
                    val matches = when (raw) {
                        Break.CR -> listOf("CR")
                        Break.LF, Break.CRLF -> listOf("CRLF", "LF")
                        Break.REPEATED_CR -> listOf("REPEATED_CR")
                        Break.QUOTE -> listOf("QUOTE")
                    }
                    assertEquals(matches, wantMessages.map { it.code }.sorted(), "$custom/$raw/$strategy positive control")
                    val expected = if (strategy == 0) dynamic.computeResults(instance, null, Locale.US, factory)
                        else static.computeResults(instance, null, Locale.US, factory)
                    val wantValues = (expected.withoutErrors().map { "${it.pointer().fullName()}|VALUE|${it.value().orElse(null)}" } +
                        expected.errored().map { "${it.pointer().fullName()}|ERRORED|${it.value().orElse(null)}" }).sorted()
                    if (wantMessages != gotMessages) failures += "$custom/$raw/$strategy messages: $wantMessages != $gotMessages"
                    if (wantValues != gotValues) failures += "$custom/$raw/$strategy values: $wantValues != $gotValues"
                }
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun computationFixtureJsonPreservesAllControlCharactersInEverySplicedExpression() {
        val base = DocumentModelDraft.named("dispatch-fixture-strings").locale("en_US")
            .rootGroup("Dispatch") { it.stringField("Source") { s -> s.lineBreaks() }
                .stringField("Copy") { s -> s.lineBreaks() } }.build().toJson()
        val bound = BoundModel.ofJson(base)
        val source = bound.str("/Dispatch/Source")
        val copy = bound.str("/Dispatch/Copy")
        val text = (0..31).map(Int::toChar).joinToString("") + "\\\""
        for (tiered in listOf(false, true)) {
            val builder = bound.computation(copy).name("CopyDispatchText").message(Dsl.en("Review the copy."))
            val computation = if (tiered) builder.commonPrecondition(source.eq(text))
                .`when`(source.eq(text)).calc(StringLiteral(text)).build()
            else builder.calc(StringLiteral(text)).build()
            val tree = ObjectMapper().readTree(TypedRules.inject(base, computation))
            val body = tree.findValue("Computation")
            val draft = computation.draft()
            assertEquals(draft.alternatives().single().operation(), body.path("computationAlternatives")[0].path("operation").asText())
            if (tiered) {
                assertEquals(draft.commonPrecondition(), body.path("commonPrecondition").asText())
                assertEquals(draft.alternatives().single().precondition(), body.path("computationAlternatives")[0].path("precondition").asText())
            }
        }
    }

    @Test
    fun semanticIndexLiteralsDecodeOnceBeforeSelectingStoredKeys() {
        val base = DocumentModelDraft.named("dispatch-bin-keys").locale("en_US")
            .rootGroup("Dispatch") { group ->
                group.stringField("Reference") { }
                group.group("Bins") { bins -> bins.repeatable(4).indexField("Code")
                    .stringField("Code") { it.lineBreaks() }.numberField("Stock") { it.fractionDigits(0) } }
            }.build().toJson()
        val bound = BoundModel.ofJson(base)
        val reference = bound.str("/Dispatch/Reference")
        val stock = bound.num("/Dispatch/Bins/Stock")
        var json = base
        for ((literal, expected) in listOf(Break.CR to 33, Break.LF to 11, Break.CRLF to 11, Break.REPEATED_CR to 22, Break.QUOTE to 44)) {
            json = TypedRules.inject(json, bound.rule(bound.group("/Dispatch")).name("Select${literal.name}")
                .errorField(reference.field()).`when`(reference.filled().and(stock.forIndex(literal.text).eq(expected.toLong())))
                .code(literal.name).message(Dsl.en("Review bin stock.")).build())
        }
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json))
        val input = ObjectMapper().writeValueAsString(mapOf("Dispatch" to mapOf("Reference" to "D", "Bins" to
            listOf(Break.LF to 11, Break.REPEATED_CR to 22, Break.CR to 33, Break.QUOTE to 44).map { (key, stock) -> mapOf("Code" to key.text, "Stock" to stock) })))
        val kernel = DocumentDifferential.kernel(json, input, dynamic = true)
        assertEquals(Break.entries.map { it.name }.sorted(), kernel.messages.map { it.code }.sorted())
        DocumentDifferential.assertAgree(json, mapOf("four distinct decoded keys" to input))
    }
}
