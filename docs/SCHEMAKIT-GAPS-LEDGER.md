# schemakit — import gaps ledger

> **What this is** — the `-LEDGER`-family tracker for the **`:schemakit` JSON-Schema importer**, a peer of [`DEV-API-GAPS-LEDGER.md`](DEV-API-GAPS-LEDGER.md) / [`INTERPRETER-GAPS-LEDGER.md`](INTERPRETER-GAPS-LEDGER.md) / [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md). It records **only OPEN, tractable-but-deferred import gaps** — a constraint a12 *could* express but the importer does not synthesize **yet**. The stable mapping contract lives in [`SCHEMAKIT-SPEC.md`](SCHEMAKIT-SPEC.md) §4; this ledger holds the moving edge.
>
> **No-history policy** (like its siblings): a shipped gap is **deleted**, never kept as a "shipped" list — git is the *what/when*, and the gap's home is the SCHEMAKIT-SPEC §4 "built" row (its *why*). The `else` branch and `not {pattern}`/`{minimum}`/`{maximum}` shipped this way and so are **not** here.
>
> **Open vs not-feasible:** §1 holds **feasible-but-deferred** gaps (a12 *could* express it — there is a kernel operator/structure, possibly needing DSL plumbing). A feature with **no faithful a12 form at all** is *not feasible*, not deferred — it lives in **§4 (Not feasible)**, with the reason. Import never blocks either way: every item still imports the **structure**; only the listed *constraint* is reported, not enforced (the omit-never-silent rule, SCHEMAKIT-SPEC §4a).

---

## 1. Open gaps

The live, unfinished import work. All are **reported, not enforced** today (the structure imports; the report carries a corrective to-do) — except where noted as a refusal precondition.

### Rule-synthesis gaps

| Gap | The constraint | What a12 needs | Tractability |
|---|---|---|---|
| **non-simple `contains` predicate** | `contains` whose item subschema isn't a single `{const}` (scalar) or single `{properties:{f:{const}}}` (object) — e.g. multiple properties, ranges, nested | a multi-clause `Having` filter (conjunction of per-field predicates) inside the count | medium. (A simple-predicate `contains`/`minContains`/`maxContains` already maps — §4f.) |
| **multi-property else value-constraints** | `if {a=X, b=Y} else {properties:{g:{…}}}` | the negation of a conjunction is a disjunction — an `Or` of the per-condition negations (`LogicCondition.OR` exists) AND the violation mapper, error on `g`. (Single-property `else` value-constraints already map — §4f.) | low–medium |
| **`not {required}` / nested / compound `not`** | object-level `not {required:[…]}`; `not {not {…}}`; a `not` with several keywords (today only the first recognized keyword maps) | `not {required}` → fire on the fields being **present** (`FieldFilled`/`AllFieldsFilled`); nested/compound → De-Morgan into the paired negated operators | `not {required}` low–medium (needs object-level `not` handling); nested/compound complex. (`not {const\|enum\|pattern\|minimum\|maximum\|minLength\|maxLength}` already maps — §4/§4f; `not {type}` is **not feasible** — §4.) |

### OpenAPI / structural gaps (SCHEMAKIT-SPEC §4g)

| Gap | Today | What it needs | Tractability |
|---|---|---|---|
| **mutual recursion across components** | `--out-dir` mounts cross-component object refs and guards the **self**-reference case (expand-then-omit); a *cycle* across components (A mounts B, B mounts A) still produces models the kernel rejects (cyclic includes) | cross-component cycle detection → break one edge by inlining/expanding it, as for self-recursion | low–medium; rare in practice |

---

## 2. Known semantic divergence (a recorded decision, not a gap to close)

**`if`/`then`/`else` maps the *pragmatic* reading, not strict JSON Schema.** Because a bare `if: {properties: {tier: {const: "PREMIUM"}}}` does **not** *require* `tier`, an instance with **no `tier`** validates the `if` vacuously → strict JSON Schema applies `then` even when `tier` is absent. The importer instead maps:

- **then** → fires when `tier == PREMIUM` (does *not* fire when `tier` is absent);
- **else** → fires when `tier` is **present and** `≠ PREMIUM` (the `Filled` guard).

This matches the near-universal author intent ("when tier *is* PREMIUM …") and is the right default for best-effort import; schemas that pair the `if` with `required: [tier]` have **no** divergence. Recorded here so it is a deliberate decision, not an accident. Re-open only if a consumer needs strict vacuous-match fidelity (then: also fire on the discriminator's absence) — at which point it becomes a profile switch, not a silent change.

---

## 3. Deferred / out-of-scope (contract & architecture)

Decisions that bound the transcoder's reach today, each with its re-open trigger. Moved here from the spec — a spec holds the *built* contract, not its open edges.

| Item | Decision | Re-open trigger |
|---|---|---|
| **Live Tier-2 validation** | The interpreter as the *real* full-rule tier (not just demonstrating carriage + complementarity) needs the DM-JSON→`ModelDef` loader, which lives in **`:cli`** (`interp.InterpreterModelLoader`); reuse-first forbids reimplementing it, and depending on `:cli` from `:schemakit` is a backwards edge. **Deferred (2026-06-28)** — live Tier-2 stays in the CLI. (Shape 5 already proves Tier-1 via a stock validator + that the cross-field rule is *carried* for Tier-2.) | a kernel-free **in-process** Tier-2 round-trip is needed → first move/expose the loader in `:interpreter`, then wire it |
| **`ExternalSchemaProfile` seam** | Keep the core mapping profile **non-JSON-Schema-specific** so OpenAPI / TypeScript / SQL / Protobuf can become later import profiles (proposal §12.2 #4). No second profile is built today. | a second external-schema source is taken on → factor the JSON-Schema-specific glue behind the seam |

---

## 4. Not feasible — no a12/kernel representation

These have **no faithful a12/kernel form**, so they are *not* deferred work and will **not** be built. The importer **reports** each (the structure still imports, the rest of the document maps); this section is the single "what won't be built, and why." The test that an item belongs here, not in §1: there is **no kernel operator / structure** that could express it, even with new DSL plumbing.

| Construct | Why a12 cannot represent it |
|---|---|
| **`not {type}`** | an a12 field has exactly **one fixed kind** — structural, enforced by the type system, not by a rule. There is no runtime "is-of-type" operator to negate, so "the value must not be of type X" is either vacuous (X ≠ the field's kind) or contradictory (X = it). Nothing to synthesize. (Contrast `not {const\|enum\|pattern\|min\|max\|minLength\|maxLength}`, which all have a paired negated operator and **do** map — §4/§4f.) |

**Also permanently omitted, for the same "no static a12 form" reason** (documented in [`SCHEMAKIT-SPEC.md`](SCHEMAKIT-SPEC.md) §4c / [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md)) — referenced here so the impossibility view is complete:

- **cross-kind type unions** (`type:["string","object"]`, mixed `oneOf`/`anyOf`) — an a12 field has one kind (§4c).
- **`$dynamicRef` / `$dynamicAnchor`** — runtime-polymorphic references with no static form (§4c).
- **`unevaluatedProperties` / `unevaluatedItems`** — applicator-dependent dynamic closure (§4c).
- **`minItems`** (and any group **lower-bound** cardinality) — no a12 row-count rule (KERNEL-FINDINGS).
- **self- and cyclic includes** — the kernel rejects "a model includes itself" (proven on import; cycles are the same class). Recursion is bounded by `--recursion-depth` then omitted (§4e). *This is why the mutual-recursion item in §1 is only a crash-avoidance **mitigation**, not a true cyclic wiring.*
