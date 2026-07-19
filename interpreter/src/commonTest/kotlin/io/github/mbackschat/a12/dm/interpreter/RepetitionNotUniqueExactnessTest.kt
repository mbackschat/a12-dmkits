package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.FormalErrorCode
import io.github.mbackschat.a12.dm.interpreter.eval.PredicateOp
import io.github.mbackschat.a12.dm.interpreter.eval.formalError
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform locks for the RNU cache's typed equality, all-key partial relevance, and duplicate-peer message
 * projection. The JVM differential in `RepetitionNotUniqueExactnessDiffTest` anchors the same cases to both
 * kernel strategies.
 */
class RepetitionNotUniqueExactnessTest {

    private data class Item(val count: String?, val weight: String?, val tag: String = "")

    private val count = Expr.Field(COUNT)
    private val weight = Expr.Field(WEIGHT)
    private val tag = Expr.Field(TAG)

    private fun rnu(vararg keys: Expr.Field) =
        Cond.Pred(PredicateOp.REPETITION_NOT_UNIQUE.kernelName, keys.toList())

    private fun renderFixture(condition: Cond): String = when (condition) {
        is Cond.Pred -> "${condition.name}(${condition.args.joinToString(", ") { renderFixture(it) }})"
        is Cond.Or -> "${renderFixture(condition.left)} Or ${renderFixture(condition.right)}"
        is Cond.Cmp -> "${renderFixture(condition.left)} ${renderFixture(condition.op)} ${renderFixture(condition.right)}"
        else -> error("unsupported portable condition fixture: $condition")
    }

    private fun renderFixture(expression: Expr): String = when (expression) {
        is Expr.Field -> "[${expression.path}]"
        is Expr.NumLit -> expression.v.display()
        else -> error("unsupported portable expression fixture: $expression")
    }

    private fun renderFixture(operator: CmpOp): String = when (operator) {
        CmpOp.LT -> "<"
        CmpOp.LE -> "<="
        CmpOp.GT -> ">"
        CmpOp.GE -> ">="
        CmpOp.EQ -> "=="
        CmpOp.NE -> "!="
    }

    private fun model(condition: Cond, weightField: FieldDef = FieldDef(WEIGHT, Kind.NUMBER)) = ModelDef(
        fields = listOf(
            FieldDef(SKU, Kind.STRING),
            FieldDef(COUNT, Kind.NUMBER),
            weightField,
            FieldDef(TAG, Kind.STRING),
        ),
        rules = listOf(RuleDef("/Order/RU", "/Order", renderFixture(condition), "RU", "../Items/Count")),
        repeatableGroups = setOf("/Order/Items"),
        declaredReps = mapOf("/Order/Items" to 50),
        indexFields = setOf(SKU),
    )

    private fun document(model: ModelDef, items: List<Item>): Document =
        model.newDocument().group("/Order", 1).also { doc ->
            items.forEachIndexed { index, item ->
                val row = index + 1
                doc.group("/Order/Items", 1, row).field(SKU, "K$row", 1, row, 1)
                item.count?.let { doc.field(COUNT, it, 1, row, 1) }
                item.weight?.let { doc.field(WEIGHT, it, 1, row, 1) }
                item.tag.takeIf { it.isNotEmpty() }?.let { doc.field(TAG, it, 1, row, 1) }
            }
        }

    private fun messages(model: ModelDef, items: List<Item>): List<Message> =
        DmInterpreter(model).validateFull(document(model, items)).filter { it.code == "RU" }

    @Test
    fun numericKeysUseValueEqualityButTextKeysStayExact() {
        val numeric = model(rnu(count, weight))
        assertEquals(
            2,
            messages(numeric, listOf(Item("5", "7.00"), Item("5.00", "7"))).size,
            "numeric key atoms compare by their declared numeric value",
        )

        val text = model(rnu(tag))
        assertEquals(
            0,
            messages(text, listOf(Item("1", null, "5"), Item("2", null, "5.00"))).size,
            "string key atoms retain exact stored spelling",
        )
    }

    @Test
    fun everyCompositeKeyMustBeRelevantToParticipateInPartialValidation() {
        val model = model(rnu(count, weight))
        val doc = document(model, listOf(Item("5", "7"), Item("5", "7")))
        val engine = DmInterpreter(model)

        assertEquals(
            2,
            engine.validatePart(doc, setOf(RelevantEntity.anyRep(COUNT), RelevantEntity.anyRep(WEIGHT))).messages
                .count { it.code == "RU" },
            "all key components relevant → both duplicate rows participate",
        )
        assertEquals(
            0,
            engine.validatePart(doc, setOf(RelevantEntity.anyRep(COUNT))).messages.count { it.code == "RU" },
            "a non-relevant key component makes the RNU leaf unknown/non-firing",
        )
    }

    @Test
    fun aRelevantTrueOrBranchStillFiresWhenTheRnuKeysAreOnlyPartlyRelevant() {
        val condition = Cond.Or(rnu(count, weight), Cond.Cmp(CmpOp.GT, count, Expr.NumLit(dec(0))))
        val model = model(condition)
        val doc = document(model, listOf(Item("5", "7"), Item("5", "7")))

        assertEquals(
            2,
            DmInterpreter(model).validatePart(doc, setOf(RelevantEntity.anyRep(COUNT))).messages.count { it.code == "RU" },
            "false/unknown RNU Or independently true relevant branch still fires",
        )
    }

    @Test
    fun aNonRelevantCustomKeyDoesNotInvokeItsValidator() {
        var checks = 0
        val model = model(
            rnu(count, weight),
            FieldDef(WEIGHT, Kind.STRING, customType = CustomFieldTypeDeclaration("Observed")),
        )
        val doc = document(model, listOf(Item("5", "accepted"), Item("5", "accepted")))
        val engine = DmInterpreter(
            model,
            customFieldTypes = mapOf("Observed" to CustomFieldType { _, _ ->
                checks++
                null
            }),
        )

        assertEquals(
            0,
            engine.validatePart(doc, setOf(RelevantEntity.anyRep(COUNT))).messages.count { it.code == "RU" },
            "the partly relevant composite key cannot participate",
        )
        assertEquals(0, checks, "relevance must short-circuit before formal/custom key validation")
    }

    @Test
    fun duplicateMessagesExpandEveryKeyAcrossEveryPeer() {
        val model = model(rnu(count, weight))
        val messages = messages(model, List(3) { Item("5", "7") })
        val expected = (1..3).flatMap { row ->
            listOf("/Order[1]/Items[$row]/Count", "/Order[1]/Items[$row]/UnitWeight")
        }.toSet()

        assertEquals(3, messages.size)
        messages.forEach { message ->
            assertEquals(expected, message.referenced.toSet(), "RNU references the complete duplicate cluster")
            assertEquals(emptySet(), message.fillToFix.toSet(), "VALUE RNU has no fill-to-fix pointers")
        }
    }

    @Test
    fun omissionMessagesExpandEveryKeyAcrossEveryPeer() {
        val model = model(rnu(count, weight))
        val messages = messages(model, List(3) { Item("5", null) })
        val expected = (1..3).flatMap { row ->
            listOf("/Order[1]/Items[$row]/Count", "/Order[1]/Items[$row]/UnitWeight")
        }.toSet()

        assertEquals(3, messages.size)
        messages.forEach { message ->
            assertEquals(expected, message.referenced.toSet(), "RNU references the complete omission cluster")
            assertEquals(expected, message.fillToFix.toSet(), "OMISSION RNU exposes the complete cluster as fill-to-fix")
        }
    }

    @Test
    fun scale19EqualityBoundaryIsOutsideTheLegalStoredKeyDomain() {
        val belowTheBoundaryA = "1.000000000000000000003"
        val belowTheBoundaryB = "1.000000000000000000004"

        assertEquals(
            FormalErrorCode.TOTAL_DIGITS_TOO_LONG,
            formalError(Kind.NUMBER, belowTheBoundaryA, scale = 21),
            "a stored RNU key that could expose scale-19 rounding is formally invalid before cache construction",
        )
        assertEquals(
            rnuNumericKey(belowTheBoundaryA),
            rnuNumericKey(belowTheBoundaryB),
            "the cache primitive still mirrors VkBigDecimal.equals' scale-19 normalization",
        )
    }

    private companion object {
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
        const val WEIGHT = "/Order/Items/UnitWeight"
        const val TAG = "/Order/Items/Tag"
    }
}
