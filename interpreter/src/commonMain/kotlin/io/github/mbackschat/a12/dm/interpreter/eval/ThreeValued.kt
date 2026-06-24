package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Tri

/**
 * **Kleene's strong three-valued logic (K₃)** over [Tri] — the heart of the engine's truth model, and the
 * single mechanism behind formal-error suppression, empty-row gating, AND partial validation. Factored out of
 * the tree-walk as **pure, strategy-agnostic** functions (the kernel's `ValidierungsErgebnis.combineUND` /
 * `combineODER`): they depend only on resolved truth values, so both evaluation strategies share them — the
 * [Interpreter] tree-walk calls them today, a future code GENERATOR would emit calls to the same functions
 * (INTERPRETER-FINDINGS IF10).
 *
 * <h3>The third value, and what it MEANS</h3>
 * A rule's condition is `TRUE` (the rule fires — an error), `FALSE` (no error), or **`UNKNOWN`** — "not enough
 * information to decide." The defining law of K₃ (and the reason it's exactly right here): a combination is
 * `TRUE`/`FALSE` only when it would be that value for **every** way the `UNKNOWN`s could resolve; if the
 * `UNKNOWN`s could swing the outcome, the result is `UNKNOWN`. So `UNKNOWN` is precisely "the answer depends on
 * a value we don't have." A rule **fires only on `TRUE`** — never on `UNKNOWN` — so `UNKNOWN` reads as
 * "cannot convict."
 *
 * <h3>The truth tables (∧ = [and], ∨ = [or], ¬ = [not])</h3>
 * <pre>
 *   A ∧ B │ T  U  F        A ∨ B │ T  U  F          ¬A
 *   ──────┼─────────       ──────┼─────────        ─────
 *     T   │ T  U  F          T   │ T  T  T         ¬T = F
 *     U   │ U  U  F          U   │ T  U  U         ¬U = U
 *     F   │ F  F  F          F   │ T  U  F         ¬F = T
 * </pre>
 * Read the two corners that matter: **`T ∧ U = U`** (a true operand AND an unknown — the unknown could be
 * `F`, so we can't be sure → suppressed) but **`F ∧ U = F`** (a false operand forces the whole AND false — no
 * value of the unknown could rescue it). Symmetrically **`T ∨ U = T`** (a true operand fires the OR regardless
 * — no value of the unknown could prevent it) but **`F ∨ U = U`**. The "absorbing" value (`F` for ∧, `T` for ∨)
 * wins even against `UNKNOWN`; otherwise `UNKNOWN` propagates.
 *
 * <h3>Why this ONE table is the whole game</h3>
 * Every place the kernel "suppresses" a rule funnels an operand to `UNKNOWN`, and this table does the rest —
 * we never special-case suppression. The three sources of `UNKNOWN`:
 *  1. **Formal errors** — a referenced field whose value is malformed, required-but-empty, or a duplicate
 *     index is un-evaluable → its leaf is `UNKNOWN` ([Interpreter] `invalid`; KERNEL-FINDINGS §1, branch-scoped).
 *  2. **Empty-row gating** — a comparison's empty-as-0 can't fire a fresh row (the structural "can fire empty?"
 *     gate keeps it from being evaluated at all).
 *  3. **Partial validation** — a field outside the relevant set is treated as `UNKNOWN` (`DmInterpreter
 *     .validatePart`; KERNEL-FINDINGS "Partial validation").
 *
 * The payoff is the partial-validation punchline: the kernel's prose rule is *"an error is suppressed if the
 * rule references a non-relevant field — **except where the kernel is sure no possible value of that field
 * could have prevented the error**."* That exception is **not a special case** — it is just `T ∨ U = T`
 * (vs `T ∧ U = U`). Resolve non-relevant fields to `UNKNOWN`, run this table, and the "except where sure"
 * behaviour falls out for free. Same for a formal error: `healthyTrue ∨ [broken] > 0` still FIRES, while
 * `healthyTrue ∧ [broken] > 0` is suppressed — branch-scoped suppression is Kleene monotonicity, nothing more.
 */
object Kleene {
    fun and(a: Tri, b: Tri): Tri = when {
        a == Tri.FALSE || b == Tri.FALSE -> Tri.FALSE
        a == Tri.UNKNOWN || b == Tri.UNKNOWN -> Tri.UNKNOWN
        else -> Tri.TRUE
    }

    fun or(a: Tri, b: Tri): Tri = when {
        a == Tri.TRUE || b == Tri.TRUE -> Tri.TRUE
        a == Tri.UNKNOWN || b == Tri.UNKNOWN -> Tri.UNKNOWN
        else -> Tri.FALSE
    }

    fun not(a: Tri): Tri = when (a) {
        Tri.TRUE -> Tri.FALSE
        Tri.FALSE -> Tri.TRUE
        Tri.UNKNOWN -> Tri.UNKNOWN
    }
}
