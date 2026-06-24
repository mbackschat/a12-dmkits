package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `EnumCategory` (`[Priority -> SpeedClass]`): the enum field's stored value mapped POSITIONALLY
 * to its value in the named category, compared as a string against the category's value domain. The order
 * fixture's `Priority` (STANDARD/EXPRESS/OVERNIGHT) carries category `SpeedClass` = NORMAL/FAST/FAST (a
 * many-to-one map). Kernel-probed: EXPRESS/OVERNIGHT → FAST (fires VALUE), STANDARD → NORMAL; an EMPTY enum
 * does NOT fire (the empty-string compare rule); an OUT-OF-ENUM value is formally invalid → the rule is
 * suppressed (no fire). Locks both firing and polarity (always VALUE — the enum is filled when it fires).
 */
class EnumCategoryDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(
            PRIORITY, Kind.STRING,
            enumValues = listOf("STANDARD", "EXPRESS", "OVERNIGHT"),
            enumCategories = mapOf("SpeedClass" to listOf("NORMAL", "FAST", "FAST")),
        ),
    )

    /** [cond] with Priority = [priority] (null = empty); compare kernel vs interpreter (firing + polarity). */
    private fun agree(cond: String, priority: String?, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../Priority", "EC", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "EC", "../Priority")))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats, def.datePrecisions, def.enumValues, def.enumCategories).group("/Order", 1)
        if (priority != null) { kDoc.field(PRIORITY, priority, 1, 1); iDoc.field(PRIORITY, priority, 1, 1) }
        val k = RuntimeLaws.typeOf(kModel, "EC", kDoc).map { it.name }.orElse(null)
        val i = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.code == "EC" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(k, i, "$msg  (kernel=$k interp=$i)")
    }

    @Test fun expressMapsToFast() = agree("[Priority -> SpeedClass] == \"FAST\"", "EXPRESS", "EXPRESS → FAST → fires VALUE")
    @Test fun overnightMapsToFast() = agree("[Priority -> SpeedClass] == \"FAST\"", "OVERNIGHT", "OVERNIGHT → FAST → fires VALUE")
    @Test fun standardIsNotFast() = agree("[Priority -> SpeedClass] == \"FAST\"", "STANDARD", "STANDARD → NORMAL ≠ FAST → no fire")
    @Test fun standardMapsToNormal() = agree("[Priority -> SpeedClass] == \"NORMAL\"", "STANDARD", "STANDARD → NORMAL → fires VALUE")
    @Test fun emptyEnumNoFireFast() = agree("[Priority -> SpeedClass] == \"FAST\"", null, "empty Priority → no fire")
    @Test fun emptyEnumNoFireNormal() = agree("[Priority -> SpeedClass] == \"NORMAL\"", null, "empty Priority → no fire (NORMAL)")
    @Test fun outOfEnumSuppressed() = agree("[Priority -> SpeedClass] == \"FAST\"", "BOGUS", "out-of-enum → suppressed → no fire")
    @Test fun notFastFiresOnNormal() = agree("[Priority -> SpeedClass] != \"FAST\"", "STANDARD", "STANDARD → NORMAL != FAST → fires VALUE")
    @Test fun notFastNoFireOnFast() = agree("[Priority -> SpeedClass] != \"FAST\"", "EXPRESS", "EXPRESS → FAST, != FAST → no fire")

    private companion object {
        const val PRIORITY = "/Order/Priority"
    }
}
