package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.en
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.library
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.portable
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rule-group terminal in a message's field position, on kernel 31.1.1. `$RuleGroup$` is refused for the
 * shorthand itself (`INVALID_PARAMETER`), but with the form-line suffix the terminal is judged as the group it
 * names: `$RuleGroup.Vordruckzeile$` draws `INVALID_FIELD_IN_PARAMETER`, the class `$Loans.Vordruckzeile$` draws
 * (`MessageNamePositionAgreementTest`), where 30.8.1 faulted internally. `Vordruckname` left the parameter grammar
 * in 31.1.1 (KERNEL-FINDINGS KF657), so after the terminal it is an unexpected token, as a path continuation is.
 * The same classes hold from a rule in the root group and from one two levels down.
 *
 * Each row is pinned for A12 and compared against the portable checker. Requested by a12-kernel-lean as
 * SPEC-2026-11-04-03 (rows `I01`, `P01`, `P00`); own domain (the Library carrier of [MessageParameterProbes]).
 */
class CheckerRuleGroupFormLineAgreementTest {

    @Test
    fun theRuleGroupTerminalIsJudgedAsAGroupBeforeTheFormLine() {
        val base = library()
        val carriers = linkedMapOf(
            "root" to { parameter: String ->
                rule(base, "/Library", "Branch", en(parameter)) { it.group("/Library").str("Branch").filled() }
            },
            "two levels down" to { parameter: String ->
                rule(base, "/Library/Loans/Shelf", "Slot", en(parameter)) {
                    it.group("/Library/Loans/Shelf").str("Slot").filled()
                }
            },
        )
        val parameters = linkedMapOf(
            "\$RuleGroup.Vordruckzeile\$" to listOf("INVALID_FIELD_IN_PARAMETER"),
            "\$RuleGroup.Vordruckname\$" to listOf("MVK_UNEXPECTED_TOKEN"),
            "\$RuleGroup/Slot\$" to listOf("MVK_UNEXPECTED_TOKEN"),
            "\$RuleGroup\$" to listOf("INVALID_PARAMETER"),
        )
        val expected = linkedMapOf<String, Pair<List<String>, List<String>>>()
        val observed = linkedMapOf<String, Pair<List<String>, List<String>>>()
        for ((carrier, model) in carriers) {
            for ((parameter, classes) in parameters) {
                val id = "$carrier: $parameter"
                val built = model(parameter)
                expected[id] = classes to classes
                observed[id] = kernel(built) to portable(built)
            }
        }
        assertEquals(expected, observed, "(A12, ours)")
    }
}
