# schemakit — import gaps

> **What this is** — the `-GAPS`-family tracker for the **`:schemakit` JSON-Schema importer**, a peer of [`DEV-API-GAPS.md`](DEV-API-GAPS.md) / [`INTERPRETER-GAPS.md`](INTERPRETER-GAPS.md) / [`FEATURE-GAPS.md`](FEATURE-GAPS.md). It records the importer's **open build queue (§1)**, a recorded **semantic decision (§2)**, the constraints that are **feasible but deliberately not built (§3)**, and what has **no a12 form at all (§4)**. The stable mapping contract lives in [`SCHEMAKIT-SPEC.md`](SCHEMAKIT-SPEC.md) §4; this ledger holds the moving edge.
>
> **No-history policy** (like its siblings): a shipped gap is **deleted**, never kept as a "shipped" list — git is the *what/when*, and the gap's home is the SCHEMAKIT-SPEC §4 "built" row (its *why*). The `else` branch and `not {pattern}`/`{minimum}`/`{maximum}` shipped this way and so are **not** here.
>
> **The dispositions, by feasibility × intent** — an item sits in exactly one:
>
> | Section | Feasible? | Built / queued? |
> |---|---|---|
> | **§1 Open** | yes | queued to build |
> | **§3 Declined / deferred** | yes | **deliberately not built** (low ROI, or an architecture cost not yet worth paying) — each with a re-open trigger |
> | **§4 Not feasible** | **no** (no faithful a12/kernel form) | never |
>
> §2 is a different instrument: a recorded *decision* about **how** a construct maps, not a gap. **Import never blocks** across any of these — every item still imports the **structure**; only the listed *constraint* is reported, not enforced (the omit-never-silent rule, SCHEMAKIT-SPEC §4a).

---

## 1. Open gaps

- **Hygiene sweep (queued, Set F):** an orphaned stale Javadoc in `JsonSchemaImporter` claiming "minItems NOT enforced" (false since the 2026-07-09 synthesis) plus a second orphan; the dead empty `UNSUPPORTED_KEYWORDS` list and its reporting loop, with stale `task #n` Javadoc refs; and `TierAProjection` calling `intValueExact` on arbitrary exported rules — guard on `scale <= 0`, skip-and-report.

Before these, the queue was empty: the `minItems` synthesis shipped 2026-07-09 (`NumberOfFilledGroups(G*) < N`, in-group anchored + unguarded, round-trip-projected — SCHEMAKIT-SPEC §4f, `MinItemsSynthesisTest`), and the earlier synthesis backlog drained 2026-06-29 (coverage in SCHEMAKIT-SPEC §4d/§4f). The feasible-but-not-worthwhile constraints sit in **§3** with re-open triggers, the impossible ones in **§4**. A gap lands here only when it is both feasible **and** queued — a reader never sees a "TODO" nobody intends to do.

---

## 2. Known semantic divergence (a recorded decision, not a gap to close)

**`if`/`then`/`else` maps the *pragmatic* reading, not strict JSON Schema.** Because a bare `if: {properties: {tier: {const: "PREMIUM"}}}` does **not** *require* `tier`, an instance with **no `tier`** validates the `if` vacuously → strict JSON Schema applies `then` even when `tier` is absent. The importer instead maps:

- **then** → fires when `tier == PREMIUM` (does *not* fire when `tier` is absent);
- **else** → fires when `tier` is **present and** `≠ PREMIUM` (the `Filled` guard).

This matches the near-universal author intent ("when tier *is* PREMIUM …") and is the right default for best-effort import; schemas that pair the `if` with `required: [tier]` have **no** divergence. Recorded here so it is a deliberate decision, not an accident. Re-open only if a consumer needs strict vacuous-match fidelity (then: also fire on the discriminator's absence) — at which point it becomes a profile switch, not a silent change.

---

## 3. Declined / deferred — feasible, but deliberately not built

Constraints a12 *could* express (there is a kernel operator/structure, possibly needing new DSL plumbing) that are **not on the build queue** — each by a deliberate decision, each with the trigger that would re-open it. They are **not** in §1 (nothing here is queued) and **not** in §4 (they *are* possible). Each still imports the structure and reports the unmapped constraint (§4a), so leaving them costs nothing.

### Rule-synthesis — declined on ROI

| Construct | The constraint | What a12 would need | Why declined | Re-open trigger |
|---|---|---|---|---|
| **`contains` descending into a nested array, or an arbitrary combinator subschema** | a `contains` predicate that descends into a nested **array** (a deeper repetition — *nested aggregation*), or whose clause is a **combinator** (`oneOf`/`anyOf`/`not`) rather than a value constraint | nested array → nested quantification over a sub-repetition; combinator → reduce the subschema to a single row condition (an `Or` / negation of clauses) | complex; nested aggregation needs nested quantification, and an arbitrary combinator only *sometimes* reduces to a row condition. Value constraints (const/enum/**range**/pattern), single- and multi-property, **and nested-object clauses** (`payment/method`, `origin/address/country`), all map (§4f). | a real corpus shows either in meaningful numbers |
| **nested / compound `not`** | `not {not {…}}` (double negation); a `not` carrying several keywords (today only the first recognized keyword maps) | nested → unwrap the double negation; compound → De Morgan into the paired negated operators | **near-zero ROI** — double/compound negation is pathological in hand-written schemas, so building it now would be speculative. `not {const\|enum\|pattern\|min\|max\|minLength\|maxLength}` and object `not {required}` already map (§4/§4f); `not {type}` is **not feasible** (§4). | a real schema needs it |

### Contract & architecture — deferred

| Item | Decision | Re-open trigger |
|---|---|---|
| **Live Tier-2 validation** | The interpreter as the *real* full-rule tier (not just demonstrating carriage + complementarity) needs the DM-JSON→`ModelDef` loader. *The 2026-06-28 architectural blocker is GONE* (premise updated 2026-07-09): the loader moved to **`:interpreter`** (`interpreter.load.ModelLoader`, kernel-free multiplatform — API-FINDINGS 57), so wiring is now cheap and clean. **Still deferred** — purely on consumer-exists (Shape 5 already proves Tier-1 via a stock validator + that the cross-field rule is *carried* for Tier-2). | a consumer needs a kernel-free **in-process** Tier-2 round-trip → wire `ModelLoader` + `DmInterpreter` directly |
| **`ExternalSchemaProfile` seam** | Keep the core mapping profile **non-JSON-Schema-specific** so OpenAPI / TypeScript / SQL / Protobuf can become later import profiles (proposal §12.2 #4). No second profile is built today. | a second external-schema source is taken on → factor the JSON-Schema-specific glue behind the seam |

---

## 4. Not feasible — no a12/kernel representation

These have **no faithful a12/kernel form**, so they are *not* deferred work and will **not** be built. The importer **reports** each (the structure still imports, the rest of the document maps); this section is the single "what won't be built, and why." The test that an item belongs here, not in §3: there is **no kernel operator / structure** that could express it, even with new DSL plumbing.

| Construct | Why a12 cannot represent it |
|---|---|
| **`not {type}`** | an a12 field has exactly **one fixed kind** — structural, enforced by the type system, not by a rule. There is no runtime "is-of-type" operator to negate, so "the value must not be of type X" is either vacuous (X ≠ the field's kind) or contradictory (X = it). Nothing to synthesize. (Contrast `not {const\|enum\|pattern\|min\|max\|minLength\|maxLength}`, which all have a paired negated operator and **do** map — §4/§4f.) |

**Also permanently omitted, for the same "no static a12 form" reason** (documented in [`SCHEMAKIT-SPEC.md`](SCHEMAKIT-SPEC.md) §4c / [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md)) — referenced here so the impossibility view is complete:

- **cross-kind type unions** (`type:["string","object"]`, mixed `oneOf`/`anyOf`) — an a12 field has one kind (§4c).
- **`$dynamicRef` / `$dynamicAnchor`** — runtime-polymorphic references with no static form (§4c).
- **`unevaluatedProperties` / `unevaluatedItems`** — applicator-dependent dynamic closure (§4c).
- **self- and cyclic includes** — the kernel rejects "a model includes itself" (proven on import; cycles are the same class). The importer therefore **breaks** cycles rather than wiring them: a self-reference is expand-then-omitted, and a cross-component cycle has one edge inlined (a DFS back-edge) so the bundle still loads. Recursion itself stays bounded by `--recursion-depth` (§4e). *A true cyclic include is not representable — only the crash-avoidance mitigation is, and it is implemented.*
