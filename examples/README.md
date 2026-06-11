# examples — verifiable CLI demos

Executable, `verify`-able demos of the `dmtool` CLI (authored with [showboat](https://github.com/simonw/showboat)). Each `*.md` here is documentation *and* a doc-drift test.

**The method — how to run, verify, and extend these — lives in [`docs/CLI-TOUR-SPEC.md`](../docs/CLI-TOUR-SPEC.md).** Quick check:

```bash
uvx showboat@0.6.1 verify examples/cli-tour.md   # exit 0 = output still matches the live CLI
```

## Demos in this folder

| File | What it shows |
|---|---|
| [`cli-tour.md`](cli-tour.md) | A tour of the **read/inspect** surface on the `order-ruled` fixture. Opens with discovery (`manifest` → the target-first verb tree) and the **model** it guards (`doc describe` → fields + kinds), then walks `doc validate` · `rule read` · `rule explain` (the structural tree) · `rule check` (with the lint backstop) — each as a command, its captured envelope, and a "what just happened" note. |
| [`cli-edit-loop.md`](cli-edit-loop.md) | The **write/edit** surface on the `subscription-computed` fixture: `where-used` · `computation explain` · `computation modify` (`--dry-run` previews, then write) · `rule add` · `doc describe --include-dir` (multi-file) · `batch` · `operators`/`schema` (self-description). The companion to the tour — the F8 modify-by-re-express loop end to end (its `--dry-run` preview vs default in-place write, shown via the envelope's `outcome`/`written`). |
| [`cli-structure-edit.md`](cli-structure-edit.md) | The **structure-edit** surface on the `order-ruled` fixture: `field add` · `group add` (`--repeatable`) · `field remove` (the **safe-delete gate** — a referenced field is refused with the blocking rules surfaced under `data.referencedBy`, then `--cascade` removes them together; never a silent cascade). |
