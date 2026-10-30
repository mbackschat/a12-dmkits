package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.messageFieldPath
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A relative path's `..` pops one name whether or not a slash follows it. A rule in `/Policy/Ledger` reporting on the
 * root writes `../..`, whose second step has none; A12 fires it at `/Policy` (KERNEL-FINDINGS KF603,
 * `adapter.laws.RootErrorTargetDiffTest`), while the prefix walk kept that step as a name (INTERPRETER-FINDINGS IF454).
 */
class RelativePathResolutionTest {

    private fun rule(path: String, errorRel: String) =
        RuleDef(path, path.substringBeforeLast('/'), "", "CODE", errorRel)

    @Test
    fun aTerminalStepUpPopsOneName() {
        assertEquals("/Policy", resolveErrorPath(rule("/Policy/Ledger/rootAbsent", "../..")))
        assertEquals("/Policy/Ledger", resolveErrorPath(rule("/Policy/Ledger/ledgerAbsent", "..")))
    }

    @Test
    fun aStepUpBeforeANameStillPopsOne() {
        assertEquals("/Policy/Remark", resolveErrorPath(rule("/Policy/Ledger/remark", "../../Remark")))
        assertEquals("/Policy/Ledger/Number", resolveErrorPath(rule("/Policy/Ledger/number", "../Number")))
    }

    @Test
    fun aMessageTokenResolvesAgainstTheRuleGroupTheSameWay() {
        assertEquals("/Policy/Remark", messageFieldPath("/Policy/Ledger", "../Remark"))
        assertEquals("/Policy/Ledger/Number", messageFieldPath("/Policy/Ledger", "Number"))
        assertEquals("/Claim/Remark", messageFieldPath("/Policy/Ledger", "/Claim/Remark"))
    }
}
