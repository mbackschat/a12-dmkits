package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF103 — an ordinary rule with NO repeatable non-starred ref, declared in
 * a container root WITHOUT instance content, still evaluates once and fires at its ERROR ENTITY (kernel
 * iteration is reference-driven; the ordinary-rule sibling of IF100). Kernel-verified by the tri-check
 * differential `adapter.laws.CrossRootRuleScopeDiffTest`.
 */
class CrossRootRuleScopeTest {

    private val TEAM_END = "/Membership/LinkEnd"
    private val C_START = "/Contracts/Contract/StartDate"
    private val C_END = "/Contracts/Contract/EndDate"

    private val def = ModelDef(
        fields = listOf(FieldDef(TEAM_END, Kind.DATE), FieldDef(C_START, Kind.DATE), FieldDef(C_END, Kind.DATE),
            FieldDef("/XRules/Note", Kind.STRING)),
        rules = listOf(RuleDef("/XRules/R", "/XRules",
            "FieldFilled(/Membership/LinkEnd) And AtLeastOneFieldFilled(/Contracts/Contract*/EndDate) " +
                "And NumberOfFilledFields(/Contracts/Contract*/StartDate Having ([/Membership/LinkEnd] <= " +
                "[/Contracts/Contract/EndDate] Or FieldNotFilled(/Contracts/Contract/EndDate))) == 0",
            "UNCOVERED", "../../Membership/LinkEnd")),
        computations = emptyList(),
        repeatableGroups = setOf("/Contracts/Contract"),
    )

    private fun fired(teamEnd: String?): List<String> {
        val doc = def.newDocument().group("/Membership", 1)
        teamEnd?.let { doc.field(TEAM_END, it, 1, 1) }
        doc.group("/Contracts/Contract", 1, 1).field(C_START, "2024-04-28", 1, 1, 1).field(C_END, "2024-05-20", 1, 1, 1)
        return DmInterpreter(def).validateFull(doc).filter { it.code == "UNCOVERED" }
            .map { "${it.errorPath}|${it.type}" }
    }

    @Test fun firesAtTheErrorEntityDespiteTheEmptyDeclaringContainer() = assertEquals(
        listOf("/Membership[1]/LinkEnd|${MsgType.OMISSION}"), fired("2024-06-19"),
        "no covering contract — the empty /XRules container must not silence the rule")

    @Test fun silentWhenCovered() = assertEquals(emptyList(), fired("2024-05-01"))

    @Test fun silentWhenTheGateFieldIsEmpty() = assertEquals(emptyList(), fired(null))
}
