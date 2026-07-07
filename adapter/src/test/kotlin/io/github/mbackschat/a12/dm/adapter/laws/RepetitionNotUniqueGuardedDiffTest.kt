package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
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
 * Differential — the **combined** `<guard> And RepetitionNotUnique(key)` form (IF62). The kernel evaluates
 * `RepetitionNotUnique` as a **per-row 3VL predicate** whose duplicate cache is built over the full scope
 * **independent of the guard** (runtime `RuntimeController.wiederholungNichtEindeutig` /
 * `ermittleWiederholungenMitDuplikaten`; codegen `EntityListCondition.st` calls the SAME runtime method with a
 * per-rule cache) — the guard is a *separate* conjunct combined by the ordinary 3VL `And`. So a duplicate row
 * fires only when its guard also holds, and the fired polarity is the AND of the two operands' polarities.
 *
 * These lock: (1) the guard **gates firing but NOT the cache** (a duplicate whose guard is false does not fire,
 * yet it still makes its partner a duplicate); (2) an **omission** guard turns a value-duplicate into an
 * OMISSION; (3) a **formally-invalid** guard operand suppresses (UNKNOWN); (4) a **multi-conjunct** guard.
 * Own-domain e-commerce fixture; conditions authored through the rulekit typed DSL, rendered to the stored form
 * fed to both engines.
 */
class RepetitionNotUniqueGuardedDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER), FieldDef(WEIGHT, Kind.NUMBER),
        FieldDef(CUSTOMER, Kind.STRING))
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** A built scenario: the kernel model + doc, the interpreter model + doc, for [cond] over `Items` [rows]. */
    private class Scenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val def: ModelDef, val iDoc: Document)

    /** Build [cond] over `Items` rows = [rows] (count, weight), with an optional order-level [customer] value (a
     *  guard field ABOVE the keys' group). */
    private fun scenario(cond: String, rows: List<Pair<String?, String?>>, customer: String?): Scenario {
        val kModel = RuntimeLaws.withRule(order, "/Order", "RU", "../Items/Count", "RU", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/RU", "/Order", cond, "RU", "../Items/Count")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(SKU))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats, def.datePrecisions)
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

    /** Compare the interpreter's RU message set (pointer + polarity) to the Groovy-dynamic kernel (the anchor). */
    private fun agree(cond: String, rows: List<Pair<String?, String?>>, msg: String, customer: String? = null) {
        val s = scenario(cond, rows, customer)
        val kDesc = RuntimeLaws.messages(s.kModel, s.kDoc).filter { it.code() == "RU" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        assertEquals(kDesc, interpSigs(s), "$msg\n  kernel=$kDesc\n  interp=${interpSigs(s)}")
    }

    /** Both-strategies lock: interpreter == Groovy-dynamic == static-JAVA codegen. RepetitionNotUnique's codegen
     *  emits a call to the SAME runtime method the dynamic service uses (`wiederholungNichtEindeutig`), so this
     *  confirms the guard-AND-RNU composition is identical on the compiled path too. */
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
    private val weightFilledAndRnu = render(Dsl.fieldFilled(m.field(WEIGHT)).and(rnu))
    private val weightEmptyAndRnu = render(Dsl.fieldNotFilled(m.field(WEIGHT)).and(rnu))
    private val weightPositiveAndRnu = render(m.num(WEIGHT).gt(0).and(rnu))
    private val twoConjunctGuardAndRnu = render(Dsl.fieldFilled(m.field(WEIGHT)).and(m.num(COUNT).gt(0)).and(rnu))
    private val customerFilledAndRnu = render(Dsl.fieldFilled(m.field(CUSTOMER)).and(rnu))

    @Test fun guardGatesFiringButNotTheCache() =
        // Both rows are Count=5 duplicates (cache is guard-independent), but only the weight-filled row fires.
        agree(weightFilledAndRnu, listOf("5" to "1", "5" to null), "guard true only for row 1 → only row 1 fires")

    @Test fun guardFalseSuppressesBothDuplicates() =
        agree(weightFilledAndRnu, listOf("5" to null, "5" to null), "both dup but guard false for both → none fire")

    @Test fun omissionGuardMakesValueDuplicateAnOmission() =
        // FieldNotFilled(weight)=TRUE_AF And RepetitionNotUnique(Count)=TRUE_WF → TRUE_AF (omission dominates).
        agree(weightEmptyAndRnu, listOf("5" to null, "5" to null), "empty-weight guard → both fire OMISSION")

    @Test fun comparisonGuardValueCombines() =
        agree(weightPositiveAndRnu, listOf("5" to "1", "5" to "2"), "both weights > 0 → both fire VALUE")

    @Test fun formallyInvalidGuardOperandSuppresses() =
        // row 1's weight is malformed → the guard `[UnitWeight] > 0` is UNKNOWN → the row does not fire; the
        // partner (row 2) still fires because both remain Count=5 duplicates.
        agree(weightPositiveAndRnu, listOf("5" to "x", "5" to "2"), "malformed guard operand → only the clean row fires")

    @Test fun multiConjunctGuard() =
        agree(twoConjunctGuardAndRnu, listOf("5" to "1", "5" to "2"), "two-conjunct guard, both true → both fire VALUE")

    // The dominant real-corpus shape: the guard field sits ABOVE the keys' repeatable group (here `/Order`,
    // one level above `/Order/Items`) and resolves by walking up — one order-level value gates every Items row.
    @Test fun ancestorGuardTrueFiresAllDuplicates() =
        agree(customerFilledAndRnu, listOf("5" to null, "5" to null), "order-level guard filled → both duplicates fire", customer = "Ada")

    @Test fun ancestorGuardFalseSuppressesAllDuplicates() =
        agree(customerFilledAndRnu, listOf("5" to null, "5" to null), "order-level guard empty → duplicates suppressed", customer = null)

    @Test fun codegenEvaluatesGuardedRnuIdentically() {
        agreeTri(weightFilledAndRnu, listOf("5" to "1", "5" to null), "codegen: guard gates firing per row")
        agreeTri(weightEmptyAndRnu, listOf("5" to null, "5" to null), "codegen: omission guard → OMISSION")
    }
}

private const val SKU = "/Order/Items/Sku"
private const val COUNT = "/Order/Items/Count"
private const val WEIGHT = "/Order/Items/UnitWeight"
private const val CUSTOMER = "/Order/CustomerName"
