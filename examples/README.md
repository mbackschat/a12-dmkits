# examples — verifiable CLI demos

Executable, `verify`-able demos of the `dmtool` CLI (authored with [showboat](https://github.com/simonw/showboat)). Each `*.md` here is documentation *and* a doc-drift test.

**The method — how to run, verify, and extend these — lives in [`docs/CLI-TOUR-SPEC.md`](../docs/CLI-TOUR-SPEC.md).** Quick check (from the repo root, with the repo `bin/` on PATH so the bare `dmtool` resolves):

```bash
export PATH="$PWD/bin:$PATH"
uvx showboat@0.6.1 verify examples/cli-tour.md   # exit 0 = output still matches the live CLI
```

**Before writing or rewrapping a demo, read [`CLI-TOUR-SPEC.md` §"Reader-facing formatting"](../docs/CLI-TOUR-SPEC.md#reader-facing-formatting--three-rules-each-with-its-guard):** break long commands at the pipes, carry showboat's header, and never fence a command that does not return. Two of the three are enforced by `scripts/verify-demos.sh`.

The demos use the bare `dmtool` and read their sample models from [`models/`](models/) — the demos' **own** fixtures, kept separate from the JUnit test fixtures (`cli/src/test/resources/models/`): same kind of model, different purpose (user-facing showcase vs. internal test coverage). Both the demos and `models/` are published to the release mirror, so the walkthroughs run there too.

The [`profiles/`](profiles/) directory carries the three approved domain-blind performance-calibration artifacts used by the profile-driven synthesis and interpreter scalability workflow. They are current-format `profile′` outputs from own-domain synthesized models, not copies or profiles of private source models.

**What a green `verify-demos.sh` proves, and what it does not.** The gate proves every documented command still
produces the documented output — it is not a coverage estate, and the fixtures decide its reach. That reach is
worth knowing before reading a green run as reassurance: measured on 2026-08-15, the committed models in
[`models/`](models/) carried exactly **one** entity-list quantifier between them and **no group operand at all**,
so the whole typed group-scope surface passed the gate green whatever it did. [`cli-group-operands.md`](cli-group-operands.md)
closes that particular hole by authoring the shapes at run time rather than by adding a fixture rule. The general
point stands: when a change lands in an area, check whether any demo actually exercises it instead of trusting the
green.

## Demos in this folder

Thematic demos that together exercise **every verb** + the cross-cutting features (the result envelope, `--dry-run`/`-o`, `-w/--workspace`, the safe-delete gate, op-arg/cross-type correctives, the schema contracts, the version surface):

| File | Surface | Verbs it shows |
|---|---|---|
| [`cli-tour.md`](cli-tour.md) | **read one model** | `model check`/`model describe`/`model read` · `model usage` (the whole-model reference audit) · `rule read`/`rule explain`/`rule format` (kernel-canonical text + EN↔DE)/`rule check` · `field read`/`group read`/`config read` |
| [`cli-discover.md`](cli-discover.md) | **the self-describing surface** | `manifest` (every verb) · `operators`(+`<id>`) · `patterns`(+`<id>`/scaffold) · `diagnostics`(+`<code>`) · `schema result`/`schema <target> <op>` · `profile extract`/`synthesize`/`compare` |
| [`cli-group-operands.md`](cli-group-operands.md) | **a rule operand that is a GROUP** | `group read` · `rule check` (the star's per-row vs across-rows meaning, and the negative-quantifier refusal) · `rule add`/`field add`/`model eval` (the rule follows the group when it gains a field) · `where-used` vs `model usage` (exactness vs reachability, and why only one answers "is this safe to delete?") |
| [`cli-review.md`](cli-review.md) | **review & comprehend** | `model report` (the glossed comprehension catalog — every rule in plain language + polarity + its stored message + the dead-field set) · `model diff` (the independently workspace-bound, structural, impact-ranked semantic diff of complete field/group/rule/computation/typedef/config envelopes plus authored include/import references — incl. typed delta values, reader-sensitive removals/retypes, group repeatability/order, first-class `COMPUTATION_CHANGED`, **`POLARITY_INVERTED`**, and classified non-inverted rule edits) · the `--text` impact-sorted human view |
| [`cli-workspace.md`](cli-workspace.md) | **multi-file & workspaces** | `model info` (one model's references resolved to files) · `workspace list` (cross-model "ls" — includes/imports resolved to files) · `workspace graph` (the sub/supertype inheritance hierarchy) · `workspace roles` (the access-control lint — resolve each model's `roles` against roles.yaml) |
| [`cli-edit-loop.md`](cli-edit-loop.md) | **rulekit write/edit** | `where-used` · `rule add`/`rule modify`/`rule remove` · `computation add`/`read`/`explain`/`modify`/`remove` · `batch` (the F8 re-express loop; `--dry-run` preview vs in-place write via the envelope's `outcome`/`written`) · **`;;` comments** (the `comment` key / `--comment`, preserved across a comment-less `modify`) |
| [`cli-public-contract.md`](cli-public-contract.md) | **packaged cold-start journey** | `model new` → atomic `apply` setup → `computation add --dry-run` byte identity → the published **persistence scope** behind that `NO_WRITE` (`schema … contractVocabulary`) → persisted `computation add`/`read` → `model check` → interpreter-backed `model compute` |
| [`cli-structure-edit.md`](cli-structure-edit.md) | **modelkit structure** | `field add`/`field read`/`field remove` (the **safe-delete gate** → `--cascade`) · `group add`/`group read`/`group remove` · `typedef add`/`modify`/`read`/`remove` · `include add`/`read`/`remove` (`-w/--workspace`) · `config read`/`config modify` (incl. `--comment`) · `export` · the **safety-gated refactors** (CLI-SPEC §6): `typedef rename`/`extract`/`inline`, reference-preserving `field`/`group rename` and `field`/`group move`, `group extract` (group→include) + `include inline` · **group templates** `group multiselect`/`group attachment` · **element metadata** `meta <ref>` (the sole post-creation metadata writer; field·group·rule·computation·type-def reads/edits) |
| [`cli-apply.md`](cli-apply.md) | **the `apply` session** | `apply` — atomic multi-op, rollback, op-arg corrective (`RK_UNKNOWN_ARG`/`RK_UNKNOWN_OP`), cross-type corrective, read mid-sequence, generic **`meta modify`** (the transactional metadata route for every element kind), **in-session refactors** (a rename rewrites references atomically) · `schema apply` (the op-record frame) |
| [`cli-runtime.md`](cli-runtime.md) | **runtime evaluation** | `model eval` (which rules *fire* on a document instance — the empirical polarity check) · `rule eval` (one rule, three-way verdict: fired / passed / **suppressed**) · `model compute` (a computed field's value, incl. empty-as-0) · `model seed` (generate a best-effort model-derived sample candidate — round-trips into the verbs above) |
| [`cli-custom-types.md`](cli-custom-types.md) | **custom field types & conditions** | `model eval --predefined-types` (validate a declarative custom field type — a bad value fires `customFieldTypeInvalid`) · `data.unsupported` (visible-ignore — what the engine couldn't evaluate, never a silent pass) · `model eval --strict-custom` (fail instead of degrade) · `rule eval` on a `CustomCondition` rule (the **`unsupported`** verdict) · **the JS escape** `--custom-field-types-js` / `--custom-conditions-js` (run the project's own JS impls via a persistent Node worker; verifying this demo needs `node` on PATH) |
| [`cli-mcp.md`](cli-mcp.md) | **the MCP server** — for a host that cannot run a binary | `mcp` — `server/discover` (the revision served, cacheable) · `tools/list` (the derived surface: one tool per *target × effect × return shape*, `readOnlyHint` true only where every op is) · `tools/call` carrying the model's DM-JSON in and the edited model back out · the refusals: an older protocol revision, and any caller-supplied path |
| [`cli-version.md`](cli-version.md) | **versions & compatibility** | `--version` · `manifest.version` (full Git revision + clean/dirty state, machine-readable) · the model-version load policy — tolerant `RK_MODEL_VERSION_SKEW` vs fail-fast `RK_MODEL_VERSION_INCOMPATIBLE` · write-back preserves the version (no bump) |
| [`cli-jsonschema.md`](cli-jsonschema.md) | **JSON Schema & OpenAPI ⇄ model** | `model import-jsonschema` (JSON Schema / **OpenAPI** 3.0·3.1·3.2, JSON or **YAML** → kernel-valid model; the `MappingProfile` flags + omit-never-silent report; **constraints → synthesized a12 rules**; auto-detected `--dialect`; `--out-dir` imports a whole OpenAPI doc as **many models wired by mounts**) · `model export-jsonschema` (model → JSON Schema, best-effort: structure native, rules → `x-a12-rule` carriage, DATE/CONFIRM → `string`; `--wrap-openapi`) — the import/export **asymmetry** in action |
