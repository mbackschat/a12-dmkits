# examples — verifiable CLI demos

Executable, `verify`-able demos of the rulekit CLI (authored with [showboat](https://github.com/simonw/showboat)). Each `*.md` here is documentation *and* a doc-drift test.

**The method — how to run, verify, and extend these — lives in [`docs/CLI-TOUR-SPEC.md`](../docs/CLI-TOUR-SPEC.md).** Quick check:

```bash
uvx showboat@0.6.1 verify examples/cli-tour.md   # exit 0 = output still matches the live CLI
```

## Demos in this folder

| File | What it shows |
|---|---|
| [`cli-tour.md`](cli-tour.md) | A tour of the **read/inspect** surface on the `order-ruled` fixture. Opens with the **model** it guards (fields + kinds), then walks `manifest` · `describe` · `validate` · `read` · `explain` (the structural tree) · `check` (with the lint backstop) — each as a command, its captured output, and a "what just happened" note. |
| [`cli-edit-loop.md`](cli-edit-loop.md) | The **write/edit** surface on the `subscription-computed` fixture: `where-used` · `explain --computation` · `modify` (`--dry-run` previews, then write) · `add` · `--include-dir` (multi-file) · `batch` · `operators`/`schema` (self-description). The companion to the tour — the F8 modify-by-re-express loop end to end via `modify` (its `--dry-run` preview vs default write). |
| [`cli-structure-edit.md`](cli-structure-edit.md) | The **structure-edit** surface on the `order-ruled` fixture: `add-field` · `add-group` (`--repeatable`) · `remove` — including the **safe-delete gate** (a referenced field is refused with the blocking rules surfaced, then `--cascade` removes them together) and a final re-`validate`. |
