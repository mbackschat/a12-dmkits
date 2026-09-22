package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.ToleranceBand
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
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
            json, rule.group().path().value(), rule.name(), rule.errorEntityRelPath(), rule.code(), cond(rule),
        )

    /** The interpreter [RuleDef] for the typed [rule] — the interpreter side. */
    fun def(rule: Rule): RuleDef =
        RuleDef(
            "${rule.group().path().value()}/${rule.name()}", rule.group().path().value(),
            cond(rule), rule.code(), rule.errorEntityRelPath(),
        )

    /**
     * Inject the typed [comp] into [json] (simple or tiered — chosen by its alternatives) — the kernel side.
     *
     * The splice is [RuntimeLaws.withComputation]'s typed twin, which reads the same alternatives off the draft
     * and picks the same arm; `TypedComputationSpliceTest` pins it byte-equal to the String overloads.
     */
    fun inject(json: String, comp: Computation): String = RuntimeLaws.withComputation(json, comp)

    /** The interpreter [ComputationDef] for the typed [comp] — the interpreter side. */
    fun def(comp: Computation): ComputationDef {
        val group = comp.groupPath()
        return ComputationDef(
            comp.computedField().path().value(), group,
            comp.alternatives().map {
                ComputationAlternative(
                    it.precondition().map { p -> RuntimeLaws.render(group, p) }.orElse(null),
                    RuntimeLaws.render(group, it.operation()),
                    it.toleranceRangeOp().map { band -> ToleranceBand.ofWire(band.wire()) }.orElse(null),
                )
            },
            comp.commonPrecondition().map { RuntimeLaws.render(group, it) }.orElse(null),
        )
    }
}
