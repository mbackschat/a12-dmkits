# docs/ — the retrieval manual for this tree

> Auto-loaded for work under `docs/` (Claude Code: nested CLAUDE.md; Codex: the `AGENTS.md` symlink).
>
> **This is NOT a rules doc.** Doc *procedure* — where a finding lands, the kernel-source golden rule, the living-surface same-commit triggers, the spec/proposal/`-GAPS`/`-LEDGER` lifecycle, writing style — lives in [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md); read it before *editing* a doc. This file covers only what is local to this directory and self-verifying at the moment of use: **how these docs are addressed and retrieved.** Corpus invariants are the business of the guard tests named at the bottom, not of prose here — a rule stated in prose and checked by nobody is how this tree drifted in the first place.

## These docs are read by grep + sed, never whole

The knowledge docs total ~4.6 MB (~1.15M tokens at 4 bytes each); `INTERPRETER-FINDINGS` alone is ~1.0 MB. Never `Read` one end to end. *(Every derived figure in this doc has a recompute command in [CLAUDE.recompute.md](CLAUDE.recompute.md) — run it rather than trusting the number.)* A finding's **heading line is the retrieval preview** — it deliberately carries claim + oracle + date, so a grep over headings is usually enough to decide what to fetch.

| you need… | run |
|---|---|
| **which doc at all** | [README.md](README.md) — the "You need… → read…" router; every `docs/*.md` is indexed (guard-locked) |
| **where a kernel-semantics area lives** | [SEMANTICS-MAP.md](SEMANTICS-MAP.md) — 104 lines, keyed by the canonical `§1`–`§14`, re-derived from its live surfaces by a guard |
| **what exists** (entry inventory) | `grep -n '^### IF' docs/INTERPRETER-FINDINGS.md` (299) · `grep -n '^#\{2,3\} ' docs/KERNEL-FINDINGS.md` (188) · `grep -n '^[0-9]\+\. \*\*' docs/API-FINDINGS.md \| cut -c1-160` (125) — **API-FINDINGS entries are ONE line each, so its heading is its body**: untruncated, that inventory emits 98.6 % of the file, and any keyword lead-grep on it does the same |
| **one entry, in full** | `grep -n '^### IF229' <doc>` for the start line, then `sed -n '<start>,<end>p'` bounded by the next heading match |
| **a fact by keyword** | `grep -n '<term>' docs/KERNEL-FINDINGS.md docs/KERNEL-SEMANTICS.md \| cut -c1-200` — the kernel-truth pair. **The truncation is not optional:** these docs are one-paragraph-per-line, so an untruncated `repetition` returns 115 lines and ~99 KB in a single call. Headings are claim-rich but not subject-rich — most operator names appear only in bodies, so a heading grep alone will miss them |
| **how certain a claim is** | **KERNEL-FINDINGS:** the heading's oracle token (`probe` / `source-verified` / `source-read` / `differential` / `measured` / `tri-lock` / `runtime`) — about two thirds of its headings carry one, so **a third do not and you must fetch the entry**. **INTERPRETER-FINDINGS: almost none do** (a twentieth), so always read the entry's provenance rather than the heading. Either way the lock is prose-embedded (171 of 188 KF entries name a `*Test`/`*Laws` class inline), so pipe the entry range through `grep -i lock` and extract it; don't expect a fixed slot |
| **whether a kernel fact is current** | `grep -A2 '^## <heading>'` — the `> Fold:` marker is the first body line of every KERNEL-FINDINGS entry, **after a blank line**, so `-A1` silently matches nothing: `folded → §n` (canonical claim now lives in [KERNEL-SEMANTICS](KERNEL-SEMANTICS.md) §n) · `not-foldable` · `pending` |
| **what remains** | `grep -H '^_None open._' docs/*GAPS*.md` — the drained ledgers — all 9 of 9 today; any that is not drained carries rows under its `Open` section |

## Addressing — cite by id, never by title text

Every cited entry owns an **explicit anchor**, so a heading may be amended freely without rotting a link. That is the durable fix for a class that bit twice (`IF96`, `IAG4`): a generated heading slug moves the moment the claim sharpens.

| doc | id | notes |
|---|---|---|
| [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) | `IF<n>` → `#ifNN` | all 299 headings carry `<a id="ifNN"></a>`. Never renumber or reuse; a new finding takes the next number and must carry its anchor |
| [KERNEL-FINDINGS](KERNEL-FINDINGS.md) | `KF<n>` → `#kfNN` | all 188 entries carry `<a id="kfNN"></a>`, **trailing** — never before the `§`-tag, because two guards pin `^## §n `. Ids are dense 1..N in document order; a finding is never deleted or renumbered. `§n` is a section-*class* hint, not an address (`## §9 supplement` matches 32 entries) |
| [KERNEL-SEMANTICS](KERNEL-SEMANTICS.md) | `§1`–`§14` + heading slugs | the canonical prose; its 14 section slugs are guard-pinned, so linking them is safe. Its 120 verification cites read `§n / [IFm](INTERPRETER-FINDINGS.md#ifm)` — the two halves live in **different** docs, which is why the `IFm` half is an explicit link |
| [API-FINDINGS](API-FINDINGS.md) | ordinal, cited as `finding N` | ~123 citation sites, including production sources. Greppable exactly (`grep '^67\. '`). It is a markdown ordered list, so **never delete or insert mid-list** — rendered numbering would shift and silently break every later citation |
| the `-GAPS` ledgers | `IG<n>` `DG<n>` `IAG<n>` `IRG<n>` `MR<n>` `CG<n>` `MG<n>` | `IAG`/`IRG` rows carry `#iagN`/`#irgN` anchors and every issued interpreter API/release id remains resolvable in the [interpreter gap-ID registry](INTERPRETER-GAP-ID-REGISTRY.md), even after its live row is deleted. `CG` rows (CLI conformance) carry `#cgN` anchors. A shipped gap's row is **deleted**, so most ids you meet elsewhere are provenance, not open work — ~134 distinct `IG` ids are cited across `docs/` while only the ledger's own rows are live. Never write `pending IGn` / `IGn stays open` about a gap you have not checked against the ledger |

**Section numbers are load-bearing.** `FEATURE-GAPS §4`, `DEV-API-GAPS §2/§3`, `INTERPRETER-GAPS §3` and `SCHEMAKIT-GAPS §3` are cited from production sources and docs. Do not renumber a `-GAPS` section; add at the end instead.

## The `-GAPS` template

All nine ledgers share one shape, so a single grep answers "what remains?" across the family. Guarded by `GapLedgerTemplateGuardTest`.

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

**A shipped gap is RETIRED from the open section — in EVERY ledger, no exceptions.** Deleting the row is the completion step, not a tidy-up afterwards: its rationale graduates to the owning spec/finding and git carries the what/when, so a row left behind and annotated "FIXED" adds nothing and costs the one answer the section exists to give. This was prose-only until 2026-08-14, when a session left three shipped rows under `## 1. Open` and the owner, not the build, caught it. `GapLedgerTemplateGuardTest.noShippedItemRemainsInAnOpenSection` now fails on a shipped marker in an item's **lead**, deliberately not in its body — a partially-shipped row legitimately says what already landed as long as its lead states the open remainder.

## Evidence vs. rationale — the split that gets misfiled (2026-08-11)

**A number you measured is EVIDENCE and belongs in a dated log. The conclusion you drew from it is RATIONALE and belongs in a findings doc.** Each doc says so itself: [INTERPRETER-PERFORMANCE-RESULTS](INTERPRETER-PERFORMANCE-RESULTS.md) holds the `### Checkpoint <date> — <topic>` entries, newest first, while its method half [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) holds the contract, the protocol and the harness; [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) is "a **rationale record, not a status surface** — the timeless fact, the decision, the *why*". [DOC-DISCIPLINE §1](DOC-DISCIPLINE.md) generalizes it: specs hold the stable method, a dated log holds the numbers. *(Those two were one file until 2026-08-15, which made this doc the standing example of the misfile it warns about.)*

**The decision test, one question:** *would this sentence still be true if the same code were measured tomorrow, or on another machine?*

| answer | it is | home |
|---|---|---|
| **no** — it names a run, a machine, a date, a wall time, a median, an envelope | dated evidence | the owning doc's dated log (perf → [INTERPRETER-PERFORMANCE-RESULTS](INTERPRETER-PERFORMANCE-RESULTS.md); eval/benchmark → [`../eval/RESULTS.md`](../eval/RESULTS.md)) |
| **yes** — it states a behaviour, a mechanism, a design consequence, a tradeoff | rationale | the finding (`IF<n>` / `KF<n>` / `API-FINDINGS`), which **links** the checkpoint rather than repeating its numbers |
| it says *"not yet / parked / pending / currently unarmed"* | a disposition | the owning `-GAPS` ledger — never either of the above |

**Why this needs stating.** The misfile is attractive, not careless: the finding is where the reasoning lives, so the measurements that motivated it feel like they belong beside it. They don't — they go stale on the next machine, and a findings doc is read as durable. It happened to IF280 (three hosted runs' medians, wall times, run ids and exceedance counts written into the finding) and only the owner noticing caught it.

**Enforcement is partial, deliberately.** `DocsEvidencePlacementGuardTest` checks the *structural* forms — no results-log entry heading in a findings doc, no CI run URL in one, and every dated checkpoint in the results doc rather than in the method doc. The judgment itself has no reliable textual marker: a run-id-shaped number and a table of measurements are both legitimate in a findings doc (measured — see the guard's own "does NOT guard" note), and a guard that fails honest content gets disabled. So the question above is yours to apply; the guard only catches the copied-entry shape.

## Writing so retrieval keeps working

- **Put the claim, the oracle, and the date in the heading.** It is what grep shows; a terse heading retrieves worse than a long descriptive one.
- **Give a new finding its anchor** in the same edit (`<a id="ifNN"></a>` / `<a id="kfNN"></a>`, trailing).
- **Cite by id**, never by a slug you constructed and never by `§n` alone.
- **Put the measurement in the log and the conclusion in the finding** — the split above, applied at the moment you are tempted to paste a number you just measured.
- **Write a new finding at skeleton weight** — claim, provenance, lock, and only the **discriminating** cases (the ones that would have falsified a rival reading). The existing long entries are deliberately *not* being retro-compressed (see the KERNEL-FINDINGS fold-status preamble for why), so the way volume stays bounded is at authoring time, not by a later sweep.
- **Amend in place.** Correct an existing entry where it lives, marked (`Corrected <date>: …`); never mint a new entry to supersede one.

## What is mechanically enforced (and what is not)

**One command runs them all: `./build.sh :docGuardTest`.** The classes below are split across the root and `:cli` modules, which both own a task of that name; the root one aggregates the cli lanes so a docs-only checkpoint cannot pick the wrong half (TESTING-SPEC, "Two modules own a task named `docGuardTest`" — a malformed ledger row shipped that way).

Guards that will fail the build: `DocsIndexGuardTest` (every doc indexed, every index link resolves) · `DirectoryIndexGuardTest` (the same, for the `scripts/` and `examples/` indexes) · `DocAnchorResolveGuardTest` (intra- **and** cross-doc fragments resolve; every IF/KF heading carries its anchor; KF ids dense) · `GapLedgerTemplateGuardTest` (the template above) · `InterpreterGapIdRegistryGuardTest` (unique issued IAG/IRG ids; live row state and title agree with the append-only registry) · `ApiFindingsNumberingGuardTest` (ordinals dense, unique, aligned) · `KernelDocsFoldContractTest` (fold markers, the §-mirror, the canonical anchor set) · `KernelDocsBasisContractTest` (a mechanism claim must name its oracle) · `DocsEvidencePlacementGuardTest` (no results-log entry or CI run URL in a findings doc; every dated checkpoint in `INTERPRETER-PERFORMANCE-RESULTS.md`, none in the method doc) · `SemanticsMapResolveTest` · `StaleGapReferenceGuardTest` (present-tense cites of deleted gaps — **source files only; markdown is not scanned**) · `ReferentDriftGuardTest` (prose still naming the pre-split home of a moved checkpoint — a rename leaves every link resolving and the sentence around it false; **markdown and source, repo-wide**) · `CliSpecVerbTableTest` · `CommandReferencesResolveTest`.

Not enforced, so verify by hand when you touch them: **whether a paragraph is dated evidence or timeless rationale** (the split above — only its structural forms are checkable), stale-gap wording in markdown (the classifier reads *logical comments*, which prose does not have — see [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md#if141) for the measurement and why the wording was fixed instead), the [`../eval/TASKS.md`](../eval/TASKS.md) task inventory (dual-keyed `T<n>` + `family/NN` rows, so a filename-keyed guard false-positives), and compression of `folded` KERNEL-FINDINGS entries to their evidence skeleton.
