# docs/ — the retrieval manual for this tree

> Auto-loaded for work under `docs/` (Claude Code: nested CLAUDE.md; Codex: the `AGENTS.md` symlink).
>
> **This is NOT a rules doc.** Doc *procedure* — where a finding lands, the kernel-source golden rule, the living-surface same-commit triggers, the spec/proposal/`-GAPS`/`-LEDGER` lifecycle, writing style — lives in [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md); read it before *editing* a doc. This file covers only what is local to this directory and self-verifying at the moment of use: **how these docs are addressed and retrieved.** Corpus invariants are the business of the guard tests named at the bottom, not of prose here — a rule stated in prose and checked by nobody is how this tree drifted in the first place.

## These docs are read by grep + sed, never whole

The knowledge docs total ~1.5 MB (~380k tokens); `INTERPRETER-FINDINGS` alone is ~690 KB. Never `Read` one end to end. A finding's **heading line is the retrieval preview** — it deliberately carries claim + oracle + date, so a grep over headings is usually enough to decide what to fetch.

| you need… | run |
|---|---|
| **which doc at all** | [README.md](README.md) — the "You need… → read…" router; every `docs/*.md` is indexed (guard-locked) |
| **where a kernel-semantics area lives** | [SEMANTICS-MAP.md](SEMANTICS-MAP.md) — 99 lines, keyed by the canonical `§1`–`§14`, re-derived from its live surfaces by a guard |
| **what exists** (entry inventory) | `grep -n '^### IF' docs/INTERPRETER-FINDINGS.md` (229) · `grep -n '^#\{2,3\} ' docs/KERNEL-FINDINGS.md` (165) · `grep -n '^[0-9]\+\. \*\*' docs/API-FINDINGS.md` (103) |
| **one entry, in full** | `grep -n '^### IF229' <doc>` for the start line, then `sed -n '<start>,<end>p'` bounded by the next heading match |
| **a fact by keyword** | `grep -n '<term>' docs/KERNEL-FINDINGS.md docs/KERNEL-SEMANTICS.md` — the kernel-truth pair; headings are keyword-rich on purpose |
| **how certain a claim is** | the heading's oracle token (`probe` / `source-verified` / `source-read` / `differential` / `measured` / `tri-lock`), then pipe the entry range through `grep -i lock` for its test — the lock is prose-embedded (149 of 165 entries name a `*Test`/`*Laws` class inline), so extract it, don't expect a fixed slot |
| **whether a kernel fact is current** | `grep -A1 '^## <heading>'` — the `> Fold:` marker is the first body line of every KERNEL-FINDINGS entry: `folded → §n` (canonical claim now lives in [KERNEL-SEMANTICS](KERNEL-SEMANTICS.md) §n) · `not-foldable` · `pending` |
| **what remains** | `grep -H '^_None open._' docs/*GAPS*.md` — the drained ledgers (7 of 8 today); the rest carry rows under their `Open` section |

## Addressing — cite by id, never by title text

Every cited entry owns an **explicit anchor**, so a heading may be amended freely without rotting a link. That is the durable fix for a class that bit twice (`IF96`, `IAG4`): a generated heading slug moves the moment the claim sharpens.

| doc | id | notes |
|---|---|---|
| [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) | `IF<n>` → `#ifNN` | all 229 headings carry `<a id="ifNN"></a>`. Never renumber or reuse; a new finding takes the next number and must carry its anchor |
| [KERNEL-FINDINGS](KERNEL-FINDINGS.md) | `KF<n>` → `#kfNN` | all 165 entries carry `<a id="kfNN"></a>`, **trailing** — never before the `§`-tag, because two guards pin `^## §n `. Ids are dense 1..N in document order; a finding is never deleted or renumbered. `§n` is a section-*class* hint, not an address (`## §9 supplement` matches 25 entries) |
| [KERNEL-SEMANTICS](KERNEL-SEMANTICS.md) | `§1`–`§14` + heading slugs | the canonical prose; its 14 section slugs are guard-pinned, so linking them is safe. Its 107 verification cites read `§n / [IFm](INTERPRETER-FINDINGS.md#ifm)` — the two halves live in **different** docs, which is why the `IFm` half is an explicit link |
| [API-FINDINGS](API-FINDINGS.md) | ordinal, cited as `finding N` | ~113 citation sites, including production sources. Greppable exactly (`grep '^67\. '`). It is a markdown ordered list, so **never delete or insert mid-list** — rendered numbering would shift and silently break every later citation |
| the `-GAPS` ledgers | `IG<n>` `DG<n>` `IAG<n>` `IRG<n>` `MR<n>` | `IAG`/`IRG` rows carry `#iagN`/`#irgN` anchors. A shipped gap's row is **deleted**, so most ids you meet elsewhere are provenance, not open work — ~99 `IG` ids are cited across `docs/` while only the ledger's own rows are live. Never write `pending IGn` / `IGn stays open` about a gap you have not checked against the ledger |

**Section numbers are load-bearing.** `FEATURE-GAPS §4`, `DEV-API-GAPS §2/§3`, `INTERPRETER-GAPS §3` and `SCHEMAKIT-GAPS §3` are cited from production sources and docs. Do not renumber a `-GAPS` section; add at the end instead.

## The `-GAPS` template

All eight ledgers share one shape, so a single grep answers "what remains?" across the family. Guarded by `GapLedgerTemplateGuardTest`.

```markdown
# <scope> — gaps

> **What this is.** <scope; where the implemented contract lives; the id family>
>
> **Lifecycle.** A gap lives here only while open. When it ships its row is DELETED in the
> same commit and its rationale graduates to <spec/finding>; git is the what/when. No
> shipped list, ever.
>
> **Current state.** <why the open section looks the way it does; closure context>

## <N>. Open

_None open._          ← when drained: the marker ALONE, nothing else

## <N>. Deferred — with re-open triggers
## <N>. Out of scope
```

**The open section carries only rows or only the marker.** Closure history and lifecycle policy belong in `Current state.` / `Lifecycle.` — prose there buries the one answer a reader came for. Doc-specific sections (a framing section, a recorded-decision section) are fine; keep them outside the three canonical ones and never renumber to make room.

## Writing so retrieval keeps working

- **Put the claim, the oracle, and the date in the heading.** It is what grep shows; a terse heading retrieves worse than a long descriptive one.
- **Give a new finding its anchor** in the same edit (`<a id="ifNN"></a>` / `<a id="kfNN"></a>`, trailing).
- **Cite by id**, never by a slug you constructed and never by `§n` alone.
- **Amend in place.** Correct an existing entry where it lives, marked (`Corrected <date>: …`); never mint a new entry to supersede one.

## What is mechanically enforced (and what is not)

Guards that will fail the build: `DocsIndexGuardTest` (every doc indexed, every index link resolves) · `DocAnchorResolveGuardTest` (intra- **and** cross-doc fragments resolve; every IF/KF heading carries its anchor; KF ids dense) · `GapLedgerTemplateGuardTest` (the template above) · `ApiFindingsNumberingGuardTest` (ordinals dense, unique, aligned) · `KernelDocsFoldContractTest` (fold markers, the §-mirror, the canonical anchor set) · `KernelDocsBasisContractTest` (a mechanism claim must name its oracle) · `SemanticsMapResolveTest` · `StaleGapReferenceGuardTest` (present-tense cites of deleted gaps — **source files only; markdown is not scanned**) · `CliSpecVerbTableTest` · `CommandReferencesResolveTest`.

Not enforced, so verify by hand when you touch them: stale-gap wording in markdown (the classifier reads *logical comments*, which prose does not have — see [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md#if141) for the measurement and why the wording was fixed instead), the `scripts/`-index and other directory indexes, and compression of `folded` KERNEL-FINDINGS entries to their evidence skeleton.
