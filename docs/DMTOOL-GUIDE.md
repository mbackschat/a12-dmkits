# The `dmtool` guide

`dmtool` authors, reads and safely edits A12 document models from the command line, and runs their rules against real documents. This guide tours every verb family with copyable calls. The [project README](../README.md) gives the short orientation, and the [CLI specification](CLI-SPEC.md) is the full contract.

**Contents:** [install](#install--see-the-release-repo-single-source-of-truth) · [MCP](#serve-it-over-mcp) · [discover](#discover-the-tool-itself) · [start a model](#start-a-model-from-scratch) · [JSON Schema & OpenAPI](#json-schema--openapi) · [read](#read--understand-a-model) · [diff](#review-what-changed--a-semantic-diff) · [workspaces](#explore-a-whole-folder-of-models) · [patterns](#scaffold-from-a-validation-pattern--polarity--error-field-baked-in) · [author](#author--check--confirmed-by-the-kernel) · [run](#run-the-rules-against-real-data-not-just-static-checks) · [profiles](#calibrate-with-performance-profiles) · [edit](#edit-with-a-preview-diff) · [structure](#edit-structure--with-a-safe-delete-gate) · [`apply`](#both-kits-at-once-atomically--the-apply-session) · [multi-file](#works-on-real-multi-file-apps) · [versions](#version--compatibility--tolerant-but-explicit) · [demos](#see-it-all-runnable)

One native binary, no Java required. Model-bound operations use `dmtool -m <model> <target> <op> [args]`; root, catalog, and artifact operations expose their own syntax. Each matching manifest operational profile declares whether effective stdout is the standard JSON **result envelope** (`{ok, valid, outcome, data, diagnostics[], …}`), raw JSON/text, or empty after an artifact write. It is **self-describing**, so a cold agent (or you) can discover the whole surface from the binary alone.

## Install — see the release repo (single source of truth)

`dmtool` is distributed from the public release repo — **[`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases)**, which holds the **single source of install truth**. Both paths are documented there in full: the **agent plugin** (Claude Code / Codex — bundles the binary + the canonical skill, *including the post-install activation step*) and the **raw per-OS binary** (+ `SHA256SUMS`) for plain terminal use. (This source repo holds the plugin *source* under [`plugin/`](../plugin/) (Claude Code) and [`plugin-codex/`](../plugin-codex/) (Codex); they ship to the mirror at release.)

The binary is **EUPL-1.2**, because it embeds the A12 Kernel; see the mirror's `LICENSE`/`NOTICE`.

Package managers install the same release binaries and notice bundle, without a JVM:

- [Homebrew tap](https://github.com/mbackschat/homebrew-tap#dmtool): `brew install mbackschat/tap/dmtool`, for Apple Silicon on **macOS 27 or later** and Linux x64/ARM64.
- [Scoop bucket](https://github.com/mbackschat/scoop-bucket#dmtool): `scoop install mbackschat/dmtool` after adding the bucket, for Windows x64.

The runtime-evaluation verbs (`model eval`, `rule eval`, `model compute`, `model seed`) run on a **native-safe interpreter** on every target. It is the sole runtime eval engine, so they work in the native binary too.

## Serve it over MCP

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

Worked session: [`examples/cli-mcp.md`](../examples/cli-mcp.md). Full contract, the refusals, and what is deliberately not built: [`docs/MCP-SPEC.md`](MCP-SPEC.md) + [`docs/MCP-GAPS.md`](MCP-GAPS.md). Standing up a hosted endpoint for real: [`docs/MCP-HOSTING.md`](MCP-HOSTING.md).

## Discover the tool itself

```sh
dmtool manifest                       # every verb, one line each; `manifest <target> [<op>]` adds params + a worked example
dmtool operators                      # the DSL operator catalogue …
dmtool operators GroupFilled          #   … or one operator in full (meaning, examples, gotchas, OPPOSITE)
dmtool patterns                       # the validation-idiom catalogue (date-order, required-when, …) …
dmtool patterns date-order            #   … or one idiom in full (params, polarity, skeleton, example)
dmtool diagnostics                    # the diagnostic-code catalogue …
dmtool diagnostics RK_NO_SUCH_FIELD   #   … or one code in full (meaning + fix) — so a `code` you got back is explorable
dmtool schema rule add                # the exact JSON shape a verb consumes / returns
dmtool --version                      # this build + the kernel it targets (see "compatibility" below)
```

## Start a model from scratch

```sh
# a new, empty, kernel-valid model
dmtool model new --id orders --locale en_US --root Order > orders.dm.json

# …then populate it
dmtool -m orders.dm.json field add --group /Order --name Reference --kind STRING

# re-type a field in place (e.g. add a STRING pattern) — no delete-and-recreate
dmtool -m orders.dm.json field modify postal.spec.json
```

`model new` is the primary model-*originating* verb (no `-m` — it makes one); every other verb edits an existing `-m` model. What it emits is kernel-checked, so the next `-m` edit binds.

## JSON Schema & OpenAPI

Originate a model **from a JSON Schema or an OpenAPI document**, and export one back out — the same two verbs handle both, because OpenAPI's "Schema Object" *is* a dialect of JSON Schema:

<p align="center">
  <a href="../assets/infographics/json-schema-document-model.avif">
    <img src="../assets/infographics/json-schema-document-model.avif" alt="How schemakit maps JSON Schema and OpenAPI to and from A12 DocumentModels with explicit fidelity" width="720">
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
- Root names and repetition survive through explicit root metadata and native object/array carriers; model identity stays separate.
- Group index references round-trip as `x-a12-index-field-name` on the group object; see the [schema mapping contract](SCHEMAKIT-SPEC.md).
- **`--component`** picks the OpenAPI bundle's entry-point schema on import; **`--out-dir`** imports the *whole* bundle (one model per component, object cross-refs wired as mounts); **`--wrap-openapi`** emits the OpenAPI envelope on export.
- Import is **best-effort by default** — it maps as much as possible (even unbounded arrays and recursive `$ref`s, so nested structure isn't lost) and *flags every guess* in the stderr report; nothing is silent or blocked. `--strict` flips to omit-everything-uncertain; per-decision flags (`--array-of-scalar`, `--union`, `--number-scale`, …) tune each choice.
- Constraints with no field-config home become real a12 **rules** — `required`/`enum`/`pattern`, exclusive bounds, `multipleOf`, `const`, `dependentRequired`, `uniqueItems`, `oneOf`/`anyOf` discriminators, `if`/`then`/`else` (incl. multi-property), `not`, and `contains`/`minContains` — each kernel-checked (the full table is in the spec).

Array lower bounds require unconditional root-property applicability; otherwise import reports an omission and recommends keeping that constraint in JSON Schema validation. Empty-pattern composition is simplified without generating an executable empty A12 pattern. Object `contains` predicates preserve missing-property matches where supported and report ambiguous absent/empty string cases as omissions.

The dialects, the full mapping contract, and the import↔export asymmetry are tabulated in [docs/SCHEMAKIT-SPEC.md §4d/§4g](SCHEMAKIT-SPEC.md).

## Read & understand a model

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

# the inverse: which rules, computations and document uniqueness criteria use a field
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

## Review what changed — a semantic diff

When an agent has edited a model on your behalf, raw JSON diffs are unreadable. `model diff` compares two model files **structurally**: it parses and independently binds both, so a pure reformat is never reported. It reports changes with per-aspect deltas and a **review-impact tier**.

What each element kind compares:

- **Fields:** complete typed config, requiredness, enum values, metadata, and flags.
- **Groups:** repeatability, mounts, metadata, and mixed authored ordering.
- **Rules:** condition, error entity, localized messages, and metadata.
- **Computations:** targets, preconditions, operations, tolerances, suppressions, and messages.
- **Type definitions:** full typed config and metadata.
- **Authored includes/imports:** their purpose and referenced model, without conflating expanded content.
- **The single `CONFIG model` element:** identity/version, locales, decimal/time-zone/language/short-name settings, supported characters, base year, labels, comment, annotations, and roles. Set-like values are canonicalized, so harmless ordering creates no noise.

Each delta keeps deterministic `before`/`after` strings and adds tagged `beforeValue`/`afterValue` scalars or string lists for typed consumers.

Review impact follows *loosening > tightening*. Increasing a repetition cap, adding an enum value, expanding supported characters, or removing/loosening a rule is HIGH, while tightening is MEDIUM. Removing or retyping a field still used by a rule/computation is HIGH and names `REFERENCED_ELEMENT_REMOVED`/`RETYPE_AFFECTS_READERS`.

Multi-model two-file diffs resolve each side from `-w` or its own folder. `--since` resolves the base model and its dependencies at the Git ref, not from the edited worktree.

<p align="center">
  <a href="../assets/infographics/semantic-model-diff.avif">
    <img src="../assets/infographics/semantic-model-diff.avif" alt="How dmtool compares two model snapshots structurally, classifies semantic changes, and ranks review impact" width="720">
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

It's a **structural** semantic diff; the contract (change model, the full review-impact taxonomy, the rendering policy) is in [docs/MODEL-REVIEW-SPEC.md](MODEL-REVIEW-SPEC.md), open items in [docs/MODEL-REVIEW-GAPS.md](MODEL-REVIEW-GAPS.md).

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

## Explore a whole folder of models

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

`workspace roles` is a second lint, on **access control**, and it is **advisory, never a gate**. A12 models carry a `roles` header annotation naming who may access them; a workspace declares those roles in `auth/roles.yaml` and its users, with the roles each holds, in `auth/users.yaml`. The lint resolves this whole **RBAC triangle** and flags:

- an **undefined role**: a model gating to a role the file doesn't declare, which even the A12 model editor permits silently;
- an **undefined authority**: a user holding an undeclared role;
- an **unreachable model**: gated to roles no user holds;
- ungated models, a missing or duplicate roles file, and orphan roles.

Every finding is a **warning** (or a softer INFO), never an error, and the verb **always exits 0**. Access-control config is often a dev seed or owned by an external IdP, so the lint informs without ever blocking model work. Each finding carries a `fix` with the concrete remedy (see the [`cli-tour`](../examples/cli-tour.md) walkthrough).

For multi-file models (one model `include`s or imports another *by id*), set the **workspace root once** so every verb resolves references with no per-call flag:

```sh
dmtool -w ./models -m app.dm.json model check     # -w/--workspace: the dir to resolve includes/imports from
export DMTOOL_WORKSPACE=./models                      # …or set it once per shell session (the env fallback)
```

Precedence: `-w/--workspace` > `$DMTOOL_WORKSPACE` > the model's own folder (see [CLI-SPEC §3a](CLI-SPEC.md)). There's no separate `-w/--workspace` — one workspace concept resolves both includes and type-def imports.

## Scaffold from a validation pattern — polarity & error field baked in

A BA's recurring tasks (*required-when*, *date-order*, *mutually-exclusive*, *at-least-one-of*, *sum-of-line-items*) are exactly where an agent re-risks the two hardest **rule** traps every time. The **polarity** must be right, because an A12 rule fires on the *violation*. The **error field** must be valid: referenced and in scope, else `MVK_ERROR_FIELD_NOT_REFERENCED`.

The `patterns` verb removes the re-derivation. Each idiom is a **typed-DSL-backed** template, correct by construction, that scaffolds a kernel-valid artifact from a few parameters. Beyond the rule idioms there are two more kinds:

- **`tiered-amount`** is a **computation** idiom. It scaffolds a `kind:computation` artifact whose preconditions form a **mutually-exclusive, exhaustive table** (`< threshold` / `>= threshold`), the matching trap on the computation side.
- The **field-constraint** idioms (`bounded-number`, `formatted-string`, `value-set-enum`) scaffold the **field-level alternative** to a rule. The constraint lives in the field config (`number.min/max`, `string.pattern`, `enum.values`) that the `seeAlso` bridge points at.

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

The catalogue is **self-verifying**: every idiom's example is CI-instantiated and run through the real kernel, so a pattern that no longer scaffolds a valid rule fails the build. See [CLI-SPEC](CLI-SPEC.md) and the [API guide](API-GUIDE.md).

## Author & check — confirmed by the kernel

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

For intentionally scale-unknown division or field-exponent power, the computation spec uses A12's preamble member unchanged: `"additionalParserInformation":"@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)"` (`@UnterdrueckeWarnung(…)` in a German-condition model). Discover the complete, target-specific shape with `dmtool schema computation add`; rule-only keys are rejected on computations and computation-only keys are rejected on rules.

Diagnostics are **corrective**: each carries a stable `code`, a `where`, and a `fix` — and a built-in lint flags the classic **inverted-polarity** mistake automatically (and, on `computation add`, forbidden constructs + overlapping preconditions).

Adding many rules or edits in one go? **`apply`** (below) is the bulk path: one model load, ordered ops, one terminal kernel gate, one write, and atomic rollback.

`batch` merely dispatches independent calls. It continues after a child failure and never rolls back an earlier successful write. In ordinary mode, outer `ok` and exit 0/1 follow `data.allExitedZero`.

For a replayable static-kernel verdict matrix, give every supported child an `id` and add `batch --observations <file>`. After full dispatch plus the artifact write, observation mode returns outer `ok:true` and exit 0 independently of `allExitedZero`. It overwrites one deterministic, diagnostic-code-only artifact whose exact shape `schema batch` serves.

## Run the rules against real data (not just static checks)

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

The `--instance` document is **A12's own canonical Document JSON**: the shape the A12 kernel's document serializer reads and writes, so a document a real A12 application produced drops straight in.

- A root group name is a top-level key (no wrapper), and a repeating group is an array whose index is the repetition.
- A number or boolean is native JSON, and `null` is a field that is present with no value.
- Reading discards unknown keys and wrong-shaped entities and retains empty `{}` rows. An array holding anything but objects refuses the document, as A12's reader does.
- [Conversion and fatal errors](INTERPRETER-API-SPEC.md#43-conversion-and-fatal-input-errors) follow the shared codec.

`model seed` writes that same shape through the same codec, so a generated sample round-trips back into the verbs above by construction. `dmtool schema model eval` serves the contract.

This is the empirical check a static "is it valid?" can't give: it's how you confirm a rule's *polarity* on concrete data.

**`rule eval` scopes it to one rule** with a three-way verdict. It distinguishes a genuine **pass** from a rule the engine **suppressed** because a field it references is formally invalid.

- Its locator has two exclusive forms: a stored `<rulePathInModel>`, or a non-stored candidate given as `--condition` plus absolute `--field` (optional `--code`). Candidate evaluation uses the same focused envelope and leaves the model byte-identical.
- A **firing** also reports that message's own channels: `code`, `field`, `severity`, `type`, `message`, `referenced`, `fillToFix` for an omission, and `possiblyCausing`, A12's groups of the entities that may have caused it. These are the same keys `model eval` puts on a `messages[]` entry, so the message `type` (`VALUE_ERROR` / `OMISSION_ERROR`) is observable without persisting the candidate first. `dmtool schema rule eval` serves the shape.

**Partial validation.** `model eval --relevant '["/Order/Name","/Order/Lines[2]/Quantity"]'` selects partial or wizard-page validation, and global fields are added automatically.

- `[*]` (or `[0]`) means every repetition, and `[n]` selects one row.
- An **omitted** index on a repeatable segment is refused rather than guessed. In A12 that spelling means repetition 1, and reading it as "all" would silently widen the check.
- Starred value-list extent accepts complete declared-slot coverage as well as wildcards; ordinary aggregate gates remain distinct.

**The form-engine flow is the default.** `model eval` and `rule eval` run *compute → apply → validate*, so a required-or-referenced **computed** field is filled by its computation before validation, matching the running app. The kernel keeps `validateFull` and `compute` separate, so a bare validate would false-fail a computed field. `--no-computations` opts down to the bare `validateFull` over stored values.

These verbs run on a **native-safe interpreter** on every target, the sole runtime eval engine. It reproduces the kernel's runtime semantics, verified rule-for-rule, with the A12 kernel as the differential oracle behind that verification.

`model eval` preserves message occurrences: a numeric parallel join may visit the same matched rows twice when its index declarations render one value differently (`5` versus `5.00`). `data.messages` retains both occurrences; `data.fired` lists distinct codes. The [runtime tour](../examples/cli-runtime.md) demonstrates this distinction.

Partial parallel validation separates rule dispatch from operand relevance: combining error-field selectors can admit a join while unselected inputs remain unknown. See the [partial-validation contract](CLI-SPEC.md) and its [kernel controls](KERNEL-FINDINGS.md#kf544). Message addresses preserve index associations independently of readable values; the [compatibility contract](INTERPRETER-FINDINGS.md#if388) covers duplicate keys and invalid singleton defaults.

`FirstFilledValue` retains missing nested prefixes: an omitted inner group before the selected value can make a firing an OMISSION error, while the same empty branch after it preserves VALUE. The [runtime tour](../examples/cli-runtime.md) shows both documents.

Partial validation decides group facts conservatively:

- It can prove a clean, empty parent group empty when selected descendants cover all declared finite repetition combinations and every descendant field, independently of held rows.
- Starred group predicates retain uncertainty for uncovered missing positions; a relevant positive row can still establish presence.
- Partial group counts keep structural errors separate from invalid values. An excess descendant can report an over-repetition error while an ancestor count remains decidable, and an in-capacity malformed value can suppress that count.
- Nonempty partial selections still report structural errors outside the selected fields, including present null/empty cells.

The [runtime tour](../examples/cli-runtime.md) demonstrates the distinction.

`Today` and `Now` read the current instant in the model's time zone, as A12 does; `--clock <instant>` (e.g. `2026-10-14T10:00:00Z`) pins it on `model eval`, `rule eval` and `model compute` for a reproducible run. See the [clock contract](CLI-SPEC.md).

**Models that use custom constructs** still run:

- A `CustomFieldType` whose format is declarative is validated with `--predefined-types <file>`.
- For the *imperative* tail, a `CustomCondition` or a checksum custom type, point `dmtool` at the project's own JS with `--custom-conditions-js` / `--custom-field-types-js`. It runs in a persistent Node worker.
- Anything still unhandled is **surfaced** under `data.unsupported` rather than silently passed; `--strict-custom` turns that into a failure.
- `model compute` accepts the same custom type registries and checks reached computed targets. There, `--strict-custom` also refuses missing target handlers while retaining the result data.

Walkthrough: [`examples/cli-custom-types.md`](../examples/cli-custom-types.md).

> **The interpreter is kernel-exact *and* fast** — clean-room, no kernel code, verified rule-for-rule against the real kernel. The full story (library use on JVM + JS, the verification estate, the measured performance): [the interpreter section](../README.md#the-interpreter--kernel-free-runtime-evaluation) of the project README.

## Calibrate with performance profiles

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

The profile records the model shapes that drive evaluation cost (structure and repeatability, rule/computation work, and field wiring) without carrying domain identifiers, literals, messages, or source expressions. Includes and imported type definitions resolve through the same `-w/--workspace` boundary as other model reads.

- **`profile synthesize`** is the inverse artifact step. It accepts the bare artifact or an extraction envelope and uses typed dmkits to emit a deterministic own-domain DM-JSON model. Stdout carries the model, unless `-o` writes it and returns an applied receipt. Synthesis refuses profiles with unbounded repeatable groups instead of silently substituting a cheaper workload.
- **`profile compare`** accepts bare artifacts or extraction envelopes and returns the overall match plus every `EXACT`/`AT_LEAST`/`BUCKET`/`INFORMATIVE` axis in `data`. The verdict is `data.matches`, and a mismatch exits 1. No consistency gate arbitrates between two profile artifacts, so the envelope carries no `valid`/`verification`: the exit code and `data.matches` are the whole answer.

All three contracts are discoverable from `dmtool schema profile extract`, `dmtool schema profile synthesize`, and `dmtool schema profile compare`; the synthesis contract describes both its DM-JSON artifact and its `-o` result receipt. There is no V1/V2 or `profileVersion` mode. The implemented contract is [`MODEL-PROFILE-SPEC.md`](MODEL-PROFILE-SPEC.md).

## Edit, with a preview diff

```sh
# Re-express an existing rule; --dry-run PREVIEWS the change (writes nothing):
dmtool -m order.dm.json rule modify /Order/DeliveryNotBeforeOrder \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0' --dry-run
```

## Edit structure — with a safe-delete gate

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
  --reference pricelist \
  --parent /Order --name Prices --exclude-rules -w ./models

# declare a document uniqueness criterion: one entry in A12's stored shape, which `criterion read` returns
dmtool -m order.dm.json criterion add order-number-key.json
dmtool -m order.dm.json criterion remove UniqueOrderNumber   # frees its fields for `field remove`

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

## Both kits at once, atomically — the `apply` session

The CLI face of the unified `EditSession` ([the library's one-model-one-gate session](../edit/README.md#in-practice)) — **modelkit structure edits and rulekit rule edits in one transaction**:

```sh
# Read → ordered surgery (structure via modelkit + rules via rulekit) → ONE gate → ONE write, rollback on failure:
dmtool -m order.dm.json apply <ops.json>
```

`apply` runs a list of op-records on a single in-memory session. For example: *add field `Discount`*, then *re-type `Plz` to add a pattern*, then *rename `Note`→`Memo`* rewriting its references, then *add a rule that references `Discount`*. It tolerates transient-invalid intermediate states, gates once at the end, and **rolls back atomically** if any op fails, reporting which op and why (with did-you-mean correctives for typos).

The op-set spans add·**modify (incl. `field` re-type)**·remove·read **plus the reference-rewriting refactors**: field/group rename·move and rule/typedef rename. Each refactor runs its safety gate at its turn, so a refused refactor rolls the whole sequence back. See [`cli-apply`](../examples/cli-apply.md) and the gate contract in [CLI-SPEC §6/§7](CLI-SPEC.md).

## Works on real, multi-file apps

```sh
dmtool -m app/storefront.dm.json model check -w lib   # resolves include'd / typedef'd models
```

## Version & compatibility — tolerant, but explicit

This build targets **A12 Kernel 31.3.1** (A12 Tools distribution **2026.06-ext2**). `dmtool --version` and `manifest.version` report that, alongside the base source revision, the clean/dirty build state, the kernel actually on the classpath, the operator-catalog floor, and the document-model reference version. A `CLEAN` stamp identifies the exact committed source; `DIRTY` says the artifact differs from that revision.

Loading a model authored for a different format is handled with the kernel as the authority:

- an older, compatible model loads with an informational `RK_MODEL_VERSION_SKEW`;
- an incompatible one fails fast with `RK_MODEL_VERSION_INCOMPATIBLE`;
- writing a model back never bumps its version.

A second, A12-Tools-only packaging invariant is made *readable* rather than policed. That distribution requires a model's file basename to equal its `header.id`, while the kernel does not care, so `model info` reports `fileName` and `fileNameMatchesId` beside `id` and leaves the call to you.

## See it all, runnable

The [`examples/`](../examples/) directory has **verifiable** walkthroughs of every verb — each is documentation *and* a regression test (re-run against the live CLI):

| Demo | Shows |
|---|---|
| [`cli-tour`](../examples/cli-tour.md) | read one model: `model check` / `model describe` / `model read` · `model usage` · `rule read` / `rule explain` / `rule format` / `rule check` · `field read` / `group read` / `config read` |
| [`cli-discover`](../examples/cli-discover.md) | the self-describing surface: `manifest` · `operators` · `patterns` · `diagnostics` · `schema` · `profile extract`/`synthesize`/`compare` |
| [`cli-review`](../examples/cli-review.md) | review & comprehend: `model report` (the glossed catalog) · `model diff` (the impact-ranked semantic diff, incl. typedefs, model configuration, and `POLARITY_INVERTED`) |
| [`cli-workspace`](../examples/cli-workspace.md) | multi-file models: `model info` · `workspace list` / `workspace graph` / `workspace roles` |
| [`cli-edit-loop`](../examples/cli-edit-loop.md) | author/modify rules & computations, `where-used`, `batch`, `;;` comments, the re-express loop |
| [`cli-structure-edit`](../examples/cli-structure-edit.md) | fields/groups/typedefs/includes/config + the safe-delete gate, the safety-gated refactors, group templates, `meta` |
| [`cli-apply`](../examples/cli-apply.md) | the atomic multi-op session + rollback + correctives + in-session refactors |
| [`cli-runtime`](../examples/cli-runtime.md) | `model eval` / `rule eval` / `model compute` against document instances, `model seed` to generate one |
| [`cli-custom-types`](../examples/cli-custom-types.md) | custom field types & conditions: `--predefined-types`, the JS escape, `data.unsupported`, `--strict-custom` |
| [`cli-version`](../examples/cli-version.md) | `--version`, `manifest.version`, the model-version policy |
| [`cli-jsonschema`](../examples/cli-jsonschema.md) | `model import-jsonschema` / `model export-jsonschema` — JSON Schema & OpenAPI, both directions |

The full CLI contract: [`docs/CLI-SPEC.md`](CLI-SPEC.md).
