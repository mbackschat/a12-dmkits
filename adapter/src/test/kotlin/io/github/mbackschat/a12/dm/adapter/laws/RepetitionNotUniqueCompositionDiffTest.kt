package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `RepetitionNotUnique` composed under an arbitrary legal boolean tree (IF149). The
 * kernel evaluates RNU as an **ordinary per-row 3VL leaf** (`RuntimeController.wiederholungNichtEindeutig`
 * returns TRUE_WF / TRUE_AF / FALSE_OR_UNKNOWN) whose duplicate cache is built ONCE over the full scope,
 * independent of the surrounding boolean context, and whose result feeds the ordinary `combineUND`/`combineODER`
 * 3VL. So a legal `RepetitionNotUnique(key) Or otherCondition` and `guard And (RepetitionNotUnique(key) Or
 * otherCondition)` compose exactly like any other leaf — the interpreter must produce the same message set as
 * the kernel, NOT degrade to unsupported (the pre-IF149 recognizer only accepted RNU standing alone or as one
 * top-level `And` conjunct).
 *
 * These lock: RNU under `Or` (the duplicate branch AND the other branch each convict on their own rows, with the
 * `Or` polarity rule — a VALUE branch wins), a `guard And (RNU Or other)` nest, and a composite-key RNU whose
 * OMISSION composes under `Or`. The `Or`'s other branch is always a POSITIVE-presence / value condition: a rule
 * that iterates a repetition level may NOT fire on a not-filled repetition (`MVK_NEG_CONDITION_IN_ITERATION`), so
 * `RNU Or FieldNotFilled(...)` is kernel-INVALID and never appears in a loadable model. Own-domain e-commerce
 * fixture; conditions authored through the rulekit typed DSL, rendered to the stored form fed to both engines.
 * `RepetitionNotUniqueGuardedDiffTest` covers the `guard And RNU` conjunct form.
 */
class RepetitionNotUniqueCompositionDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER), FieldDef(WEIGHT, Kind.NUMBER),
        FieldDef(CUSTOMER, Kind.STRING))
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private class Scenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val def: ModelDef, val iDoc: Document)

    /** Build [cond] over `Items` rows = [rows] (count, weight), with an optional order-level [customer]. */
    private fun scenario(cond: String, rows: List<Pair<String?, String?>>, customer: String?): Scenario {
        val kModel = RuntimeLaws.withRule(order, "/Order", "RU", "../Items/Count", "RU", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/RU", "/Order", cond, "RU", "../Items/Count")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(SKU))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument()
        if (customer != null) { kDoc.field(CUSTOMER, customer, 1, 1); iDoc.field(CUSTOMER, customer, 1, 1) }
        rows.forEachIndexed { i, (c, w) ->
            val r = i + 1
            kDoc.group("/Order/Items", 1, r).field(SKU, "K$r", 1, r, 1)
            iDoc.group("/Order/Items", 1, r).field(SKU, "K$r", 1, r, 1)
            if (c != null) { kDoc.field(COUNT, c, 1, r, 1); iDoc.field(COUNT, c, 1, r, 1) }
            if (w != null) { kDoc.field(WEIGHT, w, 1, r, 1); iDoc.field(WEIGHT, w, 1, r, 1) }
        }
        return Scenario(kModel, kDoc, def, iDoc)
    }

    private fun interpSigs(s: Scenario) = DmInterpreter(s.def).validateFull(s.iDoc).filter { it.code == "RU" }
        .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()

    /** Compare the interpreter's RU message set to the Groovy-dynamic kernel (the normative anchor). */
    private fun agree(cond: String, rows: List<Pair<String?, String?>>, msg: String, customer: String? = null) {
        val s = scenario(cond, rows, customer)
        val kDesc = RuntimeLaws.messages(s.kModel, s.kDoc).filter { it.code() == "RU" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        assertEquals(kDesc, interpSigs(s), "$msg\n  kernel=$kDesc\n  interp=${interpSigs(s)}")
    }

    /** Both-strategies lock: interpreter == Groovy-dynamic == static-Java codegen. */
    private fun agreeTri(cond: String, rows: List<Pair<String?, String?>>, msg: String, customer: String? = null) {
        val s = scenario(cond, rows, customer)
        val groovy = RuntimeLaws.messages(s.kModel, s.kDoc).filter { it.code() == "RU" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(s.kModel)
        val javaStatic = engine.messages(engine.instance(s.kDoc)).filter { it.code() == "RU" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        assertEquals(groovy, interpSigs(s), "$msg (interp≠groovy)\n  kernel=$groovy\n  interp=${interpSigs(s)}")
        assertEquals(groovy, javaStatic, "$msg (java-static≠groovy)\n  groovy=$groovy\n  java=$javaStatic")
    }

    private val rnu = Dsl.repetitionNotUnique(m.field(COUNT))
    private val rnuComposite = Dsl.repetitionNotUnique(m.field(COUNT), m.field(WEIGHT))
    // The `Or`'s other branch is a value comparison (a comparison can't fire on an empty row — empty-as-0, so
    // 0 > 100 is false — so the iterating rule never fires on a not-filled rep: kernel-legal).
    private val rnuOrHeavy = render(rnu.or(m.num(WEIGHT).gt(100)))
    private val rnuOrWeightFilled = render(rnu.or(Dsl.fieldFilled(m.field(WEIGHT))))
    private val compositeRnuOrHeavy = render(rnuComposite.or(m.num(WEIGHT).gt(100)))
    private val customerAndRnuOrHeavy = render(Dsl.fieldFilled(m.field(CUSTOMER)).and(rnu.or(m.num(WEIGHT).gt(100))))

    @Test fun rnuUnderOrDuplicateBranchFires() =
        // No `Or` branch help (all weights below the threshold): the duplicate rows fire on RNU alone, the unique
        // row does not.
        agree(rnuOrHeavy, listOf("5" to "1", "5" to "1", "9" to "1"),
            "duplicate rows fire on RNU; the unique row's Or branch (weight>100) is false → no fire")

    @Test fun rnuUnderOrOtherBranchFiresUniqueRow() =
        // The unique row (Count=9) fires via the Or's `weight > 100` branch; the duplicates fire on RNU. All VALUE.
        agree(rnuOrHeavy, listOf("5" to "1", "5" to "1", "9" to "200"),
            "duplicates fire on RNU (VALUE), the unique heavy row fires via the Or branch (VALUE)")

    @Test fun rnuUnderOrPositivePresenceBranch() =
        // A positive-presence branch (`FieldFilled(weight)`) is also kernel-legal under Or (it too cannot fire an
        // empty rep): the weight-filled unique row fires via the branch, the duplicates via RNU.
        agree(rnuOrWeightFilled, listOf("5" to null, "5" to null, "9" to "3"),
            "duplicates fire on RNU, the unique weight-filled row fires via the FieldFilled branch")

    @Test fun compositeRnuOmissionComposesUnderOr() =
        // A composite-key RNU whose empty component makes it OMISSION, under Or with a false value branch: the
        // OMISSION propagates through the Or (no VALUE branch is true), so both duplicate rows fire OMISSION.
        agree(compositeRnuOrHeavy, listOf("5" to null, "5" to null),
            "(5,empty)(5,empty) duplicate → RNU OMISSION composes through the Or")

    @Test fun guardAndRnuOrOtherNest() =
        agree(customerAndRnuOrHeavy, listOf("5" to "1", "5" to "1", "9" to "200"),
            "guard filled → (duplicate rows fire VALUE via RNU, unique heavy row fires VALUE via Or)", customer = "Ada")

    @Test fun guardFalseSuppressesTheWholeNest() =
        agree(customerAndRnuOrHeavy, listOf("5" to "1", "5" to "1"), "guard empty → the whole And is false → none fire", customer = null)

    @Test fun codegenEvaluatesCompositionIdentically() {
        agreeTri(rnuOrHeavy, listOf("5" to "1", "5" to "1", "9" to "200"), "codegen: RNU under Or")
        agreeTri(customerAndRnuOrHeavy, listOf("5" to "1", "5" to "1", "9" to "200"),
            "codegen: guard And (RNU Or other)", customer = "Ada")
    }
}

private const val SKU = "/Order/Items/Sku"
private const val COUNT = "/Order/Items/Count"
private const val WEIGHT = "/Order/Items/UnitWeight"
private const val CUSTOMER = "/Order/CustomerName"
