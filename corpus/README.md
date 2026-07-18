# Conformance corpus

A portable, kernel-independent record of A12 document-model **runtime semantics as data**. Each file under [`cases/`](cases/) is one case: a reference to a document model, one document instance, one operation, and the kernel-verified expected outcome. The models themselves are **standalone DM-JSON files** under [`models/`](models/) that the cases share. Any engine claiming kernel-compatible evaluation — the in-repo interpreter (JVM and JS), a kernel codegen strategy, or a third-party reimplementation — demonstrates it by replaying the corpus and matching the expectations.

- **The contract** (case format, placement/pointer semantics, signatures, verdict rules): [`docs/CONFORMANCE-CORPUS-SPEC.md`](../docs/CONFORMANCE-CORPUS-SPEC.md).
- **The machine-readable case shape**: [`case.schema.json`](case.schema.json).
- **Why the program exists**: the [formalization proposal §7](../docs/archived/SEMANTICS-FORMALIZATION-PROPOSAL.md).

## Layout

```
corpus/
  README.md                    # this file
  case.schema.json             # the machine-readable case shape
  models/<name>.json           # standalone DM-JSON models, shared across cases
  cases/<family>/<id>.json     # one case per file (id == file stem)
```

`<family>` is a kebab-case directory naming the primary semantic area (`comparison`, `compute`, `partial`, `clock`, `fuzz`, `empty-polarity`, …) — a grouping aid; the normative taxonomy is each case's `meta.sections`.

## Models & `modelRef`

A case does not embed its model — it carries a **`modelRef`**, the corpus-root-relative path of a standalone model file (e.g. `"models/order-qty-low.json"`). A runner resolves that ref against the corpus root and loads the file. One model serves many cases (the two `comparison` cases share `order-qty-low`), so a shared-model diff stays reviewable.

Each `models/<name>.json` is **pure DM-JSON with no wrapper envelope** — expanded, self-contained, with rules/computations embedded as stored-English condition strings. That is exactly what makes it **openable in the A12 modeling tools** and directly consumable by `dmtool` (e.g. `dmtool model describe -m corpus/models/order-qty-low.json`, `dmtool rule check …`). Files are domain-named, deduped by content (same model → one file), stable pretty-printed, and never orphaned — a model lands with its first referencing case (all guard-checked, see below).

Each model's **identity (`header.id` and `content.modelInfo.name`) equals its file-stem name**, so it is a **uniquely-named workspace item**: the A12 modeling tools (SME) require workspace items to have unique names, and several models here derive from one base (all Order models), which would otherwise share the base's id and collide. Giving each the unique file-stem id makes them coexist. This is set by the generator and CI-locked (`header.id == stem`), so uniqueness holds by construction.

Each model's date-ish fields also use only **SME-canonical `format`s** (`yyyy-MM-dd`, `yyyy-MM-dd'T'HH:mm:ss`, `HH:mm:ss`). The kernel runtime tolerates a localized display format (e.g. German `dd.MM.yyyy`) directly in `format`, but the SME's model deserializer rejects it (`unsupported date format`) — a custom display format belongs in `notInDCustomFormat`. The generator canonicalizes date formats when it writes a standalone model, and a guard rejects any non-canonical one. Background: [KERNEL-FINDINGS §6](../docs/KERNEL-FINDINGS.md).

## Reference runners & re-capture

The kernel-grounded reference implementation lives in `:adapter` test sources (it needs the kernel); a second, **kernel-free** runner replays the same cases on the interpreter's JVM **and JS** targets:

| Task | What it does |
|---|---|
| `./build.sh :adapter:corpusReplayTest` | Replays every committed case **three ways** — interpreter, kernel groovy-dynamic, kernel java-static — and asserts each PASSes (the standing guard). The groovy leg re-runs the real kernel, so it is also the freshness guard. |
| `./build.sh :interpreter:check` | Runs `CorpusReplayCommonTest` (the kernel-free interpreter leg) on **both** targets — `jvmTest` and `jsNodeTest`. The JS run is the **first kernel-grounded JS oracle**: since capture tri-verified `interpreter == kernel`, a JS-vs-JVM interpreter split (a decimal-shim / regex / format platform difference) surfaces here as a FAIL. Zero FAIL / zero SKIP = full conformance. The corpus is located via the `CORPUS_DIR` env var Gradle sets on those tasks. |
| `./build.sh -PrunCorpusCapture :adapter:test --tests '*CorpusGenerator'` | Re-captures the case set from `CorpusScenarios` (tri-verified) and rewrites the files. The KERNEL-UPGRADE re-capture entry point: on a kernel bump the committed diff **is** the behavior-change characterization. |

Everything committed here is **own-domain original material** (the project's license-safe rule); nothing from the EUPL/proprietary corpora is persisted.

## The portable evidence-capture commands

The maintained capture boundary ([`docs/CONFORMANCE-CORPUS-SPEC.md`](../docs/CONFORMANCE-CORPUS-SPEC.md) §15) — adapter-internal developer tooling that executes a consumer-authored, expectation-free scenario set through the registered runners into a deterministic evidence packet. **Capture never writes into this corpus tree** (the command refuses it; promotion is a separate, reviewed, explicitly authorized action). A complete, runnable **authoring example** (scenario file + model, real digests) is committed at [`adapter/capture-examples/scenario-example/`](../adapter/capture-examples/scenario-example/scenarios.json); the machine-readable capability contract — including each policy's qualifying runners, mandatory projections, and the `requiredChannels` semantics — comes from `:adapter:captureCapabilities`.

| Task | What it does |
|---|---|
| `./build.sh :adapter:captureCapabilities` | Print the machine-readable capability output (`a12-dmkits-capture-capabilities-v1`) — transport capability, never semantic support. |
| *(path arguments)* | Every `-Pcapture.*` **file/dir argument resolves against `adapter/`** (the JavaExec working dir), not the repo root — pass **absolute paths** to be safe; a mis-resolved path fails loudly ("not a regular file"), never silently. |
| `./build.sh :adapter:captureValidateScenarios -Pcapture.scenarios=<file>` | Structurally validate a `capture-scenario-set-v1` file through the SAME closed decoder capture uses — no engine runs, nothing is written. |
| `./build.sh :adapter:capture -Pcapture.scenarios=<file> -Pcapture.output=<new-or-empty-dir> [-Pcapture.development]` | Capture an evidence packet: legality gate → the three registered runners → deterministic directory-shaped packet; prints the out-of-band `RECEIPT.json` SHA-256. Default mode requires a clean committed checkout; `--development` packets are permanently qualification-ineligible. |
| `./build.sh :adapter:captureVerify -Pcapture.packet=<dir> -Pcapture.packetReceiptSha256=<hex> -Pcapture.policy=<id> -Pcapture.output=<new-dir>` | Apply the packet's **request-declared** qualification policy (`characterization-v1` / `kernel-route-confirmed-v1` / `dmkits-projected-conformance-v1`); the `-Pcapture.policy` value is an explicit confirmation — a mismatch with the declared policy is refused fail-closed. Verifies byte integrity AND the closed packet relations, then writes a digest-bound report directory (`PROFILE.json` + `REPORT.json` + `RECEIPT.json`), never touching the packet. |
| `./build.sh :adapter:captureDiffPackets -Pcapture.before=<dir> -Pcapture.after=<dir> -Pcapture.projection=<id> -Pcapture.output=<new-dir>` | Structural packet diff — input/provenance/legality drift vs per-runner observation drift (named per case + runner). |
| `./build.sh :adapter:captureDiffReports -Pcapture.before=<dir> -Pcapture.after=<dir> -Pcapture.output=<new-dir>` | Structural report diff — policy-profile drift vs qualification-result drift. |
| `./build.sh :adapter:exportSemanticObservations -PsemanticEvidence.packet=<dir> -PsemanticEvidence.packetReceiptSha256=<hex> -PsemanticEvidence.qualification=<dir> -PsemanticEvidence.qualificationReceiptSha256=<hex> -PsemanticEvidence.output=<new-or-empty-dir>` | The compact semantic-observation exporter (§15h) — post-capture, ONE family (`string-direct-cascade-v1`): re-verifies the retained packet + qualification against the out-of-band digests, re-applies `kernel-route-confirmed-v1`, and writes exactly `semantic-observations.json` (canonical UTF-8, ≤ 256 KiB, SHA-256 printed, no receipt tree). Requires a clean committed checkout. Unlike `-Pcapture.*`, the `-PsemanticEvidence.*` path arguments resolve against the **repo root** (absolute paths recommended; they pass through unchanged). |
