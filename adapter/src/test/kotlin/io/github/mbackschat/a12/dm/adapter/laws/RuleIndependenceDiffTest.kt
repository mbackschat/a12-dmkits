package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Kernel anchor for the common property that adding an unrelated rule cannot change existing rule results. */
class RuleIndependenceDiffTest {

    private val structure = DocumentModelDraft.named("rule-independence").locale("en_US")
        .rootGroup("Order") { order ->
            order.stringField("Customer") { }
            order.group("Lines") { lines ->
                lines.repeatable(4).numberField("Qty") { it.fractionDigits(0) }
            }
        }
        .build().toJson()

    private val originalModel = EditSession.read(structure).also { session ->
        session.addRule { bound ->
            bound.rule(bound.group(ORDER)).name("CustomerPresent").code(ROOT_CODE)
                .errorField(bound.str(CUSTOMER))
                .`when`(bound.str(CUSTOMER).filled())
                .severity(Severity.ERROR).message(Dsl.en("Customer is present.")).build()
        }
        session.addRule { bound ->
            bound.rule(bound.group(LINES)).name("PositiveQuantity").code(ROW_CODE)
                .errorField(bound.num(QTY))
                .`when`(bound.num(QTY).gt(0))
                .severity(Severity.ERROR).message(Dsl.en("Quantity is positive.")).build()
        }
        session.validate()
    }.toJson()

    private val augmentedModel = EditSession.read(originalModel).also { session ->
        session.addRule { bound ->
            bound.rule(bound.group(LINES)).name("FilledQuantityControl").code(ADDED_CODE)
                .errorField(bound.num(QTY))
                .`when`(bound.num(QTY).filled())
                .severity(Severity.ERROR).message(Dsl.en("Quantity is filled.")).build()
        }
        session.validate()
    }.toJson()

    @Test
    fun addingAnExecutingRuleChangesOnlyThatRulesOutput() {
        val original = observations(originalModel)
        val augmented = observations(augmentedModel)

        assertEquals(original.dynamic, original.generated, "original dynamic Groovy vs generated Java")
        assertEquals(original.dynamic, original.interpreter, "original kernel vs interpreter")
        assertEquals(augmented.dynamic, augmented.generated, "augmented dynamic Groovy vs generated Java")
        assertEquals(augmented.dynamic, augmented.interpreter, "augmented kernel vs interpreter")

        val existingCodes = setOf(ROOT_CODE, ROW_CODE)
        assertEquals(
            listOf(
                "$ROOT_CODE|VALUE|/Order[1]/Customer",
                "$ROW_CODE|VALUE|/Order[1]/Lines[1]/Qty",
            ),
            original.dynamic.filter { signatureCode(it) in existingCodes },
            "both pre-existing rule scopes must fire before independence is compared",
        )
        assertEquals(
            original.dynamic.filter { signatureCode(it) in existingCodes },
            augmented.dynamic.filter { signatureCode(it) in existingCodes },
            "adding a rule changed an existing rule's code, polarity, or pointer",
        )
        assertEquals(
            listOf("$ADDED_CODE|VALUE|/Order[1]/Lines[1]/Qty"),
            augmented.dynamic.filter { signatureCode(it) == ADDED_CODE },
            "the added rule must execute and fire as the relation's positive control",
        )
    }

    private fun observations(modelJson: String): Observation {
        val kernelDocument = RuntimeLaws.doc().group(ORDER, 1)
            .field(CUSTOMER, "Acme", 1, 1)
            .group(LINES, 1, 1).field(QTY, "1", 1, 1, 1)
            .group(LINES, 1, 2)
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).map {
            "${it.code()}|${it.type().name.removeSuffix("_ERROR")}|${it.field()}"
        }.sorted()
        val java = RuntimeLaws.kernelJavaEngine(modelJson)
        val generated = java.messages(java.instance(kernelDocument)).map {
            "${it.code()}|${it.type().name.removeSuffix("_ERROR")}|${it.field()}"
        }.sorted()

        val definition = ModelLoader.load(modelJson)
        val interpreterDocument = definition.newDocument().group(ORDER, 1)
            .field(CUSTOMER, "Acme", 1, 1)
            .group(LINES, 1, 1).field(QTY, "1", 1, 1, 1)
            .group(LINES, 1, 2)
        val interpreter = DmInterpreter(definition).validateFull(interpreterDocument).map { it.signature() }.sorted()
        return Observation(dynamic, generated, interpreter)
    }

    private fun Message.signature(): String = "$code|$type|$errorPath"

    private fun signatureCode(signature: String): String = signature.substringBefore('|')

    private data class Observation(
        val dynamic: List<String>,
        val generated: List<String>,
        val interpreter: List<String>,
    )

    private companion object {
        const val ORDER = "/Order"
        const val CUSTOMER = "/Order/Customer"
        const val LINES = "/Order/Lines"
        const val QTY = "/Order/Lines/Qty"
        const val ROOT_CODE = "EXISTING_ROOT"
        const val ROW_CODE = "EXISTING_ROW"
        const val ADDED_CODE = "ADDED_RULE"
    }
}
