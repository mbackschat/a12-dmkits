package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rows a correlated tally leaves undecided belong to one document, so a low-level [Interpreter] that reuses one
 * [EvalConfig] across two documents must not read the first document's rows for the second (KERNEL-FINDINGS KF552:
 * a kept row's unavailable cell is neither filled nor empty). The documents differ only in claim 2's amount, filled
 * in the first and empty, so unavailable under the required gate, in the second; the control is the same evaluation
 * under a fresh configuration.
 */
class UnavailableRowsCallScopeTest {

    private val model = ModelDef(
        fields = listOf(FieldDef(REFERENCE, Kind.NUMBER, scale = 0), FieldDef(ALIAS, Kind.NUMBER, scale = 0),
            FieldDef(AMOUNT, Kind.NUMBER, scale = 0)),
        rules = emptyList(),
        repeatableGroups = setOf(CLAIMS),
        declaredReps = mapOf(CLAIMS to 5),
    )

    private val condition = parseCondition(
        "FieldFilled(Claims/Alias) And NoFieldFilled(Claims*/Amount Having [Claims/Reference] == [\$Claims/Alias])", POLICY)

    /** A required amount: its empty cell is formally invalid, so the tally cannot decide the row holding it. */
    private val requiredAmount: (EvalContext, String) -> Boolean = { ctx, path -> path == AMOUNT && !ctx.filled(path) }

    private fun firstClaim(secondAmount: String?) = model.newDocument()
        .field(REFERENCE, "1", 1, 1, 1).field(ALIAS, "7", 1, 1, 1).field(AMOUNT, "10", 1, 1, 1)
        .field(REFERENCE, "7", 1, 2, 1).field(ALIAS, "8", 1, 2, 1)
        .let { doc -> secondAmount?.let { doc.field(AMOUNT, it, 1, 2, 1) } ?: doc }
        .contextsFor(CLAIMS).first()

    @Test
    fun aReusedConfigurationReadsEachDocumentsOwnRows() {
        val shared = EvalConfig()
        Interpreter(firstClaim(secondAmount = "20"), shared, invalid = requiredAmount).eval(condition)
        val reused = Interpreter(firstClaim(secondAmount = null), shared, invalid = requiredAmount).eval(condition)
        val fresh = Interpreter(firstClaim(secondAmount = null), EvalConfig(), invalid = requiredAmount).eval(condition)
        assertEquals(fresh, reused)
        // The unavailable amount decides it, as `adapter.laws.UnavailableCorrelationRowDiffTest`'s ALIAS_NONE row
        // holds on both kernel strategies; reading the first document's rows answered TRUE.
        assertEquals(Tri.FALSE, fresh)
    }

    private companion object {
        const val POLICY = "/Policy"
        const val CLAIMS = "/Policy/Claims"
        const val REFERENCE = "/Policy/Claims/Reference"
        const val ALIAS = "/Policy/Claims/Alias"
        const val AMOUNT = "/Policy/Claims/Amount"
    }
}
