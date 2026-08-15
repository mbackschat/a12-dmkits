# Recompute register for [CLAUDE.md](CLAUDE.md)

> **What this is.** [CLAUDE.md](CLAUDE.md) is a retrieval manual, so it quotes figures *derived from this tree* — entry counts, anchor coverage, corpus size. Derived figures go false silently: nothing links them to the thing they measure, so they are right on the day they are written and wrong from the next commit on, and no guard can tell. Measured 2026-08-15, recomputing all of them for the first time: **16 of the 17 were stale**, from 5 % off (a line count) to a corpus size that had tripled. One was still correct.
>
> This register is the missing link. One row per derived figure: where it sits, and **the command that recomputes it**. Run the commands, compare, repair the doc.

## How to use it

```
./build.sh :docGuardTest          # after any repair — the doc guards read this tree
```

Run each row's command from the repository root. Where a figure is a rounded estimate the doc writes it with `~`; the register still names the exact command, so "is this still roughly right?" has one answer instead of a judgement.

**This register states no results, by construction.** A register carrying expected values would be a second hand-authored derived artifact drifting exactly like the table it repairs — so the command *is* the value. (A naive grep cannot check that property, because the commands contain regex digits like `§9` and `{2,3}`. The invariant is that no digit here is an *outcome*.)

**Every row names its denominator.** Two figures have more than one defensible reading, and a register that omitted the regex would leave them unfalsifiable — the row would look checked while two people got different answers. Where the reading is a choice, the row says which one and why.

## The rows

| where | figure | recompute with |
|---|---|---|
| `## These docs are read by grep + sed` | corpus size | `cat docs/*.md \| wc -c` — top-level `docs/` only; `archived/` is excluded because you do not grep it for current knowledge |
| " | corpus in tokens | the byte count above **÷ 4**. Not a tokenizer: a stated convention, and the *original* one — the doc's first pair (`~1.5 MB` / `~380k`) is exactly this ratio, so naming the rule recovers the convention rather than inventing one |
| " | `INTERPRETER-FINDINGS` size | `wc -c < docs/INTERPRETER-FINDINGS.md` |
| retrieval table, `SEMANTICS-MAP` row | its length | `wc -l < docs/SEMANTICS-MAP.md` |
| retrieval table, "what exists" row | IF / KF / API entry counts | the three commands the row already prints, with `-c`: `grep -c '^### IF' docs/INTERPRETER-FINDINGS.md` · `grep -c '^#\{2,3\} ' docs/KERNEL-FINDINGS.md` · `grep -c '^[0-9]\+\. \*\*' docs/API-FINDINGS.md`. The doc's own greps are the denominator, which is what makes this row self-checking |
| retrieval table, "how certain" row | KF entries naming a lock class | per-entry scan, entry = one `^#{2,3} ` heading to the next: an entry counts when its body matches **`\b[A-Z][A-Za-z0-9]*(Test\|Laws)\b`**. The capitalized-identifier anchor is load-bearing — a bare `(Test\|Laws)` also matches the English words and over-counts |
| retrieval table, "how certain" row | oracle-token coverage per doc, which is *why* that row splits the two | headings matching `(?i)(probe\|source-verified\|source-read\|differential\|measured\|tri-lock\|runtime)` over all headings, per doc. **The vocabulary is load-bearing and `runtime` is part of it**: omitting that one token moves KERNEL-FINDINGS from about two thirds to under half, which is the difference between "most carry one" and a reversal. A cold audit caught exactly that error here on 2026-08-15 |
| retrieval table, "what remains" row | drained `-GAPS` ledgers | `grep -l '^_None open._' docs/*GAPS*.md \| wc -l` over `ls docs/*GAPS*.md \| wc -l` |
| addressing table, INTERPRETER-FINDINGS row | headings carrying an anchor | `grep -c '^### IF.*<a id="if' docs/INTERPRETER-FINDINGS.md`, against the entry count above. The claim is *all*, so the two must be equal — a mismatch is a missing anchor, not a stale number |
| addressing table, KERNEL-FINDINGS row | entries carrying an anchor | `grep -c '^#\{2,3\} .*<a id="kf' docs/KERNEL-FINDINGS.md`, against the entry count above; same all-or-defect reading |
| " | `## §9 supplement` entries | `grep -c '^## §9 supplement' docs/KERNEL-FINDINGS.md` |
| addressing table, KERNEL-SEMANTICS row | numbered sections | `grep -c '^## [0-9]\+\. ' docs/KERNEL-SEMANTICS.md` |
| " | verification cites | `grep -oE '\[IF[0-9]+\]\(INTERPRETER-FINDINGS\.md#if[0-9]+\)' docs/KERNEL-SEMANTICS.md \| wc -l` — resolved links only, which is the form the row describes |
| addressing table, API-FINDINGS row | citation sites | `grep -roE 'finding [0-9]+' --include=*.md --include=*.java --include=*.kt . \| grep -v '/build/' \| wc -l` — **occurrences, not lines**; the row says "sites", and a line carrying two cites is two. Counting files instead gives a number roughly a third the size |
| addressing table, `-GAPS` row | distinct `IG` ids cited | `grep -ohE '\bIG[0-9]+' docs/*.md \| sort -u \| wc -l` — distinct ids, not occurrences |
| retrieval table, OPERATOR-INDEX row | operators discussed / heading-blind / the two worst mention counts | one python pass over `docs/KERNEL-FINDINGS.md` + `docs/KERNEL-SEMANTICS.md`: an operator counts as *discussed* when its catalog id appears as a whole token inside a code span (the same rule `OperatorIndex` applies, so the row and the generated page cannot disagree), and as *heading-blind* when no `^#{2,4} ` line in either doc contains it. The denominator is the catalog's ids, `rulekit/src/main/resources/catalog/operators.json`. **The code-span restriction is the load-bearing half**: counting bare words instead inflates `Sum` from 76 to 84 by sweeping in English prose |
| `**Section numbers are load-bearing.**` | CLI-SPEC + API-GUIDE citation sites, and the `§5`/`§6` share | `git grep -ohE '(CLI-SPEC\|API-GUIDE)[^§]{0,40}§ ?[0-9][0-9a-z.]*' -- ':!*/build/*'` — pipe to `wc -l` for the total, and to `grep -cE '§ ?5$'` / `'§ ?6$'` for the two shares. **Occurrences, not lines**, matching the API-FINDINGS row's reading. The 40-character window is the denominator choice: it admits `[CLI-SPEC](CLI-SPEC.md) §3b` but not two unrelated sentences, and widening it inflates the count. `docs/archived/` is *included* here — the sites exist and would mislead a reader, even though they are never repaired. **Use `git grep`, never `grep -r --include`**: an `--include` list is a suffix filter, so it silently drops EXTENSIONLESS tracked files, and `bin/dmtool` alone carried five of these citations. That blind spot is not hypothetical — it let a `§B2` site survive a sweep that reported itself complete (2026-08-16) |
| `## The -GAPS template` | ledger count | `ls docs/*GAPS*.md \| wc -l` |

## What this register deliberately does not cover

- **Figures in other docs.** One register, for one doc. Whether the pattern earns a second is a question for after this one has been run a few times.
- **`IF229` in the "one entry, in full" row.** It is an illustrative id, not a count, and it resolves. It goes stale only in the weak sense that it stops being a recent example.
- **Anything a guard already checks.** `DocAnchorResolveGuardTest` enforces anchor presence, so the two anchor rows above are a cross-check on the *stated* number, not the enforcement.
