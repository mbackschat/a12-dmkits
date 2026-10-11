package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
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

    @Test
    fun nestedMissingPrefixesRetainOmissionAcrossValueKinds() {
        for ((kind, raw) in listOf(Kind.STRING to "\"ready\"", Kind.NUMBER to "7", Kind.BOOLEAN to "true")) {
            val literal = if (kind == Kind.BOOLEAN) "True" else raw
            val operations = listOf("Breakdown/Payouts*/Parts*/Value", "Breakdown", "Breakdown/Payouts*/Parts*")
            val model = ModelDef(
                fields = listOf(FieldDef("/Claim/Ref", Kind.STRING),
                    FieldDef("/Claim/Breakdown/Payouts/Parts/Value", kind)),
                rules = operations.mapIndexed { i, operand ->
                    RuleDef("/Claim/Read$i", "/Claim", "FieldFilled(Ref) And FirstFilledValue($operand) == $literal", "R$i", "../Ref")
                },
                declaredReps = mapOf("/Claim/Breakdown/Payouts" to 2, "/Claim/Breakdown/Payouts/Parts" to 2),
                groupPaths = setOf("/Claim", "/Claim/Breakdown", "/Claim/Breakdown/Payouts", "/Claim/Breakdown/Payouts/Parts"),
            )
            val filled = """{"Parts":[{"Value":$raw}]}"""
            for ((rows, expected) in listOf(
                "[{},$filled]" to MsgType.OMISSION,
                "[{\"Parts\":[]},$filled]" to MsgType.OMISSION,
                "[{\"Parts\":[{}]},$filled]" to MsgType.OMISSION,
                "[$filled,{}]" to MsgType.VALUE,
            )) {
                val document = model.readDocumentJson("""{"Claim":{"Ref":"C","Breakdown":{"Payouts":$rows}}}""")
                val messages = DmInterpreter(model).validateFull(document).filter { it.code.startsWith("R") }
                assertEquals(3, messages.size, "$kind $rows")
                assertEquals(listOf(expected, expected, expected), messages.map { it.type }, "$kind $rows")
            }
        }
    }

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
