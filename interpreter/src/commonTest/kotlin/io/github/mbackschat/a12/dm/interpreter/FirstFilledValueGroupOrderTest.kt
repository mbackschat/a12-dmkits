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

/**
 * JVM + Node owner lock for the fixed- and starred-group declaration-order matrices measured by
 * SPEC-2026-08-17-02 and SPEC-2026-08-17-03. Both shapes stay on [eval.firstFilledScan]: fixed groups consume
 * [eval.EvalContext.containedFieldCells], while starred groups expand the same declaration-ordered field list
 * and scan each field's rows before advancing to the next declaration.
 */
class FirstFilledValueGroupOrderTest {

    private companion object {
        const val REF = "/Return/Reference"
        const val ZETA_DIRECT = "/Return/Encounter/ZetaDirect"
        const val ALPHA_DIRECT = "/Return/Encounter/AlphaDirect"
        const val ZETA_NESTED_VALUE = "/Return/Encounter/ZetaNested/Value"
        const val ALPHA_NESTED_VALUE = "/Return/Encounter/AlphaNested/Value"
        const val ROWS = "/Return/Rows"
        const val FIRST = "/Return/Rows/First"
        const val SECOND = "/Return/Rows/Second"
        const val FALLBACK = "/Return/Fallback"
        const val CODE = "FFVG"
    }

    private fun model(condition: String) = ModelDef(
        fields = listOf(
            FieldDef(REF, Kind.STRING),
            FieldDef(ZETA_DIRECT, Kind.STRING),
            FieldDef(ALPHA_DIRECT, Kind.STRING),
            FieldDef(ZETA_NESTED_VALUE, Kind.STRING),
            FieldDef(ALPHA_NESTED_VALUE, Kind.STRING),
            FieldDef(FIRST, Kind.STRING),
            FieldDef(SECOND, Kind.STRING),
            FieldDef(FALLBACK, Kind.STRING),
        ),
        rules = listOf(RuleDef("/Return/Rule", "/Return", condition, CODE, "../Reference")),
        repeatableGroups = setOf(ROWS),
        declaredReps = mapOf(ROWS to 3),
    )

    private fun signal(condition: String, build: (Document) -> Unit): String? {
        val definition = model(condition)
        val document = definition.newDocument().group("/Return", 1).field(REF, "RET-1", 1, 1)
        build(document)
        return DmInterpreter(definition).validateFull(document).firstOrNull { it.code == CODE }
            ?.let { if (it.type == MsgType.OMISSION) "OMISSION" else "VALUE" }
    }

    private fun fixed(expected: String, build: (Document) -> Unit): String? =
        signal("FirstFilledValue(Encounter) == \"$expected\"", build)

    private fun starred(expected: String, build: (Document) -> Unit): String? =
        signal("FirstFilledValue(Rows*, Fallback) == \"$expected\"", build)

    @Test
    fun fixedGroupUsesStableDeclarationOrderAndPrefixPolarity() {
        assertEquals("VALUE", fixed("zeta") { document ->
            document.field(ZETA_DIRECT, "zeta", 1, 1, 1)
                .field(ALPHA_DIRECT, "alpha", 1, 1, 1)
        }, "the earlier Zeta direct declaration wins despite lexical path order")

        assertEquals("OMISSION", fixed("alpha") { document ->
            document.field(ALPHA_DIRECT, "alpha", 1, 1, 1)
        }, "the later direct declaration is reachable after an empty prefix")

        assertEquals("OMISSION", fixed("zeta") { document ->
            document.field(ZETA_NESTED_VALUE, "zeta", 1, 1, 1, 1)
                .field(ALPHA_NESTED_VALUE, "alpha", 1, 1, 1, 1)
        }, "the earlier Zeta nested declaration wins after the empty direct prefix")

        assertEquals("OMISSION", fixed("alpha") { document ->
            document.field(ALPHA_NESTED_VALUE, "alpha", 1, 1, 1, 1)
        }, "the later nested declaration is reachable after an empty prefix")
    }

    @Test
    fun starredGroupTraversesDeclarationMajorAcrossRows() {
        assertEquals("OMISSION", starred("first") { document ->
            document.group(ROWS, 1, 1).field(SECOND, "second", 1, 1, 1)
                .group(ROWS, 1, 2).field(FIRST, "first", 1, 2, 1)
        }, "row 2 First wins over row 1 Second")

        assertEquals("OMISSION", starred("second") { document ->
            document.group(ROWS, 1, 1).field(SECOND, "second", 1, 1, 1)
                .group(ROWS, 1, 2)
        }, "the later declaration is reachable")

        assertEquals("OMISSION", starred("first") { document ->
            document.group(ROWS, 1, 1)
                .group(ROWS, 1, 2).field(FIRST, "first", 1, 2, 1)
        }, "the later row of the earlier declaration is reachable")

        assertEquals("VALUE", starred("first") { document ->
            document.group(ROWS, 1, 1).field(FIRST, "first", 1, 1, 1)
                .group(ROWS, 1, 2).field(SECOND, "second", 1, 2, 1)
        }, "an immediate value stays VALUE despite the later row")

        assertEquals("OMISSION", starred("fallback") { document ->
            document.field(FALLBACK, "fallback", 1, 1)
        }, "a no-row group star makes the selected fallback OMISSION")

        assertEquals("OMISSION", starred("fallback") { document ->
            document.group(ROWS, 1, 1).field(FALLBACK, "fallback", 1, 1)
        }, "one empty row before the fallback is also OMISSION")
    }
}
