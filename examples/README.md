# examples — verifiable CLI demos

Executable, `verify`-able demos of the `dmtool` CLI (authored with [showboat](https://github.com/simonw/showboat)). Each `*.md` here is documentation *and* a doc-drift test.

**The method — how to run, verify, and extend these — lives in [`docs/CLI-TOUR-SPEC.md`](../docs/CLI-TOUR-SPEC.md).** Quick check:

```bash
uvx showboat@0.6.1 verify examples/cli-tour.md   # exit 0 = output still matches the live CLI
```

## Demos in this folder

Six thematic demos that together exercise **every verb** + the cross-cutting features (the result envelope, `--dry-run`/`-o`, `--include-dir`, the safe-delete gate, op-arg/cross-type correctives, the schema contracts, the version surface):

| File | Surface | Verbs it shows |
|---|---|---|
| [`cli-tour.md`](cli-tour.md) | **discover + read** | `manifest` · `operators`(+`<id>`) · `diagnostics`(+`<code>`) · `schema result`/`<target> <op>` · `doc describe`/`doc read`/`doc validate` · `rule read`/`rule explain`/`rule check` · `field read`/`group read`/`config read` |
| [`cli-edit-loop.md`](cli-edit-loop.md) | **rulekit write/edit** | `where-used` · `rule add`/`rule modify`/`rule remove` · `computation add`/`read`/`explain`/`modify`/`remove` · `batch` (the F8 re-express loop; `--dry-run` preview vs in-place write via the envelope's `outcome`/`written`) · **`;;` comments** (the `comment` key / `--comment`, preserved across a comment-less `modify`) |
| [`cli-structure-edit.md`](cli-structure-edit.md) | **modelkit structure** | `field add`/`field read`/`field remove` (the **safe-delete gate** → `--cascade`) · `group add`/`group read`/`group remove` · `typedef add`/`read`/`remove` · `include add`/`read`/`remove` (`--include-dir`) · `config read`/`config modify` · `export` · the **safety-gated refactors** (CLI-SPEC §6): `typedef rename`/`extract`/`inline`, reference-preserving `field`/`group rename` and `field`/`group move`, `group extract` (group→include) + `include inline` |
| [`cli-apply.md`](cli-apply.md) | **the `apply` session** | `apply` — atomic multi-op, rollback, op-arg corrective (`RK_UNKNOWN_ARG`/`RK_UNKNOWN_OP`), cross-type corrective, read mid-sequence, **in-session refactors** (a rename rewrites references atomically) · `schema apply` (the op-record frame) |
| [`cli-runtime.md`](cli-runtime.md) | **runtime evaluation** | `doc eval` (which rules *fire* on a document instance — the empirical polarity check) · `doc compute` (a computed field's value, incl. empty-as-0) |
| [`cli-version.md`](cli-version.md) | **versions & compatibility** | `--version` · `manifest.version` (machine-readable) · the model-version load policy — tolerant `RK_MODEL_VERSION_SKEW` vs fail-fast `RK_MODEL_VERSION_INCOMPATIBLE` · write-back preserves the version (no bump) |
