package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A single-rule eval retains the **firing [Message] itself**, not only its rendered text.
 *
 * <p>The reason is a reported observable loss: the message TYPE (VALUE vs OMISSION) is the only thing that
 * distinguishes a filtered from an unfiltered firing on identical data, and a consumer of [DmInterpreter.testRule]
 * / [DmInterpreter.evalCandidate] could not see it — nor the operands the rule read — so measuring it forced a
 * detour through whole-document validation. Both single-rule entry points now hand back the same message value
 * `validateFull` publishes for that firing, with `message`/`type` derived from it.
 */
class RuleResultFiringMessageTest {

    private val code = "/Policy/Coverages/CoverageCode"
    private val premium = "/Policy/Coverages/Premium"

    private fun model(condition: String) = ModelDef(
        fields = listOf(FieldDef(code, Kind.STRING), FieldDef(premium, Kind.NUMBER)),
        rules = listOf(RuleDef("/Policy/DUP", "/Policy/Coverages", condition, "DUP", "../CoverageCode")),
        repeatableGroups = setOf("/Policy/Coverages"),
        declaredReps = mapOf("/Policy/Coverages" to 20),
    )

    /** Two coverage rows sharing one code — the duplicate the uniqueness predicate reports. */
    private fun duplicateRows(m: ModelDef) =
        m.newDocument().group("/Policy", 1).also { doc ->
            listOf("100", "200").forEachIndexed { index, fee ->
                val row = index + 1
                doc.group("/Policy/Coverages", 1, row)
                    .field(code, "LIAB", 1, row, 1)
                    .field(premium, fee, 1, row, 1)
            }
        }

    @Test
    fun aFiredRuleCarriesItsMessageValue() {
        val m = model("FieldValuesNotUnique(/Policy/Coverages*/CoverageCode)")
        val result = DmInterpreter(m).testRule("/Policy/DUP", duplicateRows(m))

        assertEquals(Verdict.FIRED, result.verdict)
        val firing = result.firing
        assertTrue(firing != null, "a FIRED verdict retains the message it fired with")
        assertEquals("DUP", firing.code)
        assertEquals("/Policy/DUP", firing.rulePath)
        assertEquals(MsgType.VALUE, firing.type, "an unfiltered uniqueness firing is a VALUE error")
        assertEquals(listOf("/Policy[1]/Coverages[0]/CoverageCode"), firing.referenced,
            "the operand the rule read is the whole column, so its address names a set of cells")
        assertEquals(firing.type, result.type, "`type` stays available, derived from the retained message")
        assertEquals(firing.text, result.message, "`message` stays available, derived from the retained message")
    }

    /**
     * The discriminating pair — same data, same operand, one filter apart. Only the message type separates them,
     * which is exactly why dropping it made the two firings indistinguishable to a single-rule consumer.
     */
    @Test
    fun aReachedFilterFlipsTheRetainedTypeToOmission() {
        val filtered = model("FieldValuesNotUnique(/Policy/Coverages*/CoverageCode Having [/Policy/Coverages/Premium] > 50)")
        val result = DmInterpreter(filtered).testRule("/Policy/DUP", duplicateRows(filtered))

        assertEquals(Verdict.FIRED, result.verdict)
        assertEquals(MsgType.OMISSION, result.type,
            "a Having-filtered star escalates the firing to an omission error (IF101/PolarityWalk)")
    }

    @Test
    fun aCandidateRuleRetainsItsMessageToo() {
        val m = model("FieldValuesNotUnique(/Policy/Coverages*/CoverageCode)")
        val candidate = RuleDef("/Policy/Candidate", "/Policy/Coverages",
            "FieldValuesNotUnique(/Policy/Coverages*/CoverageCode Having [/Policy/Coverages/Premium] > 50)",
            "CAND", "../CoverageCode")
        val result = DmInterpreter(m).evalCandidate(candidate, duplicateRows(m))

        assertEquals(Verdict.FIRED, result.verdict)
        assertEquals("CAND", result.firing?.code)
        assertEquals(MsgType.OMISSION, result.type)
    }

    @Test
    fun aPassedRuleRetainsNoMessage() {
        val m = model("FieldValuesNotUnique(/Policy/Coverages*/CoverageCode)")
        val distinct = m.newDocument().group("/Policy", 1).also { doc ->
            listOf("LIAB", "COLL").forEachIndexed { index, value ->
                val row = index + 1
                doc.group("/Policy/Coverages", 1, row).field(code, value, 1, row, 1)
            }
        }
        val result = DmInterpreter(m).testRule("/Policy/DUP", distinct)

        assertEquals(Verdict.PASSED, result.verdict)
        assertNull(result.firing, "nothing fired, so there is no message to retain")
        assertNull(result.type)
        assertEquals("", result.message)
    }
}
