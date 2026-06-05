# examples — verifiable CLI demos

Executable, `verify`-able demos of the rulekit CLI (authored with [showboat](https://github.com/simonw/showboat)). Each `*.md` here is documentation *and* a doc-drift test.

**The method — how to run, verify, and extend these — lives in [`docs/CLI-TOUR-SPEC.md`](../docs/CLI-TOUR-SPEC.md).** Quick check:

```bash
uvx showboat@0.6.1 verify examples/cli-tour.md   # exit 0 = output still matches the live CLI
```

## Demos in this folder

| File | What it shows |
|---|---|
| [`cli-tour.md`](cli-tour.md) | A tour of the verb surface on the `order-ruled` fixture. Opens with the **model** it guards (fields + kinds), then walks `validate` · `ingest` · `explain` (the structural tree) · `check` (with the lint backstop) — each as a command, its captured output, and a "what just happened" note. |
