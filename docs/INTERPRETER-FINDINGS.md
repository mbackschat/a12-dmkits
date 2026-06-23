# Interpreter findings — design & treatment record

The numbered **design-findings** for the `:interpreter` module (the kernel-free, native-safe + Kotlin/JS rule evaluator) — the *why* behind how it reproduces kernel behavior. This is the interpreter's peer of [API-FINDINGS.md](API-FINDINGS.md) (the rulekit/modelkit library's findings): a **rationale record, not a status surface**. What's *built* is shown by the differential tests (`adapter/…/laws/*DiffTest.kt`) and [INTERPRETER-BUILDOUT.md](INTERPRETER-BUILDOUT.md)'s coverage map.

**Where a fact lives (the routing rule the owner set, 2026-06-23):**
- a **kernel-behavior** truth → [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) (write), cross-checked against the consult-only `../a12-kernel-demo/KERNEL-SEMANTICS.md` (read);
- an **interpreter design / treatment** finding (how the interpreter mirrors the kernel, an idiom, a deliberate divergence) → **here** (write).

Correctness throughout = **agrees with the real kernel** (the `RuntimeLaws` differential oracle). Each finding cites the lock.

---

### IF1 — Operator dispatch is enum-keyed by the kernel construct name; the AST stays string-typed

The evaluator dispatches presence predicates and value functions on the `PredicateOp` / `FunctionOp` enums (each carrying its kernel construct name, e.g. `FieldFilled`, `Sum`), not on raw strings. But the AST node (`Cond.Pred.name` / `Expr.Func.name`) keeps the name as a **string** on purpose: the kernel vocabulary is open and growing (109+ constructs), and an *unmodeled* operator must round-trip far enough to be **reported** (`validate` returns it as `unsupported`), not fail at parse. So the parser emits the string; the evaluator decodes the handled subset via `byName`, and an unknown name → `null` → `UnsupportedConstructException` (caught and reported). The enums double as the handled-operator registry that operator-coverage growth extends. *(Locked: every `*DiffTest`; the registry is `Operators.kt`.)*

### IF2 — Value-list membership reuses the typed `==` path, so empty→UNKNOWN falls out for free

`FieldValueIncludedInValueList` is evaluated by `evalExpr(field)` then the same typed `eq` used by `==`, against each member expression. This means the kernel's two value-list facts (KERNEL-FINDINGS §8) are reproduced *without special-casing*: an empty string/enum operand evaluates to `Val.Unknown` → the predicate is `Tri.UNKNOWN` → neither the included nor the `Not` form fires; and members compare with the field's type (numbers numerically, strings by text). Reusing the existing 3VL machinery — rather than a bespoke string-contains — is why the differential passed on the empty and number cases. *(Locked: `adapter.laws.ValueListDiffTest`.)*

### IF3 — `$field$` and formal-error text: faithful structure, own prose

Both message tiers reproduce the kernel's **structural** channels exactly but diverge deliberately on free text, to keep the publishable `:interpreter` artifact clear of kernel-copyrighted strings:
- **`$field$`** resolves to the field's label-for-locale else the field NAME (`ModelDef.labelOf`), mirroring the kernel under a label provider — see KERNEL-FINDINGS §3 and the cross-engine treatment in [KERNEL-INTEGRATION-NOTES.md](KERNEL-INTEGRATION-NOTES.md).
- **Formal errors** emit the kernel's verbatim `FormalErrorCode.id` + `formalePruefung` + pointer + ERROR/VALUE, but the human text is the interpreter's **own wording (Option A)**; the byte-identical-kernel-text path is the deferred Option B (KERNEL-INTEGRATION-NOTES "Formal-error message text"). *(Locked: `adapter.laws.DisplayNameDiffTest`, `FormalErrorMessageDiffTest`.)*

### IF4 — `GroupFilled` is an `EvalContext` seam resolved only by the general `Document`

"Is this group filled?" needs the document's instance structure, which only the general `Document` models; the single-row / repetition spike contexts (`SingleRowDoc`/`RepDoc`, slated for consolidation) don't, and throw the same way `star` does on a flat doc. So `groupFilled` is an `EvalContext` method the general `Document` overrides (any valued field beneath the group, in the row's scope). The **repeatable**-group form is out of scope here: the kernel requires the `*` aggregate (`GroupFilled(Items*)`, KERNEL-FINDINGS §8), which parses to a `Star` operand and belongs to the `*`/aggregate family. *(Locked: `adapter.laws.GroupFilledDiffTest`.)*
