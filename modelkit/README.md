# `:modelkit` — the model's structure, typed

Published as `io.github.mbackschat.a12.dm:dmkits-modelkit` (MIT). Package `io.github.mbackschat.a12.dm.modelkit`.

[`:rulekit`](../rulekit)'s sibling, for the other half of a DocumentModel: fields and their per-kind configuration, groups and repetition, type definitions, includes and imports, document config and element metadata. **Neither kit depends on the other** — that boundary is a build-graph fact, not a convention, so nothing can quietly couple them.

Start at [`DocumentModelDraft`](src/main/java/io/github/mbackschat/a12/dm/modelkit/DocumentModelDraft.java) and the per-kind field builders (`DateFieldBuilder`, `EnumFieldBuilder`, `CustomFieldBuilder`, `DateRangeFieldBuilder`, …). Each kind carries its own builder because each admits different configuration, which is how a `TIME` format or an enum's S-value domain becomes unrepresentable in the wrong place instead of rejected at runtime.

**Illegal states are made unrepresentable where the type system can carry it, and refused as you build where it cannot** — a repeatable group with `max < 2`, a field referencing an undeclared type definition. The kernel still has the final say: every result re-binds through `checkConsistency`.

To edit the structure of an *existing* model, use [`:edit`](../edit)'s `EditSession`, which puts structure and rule changes behind one kernel gate and one write.

Build and test: `./build.sh :test` — the suites span all four kits and run at the root project. Guide: [`docs/API-GUIDE.md`](../docs/API-GUIDE.md).
