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
 * The field-list value-list QUANTIFIERS (shape B: `op(f1, f2 In v1, v2)`) against the kernel — the list-of-fields
 * siblings of the scalar `FieldValueIncludedInValueList`. Probed semantics: classify each field as IN (present
 * value ∈ list) / OUT (present, ∉ list) / EMPTY; `AtLeastOne` fires iff some field is IN, `No` iff none is IN,
 * `NotAll` iff some field is OUT. Empties count as neither (a malformed field would suppress, but shape-B value lists are string/entity-only —
 * KERNEL-FINDINGS §8 — so no malformed-able field is expressible here; that path is locked by the sibling
 * operators that share the `Val.Unknown` suppression). Polarity (probed): `AtLeastOne`/`NotAll` → VALUE; `No` →
 * OMISSION when some field is still empty (fillable with a list value), else VALUE. The single [agree] helper
 * compares the fired message TYPE (null = not fired), locking firing AND polarity. On /Order
 * `CustomerName`/`ProductCode` (strings).
 */
class ValueListQuantifierDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(CN, Kind.STRING), FieldDef(PC, Kind.STRING))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The list fields are `m.str(CN)` / `m.str(PC)` (absolute paths).
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Inject [cond] at /Order (error field [errorRel], referenced in [cond]); set cells; assert fired type agrees. */
    private fun agree(cond: String, errorRel: String, vararg cells: Pair<String, String?>, msg: String) {
        val model = RuntimeLaws.withRule(order, "/Order", "VQ", errorRel, "VQ", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/VQ", "/Order", cond, "VQ", errorRel)))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .group("/Order", 1)
        for ((p, v) in cells) if (v != null) { kDoc.field(p, v, 1, 1); iDoc.field(p, v, 1, 1) }
        val kernel = RuntimeLaws.typeOf(model, "VQ", kDoc).map { it.name }.orElse(null)
        val interp = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.code == "VQ" }?.type
            ?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interp, "$msg  [kernel=$kernel interp=$interp]")
    }

    private val vals = listOf("AB", "CD")
    private val atLeastOne = render(Dsl.atLeastOneFieldValueIncludedInValueList(vals, m.str(CN), m.str(PC)))
    private val no = render(Dsl.noFieldValueIncludedInValueList(vals, m.str(CN), m.str(PC)))
    private val notAll = render(Dsl.notAllFieldValuesIncludedInValueList(vals, m.str(CN), m.str(PC)))
    private val cn = "../CustomerName"

    @Test fun atLeastOneSomeInListFiresValue() = agree(atLeastOne, cn, CN to "AB", PC to "XX", msg = "AB in list → fires VALUE")
    @Test fun atLeastOneNoneInListDoesNotFire() = agree(atLeastOne, cn, CN to "XX", PC to "YY", msg = "none in list → no fire")
    @Test fun atLeastOneInPlusEmptyFiresValue() = agree(atLeastOne, cn, CN to "AB", PC to null, msg = "AB in list, empty skipped → VALUE")

    @Test fun noNoneInListAllPresentFiresValue() = agree(no, cn, CN to "XX", PC to "YY", msg = "none in list, both present → VALUE")
    @Test fun noNoneInListWithEmptyFiresOmission() = agree(no, cn, CN to "XX", PC to null, msg = "none in list, an empty field → fillable → OMISSION")
    @Test fun noAllEmptyFiresOmission() = agree(no, cn, CN to null, PC to null, msg = "all empty, none in list → OMISSION")
    @Test fun noSomeInListDoesNotFire() = agree(no, cn, CN to "AB", PC to "XX", msg = "AB in list → No is false")

    @Test fun notAllSomeOutFiresValue() = agree(notAll, cn, CN to "AB", PC to "XX", msg = "XX outside list → fires VALUE")
    @Test fun notAllAllInDoesNotFire() = agree(notAll, cn, CN to "AB", PC to "CD", msg = "both in list → no field outside → no fire")
    @Test fun notAllInPlusEmptyDoesNotFire() = agree(notAll, cn, CN to "AB", PC to null, msg = "AB in, empty skipped → no OUT → no fire")
    @Test fun notAllOutPlusEmptyFiresValue() = agree(notAll, cn, CN to "XX", PC to null, msg = "XX out, empty skipped → an OUT → VALUE")

    private companion object {
        const val CN = "/Order/CustomerName"
        const val PC = "/Order/ProductCode"
    }
}
