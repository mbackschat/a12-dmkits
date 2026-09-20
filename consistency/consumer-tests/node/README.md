# `a12-consistency` — plain Node consumer

A throwaway Node project with **no bundler and no framework**, into which the build installs the packed `a12-consistency` tarball and imports it by package name. It answers the question the Kotlin suites cannot: *can a Node developer install this and use it?*

Run it from the repository root — never by hand, because the tarball must be packed and installed first:

```sh
./build.sh :consistency:nodeConsumerTest
```

## What each file is for

**Correctness of the plumbing, not of the checker.**

| file | what it holds |
|---|---|
| [`check.test.mjs`](check.test.mjs) | the Node half of the packaged replay: the [shared cases](../shared) run through the installed package. It asserts the **plumbing** — that the package installs, imports, runs a real model and returns a report a consumer can read. Coverage is deliberately *not* retested here |
| [`impact-and-package-surface.test.mjs`](impact-and-package-surface.test.mjs) | the two things about the editor surface only a Node consumer can establish. Its behavioural rows moved into [`../shared/editor.ts`](../shared/editor.ts) so the browser arm runs them too |

**The CI gate — a worked example, and its own test.**

| file | what it holds |
|---|---|
| [`ci-gate.mjs`](ci-gate.mjs) | a CI gate over model sources built on nothing but the published package. It exists because *"may this land?"* and *"is this model valid?"* are different questions: a CI job wants the first, and `ALL_CHECKS_PASSED` answers it |
| [`ci-gate.test.mjs`](ci-gate.test.mjs) | **the gate cannot manufacture a pass.** The gate is a consumer, so its defects are consumer defects — reading "no findings" as a pass, collapsing a tool failure into a rejection, or exporting an exit code the process never actually exits with. Those rows belong here rather than in the Kotlin tests because none of them is a checker defect |

**The resource boundaries, on the host that can answer them.** Each is a measurement script rather than a test, reporting one boundary the specification asks for separately per host:

| file | boundary |
|---|---|
| [`cold-first-result.mjs`](cold-first-result.mjs) | one cold check in a process that will never run a second — package load through to first complete result |
| [`prepared-growth.mjs`](prepared-growth.mjs) | initial analysis work and repeated same-snapshot queries, reported separately |
| [`after-edit.mjs`](after-edit.mjs) | what holding one analysis across many questions actually saves |
| [`retained-memory.mjs`](retained-memory.mjs) | **release** behaviour — a heap flat across repeated stateless checks |
| [`retained-snapshot.mjs`](retained-snapshot.mjs) | **retention** — what holding one analysis costs a consumer's heap |

**Corpus maintenance.** [`write-parity-corpus.mjs`](write-parity-corpus.mjs) regenerates the three-arm parity corpus *from the packed package*. Run it through `./build.sh :consistency:syncCheckerParityCorpus`, never directly: that task packs and installs first, so the corpus is written by the artifact a consumer receives rather than by whatever happens to be in `build/`.
