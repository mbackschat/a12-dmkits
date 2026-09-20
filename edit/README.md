# `:edit` — one model, one gate, one write

Published as `io.github.mbackschat.a12.dm:dmkits-edit` (MIT). Package `io.github.mbackschat.a12.dm.edit`.

The cross-kit top layer, and the only module that depends on **both** [`:rulekit`](../rulekit) and [`:modelkit`](../modelkit). [`EditSession`](src/main/java/io/github/mbackschat/a12/dm/edit/EditSession.java) loads a model once, applies structure *and* rule edits to that one in-memory copy, and puts the result through a **single** kernel consistency gate before anything is written — so a multi-step change is atomic, and a failure rolls the whole session back rather than leaving a half-edited model on disk.

The editors are reached from the session by relative navigation rather than by repeating path strings: [`FieldEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/FieldEditor.java), [`GroupEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/GroupEditor.java), [`RuleEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/RuleEditor.java), [`ComputationEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/ComputationEditor.java), [`TypeDefEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/TypeDefEditor.java), [`IncludeEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/IncludeEditor.java), [`ConfigEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/ConfigEditor.java) and [`ElementMetadataEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/ElementMetadataEditor.java). A path written once, validated at the call site, is the preferred idiom throughout this project.

`dmtool apply` is this session exposed as a CLI verb: read, ordered surgery, one gate, one write, rollback on failure.

Build and test: `./build.sh :test` — the suites span all four kits and run at the root project. Guide: [`docs/API-GUIDE.md`](../docs/API-GUIDE.md).
