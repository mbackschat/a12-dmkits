package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM + JS lock for SPEC-2026-07-26-02's non-repeatable group product below starred ancestors. */
class NonRepeatableGroupBelowStarTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(WITNESS, Kind.STRING),
            FieldDef(SCORE, Kind.NUMBER, scale = 1, minFractionalDigits = 1),
            FieldDef(CODE, Kind.STRING),
        ),
        rules = listOf(
            rule(NO_DETAILS, "NoGroupFilled(Sections*/Items*/Details)"),
            rule(SOME_DETAILS, "AtLeastOneGroupFilled(Sections*/Items*/Details)"),
            rule(SOME_EVIDENCE_ROWS, "AtLeastOneGroupFilled(Sections*/Items*/Details/Evidence*)"),
        ),
        computations = emptyList(),
        repeatableGroups = setOf(SECTIONS, ITEMS, EVIDENCE),
        declaredReps = mapOf(SECTIONS to 3, ITEMS to 3, EVIDENCE to 3),
    )

    @Test fun starredAncestorsAloneDoNotFillTheNonRepeatableTerminal() =
        assertShape(Shape.ANCESTOR_ONLY, setOf("$NO_DETAILS|OMISSION"))

    @Test fun admittedScalarContentFillsTheTerminal() =
        assertShape(Shape.ADMITTED_SCALAR, setOf("$SOME_DETAILS|VALUE"))

    @Test fun anInstantiatedRepeatableDescendantRowFillsTheTerminalAndRemainsStructuralItself() =
        assertShape(
            Shape.REPEATABLE_DESCENDANT,
            setOf("$SOME_DETAILS|VALUE", "$SOME_EVIDENCE_ROWS|VALUE"),
        )

    @Test fun malformedOnlyTerminalContentIsUnavailableRatherThanEmpty() =
        assertShape(Shape.MALFORMED_ONLY, emptySet())

    @Test fun aPositiveTerminalWitnessDecidesBesideAnUnavailablePeer() =
        assertShape(Shape.POSITIVE_BESIDE_UNAVAILABLE, setOf("$SOME_DETAILS|VALUE"))

    private fun assertShape(shape: Shape, expected: Set<String>) {
        val signatures = DmInterpreter(model).validateFull(document(shape))
            .filter { it.code in CODES }
            .map { "${it.code}|${it.type.name}" }
            .toSet()
        assertEquals(expected, signatures, shape.name)
    }

    private fun document(shape: Shape): Document {
        val document = model.newDocument().group(ROOT, 1).field(WITNESS, "yes", 1, 1)
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

    private fun rule(code: String, groupCondition: String) = RuleDef(
        "$ROOT/$code",
        ROOT,
        "FieldFilled(Witness) And $groupCondition",
        code,
        "Witness",
    )

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
        const val CODE = "$EVIDENCE/Code"
        const val NO_DETAILS = "NO_DETAILS"
        const val SOME_DETAILS = "SOME_DETAILS"
        const val SOME_EVIDENCE_ROWS = "SOME_EVIDENCE_ROWS"
        val CODES = setOf(NO_DETAILS, SOME_DETAILS, SOME_EVIDENCE_ROWS)
    }
}
