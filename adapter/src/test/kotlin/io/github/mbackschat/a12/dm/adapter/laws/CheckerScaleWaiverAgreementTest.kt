package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionDirectives
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The portable checker reads the scale-gate waiver as A12 does: the model language's keyword lifts the gate, with
 * padding and a following comment, and the other language's keyword is a syntax error (KERNEL-FINDINGS KF75,
 * a12-kernel-lean SPEC-2026-10-14-11). The unwaived row is the control that the waiver alone lifts it. Each row is
 * pinned for A12 and compared against the checker.
 *
 * Own domain (a policy with whole units against a two-place rate).
 */
class CheckerScaleWaiverAgreementTest {

    private val validator = RuleValidator()

    @ParameterizedTest
    @EnumSource(ConditionLanguage::class)
    fun theWaiverLiftsTheGateInItsOwnLanguageOnly(language: ConditionLanguage) {
        val other = if (language == ConditionLanguage.EN) ConditionLanguage.DE else ConditionLanguage.EN
        val gate = ConditionDirectives.SCALE_GATE
        val padded = if (language == ConditionLanguage.EN) "@ SuppressWarning ( $gate ) " else "@ UnterdrueckeWarnung ( $gate ) "
        val rows = linkedMapOf(
            "unwaived — the control" to (CROSS_SCALE to setOf(gate)),
            "the model's keyword" to (ConditionDirectives.allowDifferingDecimals(language, CROSS_SCALE) to emptySet()),
            "padded" to ("$padded$CROSS_SCALE" to emptySet()),
            "a comment after it" to (ConditionDirectives.allowDifferingDecimals(language, ";;two places\n$CROSS_SCALE") to emptySet()),
            "the other language's keyword" to
                (ConditionDirectives.allowDifferingDecimals(other, CROSS_SCALE) to setOf("MVK_UNEXPECTED_TOKEN")),
        )
        val model = model(language)
        val observed = rows.mapValues { (_, row) -> verdicts(model, row.first) }
        assertEquals(rows.mapValues { (_, row) -> row.second to row.second }, observed, "(A12, ours)")
    }

    private fun model(language: ConditionLanguage): String {
        val base = DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy") { p -> p
                .numberField("Units") { it.fractionDigits(0) }
                .numberField("Rate") { it.fractionDigits(2) } }
            .build().toJson()
        return EditSession.read(base).setConditionLanguage(if (language == ConditionLanguage.DE) "de_DE" else "en_US").toJson()
    }

    private fun verdicts(model: String, condition: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Policy", "ProbeRule", "../Units", condition, "PROBE", "ERROR")
        val result = validator.check(model, draft)
        val a12 = if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("?") }.toSet()
        val ours = rejectingCodes(validator.inject(model, draft)).toSet()
        return a12 to ours
    }

    private companion object {
        /** Language-neutral, so one text serves both models. */
        const val CROSS_SCALE = "[Units] == [Rate]"
    }
}
