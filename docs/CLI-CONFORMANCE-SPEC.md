# CLI conformance — the self-explain + diagnosis harness

This is the **contract** every `dmtool` verb must satisfy and the **gates** that enforce it. Its purpose is the project's #1 priority — a cold LLM agent (and a library dev) must be able to *discover* the surface and *recover* from every error using the tool's own output, never an out-of-band skill. The harness exists because that promise was silently unmet: a hand probe of 13 mutating verbs (2026-06-13) found **11 returned a bare stderr string on a not-found input** — no envelope, no code, no fix — and one accepted invalid input at exit 0. No gate caught any of it. The lesson driving this spec:

> **Gates over checklists.** Anything an LLM (or a human) can skip, will eventually be skipped. A criterion that matters becomes an executable gate that fails the build — a checklist item is only the human-readable shadow of a gate.

The home of the *safety-gate* contract is [`CLI-SPEC.md`](CLI-SPEC.md) §6; the *operator vocabulary* is the `operators` verb (the self-describing catalog; its design is the catalog spine in [`PROJECT-DESIGN.md`](PROJECT-DESIGN.md)). This doc is the *conformance* contract — what makes a verb self-explaining and well-diagnosed, and the guards that prove it.

## The anti-patterns it forbids (grounded in observed misses)

| # | anti-pattern | observed |
|---|---|---|
| **AP1** | **bare-fail domain error** — `fail(msg)` (stderr text, exit 2) for a not-found/invalid condition instead of a structured envelope | 11 of 13 mutating verbs |
| **AP2** | **asymmetric gating** — one wrong-input position richly diagnosed, its peer not | refactor source vs destination |
| **AP3** | **silent accept** — invalid input → exit 0, no diagnostic (the agent thinks it succeeded) | `config modify --decimal-separator XYZ` |
| **AP4** | **happy-path-only tests** — success + the refusals the author *thought of*, never the full error matrix | the original move verbs |
| **AP5** | **surface rot** — stale `@Command`/param descriptions feeding `manifest`/help; un-described params | the pre-flip rename descriptions |
| **AP6** | **under-surfaced verb** — a verb ships listed + described but its **I/O shape is undiscoverable** (no resolvable `schema` pointer) or it is never **demonstrated in action** | `meta` shipped with no `schema` pointer; the templates/cross-cutting verbs had no worked example |

## The two contracts

### 1. The diagnostic contract (diagnosis)

> A **domain error** of any mutating verb — and of any locator-bearing **read** verb (a not-found target) — is a **structured envelope** — `outcome ∈ {refused, rejected}`, exit `2`/`1`, `written:false` — carrying **≥1 diagnostic with `code` + `where` + `fix`**, and the `code` **resolves in the diagnostic registry**.

- **Domain error** = the CLI syntax parsed, the model loaded, the op was attempted, and a model-level precondition failed (not-found, collision, type mismatch, kernel reject, …). These MUST be structured.
- **Usage / IO errors** (bad CLI syntax, missing/unreadable `-m`) MAY stay plain text — they are picocli/IO concerns, not domain diagnoses.
- **`refused`** = a pre-mutation safety-gate precondition failed (exit 2, nothing written). **`rejected`** = the kernel re-validate failed (exit 1). Never a bare `fail()` for a domain condition; never `outcome:applied`/exit 0 on invalid input (AP3).
- Every **error-severity** diagnostic carries `code` + `where` + `fix`. `explain` (the *why*) is added where it helps.

### 2. The self-explain contract (discoverability)

A cold agent learns the whole surface from the tool:

| an agent can discover… | via | gate |
|---|---|---|
| every verb + its params (with op-record key) | `manifest` (derived from the live picocli tree) | `ManifestVerbTest` / `ManifestTreeTest` |
| what each verb does | the `@Command` description (non-blank, capability-only) | `ManifestVerbTest` |
| what each param means **and what to pass** | the param description **+ its `@ParamKind`** (e.g. "a FIELD_PATH that must exist" vs "a NEW_NAME you choose") | the conformance matrix (description) + `ParamKindManifestTest` (kind) |
| a verb's I/O shape | `schema <target> <op>` → the `result` schema (and `schema <verb>` for the single-token **envelope** verbs like `meta`); input-bearing verbs add an input schema with CI-executed `examples` | `SchemaVerbTest` / `SchemaExamplesGuardTest` / the conformance matrix |
| **a verb in action** | the verb's `example {intent, command}` in `manifest` — a worked, read-only invocation the agent can run | the conformance matrix / `VerbDemonstrationTest` |
| **what a `code` means** | `dmtool diagnostics` (list) / `dmtool diagnostics <CODE>` (explain) — the `operators` twin, derived from the registry | registry parity guard |
| what `severity`/`source` mean | `schema diagnostic` (the `severity`/`source` enum descriptions) | schema guard |

## The gates (what enforces each rule)

| gate | proves |
|---|---|
| **diagnostic-conformance fuzzer** | for every mutating verb — and every READ-projection verb's locator positional, regardless of `@ParamKind` (F5) — derived from the live tree, every synthesized **domain error** is a conformant structured envelope — code+where+fix, code∈registry, exit/outcome consistent, `written:false`. A new mutating OR read verb **cannot escape it.** Kills AP1/AP2/AP4. |
| **registry parity** (no-rot) | every `RK_*` code the tool can emit resolves to a registry entry — `RkCodeParityTest` scans every emit site (an enum reference, or a raw `"RK_*"` literal as in `SafetyChecks`/`ApplyCommand`) against the typed `RkCode` enum, so an un-catalogued code can't ship. `MVK_*` ride `MVK-LEDGER`/`MvkLedgerTest`. |
| **single-source derivation** (no-drift) | the `diagnostics` verb, the `diagnostic.schema` enum/examples, and the API Javadoc all **derive from the one registry** — a guard forbids a second hand-authored copy of a code's meaning. |
| **conformance matrix** (`CliConformanceMatrixTest`) | the single **derived** per-verb gate (folds the old completeness check): every verb is described, every param is described, every **envelope** verb advertises a `schema` pointer that *resolves to real JSON*, and every verb is **demonstrated** (a worked example in `manifest`). One row per verb, one column per obligation — so a new verb can't ship under-surfaced, and a newly-learned anti-pattern is a one-column change. Kills AP5/AP6. |
| **demonstration gate** (`VerbDemonstrationTest`) | every `verb-examples.json` invocation (the one `manifest` surfaces) is **executed through the tool** against a fixture and must produce its documented outcome — so the "in action" example an agent reads cannot rot. All examples are read-only (read or `--dry-run`). |
| **silent-accept guard** | scalar/enum setters reject invalid input with a structured diagnostic (or are documented freeform + kernel-confirmed). Kills AP3. |
| **negative-control rehearsals** | each gate above is proven to **fire** (a fabricated bare-fail verb → fuzzer red; an un-catalogued code → red; a param missing a kind → red) so a guard can't rot into a no-op. |

> **`@ParamKind` classifies the value's *role*, not its multiplicity** (recorded from the Set-G T7 probe, 2026-07-10). A repeatable `Map<String,String>`/`List<String>` option — `config modify --label <locale>=<text>`, `--annotation <name>=<value>`, `--role <name>`, `--supported-character <singleton-range-or-grapheme>` — is correctly tagged `SCALAR`: each entry's *value* is a scalar, and there is deliberately **no `MAP`/`LIST` kind** (multiplicity is picocli's concern). Nothing is lost: the manifest self-describes the repeatable/map shape via the param's `flag`/label (`<locale>=<text>`) and description ("*(repeatable), e.g. `--label en_US=Permit`*", verified in the live manifest). It is also **fuzzer-inert** — the conformance fuzzer only synthesizes a `benignDefault()` for *required* params, and every one of these is optional. (Latent note for a future author: a *required* Map/List option tagged `SCALAR` would have the fuzzer pass the scalar default `"1"`, not a valid `k=v` — tag such a param `FREEFORM` instead. None exists today.)

## The conformance matrix — "is this new verb actually surfaced?"

The recurring failure mode is subtle: you add a verb, it *works*, it's *listed* in the manifest — but its I/O shape is undiscoverable or it's never shown in action, and no gate complains (AP6). The matrix (`CliConformanceMatrixTest`) makes "fully surfaced?" **one green/red signal**. It is **derived** — it walks the live `manifest` and asserts a per-verb obligation row, so a new verb is in scope automatically; you cannot opt out by forgetting to register a check.

| column | obligation | exemptions |
|---|---|---|
| **described** | non-blank capability description | none — every verb |
| **params-described** | every param has a non-blank description | none |
| **schema-resolves** | advertises a `schema` pointer that resolves to real JSON | verbs without a directional contract (below) |
| **demonstrated** | carries a CI-executed worked invocation an agent can see *in action* | the self-describing verbs |
| **status-free** | no verb/param description carries roadmap language (`TODO`, `not yet`, `for now`, `coming soon`, …) | none |
| **verdict-provenance** | `valid` appears only on a verdict route (`model`/`rule`/`workspace check`, `patterns scaffold`) | the list itself is the ledger, checked in both directions |
| **runtime-attribution** | a verb whose demonstrated envelope names `engine:DM_INTERPRETER` never attributes its runtime output to the kernel in its manifest description, example intent, or output-schema description | denominator derived from executed demonstrations; the real shipped false sentence is the positive control |
| **receipt-totality** | a preview that ran the consistency oracle publishes `verification` | `VerbDemonstrationTest.UNGATED_PREVIEW` — today only `model normalize`, with its reason |
| **`--text` renders** | an envelope verb's `--text` output is not the JSON envelope | the artifact/raw verbs, which have no envelope |

**The four columns added 2026-08-01 (Phase 3) are totality columns, and each replaced a check that could not fail for the reason it existed.** They are worth reading as a set, because the shared failure mode is what makes them necessary:

- **`--text` renders** — `TextRendererDispatchTest` pins renderer dispatch given a well-formed envelope, so it is structurally blind to a verb that never produces one. That is precisely how `apply` and `batch` emitted raw JSON under `--text`. The column asserts the *inverse* over all 63 envelope demos: the output must **not** be the JSON envelope, because pass-through is the failure mode.
- **receipt-totality** — the `ConsistencyEvidence` type makes a *wrong* provenance claim unrepresentable but leaves **silence** cheap: `of(x)` compiles for any witness and yields `none()` for one that never gated. A mis-named witness or a dropped gate therefore degrades to an absent key, failing nothing. Presence is asserted per verb over an enumerated list — never `anyMatch`, which one passing verb satisfies for all of them.
- **verdict-provenance** — the behavioural backstop for a structural property. The typed factories already restrict `valid` to two of them; this covers the axis structure cannot, namely whether the route being *run* is one of those.
- **status-free** — the surface is clean today, which is exactly when this costs nothing and refuses the first "not yet" someone reaches for later. An agent reads a description at the moment it decides what to run and cannot check whether the note is still true.

**One row per verb, one column per obligation.** A newly-learned anti-pattern becomes one more column, re-checked against every existing verb at once — the same growth loop that produced AP1–AP6. The matrix *folds and strengthens* the old `ManifestCompletenessTest`: that check **skipped** a verb that advertised no schema, which is exactly how `meta` slipped through; the matrix has no skip.

**The contract exemption is a reasoned ledger, not a silent skip.** `schema <verb>` serves result-envelope contracts and explicitly registered artifact contracts. `profile synthesize` is the artifact case: its contract exposes the current profile input schema, the `dm-json-model` stdout artifact, and the `-o` branch that writes the artifact and returns an applied result receipt. Verbs with no directional contract live in `SchemaCommand.CONTRACT_EXEMPT` as a `verb → why` map — the single source the manifest reads to decide the pointer *and* the matrix reads as the ledger:

- the four **self-describing** verbs (`manifest`/`schema`/`operators`/`diagnostics`) emit their own JSON — you run them to see it;
  **`patterns` no longer belongs in that list (2026-08-01; catalogue key corrected 2026-08-02).** It returns the standard result envelope, and its `data` payload now has a published schema (`patterns.schema.json`, served by `schema patterns`, in `DATA_SCHEMA`, conformance-checked by `DataSchemaConformanceTest`). The payload is a **`oneOf` over three shapes selected by the ARGUMENTS, not a flag** — no argument lists the catalogue, an id describes one idiom, an id plus `--arg` returns the scaffolded artifact — so all three need a real run or the `oneOf` carries unreachable branches. The catalogue calls its string-only projection `paramNames`; the detail route reserves `params` for `{name,kind,doc}` objects, so a consumer never has to infer a key's type from the route. The scaffold's `spec` is `$ref`'d from the `add` verbs' own input schemas rather than restated, since a restatement conforms to its meta-schema, executes nothing, and drifts invisibly (the `eval-doc` incident). A kernel-REJECTED scaffold returns the SAME shape with the verdict on the envelope, so it adds no branch; `--apply` is a different route that emits `changed` and no `data`;

**Descriptions are guarded only where the claim has a machine oracle.** `key:literal` claims are compared with the demonstrated envelope, interpreter runtime attribution is compared with the demonstrated `engine`, pointer syntax has its format-specific guard, and the document shape is single-sourced from `document-instance.schema.json`. General prose↔behaviour comparison is deliberately excluded: prose such as “best effort” or “what changed” has no generic oracle, and a regex broad enough to pretend otherwise would encode editorial taste as behaviour. A new claim kind joins the matrix only with its own observable oracle and positive control.
- the remaining unregistered artifact verbs (`export`, `model new`, `model seed`, `model import-jsonschema`, and `model export-jsonschema`) emit their artifact directly and stay exempt. (`model new` with `-o` additionally prints a write-confirmation envelope — the model went to the file, so stdout carries a machine-confirmable success signal — but its primary stdout form has no directional contract.)

The matrix proves the ledger is **honest**: every exempt verb exists in the manifest (no stale row) and carries a non-blank reason. Anything not exempt **must** be contract-discoverable — which is how `meta` (an envelope verb that was wrongly missing its pointer) is now locked in.

**The demonstration — every verb in action, surfaced by the binary.** A description tells an agent a verb exists; it does not show it *working*. Each verb carries a worked invocation in `verb-examples.json` (read-only by construction — a read, or a mutation under `--dry-run`), and the `manifest` surfaces it as the verb's `example {intent, command}` — so the demonstration ships *in the binary*, on the cold agent's material boundary, not in `examples/` (which the agent never sees). `VerbDemonstrationTest` **executes** every example against a fixture and asserts its documented outcome, so a drifted demo (renamed flag, changed behavior) fails the build. The matrix's `demonstrated` column accepts three forms: a `verb-examples` entry, a schema **input** example (the rich-input verbs, executed by `SchemaExamplesGuardTest`), or a reasoned row in `CliConformanceMatrixTest.DEMONSTRATION_PENDING` — a CI-locked debt ledger for a verb whose happy path needs a fixture the corpus lacks (**currently empty — every verb is demonstrated**; `typedef inline`/`import` close against the `typedefs.dm.json` + `catalogtypes.dm.json` fixtures). A **new** verb is in none of the three, so the gate fires.

## The gap-finder — the qualitative layer above the matrix (on-demand)

The matrix proves a description/example *exists, resolves, and runs*; it **cannot** judge whether the prose actually *helps* — a description can be non-blank yet useless, or a verb's semantics latent (the dual-mode `meta`). That residue is what `eval/gap-finder.workflow.js` finds. Three layers, cheapest-first:

```
deterministic matrix   →  gap-finder (this)        →  N6 benchmark
(per-commit, free)        (on-demand, ~3 agents)      (on-demand, dozens of agents)
"is it surfaced?"         "is the surface clear?"     "can an agent finish the task?"
```

**Mechanism.** k=3 **cold** agents (opus) are each handed *only* the binary's self-description (`manifest`/`--help`/`schema`/`operators`/`diagnostics` — the N6 material boundary, no source/docs/examples) and asked, per verb: *could you tell what it does, what to pass, and what comes back?* Each emits structured findings `{verb, gapType, detail}` over a **fixed `gapType` enum** (`AMBIGUOUS_MODE`, `UNCLEAR_INPUT`, `UNCLEAR_OUTPUT`, `PARAM_MEANING_UNCLEAR`, `MISSING_CONSTRAINTS`, `UNCLEAR_PURPOSE`, `OTHER`). The script **aggregates deterministically** — a `(verb, gapType)` pair survives only if a **majority** of agents raise it, which damps run-to-run LLM noise into a stable signal.

**The tripwire is a reasoned, both-directions-honest ledger** — `eval/gap-allowlist.json`, the same idiom as `CONTRACT_EXEMPT`/`DEMONSTRATION_PENDING`/the MVK ledger. Each entry is an accepted `(verb, gapType)` gap (by-design or won't-fix) with a reason. A run's agreed gaps diff against it: a pair **not** in the list is a new gap to triage; a list entry that **no longer surfaces** is stale and gets removed.

**It is on-demand, not a per-commit gate** (LLM cost + nondeterminism). Its output is *candidates*, not proof — a human triages them, and a gap that recurs **graduates into a new deterministic matrix column** (the AP1→AP6 growth loop). So the gap-finder *feeds* the matrix; the matrix is what actually gates. Run it via the `Workflow` tool; results + the allow-list are the durable record.

## Never stale — the structural guarantee

The diagnostic info is **derived, never authored twice**, and **CI-verified both directions**:

- **One registry**, keyed by a typed `RkCode` enum. Emit sites reference the enum where practical; the raw `"RK_*"` literals (e.g. in `SafetyChecks`/`ApplyCommand`) are caught by `RkCodeParityTest`, so an un-catalogued code can't ship.
- Every **surface** (CLI `diagnostics` verb, `diagnostic.schema`, the API Javadoc, the dev doc) reads/points to that registry — no parallel prose to drift.
- **Forward** parity: emitted ⊆ registry (`RkCodeParityTest`, a CI scan of emit sites). **Reverse** parity: every registry entry is emitted/reachable or explicitly classified (no dead rows).
- The `diagnostic.schema` `examples` and the conformance fuzzer **execute** against the live registry every build.

The `RK_*` registry is **rulekit-owned** — it has no kernel coupling, so a kernel upgrade doesn't touch it. A kernel upgrade that adds/renames `MVK_*` codes rides the existing **MVK ledger** (`MvkLedgerTest` / [`MVK-LEDGER.md`](MVK-LEDGER.md)), not this registry; the broader kernel guardians and their realignments are in [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md).

## Command examples never go stale — the two-guard defense

Help text, diagnostic hints, and schema descriptions are riddled with worked command examples (`dmtool -m <model> rule add …`, "use `rule read`") — and so are the **command-bearing markdown surfaces** an agent or installer reads: the source + public READMEs, the canonical `skill/SKILL.md` (the **N6 material-under-test**, so a stale verb there misleads the cold agent directly), and the Codex `AGENTS.md`. A third surface is command-bearing **JS an agent harness executes**: the eval workflow (`eval/benchmark.workflow.js`) embeds `${CLI} …` command forms (`${CLI}` = its `bin/dmtool` handle), and *that* is where the task-17 recheck drifted — a positional rule passed as `rule modify --rule <name>`, whose usage error the harness silently read as "kernel rejected", scoring a correct capability 0/3. A verb rename, a tool rename, or a hand-built stale form silently turns any of these into instructions an agent *will follow into a dead end* — a real defect that the conformance matrix (which checks a verb is *surfaced*) doesn't see, because it never reads the prose. Two complementary guards cover it, and the tool name is single-sourced so a rename is one edit:

| Lever | What it does | Catches | Test |
|---|---|---|---|
| **single-source name** | `Cli.TOOL_NAME` is the one literal; `RootCommand` binds it, `manifest.tool` derives from the live name | re-hardcoded names drifting from the binding | (compile-time) |
| **pattern guard** (cheap) | forbids a fixed list of *known-bad* forms (`rulekit …`, `doc …`, the flat-verb hints, positional `<model>`) | the specific stale idioms left by past renames | `NoStaleCommandSyntaxTest` |
| **positive guard** (rename-proof) | every `dmtool …` invocation + two-token `` `target op` `` shorthand must **resolve against the live picocli tree**, across the in-binary text, the markdown surfaces above, **and the eval workflow JS** (`${CLI} …` forms, `${…}` placeholders neutralized); an invocation-count floor doubles as the tool-rename trip-wire (the JS is counted separately, keyed on `${CLI}` not the tool name, so it can't prop that floor up) | a *future* verb rename/removal, a hand-built stale command form (the task-17 class), and a tool rename that leaves the old name in the text | `CommandReferencesResolveTest` |

The positive guard is the durable one: it needs no knowledge of *which* form went bad — it derives the valid verb set and the tool name from the same tree that drives parsing, so a rename that forgets an example fails the build with the file:line and the offending token. **Scope boundaries that keep it honest:** the `examples/` demos are already guarded by Showboat (it *executes* them, so a renamed verb fails there), and the skill's plugin copies by the `sync-plugin-assets.sh --check` copy-equality gate — so the guard scans only the *canonical* markdown files. **Markdown is read by code context, not prose:** inside a fenced block the whole line is a command; outside, only inline-code spans count — so a bare prose mention ("dmtool guidance") is ignored, while a `` `rule read` `` span and a fenced `dmtool …` line are checked. Illustrative placeholders the skill teaches with (`dmtool <target> <op>`, `operators <id>`) resolve cleanly — an angle-bracket token ends the path without a violation — and a `#` shell comment or a `|` shell pipe ends the command, so a concrete `dmtool -m order.dm.json rule add … # note` still validates `rule add`, and a `dmtool … model describe | jq --arg …` doesn't mistake jq's `--arg` for a dmtool flag. **The JS surface** keys off the `${CLI}` marker (not the tool-name literal, which the JS never writes inline): the args up to the closing (escaped) backtick are taken, each `${…}` placeholder is neutralized to a `<v>` token, and the same resolve + flag-check runs — so a stale op or a phantom flag (`rule modify --rule …`) fails here **statically**, alongside the runtime usage-error backstop for the dynamically-built commands a static scan can't see.

## Library consumers — the same registry, no second source

Diagnostics aren't a CLI-only concern: the diagnostic model is **public library surface**. A dependency that drives rulekit/modelkit directly receives `Diagnostic` objects (record: `code`/`severity`/`source`/`where`/`fix`/`explain`) from `ValidationResult.diagnostics()` and the edit/validate seams. So the meaning of a code must be **dev-discoverable**, not only CLI-discoverable — and it resolves through the *same* registry the `diagnostics` verb serves:

- **`RkCode` is public.** `RkCode.byCode("RK_NO_SUCH_FIELD")` returns the catalog entry — `meaning()`, `severity()`, `source()`, `fix()`. The CLI `diagnostics` verb is a thin shell over exactly this; a library consumer calls it directly (see `Diagnostic`'s Javadoc for the idiom). `Severity` (the kernel's enum) and `DiagnosticSource` carry their own Javadoc.
- **One source, both faces.** Tool and library read the one `RkCode` enum, so they never diverge — and the parity guard above keeps it honest. `MVK_*` codes are the kernel's; their meaning is the operator catalog's (`operators`/`explain`), not this registry.

## Authoring checklist — adding or changing a verb

The checklist is the human shadow of the gates above; the gates fail the build if you skip a row.

- [ ] **Discoverable:** `@Command` description is capability-only (no status/roadmap); every param has a description (add a `@ParamKind` where the value's role isn't obvious — it's additive, spot-checked by `ParamKindManifestTest`, not required on every param); the verb appears in `manifest` (flip `ManifestVerbTest.EXPECTED_VERBS`); `schema <target> <op>` (or `schema <verb>` for an envelope cross-cutting verb) resolves, or the verb is classified in `SchemaCommand.CONTRACT_EXEMPT` with a reason; an input-bearing verb adds an input schema + a CI-executed `examples` block.
- [ ] **Demonstrated:** add a read-only worked invocation to `verb-examples.json` (a read or a `--dry-run` mutation) so `manifest` surfaces the verb *in action* and `VerbDemonstrationTest` executes it — or classify it in `DEMONSTRATION_PENDING` with a reason. **The conformance matrix enforces this and the Discoverable row's description/manifest/schema obligations** (the `@ParamKind` is additive — `ParamKindManifestTest` spot-checks it).
- [ ] **Diagnosed:** every domain-error path returns a structured envelope (`refused`/`rejected`), not `fail()`; every error diagnostic has `code` + `where` + `fix`; each `code` is an `RkCode` registry entry (so it's catalogued + explorable via `diagnostics`).
- [ ] **Symmetric:** every input position that can be wrong has a diagnostic (no AP2); no input is silently accepted (no AP3).
- [ ] **Proven:** the conformance fuzzer is green for the verb; each new refusal has a does-fire test (the §6 refactor-gate rule); a new gate has a negative-control rehearsal.
- [ ] **Documented + demonstrated** in the living surfaces (the `examples/` demo for a CLI change; the API-GUIDE snippet for a library capability), per the user-facing-no-status rule.
