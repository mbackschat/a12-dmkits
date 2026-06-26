package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule

/**
 * Feeds ONE typed rulekit [Rule] / [Computation] to BOTH differential engines — the **kernel** (injected into
 * the model JSON via [RuntimeLaws]) and the **interpreter** ([RuleDef] / [ComputationDef]). Because the rule is
 * the dmkit's own typed object — its error field and condition are refs, and its kernel-relative error path
 * ([Rule.errorEntityRelPath]) + canonical condition text ([Rule.render]) are *derived* — the two engines are
 * driven from one source and can never drift, and no path or condition string is hand-written.
 *
 * Eating our own dogfood (CLAUDE.md): the differential consumes the very kits it tests.
 */
object TypedRules {

    /**
     * The rule's condition in the kernel-canonical GROUP-RELATIVE form — `[Rule.render]`, the dogfood-faithful
     * derived render both engines parse (the kernel natively; the interpreter via the relative + IF24-absolute +
     * IF25 `../` parent-relative path support). This is the strongest differential lock: every catalog/fuzz shape
     * now drives the interpreter's `../` resolution against the real kernel, since cross-level UP refs render as
     * `../Qty`. (Until the `../` parser support shipped — INTERPRETER-FINDINGS IF25 — this had to render ABSOLUTE.)
     */
    private fun cond(rule: Rule): String = rule.render()

    /** Inject the typed [rule] into [json] — the kernel side of the differential. */
    fun inject(json: String, rule: Rule): String =
        RuntimeLaws.withRule(
            json, rule.group().path(), rule.name(), rule.errorEntityRelPath(), rule.code(), cond(rule),
        )

    /** The interpreter [RuleDef] for the typed [rule] — the interpreter side. */
    fun def(rule: Rule): RuleDef =
        RuleDef(
            "${rule.group().path()}/${rule.name()}", rule.group().path(),
            cond(rule), rule.code(), rule.errorEntityRelPath(),
        )

    /** Inject the typed [comp] into [json] (simple or tiered — chosen by its alternatives) — the kernel side. */
    fun inject(json: String, comp: Computation): String {
        val r = DslRenderer(ConditionLanguage.EN, comp.groupPath())
        val alts = comp.alternatives()
        val common = comp.commonPrecondition().map { r.render(it) }.orElse(null)
        val unconditional = alts.size == 1 && alts[0].precondition().orElse(null) == null && common == null
        return if (unconditional) {
            RuntimeLaws.withComputation(
                json, comp.groupPath(), comp.name(), comp.computedFieldRelPath(), r.render(alts[0].operation()),
            )
        } else {
            RuntimeLaws.withTieredComputation(
                json, comp.groupPath(), comp.name(), comp.computedFieldRelPath(), common,
                alts.map { RuntimeLaws.CompAlt(it.precondition().map { p -> r.render(p) }.orElse(null), r.render(it.operation())) },
            )
        }
    }

    /** The interpreter [ComputationDef] for the typed [comp] — the interpreter side. */
    fun def(comp: Computation): ComputationDef {
        val r = DslRenderer(ConditionLanguage.EN, comp.groupPath())
        return ComputationDef(
            comp.computedField().path(), comp.groupPath(),
            comp.alternatives().map {
                ComputationAlternative(it.precondition().map { p -> r.render(p) }.orElse(null), r.render(it.operation()))
            },
            comp.commonPrecondition().map { r.render(it) }.orElse(null),
        )
    }
}
