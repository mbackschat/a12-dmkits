package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Comments
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — a leading `;;` comment in stored condition text (IG18). The kernel's condition grammar makes
 * a leading `Comment` a real, valid token (`teilBedingung : before=Comment? …`, `WS -> channel(HIDDEN)`), so
 * the kernel parses past it and evaluates the body at `validateFull`. The interpreter must match. Before the
 * fix the interpreter's `skipWs()` skipped only `' '` (not `;` / newline), so `readIdent` threw
 * `IllegalArgumentException` on the `;`, and `validateWith`'s `UnsupportedConstructException`-only catch let it
 * ESCAPE — crashing the whole validateFull/validatePart/compute run (all share this parser).
 *
 * The body is authored via the typed DSL; the `;;` comment is attached through the renderer (`Comments.before`)
 * — the exact write path `CommentRoundTripTest` proves is kernel-valid; the kernel model is built through
 * `EditSession` (correct serialization/escaping), not a hand-built JSON string.
 */
class CommentConditionDiffTest {

    private val json = DocumentModelDraft.named("form").locale("en_US")
        .rootGroup("Order", Consumer { g -> g.field("MonthlyFee", FieldKind.NUMBER) })
        .build().toJson()
    private val m = BoundModel.ofJson(json)

    private val body: Condition = Dsl.fieldFilled(m.field(FEE)).and(m.num(FEE).gt(1000))
    private val cond = RuntimeLaws.render(body, Comments.none().before(body, "cap the monthly fee"))

    private val authored = EditSession.read(json)
        .addRule("/Order", "CAP", "../MonthlyFee", "CAP", cond, "ERROR", listOf(Dsl.en("fee too high")))
        .validate().toJson()

    private val def = ModelDef(
        listOf(FieldDef(FEE, Kind.NUMBER)),
        listOf(RuleDef("/Order/CAP", "/Order", cond, "CAP", "../MonthlyFee")),
    )

    private fun kDoc() = RuntimeLaws.doc().group("/Order", 1).field(FEE, "1500", 1, 1)
    private fun iDoc() = def.newDocument().group("/Order", 1).field(FEE, "1500", 1, 1)

    /** validateFull: a commented condition whose body fires (1500 > 1000) — both engines agree on the codes. */
    @Test fun leadingCommentConditionEvaluatesLikeTheKernel() {
        val kernel = RuntimeLaws.firedCodes(authored, kDoc()).toSortedSet()
        val interp = DmInterpreter(def).validate(iDoc()).messages.map { it.code }.toSortedSet()
        assertEquals(kernel, interp, "leading ;; comment on a condition — kernel=$kernel interp=$interp")
    }

    private companion object {
        const val FEE = "/Order/MonthlyFee"
    }
}
