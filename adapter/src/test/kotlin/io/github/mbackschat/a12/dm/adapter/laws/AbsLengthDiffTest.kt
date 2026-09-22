package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the numeric functions **`Abs`** (`Abs([expr])`, bracketed), **`AbsValue`** (`AbsValue(field)`,
 * the bare-field sibling) = |x|, and **`Length`** (`Length(field)`, the character count of a string field's
 * value; bare field). The interpreter treats `Abs`/`AbsValue` identically (both → |operand value|). Each case
 * fires identically in both engines. (Index field `ItemId` is always filled so no `mandatoryField` intrudes;
 * `Length` rules are guarded with `FieldFilled` — the kernel rejects a rule that could fire on an empty row.)
 */
class AbsLengthDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(
        FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines (kernel injection + the interpreter) — no free-text condition literals.
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private fun agree(cond: String, errorRel: String, item: String, qty: String, price: String, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "RR", errorRel, "RR", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/Lines/RR", "/Cart/Lines", cond, "RR", errorRel)),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, item, 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val iDoc = def.newDocument()
            .field(ITEM, item, 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val kernel = RuntimeLaws.fires(kModel, "RR", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "RR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    @Test fun absNegativeFires() = agree(render(Dsl.abs(m.num(PRICE).asNumber()).gt(4)), "../Price", "A", "1", "-5", "|−5| = 5 > 4")
    @Test fun absPositiveDoesNotFire() = agree(render(Dsl.abs(m.num(PRICE).asNumber()).gt(4)), "../Price", "A", "1", "3", "|3| = 3 not > 4")
    @Test fun absValueBareFieldFires() = agree(render(Dsl.absValue(m.num(PRICE)).gt(4)), "../Price", "A", "1", "-5", "AbsValue(-5) = 5 > 4")
    @Test fun absOfExpression() = agree(render(Dsl.abs(m.num(PRICE).asNumber().minus(m.num(QTY).asNumber())).gt(5)), "../Price", "A", "9", "2", "|2 − 9| = 7 > 5")
    @Test fun lengthEquals() = agree(render(m.field(ITEM).filled().and(m.str(ITEM).length().eq(4))), "../ItemId", "ABCD", "1", "1", "len(ABCD) = 4")
    @Test fun lengthDoesNotFire() = agree(render(m.field(ITEM).filled().and(m.str(ITEM).length().gt(4))), "../ItemId", "ABCD", "1", "1", "len(ABCD) = 4 not > 4")

    // KERNEL-FINDINGS §7 — Length counts UTF-16 code units: a decomposed combining sequence (e + U+0301,
    // BMP-legal) counts as 2, not 1 (a grapheme count would say 1). Kernel == interpreter here; the JS target is pinned by
    // interpreter.LengthUnicodeCountTest. (Supplementary-plane / astral chars: a recorded residual, KERNEL-FINDINGS §7.)
    @Test fun lengthCountsACombiningSequenceAsTwo() =
        agree(render(m.field(ITEM).filled().and(m.str(ITEM).length().eq(2))), "../ItemId", "e\u0301", "1", "1",
            "decomposed e+U+0301 = 2 UTF-16 units")
    @Test fun lengthOfACombiningSequenceIsNotOne() =
        agree(render(m.field(ITEM).filled().and(m.str(ITEM).length().eq(1))), "../ItemId", "e\u0301", "1", "1",
            "decomposed e+U+0301 is 2, not 1 grapheme")

    @Test
    fun lengthHasGeneratedJavaTriEvidence() {
        val cond = render(m.field(ITEM).filled().and(m.str(ITEM).length().eq(2)))
        val model = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "RR", "../ItemId", "RR", cond)
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, "e\u0301", 1, 1, 1)
            .field(QTY, "1", 1, 1, 1).field(PRICE, "1", 1, 1, 1)
        val groovyDynamic = RuntimeLaws.fires(model, "RR", kDoc)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.firedCodesSorted(engine.instance(kDoc)).contains("RR")

        val def = ModelDef(
            fields,
            listOf(RuleDef("/Cart/Lines/RR", "/Cart/Lines", cond, "RR", "../ItemId")),
            repeatableGroups = setOf("/Cart/Lines"),
            declaredReps = mapOf("/Cart/Lines" to 1000),
            indexFields = setOf(ITEM),
        )
        val iDoc = def.newDocument()
            .field(ITEM, "e\u0301", 1, 1, 1).field(QTY, "1", 1, 1, 1).field(PRICE, "1", 1, 1, 1)
        val interpreter = DmInterpreter(def).validateFull(iDoc).any { it.code == "RR" }

        assertEquals(true, groovyDynamic, "Length must count the decomposed sequence as two UTF-16 units")
        assertEquals(groovyDynamic, javaStatic, "Length: groovy-dynamic vs generated Java")
        assertEquals(groovyDynamic, interpreter, "Length: kernel vs interpreter")
    }

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
        const val PRICE = "/Cart/Lines/Price"
    }
}
