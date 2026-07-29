# docs/ — the retrieval manual for this tree

> Auto-loaded for work under `docs/` (Claude Code: nested CLAUDE.md; Codex: the `AGENTS.md` symlink).
>
> **This is NOT a rules doc.** Doc *procedure* — where a finding lands, the kernel-source golden rule, the living-surface same-commit triggers, the spec/proposal/`-GAPS`/`-LEDGER` lifecycle, writing style — lives in [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md); read it before *editing* a doc. This file covers only what is local to this directory and self-verifying at the moment of use: **how these docs are addressed and retrieved.** Corpus invariants (anchors resolve, numbering dense, markers uniform) are the business of the guard tests named at the bottom, not of prose here — a rule stated in prose and checked by nobody is how this tree drifted in the first place.

## Read these docs with grep + sed, never end to end

The knowledge docs total ~1.5 MB (~380k tokens); `INTERPRETER-FINDINGS` alone is 687 KB. Never `Read` one whole. A finding's **heading line is the retrieval preview** — it deliberately carries claim + oracle + date, so a grep over headings is usually enough to decide what to fetch.

| you need… | run |
|---|---|
| **which doc at all** | [README.md](README.md) — the "You need… → read…" router; every `docs/*.md` is indexed (guard-locked) |
| **where a kernel-semantics area lives** | [SEMANTICS-MAP.md](SEMANTICS-MAP.md) — 99 lines, keyed by the canonical `§1`–`§14`, re-derived from its live surfaces by a guard |
| **what exists** (entry inventory) | `grep -n '^### IF' docs/INTERPRETER-FINDINGS.md` · `grep -n '^#\{2,3\} ' docs/KERNEL-FINDINGS.md` · `grep -n '^[0-9]\+\. \*\*' docs/API-FINDINGS.md` |
| **one entry, in full** | `grep -n '^### IF229' <doc>` for the start line, then `sed -n '<start>,<end>p'` bounded by the next heading match |
| **a fact by keyword** | `grep -n '<term>' docs/KERNEL-FINDINGS.md docs/KERNEL-SEMANTICS.md` — the kernel-truth pair; headings are keyword-rich on purpose |
| **how certain a claim is** | the heading's oracle token (`probe` / `source-verified` / `source-read` / `differential` / `measured` / `tri-lock`) **plus** the lock test: `grep -o '\*Locked:\*.\{0,90\}'` inside the entry's line range |
| **whether a kernel fact is current** | `grep -A1 '^## <heading>'` — the `> Fold:` marker is the first body line of every KERNEL-FINDINGS entry: `folded → §n` (canonical claim now lives in [KERNEL-SEMANTICS](KERNEL-SEMANTICS.md) §n) · `not-foldable` · `pending` |
| **what remains** | `grep -h '^- \*\*IG' docs/INTERPRETER-GAPS.md` for live interpreter gaps; then the other `-GAPS` ledgers by name (see README) — there is no single roll-up, and the "none open" marker is **not** yet uniform across them |

**Caveat on `sed` ranges:** the `*Locked:*` pointer sits in the **last quarter** of the entry in most KERNEL-FINDINGS entries, so a front-truncated range gives you the claim and its currency but not its lock. Fetch the full range when certainty is the question.

## Addressing — cite by ID where one exists, never by title text

| doc | what identifies an entry | what to know |
|---|---|---|
| [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) | `IF<n>`, unique on `^### IF<n> ` | the only doc with real per-entry IDs. Never renumber or reuse. Cite `IF<n>`, not a heading slug — headings get amended, and one intra-doc slug link is already broken from exactly that. `IF23` is an exception: it lives inside IF22's body, so `grep '^### IF'` will not find it |
| [KERNEL-FINDINGS](KERNEL-FINDINGS.md) | **no per-entry ID** — the exact heading text | `§n` is a *section-class hint, not an address*: `## §9 supplement` matches 25 entries, `§6` 24 across two heading depths. When citing, quote enough heading text to be unique |
| [KERNEL-SEMANTICS](KERNEL-SEMANTICS.md) | `§1`–`§14` + heading slugs | the canonical prose. Its 14 section slugs are pinned by a guard, so they are safe to link. It cites verification as `KERNEL-FINDINGS §n / IFm` — note the two halves resolve in **different docs** (`§n` here, `IFm` in INTERPRETER-FINDINGS) |
| [API-FINDINGS](API-FINDINGS.md) | ordinal, cited as `finding N` from ~113 sites incl. production source | greppable exactly (`grep '^67\. '`). It is a markdown ordered list, so **never delete or insert mid-list** — rendered numbering would shift and silently break every later citation |
| [INTERPRETER-GAPS](INTERPRETER-GAPS.md) | `IG<n>` on rows matching `^- \*\*IG` | a shipped gap's row is **deleted**, so most `IG<n>` you meet elsewhere are provenance, not open work — ~99 are cited across `docs/`, and only the ledger's own rows are live. Never write `pending IGn` / `IGn stays open` about a gap you have not checked against the ledger |

## Writing so retrieval keeps working

Only edit-local habits belong here — things you can satisfy while writing the entry:

- **Put the claim, the oracle, and the date in the heading.** It is what grep shows; a terse heading retrieves worse than a long descriptive one.
- **Put the `*Locked:*` pointer early**, not only at the tail, so a partial `sed` can still answer "how certain is this?".
- **Cite an entry by its ID or an exact unique heading fragment** — never by a slug you constructed, and never by `§n` alone.
- **Amend in place.** Correct an existing entry where it lives, marked (`Corrected <date>: …`); never mint a new entry to supersede one.

## What is mechanically enforced (and what is not)

Guards that will fail the build: `DocsIndexGuardTest` (every doc indexed, every index link resolves) · `KernelDocsFoldContractTest` (fold markers, the §-mirror, the canonical anchor set) · `KernelDocsBasisContractTest` (a mechanism claim must name its oracle) · `SemanticsMapResolveTest` (the map re-derived from live surfaces) · `StaleGapReferenceGuardTest` (present-tense cites of deleted gaps — **source files only; markdown is not scanned**) · `CliSpecVerbTableTest` · `CommandReferencesResolveTest`.

Not enforced today, so verify by hand when you touch them: intra-doc anchor resolution, API-FINDINGS numbering, the uniformity of the `-GAPS` "none open" marker, and compression of `folded` KERNEL-FINDINGS entries to their evidence skeleton.
