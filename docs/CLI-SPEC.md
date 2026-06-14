# a12-dmkits — CLI spec (`dmtool`, the modeller/BA front-end)

The component spec for the standalone CLI (N10). The proposal's [§14/§16](PROJECT-PROPOSAL.md) state intent; [`ARCHITECTURE.md`](ARCHITECTURE.md) places it among the subprojects; this spec holds the design — the **op model**, the **verb surface**, the **diagnostics contract**, the dev/test workflow, sizing, and packaging. Per-verb implementation status lives on the [`API-POC-STUDY.md` §9](API-POC-STUDY.md) board; runnable demos in [`CLI-TOUR-SPEC.md`](CLI-TOUR-SPEC.md) ↔ [`../examples/`](../examples/).

> **Status.** This spec is the **target surface** — binary `dmtool`, target-first verbs (`dmtool -m <model> <target> <op>`), and the uniform result envelope (§3). The verb-first surface is mid-migration: the legacy names still resolve as hidden aliases until the flip completes (per-verb status on the §9 board). The structural refactor ops (§6) have **shipped, safety-gated** — `typedef rename`/`extract`/`inline`, `field`/`group`/`rule rename` and `field`/`group move` (both **reference-rewriting** via the kernel's `MoveSupportDM`), `group extract` (group→include), and `include inline` (the extract inverse). The §3.1 refactor set is complete.

## 1. Purpose & persona

`dmtool` is the **modeller/BA's** interaction surface (N10): one engine (core + adapter + the modelkit/rulekit kits), driven by an **agent** through verbs with **JSON in / JSON out**, the JVM bundled and invisible. It is *not* the developer's surface — a developer uses the kits as libraries (no CLI). It edits the **whole document model** — structure (fields, groups, type definitions, includes, doc config) *and* rules/computations — validates, reads/explains, and batches. The CLI never shells out to `javac`; the agent authors via **JSON / flags**, which `dmtool` deserializes *through the typed builders*, so the BA gets the builder pre-checks **plus** the kernel `Diagnostic`s (F4a) at runtime — the runtime analogue of the developer's compile-time steering.

## 2. Design principle — self-explaining **and** corrective diagnostics (the #1 priority)

The tool exists to be driven by a cold LLM agent. Two properties, above all else, make that work — and they are the tie-breaker for every CLI decision:

1. **Self-explaining & explorable.** A cold agent, given only the binary, must discover the verbs, their parameters and meaning, and each verb's I/O shape — *without* the N1 skill. Discoverability is a property of the **tool**, not the skill (which stays minimal: it teaches judgment — polarity, the traps — not the verb catalog).
2. **Corrective diagnostics.** Good diagnostics let the agent **fix its own mistakes**. Every error reaches *detect → locate → repair*: a stable `code`, a `where`, and a `fix`. Self-correction is a measured eval dimension (N-correct, [`EVAL-SPEC.md`](EVAL-SPEC.md) §3), not an aspiration.

| Layer | Question | Mechanism |
|---|---|---|
| **Targets & ops** | "what can this tool do?" | `dmtool --help` (target list) · `dmtool <target> --help` (its ops) · `manifest` (the whole tree, machine-readable, one call) |
| **Parameters** | "how do I call `<target> <op>`?" | per-op `--help` + the `manifest` `params[]` |
| **A worked call** | "show me this verb in action" | the `manifest` `example {intent, command}` — a read-only invocation per verb (CI-executed; see [`CLI-CONFORMANCE-SPEC`](CLI-CONFORMANCE-SPEC.md)) |
| **I/O shape** | "what does it emit / expect?" | `schema` — the result envelope (§3) + per-op input schemas |
| **Failure space** | "what can go wrong, and how do I fix it?" | each op advertises the `emits[]` codes in the manifest; every code has a `fix` (§4) |
| **DSL vocabulary** | "what operators exist, what do they mean — and how do I express the inverse?" | `operators` (list + per-operator semantics + gotchas); `operators <id>` carries `opposite` — the negation partner(s) (`FieldFilled`→`FieldNotFilled`, `<`→`>=`), serving the kernel's "no generic negation" model (G3) |
| **Validation idioms** | "what's the standard way to write *this kind* of rule?" | `patterns` (the catalogue of named idioms — date-order, required-when, …); `patterns <id>` (its params + polarity + skeleton); `patterns <id> --arg k=v` scaffolds a kernel-valid rule-spec (right polarity + a referenced error field baked in) and auto-checks it, `--apply` persists it (G2) |
| **Diagnostic codes** | "what does this `code` mean, how do I fix it?" | `diagnostics` (list all `RK_*` codes, filterable) · `diagnostics <CODE>` (one code's meaning + canonical fix) — the `operators` twin; see [`CLI-CONFORMANCE-SPEC`](CLI-CONFORMANCE-SPEC.md) |
| **Versions** | "what is this build, what does it target?" | `--version` (human) · `manifest.version` (machine-readable) — §2a |

**Implementation: picocli** (§8). One `@Command`/`@Option(description=…)` declaration per op drives parsing, `--help`, *and* the `manifest` — help cannot drift from behavior. The manifest emits the **entire** target×op tree in one response, so an agent discovers everything without walking the subcommand tree interactively. Drift is caught at build: `ManifestVerbTest` (the verb tree), `SchemaVerbTest` (schema ↔ envelope coverage) — the F11 discipline.

### 2a. Version & compatibility surface

The tool reports **what it is and what it targets** on two peer surfaces fed from one `VersionInfo`: `--version` (human) and `manifest.version` (machine-readable, so a cold agent reads it like any other manifest fact). Five axes, each from its single source of truth: the **rulekit** release (baked from the build), the kernel **built-against** (the `build.gradle` pin) and **runtime** (the kernel jar on the classpath — may be newer), the **catalog verified-against** floor (`operators.json`), and the **model-format reference** (`core.ModelFormat.VERSION`).

```
$ dmtool --version
a12-dmkits 0.1.0-SNAPSHOT
  kernel: 30.8.1 (built) / 30.8.1 (runtime)
  catalog verified against: 30.8.1
  model version (reference): 28.4.0
```

**Compatibility — tolerant, but explicit, with the kernel as the authority.** Two version axes, each handled the same way (inform, don't second-guess the kernel):

- **Kernel skew** (F11): a runtime kernel newer than the catalog floor is safe (rulekit binds only the public surface), so it yields a single advisory INFO `RK_KERNEL_VERSION_SKEW` — never an error. `manifest.version.kernel.skewed` exposes it too.
- **Document `modelVersion`** (the load-time policy): the kernel itself accepts an older same-major model and rejects a newer/different-major one with a clear `"Version mismatch …"` ERROR (KERNEL-FINDINGS, *Header `modelVersion` compatibility*). rulekit **classifies** that verdict so it's machine-actionable: the un-coded kernel ERROR is enriched to **`RK_MODEL_VERSION_INCOMPATIBLE`** (+ a fix — the **fail-fast** case, `valid:false`, exit 1), and an accepted-but-off-reference model gets a tolerant INFO **`RK_MODEL_VERSION_SKEW`** (the **tolerant** case, still `valid:true`). rulekit never re-derives the rule or flips `valid` — the kernel decides; rulekit annotates.

**Write-back never bumps the version.** Edits are surgical (the `header` is untouched), so a loaded model writes back with its `modelVersion` verbatim — no implicit bump to the reference. (Locked: `cli.ModelVersionPolicyTest`.)

## 3. The op model — one record, two surfaces

Every action is one **operation record** — `{ target, op, args }` — and every action returns one **result envelope**. A single CLI invocation is *one* record; a batch is an ordered *list* of them ([§7](#7-batching--dispatcher-batch-vs-session-apply)). Define the vocabulary once; render it onto both surfaces. Consistency on the two axes — **element family** (modelkit structure ⟂ rulekit rules) × **operation** — then holds by construction, in both the CLI and the batch.

### 3a. Invocation shape — `dmtool -m <model> <target> <op> [args]`

The model file is **context**, named once with `-m/--model`, like `git -C <dir>` / `kubectl -n <ns>` / `docker --context`. The per-op object (a field path, a rule name) is a positional on the op, like `docker image rm <image>`. So the op's own flags are never interrupted by the model path, and the op record carries **no model** — making single-verb and batch perfectly symmetric:

```
single:  dmtool -m order.dm.json field add --group /Order --name PromoCode --kind STRING
batch:   dmtool -m order.dm.json apply --ops edits.json      # edits.json = [ {target,op,args}, … ]
```

`-m` is an **inherited, position-tolerant** option (accepted before or after the verb); if it's missing the diagnostic is corrective (`pass -m <model>`). We deliberately keep the model **explicit per call** rather than an ambient "use this model" state — a fully-specified, stateless invocation is easier for an agent to generate and to reproduce/debug.

**The workspace root — `-w/--workspace`, one option for the whole CLI.** A multi-file model `include`s / type-def-imports other models *by id*, so every reference-resolving verb needs to know where those models live. There is **one** way to say it: an inherited, repeatable **`-w/--workspace <dir>`** option (sibling to `-m`), set once for every verb, with the **`DMTOOL_WORKSPACE` env var** as the set-once-per-shell-session fallback (ideal for an agent harness). The resolution precedence, one rule for the whole CLI (`WorkspaceOption`):

```
-w/--workspace  >  $DMTOOL_WORKSPACE  >  the model's own directory
```

So a workspace resolves with no per-verb flag once the root is set, and a self-contained model needs none of it. **There is deliberately no separate `--include-dir`** — "include" is a document-model concept (a *mounted* sub-model), but the resolver serves *every* reference (includes **and** type-def imports), so naming it after one kind would mislead and split the vocabulary. The old `--include-dir` is a **fail-fast tombstone** (`Cli` errors with the `-w` replacement — no silent alias). It stays **observable, not ambient**: the option is in the manifest/`--help`, and an unresolved reference fails loudly naming the directories it searched (never a silent wrong-model). (Locked: `cli.WorkspaceOptionTest` for the precedence, `cli.WorkspaceRootTest` end to end + the tombstone.)

### 3b. The result envelope

Every verb and every batch op emits the same shape. It **embeds the existing diagnostics array verbatim** (§4); the new part is only the wrapper around it.

| field | meaning |
|---|---|
| `target`, `op` | echo the request — self-identifying, never mis-attributed |
| `outcome` | `applied · preview · refused · rejected · read · staged` (the execution result class) |
| `ok` | the operation executed as asked (`outcome ∈ {applied, preview, read, staged}`) |
| `valid` | *(validate/check, and after a mutating op)* the **subject model** is kernel-valid — **distinct from `ok`** |
| `summary` | one human-readable line (the agent's quick read / log line) |
| `changed` | *(mutations)* the delta on success — e.g. `{added, kind}`, a refactor's rewritten refs |
| `data` | *(reads/queries)* the op's payload — explanation tree · model card · fired-list · … ; shape is op-specific (`schema <target> <op>`) |
| `diagnostics` | exactly `ValidationResult.toJson()` — an array of `Diagnostic` (§4) |
| `written`, `output` | whether the model was written, and where (`null` for read/preview/refused/rejected) |

**The `ok` / `valid` split fixes a real overload:** `ok` = "the tool did the job"; `valid` = "the model is kernel-valid." A `model validate` that finds the model invalid is `ok:true, valid:false` — the tool worked, the model didn't. **Exit codes** key off this: **0** = `ok` and not `valid:false` (applied/preview/read of a valid subject); **1** = `rejected`, or a validate/check with `valid:false`; **2** = `refused` (safe-gate) or a usage error. Everything else lives in the envelope.

## 4. Diagnostics — reuse, extend, correct

The diagnostic is **already defined** — reuse it, don't reinvent it.

- **`Diagnostic`** (LSP-modelled): `severity` (ERROR/WARNING/INFO) · `source` (PRECHECK/KERNEL/LINT/ENVIRONMENT) · `code` (optional; `MVK_*` kernel / `RK_*` rulekit-or-structure) · `summary` · `where` · `fix` (optional) · `explain` (optional).
- **`ValidationResult{ok, diagnostics[]}`** with `toJson()` — the agent-parseable array (empty optionals omitted) that the envelope's `diagnostics` *is*.
- **`DiagnosticEnricher`** — the **catalog-join**: an `MVK_*` code is matched to its operator-catalog entry, and that entry's curated `fix`/`explain` are attached. The fix database is the N7-locked catalog — one source, so kernel diagnostics and authoring examples never drift.

Two extensions this redesign adds (minimal, deliberate):

1. **`Where` gains `path` and `arg`.** Today `Where` only points at a `rule` (+`operator`/`span`). Structure ops and arg-level errors have no locus — so `Where` grows a **model-element `path`** (any field/group/typedef/include) and an optional input **`arg`/token**, keeping `rule`/`operator`/`span`.
2. **An `RK_*` curated-fix source.** `DiagnosticEnricher` only fixes catalog `MVK_*` codes; the new structure/refusal codes (`RK_NO_SUCH_GROUP`, the safe-delete `RK_REFERENCED`, …) get a curated-fix table so **every error-severity code carries a `fix`** — CI-guarded.

**The corrective bar — detect → locate → repair.** A diagnostic that only says *what's wrong* lets the agent report failure; one that says *how to fix it* lets the agent close the loop. The `fix` is the highest-value field (an applicable DSL/path replacement or a discovery command, e.g. *"list paths with `field read --all`"*). Discipline: a "did-you-mean" only appears above an edit-distance threshold — a wrong suggestion is worse than none. The structured machine-applicable `suggest` is **deferred** until a consumer (an apply-the-fix harness) exists.

Worked shapes (the real `Diagnostic` fields, inside the envelope):

```jsonc
// kernel rejection — MVK_* verbatim, located, with a catalog-joined fix:
{ "severity":"ERROR", "source":"KERNEL", "code":"MVK_UNKNOWN_FIELD",
  "summary":"/Order/Quanity is not a field in the model",
  "where": { "rule":"/Order/MinQuantity", "operator":"…", "span":[12,26] },
  "fix":"replace /Order/Quanity with /Order/Quantity",
  "explain":"…" }

// structure precheck — the Where extension (path/arg) + an RK_* curated fix:
{ "severity":"ERROR", "source":"PRECHECK", "code":"RK_NO_SUCH_GROUP",
  "summary":"no group /Order/Lines in the model",
  "where": { "path":"/Order/Lines", "arg":"--group" },
  "fix":"list groups with `dmtool -m order.dm.json group read --all`" }
```

## 5. Verb surface — target × op

Every command is `dmtool -m <model> <target> <op> [args]`. Family (modelkit ⟂ rulekit) is a **grouping in `--help`/manifest**, not a command level — the real two axes (kind × op) *are* the two command levels. Two verbs sit outside the `-m`-single-model frame: **`model new`**, the sole model-*originating* verb (no `-m` — it creates a model from scratch: `--id`/`--locale`/`--root`, kernel-checked, emitted to stdout or `-o`, so an agent has a first step before there is any model to edit; like `export` it produces an artifact, `CONTRACT_EXEMPT`); and **`workspace list <dir>`**, the cross-*model* verb (it scans a *directory* of models, not one `-m` model — see the `workspace` row).

| target | ops | family |
|---|---|---|
| `model` | `new · info · describe · read · validate · export · eval --doc · compute --doc` *(the **document model** target — vs `--doc`, a document **instance**; renamed from `doc`, which is now a fail-fast tombstone. `info` = the header dashboard: identity + super/subtype graph + includes/type-def imports **resolved to files** + compact counts — the one facet no other read verb owns; `-w/--workspace` resolves, default the model's folder)* | (whole model) |
| `rule` | `add <spec> · read · explain · deps · check [--suggest-error-field] · modify --condition · remove · rename` *(`deps` = the rule's forward dependencies — the fields/groups it references + iteration; the thin projection of `explain`, the inverse of `where-used`. `check --suggest-error-field` = G4: list the valid error-field candidates (the condition's referenced fields inside its iteration scope) in `data`, so a valid field is picked up front instead of after a `MVK_ERROR_FIELD_NOT_REFERENCED` reject)* | rulekit |
| `computation` | `add <spec> · read · explain · modify --spec · remove` *(`add` runs G5 author-time lints: a forbidden construct in a precondition — `CustomCondition`/`RepetitionNotUnique` — and overlapping (identical) alternative preconditions, emitted as `RK_COMPUTATION_*` advisories that ride the envelope whether the kernel accepts or rejects)* | rulekit |
| `field` | `add · read · remove · rename · move` | modelkit |
| `group` | `add · multiselect · attachment · read · remove · rename · move · extract` *(extract = group→include refactor, §6)* | modelkit |
| `typedef` | `add · read · remove · rename · extract · inline · import` *(rename/extract/inline = Tier-1 structural refactors, §6; `import` declares a `purpose=typeDefinitions` reference — another model's type-defs merge in by id, not mounted)* | modelkit |
| `include` | `add · read · remove · inline` *(inline = group→include extract inverse, §6)* | modelkit |
| `config` | `read · modify` *(incl. `--comment`, the model-level `modelInfo.comment`)* | modelkit |
| `workspace` | `list <dir> [--recursive] [--format json\|table] [--validate]` · `graph <dir> [--recursive] [--format json\|tree\|dot]` · `validate <dir> [--recursive]` *(cross-**model**: `list` = a kernel-free per-model index — id/type, the super/subtype convention, and every `include`/type-def import **resolved to its file** (`--validate` flags which are kernel-valid); `graph` = the kernel-free **inheritance** hierarchy (subtype→supertype edges; `tree`/`dot` render it; composition lives in `list`/`model info`); `validate` = the **project gate** — kernel-validates every model (references resolved across the tree), emits per-model `{valid, diagnostics[]}`, and **exits 1 if any is invalid**. The split vs `list --validate`: that only flags a per-model boolean; `validate` surfaces the rich `MVK_*` diagnostics and fails the build — the form a pre-commit/CI hook calls.)* | modelkit |
| *(root meta)* | `manifest · schema · operators · patterns · diagnostics · batch · apply` *(`patterns` = the validation-idiom catalogue; `patterns <id> --arg k=v` scaffolds a kernel-valid rule-spec + auto-checks it, `--apply` persists — G2; CONTRACT_EXEMPT, self-describing like `operators`)* | rulekit |
| *(cross-cutting)* | `where-used --entity` · `meta <ref>` *(read/edit any element's descriptions + annotations — field/group/rule by path, type-def by id; `ELEMENT_REF`, refuses `RK_NO_SUCH_ELEMENT`)* | adapter / modelkit |

**Legacy → target-first** (the migration map; legacy names stay as hidden aliases for one release):

| legacy verb | target-first |
|---|---|
| `describe` / `validate` / `export` / `eval-doc` / `compute-doc` | `model describe` / `model validate` / `export` / `model eval --doc` / `model compute --doc` |
| `add <spec>` (rule) / `add <spec>` (computation) | `rule add <spec>` / `computation add <spec>` |
| `read --rule` / `read --all` / `explain --rule` / `explain --computation` | `rule read` / `model read --all` / `rule explain` / `computation explain` |
| `check` | `rule check` |
| `modify --rule --condition` / `modify --computation --spec` | `rule modify --condition` / `computation modify --spec` |
| `add-field` / `add-group` / `add-typedef` / `add-include` | `field add` / `group add` / `typedef add` / `include add` |
| `remove --field/--group/--rule/--computation/--typedef/--include` | `field remove` / `group remove` / `rule remove` / `computation remove` / `typedef remove` / `include remove` |

**`doc` → `model` is a clean rename, not a hidden alias.** The whole-model target was `doc`, but in A12 "document" is the runtime **instance** (the data) — the same word the `--doc` flag already means. So the target was renamed to **`model`** (the schema), leaving `doc`/`--doc` unambiguously the instance. Unlike the legacy migrations above (hidden aliases for one release), `doc` is a **fail-fast tombstone**: `dmtool doc <op>` does *not* run — it exits non-zero with a corrective message naming `model` (and echoes the corrected command), so a stale caller is *revealed and fixed*, never silently served. The tombstone is hidden from the manifest/`--help` (the canonical surface is `model`) and removable after a release. Rationale: a hidden functional alias would mask exactly the dependency a clean break surfaces (locked: `cli.DocTombstoneTest`).

**The spec-vs-flags asymmetry is inherent and stated, not hidden.** A rule/computation is a rich JSON envelope (condition, code, messages, severity), so `rule add` takes a `--spec <file>`; a field/group is a few scalars, so `field add` takes `--group/--name/--kind` flags. Consistency lives at the **selector** (`<target> <op>`), uniform across the lifecycle (`field add` ↔ `field remove`, `rule add` ↔ `rule modify`); the *payload* differs by necessity.

**Write convention.** Every mutating op writes the model **in place** by default, `-o <file>` redirects, `--dry-run` previews (read-only).

### 5a. Schema discovery & the op-record

`schema <X>` is addressed with the same tokens as the verbs:

- `schema result` / `schema diagnostic` — the cross-cutting structures (raw JSON Schema).
- `schema <target> <op>` — that op's **directional contract**, `{input?, docInput?, returns:"result", data?, dataSchema?}`: what it *consumes* — a rich JSON spec file (`input`, e.g. the rule-spec) and/or a `--doc` document **instance** (`docInput`, the `DocumentInstance` shape, for `model eval`/`model compute`/`rule test`) — and the shape it *returns* (a read's `data` payload is described by `dataSchema` where one is published — `Explanation`, `ModelCard`, `ValidationResult`, `EvalDocResult`, `WorkspaceList`, …). One call, unambiguous direction — never "is this what I send or what I get?". Flag-only ops carry no input schema (their params are in the manifest).

The `manifest` is the index that makes assembly *copy, not infer*: every verb entry carries `target`, `op`, its params (each with the CLI `flag` **and** the op-record `key` = the `--`-stripped name an `apply` op uses), a `schema` pointer (for the envelope verbs — including the single-token ones like `meta`; `export`/`where-used` and the self-describing verbs carry none, see `SchemaCommand.CONTRACT_EXEMPT`), and a worked **`example` `{intent, command}`** — a read-only invocation the agent can run to see the verb *in action* (CI-executed by `VerbDemonstrationTest`). The root carries `resultSchema:"result"` and the global `-m`. So an agent builds any call — single or batched — straight from the manifest. (`schema add` is gone: it splits into `schema rule add` / `schema computation add`.)

## 6. Op taxonomy — Local / Cascading / Refactor

Classify every op by **blast radius** — this is the standing rule for adding ops, CRUD or not:

| class | ops | semantics | mechanism |
|---|---|---|---|
| **Local** | add · read · modify · remove-leaf | one element only | plain surgery, gate at end |
| **Cascading** | remove-referenced | element + *deletes* dependents | the `where-used` refuse-or-`--cascade` gate |
| **Refactor** | **rename** (field/group/rule/typedef — **reference-rewriting** ✅) · **move** (field/group — **reference-rewriting** ✅) · **extract/inline typedef** ✅ · **extract group→include** + **inline include** ✅ | rename/move rewrite the element **and every reference** atomically → valid→valid (move also relocates the node to a new parent); extract/inline lift or fold a subtree at a same-name mount (paths preserved); typedef ops repoint structural pointers | the **safety gate** (✅, this §) for collisions/preconditions + the kernel's `MoveSupportDM` reference-rewriter (`adapter.ReferenceRewriter`) |

### The refactor safety gate — *check before you change* (standing principle, every refactor, now and future)

Every refactor — and every cascading op — is **precondition-checked and refusable**. The author stays in control; the tool never best-effort-rewrites and never silently degrades. The shape is always the same:

1. **Check** the *specific* refactor's safety preconditions against *this* model, **before** touching anything.
2. **Refuse if unsafe** — `outcome:refused`, exit 2, **no mutation** — with rich, corrective diagnostics (detect → locate → repair, §4): *what* is unsafe, *where*, and *how* to resolve it.
3. **Only when every precondition holds, mutate** (then the usual single end-gate).

This generalizes the safe-delete gate below to the whole Refactor class. Each refactor declares its own preconditions and the diagnostic it refuses with:

| refactor | unsafe precondition → refuse with a named diagnostic |
|---|---|
| **remove** (referenced) | anything still references the target → refuse, list referrers (`RK_REFERENCED`), or proceed only on explicit `--cascade` |
| **rename** | the target element does not exist → refuse (`RK_NO_SUCH_FIELD` / `RK_NO_SUCH_GROUP` / `RK_NO_SUCH_RULE`, located at the path); a name **collision** with an existing sibling → refuse (`RK_NAME_EXISTS`). References — including starred and `Opaque`-to-rulekit conditions — are **rewritten**, not refused (the kernel's lenient `MoveSupportDM`) |
| **move** | the source element does not exist → refuse (`RK_NO_SUCH_FIELD` / `RK_NO_SUCH_GROUP`); the destination parent group does not exist → refuse (`RK_NO_SUCH_GROUP`); a group moved **into itself or its own subtree** → refuse (`RK_MOVE_INTO_SELF` — it would orphan the subtree); the destination already has a child of that name → refuse (`RK_NAME_EXISTS`). When safe: relocate the node (re-prefixing its + descendants' ids) and **rewrite every reference** to the new path (same `MoveSupportDM` engine as rename), then re-validate. |
| **extract group → include** | a rule inside the subtree references **outside** it (`RK_OUTWARD_REFERENCE`, names the rules); the group is **repeatable** (`RK_REPEATABLE_INCLUDE_ROOT` — an include's root must be a single, non-repeatable group) → refuse. (A rule *outside* referencing *into* the subtree is fine — the mount preserves the path; verified.) |

**Two obligations on every refactor — non-negotiable:**

1. **Document the gate well in these md docs.** Its preconditions, its refusal conditions, and the diagnostics it emits belong here in §6 (or the refactor's own spec section) — never left implicit in code. The safety contract is part of the published, self-describing surface.
2. **Lock the refusal with a law test.** The refusal path gets a test proving the gate **fires** on unsafe input, just as the accept path proves it proceeds when safe. An ungated *or* undocumented refactor does not ship.

**Implemented** (2026-06-12) as `SafetyGate` (runs the registered checks in order, returns the first `Refusal`) + `SafetyChecks` (the reusable preconditions — the first is the `RK_REFERENCED` reference gate shared by safe-delete and safe-rename) + `Refusal`/`BaseCommand.refuse` (the `refused`-envelope emit). **Safe-delete (`field`/`group remove`) is the first adopter** — its hand-rolled refusal was replaced by the shared check. Locked by `SafetyGateTest` + the remove verb tests; each new refactor registers its own `SafetyChecks` precondition and adds the matching refusal test.

**Reference-rewriting rename + move shipped (2026-06-12) — §3.1 complete.** `field` / `group` / `rule` / `typedef rename` rename the declaration **and rewrite every reference** to it — rule conditions, error-entity paths, computations — then re-validate. **`field` / `group move`** add the node relocation on top of the same rewrite: detach the element, re-home it under a new parent (re-prefixing its + descendants' ids), rewrite every reference to the new path, re-validate. The rewriter is the kernel's own **`MoveSupportDM`** (lenient, token-precise, preserves absolute-vs-relative style, *follows* starred-group references, and also rewrites a group's index/sort-field refs — KERNEL-FINDINGS §10), wrapped at the N8 boundary as `adapter.ReferenceRewriter`; rulekit builds no parser of its own. For a `group` rename/move the whole subtree re-homes and references **into** it from outside are rewritten too. The gates refuse only on a precondition (rename: `RK_NAME_EXISTS`; move: `RK_NO_SUCH_GROUP` / `RK_MOVE_INTO_SELF` / `RK_NAME_EXISTS`); a narrow case the rewriter can't fix is caught loudly by the kernel re-validate (`rejected`), never a silent dangle. The eval task `03-rename-field` (refactor family) is now **active** — its Layer-1 oracle (`EvalGoldOracleTest`) applies the gold rename via the rewriter and asserts it re-binds valid→valid. (**Typedef rename/extract/inline** need no rewriter — a typedef never appears in a condition.) These reference-rewriting refactors (field/group **rename·move**, **rule rename**, **typedef rename**) run **both as standalone verbs and as `apply` op-records** (§7): a single `StructureRefactor` definition single-sources each op's gate + entity-moves + node change, so the standalone verb and the in-session apply op share one gate and can't drift. (**`extract`/`inline` stay standalone** — they produce/consume a second artifact, which apply's one-model-one-write shape doesn't represent.)

### Safe-delete — the Cascading gate (recorded so it isn't re-litigated)

Removing a field/group a rule/computation still references would dangle a reference. So `field remove`/`group remove` run the adapter's exact **`where-used`** first. If anything references the target it **refuses** (`outcome:refused`, exit 2) and surfaces the references as a structured diagnostic (`RK_REFERENCED`, `where.referencedBy:{rules,computations}`, a `fix`), so an agent can act — remove them first, or pass **`--cascade`** (which removes the referrers, then the element). **Never a silent cascade.** **One refinement for composite groups (§3.8):** the gate counts only referrers **outside** the removed subtree — a rule/computation that *lives inside* the group being removed (an attachment template's own internal rules, which the author never wrote) goes away with the subtree and must not trigger a spurious `--cascade`. So `group remove` drops in-subtree referrers (those whose full name is under `<group>/`) before applying the gate (`cli.GroupAttachmentVerbTest`). The *proactive* "what references this?" check lives at the CLI (which has the adapter — `where-used` is an adapter capability above `EditSession`, and `dm.modelkit ⟂ dm.rulekit` means modelkit can't see rule knowledge); the *authoritative* dangling-reference check stays the kernel gate (`EditSession.validate()`). Layering rationale: [`KERNEL-INTEGRATION-NOTES.md`](KERNEL-INTEGRATION-NOTES.md) "Structure edits + the delete-safety rule".

### What counts as a "reference" — the symmetry, and querying both directions

The whole Cascading/Refactor story hinges on one definition, so make it explicit. A **reference** is whatever the kernel's `getDirectlyReferencedEntities` returns for a rule — and it is **not** just the field a rule's error is *attached to*. It is **every field/group the condition reads** (the operands), and for a **computation** it additionally spans all four sites: the computed (calculated) field, the common precondition, each alternative's precondition, **and each operation expression**. A field read *only* inside a computation's operation — invisible to a rule scan — still counts. (KERNEL-FINDINGS §9 — the `getDirectlyReferencedEntities` primitive; locked by `adapter.WhereUsedInComputationsTest`.)

That definition makes the dependency direction — and the whole gate behaviour — fall out of a simple **referrer vs referent** asymmetry:

```
   rule / computation  ──references──▶  field / group
   (referrer — a leaf)                  (referent — depended-upon)
```

- A **rule or computation is a leaf**: nothing references *it*. So `rule remove` / `computation remove` need **no cascade gate** — removing one can't dangle anything (a computation's computed field simply survives, now unfilled). It only re-binds, so a removal that somehow broke the model still fails loudly.
- A **field/group is a referent**: removing it could dangle every referrer. So `field remove` / `group remove` are gated.

The three things you can do to a field a rule/computation *reads* — operand, precondition, computed-target, or error-entity alike — each resolve **valid→valid, or refuse/reject; never a silent break**:

| operation on a referenced field | what happens | safe? |
|---|---|---|
| **delete** (`field remove`) | every referring rule **and** computation surfaces via `where-used` → **refused** (`RK_REFERENCED`) unless `--cascade`, which removes the *whole* referrers first (it cannot excise a single operand from a condition) | gated |
| **rename** (`field rename --to`) | every referring site is **rewritten** to the new name — condition operands, preconditions, operations, error-entity + computed-field refs — atomically (`MoveSupportDM`), then re-validated | ✅ automatic |
| **move** (`field move --to <group>`) | same rewrite **plus** node relocation; refuses first on a missing/again-taken destination (`RK_NO_SUCH_GROUP` / `RK_NAME_EXISTS`) | ✅ automatic |

**Both directions are queryable today** — you don't reason about references by hand:

| direction | verb | output |
|---|---|---|
| **usages of an element** (inverse) | `dmtool where-used <model.json> --entity <path>` | `{entity, rules:[…], computations:[…], count}` — every rule/computation that directly references the field/group, the two kinds kept separate |
| **dependencies of a rule** (forward) | `dmtool -m <model> rule deps <rulePath>` | `data: {references:[{path, kind}], iterates, iterationGroup?, count}` — the fields/groups the condition touches + the repeatable group it ranges over. The focused verb; `rule explain` carries the same `references`/`iterates` inside its fuller projection (tree + operators + gloss) |
| **every rule's dependencies at once** | `dmtool -m <model> export …` | a `referencedFields` array per rule across the whole model |
| **valid error fields for a candidate** (G4) | `dmtool -m <model> rule check --field … --condition … --code … --suggest-error-field` | `data: {field, candidates:[…], fieldIsCandidate}` — the referenced fields inside the condition's iteration scope, the legal error-field picks (the `MVK_ERROR_FIELD_NOT_REFERENCED` law, up front). Same `getDirectlyReferencedEntities` source as `rule deps`, filtered to the iteration scope; the candidate is bound *leniently* so a bad error field still yields the corrective list |

The same primitive (`getDirectlyReferencedEntities`) backs all of these — `where-used` is literally its inverse, `explain`'s `references` (and `rule deps`) its forward projection, the error-field candidates its scope-filtered forward projection, and the safe-delete gate its membership test — so they can never disagree about what "references" means. (Adapter API equivalents for library callers: `whereUsed` / `whereUsedInComputations`, `referencedFields` / `referencedFieldsOfComputation`.) Locks: `cli.WhereUsedVerbTest`, `adapter.WhereUsedInComputationsTest`, `cli.RemoveVerbTest`, `adapter.ReferenceRewriteTest`.

## 7. Batching — dispatcher `batch` vs session `apply`

Two batch verbs, because they solve different problems — conflating them would break the eval runner:

| verb | op shape | model | semantics | for |
|---|---|---|---|---|
| **`batch`** | `{verb, args:[…], id?}` — the verb + its CLI argv | many ops, **any verb** (incl. refactors), possibly **different models** | stateless dispatch — each op is a full `Cli.run`; never rolls back | independent checks/reads/refactors; **the N6 eval runner** ([`eval/benchmark.workflow.js`](../eval/benchmark.workflow.js)) |
| **`apply`** | `{target, op, …args}` — the op-record | one model, ordered edits | one read → ordered surgery on one `EditSession` → **one terminal gate** → one write; **atomic rollback** | the north-star: read → add fields/groups → add/modify rules → **rename/move** → delete → write back |

**`apply` semantics.** It tolerates **transient-invalid states between ops** (defers the kernel gate to the end) and emits a **per-op result envelope** plus a batch summary (`{ops, committed, failedAt, written, results[]}`); a read op's payload rides its envelope's `data` (§3b). On a mid-sequence failure it **rolls back** (nothing written), the failing op's diagnostic cross-op aware (*"op f1 added /Order/Discount — fix the spelling"*). Rule ops carry **raw DSL/spec text** (the surgery path), so a transient-invalid model doesn't block authoring the fix; clean per-op attribution comes from validating per-op while the model is currently valid.

**Refactors in `apply` — rename/move are op-records too.** `apply`'s op-set is the **Local** class (add · read · modify · remove + config) **plus the reference-rewriting Refactor ops** `field`/`group rename·move`, `rule rename`, and `typedef rename` (§6). Each refactor op runs its own §6 safety gate **at its turn, against the live (transiently-edited) session** — so it sees prior ops' effects (e.g. moving a field into a group an earlier op just added) — then rewrites references via the kernel's `MoveSupportDM` and re-homes the result into the session; the change rides the one terminal gate + atomic rollback like every other op. A **refused** refactor (a name collision, a missing destination, move-into-self) becomes a mid-sequence failure → the whole sequence rolls back with the gate's corrective diagnostic in the failing op's `refused` envelope. The same `StructureRefactor` definition backs both the standalone verb and the apply op, so the gate can't drift. **Still standalone-only:** `extract`/`inline` — they produce/consume a *second* artifact (an include file), which apply's one-model-one-write shape doesn't represent; an `apply` op-record naming one is rejected with `RK_UNKNOWN_OP` (which lists the ops `apply` *does* support). Decision rule: **rename/move + edits on one model, transactionally → `apply`**; **a cheap fan-out of independent verbs, or `extract`/`inline` → `batch`/standalone**.

**Writing a batch without prior knowledge — by composition.** A batch op *is* a CLI call as JSON, so discovering a verb (manifest + `schema <target> <op>`, §5a) *is* discovering how to batch it. `schema apply`/`schema batch` add only the list *frame* + a worked `examples` block; the per-op fields are the verb's params, copied from the manifest. The op schema is intentionally loose (`additionalProperties:true` — inlining every op's args would need an unreadable `oneOf`), so **validation moves to the runtime, and stays corrective:**

- **Op-arg validation.** `apply`/`batch` validate each op's keys against that verb's param model and emit a corrective diagnostic in that op's envelope — `RK_UNKNOWN_ARG`, `where.arg`, a did-you-mean `fix`. The most common batch mistake (a typo'd/wrong key, or an `op` not valid for `target`) self-corrects instead of failing opaquely. This is the point: corrective diagnostics must reach *inside* batches, not just standalone calls.
- **Cross-type corrective.** Feeding `batch` an `apply`-shaped op (or vice versa) is detected and named (*"this op has `target`/`op` — did you mean `apply`?"*).
- **Examples can't rot.** Every schema `examples` block is CI-executed through the tool (a drift guard alongside the manifest/schema guardians) — a stale example actively misleads an agent, so an unverified one cannot ship.

**Implementation status.** `batch` is the long-standing dispatcher. `apply` is **built and feature-complete**: ordered surgery on one `EditSession` → one terminal gate → one write, atomic rollback, per-op envelopes (edits `staged`, reads `read`+`data`) + the `{ops, committed, failedAt, written, results[]}` summary. Ops cover **field/group/typedef/include add·remove, config modify, rule/computation add·modify, the reference-rewriting refactors (field/group rename·move, rule rename, typedef rename), and the structure reads (field/group/typedef/include/config)** — a read op mid-sequence returns the live session state in its `data` (unified read+edit); a refactor op runs its §6 gate against the live session, rewrites references, and re-homes the result, deferring to the one terminal gate. Op-arg validation (`RK_UNKNOWN_ARG`/`RK_UNKNOWN_OP` + did-you-mean), the cross-type corrective, and `schema apply`/`schema batch` (CI-executed examples) all land; dispatch is an exhaustive `switch` over the `Op` enum (no string drift). **The one niche extension left:** rule/computation/model *reads* inside `apply` (they'd bind the live model via the adapter per read — low value, since reads are normally a standalone call). (`extract`/`inline` stay standalone — multi-artifact.)

## 8. Module shape

- **`:cli` Gradle subproject**, `application` plugin, **builds the `dmtool` binary** (`applicationName = "dmtool"`; the gradle module stays `:cli`). Depends on **core + adapter + the kits**; the adapter pulls the internal kernel read tier — fine for an *app* (N8 confines the internal tier to adapter/cli, never core).
- **picocli** drives the command tree (§2): `RootCommand` (`name = "dmtool"`) + a target group per kind + one `@Command` op per cell, each a `Callable<Integer>` extending `BaseCommand` (the `out`/`err` seam). `picocli-codegen` auto-emits the GraalVM native-image reflection config.
- **Testability split — the fast loop:** `Cli.run(String[] args, Appendable out, Appendable err) -> int` is **pure and testable** (no `System.exit`, returns an exit code, writes to passed sinks); `CliMain.main` is the thin `System.exit(Cli.run(…))` shell. Every op is unit-testable in-process — no packaging, no spawned process.

## 9. Dev/test workflow — keep packaging out of the inner loop

The self-contained binary (jlink/jpackage) only *provisions a JVM* — zero behavior, ~a minute to build — so it is **never** in the code-build-test cycle. Three speeds:

| Changing | Command | Speed |
|---|---|---|
| **CLI/verb code** | `./gradlew :cli:test` — JUnit over `Cli.run(...)` | sub-second (warm daemon); the primary loop |
| code, want a real process | `./gradlew -q :cli:run --args="-m model.json model validate"` | seconds (incremental; skips packaging) |
| **only inputs** (model/spec JSON) | `./gradlew :cli:installDist` once, then `cli/build/install/dmtool/bin/dmtool …` (or the `bin/dmtool` shim) | ~1s JVM startup, no rebuild |

Rules of the loop: never `clean`; never `installDist`/jlink/jpackage to test *behavior* (only to produce a distributable). Point an agent dry-run at the one-time `installDist` launcher. *Future option (if multi-call latency bites):* a resident/stdin mode (JVM startup paid once across many calls).

## 10. Sizing

- **Application jars: ~18.5 MB** (51 jars — kernel subset + Jackson + commons + antlr4-runtime + Groovy; Groovy ~7–8 MB).
- **jlink-trimmed JRE: ~45–70 MB**; jpackage launcher +1–3 MB. **Self-contained app-image: ~65–90 MB/OS; compressed download ~30–50 MB.**
- **Shrink levers:** drop kernel jars the verbs never exercise (verify with `jdeps`/coverage — Groovy + `kernel-core-runtime`/codegen may be unused; biggest win); `jlink --compress=zip-9 --strip-debug …`; ship per-OS only → could approach ~25–35 MB.

## 11. Packaging phases & licensing

- **Phase A — dev/test:** `application` plugin → `run`/`installDist`/`distZip`. Dev JDK + kernel from `mavenLocal`. CI-testable; redistributes nothing.
- **Phase B1 — distribution, default: jlink + jpackage app-image.** Self-contained, signed; real JVM (no closed-world issues). `jdeps`-measured JDK modules → ~50–65 MB runtime + the classpath jars. Adds no behavior.
- **Phase B2 — GraalVM native-image: harder than the original spike suggested; its own topic now.** The detailed recipe, findings, sizing, and status live in **[`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md)**. In brief: native needs Groovy excluded (analysis), resources bundled, and broad reflection registration; reflection coverage is **incomplete**, and the **hard ceiling** bites concretely — the runtime-eval verbs (`model eval`/`rule test`/`model compute`) execute kernel runtime validation via `GroovyClassLoader.parseClass` (not native-compilable; root cause: [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) "`checkConsistency` runs runtime code generation"), so they **cannot go native**. The earlier "byte-identical once template resources are bundled" claim was incomplete. **Whether B2 ships before B1 is therefore reopened** — see [`RELEASE-PLAN.md`](RELEASE-PLAN.md) §8 and [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §8.

**JPMS verdict:** use **jlink** (runtime trimming) — yes; full app modularization — no (53 non-modular jars with near-certain split packages, ~zero payoff). **Licensing** (settled — [`LICENSING.md`](LICENSING.md)): source is **MIT**; the bundled distributable embeds the kernel and ships under **EUPL-1.2** (carrying the kernel LICENSE/NOTICE/THIRD_PARTY + source pointer, **no "mgm A12" branding** — which the `dmtool` name respects).
