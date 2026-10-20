package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential (groovy-dynamic == java-static == interpreter) for `RepetitionNotUnique` keys that sit in sibling
 * FIXED groups of one repeatable row (a12-kernel-lean SPEC-2026-10-14-09, re-measured here on an own-domain order
 * model). A12 compares the key per row of the repeatable group the siblings share, in either key order; the
 * interpreter refused such a rule as unsupported, because it took the key row from a single hierarchy path. The
 * mixed key, one component in a sibling group and one on the row itself, lies on one path and is the control.
 * The portable checker's admission of the same model is asserted beside A12's.
 *
 * Only siblings under one repeatable level are measured; keys below different repeatable levels are not claimed.
 */
class RepetitionNotUniqueSiblingGroupKeyDiffTest {

    private val base = DocumentModelDraft.named("fulfilment").locale("en_US")
        .rootGroup("Order") { order ->
            order.group("Lines") { lines -> lines.repeatable(5)
                .stringField("Sku") { }
                .group("Supplier") { s -> s.stringField("Code") { } }
                .group("Batch") { b -> b.stringField("Number") { } } }
        }
        .build().toJson()

    private val model = run {
        val m = BoundModel.ofJson(base)
        val supplier = m.field(SUPPLIER)
        val batch = m.field(BATCH)
        val sku = m.field(SKU)
        var json = RuntimeLaws.withRule(base, "/Order", "R_PAIR", "../Lines/Supplier/Code", "PAIR",
            RuntimeLaws.render(Dsl.repetitionNotUnique(supplier, batch)))
        json = RuntimeLaws.withRule(json, "/Order", "R_SWAP", "../Lines/Batch/Number", "SWAP",
            RuntimeLaws.render(Dsl.repetitionNotUnique(batch, supplier)))
        json = RuntimeLaws.withRule(json, "/Order", "R_MIXED", "../Lines/Supplier/Code", "MIXED",
            RuntimeLaws.render(Dsl.repetitionNotUnique(supplier, sku)))
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json), "A12 admits every key")
        assertEquals(emptyList<String>(), check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code },
            "the portable checker refuses none of them")
        json
    }

    /** One line: its SKU, and its supplier code and batch number, a null leaving that sibling group absent. */
    private data class Line(val sku: String, val supplier: String?, val batch: String?)

    @Test
    fun aDuplicateKeyAcrossSiblingGroupsFiresOnEveryDuplicateRow() = assertAgrees(
        listOf(Line("a", "S1", "B1"), Line("a", "S1", "B1"), Line("b", "S2", null)),
        setOf(
            "PAIR|/Order[1]/Lines[1]/Supplier[1]/Code|VALUE", "PAIR|/Order[1]/Lines[2]/Supplier[1]/Code|VALUE",
            "SWAP|/Order[1]/Lines[1]/Batch[1]/Number|VALUE", "SWAP|/Order[1]/Lines[2]/Batch[1]/Number|VALUE",
            "MIXED|/Order[1]/Lines[1]/Supplier[1]/Code|VALUE", "MIXED|/Order[1]/Lines[2]/Supplier[1]/Code|VALUE",
        ),
    )

    @Test
    fun anAbsentSiblingGroupMakesTheDuplicateAnOmission() = assertAgrees(
        listOf(Line("a", "S1", null), Line("b", "S1", null)),
        setOf(
            "PAIR|/Order[1]/Lines[1]/Supplier[1]/Code|OMISSION", "PAIR|/Order[1]/Lines[2]/Supplier[1]/Code|OMISSION",
            "SWAP|/Order[1]/Lines[1]/Batch[1]/Number|OMISSION", "SWAP|/Order[1]/Lines[2]/Batch[1]/Number|OMISSION",
        ),
    )

    @Test
    fun distinctKeysDoNotFire() = assertAgrees(
        listOf(Line("a", "S1", "B1"), Line("b", "S1", "B2")),
        emptySet(),
    )

    private fun assertAgrees(lines: List<Line>, expected: Set<String>) {
        val def = ModelLoader.load(model)
        val kDoc = RuntimeLaws.doc().group(ROOT, 1)
        val iDoc = def.newDocument().group(ROOT, 1)
        lines.forEachIndexed { i, line ->
            val r = i + 1
            kDoc.group(LINES, 1, r).field(SKU, line.sku, 1, r, 1)
            iDoc.group(LINES, 1, r).field(SKU, line.sku, 1, r, 1)
            line.supplier?.let {
                kDoc.group("$LINES/Supplier", 1, r, 1).field(SUPPLIER, it, 1, r, 1, 1)
                iDoc.group("$LINES/Supplier", 1, r, 1).field(SUPPLIER, it, 1, r, 1, 1)
            }
            line.batch?.let {
                kDoc.group("$LINES/Batch", 1, r, 1).field(BATCH, it, 1, r, 1, 1)
                iDoc.group("$LINES/Batch", 1, r, 1).field(BATCH, it, 1, r, 1, 1)
            }
        }
        val dynamic = RuntimeLaws.messages(model, kDoc).filter { it.code() in CODES }
            .map { "${it.code()}|${it.field()}|${it.type().name.removeSuffix("_ERROR")}" }.toSet()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val generated = engine.messages(engine.instance(kDoc)).filter { it.code() in CODES }
            .map { "${it.code()}|${it.field()}|${it.type().name.removeSuffix("_ERROR")}" }.toSet()
        val interpreted = DmInterpreter(def).validateFull(iDoc).filter { it.code in CODES }
            .map { "${it.code}|${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION" else "VALUE"}" }.toSet()
        assertEquals(expected, dynamic, "dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }

    private companion object {
        const val ROOT = "/Order"
        const val LINES = "/Order/Lines"
        const val SKU = "/Order/Lines/Sku"
        const val SUPPLIER = "/Order/Lines/Supplier/Code"
        const val BATCH = "/Order/Lines/Batch/Number"
        val CODES = setOf("PAIR", "SWAP", "MIXED")
    }
}
