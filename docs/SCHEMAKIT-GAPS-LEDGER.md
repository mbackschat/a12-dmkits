# schemakit — import gaps ledger

> **What this is** — the `-LEDGER`-family tracker for the **`:schemakit` JSON-Schema importer**, a peer of [`DEV-API-GAPS-LEDGER.md`](DEV-API-GAPS-LEDGER.md) / [`INTERPRETER-GAPS-LEDGER.md`](INTERPRETER-GAPS-LEDGER.md) / [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md). It records **only OPEN, tractable-but-deferred import gaps** — a constraint a12 *could* express but the importer does not synthesize **yet**. The stable mapping contract lives in [`SCHEMAKIT-SPEC.md`](SCHEMAKIT-SPEC.md) §4; this ledger holds the moving edge.
>
> **No-history policy** (like its siblings): a shipped gap is **deleted**, never kept as a "shipped" list — git is the *what/when*, and the gap's home is the SCHEMAKIT-SPEC §4 "built" row (its *why*). The `else` branch and `not {pattern}`/`{minimum}`/`{maximum}` shipped this way and so are **not** here.
>
> **What is NOT a gap (so it is not here):** a feature with **no faithful static a12 form** is a permanent omission, not deferred work — those live in SCHEMAKIT-SPEC §4c (cross-kind unions, `$dynamicRef`, `unevaluated*`, unbounded recursion) and the **`minItems`** cardinality case (no a12 row-count rule — [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md)). Import never blocks: every gap below still imports the **structure**; only the listed *constraint* is reported, not enforced (the omit-never-silent rule, §4a).

---

## 1. Open gaps

The live, unfinished import work. All are **reported, not enforced** today (the structure imports; the report carries a corrective to-do) — except where noted as a refusal precondition.

### Rule-synthesis gaps

| Gap | The constraint | What a12 needs | Tractability |
|---|---|---|---|
| **non-simple `contains` predicate** | `contains` whose item subschema isn't a single `{const}` (scalar) or single `{properties:{f:{const}}}` (object) — e.g. multiple properties, ranges, nested | a multi-clause `Having` filter (conjunction of per-field predicates) inside the count | medium. (A simple-predicate `contains`/`minContains`/`maxContains` already maps — §4f.) |
| **else value-constraints; then `pattern`** | `if {f=v} else {properties:{g:{…}}}` (the else peer), or `then {properties:{g:{pattern}}}` | else value-constraints need the negated condition (single-property) / a disjunction (multi-property); `then {pattern}` needs a *negated-match* condition a12's rule DSL doesn't offer | else: low–medium (single-condition); then-pattern: hard (no negation). (`then {const\|enum\|min\|max\|exclusive*}` already maps — §4f.) |
| **complex / compound `not`** | `not {type}`, `not {required}`, **nested** `not`, or a `not` carrying **several** keywords (compound — today only the first recognized keyword maps) | `type`/`required`/nested have no clean single-field rule; a compound `not` is "not (all of)" → needs decomposition | hard; low ROI (rare). (`not {const\|enum\|pattern\|minimum\|maximum\|minLength\|maxLength}` already maps — §4/§4f.) |

### OpenAPI / structural gaps (SCHEMAKIT-SPEC §4g)

| Gap | Today | What it needs | Tractability |
|---|---|---|---|
| **mutual recursion across components** | `--out-dir` mounts cross-component object refs and guards the **self**-reference case (expand-then-omit); a *cycle* across components (A mounts B, B mounts A) still produces models the kernel rejects (cyclic includes) | cross-component cycle detection → break one edge by inlining/expanding it, as for self-recursion | low–medium; rare in practice |
| **array-of-`$ref` items** | an `array` whose `items` is a `$ref` to a component isn't recognized as an object array (no `items.type:object`) → treated as a scalar array | follow the `items` `$ref` before the scalar/object dispatch (then mount/inline per mode) | low–medium |

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
