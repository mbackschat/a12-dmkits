# Conformance corpus

A portable, kernel-independent record of A12 document-model **runtime semantics as data**. Each file under [`cases/`](cases/) is one case: a reference to a document model, one document instance, one operation, and the kernel-verified expected outcome. The models themselves are **standalone DM-JSON files** under [`models/`](models/) that the cases share. Any engine claiming kernel-compatible evaluation — the in-repo interpreter (JVM and JS), a kernel codegen strategy, or a third-party reimplementation — demonstrates it by replaying the corpus and matching the expectations.

- **The contract** (case format, placement/pointer semantics, signatures, verdict rules): [`docs/CONFORMANCE-CORPUS-SPEC.md`](../docs/CONFORMANCE-CORPUS-SPEC.md).
- **The machine-readable case shape**: [`case.schema.json`](case.schema.json).
- **Why the program exists**: the [formalization proposal §7](../docs/SEMANTICS-FORMALIZATION-PROPOSAL.md).

## Layout

```
corpus/
  README.md                    # this file
  case.schema.json             # the machine-readable case shape
  models/<name>.json           # standalone DM-JSON models, shared across cases
  cases/<family>/<id>.json     # one case per file (id == file stem)
```

`<family>` is a kebab-case directory naming the primary semantic area (`comparison`, `compute`, `partial`, `clock`, `fuzz`, …) — a grouping aid; the normative taxonomy is each case's `meta.sections`.

## Models & `modelRef`

A case does not embed its model — it carries a **`modelRef`**, the corpus-root-relative path of a standalone model file (e.g. `"models/order-qty-low.json"`). A runner resolves that ref against the corpus root and loads the file. One model serves many cases (the two `comparison` cases share `order-qty-low`), so a shared-model diff stays reviewable.

Each `models/<name>.json` is **pure DM-JSON with no wrapper envelope** — expanded, self-contained, with rules/computations embedded as stored-English condition strings. That is exactly what makes it **openable in the A12 modeling tools** and directly consumable by `dmtool` (e.g. `dmtool model describe -m corpus/models/order-qty-low.json`, `dmtool rule check …`). Files are domain-named, deduped by content (same model → one file), stable pretty-printed, and never orphaned — a model lands with its first referencing case (all guard-checked, see below).

## Reference runners & re-capture

The kernel-grounded reference implementation lives in `:adapter` test sources (it needs the kernel); a second, **kernel-free** runner replays the same cases on the interpreter's JVM **and JS** targets:

| Task | What it does |
|---|---|
| `./build.sh :adapter:corpusReplayTest` | Replays every committed case **three ways** — interpreter, kernel groovy-dynamic, kernel java-static — and asserts each PASSes (the standing guard). The groovy leg re-runs the real kernel, so it is also the freshness guard. |
| `./build.sh :interpreter:check` | Runs `CorpusReplayCommonTest` (the kernel-free interpreter leg) on **both** targets — `jvmTest` and `jsNodeTest`. The JS run is the **first kernel-grounded JS oracle**: since capture tri-verified `interpreter == kernel`, a JS-vs-JVM interpreter split (a decimal-shim / regex / format platform difference) surfaces here as a FAIL. Zero FAIL / zero SKIP = full conformance. The corpus is located via the `CORPUS_DIR` env var Gradle sets on those tasks. |
| `./build.sh -PrunCorpusCapture :adapter:test --tests '*CorpusGenerator'` | Re-captures the case set from `CorpusScenarios` (tri-verified) and rewrites the files. The KERNEL-UPGRADE re-capture entry point: on a kernel bump the committed diff **is** the behavior-change characterization. |

Everything committed here is **own-domain original material** (the project's license-safe rule); nothing from the EUPL/proprietary corpora is persisted.
