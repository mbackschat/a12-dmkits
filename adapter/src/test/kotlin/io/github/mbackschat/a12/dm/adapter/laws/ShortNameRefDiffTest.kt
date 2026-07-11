package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a bare SHORT-NAME field reference that resolves CROSS-GROUP (KERNEL-FINDINGS §10; IF119). The
 * kernel's condition parser resolves a bare `[Name]` to a field by short name model-wide when it does NOT
 * resolve against the rule's own group, gated by `modelConfig.fieldRefByShortNameAllowed` and requiring a
 * UNIQUE match (`CheckIdNameRefImpl.getEntityFromConditionText`). The interpreter's parser used to absolutize
 * a bare name against the declaring group only, so a cross-group `[PostalCode]` mis-bound to `/Order/PostalCode`
 * and failed LOUDLY at eval ("no kind declared for …"). Both engines load from ONE model JSON (`order.dm.json`
 * has the flag on): the kernel from the string, the interpreter via `ModelLoader` (which reads the flag).
 *
 * The conditions are written as RAW bare short-name strings on purpose — the typed rulekit renderer always
 * emits ABSOLUTE paths, so a bare cross-group short name (the form a real expanded model retains when the flag
 * is on) can only be expressed as a literal here.
 */
class ShortNameRefDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val PC = "/Order/BillingAddress/PostalCode"   // the cross-group target (unique short name)
    private val CN = "/Order/CustomerName"                // a same-group field, for content when PostalCode is empty

    /** [cond] (error code `SN`, error entity the referenced `PostalCode`) over the given field values — the
     *  kernel AND the interpreter must BOTH fire iff [expected] (before IF119 the interpreter threw on the
     *  cross-group ref). The error field is `PostalCode` because the kernel requires the rule's error field to
     *  be referenced in the condition (`MVK_ERROR_FIELD_NOT_REFERENCED`), which is exactly what we reference. */
    private fun agree(cond: String, expected: Boolean, msg: String, values: Map<String, String>) {
        val modelJson = RuntimeLaws.withRule(order, "/Order", "SN", "../BillingAddress/PostalCode", "SN", cond)
        val def = ModelLoader.load(modelJson)
        val kernel = RuntimeLaws.fires(modelJson, "SN", setOf("/Order"), values)
        val iDoc = def.newDocument()
        values.forEach { (p, v) -> iDoc.field(p, v, *IntArray(p.trim('/').split('/').size) { 1 }) }
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "SN" }
        assertEquals(expected, kernel, "KERNEL: $msg  (got kernel=$kernel)")
        assertEquals(expected, interp, "INTERP: $msg  (got interp=$interp)")
    }

    // ---- FieldFilled over a cross-group bare short name ----------------------------------------------------

    @Test fun crossGroupPresenceFiresWhenFilled() =
        agree("FieldFilled(PostalCode)", true, "[PostalCode] resolves cross-group; fires when it is filled",
            mapOf(PC to "12345"))

    @Test fun crossGroupPresenceSilentWhenEmpty() =
        agree("FieldFilled(PostalCode)", false, "[PostalCode] resolves cross-group; silent when it is empty",
            mapOf(CN to "Alice"))   // CustomerName filled → the row has content and the rule is evaluated

    // ---- a VALUE comparison over the cross-group bare short name -------------------------------------------

    @Test fun crossGroupValueFiresOnMatch() =
        agree("[PostalCode] == \"12345\"", true, "[PostalCode] == \"12345\" fires when the cross-group field matches",
            mapOf(PC to "12345"))

    @Test fun crossGroupValueSilentOnMismatch() =
        agree("[PostalCode] == \"12345\"", false, "[PostalCode] == \"12345\" is silent when the value differs",
            mapOf(PC to "99999"))
}
