package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult

/**
 * The within-model control of the type-definition differentials (KERNEL-FINDINGS KF602): each probe is judged once
 * on its plain fields and once with every one of [twinned] renamed to its `Def` twin, a field typed through a type
 * definition declaring the same type and settings.
 *
 * A rule is declared at [group] on [errorField] unless a probe names its own, guarded by its being filled so that the
 * probe is the only defect.
 */
internal class TypeDefinedTwins(
    private val model: String,
    private val group: String,
    private val errorField: String,
    private val twinned: List<String>,
) {
    private val validator = RuleValidator()

    /** [condition] with every twinned name replaced by its `Def` twin. */
    fun twin(condition: String): String =
        twinned.fold(condition) { text, name -> text.replace(Regex("\\b$name\\b"), "${name}Def") }

    /** A12's classes and the checker's, each sorted, for a rule erring on [on] where [condition] holds. */
    fun judge(condition: String, on: String = errorField, message: String = "Check the entry."): Pair<List<String>, List<String>> {
        val draft = DraftRule.builder().groupPath(group).name("KindProbe").errorEntityRelPath("../$on")
            .errorCondition("FieldFilled($on) And $condition").errorCode("PROBE").severity("ERROR")
            .messages(listOf(Dsl.en(message))).build()
        return verdicts(validator.check(model, draft), validator.inject(model, draft))
    }

    /** The same for a computation at [group] writing [target] from [operation]. */
    fun judgeComputation(target: String, operation: String): Pair<List<String>, List<String>> {
        val draft = DraftComputation(group, "KindProbe", "../$target", null,
            listOf(DraftComputation.Alternative(null, operation, null)), listOf())
        return verdicts(validator.check(model, draft), validator.inject(model, draft))
    }

    private fun verdicts(result: ValidationResult, injected: String): Pair<List<String>, List<String>> {
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        return a12.sorted() to check(ModelSource(injected)).findings.filter { it.justifiesRejection }.map { it.code }.sorted()
    }
}
