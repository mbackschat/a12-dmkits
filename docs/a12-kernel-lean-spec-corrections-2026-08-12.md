# a12-kernel-lean — spec corrections handed over from a12-dmkits

> **What this is.** The **outbound** handback channel to the `a12-kernel-lean` formalization project (a sibling checkout, not linked here because it is not guaranteed present): kernel measurements taken here that correct or extend a clause in its `spec/`. It is a **running** document — the date in its filename records when the exchange opened, not the batch it carries. Every later batch is appended below, and a correction to an earlier batch is made **in place** and marked, never in a successor document.
>
> **Why it lives here.** It used to live outside version control on one machine, which made keeping it current depend on that machine — recorded as `IG137(e)` and closed by this file. A record that is read back across sessions and across machines belongs in the repository, for the same reason the performance record does.
>
> **What it carries, and what it does not.** The payload is the table of *(input → observed output → oracle used)*. Mechanisms — "because the kernel does X" — are quarantined in [§ Mechanisms](#mechanisms) and marked as hypotheses, because measurements travel and explanations do not. Two full round-trips of the 2026-07-25 exchange existed only because that separation was missing.

## How to resolve a cited revision

Each batch cites the a12-dmkits revision its locks were reviewed at. Resolve it against `main`.

> **The 2026-08-14 batch is NOT yet reachable from `origin/main`.** At the time of writing, `af9d14b9` is local `HEAD` and `origin/main` is `0e9100e2`, with 32 commits between them. `e233548e` and every revision cited in that batch therefore resolves **here** and not yet at the remote. This is stated rather than left implicit because the peer's ledger already carries the reverse error — a reachability measurement taken against a stale checkout being reported as a dead SHA — and a handback citing an unresolvable revision is the same failure from the other side. Re-check reachability before acting on the 2026-08-14 rows.

## Batch 2026-08-12 (a) — the entity-list aggregates take a group-scope operand

**Oracle:** the real kernel's `checkConsistency`, via `KernelLaws.acceptsModel` / `rejectsModel`. **Lock:** [`AggregateGroupOperandLawsTest`](../src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/AggregateGroupOperandLawsTest.java). **Revision:** `4b244b84`. **Scope: static admission only** — no row here establishes runtime behaviour.

### It NARROWS the wildcard duplicate-skip this project had canonized as blanket

| input | observed | 
|---|---|
| the same starred group listed twice | ACCEPT |
| `Sum(/Invoice/Lines*, /Invoice/Lines*/Fees*)` — a starred group beside its own starred descendant | `MVK_DUPLICATE_PARAM2` |

So the skip belongs to the **direct**-duplicate arm alone; the indirect ancestor/descendant arm **does** fire between two wildcarded references. The clause as written ("skips every wildcarded reference") covers both arms in one sentence and is too broad.

### It ADDS the group-scope operand's own gates on that carrier

| input | observed |
|---|---|
| a NON-repeatable group, unstarred | ACCEPT |
| a NON-repeatable group, STARRED | `MVK_INVALID_WILDCARD` |
| a REPEATABLE group, unstarred (`Sum`, `NumberOfFilledFields`) | `MVK_NO_WILDCARD` |
| a REPEATABLE group, starred | ACCEPT |
| a starred group scope alone, no other operand | ACCEPT — the group slot satisfies the param law by itself |
| a group scope mixed with fields, carrying nested stars | ACCEPT |
| expansion of the wrong kind, under `Sum` | `MVK_NO_NUMBER` |
| expansion of the wrong kind, under `MaxValue` | `MVK_NOT_SORTABLE` |
| expansion of the wrong kind, under `NumberOfDifferentValues` | `MVK_STRING_ENUM_AND_NON_STRING_ENUM` |

**Read each row at the carrier that measured it.** The two halves generalize differently: the **structural** gates (param law, star requirement, both duplicate arms) sit on the shared entity-list checker, so they do not vary by carrier even where only one carrier ran the row. The **kind** gate does vary — three carriers, three codes for the same offending operand.

`FirstFilledValue` routes through the same shared checker but had **no group-operand row at all** in this batch, so its expansion kind question was open here. The 2026-08-14 batch closes its admission.

## Batch 2026-08-12 (b) — `FieldValuesNotUnique` admits the shape, correcting a design premise

**Oracle:** the real kernel's `RuleValidator` / `checkConsistency`, kernel 30.8.1 under JDK 21.0.12. **Locks:** [`FieldValuesNotUniqueAdmissionLawsTest`](../src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/FieldValuesNotUniqueAdmissionLawsTest.java), [`AggregateGroupOperandLawsTest`](../src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/AggregateGroupOperandLawsTest.java). **Durable home:** `KF173`. **Revision:** `7a609235`.

Its first effect corrects a **local design premise rather than a `spec/` clause**: `SEMANTICS-GAPS` SG5 had reasoned from the absence of a measured row to the conclusion that no measurement admits a group operand for `FieldValuesNotUnique`, and let that gate a semantic decision — that the operator must refuse the shape at its whole-list gate. Implementing it as written would have shipped an over-rejection.

| input | observed |
|---|---|
| a non-repeatable group expanding to two Strings | ACCEPT |
| a starred repeatable group expanding to two same-scale Numbers | ACCEPT |
| the equivalent explicit field lists (controls, same run) | ACCEPT |
| a group whose expansion is a SINGLE field | ACCEPT |
| that same single field authored directly | `MVK_PARAMSIZE_INVALIDN` |
| a group expanding to a Number beside a String | `MVK_VARYING_TYPES_NOT_ALLOWED` |
| `NumberOfDifferentValues(/Order/ShippingAddress*)` — star on a non-repeatable group | `MVK_INVALID_WILDCARD` |

**The two-gate reading follows from the third and fourth rows together, and neither shows it alone:** **arity** is satisfied by the group slot itself, while the **category** gate reads the expansion.

The last row **closes** the star-on-a-non-repeatable-group row that batch (a) had left as an explicit *not measured*. It is measured against a character-identical unstarred control that is additionally asserted equal to the typed renderer's output, so the pair cannot be measuring a hand-authoring typo. It is a **second carrier** for the star gate and the first on a non-root group — the same code had been measured only under `NumberOfFilledGroups` on a root. It **corroborates** the shared-entity-list-checker reading without proving it: a coincidence of two carriers predicts the same row equally well.

**Disclosed from our side:** the mirror-image defect in our own DSL, where `Dsl.fieldValuesNotUnique` takes a `FieldOperand` and the shape is therefore type-level unrepresentable (`DG33`) — and that we had expected the kernel to refuse, by analogy with `NumberOfFilledGroups`' `MVK_NO_FIELDS_ALLOWED`. The analogy was wrong.

## Batch 2026-08-14 — group-operand admission is per-CARRIER, and ACCEPT means EXPANDED

**Oracle:** the real kernel's `rule check`. **Lock:** [`GroupOperandCarrierAdmissionLawsTest`](../src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/GroupOperandCarrierAdmissionLawsTest.java). **Durable home:** [`KF187`](KERNEL-FINDINGS.md#kf187). **Revision:** `e233548e` (see the reachability caveat above).

A group operand is **not** a shape the kernel admits or refuses globally. Swept across every carrier a group-expansion audit had named:

| carrier | non-repeatable group (unstarred) | repeatable group (starred) |
|---|---|---|
| the 7 field-fill quantifiers (`AllFieldsFilled`, `NoFieldFilled`, `AtLeastOneFieldFilled`, `NotAllFieldsFilled`, `FieldsNotCollectivelyFilled`, `NotExactlyOneFieldFilled`, `MoreThanOneFieldFilled`) | ACCEPT | ACCEPT |
| the 3 plural value-list quantifiers (`AtLeastOneFieldValueIncludedInValueList`, `NoFieldValueIncludedInValueList`, `NotAllFieldValuesIncludedInValueList`) | ACCEPT | ACCEPT |
| `FirstFilledValue` | ACCEPT | ACCEPT |
| `NumberOfFilledFields` | ACCEPT | ACCEPT |
| `MinValue` over a **DATE** group | ACCEPT | ACCEPT |
| `MaxValue` over a **DATE** group | ACCEPT | ACCEPT |
| **`Min`** (the operand-LIST form) | `MVK_NO_GROUPS_ALLOWED` | `MVK_NO_GROUPS_ALLOWED` |
| **`Max`** (the operand-LIST form) | `MVK_NO_GROUPS_ALLOWED` | `MVK_NO_GROUPS_ALLOWED` |
| **`DateRangesOverlap`** | `MVK_NO_GROUPS_ALLOWED` | `MVK_NO_GROUPS_ALLOWED` |
| **`AtLeastOneDateRangeOverlaps`**, group in the `In` slot | — | ACCEPT |

**Two carriers disagree with their own family member, and that is the finding.** `Min`/`Max` refuse a group operand while their `MinValue`/`MaxValue` siblings accept one, so the min/max *name* does not predict the gate. And `DateRangesOverlap` refuses a group outright while the plural `AtLeastOneDateRangeOverlaps` accepts one in its `In` slot, so within one predicate family the singular and plural forms disagree. **A formalization that generalizes either verdict across its family gets the other half wrong.**

### ACCEPT means the kernel EXPANDED the group and type-checked the result

Measured, not inferred — the alternative reading ("waved past the type gates on the way through a group") is just as consistent with every row above, because a **homogeneous** group cannot separate them.

| input | observed |
|---|---|
| a DATE group against a STRING value list | `MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED` |
| the same carrier over a String group | ACCEPT |

So a runtime implementation may rely on the expansion. That licence is the whole point of the sweep.

### ARITY does not count the expansion

| input | observed |
|---|---|
| `MoreThanOneFieldFilled(<single-field group>)` | ACCEPT |
| `MoreThanOneFieldFilled(<that same field>)` | `MVK_PARAMSIZE_INVALIDN` |

A group slot satisfies the param law **unconditionally**, exactly as a starred slot does — the same split `KF173` records for `FieldValuesNotUnique`. Neither row shows it alone.

### `Min`/`Max` refuse groups and wildcards by two SEPARABLE gates

| input | observed |
|---|---|
| `Min([Payouts*/Gross], 5)` — a starred FIELD | `MVK_NO_WILDCARDS_ALLOWED` |
| `Min(<group>)`, either repetition shape | `MVK_NO_GROUPS_ALLOWED` |

The **differing code on the starred field is what separates the gates.** The starred-*group* row would report the group code under either gate and cannot on its own establish which fired — recorded because the first version of this entry rested that cell on a row with no admissible counterfactual.

### The `$` OUTER marker is a UNIVERSAL filter-only gate and says nothing about any carrier

| input | observed |
|---|---|
| `[$/Claim/ExcessDue] > 0` — a bare comparison, no aggregate anywhere | `MVK_INVALID_OUTER_ITERATION` |
| `Sum($…, …)` | `MVK_INVALID_OUTER_ITERATION` |
| the unmarked list | ACCEPT |

**This corrects our own earlier reading**, which took the aggregate rows as a statement about aggregate operands. They are not — and the misreading had been used to close a defect. The reachable spelling is `$` **inside** a filter, which this table does not cover.

### Three rows measure a SPELLING, not a gate, and are excluded on that basis

Each was caught by its own field control failing alongside it, which is the only reason they are separable at all.

| input | observed | why excluded |
|---|---|---|
| `Min(Assessment, 5)` | `MVK_UNEXPECTED_TOKEN` | `Min`'s operands are value expressions; an entity must ride in brackets |
| `MinValue(<dates>) > 0` | `MVK_INVALID_COMPARE_TO_DATE` | the comparison rejects, not the operand |
| a `Sum` inside an overlap predicate's `Having` | `MVK_NO_ITERATION_FOR_WILDCARD` | fires in the marked *and* unmarked form |

### One further admission fact, measured while building the runtime locks

| input | observed |
|---|---|
| a computation whose target sits inside its own operand | `MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD` |

Consequence for anyone building a discriminator on the compute path: since the target must live in the computation's group and that group must be below the operand, **there is no legal compute spelling** of a "group scope from a deep row" discriminator. A rule's error field carries no such restriction, so validation is the only carrier that can measure it.

## Batch 2026-08-14 — the group operand's runtime EXTENT

**Oracle:** an adapter differential over **both** kernel codegen strategies (dynamic-Groovy and static-Java), asserted equal to each other before the interpreter is compared. Rows below report the kernel's behaviour; where the two strategies agreed, which was every row here, no normativity question arose. **Revisions:** `19adb3e5`..`af9d14b9`.

These bear on `spec/07` (repetition and iteration), which states the static gates but not the extent.

| shape | measured behaviour | lock |
|---|---|---|
| a group operand's expansion crossing a **nested repeatable** level | the expansion reaches cells in **every** row of the nested level, not row 1 | `NestedGroupFillQuantifierDiffTest` (all 7 fill quantifiers, one asserted table) |
| a group-scope fill quantifier evaluated from a rule authored on a repeatable level **strictly below** the operand | the **operand** decides the scope, not the evaluating row: `AllFieldsFilled(<group>)` is false from every row when any cell below the operand is empty | `GroupScopeFromADeepRuleRowDiffTest` |
| a **starred** group operand (`G*`) evaluated from a rule authored **on** `G` | `G`'s own level **iterates**; the quantifier spans every row of its own starred level | `StarredGroupOperandOwnLevelDiffTest` |
| `FirstFilledValue(<group>)` from a deep rule row | the same answer from every row — the operand's first filled cell in **encounter order**; a filled DIRECT field precedes the nested rows | `ComputeGroupScopeFromADeepRowDiffTest` |
| a **doubly-starred** operand (`/Doc/Outer*/Inner*/Detail`) driving a computation's precondition from an inner row | **both** starred levels iterate whatever the computation's own row is | `MultiStarDeclaredScanScopeDiffTest` |

### The polarity law for a group operand

The message **type** a firing carries is not a property of the operator alone; it depends on whether a fill could still change the answer.

| shape | observed type |
|---|---|
| `NotAllFieldsFilled(<group>)` firing because a cell below the operand is empty | `OMISSION_ERROR` |
| `AllFieldsFilled(<group>)` firing with every cell filled | `VALUE_ERROR` |
| `FirstFilledValue(<group>) == <v>` firing while the group's direct field is **empty** | `OMISSION_ERROR` |
| the same, with the direct field **filled** | `VALUE_ERROR` |

A boolean "did it fire" projection cannot see this axis. Widening our own differentials from `any { code }` to a full `errorPath|type` signature is what exposed four defects on our side that had passed as agreements — offered as method, since the same blind spot would hide the same class in any conformance suite.

## Batch 2026-08-14 (b) — the operand-list `Min`/`Max` refusal holds on the COMPUTATION carrier too

**Oracle:** the real kernel's `RuleValidator` over a `DraftComputation`. **Lock:** `MinMaxGroupOperandComputeCarrierLawsTest`.

The `KF187` table above was measured entirely through **rules**. `KF187`'s own finding is that carriers within one family can disagree, so "a different carrier applies the same gate" is precisely the inference it forbids — and a closure that rested on the rule rows alone was therefore under-supported. Measured on the computation carrier, in both positions a condition can occupy:

| position | input | observed |
|---|---|---|
| common precondition | `Min([<non-repeatable group>], 5) > 0` | `MVK_NO_GROUPS_ALLOWED` |
| common precondition | `Max([<non-repeatable group>], 5) > 0` | `MVK_NO_GROUPS_ALLOWED` |
| common precondition | `Min([<starred repeatable group>], 5) > 0` | `MVK_NO_GROUPS_ALLOWED` |
| alternative operation | `Min([<non-repeatable group>], 5)` | `MVK_NO_GROUPS_ALLOWED` |
| alternative operation | `Max([<starred repeatable group>], 5)` | `MVK_NO_GROUPS_ALLOWED` |
| alternative operation | `Min([<field>], [<field>])` — control | ACCEPT |
| alternative operation | `Min([<starred FIELD>], 5)` — gate separator | `MVK_NO_WILDCARDS_ALLOWED` |

The last row is the same separator the rule table needs and for the same reason: a starred *group* would report the group code under either gate, so only a starred *field* drawing a **different** code shows which one fired.

## Mechanisms — hypotheses, not actionable <a id="mechanisms"></a>

Everything above is measured. Everything here is an explanation, carries the confidence of an explanation only, and is **not** a basis for a spec clause.

- **Why the structural gates look carrier-independent while the kind gate does not.** The structural gates plausibly sit on one shared entity-list checker, whose kernel-source basis the peer's `SPEC-2026-07-22-07` already records, while each carrier applies its own kind predicate to the expansion. The three-code kind row is consistent with this and does not establish it; a coincidence of carriers predicts the same rows.
- **Why `Min`/`Max` split from `MinValue`/`MaxValue`.** They are plausibly different carriers that happen to share a concept — an operand-list form versus an entity-list aggregate — rather than one family with an inconsistent gate. Nothing in the measurements distinguishes this from a deliberate asymmetry.
- **What we do NOT claim.** No row above establishes read order or precedence between the gates, except the one pair that measured a preemption explicitly (`refusesInsteadOf`). A gate ordering read from source alone stays a hypothesis here until a template, a generated program, or a probe confirms it — that rule has caught us four times.

## Disclosed defects on our side

Handed over because the peer's own record shows it treats our disclosures as calibration for its spec, not as noise.

- **`DG33`** — `Dsl.fieldValuesNotUnique` takes a `FieldOperand`, so the admitted group shape is type-level unrepresentable. Disclosed in batch (b).
- **`DG34`** — `Dsl.numberOfValueInFields` has no `GroupListRef` overload; same erasure, same absence of a measured basis.
- **`DG35`** — the DATE extrema and the three plural value-list quantifiers carry six operand overload shapes and not one is a `GroupListRef`, which makes the omission read as deliberate when it is not.
- **`DG34` is now CLOSED**, and its shape is worth naming because we got the diagnosis wrong at first: the fix was not an added overload but a **record-component widening**. `NumberOfValueInFields` held a `List<FieldOperand>` where the kernel routes its entities through the same `getRefList` as the aggregates, so the erasure was in the operand TYPE, not in a missing factory. `DG35` remains open on a genuinely different obstacle — its record's component carries a *kind* that `GroupListRef` cannot supply.
- **A cross-component sweep of our own tooling found five defects of the same family** (`CG13`–`CG18`), all downstream of one habit rather than of the kernel: asking an EXACT reference primitive a REACHABILITY question. The kernel expands a group operand recursively to every field below it, so a field read only through `AllFieldsFilled(/Order/Address)` has no direct referrer — and our audit report called it dead, our delete gate found no referrer, and the delete then succeeded against a model that stayed **kernel-valid**, since the rule still named a group that still existed. Offered because the trap is structural rather than ours: any tool that reports "unreferenced" or gates a delete over a model with group operands has the same two questions to keep apart, and the kernel's own validator cannot distinguish them for you.
- **Twenty-one runtime instances of one class** (`IF287`): our evaluator's group expansion was opt-in at every operand-resolution site. The reliable tell was always **a site disagreeing with a sibling that got it right** — offered because it is a cheap audit any second implementation can run against itself.
