package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-07-26-02 — a non-repeatable terminal below starred ancestors contributes its ordinary
 * admitted-content × error × relevance group state in every selected ancestor environment. The stars select
 * environments; they do not turn the terminal into a repeatable-row count.
 */
class NonRepeatableGroupBelowStarDiffTest {

    private val baseJson = DocumentModelDraft.named("nested-audit").locale("en_US")
        .rootGroup("Survey") { survey ->
            survey.stringField("Witness") { }
            survey.group("Sections") { sections ->
                sections.repeatable(3)
                sections.group("Items") { items ->
                    items.repeatable(3)
                    items.group("Details") { details ->
                        details.numberField("Score") { it.fractionDigits(1, 1) }
                        details.group("Evidence") { evidence ->
                            evidence.repeatable(3)
                            evidence.stringField("Code") { }
                        }
                    }
                }
            }
        }
        .build().toJson()
    private val root = EditSession.read(baseJson).group(ROOT)
    private val witness = root.str("Witness")
    private val details = root.flattenGroup("Sections*/Items*/Details")
    private val evidenceRows = root.flattenGroup("Sections*/Items*/Details/Evidence*")
    private val rules = listOf(
        rule(NO_DETAILS, Dsl.noGroupFilled(details)),
        rule(SOME_DETAILS, Dsl.atLeastOneGroupFilled(details)),
        rule(SOME_EVIDENCE_ROWS, Dsl.atLeastOneGroupFilled(evidenceRows)),
    )
    private val modelJson = rules.fold(baseJson, TypedRules::inject)
    private val definition = ModelLoader.load(modelJson)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(modelJson)

    @Test fun starredAncestorsAloneDoNotFillTheNonRepeatableTerminal() =
        agree(Shape.ANCESTOR_ONLY, setOf("$NO_DETAILS|/Survey[1]/Witness|OMISSION_ERROR"))

    @Test fun admittedScalarContentFillsTheTerminal() =
        agree(Shape.ADMITTED_SCALAR, setOf("$SOME_DETAILS|/Survey[1]/Witness|VALUE_ERROR"))

    @Test fun anInstantiatedRepeatableDescendantRowFillsTheTerminalAndRemainsStructuralItself() =
        agree(Shape.REPEATABLE_DESCENDANT, setOf(
            "$SOME_DETAILS|/Survey[1]/Witness|VALUE_ERROR",
            "$SOME_EVIDENCE_ROWS|/Survey[1]/Witness|VALUE_ERROR",
        ))

    @Test fun malformedOnlyTerminalContentIsUnavailableRatherThanEmpty() =
        agree(Shape.MALFORMED_ONLY, emptySet())

    @Test fun aPositiveTerminalWitnessDecidesBesideAnUnavailablePeer() =
        agree(Shape.POSITIVE_BESIDE_UNAVAILABLE, setOf("$SOME_DETAILS|/Survey[1]/Witness|VALUE_ERROR"))

    private fun rule(code: String, condition: Condition): Rule = root.rule {
        it.name(code)
            .code(code)
            .errorField(witness)
            .`when`(witness.filled().and(condition))
            .severity(Severity.ERROR)
            .message(Dsl.en("Nested detail presence outcome."))
            .build()
    }

    private fun agree(shape: Shape, expected: Set<String>) {
        val kernelDocument = kernelDocument(shape)
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).signatures()
        val generated = javaEngine.messages(javaEngine.instance(kernelDocument)).signatures()
        val interpreted = DmInterpreter(definition).validateFull(interpreterDocument(shape))
            .filter { it.code in CODES }
            .mapTo(sortedSetOf()) {
                "${it.code}|${it.errorPath}|${if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"}"
            }

        assertEquals(expected, dynamic, "$shape — normative dynamic-Groovy")
        assertEquals(dynamic, generated, "$shape — generated Java")
        assertEquals(dynamic, interpreted, "$shape — kernel-free interpreter")
    }

    private fun kernelDocument(shape: Shape): RuntimeLaws.Doc {
        val document = RuntimeLaws.doc().group(ROOT, 1).field(WITNESS, "yes", 1, 1)
            .group(SECTIONS, 1, 1).group(ITEMS, 1, 1, 1)
        when (shape) {
            Shape.ANCESTOR_ONLY -> Unit
            Shape.ADMITTED_SCALAR -> document.group(DETAILS, 1, 1, 1, 1)
                .field(SCORE, "7.0", 1, 1, 1, 1, 1)
            Shape.REPEATABLE_DESCENDANT ->
                document.group(DETAILS, 1, 1, 1, 1).group(EVIDENCE, 1, 1, 1, 1, 1)
            Shape.MALFORMED_ONLY -> document.group(DETAILS, 1, 1, 1, 1)
                .field(SCORE, "7", 1, 1, 1, 1, 1)
            Shape.POSITIVE_BESIDE_UNAVAILABLE -> document.group(DETAILS, 1, 1, 1, 1)
                .field(SCORE, "7.0", 1, 1, 1, 1, 1)
                .group(ITEMS, 1, 1, 2).group(DETAILS, 1, 1, 2, 1)
                .field(SCORE, "8", 1, 1, 2, 1, 1)
        }
        return document
    }

    private fun interpreterDocument(shape: Shape): Document {
        val document = definition.newDocument().group(ROOT, 1).field(WITNESS, "yes", 1, 1)
            .group(SECTIONS, 1, 1).group(ITEMS, 1, 1, 1)
        when (shape) {
            Shape.ANCESTOR_ONLY -> Unit
            Shape.ADMITTED_SCALAR -> document.group(DETAILS, 1, 1, 1, 1)
                .field(SCORE, "7.0", 1, 1, 1, 1, 1)
            Shape.REPEATABLE_DESCENDANT ->
                document.group(DETAILS, 1, 1, 1, 1).group(EVIDENCE, 1, 1, 1, 1, 1)
            Shape.MALFORMED_ONLY -> document.group(DETAILS, 1, 1, 1, 1)
                .field(SCORE, "7", 1, 1, 1, 1, 1)
            Shape.POSITIVE_BESIDE_UNAVAILABLE -> document.group(DETAILS, 1, 1, 1, 1)
                .field(SCORE, "7.0", 1, 1, 1, 1, 1)
                .group(ITEMS, 1, 1, 2).group(DETAILS, 1, 1, 2, 1)
                .field(SCORE, "8", 1, 1, 2, 1, 1)
        }
        return document
    }

    private fun List<RuntimeLaws.Msg>.signatures(): Set<String> =
        filter { it.code() in CODES }.mapTo(sortedSetOf()) { "${it.code()}|${it.field()}|${it.type().name}" }

    private enum class Shape {
        ANCESTOR_ONLY,
        ADMITTED_SCALAR,
        REPEATABLE_DESCENDANT,
        MALFORMED_ONLY,
        POSITIVE_BESIDE_UNAVAILABLE,
    }

    private companion object {
        const val ROOT = "/Survey"
        const val WITNESS = "$ROOT/Witness"
        const val SECTIONS = "$ROOT/Sections"
        const val ITEMS = "$SECTIONS/Items"
        const val DETAILS = "$ITEMS/Details"
        const val SCORE = "$DETAILS/Score"
        const val EVIDENCE = "$DETAILS/Evidence"
        const val NO_DETAILS = "NO_DETAILS"
        const val SOME_DETAILS = "SOME_DETAILS"
        const val SOME_EVIDENCE_ROWS = "SOME_EVIDENCE_ROWS"
        val CODES = setOf(NO_DETAILS, SOME_DETAILS, SOME_EVIDENCE_ROWS)
    }
}
