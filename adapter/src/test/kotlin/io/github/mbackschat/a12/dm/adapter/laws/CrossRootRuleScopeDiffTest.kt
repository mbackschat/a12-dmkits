package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — an ORDINARY rule declared in an
 * EMPTY container root, referencing and erroring into OTHER roots (the IG56 E3 hunt-3 minimal: a real
 * workspace `*_Rules`-container rule — `FieldFilled(/A/…) And AtLeastOneFieldFilled(/B* /…) And
 * NumberOfFilledFields(/B* /… Having …) == 0` — the ordinary-RULE sibling of IF100's implicit-validation
 * anchor).
 *
 * Kernel iteration is REFERENCE-driven (KERNEL-SEMANTICS §9): with no repeatable non-starred ref, the rule
 * evaluates ONCE and fires at its error entity — the declaring container's lack of instance content is
 * irrelevant. The interpreter's scope fallback was the DECLARING group (`scopeFromRefs(refs) ?: rule.group`),
 * so `contextsFor(<empty container>)` yielded nothing and the rule was never evaluated — a `kernel-only`
 * unit. The fix re-anchors such a rule at its ERROR ENTITY's parent (the IF100 anchor), only when the
 * declaring scope yields no contexts.
 */
class CrossRootRuleScopeDiffTest {

    private companion object {
        const val TEAM_END = "/Membership/LinkEnd"
        const val C_START = "/Contracts/Contract/StartDate"
        const val C_END = "/Contracts/Contract/EndDate"
    }

    private val base = DocumentModelDraft.named("teambook").locale("en_US")
        .rootGroup("Membership") { m -> m.dateField("LinkEnd") { } }
        .rootGroup("Contracts") { c ->
            c.group("Contract") { g -> g.repeatable(10).dateField("StartDate") { }.dateField("EndDate") { } }
        }
        .rootGroup("XRules") { x -> x.stringField("Note") { } }.build().toJson()

    /** The corpus shape: membership end date not covered by ANY contract (count of covering contracts == 0). */
    private val cond = "FieldFilled(/Membership/LinkEnd) And AtLeastOneFieldFilled(/Contracts/Contract*/EndDate) " +
        "And NumberOfFilledFields(/Contracts/Contract*/StartDate Having ([/Membership/LinkEnd] <= [/Contracts/Contract/EndDate] " +
        "Or FieldNotFilled(/Contracts/Contract/EndDate))) == 0"

    private val kModel = RuntimeLaws.withRule(base, "/XRules", "R", "../../Membership/LinkEnd", "UNCOVERED", cond)

    private fun agree(expect: List<String>, msg: String, build: (RuntimeLaws.Doc, Document) -> Unit) {
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val groovy = RuntimeLaws.messages(kModel, kDoc).filter { it.code() == "UNCOVERED" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaStatic = engine.messagesModern(kDoc2, null, java.util.Locale.US).filter { it.code() == "UNCOVERED" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val interp = DmInterpreter(def).validateFull(iDoc).filter { it.code == "UNCOVERED" }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(expect.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    @Test fun firesOnceDespiteTheEmptyDeclaringContainer() =
        agree(listOf("/Membership[1]/LinkEnd|OMISSION_ERROR"),
            "membership ends after the only contract → no covering contract → fires at the error entity") { k, i ->
            k.group("/Membership", 1).field(TEAM_END, "2024-06-19", 1, 1)
            i.group("/Membership", 1).field(TEAM_END, "2024-06-19", 1, 1)
            k.group("/Contracts/Contract", 1, 1).field(C_START, "2024-04-28", 1, 1, 1).field(C_END, "2024-05-20", 1, 1, 1)
            i.group("/Contracts/Contract", 1, 1).field(C_START, "2024-04-28", 1, 1, 1).field(C_END, "2024-05-20", 1, 1, 1)
        }

    @Test fun silentWhenACoveringContractExists() =
        agree(emptyList(), "a contract end covers the membership end → the count is ≥ 1 → silent") { k, i ->
            k.group("/Membership", 1).field(TEAM_END, "2024-05-01", 1, 1)
            i.group("/Membership", 1).field(TEAM_END, "2024-05-01", 1, 1)
            k.group("/Contracts/Contract", 1, 1).field(C_START, "2024-04-28", 1, 1, 1).field(C_END, "2024-05-20", 1, 1, 1)
            i.group("/Contracts/Contract", 1, 1).field(C_START, "2024-04-28", 1, 1, 1).field(C_END, "2024-05-20", 1, 1, 1)
        }

    @Test fun silentOnATrulyEmptyDocument() =
        agree(emptyList(), "nothing filled — the FieldFilled gate holds it silent (both engines)") { k, i ->
            k.group("/Membership", 1); i.group("/Membership", 1)
        }
}
