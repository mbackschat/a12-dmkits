# examples — verifiable CLI demos

Executable, `verify`-able demos of the `dmtool` CLI (authored with [showboat](https://github.com/simonw/showboat)). Each `*.md` here is documentation *and* a doc-drift test.

**The method — how to run, verify, and extend these — lives in [`docs/CLI-TOUR-SPEC.md`](../docs/CLI-TOUR-SPEC.md).** Quick check (from the repo root, with the repo `bin/` on PATH so the bare `dmtool` resolves):

```bash
export PATH="$PWD/bin:$PATH"
uvx showboat@0.6.1 verify examples/cli-tour.md   # exit 0 = output still matches the live CLI
```

The demos use the bare `dmtool` and read their sample models from [`models/`](models/) — the demos' **own** fixtures, kept separate from the JUnit test fixtures (`cli/src/test/resources/models/`): same kind of model, different purpose (user-facing showcase vs. internal test coverage). Both the demos and `models/` are published to the release mirror, so the walkthroughs run there too.

## Demos in this folder

Thematic demos that together exercise **every verb** + the cross-cutting features (the result envelope, `--dry-run`/`-o`, `-w/--workspace`, the safe-delete gate, op-arg/cross-type correctives, the schema contracts, the version surface):

| File | Surface | Verbs it shows |
|---|---|---|
| [`cli-tour.md`](cli-tour.md) | **read one model** | `model check`/`model describe`/`model read` · `model usage` (the whole-model reference audit) · `rule read`/`rule explain`/`rule format` (kernel-canonical text + EN↔DE)/`rule check` · `field read`/`group read`/`config read` |
| [`cli-discover.md`](cli-discover.md) | **the self-describing surface** | `manifest` (every verb) · `operators`(+`<id>`) · `patterns`(+`<id>`/scaffold) · `diagnostics`(+`<code>`) · `schema result`/`schema <target> <op>` |
| [`cli-review.md`](cli-review.md) | **review & comprehend** | `model report` (the glossed comprehension catalog — every rule in plain language + polarity + its stored message + the dead-field set) · `model diff` (the structural, risk-ranked semantic diff of two models — incl. **`POLARITY_INVERTED`**: a silent polarity flip detected from the rule ASTs, and the non-inverted change classified) · the `--text` risk-sorted human view |
| [`cli-workspace.md`](cli-workspace.md) | **multi-file & workspaces** | `model info` (one model's references resolved to files) · `workspace list` (cross-model "ls" — includes/imports resolved to files) · `workspace graph` (the sub/supertype inheritance hierarchy) · `workspace roles` (the access-control lint — resolve each model's `roles` against roles.yaml) |
| [`cli-edit-loop.md`](cli-edit-loop.md) | **rulekit write/edit** | `where-used` · `rule add`/`rule modify`/`rule remove` · `computation add`/`read`/`explain`/`modify`/`remove` · `batch` (the F8 re-express loop; `--dry-run` preview vs in-place write via the envelope's `outcome`/`written`) · **`;;` comments** (the `comment` key / `--comment`, preserved across a comment-less `modify`) |
| [`cli-structure-edit.md`](cli-structure-edit.md) | **modelkit structure** | `field add`/`field read`/`field remove` (the **safe-delete gate** → `--cascade`) · `group add`/`group read`/`group remove` · `typedef add`/`read`/`remove` · `include add`/`read`/`remove` (`-w/--workspace`) · `config read`/`config modify` (incl. `--comment`) · `export` · the **safety-gated refactors** (CLI-SPEC §6): `typedef rename`/`extract`/`inline`, reference-preserving `field`/`group rename` and `field`/`group move`, `group extract` (group→include) + `include inline` · **group templates** `group multiselect`/`group attachment` · **element metadata** `meta <ref>` (label/describe/annotate/read field·group·rule·type-def) |
| [`cli-apply.md`](cli-apply.md) | **the `apply` session** | `apply` — atomic multi-op, rollback, op-arg corrective (`RK_UNKNOWN_ARG`/`RK_UNKNOWN_OP`), cross-type corrective, read mid-sequence, **in-session refactors** (a rename rewrites references atomically) · `schema apply` (the op-record frame) |
| [`cli-runtime.md`](cli-runtime.md) | **runtime evaluation** | `model eval` (which rules *fire* on a document instance — the empirical polarity check) · `rule eval` (one rule, three-way verdict: fired / passed / **suppressed**) · `model compute` (a computed field's value, incl. empty-as-0) · `model seed` (generate a valid sample instance — round-trips into the verbs above) |
| [`cli-custom-types.md`](cli-custom-types.md) | **custom field types & conditions** | `model eval --predefined-types` (validate a declarative custom field type — a bad value fires `customFieldTypeInvalid`) · `data.unsupported` (visible-ignore — what the engine couldn't evaluate, never a silent pass) · `model eval --strict-custom` (fail instead of degrade) · `rule eval` on a `CustomCondition` rule (the **`unsupported`** verdict) · **the JS escape** `--custom-field-types-js` / `--custom-conditions-js` (run the project's own JS impls via a persistent Node worker) |
| [`cli-version.md`](cli-version.md) | **versions & compatibility** | `--version` · `manifest.version` (machine-readable) · the model-version load policy — tolerant `RK_MODEL_VERSION_SKEW` vs fail-fast `RK_MODEL_VERSION_INCOMPATIBLE` · write-back preserves the version (no bump) |
| [`cli-jsonschema.md`](cli-jsonschema.md) | **JSON Schema & OpenAPI ⇄ model** | `model import-jsonschema` (JSON Schema / **OpenAPI** 3.0·3.1·3.2, JSON or **YAML** → kernel-valid model; the `MappingProfile` flags + omit-never-silent report; **constraints → synthesized a12 rules**; auto-detected `--dialect`; `--out-dir` imports a whole OpenAPI doc as **many models wired by mounts**) · `model export-jsonschema` (model → JSON Schema, best-effort: structure native, rules → `x-a12-rule` carriage, DATE/CONFIRM → `string`; `--wrap-openapi`) — the import/export **asymmetry** in action |
