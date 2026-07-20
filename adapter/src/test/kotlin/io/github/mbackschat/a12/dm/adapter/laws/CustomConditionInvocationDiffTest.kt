package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.api.IDocument
import com.mgmtp.a12.kernel.md.document.api.IEntityInstance
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.FieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine differential for the complete `CustomCondition` invocation contract. A reached callback owns
 * its hidden dependency checks: the evaluator supplies the document-wide formal-invalid pointer set,
 * partial relevance (or null in full), and the current repeated error pointer without pre-suppressing it.
 */
class CustomConditionInvocationDiffTest {

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
        DslRenderer(ConditionLanguage.EN, scope).render(condition)

    private fun withRule(group: String, name: String, errorRel: String, code: String, condition: Condition): String =
        RuntimeLaws.withRule(base, group, name, errorRel, code, render(group, condition))

    private fun kPointer(entity: IEntityInstance): String =
        "${entity.path}@${entity.repetitions.joinToString(",")}"

    private fun iPointer(pointer: FieldPointer): String =
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
                document: IDocument,
                relevant: Set<IEntityInstance>?,
                formallyIncorrect: Set<IEntityInstance>,
                errorEntity: IEntityInstance,
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
                document: IDocument,
                relevant: Set<IEntityInstance>?,
                formallyIncorrect: Set<IEntityInstance>,
                errorEntity: IEntityInstance,
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
                document: IDocument,
                relevant: Set<IEntityInstance>?,
                formallyIncorrect: Set<IEntityInstance>,
                errorEntity: IEntityInstance,
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
