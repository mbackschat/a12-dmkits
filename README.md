# a12-dmkits

Author, read, and safely edit the **rules** and **structure** of A12 Kernel document models — through a self-describing command-line tool (**`dmtool`**), or as typed Java code. What you author is confirmed by the **real A12 kernel**, so it is *accepted*, not merely plausible; what you run — which rules fire on a document — is evaluated by a clean-room, kernel-exact **interpreter** (JVM + JS). Models also import from and export to **JSON Schema / OpenAPI**. Rich specs and result envelopes are JSON; other inputs use flags, and artifact/catalog modes explicitly declare JSON, text, or empty stdout.

> **Not an official A12 artifact.** A personal exploration, not affiliated with or endorsed by mgm — currently a private project, built using GPT 5.5. Shipped code binds only to the kernel's **public** OSS surface — no internal systems. (An opt-in, Java-only adapter module may read the kernel's internal AST tier under strict guardrails.)

**What's in the box** — one line each; the sections below carry the detail:

- **rulekit** — validation rules & computations as typed, model-bound Java code: author, read back, re-express
- **modelkit** — the model's **structure**, the same way: fields, groups, type definitions, includes, document config
- **dm.edit** — `EditSession`: structure + rule edits on one in-memory model, behind a single kernel gate
- **schemakit** — JSON Schema / OpenAPI ⇄ document model, import and export
- **the interpreter** — **[PUBLIC RELEASE REPOSITORY →](https://github.com/mbackschat/a12-interpreter-releases)** — kernel-free runtime evaluation (validate / compute); one engine for the JVM, native binary, Node.js, and browser, with a production browser JavaScript entry of about 260 kB gzip
- **the portable checker** (`a12-consistency`) — kernel-free *consistency* checking: would A12 reject this model? One Kotlin Multiplatform implementation for the JVM, Node and the browser, answering `INVALID` or `INCONCLUSIVE` — and, deliberately, never `VALID`
- **`dmtool`** — **[PUBLIC RELEASE REPOSITORY →](https://github.com/mbackschat/a12-dmtool-releases)** — the CLI over all of it: self-describing, machine-readable by default, explicitly profiled artifacts, agent-first

<p align="center">
  <a href="assets/infographics/a12-dmkits-toolchain.avif">
    <img src="assets/infographics/a12-dmkits-toolchain.avif" alt="A12 dmkits toolchain from typed authoring through Kernel acceptance to runtime evaluation" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

---

<!-- shared:dmtool-story — canonical HERE; mirrored verbatim into plugin/README.md (guarded by SharedReadmeRegionTest). Keep it link-light: NO docs/ links (the public mirror has no docs/ tree). -->
## Why `dmtool`

An A12 document model carries **validation rules** and **computations** whose logic is a condition in the kernel's expression language — *"a delivery date must not be before the order date"* becomes a string like:

```
AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0
```

Writing these by hand is fragile: a mistyped path, a wrong operator or decimal scale, or — most insidious — **inverted polarity** (the condition true on the *requirement* instead of the *violation*) slips through until the kernel runs, if it's caught at all.

**`dmtool` closes that gap from the command line** — one self-describing native binary, machine-readable by default with explicitly profiled JSON/text artifacts, no Java toolchain. It authors, reads, and safely edits a model's rules *and* structure — **every edit is confirmed by the embedded real A12 kernel before it is written**, so *valid* means the engine accepts it, not that it merely looks right — and it runs rules against real documents on a **clean-room interpreter** held kernel-exact by a differential test estate and a tri-verified conformance corpus.

It is built **agent-first** — the #1 use is inside a coding-agent session (Claude Code, Codex):

- **Self-describing — a cold agent needs no external docs.** `manifest` lists every verb × parameter with a worked example; `operators` / `patterns` / `diagnostics` / `schema` expose the DSL vocabulary, the validation idioms, each diagnostic code, and the exact I/O shape — all from the binary itself.
- **Judgment where `--help` can't reach.** A bundled skill teaches what the catalog can't — condition *polarity*, error-field paths, iteration scope — so a scaffolded rule has the right shape before the kernel ever sees it.
- **Safe by construction.** Envelope-producing operations share one standard result shape, while raw artifact/catalog and text-view modes are explicit in the operational profile; deletes and structural refactors are **gated** (a referenced field won't silently vanish); a multi-op `apply` session is **atomic** (rollback on any failure).

<p align="center">
  <a href="assets/infographics/dmtool-model-workbench.avif">
    <img src="assets/infographics/dmtool-model-workbench.avif" alt="dmtool DocumentModel workbench for discovery, schema exchange, understanding, authoring, review, and runtime automation" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

### A session, in natural language

You don't type `dmtool` — your agent does. You state the rule in plain language; the agent resolves the model, reasons about **polarity** and **operator semantics**, and the **real kernel** confirms the authored rule before anything is written:

**You —** *"In the order model, a delivery date must never be before the order date."*

**Agent —** *finds the fields, then checks the operator's sign convention:*

```sh
dmtool -m order.dm.json model describe | jq -c '.data.fields[] | select(.kind=="DATE") | .path'
# → "/Order/OrderDate"   "/Order/DeliveryDate"
dmtool operators DifferenceInDays          # its meaning, sign convention, gotchas, and OPPOSITE
```

*An A12 rule fires on the **violation**, so the condition must be true when delivery is before order — and that gets validated against the real kernel before anything is written:*

```sh
dmtool -m order.dm.json rule check --field /Order/DeliveryDate --code DELIVERY_BEFORE_ORDER \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0'
# → { "valid": true, "diagnostics": [] }   ✓ accepted — polarity lint clean, error field legal & in scope
dmtool -m order.dm.json rule add delivery-rule.json     # persist (kernel-checked again on write)
```

**Done** — the rule is written and re-validated: true *exactly* when delivery precedes the order (the violation), with a referenced, in-scope error field. The agent didn't guess: had it written the condition true on the *requirement*, the built-in **polarity lint** would have flagged it; had it invented a field path or misread the operator, the kernel would have rejected it. That is the point — **deep rule + operator semantics, gated by the engine itself**, reachable from one English sentence.

### The skill & the plugin

Two pieces make an agent this good with `dmtool`:

- **The plugin** delivers the binary — a bundled installer the skill runs **on demand** (the first time `dmtool` is needed, in that same session) fetches the right per-OS native build (anonymous, checksum-verified). One install line (below); the same plugin model serves Claude Code and Codex.
- **The skill** is a small `SKILL.md` of *judgment* — the traps `--help` can't teach: rule **polarity**, valid **error fields**, **iteration** scope, the date/number gotchas. The binary stays self-describing (`manifest` / `operators` / `schema`); the skill teaches *when* and *why*, and the same canonical skill backs both agents.

### 🔌 The same tool over MCP — for a host that cannot run a binary

Some callers can't spawn a subprocess at all: a web client, a hosted agent platform, a sandboxed IDE integration. For those, **the same binary is a Model Context Protocol server** (revision `2026-07-28`) — two transports, one implementation:

```sh
dmtool mcp                                       # stdin/stdout, for a host that launches the binary
dmtool mcp --http --host 0.0.0.0 --port 8080     # a bound port — upload it, start it, hand out the URL
```

The whole verb surface arrives as tools, **derived from the same command tree the CLI describes itself from** — so the two surfaces can never describe the tool differently, and a verb added to the CLI appears here by itself. Each tool carries the facts a host can act on: a read tool is annotated read-only, so allowlisting `dm.rule.read` cannot reach `rule remove` — a guarantee granting shell access can never give you.

**Every call carries its own content.** Send the model's DM-JSON, get the edited DM-JSON back. The server accepts **no filesystem path**, holds no model between calls, and deletes everything a call creates before it answers — so two callers cannot reach each other's work, by construction rather than by policy.

> **Where you *can* run the binary, run it.** The CLI edits models in place; over MCP you pay a content round trip in each direction. MCP is the answer to "my host cannot execute anything", not a local convenience — and the verb's own `--help` says so.
<!-- /shared:dmtool-story -->

Prefer typed Java in your own JVM project? The same engine is a library — see *The idea* and the rulekit / modelkit sections below.

---

## Three ways to use it

| You are… | Use | Why |
|---|---|---|
| a **modeller / BA**, or an **LLM agent** | **`dmtool`**, the CLI | verbs + profiled JSON/text I/O, single native binary, self-describing — no Java toolchain to install |
| a **developer**, editing models in your own JVM project | **rulekit / modelkit** as a library | typed builders, kernel-checked, in your own code & compiler |
| a **host that cannot execute a binary** — a web client, a hosted agent platform, a sandboxed integration | **`dmtool mcp`**, the MCP server ([contract](docs/MCP-SPEC.md)) | the same verbs as tools derived from the same command tree; every call carries its content both ways and the server keeps nothing |
| a **TypeScript / Node / browser developer** who needs model *checking* with no kernel, server or licence | **`a12-consistency`**, the portable checker ([contract](docs/CONSISTENCY-API.md)) | one Kotlin Multiplatform implementation, strings in and out, nothing thrown; it reports `INVALID` or `INCONCLUSIVE` with its coverage evidence and never claims `VALID` |

*(The kits — rulekit, modelkit, dm.edit, schemakit — are a **Java** library. The **interpreter** and the **portable checker** are Kotlin Multiplatform, and both packed libraries are tested on JVM, Node.js, and Chrome.)*

## Why Kotlin, GraalVM, and the A12 Kernel

The three technologies have separate jobs. Kotlin Multiplatform carries one portable runtime-evaluation engine across JVM and JavaScript. GraalVM turns the JVM application stack into the self-contained per-OS `dmtool` executable. The real A12 Kernel remains the authority for model acceptance, canonical read-back, safe rewrites, and the differential evidence that keeps the clean-room Interpreter aligned.

<p align="center">
  <a href="assets/infographics/kotlin-graalvm-kernel-roles.avif">
    <img src="assets/infographics/kotlin-graalvm-kernel-roles.avif" alt="How Kotlin Multiplatform, GraalVM Native Image, and the A12 Kernel divide responsibilities across a12-dmkits" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

---

## `dmtool` — the CLI

Same engine, no Java required. Model-bound operations use `dmtool -m <model> <target> <op> [args]`; root, catalog, and artifact operations expose their own syntax. Each matching manifest operational profile declares whether effective stdout is the standard JSON **result envelope** (`{ok, valid, outcome, data, diagnostics[], …}`), raw JSON/text, or empty after an artifact write. It is **self-describing**, so a cold agent (or you) can discover the whole surface from the binary alone.

### Install — see the release repo (single source of truth)

`dmtool` is distributed from the public release repo — **[`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases)**, which holds the **single source of install truth**. Both paths are documented there in full: the **agent plugin** (Claude Code / Codex — bundles the binary + the canonical skill, *including the post-install activation step*) and the **raw per-OS binary** (+ `SHA256SUMS`) for plain terminal use. (This source repo holds the plugin *source* under [`plugin/`](plugin/) (Claude Code) and [`plugin-codex/`](plugin-codex/) (Codex); they ship to the mirror at release.)

The binary is **EUPL-1.2** (it embeds the A12 Kernel — see the mirror's `LICENSE`/`NOTICE`); Homebrew/Scoop come later. The runtime-evaluation verbs (`model eval`, `rule eval`, `model compute`, `model seed`) run on a **native-safe interpreter** on every target — the sole runtime eval engine — so they work in the native binary too.

### Serve it over MCP

For a caller that cannot execute the binary, the same executable speaks the **Model Context Protocol**, revision `2026-07-28`, over stdin/stdout. Register it with a host that reads a project `.mcp.json`:

```json
{ "mcpServers": { "dmtool": { "command": "dmtool", "args": ["mcp"] } } }
```

**Or host it.** For a caller that reaches the tool over the network rather than launching it, the same binary binds a port and serves Streamable HTTP — upload it, start it, hand out the URL:

```sh
dmtool mcp --http --host 0.0.0.0 --port 8080     # POST https://your-host/mcp
```

It binds loopback and allows no browser origin unless told otherwise, refuses a body over its cap, and answers with the protocol's own status codes (`400` on a header/body mismatch, `404` on an unknown method, `403` on an unlisted `Origin`). Several callers are served at once by a **bounded** pool — past `--workers` × `--queue-depth` a caller gets an immediate `503` with `Retry-After`, because this protocol revision dropped stream resumability and a deep queue would turn one slow call into a retry storm.

Over stdio you can also drive it directly — one newline-delimited JSON-RPC message per line:

```sh
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}' | dmtool mcp
```

The tools are **derived** from the live command tree plus the operational contract, so `readOnlyHint` and `outputSchema` are true of every op a tool covers or are not claimed at all. Content crosses the wire in both directions — no path is accepted, nothing is stored, and a call's working tree is deleted before it answers. A few verbs cannot answer honestly from a fragment (the `workspace` family, `model rename`): they report over a whole directory, or their refusal must prove a sibling's *absence*. Rather than hide them, the surface runs them **only** under `authoritativeWorkspace: true`, by which the caller takes responsibility for having sent a complete workspace.

Worked session: [`examples/cli-mcp.md`](examples/cli-mcp.md). Full contract, the refusals, and what is deliberately not built: [`docs/MCP-SPEC.md`](docs/MCP-SPEC.md) + [`docs/MCP-GAPS.md`](docs/MCP-GAPS.md). Standing up a hosted endpoint for real: [`docs/MCP-HOSTING.md`](docs/MCP-HOSTING.md).

### Discover the tool itself

```sh
dmtool manifest                       # every verb × parameter + a worked example per verb, in one call
dmtool operators                      # the DSL operator catalogue …
dmtool operators GroupFilled          #   … or one operator in full (meaning, examples, gotchas, OPPOSITE)
dmtool patterns                       # the validation-idiom catalogue (date-order, required-when, …) …
dmtool patterns date-order            #   … or one idiom in full (params, polarity, skeleton, example)
dmtool diagnostics                    # the diagnostic-code catalogue …
dmtool diagnostics RK_NO_SUCH_FIELD   #   … or one code in full (meaning + fix) — so a `code` you got back is explorable
dmtool schema rule add                # the exact JSON shape a verb consumes / returns
dmtool --version                      # this build + the kernel it targets (see "compatibility" below)
```

### Start a model from scratch

```sh
# a new, empty, kernel-valid model
dmtool model new --id orders --locale en_US --root Order > orders.dm.json

# …then populate it
dmtool -m orders.dm.json field add --group /Order --name Reference --kind STRING

# re-type a field in place (e.g. add a STRING pattern) — no delete-and-recreate
dmtool -m orders.dm.json field modify postal.spec.json
```

`model new` is the primary model-*originating* verb (no `-m` — it makes one); every other verb edits an existing `-m` model. What it emits is kernel-checked, so the next `-m` edit binds.

### JSON Schema & OpenAPI

Originate a model **from a JSON Schema or an OpenAPI document**, and export one back out — the same two verbs handle both, because OpenAPI's "Schema Object" *is* a dialect of JSON Schema:

<p align="center">
  <a href="assets/infographics/json-schema-document-model.avif">
    <img src="assets/infographics/json-schema-document-model.avif" alt="How schemakit maps JSON Schema and OpenAPI to and from A12 DocumentModels with explicit fidelity" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

```sh
# JSON Schema → kernel-valid model
dmtool model import-jsonschema --schema order.schema.json > order.dm.json

# OpenAPI 3.0 / 3.1 / 3.2 → model (auto-detects the dialect; pick the components/schemas entry)
dmtool model import-jsonschema \
  --schema petstore.openapi.json \
  --component Order \
  -o order.dm.json

# …or import the WHOLE OpenAPI document as a bundle — one model per component, wired by includes
dmtool model import-jsonschema \
  --schema petstore.openapi.json \
  --out-dir ./models/

# model → JSON Schema (best-effort)
dmtool -m order.dm.json model export-jsonschema > order.schema.json

# model → a drop-in OpenAPI components/schemas envelope
dmtool -m order.dm.json model export-jsonschema \
  --dialect OPENAPI_31 --wrap-openapi > order.openapi.json
```

- **`--dialect`** (`AUTO` · `JSON_SCHEMA` · `OPENAPI_30` · `OPENAPI_31`) reads/writes the right encoding — OpenAPI 3.0's `nullable:true` and boolean exclusive bounds, `components/schemas` `$ref`s, the explicit `discriminator`. `AUTO` detects it from the `openapi:` version field.
- **`--component`** picks the OpenAPI bundle's entry-point schema on import; **`--out-dir`** imports the *whole* bundle (one model per component, object cross-refs wired as mounts); **`--wrap-openapi`** emits the OpenAPI envelope on export.
- Import is **best-effort by default** — it maps as much as possible (even unbounded arrays and recursive `$ref`s, so nested structure isn't lost) and *flags every guess* in the stderr report; nothing is silent or blocked. `--strict` flips to omit-everything-uncertain; per-decision flags (`--array-of-scalar`, `--union`, `--number-scale`, …) tune each choice.
- Constraints with no field-config home become real a12 **rules** — `required`/`enum`/`pattern`, exclusive bounds, `multipleOf`, `const`, `dependentRequired`, `uniqueItems`, `oneOf`/`anyOf` discriminators, `if`/`then`/`else` (incl. multi-property), `not`, and `contains`/`minContains` — each kernel-checked (the full table is in the spec).

Array lower bounds require unconditional root-property applicability; otherwise import reports an omission and recommends keeping that constraint in JSON Schema validation. Empty-pattern composition is simplified without generating an executable empty A12 pattern. Object `contains` predicates preserve missing-property matches where supported and report ambiguous absent/empty string cases as omissions.

The dialects, the full mapping contract, and the import↔export asymmetry are tabulated in [docs/SCHEMAKIT-SPEC.md §4d/§4g](docs/SCHEMAKIT-SPEC.md).

### Read & understand a model

```sh
# the header dashboard: identity (`id`, and whether the `fileName` A12 Tools wants it to match does),
# super/subtypes, includes/imports (resolved to files), counts
dmtool -m order.dm.json model info

# the model card: fields, kinds, enum domains, groups
dmtool -m order.dm.json model describe

# run it through the real kernel → {valid, diagnostics[]}; malformed input is refused (exit 2)
dmtool -m order.dm.json model check

# the same question on the kernel-free portable checker, which answers a NARROWER one: it can reject a
# model, or report that it concluded nothing (exit 3) — it never calls a model valid. The engine you ask
# for is the engine that answers; neither one ever falls back to the other.
dmtool -m order.dm.json model check --engine portable

# one field in full: kind, required, AND its per-kind config (min/max/unit/pattern/format)
# — verify a constraint, never read raw JSON
dmtool -m order.dm.json field read /Order/Quantity

# a rule's condition, as typed JSON
dmtool -m order.dm.json rule read /Order/DeliveryNotBeforeOrder

# what it checks + WHEN it fires
dmtool -m order.dm.json rule explain /Order/DeliveryNotBeforeOrder

# its forward deps: the fields it references (+ iteration)
dmtool -m order.dm.json rule deps /Order/DeliveryNotBeforeOrder

# the kernel's canonical text, switched EN↔DE
dmtool -m order.dm.json rule format /Order/DeliveryNotBeforeOrder --lang DE

# the inverse: which rules/computations use a field
dmtool -m order.dm.json where-used /Order/DeliveryDate

# whole-model audit: every field's readers + the unreferenced set
# (a field reached only through an ancestor GROUP operand counts as read)
dmtool -m order.dm.json model usage

# the comprehension dashboard: identity + structure outline + the dead-field set
# + a GLOSSED rules/computations catalog (each in plain language, next to its stored message)
dmtool -m order.dm.json model report

# an agent-first flat subtree (JSON) or the same records drawn for a human
dmtool -m order.dm.json model report --tree --root /Order --depth 2
dmtool --text -m order.dm.json model report --tree --root /Order --include rules,computations
```

`model report` is the *understand-the-whole-model* companion to `model diff` below: where the others read one facet, it assembles the lot — and renders every rule/computation in **plain language** (a tool-computed gloss) next to its **stored message**, so you can see what a rule *does* against what it *claims*. `--tree` keeps JSON agent-friendly as one document-ordered `nodes[]` list with paths, depths, types, repeatability, and provenance; add `--include rules,computations,descriptions,annotations,enum` for richer facets, while `--text` draws the same records with `├─/└─`.

### Review what changed — a semantic diff

When an agent has edited a model on your behalf, raw JSON diffs are unreadable. `model diff` compares two model files **structurally** (it parses and independently binds both, so a pure reformat is never reported) and reports field, group, rule, computation, typedef, authored include/import, and model-configuration changes with per-aspect deltas and a **review-impact tier**. Multi-model two-file diffs resolve each side from `-w` or its own folder; `--since` resolves the base model and dependencies at the Git ref, not from the edited worktree. Each delta keeps deterministic `before`/`after` strings and adds tagged `beforeValue`/`afterValue` scalars or string lists for typed consumers. Fields include complete typed config, requiredness, enum values, metadata, and flags; rules include their error entity, localized messages, and metadata. Removing or retyping a field still used by a rule/computation is HIGH and names `REFERENCED_ELEMENT_REMOVED`/`RETYPE_AFFECTS_READERS`. Groups cover repeatability, mounts, metadata, and mixed authored ordering; computations cover targets, preconditions, operations, tolerances, suppressions, and messages; typedefs carry full typed config and metadata; includes/imports carry their purpose and referenced model without conflating expanded content. The single `CONFIG model` element covers identity/version, locales, decimal/time-zone/language/short-name settings, supported characters, base year, labels, comment, annotations, and roles; set-like values are canonicalized so harmless ordering does not create noise. Review impact follows *loosening > tightening*: increasing a repetition cap, adding an enum value, expanding supported characters, or removing/loosening a rule is HIGH, while tightening is MEDIUM.

<p align="center">
  <a href="assets/infographics/semantic-model-diff.avif">
    <img src="assets/infographics/semantic-model-diff.avif" alt="How dmtool compares two model snapshots structurally, classifies semantic changes, and ranks review impact" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

When base and head contain different revisions of the same referenced model id, use repeatable `--base-workspace` and `--head-workspace` roots; shared `-w` remains the concise form for one common dependency snapshot.

```sh
dmtool model diff before.dm.json after.dm.json        # two files: base then head
dmtool model diff --base-workspace old --head-workspace new old/app.dm.json new/app.dm.json
dmtool -m order.dm.json model diff --since HEAD        # or: vs the model's last git-committed state
# → { "summary":"2 added, 1 removed, 1 modified (1 high-risk)",
#     "data": { "changes": [
#       { "element":"RULE", "id":"/Order/MinTotal", "change":"REMOVED", "risk":"HIGH",
#         "riskReasons":["RULE_REMOVED"], "deltas":[ … ] }, … ] } }
```

The sharpest case is a **silent polarity flip** — a rule that stays but now fires on the complementary set, accepting what it used to reject. `model diff` reads both conditions as typed ASTs and proves the flip structurally, grading it HIGH `POLARITY_INVERTED` (not a generic `CONDITION_CHANGED`) — the one edit a line-based diff buries.

It's a **structural** semantic diff; the contract (change model, the full review-impact taxonomy, the rendering policy) is in [docs/MODEL-REVIEW-SPEC.md](docs/MODEL-REVIEW-SPEC.md), open items in [docs/MODEL-REVIEW-GAPS.md](docs/MODEL-REVIEW-GAPS.md).

`dmtool` writes models **deterministically** (element/key order preserved — never reordered), so an edit yields a clean, *localized* git diff. To bring a foreign-authored model (e.g. exported from the A12 editor) into that canonical form once — as an explicit, reviewable commit rather than churn buried in the first edit — run:

```sh
dmtool -m order.dm.json model normalize        # formatting only — order preserved, no semantic change
```

To turn a raw multi-model workspace into one portable DM-JSON artifact, use the public interpreter assembler through dmtool:

```sh
dmtool -m app.dm.json -w ./models model expand > app-expanded.dm.json
dmtool -m app.dm.json -w ./models model expand -o app-expanded.dm.json
```

`model expand` resolves only the entry model's discovered include/type-definition closure, mounts includes, inlines every typedef use, normalizes ids/references, and refuses ambiguity or residue without writing a partial file. It is a conversion operation, so it stops before interpreter-only evaluator checks: a Kernel-legal model that dmtool cannot evaluate can still be expanded.

### Explore a whole folder of models

When you're dropped into a *directory* of models — not one file — `workspace list` is the agent-friendly "ls" that goes *into* them: a per-model index with the sub/supertype graph and every `include` / type-def import **resolved to its file**, so you know what to wire (which `-w/--workspace`, which file provides a needed type-def) without reading raw JSON.

```sh
dmtool workspace list ./models                       # the index as JSON (id, type, super/subtypes, resolved refs, counts)
dmtool workspace list ./models --recursive           # descend sub-folders (widens reference resolution)
dmtool workspace list ./models --format table        # the same facts as an aligned human listing
dmtool workspace list ./models --validate            # also flag which models are kernel-valid
dmtool workspace graph ./models                      # the sub/supertype inheritance hierarchy (nodes + edges)
dmtool workspace graph ./models --format tree        # …as an indented hierarchy (or --format dot for Graphviz)
dmtool workspace check ./models                   # the project GATE: kernel-validate every model, exit 1 if any invalid
dmtool workspace roles ./models                      # the access-control lint: resolve each model's `roles` vs roles.yaml
```

`workspace check` is the **gate** form — it runs the real kernel over every model in the directory (references resolved across the tree), reports per-model `{valid, diagnostics[]}` (the same rich `MVK_*` diagnostics as `model check`), and **exits non-zero if any model is invalid**. That makes it a one-line pre-commit / CI check over a whole project:

```sh
dmtool workspace check ./models || exit 1         # fail the commit/build on any invalid model
```

(`workspace list --validate` differs: it only flags a per-model boolean; `workspace check` surfaces *why* and fails the build.)

`workspace roles` is a second lint, on **access control** — but **advisory, never a gate**: A12 models carry a `roles` header annotation naming who may access them, a workspace declares those roles in `auth/roles.yaml`, and its users (with the roles each holds) in `auth/users.yaml`. The lint resolves the whole **RBAC triangle** and flags an **undefined role** (a model gating to a role the file doesn't declare — something even the A12 model editor permits silently), an **undefined authority** (a user holding an undeclared role), an **unreachable model** (gated to roles no user holds), plus ungated models, a missing/duplicate roles file, and orphan roles. Every finding is a **warning** (or a softer INFO) — never an error — and the verb **always exits 0**: access-control config is often a dev seed or owned by an external IdP, so the lint informs without ever blocking model work. Each finding carries a `fix` with the concrete remedy (see the [`cli-tour`](examples/cli-tour.md) walkthrough).

For multi-file models (one model `include`s or imports another *by id*), set the **workspace root once** so every verb resolves references with no per-call flag:

```sh
dmtool -w ./models -m app.dm.json model check     # -w/--workspace: the dir to resolve includes/imports from
export DMTOOL_WORKSPACE=./models                      # …or set it once per shell session (the env fallback)
```

Precedence: `-w/--workspace` > `$DMTOOL_WORKSPACE` > the model's own folder (see [CLI-SPEC §3a](docs/CLI-SPEC.md)). There's no separate `-w/--workspace` — one workspace concept resolves both includes and type-def imports.

### Scaffold from a validation pattern — polarity & error field baked in

A BA's recurring tasks — *required-when*, *date-order*, *mutually-exclusive*, *at-least-one-of*, *sum-of-line-items* — are exactly where an agent re-risks the two hardest **rule** traps every time: getting the **polarity** right (an A12 rule fires on the *violation*) and picking a **valid error field** (it must be referenced + in scope, else `MVK_ERROR_FIELD_NOT_REFERENCED`). The `patterns` verb removes the re-derivation: each idiom is a **typed-DSL-backed** template — correct by construction — that scaffolds a kernel-valid artifact from a few parameters. Beyond the rule idioms, **`tiered-amount`** is a **computation** idiom — it scaffolds a `kind:computation` artifact whose preconditions form a **mutually-exclusive, exhaustive table** (`< threshold` / `>= threshold`), the trap on the BA's *computation* side; and the **field-constraint** idioms (`bounded-number`, `formatted-string`, `value-set-enum`) scaffold the **field-level alternative** to a rule — the constraint lives in the field config (`number.min/max`, `string.pattern`, `enum.values`) the `seeAlso` bridge points at.

```sh
dmtool patterns                                  # list the idioms
dmtool patterns date-order                        # one idiom: its params, polarity note, skeleton, example

# Scaffold a rule-spec from an idiom and AUTO-CHECK it against the kernel (read-only):
dmtool -m order.dm.json patterns date-order \
  --arg earlier=/Order/OrderDate --arg later=/Order/DeliveryDate
# → { "pattern":"date-order",
#     "spec": { "field":"/Order/DeliveryDate",
#               "condition":"AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(…) < 0", … },
#     "valid": true, "diagnostics": [] }     ← right polarity + a referenced error field, ready for `rule add`

dmtool -m order.dm.json patterns date-order \
  --arg earlier=/Order/OrderDate --arg later=/Order/DeliveryDate --apply   # …or persist it directly (--dry-run previews)

# A COMPUTATION idiom — a mutually-exclusive, exhaustive precondition table (scaffolds a computation-spec):
dmtool -m order.dm.json patterns tiered-amount \
  --arg computed=/Order/BackorderQuantity --arg driver=/Order/Quantity \
  --arg threshold=10 --arg below=0 --arg atOrAbove=5      # --apply persists via `computation add`
```

The catalogue is **self-verifying**: every idiom's example is CI-instantiated and run through the real kernel, so a pattern that no longer scaffolds a valid rule fails the build. See [CLI-SPEC](docs/CLI-SPEC.md) and the [API guide](docs/API-GUIDE.md).

### Author & check — confirmed by the kernel

```sh
# Validate a candidate rule WITHOUT touching the model (returns {valid, diagnostics[]}):
dmtool -m order.dm.json rule check \
  --field /Order/DeliveryDate \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And [DeliveryDate] < [OrderDate]' \
  --code DELIVERY_BEFORE_ORDER

# …and let the tool name the LEGAL error fields up front (the condition's referenced fields, in scope):
dmtool -m order.dm.json rule check --field /Order/DeliveryDate \
  --condition '…' --code DELIVERY_BEFORE_ORDER --suggest-error-field   # → data.candidates[]

# …and check the ERROR TEXT too — the kernel's second checker, read-only (omitted locales keep a placeholder):
dmtool -m order.dm.json rule check --field /Order/DeliveryDate \
  --condition '…' --code DELIVERY_BEFORE_ORDER \
  --message 'en_US=Delivery $DeliveryDate.value$ precedes the order date.'

# …or ask the kernel-free portable checker instead, which judges the candidate by what it INTRODUCES:
# data.introduced / data.resolved, `verification: PORTABLE_CHECKED`, and exit 3 when it concludes nothing.
dmtool -m order.dm.json rule check --engine portable --field /Order/DeliveryDate \
  --condition '…' --code DELIVERY_BEFORE_ORDER

dmtool -m order.dm.json rule add <rule-spec.json>          # persist it (kernel-checked before + after)
dmtool -m sub.dm.json   computation add <comp-spec.json> --dry-run  # static preflight, writes nothing
dmtool -m sub.dm.json   computation add <comp-spec.json>   # persist the same kernel-accepted computation
```

For intentionally scale-unknown division or field-exponent power, the computation spec uses A12's property unchanged: `"errorCodesToSuppress":["MVK_INVALID_COMPARE_DEC_PLACES"]`. Discover the complete, target-specific shape with `dmtool schema computation add`; rule-only keys are rejected on computations and computation-only keys are rejected on rules.

Diagnostics are **corrective**: each carries a stable `code`, a `where`, and a `fix` — and a built-in lint flags the classic **inverted-polarity** mistake automatically (and, on `computation add`, forbidden constructs + overlapping preconditions).

Adding many rules or edits in one go? **`apply`** (below) is the bulk path: one model load, ordered ops, one terminal kernel gate, one write, atomic rollback. `batch` merely dispatches independent calls: it continues after a child failure and never rolls back an earlier successful write. In ordinary mode, outer `ok` and exit 0/1 follow `data.allExitedZero`. For a replayable static-kernel verdict matrix, give every supported child an `id` and add `batch --observations <file>`: after full dispatch plus artifact write, observation mode returns outer `ok:true` and exit 0 independently of `allExitedZero`. It overwrites one deterministic, diagnostic-code-only artifact whose exact shape is served by `schema batch`.

### Run the rules against real data (not just static checks)

```sh
# which rules actually FIRE on this document
dmtool -m order.dm.json model eval --instance order-instance.json

# one rule: fired / passed / suppressed
dmtool -m order.dm.json rule eval /Order/DeliveryNotBeforeOrder --instance order-instance.json

# one NON-STORED candidate: the same focused verdict, no model write
dmtool -m order.dm.json rule eval --condition '[Quantity] > 100' --field /Order/Quantity --instance order-instance.json

# what each computed field evaluates to
dmtool -m sub.dm.json model compute --instance sub-instance.json

# validate STORED values as-is (bare validateFull)
dmtool -m order.dm.json model eval --instance order-instance.json --no-computations

# generate a best-effort model-derived sample candidate (feeds the verbs above)
dmtool -m order.dm.json model seed --rows /Order/Items:2

# validate a custom field type
dmtool -m pay.dm.json model eval --instance pay.json --predefined-types iban.json
```

The `--instance` document is **A12's own canonical Document JSON** — the shape the A12 kernel's document serializer reads and writes, so a document a real A12 application produced drops straight in. A root group name is a top-level key (no wrapper), a repeating group is an array whose index is the repetition, a number/boolean is native JSON, and `null` is a field that is present with no value. `model seed` writes that same shape through the same codec, so a generated sample round-trips back into the verbs above by construction. `dmtool schema model eval` serves the contract.

This is the empirical check a static "is it valid?" can't give — it's how you confirm a rule's *polarity* on concrete data. `rule eval` scopes it to one rule with a three-way verdict, distinguishing a genuine **pass** from a rule the engine **suppressed** because a field it references is formally invalid. Its locator has two exclusive forms: a stored `<rulePathInModel>`, or a non-stored candidate supplied as `--condition` plus absolute `--field` (optional `--code`); candidate evaluation uses the same focused envelope and leaves the model byte-identical. A **firing** additionally reports that message's own channels — `code`, `field`, `severity`, `type`, `message`, `referenced` (and `fillToFix` for an omission) — the same keys `model eval` puts on a `messages[]` entry, so the message `type` (`VALUE_ERROR` / `OMISSION_ERROR`) is observable without persisting the candidate first: `dmtool schema rule eval` serves the shape. `model eval --relevant '["/Order/Name","/Order/Lines[2]/Quantity"]'` selects partial/wizard-page validation; `[*]` (or `[0]`) means every repetition and `[n]` selects one row; an **omitted** index on a repeatable segment is refused rather than guessed, because that spelling means repetition 1 in A12 and reading it as "all" would silently widen the check (a rule over a *whole-repetition* aggregate or value-list is fully known only when that level is wildcarded, not listed row-by-row), and global fields are added automatically. `model eval` / `rule eval` run the **form-engine flow by default** — *compute → apply → validate* — so a required-or-referenced **computed** field is filled by its computation before validation, matching the running app (the kernel keeps `validateFull` and `compute` separate, so a bare validate would false-fail a computed field); `--no-computations` opts down to the bare `validateFull` over stored values. These verbs run on a **native-safe interpreter** on every target — the sole runtime eval engine — reproducing the kernel's runtime semantics, verified rule-for-rule (the A12 kernel remains the differential oracle behind that verification).

`model eval` preserves message occurrences: a numeric parallel join may visit the same matched rows twice when its index declarations render one value differently (`5` versus `5.00`). `data.messages` retains both occurrences; `data.fired` lists distinct codes. The [runtime tour](examples/cli-runtime.md) demonstrates this distinction.

`FirstFilledValue` retains missing nested prefixes: an omitted inner group before the selected value can make a firing an OMISSION error, while the same empty branch after it preserves VALUE. The [runtime tour](examples/cli-runtime.md) shows both documents.

**Models that use custom constructs** still run: a `CustomFieldType` whose format is declarative is validated with `--predefined-types <file>`; for the *imperative* tail — a `CustomCondition`, or a checksum custom type — point `dmtool` at the project's own JS with `--custom-conditions-js` / `--custom-field-types-js` (run through a persistent Node worker); and anything still unhandled is **surfaced** under `data.unsupported` rather than silently passed, with `--strict-custom` turning that into a failure. `model compute` accepts the same custom type registries and checks reached computed targets; `--strict-custom` also refuses missing target handlers while retaining the result data. Walkthrough: [`examples/cli-custom-types.md`](examples/cli-custom-types.md).

> **The interpreter is kernel-exact *and* fast** — clean-room, no kernel code, verified rule-for-rule against the real kernel. The full story (library use on JVM + JS, the verification estate, the measured performance): [the interpreter section](#the-interpreter--kernel-free-runtime-evaluation) below.

### Calibrate with performance profiles

```sh
# envelope mode: inspect or pipe the identifier-free profile under .data
dmtool -m real.dm.json profile extract

# artifact mode: persist the bare current profile; stdout is an applied write receipt
dmtool -m real.dm.json profile extract -o target.profile.json

# synthesize a deterministic own-domain model with the target workload shape
dmtool profile synthesize --input target.profile.json --seed 42 -o calibration.dm.json

# compare every classified axis after synthesis and re-extraction
dmtool profile compare --target target.profile.json --actual actual.profile.json
```

The profile records the model shapes that drive evaluation cost — structure and repeatability, rule/computation work, and field wiring — without carrying domain identifiers, literals, messages, or source expressions. Includes and imported type definitions are resolved through the same `-w/--workspace` boundary as other model reads. `profile synthesize` is the inverse artifact step: it accepts the bare artifact or an extraction envelope and uses typed dmkits to emit a deterministic own-domain DM-JSON model; stdout carries the model unless `-o` writes it and returns an applied receipt. Synthesis refuses profiles with unbounded repeatable groups instead of silently substituting a cheaper workload. `profile compare` accepts either bare artifacts or extraction envelopes, returns the overall match plus every `EXACT`/`AT_LEAST`/`BUCKET`/`INFORMATIVE` axis in `data`: the verdict is `data.matches`, and a mismatch exits 1. No consistency gate arbitrates a comparison between two profile artifacts, so the envelope carries no `valid`/`verification` — the exit code and `data.matches` are the whole answer. All three operation contracts are independently discoverable from `dmtool schema profile extract`, `dmtool schema profile synthesize`, and `dmtool schema profile compare`; the synthesis contract describes both its DM-JSON artifact and its `-o` result receipt. There is no V1/V2 or `profileVersion` mode. The implemented contract is [`MODEL-PROFILE-SPEC.md`](docs/MODEL-PROFILE-SPEC.md).

### Edit, with a preview diff

```sh
# Re-express an existing rule; --dry-run PREVIEWS the change (writes nothing):
dmtool -m order.dm.json rule modify /Order/DeliveryNotBeforeOrder \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0' --dry-run
```

### Edit structure — with a safe-delete gate

```sh
# simple, via flags
dmtool -m order.dm.json field add --group /Order --name LoyaltyPoints --kind NUMBER

# rich: per-kind config + metadata (unit/pattern/suggestions/…) — see `schema field add`
dmtool -m order.dm.json field add fee-spec.json

# …or pipe the same spec in, so a script needs no temp file (the config-bearing kinds —
# TIME/DATETIME/DATERANGE/DATEFRAGMENT/ENUM/CUSTOM — cannot be expressed with --kind alone)
printf '%s' "$SPEC" | dmtool -m order.dm.json field add -

dmtool -m order.dm.json group add --parent /Order --name Shipping
# --parent / (equivalently the empty string) adds a new ROOT group — the shape that separates
# model-wide from root-wide addressing; the same spelling works for include/multiselect/attachment
dmtool -m order.dm.json group add --parent / --name Claim
# index field (also allowed on non-repeatable groups; repeating groups use it as their row key)
dmtool -m order.dm.json group modify /Order/Items --index-field Sku

# a reusable type — simple via --kind, or a rich ENUM/restricted type via a spec
dmtool -m order.dm.json typedef add --id Money --kind NUMBER
dmtool -m order.dm.json typedef add --id Tier tier-enum.json   # { "kind":"ENUM", "enum":{"values":[…]} }
dmtool -m order.dm.json typedef modify --id Money money-3dp.json   # change it in ONE place — typed fields inherit it

dmtool -m order.dm.json config modify \
  --decimal-separator "," --label en_US="Customer Order"

# access-control roles (merge-safe; the `workspace roles` lint binds them to auth/roles.yaml)
dmtool -m order.dm.json config modify --role shopper --role merchant

# the model's base year (resolves year-less dates + numeric BaseYear arithmetic); `config read` echoes it
dmtool -m order.dm.json config modify --base-year 2024

# restrict the allowed-character set (runtime-enforced)
dmtool -m order.dm.json config modify \
  --supported-character A-Z --supported-character 0-9

# mount another model under /Order/Prices, dropping the included rules
dmtool -m order.dm.json include add \
  --alias prices --reference pricelist \
  --parent /Order --name Prices --exclude-rules -w ./models

# import another model's type-defs (merged by id, not mounted)
dmtool -m order.dm.json typedef import --reference catalogtypes -w ./models

# a multi-select group template
dmtool -m order.dm.json group multiselect \
  --parent /Order --name Conditions \
  --field Condition --values NEW,USED,REFURB --max-rows 3

# the canonical attachment template (8 fields + 4 rules), generated whole
dmtool -m order.dm.json group attachment \
  --parent /Order --name Invoice \
  --max-size-bytes 10000000 --allowed-mime application/pdf

# label/describe/annotate any element (field/group/rule/computation by path, type-def by id); reads it back
dmtool -m order.dm.json meta /Order/Total \
  --label en_US="Order Total" --internal en_US="the order total" --annotate owner=orders-team

# transactional metadata uses the same polymorphic ref (including computations and type definitions)
dmtool -m order.dm.json apply metadata-ops.json

# severity/message edit, condition untouched
dmtool -m order.dm.json rule modify /Order/SomeRule \
  --severity WARNING --message en_US="…"

# Refactor — structural & safety-gated. typedef rename repoints every using field; extract factors shared-type fields out:
dmtool -m order.dm.json typedef rename Currency --to Money

# inverse: typedef inline Money
dmtool -m order.dm.json typedef extract --from /Order/Price,/Order/Cost --id Money

# rename the model's own id (single-model; -w refuses if a sibling includes the old id)
dmtool -m order.dm.json model rename --to purchase-order -w ./models

# field/group/rule rename rewrites every reference to the element (via the kernel's MoveSupportDM), then re-validates:
dmtool -m order.dm.json field rename /Order/DeliveryDate --to ShipDate

# field/group move relocates an element to a new parent and rewrites references to its new path (same engine);
# the gate refuses a missing / colliding / self destination:
dmtool -m order.dm.json field move /Order/DeliveryDate --to /Order/Shipping

# extract a subtree into a reusable sub-model, re-mounted at the same name (paths preserved → references survive):
dmtool -m order.dm.json group extract /Order/BillingAddress --reference billing-address
dmtool -m order.dm.json include inline billing-address   # the inverse: fold the sub-model back in

# Deleting a referenced field is REFUSED with a clear diagnostic that names the referrers …
dmtool -m order.dm.json field remove /Order/DeliveryDate
# … resolve it explicitly with --cascade (removes the dependent rules too):
dmtool -m order.dm.json field remove /Order/DeliveryDate --cascade
```

### Both kits at once, atomically — the `apply` session

The CLI face of the unified `EditSession` (the library's one-model-one-gate session, below) — **modelkit structure edits and rulekit rule edits in one transaction**:

```sh
# Read → ordered surgery (structure via modelkit + rules via rulekit) → ONE gate → ONE write, rollback on failure:
dmtool -m order.dm.json apply <ops.json>
```

`apply` runs a list of op-records (e.g. *add field `Discount`*, then *re-type `Plz` to add a pattern*, then *rename `Note`→`Memo`* rewriting its references, then *add a rule that references `Discount`*) on a single in-memory session, tolerates transient-invalid intermediate states, gates once at the end, and **rolls back atomically** if any op fails — reporting which op, and why (with did-you-mean correctives for typos). The op-set spans add·**modify (incl. `field` re-type)**·remove·read **plus the reference-rewriting refactors** (field/group rename·move, rule/typedef rename — each runs its safety gate at its turn, so a refused refactor rolls the whole sequence back). See [`cli-apply`](examples/cli-apply.md) and the gate contract in [CLI-SPEC §6/§7](docs/CLI-SPEC.md).

### Works on real, multi-file apps

```sh
dmtool -m app/storefront.dm.json model check -w lib   # resolves include'd / typedef'd models
```

### Version & compatibility — tolerant, but explicit

This build targets **A12 Kernel 30.8.1** (A12 Tools distribution **2025.06-ext5**). `dmtool --version` (and `manifest.version`) report that — alongside the base source revision and clean/dirty build state, the kernel actually on the classpath, the operator-catalog floor, and the document-model reference version. A `CLEAN` stamp identifies the exact committed source; `DIRTY` explicitly says the artifact differs from that revision. Loading a model authored for a different format is handled with the kernel as the authority — an older, compatible model loads with an informational `RK_MODEL_VERSION_SKEW`, an incompatible one fails fast with `RK_MODEL_VERSION_INCOMPATIBLE` (and writing a model back never bumps its version). A second, A12-Tools-only packaging invariant is made *readable* rather than policed: that distribution requires a model's file basename to equal its `header.id`, the kernel does not care, so `model info` reports `fileName` and `fileNameMatchesId` beside `id` and leaves the call to you.

### See it all, runnable

The [`examples/`](examples/) directory has **verifiable** walkthroughs of every verb — each is documentation *and* a regression test (re-run against the live CLI):

| Demo | Shows |
|---|---|
| [`cli-tour`](examples/cli-tour.md) | read one model: `model check` / `model describe` / `model read` · `model usage` · `rule read` / `rule explain` / `rule format` / `rule check` · `field read` / `group read` / `config read` |
| [`cli-discover`](examples/cli-discover.md) | the self-describing surface: `manifest` · `operators` · `patterns` · `diagnostics` · `schema` · `profile extract`/`synthesize`/`compare` |
| [`cli-review`](examples/cli-review.md) | review & comprehend: `model report` (the glossed catalog) · `model diff` (the impact-ranked semantic diff, incl. typedefs, model configuration, and `POLARITY_INVERTED`) |
| [`cli-workspace`](examples/cli-workspace.md) | multi-file models: `model info` · `workspace list` / `workspace graph` / `workspace roles` |
| [`cli-edit-loop`](examples/cli-edit-loop.md) | author/modify rules & computations, `where-used`, `batch`, `;;` comments, the re-express loop |
| [`cli-structure-edit`](examples/cli-structure-edit.md) | fields/groups/typedefs/includes/config + the safe-delete gate, the safety-gated refactors, group templates, `meta` |
| [`cli-apply`](examples/cli-apply.md) | the atomic multi-op session + rollback + correctives + in-session refactors |
| [`cli-runtime`](examples/cli-runtime.md) | `model eval` / `rule eval` / `model compute` against document instances, `model seed` to generate one |
| [`cli-custom-types`](examples/cli-custom-types.md) | custom field types & conditions: `--predefined-types`, the JS escape, `data.unsupported`, `--strict-custom` |
| [`cli-version`](examples/cli-version.md) | `--version`, `manifest.version`, the model-version policy |
| [`cli-jsonschema`](examples/cli-jsonschema.md) | `model import-jsonschema` / `model export-jsonschema` — JSON Schema & OpenAPI, both directions |

The full CLI contract: [`docs/CLI-SPEC.md`](docs/CLI-SPEC.md).

---

## The idea — typed, model-bound, kernel-confirmed

The same fragile-by-hand condition (see *Why `dmtool`* above) has a **typed** answer for developers.

**rulekit** builds the condition as **typed code bound to your model** — so whole classes of mistake become impossible or caught immediately:

```java
BoundModel m = BoundModel.ofJson(Files.readString(modelPath));   // bind a model to the real kernel

GroupRef  addons = m.group("/Subscription/Addons");              // resolve each element ONCE …
NumberRef fee    = m.num("/Subscription/Addons/MonthlyFee");     // … then reuse the typed ref — no repeated paths

Rule rule = m.rule(addons)
    .name("FeeCapRule")
    .errorField(fee)                                            // fields come OFF the model …
    .when(fee.gt(1000))                                         // … a wrong path can't compile
    .code("FEE_CAP").severity(Severity.ERROR)
    .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch."))
    .build();

String dmJson = m.add(rule).toJson();   // re-validated through the kernel; ready to write back
```

Or hold an **editor** on the group and navigate relatively — the model path is typed once, and the rule authors in place against it:

```java
EditSession s      = EditSession.read(dmJson);
GroupEditor addons = s.group("/Subscription/Addons");           // a session-bound editor on the group
NumberRef   fee    = addons.num("MonthlyFee");                  // a typed ref off the editor — relative, no full path
addons.addRule(r -> r                                           // pre-scoped to /Subscription/Addons
        .name("FeeCapRule").errorField(fee)
        .when(fee.gt(1000))                                     // same typed condition, same kernel gate
        .code("FEE_CAP").severity(Severity.ERROR)
        .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch.")).build());
String updated = s.validate().toJson();                         // one kernel gate
```

Three things make this more than a string builder:

- **The model is the type system.** `m.num(...)`, `m.str(...)`, `m.date(...)`, `m.group(...)` return handles bound to real fields, so a path that doesn't exist (or a number operator on a date) fails *before* you ever reach the kernel. (That's a **path lookup** — one of two ways to name a field; the other is a session **editor** for the read→edit→reference flow, below.)
- **It renders to the exact DSL the kernel expects** — English keywords by default, **German** on demand (`And`/`Und`, `Today`/`Heute`, …) — and the kernel confirms the rendered rule, so "valid" means *the engine accepts it*.
- **It runs in reverse.** rulekit can **read an existing condition string back** into the same typed form — to explain it, analyze it, or re-express it with a reviewable diff. Anything it doesn't yet model is preserved *verbatim*, never dropped.

**modelkit** applies the identical idea to the model's **structure** — fields, groups, type definitions, includes, document config — emitting DM-JSON the kernel accepts.

---

## rulekit — the library

### Referencing a field — by path, or through an editor

Two ways to name the field a rule talks about; they return the **same pure refs**, so you blend them as fits:

| Style | Looks like | Reach for it when |
|---|---|---|
| **By path** | `m.num("/Order/Discount")` | referencing an **existing** element while authoring against a loaded model — the common case |
| **Through an editor** | `s.group("/Order").num("Discount")` | the read→**edit**→reference flow: a ref to a field you *just created*, or relative navigation, without re-typing paths — see *Both kits together* below |

Neither is "older": a path lookup is the foundation; the editor adds relative navigation, refs that flow out of edits, and `m.rule(editor)` for when structure and rules move together. The examples below use whichever fits — and [mix both](#both-kits-together--editsession-one-model-one-gate) when a rule spans a just-added field and an existing one.

### Author (a quick start)

The rule above is the whole pattern: pick the **scope group**, the **error field**, the **condition** (true on the violation), a **code** and per-locale **messages**, then `build()`. A **computation** (a field whose value is derived from others) reads the same way — either one unconditional `calc(...)`, or a **precondition table**: each `when(...)` guards its `calc(...)`, and a `commonPrecondition` guards them all:

```java
NumberRef base = m.num("/Subscription/Billing/BaseFee");
Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
    .name("EffectiveFeeComp")
    .commonPrecondition(base.filled())                 // guards every alternative below
    .when(base.ge(100)).calc(base.value())             // at or above the threshold: pass the base fee through
    .when(base.lt(100)).calc(NumberLiteral.of("0.00")) // below it: waived
    .message(en("Effective fee follows the fee tiers."), de("Effektivgebühr folgt den Gebührenstufen."))
    .build();
```

### Interpolated error messages

An error message can splice in the **runtime value**, the **label**, or the **row number** of a field/group through typed helpers — each takes a *ref*, so it can't name a field that doesn't exist. The kernel substitutes the token when it renders the message for a failing document:

| Helper | Renders the token | Substituted with… |
|---|---|---|
| `fieldValue(ref)` | `$/Order/Items/Count.value$` | the field's runtime **value** |
| `fieldLabel(ref)` | `$/Order/Items/Count$` | the field's localized **label** |
| `repetitionIndex(groupRef)` | `$#/Order/Items$` | the repeatable group's 1-based **row number** |

The group token is the one with a placement rule: the named group must **contain** the rule — its own group or any ancestor. A descendant, a sibling, or a group under another root is rejected `INVALID_GROUP`. Take the `GroupRef` from the bound model (`m.group("/Order/Items")`, which refuses a path that does not resolve) and you are on the absolute spelling the position requires.

```java
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.*;   // en, de, fieldValue, fieldLabel, repetitionIndex

NumberRef count = m.num("/Order/Items/Count");
GroupRef  items = m.group("/Order/Items");
// renders, e.g., "Row #2: Count (now 0) must be positive."
m.rule(items).name("CountPositive").errorField(count)
    .when(count.filled().and(count.le(0))).code("COUNT_POSITIVE").severity(Severity.ERROR)
    .message(en("Row #" + repetitionIndex(items) + ": " + fieldLabel(count.field())
            + " (now " + fieldValue(count.field()) + ") must be positive."))
    .build();
```

The CLI `rule add` / `apply` message path accepts the same `$…$` tokens as plain text — the helpers are just the typed, ref-checked way to write them.

### What you can express

The condition language is rich; a flavour of what the typed builder covers (each renders to kernel-valid DSL):

| Want to say… | DSL it produces |
|---|---|
| a field is present / absent | `FieldFilled(OrderDate)` · `FieldNotFilled(DeliveryDate)` |
| compare a field to a value **or another field** | `[Quantity] > 0` · `[MinQty] <= [MaxQty]` |
| enums by their stored value (incl. category navigation) | `[/Subscription/Tier] == "PRO"` · `[Tier -> Pricing] == "PAID"` |
| dates & arithmetic | `DifferenceInDays(OrderDate, DeliveryDate) < 0` · `[OrderDate] >= Today` |
| aggregates over a repeating group | `Sum(Items*/Amount) > 500` · `MaxValue(Items*/Price)` |
| string patterns (portable regex) | `PatternViolated(PostalCode, "[0-9]{5}")` |
| "for every add-on row, the fee is present" | `GroupFilled(/Subscription/Addons) And FieldNotFilled(MonthlyFee)` |

Browse the full, self-verifying catalogue (every operator with its meaning, examples, and **gotchas**) via the CLI's `operators` verb, or in the [API guide](docs/API-GUIDE.md).

### Read a foreign rule back

```java
KernelAdapter adapter = KernelAdapter.of(m);
ReadResult read = adapter.read("/Order/DeliveryNotBeforeOrder");   // text → typed AST, tolerant
// → ReadResult.Converted(condition)  … or  ReadResult.Opaque(text, reason)  (preserved verbatim)
```

### Explain, navigate, find

```java
ModelContext ctx = new ModelContext(m);
ModelCard card  = ctx.describe();                 // every field + kind + enum domain + group repeatability
RuleExplanation why = ctx.explain(condition);     // what it checks, and WHEN it fires (polarity made explicit)
List<FieldMatch> hits = ctx.find("birthdate");    // resolve a fuzzy name to model fields
Set<String> users = adapter.whereUsed("/Customer/BirthDate");   // which rules reference a field
m.fields().filter(f -> f.annotation("audited").isPresent());    // find fields by annotation (GroupInfo too)
```

### Modify by re-expression, with a diff — and render to German

```java
new DslRenderer(ConditionLanguage.DE).render(condition);   // German keywords: Und, Heute, AlleFelderGefüllt …
```

Re-expressing an existing rule produces a **`RenderedDiff`** that discloses any lossiness *up front* — you review before you commit. The rule's **leading `;;` comment is preserved by default** across the loop (only inline/trailing comments are re-rendered away, and the diff says so).

### Comments — author once, preserved across the loop

Conditions can carry `;;` line comments (concrete syntax the kernel tolerates). Author them with the typed builder, and a comment-less `modify` keeps them:

```java
NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
m.rule(m.group("/Subscription/Addons")).name("FeeCap").errorField(fee)
        .when(fee.filled().and(fee.gt(1000)))
        .comment("cap the monthly fee per policy")     // ← rendered as a leading ;; line, kernel-tolerated
        .code("FEE_CAP").severity(Severity.ERROR)
        .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch."))
        .build();
```

The CLI mirrors it: a `comment` key on `rule add` and `computation add`, plus `rule modify --comment`. In the typed library, computations carry comments on operations and preconditions too.

### Validation-pattern catalogue

The recurring idioms are a **catalogue** — `PatternCatalog.load().byId("date-order")` — across three kinds: **rule** idioms (date-order, required-when, mutually-exclusive, at-least-one-of, sum-of-line-items), the **computation** idiom `tiered-amount`, and **field-constraint** idioms (bounded-number, formatted-string, value-set-enum — the field-level alternatives the `seeAlso` bridge names). Each builds its artifact through the **typed DSL**, so it's correct by construction (a rule's right polarity + referenced error field, a computation's mutually-exclusive precondition table, or a typed field config). The library returns a sealed `RuleInstantiation` | `ComputationInstantiation` | `FieldInstantiation`; the CLI `patterns` verb scaffolds + `--apply`s them. Each entry's example is kernel-validated in CI, so the catalogue can't go stale. Full API + the typed-vs-data design: [API guide](docs/API-GUIDE.md).

More rulekit idioms and the design rationale are in the [API guide](docs/API-GUIDE.md).

---

## modelkit — the library

modelkit authors a model's **structure** the same way rulekit authors rules — typed, fluent, kernel-confirmed. It is the mirror of rulekit: rulekit writes the *rules*, modelkit writes the *shape* they run on.

### Build a model from scratch

```java
DocumentModelDraft order = DocumentModelDraft.named("order")
    .locale("en_US")
    .rootGroup("Order", g -> g
        .field("OrderDate", FieldKind.DATE, Requiredness.REQUIRED)
        .numberField("Quantity", n -> n.fractionDigits(0).min("1"))   // a whole number ≥ 1
        .enumField("Priority", e -> e.value("LOW").value("HIGH"))
        .group("Items", items -> items
            .repeatable(99)                                           // a repeating line-item group
            .field("Sku", FieldKind.STRING)
            .numberField("Amount", n -> n.fractionDigits(2))))        // a money amount, scale 2
    .build();

String dmJson = order.toJson();   // DM-JSON the kernel accepts
```

### Field kinds, fully configured

Each kind carries its full configuration — string length/line-breaks, number scale/min/max/unit, dates with a format, enums, and a **custom** field type:

```java
DocumentModelDraft policy = DocumentModelDraft.named("policy").locale("en_US")
    .rootGroup("Policy", g -> g
        .stringField("Reference", s -> s.minLength(3).maxLength(12))                       // bounded text
        .stringField("Notes",     s -> s.lineBreaks().maxLength(500))                      // multi-line
        .numberField("Premium",   n -> n.unit(NumberTrait.AMOUNT).fractionDigits(2).min("0"))  // money ≥ 0
        .numberField("Discount",  n -> n.unit(NumberTrait.PERCENT).fractionDigits(1))      // a percentage
        .dateField("EffectiveOn", d -> d.format("dd.MM.yyyy"))
        .dateRangeField("CoverageYears", d -> d.format("yyyy").separator("/"))             // a from–to range
        .enumField("Tier", "BRONZE", "SILVER", "GOLD")                                     // enum (varargs shorthand)
        .customField("Iban", c -> c.type("IbanType").minLength(15).maxLength(34)))         // a custom field type
    .build();
```

### Group templates — multi-select & attachment

Two ready-made compositions: a repeatable selection list, and the kernel's canonical file-attachment group (generated whole):

```java
DocumentModelDraft claim = DocumentModelDraft.named("claim").locale("en_US")
    .rootGroup("Claim", g -> g
        .multiSelectGroup("Conditions", ms -> ms.enumField("Condition", "NEW", "USED").maxRows(2)))  // pick up to 2
    .attachmentGroup("Evidence", a -> a.repeatable(10))   // canonical file-attachment group, generated whole
    .build();
```

### Type definitions & includes

Declare a reusable **type** once and reference it by name; **include** another model's tree under a group, or **import** a type-def-only model's types (merged by id, not mounted):

```java
DocumentModelDraft catalog = DocumentModelDraft.named("catalog").locale("en_US")
    .typeDef("Money", t -> t.number(n -> n.unit(NumberTrait.AMOUNT).fractionDigits(2)))  // a reusable type, declared once
    .typeDef("Sku",   t -> t.string(s -> s.maxLength(12)))
    .rootGroup("Catalog", g -> g
        .typedefField("ListPrice", "Money")      // …then a field OF that type, referenced by id
        .typedefField("Code",      "Sku"))
    .include("supplier", "SupplierModel")        // mount another model's tree under a group named "supplier"
    .importTypeDefs("SharedTypes")               // merge a type-def-only model's types (by id), no mount
    .build();
```

### Document config & metadata

Locale, base year, a runtime-enforced character set — plus per-locale labels, a model comment, and name/value annotations on any element:

```java
DocumentModelDraft m = DocumentModelDraft.named("order").locale("en_US")
    .baseYear(2026)                                       // anchors relative-date comparisons
    .supportedCharacters("A-Z", "a-z", "0-9", " ", "-")   // runtime-enforced character set
    .comment("the customer order model")                 // a model-level note
    .label("en_US", "Order").label("de_DE", "Bestellung")// per-locale display labels
    .annotation("owner", "orders-team")                  // a name/value annotation
    .rootGroup("Order", g -> g.field("Reference", FieldKind.STRING))
    .build();
```

### Illegal states are unrepresentable

The builder rejects the mistakes the kernel would — a repeatable group with `max < 2`, a field referencing an undeclared type definition — *as you build*, not after a round-trip; and the kernel still has the final say (every result re-binds through `checkConsistency`). To edit the structure of an *existing* model — add/remove fields & groups, declare type definitions, change config — use `EditSession` (below).

## Both kits together — `EditSession`: one model, one gate

This is where modelkit and rulekit meet — **structure edits and rule edits on one in-memory model, behind a single kernel gate**. The north-star flow: read a model, make a series of structure *and* rule edits (a rule may reference a field added earlier in the same session), validate **once**, write back (intermediate invalid states are fine — only the final result must satisfy the kernel):

The idiom is the **editor**: you hold an editor on a group rather than re-typing string paths — structure edits chain, a typed ref to a just-created field comes from relative navigation, and rules author in place against it.

```java
EditSession s = EditSession.read(dmJson);
GroupEditor order = s.group("/Order");                       // a session-bound editor on /Order
order.addField("Discount", FieldKind.NUMBER);                // structure edit (modelkit) — chains
NumberRef discount = order.num("Discount");                  // a typed ref to the field just added
order.addRule(r -> r                                         // a TYPED rule (rulekit), pre-scoped to /Order
        .name("DiscountNotNegative").errorField(discount)
        .when(discount.filled().and(discount.lt(0)))         // the violation: a discount below zero
        .code("DISCOUNT_NEGATIVE").severity(Severity.ERROR)
        .message(en("Discount must not be negative.")).build());
String updated = s.validate().toJson();                      // one kernel gate
```

**Refs are the currency.** `order.num("Discount")` resolves *only because* `addField` ran first — and it returns a typed ref, not a string path you re-type. Rules author in place (`order.addRule(r -> …)`, the builder pre-scoped to the group) or take the editor straight as a scope (`m.rule(order)`); either way the immutable AST holds **only the pure ref, never the session**, so a rule can't leak an effect. `GroupEditor` also nests (`addGroup(name, sub -> …)`) and carries the same per-type field builders as the from-scratch builder (`order.numberField("Surcharge", n -> n.fractionDigits(2))`, `enumField`, `dateField`, … — a *configured* field mid-edit, byte-identical to one built from scratch); `FieldEditor` re-types/labels/annotates a field; `RuleEditor` / `ComputationEditor` modify or remove by held reference — a rule's condition, **or its severity / messages in place** (`s.rule(name).severity(WARNING).messages(en("…")))`). `EditSession` also carries the **reference-preserving refactors** — `renameField`/`renameGroup`/`moveField`/`moveGroup` rewrite every reference to the element (via the kernel's `MoveSupportDM`) and re-validate, so a rename or relocation never dangles a referrer. Design + rationale: [API guide §5c](docs/API-GUIDE.md).

**Blend both as a developer would** — the editor for a field you just added, a path lookup for one that already exists, in the same condition (they are the same pure ref):

```java
GroupEditor billing = s.group("/Subscription/Billing");
billing.addField("Surcharge", FieldKind.NUMBER);                       // NEW field — via the editor
NumberRef surcharge = billing.num("Surcharge");                        // editor relative ref
s.addRule(m -> m.rule(billing.ref()).name("SurchargeUnderBase").errorField(surcharge)
        .when(surcharge.filled().and(surcharge.gt(m.num("/Subscription/Billing/BaseFee"))))  // ← path lookup, existing field
        .code("SURCHARGE_OVER_BASE").severity(Severity.ERROR)
        .message(en("Surcharge must not exceed the base fee.")).build());
```

The flat session verbs are still there for a quick one-off — `s.addField(path, …)`, `s.addRule(m -> m.rule(m.group(path)) …)` — and a raw-string condition overload backs the CLI's `apply`/spec path (conditions arrive as text), but it's not the idiom for library code.

More library idioms and the design rationale live in the [API guide](docs/API-GUIDE.md); every doc is indexed in [`docs/`](docs/README.md).

---

## The interpreter — kernel-free runtime evaluation

Runtime evaluation — *which rules fire on this document, what does each computed field evaluate to* — runs on a **clean-room, kernel-free interpreter** (Kotlin Multiplatform). It is the sole runtime eval engine, the one behind `model eval` / `rule eval` / `model compute` / `model seed` — and the reason those verbs work in the native binary: the interpreter needs **no codegen step at all**, where the kernel's Groovy codegen can't go native. Those verbs and `model expand` now share the interpreter's portable workspace boundary for includes and imported typedefs; the JVM/Jackson model reader remains a metadata/index implementation, not a second runtime resolver.

- **Kernel-exact, by verification.** Its results are checked rule-for-rule against the real A12 kernel by a **differential test suite** plus a model-driven fuzzer, and it replays a **conformance corpus** of kernel-verified expected outcomes — captured tri-verified against the kernel's two evaluation strategies — on both JVM and JS.
- **A standalone library, not just the CLI's engine.** One common engine serves **JVM, Node.js, and browser** applications. The public TypeScript, Kotlin, and Java API prepares finite or provider-backed workspaces, resolves includes and imported type definitions, reads Documents, reuses a prepared interpreter across form changes, runs full or partial validation, computes derived values, and hosts project field/condition callbacks.
- **A developer-facing public release.** Start with the [public interpreter repository](https://github.com/mbackschat/a12-interpreter-releases), [try the live browser showcase](https://mbackschat.github.io/a12-interpreter-releases/showcase/), or browse the [version-matched API guide and references](https://mbackschat.github.io/a12-interpreter-releases/api/latest/). The packed-artifact-tested [source guide](docs/INTERPRETER-API-GUIDE.md) and [showcase source](interpreter/consumer-tests/browser/) remain here for contributors.

<p align="center">
  <a href="assets/infographics/a12-interpreter-runtime.avif">
    <img src="assets/infographics/a12-interpreter-runtime.avif" alt="A12 Interpreter preparation, reusable request lifecycle, outputs, and Kernel conformance evidence" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

### Performance and footprint

The same engine runs on the JVM, Node.js and in the browser, and the figures below come from two runtimes — latency measured in system Chrome, cross-engine comparison and memory measured on the JVM.

Small enough to ship to a browser — the production JavaScript entry is **260 kB gzip**, with no A12 Kernel bytecode — and quick enough to re-run on every keystroke.

<p align="center">
  <a href="assets/infographics/interpreter-vs-kernel.avif">
    <img src="assets/infographics/interpreter-vs-kernel.avif" alt="Measured A12 Interpreter performance and resident memory compared with the A12 Kernel" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

Measured in system Chrome against the packed npm package, the same artifact consumers install, over the two Documents the collector defines. Each is timed **two ways**, because an application experiences both: the **first answer** a user waits for when a form opens, which includes building the interpreter, and the **warm** cost of revalidating after every keystroke, which does not.

| Document | Findings | First answer *(includes building the interpreter)* | Warm *(validation only)* |
|---|---|---|---|
| 200 rows — representative | — | **11.55 ms median** | **1.1 ms** |
| 10,000 rows — stress | 4,900 | **166.1 ms median** | **53.3 ms** |

The stress Document carries 50× the rows and costs 14× the time.

The comparison baseline is deliberately the toughest one available — the A12 Kernel's own **generated validator compiled as Java**, with code generation outside the clock. Each figure is how many times faster the interpreter finished the same work:

| Default inventory, 15 cases | Repeated documents | Huge document, 20,000 rows | Hardest case, 384 low-firing rules |
|---|---|---|---|
| **2.8× – 17.1×** | **12.2× – 16.8×** | **≈152×** | **1.73×** |

The spread does not track model size: the widest margins were measured on computation-heavy and repeated-document scenarios, the narrowest on 384 concentrated rules that almost never fire. Resident memory on the JVM goes the same way — a prepared model costs **4.2× – 8.1×** less than the Kernel's generated Java and **18.5× – 37.9×** less than its dynamic Groovy.

**Not one eligible comparison went the other way.** Across the default, repeated, rule-count and edge suites, neither kernel strategy won a single case — the hardest case above is the narrowest win, not a loss. Browser support is measured directly: the packed tarball is installed into a real consumer and exercised in system Chrome, never inferred from Node.js. These are bounded same-machine measurements after exact result preflight, not universal promises.

<sub>Measured 2026-08-17 · Apple M1 · Chrome 151 · JDK 21 · no discarded samples · [method, timing boundaries and complete tables](docs/INTERPRETER-PERFORMANCE.md)</sub>

---

## The portable checker — kernel-free consistency, without the kernel

<p align="center">
  <a href="assets/infographics/a12-consistency-checker.avif">
    <img src="assets/infographics/a12-consistency-checker.avif" alt="A12 Consistency Checker application inputs, checking operations, reports, snapshot reuse, and assurance boundaries" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

The interpreter answers *what happens when you run this model*. The **portable checker** (`a12-consistency`) answers the question before it: *would A12 reject this model at all?* — and it answers it with no server, no kernel and no licence, on the JVM, in Node and in the browser, from one Kotlin Multiplatform implementation.

**It never says `VALID`, and that is the design rather than a shortfall.** "Would A12 accept this?" is a claim about every rule A12 has, and the checker implements a finite subset — 103 named check families today — so it reports `INVALID` or `INCONCLUSIVE` and publishes the coverage evidence (`checksCompleted`, `gaps`) that says how much weight the answer bears. What it *can* decide is its own scope, and that does have a pass: `outcome: ALL_CHECKS_PASSED` means every family this build implements ran and none rejected the model. Reading `INCONCLUSIVE` as "valid" is the one way to misuse it, which is why the status is a word rather than a boolean.

```ts
import { checkEditJson } from 'a12-consistency';

// Gate a save by what the EDIT introduced, not by the state of the model it lands in.
const edit = JSON.parse(checkEditJson(baselineJson, editedJson));
edit.introduced;
```

- **Strings in, strings out, and nothing throws.** An unreadable source comes back as a report whose `diagnostics` explain the failure, so there is no `catch` branch to write. Beyond a whole-model check it answers an unsaved **draft**, the **impact** of a candidate change (which declarations it reaches, not which it broke), and holds one analysis across many questions through a **snapshot** — the editor path, where a keystroke costs one check rather than a full re-admission.
- **The same answer from the CLI.** `dmtool model check --engine portable` runs exactly this checker; `--engine kernel` runs the real kernel. The engine you ask for is the engine that answers — neither ever falls back to the other, and the receipt names which one spoke.
- **Worked, executable consumers.** A plain [Node project](consistency/consumer-tests/node) including a CI gate that cannot manufacture a pass, and a [browser consumer](consistency/consumer-tests/browser) replaying the same shared cases in real Chrome. Both install the packed tarball and import by package name.
- **Not released yet — unlike the interpreter above.** The publish wiring is landed and the public deploy is **deferred** ([`docs/RELEASE-SPEC.md`](docs/RELEASE-SPEC.md) §11), so `npm add a12-consistency` resolves nothing today: you pack the tarball from this repository and install it by path. The two commands are in the module README below.
- **Read next:** the module front page [`consistency/README.md`](consistency/README.md) and the full contract [`docs/CONSISTENCY-API.md`](docs/CONSISTENCY-API.md). Two JVM façades sit on top for callers who already hold one of the other products — `PortableConsistencyGate` returns the verdict as a rulekit `ValidationResult`, and `ConsistencyPreflight` answers an interpreter consumer's static precondition.

---

<!-- source-statistics:root:start -->
## Source statistics

Generated with [Tokei](https://github.com/XAMPPRocky/tokei) from this repository. Fixtures, generated output and build trees are excluded from every count.

### Production

The shipped product. Counted from `core/src/main/java`, `rulekit/src/main/java`, `modelkit/src/main/java`, `edit/src/main/java`, `schemakit/src/main/java`, `adapter/src/main/java`, `cli/src/main/java`, `interpreter/src/commonMain/kotlin`, `interpreter/src/jvmMain/kotlin`, `interpreter/src/jsMain/kotlin`, `consistency/src/commonMain/kotlin`, `consistency/src/jsMain/kotlin`, `predicates/src/commonMain/kotlin`, `interpreter-bridge/src/main/kotlin`, `consistency-gate/src/main/kotlin`, `interpreter/typescript/src`, `interpreter/consumer-tests/browser/src`, `cli/src/main/resources/io/github/mbackschat/a12/dm/cli/interp/custom-js-worker.mjs`.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| Java | 503 | 74465 | 50347 | 16683 | 7435 |
| Kotlin | 270 | 58916 | 34926 | 19608 | 4382 |
| TypeScript | 17 | 3762 | 3209 | 274 | 279 |
| JavaScript | 1 | 107 | 72 | 25 | 10 |

### Verification

Tests, laws, kernel differentials, fuzzers, conformance replay, and the external consumer programs compiled against the packed artifacts. Counted from `src/test/java`, `schemakit/src/test/java`, `adapter/src/test/java`, `cli/src/test/java`, `src/noBackendTest/java`, `src/releaseGuardTest/java`, `adapter/src/kernelClosureConsumer/java`, `adapter/src/testFixtures/java`, `interpreter/src/jvmTest/java`, `consistency-gate/src/test/java`, `interpreter/consumer-tests/kotlin-jvm/src/main/java`, `adapter/src/test/kotlin`, `interpreter/src/commonTest/kotlin`, `interpreter/src/jvmTest/kotlin`, `interpreter/src/jsTest/kotlin`, `consistency/src/commonTest/kotlin`, `predicates/src/commonTest/kotlin`, `interpreter-bridge/src/test/kotlin`, `consistency-gate/src/test/kotlin`, `interpreter/consumer-tests/kotlin-jvm/src/main/kotlin`, `interpreter/consumer-tests/kotlin-js/src/jsMain/kotlin`, `interpreter/consumer-tests/typescript/src`, `interpreter/consumer-tests/browser/scripts`.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| Java | 1099 | 167764 | 114990 | 33325 | 19449 |
| Kotlin | 1017 | 154373 | 111518 | 27844 | 15011 |
| TypeScript | 15 | 3372 | 3154 | 73 | 145 |

### Tooling

Build scripts, build-time generators, and the evaluation harness — neither shipped nor a check on what ships. Counted from `scripts/SourceProvenance.java`, `interpreter/consumer-tests/kotlin-jvm/build.gradle.kts`, `interpreter/consumer-tests/kotlin-jvm/settings.gradle.kts`, `interpreter/consumer-tests/kotlin-js/build.gradle.kts`, `interpreter/consumer-tests/kotlin-js/settings.gradle.kts`, `eval/benchmark.workflow.js`, `eval/gap-finder.workflow.js`, `interpreter/typescript/scripts`.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| Java | 1 | 70 | 52 | 10 | 8 |
| Kotlin | 4 | 90 | 81 | 0 | 9 |
| JavaScript | 6 | 1838 | 1573 | 132 | 133 |

Maintainers regenerate these tables with the local statistics updater; both release publishers compare them with fresh counts before any public mutation.
<!-- source-statistics:root:end -->

---

## Repository layout

**A top-level directory is one of five things, and never two.** The rule is what the entry *produces*, not what it is about — so the checker's module, the checker's demos and the checker's docs live in three different categories on purpose.

**1. Modules that produce a published artifact.** Each is a Gradle module with its own `build.gradle`, its own purity lint, and a committed public-API golden under [`api/`](api).

| module | artifact | what it is |
|---|---|---|
| [`core`](core) | `dmkits-core` | the shared types and the SPI ports the kits reach the kernel through |
| [`rulekit`](rulekit) | `dmkits-rulekit` | validation rules and computations as typed, model-bound Java |
| [`modelkit`](modelkit) | `dmkits-modelkit` | the model's structure — fields, groups, type definitions, includes, config |
| [`edit`](edit) | `dmkits-edit` | `EditSession`: structure + rule edits on one model behind a single kernel gate |
| [`schemakit`](schemakit) | `dmkits-schemakit` | JSON Schema / OpenAPI ⇄ document model |
| [`interpreter`](interpreter) | `dm-interpreter` | the kernel-free runtime evaluator (Kotlin Multiplatform: JVM + JS) |
| [`consistency`](consistency) | `dm-consistency` / `a12-consistency` | the kernel-free consistency checker (Kotlin Multiplatform: JVM + JS) |
| [`predicates`](predicates) | `dm-predicates` | the dependency-free leaf holding A12 laws the Java kits and the checker would otherwise each restate |
| [`interpreter-bridge`](interpreter-bridge) | `dmkits-interpreter-bridge` | condition read-back with no kernel present — the one module depending on both `rulekit` and `interpreter` |
| [`consistency-gate`](consistency-gate) | `dmkits-consistency-gate` | the two JVM façades over the checker, for a dmkits caller and for an interpreter consumer |

**2. Modules that produce something other than a library.** [`cli`](cli) is the `dmtool` application, shipped as a native binary rather than a jar. [`adapter`](adapter) is the kernel backend — the only module that touches the kernel's internal read tier, deliberately unpublished so that tier can never reach a consumer. [`src`](src) is the root project's own source, and it is **test-only**: root has no main source at all, and hosts the cross-kit integration suite plus the public-API lock.

**3. What ships to a coding agent.** [`skill`](skill) holds the two canonical skills — the authoring skill (the material the self-sufficiency eval measures) and the bug-report skill. [`plugin`](plugin) and [`plugin-codex`](plugin-codex) are the Claude Code and Codex plugin trees; their skill copies are generated from `skill/` by [`scripts/sync-plugin-assets.sh`](scripts/sync-plugin-assets.sh), never hand-edited.

**4. How the claims get proved.** [`corpus`](corpus) is the kernel-verified conformance corpus the interpreter replays. [`eval`](eval) is the self-sufficiency benchmark harness that measures whether a cold agent can drive the tool. [`probe`](probe) drives a multi-prompt session against the *shipped* native binary and captures every friction. [`perf-results`](perf-results) holds the committed performance records, per platform. [`api`](api) holds the public-API goldens, one per published artifact.

**5. What a reader or user meets.** [`docs`](docs) — every design document, spec, findings record and gap ledger, indexed by [`docs/README.md`](docs/README.md). [`examples`](examples) — the runnable `dmtool` walkthroughs, with their own sample models. [`assets`](assets) — the infographics this README embeds.

**And the build itself:** [`gradle`](gradle) (the wrapper, the shared build fragments, and the Kotlin/JS yarn lockfile), [`scripts`](scripts) (every repository tool, indexed by [`scripts/README.md`](scripts/README.md)), and [`bin`](bin) — one launcher shim so a doc, demo or script writes `dmtool …` without naming a packaging artifact.

---

## Documentation

Start at the 📚 **[documentation index →](docs/README.md)**; the rest of the map:

| To find… | See |
|---|---|
| 🚀 Live interpreter | **[Browser showcase →](https://mbackschat.github.io/a12-interpreter-releases/showcase/)** · **[API guide and references →](https://mbackschat.github.io/a12-interpreter-releases/api/latest/)** |
| 🧭 Vision, requirements, the decision log | **[Project design →](docs/PROJECT-DESIGN.md)** |
| 🧩 The API design findings | **[API findings →](docs/API-FINDINGS.md)** |
| 🛠️ The CLI contract | **[CLI specification →](docs/CLI-SPEC.md)** |
| 🔬 Verified Kernel facts | **[Kernel findings →](docs/KERNEL-FINDINGS.md)** · **[Kernel integration notes →](docs/KERNEL-INTEGRATION-NOTES.md)** |
| 🩺 Getting a build to run on a machine | **[Build environment →](docs/BUILD-ENVIRONMENT.md)** · run **[`scripts/doctor.sh`](scripts/doctor.sh)** |

## Prerequisites

The A12 Kernel is resolved from your **local Maven repository** (`~/.m2`) — no private registry. Publish it from public sources once with the sibling [`a12-kernel-bootstrap`](https://github.com/mbackschat/a12-kernel-bootstrap) kit:

```sh
# in the a12-kernel-bootstrap checkout (a sibling of this repo)
./bootstrap.sh
```

This publishes kernel `30.8.1` (Java only) to `mavenLocal`. You also need **JDK 21** — the build toolchain is pinned to 21 (a newer JDK isn't used even if installed; [why](docs/BUILD-ENVIRONMENT.md) — Groovy 3.0.25 + the validated baseline) — and **Node >=24.12.0** for the repository's checked-in TypeScript tools and Node-backed demos. GraalVM 25 is used only for the native build. Run [`scripts/doctor.sh`](scripts/doctor.sh) to check the POSIX/TypeScript tooling boundary, Node, Java, Gradle-home, hook, and local-Kernel state together.

### Maintainer agent setup (optional)

The build does not depend on personal agent configuration. To carry the maintainer's shared workflow defaults, including TDD and commit conventions, across machines, install the global instructions from [`mbackschat/coding-setup`](https://github.com/mbackschat/coding-setup): its guarded installer links `~/.claude/CLAUDE.md` for Claude Code and `~/.codex/AGENTS.md` for Codex to the same version-controlled source. This repository's tracked `AGENTS.md` → `CLAUDE.md` link remains the source of project-specific requirements for every contributor.

## Build & test

Like the sibling repos, build with a **clean/isolated Gradle home** so the machine's global config (which may point at a private registry) is bypassed — `build.gradle` declares only `mavenLocal()` + `mavenCentral()`:

```sh
ROOT="$(cd .. && pwd)"                               # the directory holding the A12 repos
export GRADLE_USER_HOME="$ROOT/.gradle-isolated"     # public-repos-only Gradle home
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS; or point at any JDK 21
./gradlew test
```

**To avoid forgetting either variable** — the machine's global Gradle config can otherwise inject a private registry and fail confusingly ([root cause](docs/BUILD-ENVIRONMENT.md)) — use the committed wrapper `./build.sh test` (it pins both, then runs Gradle), or [`.envrc`](.envrc) if you have [direnv](https://direnv.net).

A plain `./gradlew test` works too on a machine with no global Gradle config, and on one whose init script honours the committed `.no-global-gradle-repos` marker. Two separate pieces do that, and they cover different halves: `settings.gradle` declares the public plugin repositories **first**, so anything an init script appends afterwards becomes a fallback for **plugin** resolution, while **dependency** resolution is covered by the marker alone, since an init script's `allprojects` hook runs before `build.gradle`. On a machine whose init script ignores the marker, use the wrapper. It is what you want day to day regardless, because it also pins JDK 21 and the Gradle home shared across the sibling A12 checkouts. To build *through* a private registry on purpose, delete the marker. Run [`scripts/doctor.sh`](scripts/doctor.sh) to see which of these applies to your machine.

The repository enables Gradle's local build cache and gives the shared Kotlin compiler daemon enough heap for overlapping cold JVM, JS, and adapter test compilation in [`gradle.properties`](gradle.properties). `build.sh` resolves Git's common directory, so even a linked worktree under `/private/tmp` uses the primary checkout's one isolated Gradle home; relocatable compiler output is restored instead of repeated after a same-commit landing. CI persists the same isolated user home through the official Gradle setup action, so hosted-runner dependencies, transforms, and relocatable compilation outputs survive between runs; performance measurements remain explicitly non-cacheable. [`scripts/verify-build-cache.sh`](scripts/verify-build-cache.sh) proves the local contract with two clean worktrees while Gradle is offline, ruling out a remote cache or dependency download. A truly cold full suite still performs all compilation and tests; for bounded automation, run `:interpreter:check`, `:adapter:test`, and `:cli:test` as separate commands instead of one monolithic invocation.

A green `test` means the kit's output is confirmed by the engine, not just plausible. The suite covers:

- kernel wiring and the model index
- the renderer (EN/DE) and the validator
- the self-verifying catalog — every example checked against the real kernel
- the constraint-mining instruments (slot parity, the MVK / RT-semantics / grammar-production ledgers)

With a sibling `a12-kernel-mm` clone present, an opt-in harness also loads all 15 of the kernel's own metamodels through the index (skipped when absent).

Other useful tasks:

```sh
./gradlew :adapter:test                              # the opt-in internal-tier module
./gradlew :cli:run --args="-m model.json model check"   # run the standalone CLI in dev (no packaging)
```

`./gradlew :test` runs the cross-kit library suite (root `:` is the integration-test host over the four kernel-free kit modules `:core`/`:rulekit`/`:modelkit`/`:edit`); the kits' main stays kernel-free and the kernel enters only as a test-scope oracle. See [`docs/CLI-SPEC.md`](docs/CLI-SPEC.md) for the CLI.

## Commit and push hooks

The committed hooks enforce the commit-message convention, four pre-commit checks, and a pre-push CI check. Install the workflow validator and enable all hooks once per clone:

```sh
brew install actionlint
git config core.hooksPath .githooks
```

The `commit-msg` hook hard-rejects mechanical convention violations and reminds on the judgment rule. The `pre-commit` hook refuses to stage build output or log files. It also refuses a commit whose staged files can drift a CI-gated doc unless the doc guards have run green since, and refuses any commit unless the repository-wide `leakGuardTest` lane has run green since. Those two evidence checks exist because `-Pfast` skips those lanes by design, so the inner loop cannot otherwise see them fail. The fourth refuses any commit when a test run of any lane, written since the newest staged change, recorded a failure, so a red run cannot be committed past because the command that ran it did not gate the commit. The `pre-push` hook runs the typed infographic freshness and CI-cost-ordering check through its stable shell launcher, then validates every GitHub Actions workflow with `actionlint`; it refuses when Node or `actionlint` is missing instead of claiming a check it could not perform. Native Git hooks make this neutral to the caller: terminal Git, an IDE, Claude Code, and Codex CLI all trigger the same gate.

**The local hooks are the early half of a defence, never the whole of one.** They are unwired in a fresh clone until you run the command above, and `--no-verify` bypasses them on purpose. That is deliberate: a local hook must not be the only thing standing between a mistake and the repository, so each has an authoritative counterpart in the test and CI estate. `scripts/doctor.sh` reports both hook wiring and `actionlint` availability. Source statistics are intentionally not a push or CI concern: the maintainer-only Tokei updater is run locally, and each release assembler checks the README it publishes. To apply the commit-message check across all your repos, point your global Git template at it — copy that hook into `~/.git-template/hooks` and set `git config --global init.templateDir ~/.git-template`.

## License

**MIT** — see [`LICENSE`](LICENSE).

- **Source: MIT.** The kits' own original work, shipping no kernel binary, so they carry no copyleft entanglement — consistent with the sibling `a12-kernel-demo` / `a12-kernel-bootstrap` (both MIT).
- **The kernel: dual `EUPL-1.2 OR commercial`** (upstream, not ours).
- **The shipped standalone CLI distributable: EUPL-1.2**, because it *bundles* the kernel — the source stays MIT.
