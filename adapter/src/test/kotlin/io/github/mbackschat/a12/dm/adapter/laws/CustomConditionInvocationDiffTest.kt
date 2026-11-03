package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.apiV2.DocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine differential for the complete `CustomCondition` invocation contract. A reached callback owns
 * its hidden dependency checks: the evaluator supplies the document-wide formal-invalid pointer set,
 * partial relevance (or null in full), and the current repeated error pointer without pre-suppressing it.
 */
class CustomConditionInvocationDiffTest {

    @Test
    fun parallelCallbacksReadTheWholeDocumentAtMatchedAndUnmatchedErrorRows() {
        val structure = DocumentModelDraft.named("shipment-offers").locale("en_US")
            .rootGroup("Shipment") { root ->
                root.stringField("Reference") { }
                for (name in listOf("Offered", "Accepted")) root.group(name) { group ->
                    group.repeatable(3).indexField("Code").stringField("Code") { }.stringField("Note") { }
                }
            }.build().toJson()
        val bound = BoundModel.ofJson(structure)
        val offered = bound.str("/Shipment/Offered/Note")
        val accepted = bound.str("/Shipment/Accepted/Note")
        val json = TypedRules.inject(structure, bound.rule(bound.group("/Shipment")).name("ReviewOffers")
            .errorField(offered.field()).`when`(offered.filled().or(accepted.filled()).and(Dsl.customCondition("ObserveOffers")))
            .code("OFFERS").message(Dsl.en("Review the shipment offers.")).build())
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json))
        val input = """{"Shipment":{"Reference":"SHIP","Offered":[{"Code":"shared","Note":"offer"},{"Code":"offer","Note":"extra"}],"Accepted":[{"Code":"shared","Note":"accepted"},{"Code":"accept","Note":"other"}]}}"""
        for (fires in listOf(false, true)) {
            val observed = mutableListOf<Pair<String, Map<String, String>>>()
            val callback = object : ICustomCondition {
                override fun check(document: DocumentV2, relevant: Set<out DocumentMultiPointer>?,
                    formallyIncorrect: Set<DocumentPointer>, errorEntity: PartiallyKnownDocumentMultiPointer): Boolean {
                    assertEquals(null, relevant)
                    assertEquals(emptySet<DocumentPointer>(), formallyIncorrect)
                    val fields = document.filledFields().map { (pointer, value) -> kPointer(pointer) to value.toString() }
                    assertEquals(9, fields.size)
                    observed += kPointer(errorEntity) to fields.toMap()
                    return fires
                }
            }
            val dynamic = RuntimeLaws.kernelEngine(json)
            val messages = dynamic.validationSignaturesSorted(dynamic.instance(input), java.util.Locale.US, null,
                mapOf("ObserveOffers" to callback))
            val expected = observed.sortedBy { it.first }
            assertEquals(listOf("/Shipment/Offered/Note@1,-5,1", "/Shipment/Offered/Note@1,1,1", "/Shipment/Offered/Note@1,2,1"), expected.map { it.first })
            assertEquals(9, expected.first().second.size)
            assertEquals(setOf("SHIP"), expected.map { it.second["/Shipment/Reference@1,1"] }.toSet())
            observed.clear()
            val static = RuntimeLaws.kernelJavaEngine(json)
            assertEquals(messages, static.validationSignaturesSorted(static.instance(input), java.util.Locale.US, null,
                mapOf("ObserveOffers" to callback)))
            assertEquals(expected, observed.sortedBy { it.first })
            observed.clear()
            val def = ModelLoader.load(json)
            val evaluator = DmInterpreter(def, customConditions = mapOf("ObserveOffers" to CustomCondition { data, relevant, invalid, error ->
                assertEquals(null, relevant)
                assertEquals(emptySet<CustomConditionFieldPointer>(), invalid)
                val fields = data.fields()
                assertEquals(9, fields.size)
                observed += iPointer(error) to fields.associate { iPointer(it) to data.getValue(it).orEmpty() }
                fires
            }))
            val actual = evaluator.validate(def.readDocumentJson(input))
            assertEquals(emptyList<Any>(), actual.unsupported)
            assertEquals(if (fires) 3 else 0, actual.messages.count { it.code == "OFFERS" })
            assertEquals(expected, observed.sortedBy { it.first })
        }
    }

    private val base = DocumentModelDraft.named("claim-review").locale("en_US")
        .rootGroup("Claim") { claim ->
            claim.stringField("Error") {}
                .stringField("RequiredCode") { it.required() }
                .stringField("CleanNote") {}
                .group("Checks") { checks ->
                    checks.repeatable(3)
                        .stringField("Error") {}
                        .numberField("Score") { it.fractionDigits(0) }
                }
        }.build().toJson()
    private val model = BoundModel.ofJson(base)
    private val root = model.group(CLAIM)
    private val checks = model.group(CHECKS)

    private fun render(scope: String, condition: Condition): String =
        RuntimeLaws.render(scope, condition)

    private fun withRule(group: String, name: String, errorRel: String, code: String, condition: Condition): String =
        RuntimeLaws.withRule(base, group, name, errorRel, code, render(group, condition))

    private fun kPointer(entity: DocumentPointer): String =
        "${entity.path}@${entity.repetitions.joinToString(",")}"

    private fun kPointer(entity: DocumentMultiPointer): String =
        "${entity.path}@${entity.repetitions.joinToString(",")}"

    private fun kPointer(entity: PartiallyKnownDocumentMultiPointer): String =
        "${entity.path}@${entity.repetitions.joinToString(",")}"

    private fun iPointer(pointer: CustomConditionFieldPointer): String =
        "${pointer.path}@${pointer.indices.joinToString(",")}"

    private fun kernelCodes(messages: List<RuntimeLaws.Msg>, vararg codes: String): List<String> =
        messages.filter { it.code() in codes }.map { it.code() }.sorted()

    private fun interpreterCodes(
        messages: List<io.github.mbackschat.a12.dm.interpreter.model.Message>,
        vararg codes: String,
    ): List<String> =
        messages.filter { it.code in codes }.map { it.code }.sorted()

    @Test
    fun callbackOwnsFullFormalSetAndCurrentRepeatedErrorPointer() {
        val condition = model.str(CHECK_ERROR).filled().and(Dsl.customCondition(EXACT_INPUTS))
        val json = withRule(CHECKS, "ExactInputs", "../Error", EXACT_CODE, condition)
        val expectedFormal = setOf(
            "$REQUIRED@1,1",
            "$SCORE@1,2,1",
        )
        val expectedError = "$CHECK_ERROR@1,2,1"
        val kernelCondition = object : ICustomCondition {
            override fun check(
                document: DocumentV2,
                relevant: Set<out DocumentMultiPointer>?,
                formallyIncorrect: Set<DocumentPointer>,
                errorEntity: PartiallyKnownDocumentMultiPointer,
            ): Boolean =
                relevant == null &&
                    formallyIncorrect.mapTo(HashSet(), ::kPointer) == expectedFormal &&
                    kPointer(errorEntity) == expectedError
        }
        val interpreterCondition = CustomCondition { _, relevant, formallyIncorrect, errorField ->
            relevant == null &&
                formallyIncorrect.mapTo(HashSet(), ::iPointer) == expectedFormal &&
                iPointer(errorField) == expectedError
        }

        val kDoc = RuntimeLaws.doc().group(CLAIM, 1)
            .emptyField(REQUIRED, 1, 1)
            .group(CHECKS, 1, 1)
            .field(CHECK_ERROR, "first", 1, 1, 1)
            .field(SCORE, "10", 1, 1, 1)
            .group(CHECKS, 1, 2)
            .field(CHECK_ERROR, "second", 1, 2, 1)
            .field(SCORE, "bad", 1, 2, 1)
        val def = ModelLoader.load(json)
        val iDoc = def.newDocument().group(CLAIM, 1)
            .emptyField(REQUIRED, 1, 1)
            .group(CHECKS, 1, 1)
            .field(CHECK_ERROR, "first", 1, 1, 1)
            .field(SCORE, "10", 1, 1, 1)
            .group(CHECKS, 1, 2)
            .field(CHECK_ERROR, "second", 1, 2, 1)
            .field(SCORE, "bad", 1, 2, 1)

        val dynamic = kernelCodes(RuntimeLaws.messages(json, kDoc, mapOf(EXACT_INPUTS to kernelCondition)), EXACT_CODE)
        val java = RuntimeLaws.kernelJavaEngine(json)
        val staticJava = kernelCodes(java.messages(java.instance(kDoc), mapOf(EXACT_INPUTS to kernelCondition)), EXACT_CODE)
        val interpreter = interpreterCodes(
            DmInterpreter(def, customConditions = mapOf(EXACT_INPUTS to interpreterCondition)).validate(iDoc).messages,
            EXACT_CODE,
        )
        assertEquals(listOf(EXACT_CODE), dynamic)
        assertEquals(dynamic, staticJava, "dynamic-Groovy and static-Java callback inputs")
        assertEquals(dynamic, interpreter, "kernel and interpreter callback inputs")
    }

    @Test
    fun partialCallbackSeesOnlyRelevanceAndNoInventedFormalError() {
        val condition = model.str(ROOT_ERROR).filled().and(Dsl.customCondition(PARTIAL_INPUTS))
        val json = withRule(CLAIM, "PartialInputs", "../Error", PARTIAL_CODE, condition)
        val expectedRelevant = setOf("$ROOT_ERROR@0,0")
        val kernelCondition = object : ICustomCondition {
            override fun check(
                document: DocumentV2,
                relevant: Set<out DocumentMultiPointer>?,
                formallyIncorrect: Set<DocumentPointer>,
                errorEntity: PartiallyKnownDocumentMultiPointer,
            ): Boolean =
                relevant?.mapTo(HashSet(), ::kPointer) == expectedRelevant &&
                    formallyIncorrect.isEmpty() &&
                    kPointer(errorEntity) == "$ROOT_ERROR@1,1"
        }
        val interpreterCondition = CustomCondition { _, relevant, formallyIncorrect, errorField ->
            relevant?.mapTo(HashSet(), ::iPointer) == expectedRelevant &&
                formallyIncorrect.isEmpty() &&
                iPointer(errorField) == "$ROOT_ERROR@1,1"
        }
        val kDoc = RuntimeLaws.doc().group(CLAIM, 1)
            .field(ROOT_ERROR, "review", 1, 1)
            .field(CLEAN, "outside this screen", 1, 1)
        val def = ModelLoader.load(json)
        val iDoc = def.newDocument().group(CLAIM, 1)
            .field(ROOT_ERROR, "review", 1, 1)
            .field(CLEAN, "outside this screen", 1, 1)
        val kRelevant = setOf(RuntimeLaws.Relevant.of(ROOT_ERROR, 0, 0))
        val iRelevant = setOf(RelevantEntity.anyRep(ROOT_ERROR))

        val dynamic = kernelCodes(
            RuntimeLaws.messagesPart(json, kDoc, kRelevant, mapOf(PARTIAL_INPUTS to kernelCondition)),
            PARTIAL_CODE,
        )
        val java = RuntimeLaws.kernelJavaEngine(json)
        val staticJava = kernelCodes(
            java.messagesPart(kDoc, kRelevant, mapOf(PARTIAL_INPUTS to kernelCondition)),
            PARTIAL_CODE,
        )
        val interpreter = interpreterCodes(
            DmInterpreter(def, customConditions = mapOf(PARTIAL_INPUTS to interpreterCondition))
                .validatePart(iDoc, iRelevant).messages,
            PARTIAL_CODE,
        )
        assertEquals(listOf(PARTIAL_CODE), dynamic)
        assertEquals(dynamic, staticJava, "dynamic-Groovy and static-Java partial callback inputs")
        assertEquals(dynamic, interpreter, "partial relevance must not become formal invalidity")
    }

    @Test
    fun blankRowCustomOrFiresWhileGuardedAndStaysSilent() {
        val custom = Dsl.customCondition(ALWAYS)
        var json = RuntimeLaws.withRule(
            base,
            CLAIM,
            "BlankOr",
            "../Error",
            OR_CODE,
            render(CLAIM, custom.or(model.str(ROOT_ERROR).filled())),
        )
        json = RuntimeLaws.withRule(
            json,
            CLAIM,
            "BlankAnd",
            "../Error",
            AND_CODE,
            render(CLAIM, model.str(ROOT_ERROR).filled().and(custom)),
        )
        val kernelCondition = object : ICustomCondition {
            override fun check(
                document: DocumentV2,
                relevant: Set<out DocumentMultiPointer>?,
                formallyIncorrect: Set<DocumentPointer>,
                errorEntity: PartiallyKnownDocumentMultiPointer,
            ): Boolean = true
        }
        val interpreterCondition = CustomCondition { _, _, _, _ -> true }
        val kDoc = RuntimeLaws.doc()
        val def = ModelLoader.load(json)
        val iDoc = def.newDocument()

        val dynamic = kernelCodes(RuntimeLaws.messages(json, kDoc, mapOf(ALWAYS to kernelCondition)), OR_CODE, AND_CODE)
        val java = RuntimeLaws.kernelJavaEngine(json)
        val staticJava = kernelCodes(java.messages(java.instance(kDoc), mapOf(ALWAYS to kernelCondition)), OR_CODE, AND_CODE)
        val interpreter = interpreterCodes(
            DmInterpreter(def, customConditions = mapOf(ALWAYS to interpreterCondition)).validate(iDoc).messages,
            OR_CODE,
            AND_CODE,
        )
        assertEquals(listOf(OR_CODE), dynamic)
        assertEquals(dynamic, staticJava, "dynamic-Groovy and static-Java blank-row eligibility")
        assertEquals(dynamic, interpreter, "custom leaf eligibility must compose through Or and And")
    }

    private companion object {
        const val CLAIM = "/Claim"
        const val ROOT_ERROR = "/Claim/Error"
        const val REQUIRED = "/Claim/RequiredCode"
        const val CLEAN = "/Claim/CleanNote"
        const val CHECKS = "/Claim/Checks"
        const val CHECK_ERROR = "/Claim/Checks/Error"
        const val SCORE = "/Claim/Checks/Score"
        const val EXACT_INPUTS = "ExactInputs"
        const val PARTIAL_INPUTS = "PartialInputs"
        const val ALWAYS = "Always"
        const val EXACT_CODE = "CUSTOM_INPUTS"
        const val PARTIAL_CODE = "CUSTOM_PARTIAL"
        const val OR_CODE = "CUSTOM_OR"
        const val AND_CODE = "CUSTOM_AND"
    }
}
