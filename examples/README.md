# examples — verifiable CLI demos

Executable, `verify`-able demos of the `dmtool` CLI (authored with [showboat](https://github.com/simonw/showboat)). Each `*.md` here is documentation *and* a doc-drift test.

**The method — how to run, verify, and extend these — lives in [`docs/CLI-TOUR-SPEC.md`](../docs/CLI-TOUR-SPEC.md).** Quick check (from the repo root, with the repo `bin/` on PATH so the bare `dmtool` resolves):

```bash
export PATH="$PWD/bin:$PATH"
uvx showboat@0.6.1 verify examples/cli-tour.md   # exit 0 = output still matches the live CLI
```

The demos use the bare `dmtool` and read their sample models from [`models/`](models/) — the demos' **own** fixtures, kept separate from the JUnit test fixtures (`cli/src/test/resources/models/`): same kind of model, different purpose (user-facing showcase vs. internal test coverage). Both the demos and `models/` are published to the release mirror, so the walkthroughs run there too.

## Demos in this folder

Six thematic demos that together exercise **every verb** + the cross-cutting features (the result envelope, `--dry-run`/`-o`, `-w/--workspace`, the safe-delete gate, op-arg/cross-type correctives, the schema contracts, the version surface):

| File | Surface | Verbs it shows |
|---|---|---|
| [`cli-tour.md`](cli-tour.md) | **discover + read** | `manifest` · `operators`(+`<id>`) · `patterns`(+`<id>`/scaffold) · `diagnostics`(+`<code>`) · `schema result`/`<target> <op>` · `model info` (the header dashboard — identity + super/subtypes + resolved references) · `model describe`/`model read`/`model usage` (the whole-model reference audit)/`model validate` · `rule read`/`rule explain`/`rule format` (kernel-canonical text + EN↔DE)/`rule check` · `field read`/`group read`/`config read` · `workspace list` (cross-model "ls" — includes/type-def imports resolved to files) · `workspace graph` (the sub/supertype inheritance hierarchy) · `workspace roles` (the access-control lint — resolve each model's `roles` against roles.yaml) |
| [`cli-edit-loop.md`](cli-edit-loop.md) | **rulekit write/edit** | `where-used` · `rule add`/`rule modify`/`rule remove` · `computation add`/`read`/`explain`/`modify`/`remove` · `batch` (the F8 re-express loop; `--dry-run` preview vs in-place write via the envelope's `outcome`/`written`) · **`;;` comments** (the `comment` key / `--comment`, preserved across a comment-less `modify`) |
| [`cli-structure-edit.md`](cli-structure-edit.md) | **modelkit structure** | `field add`/`field read`/`field remove` (the **safe-delete gate** → `--cascade`) · `group add`/`group read`/`group remove` · `typedef add`/`read`/`remove` · `include add`/`read`/`remove` (`-w/--workspace`) · `config read`/`config modify` (incl. `--comment`) · `export` · the **safety-gated refactors** (CLI-SPEC §6): `typedef rename`/`extract`/`inline`, reference-preserving `field`/`group rename` and `field`/`group move`, `group extract` (group→include) + `include inline` · **group templates** `group multiselect`/`group attachment` · **element metadata** `meta <ref>` (label/describe/annotate/read field·group·rule·type-def) |
| [`cli-apply.md`](cli-apply.md) | **the `apply` session** | `apply` — atomic multi-op, rollback, op-arg corrective (`RK_UNKNOWN_ARG`/`RK_UNKNOWN_OP`), cross-type corrective, read mid-sequence, **in-session refactors** (a rename rewrites references atomically) · `schema apply` (the op-record frame) |
| [`cli-runtime.md`](cli-runtime.md) | **runtime evaluation** | `model eval` (which rules *fire* on a document instance — the empirical polarity check) · `rule test` (one rule, three-way verdict: fired / passed / **suppressed**) · `model compute` (a computed field's value, incl. empty-as-0) |
| [`cli-version.md`](cli-version.md) | **versions & compatibility** | `--version` · `manifest.version` (machine-readable) · the model-version load policy — tolerant `RK_MODEL_VERSION_SKEW` vs fail-fast `RK_MODEL_VERSION_INCOMPATIBLE` · write-back preserves the version (no bump) |
