package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.OVER_REPETITION_CONTEXT
import io.github.mbackschat.a12.dm.interpreter.eval.UNIQUE_INDEX
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **What the formal-result caches may and may not key on** — the invariant that decides how cheap their key is
 * allowed to be, on the pure engine, JVM AND JS/Node.
 *
 * The value-level part of a formal result (a format error, a custom-type rejection) is a function of the FIELD and
 * the VALUE, so it may be memoized on `(path, raw)` and shared by every cell that holds that value. Two things are
 * emphatically NOT functions of the value, and a cache that folds them into the same entry answers one cell's
 * question with another cell's answer:
 *
 * - **the over-repetition gate**, which depends on the cell's coordinates against the declared repeatability;
 * - **duplicate-index membership**, which depends on which siblings a cell has.
 *
 * Both belong OUTSIDE the memo rather than inside its key. That is what makes the key affordable: carrying the
 * repetition list in it cost more than the work it saved (79 % of the time inside `resultAt` was key mechanics
 * against 2.6 % in the check being cached).
 *
 * *(Amended 2026-08-13: this used to add "both are cheap to compute per cell", which measurement contradicted.
 * Duplicate membership is — an empty-set test guards it. The over-repetition gate is a `HashMap<String, PathInfo>`
 * probe that every consumer pays again for the same cell, and it was **10.33 %** of a validate profile. Moving it
 * out of the key was still right; assuming what remained was free was not. It is now guarded by the same
 * document-level precondition its sibling has — `AutoChecks.overRepetitionGateNeeded`, IG127.1 — which is what
 * `OverLimitAddressPresenceMultiplatformTest` locks.)*
 *
 * The differential twins are `adapter.laws.OverRepetitionFormalGateDiffTest` and
 * `adapter.laws.RepetitionNotUniqueFormalKeyDiffTest`, whose expectations are the real kernel's.
 */
class FormalCacheCellIdentityMultiplatformTest {

    private val code = "/Registration/Plans/Code"
    private val plans = "/Registration/Plans"

    /**
     * The sharp case: **the same value in an in-cap row and in an over-limit row.** The in-cap cell must carry the
     * custom rejection and the over-limit cell must carry over-repetition instead — and the validator must run
     * exactly once, on the in-cap cell only.
     *
     * A memo keyed on `(path, raw)` alone answers both cells from one entry, and it fails in whichever direction the
     * first evaluation happens to take: the in-cap answer would report a rejection on a cell the gate exempts, and
     * the over-limit answer would report over-repetition on a cell that is inside its cap while never invoking the
     * validator at all.
     */
    @Test
    fun theSameValueInAnInCapAndAnOverLimitRowDoNotShareAFormalResult() {
        val def = ModelDef(
            fields = listOf(FieldDef(code, Kind.STRING, customType = CustomFieldTypeDeclaration("PlanCode"))),
            rules = emptyList(),
            repeatableGroups = setOf(plans),
            declaredReps = mapOf(plans to 2),
        )
        val seen = mutableListOf<String>()
        val validator = CustomFieldType { value, _ ->
            seen += value
            if (value == "AAA") CustomTypeRejection("PLAN_CODE_INVALID") else null
        }
        val doc = def.newDocument().group("/Registration", 1)
            .group(plans, 1, 1).field(code, "AAA", 1, 1, 1)     // in cap  → validated, rejected
            .group(plans, 1, 3).field(code, "AAA", 1, 3, 1)     // over cap → gated, never validated

        val out = DmInterpreter(def, customFieldTypes = mapOf("PlanCode" to validator)).validate(doc)

        assertEquals(listOf("AAA"), seen,
            "the validator runs once: the over-limit cell is gated before any value check, and the in-cap cell's " +
                "answer must not be reused for it")
        assertEquals(
            listOf("/Registration[1]/Plans[1]/Code"),
            out.messages.filter { it.code == "PLAN_CODE_INVALID" }.map { it.errorPath },
            "the rejection belongs to the in-cap cell alone",
        )
        assertEquals(
            listOf("/Registration[1]/Plans[3]/Code"),
            out.messages.filter { it.code == OVER_REPETITION_CONTEXT }.map { it.errorPath }.distinct(),
            "and the over-limit cell reports over-repetition rather than the other cell's rejection",
        )
    }

    /**
     * Duplicate-index membership is scoped to the siblings under one parent, so the same index value in two
     * different parent rows is not a duplicate. A memo shared by value would spread one parent's collision to
     * every other parent that happens to use the same value.
     */
    @Test
    fun aDuplicateIndexValueDoesNotSpreadToAnotherParentsRow() {
        val sku = "/Order/Boxes/Items/Sku"
        val def = ModelDef(
            fields = listOf(FieldDef(sku, Kind.STRING)),
            rules = emptyList(),
            repeatableGroups = setOf("/Order/Boxes", "/Order/Boxes/Items"),
            declaredReps = mapOf("/Order/Boxes" to 50, "/Order/Boxes/Items" to 50),
            indexFields = setOf(sku),
        )
        val doc = def.newDocument().group("/Order", 1)
            .group("/Order/Boxes", 1, 1)
            .group("/Order/Boxes/Items", 1, 1, 1).field(sku, "X", 1, 1, 1, 1)
            .group("/Order/Boxes/Items", 1, 1, 2).field(sku, "X", 1, 1, 2, 1)   // collides inside box 1
            .group("/Order/Boxes", 1, 2)
            .group("/Order/Boxes/Items", 1, 2, 1).field(sku, "X", 1, 2, 1, 1)   // alone inside box 2

        val flagged = DmInterpreter(def).validate(doc).messages
            .filter { it.code == UNIQUE_INDEX }
            .map { it.errorPath }
            .sorted()

        assertEquals(
            listOf("/Order[1]/Boxes[1]/Items[1]/Sku", "/Order[1]/Boxes[1]/Items[2]/Sku"),
            flagged,
            "box 2's lone 'X' is not a duplicate — duplicate membership is a property of the cell's siblings, " +
                "never of its value, so it cannot ride in a value-keyed memo",
        )
    }
}
