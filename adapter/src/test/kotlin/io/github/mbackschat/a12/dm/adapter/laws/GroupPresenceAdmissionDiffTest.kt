package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for validation-stage group presence boundaries. A created repeatable row remains structural
 * ancestor content even when its index is over-limit (its ordinary row checks are separately suppressed), while
 * partial validation distinguishes some relevant descendants from the whole group.
 */
class GroupPresenceAdmissionDiffTest {

    private val base = DocumentModelDraft.named("admission").locale("en_US")
        .rootGroup("Form") { form ->
            form.stringField("Witness") { }
            form.group("Container") { container ->
                container.group("Rows") { rows -> rows.repeatable(2).stringField("Value") { } }
            }
            form.group("G") { group -> group.stringField("A") { }.stringField("B") { } }
        }
        .build()
        .toJson()
    private val model = BoundModel.ofJson(base)
    private val witness = model.str(WITNESS)
    private val rules = listOf(
        rule(OVER_FILLED, model.group(CONTAINER).filled()),
        rule(OVER_EMPTY, model.group(CONTAINER).notFilled()),
        rule(PART_FILLED, model.group(GROUP).filled()),
        rule(PART_EMPTY, model.group(GROUP).notFilled()),
    )
    private val modelJson = rules.fold(base, TypedRules::inject)
    private val definition = ModelLoader.load(modelJson)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(modelJson)

    @Test fun anOverLimitRepeatRowStillFillsItsAncestor() {
        val kernel = RuntimeLaws.doc().group(ROOT, 1).group(CONTAINER, 1, 1)
            .field(WITNESS, "yes", 1, 1)
            .group(ROWS, 1, 1, 3).field(VALUE, "x", 1, 1, 3, 1)
        val interpreter = definition.newDocument().group(ROOT, 1).group(CONTAINER, 1, 1)
            .field(WITNESS, "yes", 1, 1)
            .group(ROWS, 1, 1, 3).field(VALUE, "x", 1, 1, 3, 1)

        agreeFull(kernel, interpreter, setOf("$OVER_FILLED|VALUE_ERROR"), OVER_CODES, "over-limit Rows[3]")
    }

    @Test fun aRelevantFilledDescendantProvesPositiveGroupPresence() = agreePartial(
        valueA = "x",
        kernelRelevant = setOf(Relevant.of(WITNESS, 0, 0), Relevant.of(A, 0, 0, 0)),
        interpreterRelevant = setOf(RelevantEntity.anyRep(WITNESS), RelevantEntity.anyRep(A)),
        expected = setOf("$PART_FILLED|VALUE_ERROR"),
        scenario = "A relevant and filled",
    )

    @Test fun aPartiallyRelevantEmptyGroupCannotProveAbsence() = agreePartial(
        valueA = null,
        kernelRelevant = setOf(Relevant.of(WITNESS, 0, 0), Relevant.of(A, 0, 0, 0)),
        interpreterRelevant = setOf(RelevantEntity.anyRep(WITNESS), RelevantEntity.anyRep(A)),
        expected = emptySet(),
        scenario = "only empty A relevant",
    )

    @Test fun allRelevantChildrenProveGroupAbsence() = agreePartial(
        valueA = null,
        kernelRelevant = setOf(Relevant.of(WITNESS, 0, 0), Relevant.of(A, 0, 0, 0), Relevant.of(B, 0, 0, 0)),
        interpreterRelevant = setOf(
            RelevantEntity.anyRep(WITNESS),
            RelevantEntity.anyRep(A),
            RelevantEntity.anyRep(B),
        ),
        expected = setOf("$PART_EMPTY|OMISSION_ERROR"),
        scenario = "A and B relevant",
    )

    @Test fun aRelevantGroupProvesGroupAbsence() = agreePartial(
        valueA = null,
        kernelRelevant = setOf(Relevant.of(WITNESS, 0, 0), Relevant.of(GROUP, 0, 0)),
        interpreterRelevant = setOf(RelevantEntity.anyRep(WITNESS), RelevantEntity.anyRep(GROUP)),
        expected = setOf("$PART_EMPTY|OMISSION_ERROR"),
        scenario = "whole G relevant",
    )

    private fun rule(code: String, condition: Condition): Rule = model.rule(model.group(ROOT)).name(code).code(code)
        .errorField(witness).`when`(witness.filled().and(condition)).severity(Severity.ERROR)
        .message(Dsl.en("Group presence outcome.")).build()

    private fun agreeFull(
        kernelDocument: RuntimeLaws.Doc,
        interpreterDocument: Document,
        expected: Set<String>,
        codes: Set<String>,
        scenario: String,
    ) {
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).signatures(codes)
        val generated = javaEngine.messages(javaEngine.instance(kernelDocument)).signatures(codes)
        val interpreted = DmInterpreter(definition).validateFull(interpreterDocument).interpreterSignatures(codes)
        assertEquals(expected, dynamic, "$scenario — normative dynamic-Groovy")
        assertEquals(dynamic, generated, "$scenario — generated Java")
        assertEquals(dynamic, interpreted, "$scenario — interpreter")
    }

    private fun agreePartial(
        valueA: String?,
        kernelRelevant: Set<Relevant>,
        interpreterRelevant: Set<RelevantEntity>,
        expected: Set<String>,
        scenario: String,
    ) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(WITNESS, "yes", 1, 1)
        val interpreterDocument = definition.newDocument().group(ROOT, 1).field(WITNESS, "yes", 1, 1)
        if (valueA != null) {
            kernelDocument.field(A, valueA, 1, 1, 1)
            interpreterDocument.field(A, valueA, 1, 1, 1)
        }
        val dynamic = RuntimeLaws.messagesPart(modelJson, kernelDocument, kernelRelevant).signatures(PART_CODES)
        val generated = javaEngine.messagesPart(kernelDocument, kernelRelevant).signatures(PART_CODES)
        val interpreted = DmInterpreter(definition).validatePart(interpreterDocument, interpreterRelevant).messages
            .interpreterSignatures(PART_CODES)
        assertEquals(expected, dynamic, "$scenario — normative dynamic-Groovy")
        assertEquals(dynamic, generated, "$scenario — generated Java")
        assertEquals(dynamic, interpreted, "$scenario — interpreter")
    }

    private fun List<RuntimeLaws.Msg>.signatures(codes: Set<String>): Set<String> =
        filter { it.code() in codes }.mapTo(sortedSetOf()) { "${it.code()}|${it.type().name}" }

    private fun List<io.github.mbackschat.a12.dm.interpreter.model.Message>.interpreterSignatures(codes: Set<String>): Set<String> =
        filter { it.code in codes }.mapTo(sortedSetOf()) {
            "${it.code}|${if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"}"
        }

    private companion object {
        const val ROOT = "/Form"
        const val WITNESS = "$ROOT/Witness"
        const val CONTAINER = "$ROOT/Container"
        const val ROWS = "$CONTAINER/Rows"
        const val VALUE = "$ROWS/Value"
        const val GROUP = "$ROOT/G"
        const val A = "$GROUP/A"
        const val B = "$GROUP/B"
        const val OVER_FILLED = "OVER_FILLED"
        const val OVER_EMPTY = "OVER_EMPTY"
        const val PART_FILLED = "PART_FILLED"
        const val PART_EMPTY = "PART_EMPTY"
        val OVER_CODES = setOf(OVER_FILLED, OVER_EMPTY)
        val PART_CODES = setOf(PART_FILLED, PART_EMPTY)
    }
}
