package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The read-back judges `fieldRefByShortNameAllowed` by the token written, as A12 reads it: an exponent or a fraction
 * leaves the model unread (KERNEL-FINDINGS KF569, `adapter.laws.ModelTextReadingDiffTest`), so `1e0` does not open
 * the model-wide tier, although the decimal the reader parses prints as `1`.
 *
 * A rule in `/Policy` reads `/Claim/Reserve` by its bare name, which resolves only through that tier; `1` and `0`
 * are the control, and must read back differently or the row proves nothing. Own domain (an insurance claim).
 */
class ShortNameFlagReadBackTest {

    private fun readBack(flagToken: String): String {
        val draft = DocumentModelDraft.named("claim-short-names").locale("en_US")
            .rootGroup("Policy") { g -> g.field("Ref", FieldKind.STRING) }
            .rootGroup("Claim") { g -> g.field("Reserve", FieldKind.STRING) }
            .build().toJson()
        val ruled = ModelWriter.addRule(draft, "/Policy", "ReserveNamed", "../Ref", "RES", "FieldFilled(Reserve)",
            "ERROR", listOf(Dsl.en("x")))
        // Written after the rule, since the writer would respell the token, and into the member the draft declares.
        val member = Regex("\"fieldRefByShortNameAllowed\"\\s*:\\s*(true|false)")
        check(member.findAll(ruled).count() == 1) { "the draft must declare the flag once" }
        val modelJson = ruled.replace(member, "\"fieldRefByShortNameAllowed\":$flagToken")
        return InterpreterConditionReadBack().readAll(modelJson, ReferenceResolvers.selfContained())
            .getValue("/Policy/ReserveNamed").javaClass.simpleName
    }

    @Test
    fun `an exponent token does not open the model-wide tier`() {
        assertNotEquals(readBack("1"), readBack("0"), "the flag must decide this rule's read-back")
        assertEquals(readBack("0"), readBack("1e0"))
        assertEquals(readBack("0"), readBack("1.0e1"))
    }
}
