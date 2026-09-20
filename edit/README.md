# `:edit` — one model, one gate, one write

## The problem

A real change to a DocumentModel is several edits at once: add a field, declare the type definition it uses, add the rule that validates it, adjust the error message. Done naively, that goes wrong in two ways.

**Half-applied state.** Each edit written separately means a failure on the third one leaves a model on disk with the first two applied — structurally present, semantically incomplete, and now a model whose next reader has to work out what was intended.

**Gating each step separately is both slow and wrong.** You would pay a kernel consistency check per edit, and intermediate states are legitimately inconsistent: the field exists before its rule does, the rule references a type definition declared in the same batch. Gating each step refuses work that is fine, while gating nothing ships work that is not.

## What this module does about it

[`EditSession`](src/main/java/io/github/mbackschat/a12/dm/edit/EditSession.java) loads the model **once**, applies every edit to that one in-memory copy, and puts the result through a **single** kernel consistency gate before anything is written. Intermediate states are never gated and never persisted; a failure rolls the whole session back and writes nothing.

It is the only module depending on both [`:modelkit`](../modelkit) and [`:rulekit`](../rulekit), which is what lets one session mix structure and rule work at all.

**Editors are reached by navigation, not by repeating paths.** A path is written once, at the point where it is validated:

| editor | scope |
|---|---|
| [`FieldEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/FieldEditor.java) · [`GroupEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/GroupEditor.java) | structure, in place — including re-typing a field rather than delete-and-recreate |
| [`RuleEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/RuleEditor.java) · [`ComputationEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/ComputationEditor.java) | declarations, by re-expression |
| [`TypeDefEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/TypeDefEditor.java) · [`IncludeEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/IncludeEditor.java) | reusable types and mounted sub-models |
| [`ConfigEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/ConfigEditor.java) · [`ElementMetadataEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/ElementMetadataEditor.java) | document config, and labels/descriptions on any element |

`dmtool apply` is this session behind a CLI verb — read, ordered surgery, one gate, one write, rollback on failure. If you reach for `batch` instead, note that it is the opposite trade: independent dispatch, continuing past a child failure, and no rollback of an earlier successful write.

## Reference

Its publication coordinate is `io.github.mbackschat.a12.dm:dmkits-edit` (MIT). Package `io.github.mbackschat.a12.dm.edit`. Depends on [`:core`](../core), [`:modelkit`](../modelkit) and [`:rulekit`](../rulekit). Kernel-free, guarded over both compiled classes and resolved POM. The publish wiring is landed and the public deploy is **deferred** ([`../docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md) §11), so today you get it by `./build.sh publishToMavenLocal` and `mavenLocal()`, not from a registry.

```sh
./build.sh :test   # the kits' suites span all four and run at the root project
```

Guide with runnable snippets: [`docs/API-GUIDE.md`](../docs/API-GUIDE.md).
