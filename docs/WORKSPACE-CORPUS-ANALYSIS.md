# Workspace corpus analysis — operator usage & transform backlog

A read-only **analysis** of a real A12 model corpus, used to prioritize what rulekit builds next and to *pattern-transform* (not copy) realistic rules into our own fixtures. This file records **facts about DSL usage** (operator names, frequencies, abstract skeletons) — **never** the corpus's proprietary domains, field names, or rules.

## Source & provenance (read-only; do not commit corpus content)

- **Source:** the `workspaces/` tree of an *A12 Tools* product distribution (proprietary sample/business models) — **not** the OSS kernel repos and **not** `a12-kernel-demo`. Path is machine-specific (a mounted volume).
- **Rule (from `CLAUDE.md`):** never copy domains/fields/rules from non-demo sources. We treat this strictly as a **read-only oracle**: mine *patterns and frequencies*, then author original fixtures in our own domains, kernel-validated via N7. The histogram and skeletons below are usage facts, not their content.
- **Version:** all 68 models are `modelVersion` **28.4.0** and the distribution is **2025.06-ext5 = kernel 30.8.1** — identical to rulekit's pinned version. No version tolerance needed.

## Corpus stats

- **68 document models**, **150 rule conditions**, **27 models reference other models** (`modelReferences` — i.e. they exercise the `expand` + resolver path we have not yet integration-tested).
- Rule-dense models reach ~29 rules each; conditions are frequently **multi-line** and contain `;;` **comments**, and keywords appear in **case variants** (`and` / `AND` as well as `And`).

## Operator / construct frequency (across the 150 conditions)

| Count | Construct | rulekit coverage |
|--:|---|---|
| 194 | `And`/`and`/`AND` | ✅ (renders `And`; case variants are read-only concern) |
| 87 / 83 | `FieldFilled` / `FieldNotFilled` | ✅ |
| **56** | `GroupFilled` | ❌ group predicates |
| 39 / 15 | `==` / `!=` | ✅ |
| **28** | `*` flatten | ❌ iteration |
| 21 | `AllFieldsFilled` | ✅ (quantifier family done) |
| **21** | `$` correlation | ❌ iteration |
| 19 / 11 / 11 / 7 | `<=` / `<` / `>` / `>=` | ✅ (symbols; catalog has `<=`) |
| 14 | `Or`/`or`/`OR` | ✅ |
| 13 / 10 / 8 / 6 / 5 | `NotExactlyOneFieldFilled` / `NotAllFieldsFilled` / `FieldsNotCollectivelyFilled` / `AtLeastOneFieldFilled` / `NoFieldFilled` | ✅ (quantifier family done) |
| **11** | `NumberOfFilledFields` (aggregate) | ❌ |
| **11 / 3** | `True` / `False` (boolean & confirm literals) | ❌ no boolean literals |
| **8 / 8 / 8 / 6 / 4** | `CurrentRepetition` / `For` (semantic index) / `Today` / `Having` / `RepetitionNotUnique` | ❌ iteration + dates |
| **6 / 1 / 1 / 1** | `Date(...)` / `DifferenceInDays` / `DifferenceInYears` / `AddMonths` | ❌ date family |
| **5** | `->` (enum category) | ❌ |
| 4 / 4 / 3 / 1 / 1 | `RangeAsString` / (group: `AtLeastOneGroupFilled`) / `PatternViolated` / `GroupNotFilled` / `Length` | partial (`Length` ✅) |
| ~2 | value-list membership (`IncludedInValueList`, `NoFieldValue…InValueList`) | ❌ |

## Abstract pattern inventory (generic skeletons — to transform into our own domains)

- **Boolean-gated requiredness:** `[Bool] == True And FieldNotFilled(G)` / `[Bool] == True And NotAllFieldsFilled(a, b, c)`
- **Guarded cross-field comparison:** `AllFieldsFilled(F1, F2) And [F1] <op> [F2]`
- **Cross-group address equality:** `[GroupA/X] == [GroupB/X] And …`
- **Uniqueness over a repeating group:** `GroupFilled(G) And RepetitionNotUnique(G/Key)`
- **Enum-category navigation:** `[Field -> Category] == "X"`
- **Date thresholds:** `AllFieldsFilled(Start, End) And DifferenceInDays(Start, End) > N`; `[Date] >= Today`
- **Aggregate-driven:** `NumberOfFilledFields(a, b, c) <op> N`
- **Filtered aggregate / correlation:** `Sum(G*/X) Having (…)`, `[$/Other/Field]` correlated refs

## Parser-relevant findings (for the future text→AST round-trip, #3)

Real conditions include `;;` line comments, `\r\n` line breaks, and case-variant keywords (`and`/`AND`). A future parser must tolerate all three; the catalog's keyword set must include the case variants (distinct lexer tokens `UND1`/`UND2`/`UND3`).

## Prioritized transform backlog (frequency × effort)

1. **Boolean (+ confirm) literals & comparison** — `== True/False` (14). *Low effort, unlocks the most common gating pattern.*
2. **Group predicates** — `GroupFilled` (56!) + `GroupNotFilled`/`AllGroupsFilled`/`AtLeastOneGroupFilled`. *Low–med effort, highest single frequency; shape mirrors `FieldFilled` but over a group ref.*
3. **`NumberOfFilledFields` aggregate** (11) → comparable numeric expression. *Med.*
4. **Date family** — `Today`, `Date(d,m,y)`, `DifferenceIn{Days,Months,Years}`, `Add{Days,Months,Years}` (~17 combined). *Med; needs the date kinds + the year-presence comparability rule (§5b).*
5. **Iteration / structural cluster** — `*` flatten (28), `$` correlation (21), `CurrentRepetition` (8), `For` semantic-index (8), `Having` (6), `RepetitionNotUnique` (4). *High effort, highest value — this is where model-awareness (§5a) pays off. The big one; tackle as its own multi-step phase.*
6. **Long tail** — enum-category `->` (5), value-list membership, `RangeAsString` (4), `PatternViolated` (3).

## How this drives the work

Each backlog item is built as a normal increment: catalog entries (verified EN/DE keywords) + AST node(s) + typed handles + renderer, with examples authored in **our own** fixtures and **kernel-validated by N7**. The corpus only tells us *what to build and in what order*; correctness comes from the kernel, originality from our own domains. A separate, opt-in integration test that runs the `expand`+resolver path over the (path-referenced, never-committed) corpus is a strong future addition to harden the model index against the 27 reference-using models.
