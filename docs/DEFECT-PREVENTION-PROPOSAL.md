# Preventing the defect class external review keeps finding — proposal

> **Status.** PROPOSAL, **revision 3**, 2026-08-01. Revisions 1 and 2 were reviewed and rejected. This revision differs in **kind, not degree: it stops asserting design.** What is measured is stated; every unbuilt decision is a question in §4 instead of an answer, and the answers arrive as code with a red run, not as more prose.
>
> **The first measure is now BUILT, and §6 reports what building it taught** — including that one of its two laws was itself unsound, which only running it revealed. That is the whole argument for the change of kind, so it is evidence rather than illustration.
>
> **Why the change of kind.** Revision 1 carried four unverified measure-properties; revision 2 fixed those and carried eight more, including a claim that A12's legal root pointer was a defect. Each revision was longer than the last, which mathematically guarantees more unverified assertions than the next review will tolerate. The problem was never the direction — review has twice placed it in "what is now sound" — it was that prose lets unbuilt design be asserted at zero cost, and the cost then lands on the reviewer.

## 1. Why my proposals kept failing (the part worth keeping)

Three self-similar habits, evidenced by the three review rounds rather than introspected:

**Syntactic verification standing in for semantic verification.** Counts and greps are cheap and feel like measurement, so I reach for them. But almost every finding across three rounds was semantic: is the empty pointer legal in A12; is this parameter inbound or outbound; is `1.5` hostile for a document value; does an annotation reach a historical class. None is a grep, and reaching for the measurement I *could* do rather than the one the claim *needed* is the same act every time.

**Never attacking my own design.** Two round-3 findings needed no execution at all — one measure's acceptance was circular on its face, another had a one-line defeat case. I run that adversarial pass on code I review and not on my own work.

**Treating the written lesson as the learned lesson.** Revision 2 §1 named the generator and then committed it twice in the same document. [TESTING-SPEC](TESTING-SPEC.md)'s six vacuity shapes were documented, then reproduced twice. Prose about my own discipline does not constrain me; a red run does.

Two more instances arrived *after* revision 3 was written, both in one hour, which is why the habit is stated as ongoing rather than corrected. Reviewing the lean project's spec, I set out to hand over an `IIdentifier` sentinel inventory as a **new** finding — it was already section 1 of the handover document I was appending to, written more completely in an earlier pass. And I concluded from that spec's "persistence / wire format is out of scope" clause that pointer-string facts were excluded, which the same document disproves two sections later. Both were caught only by reading the target file before writing to it. The generator is not "I was careless once"; it is the default mode when a claim is cheap to state.

The conclusion, and the reason this revision is shaped differently: **a design document is the wrong artifact for me to be producing here.** The right one is one measure, built, red first.

## 2. What review has already agreed is sound

Banked, so it is not re-litigated: separating representation validity from provenance from temporal ordering; the 5,219-vector bounded repetition count; compiled-artifact reflection as the independent third source against symmetric ABI loss; removing historical diff counts from current-artifact assertions; the corrected mutation-testing rationale; the three-level acceptance split; moving the issued-id registry out of live `-GAPS` ledgers; and the packed consumer as the release oracle for the JS numeric boundary.

## 3. What is measured

Only facts executed or read this session. Nothing here is a design claim.

**`format` admits four inputs `parse` rejects** — read from `A12PointerFormat.kt`, byte-unchanged since `3d54df91`; `PART = ([\w_-]+)(\[(-?\d+)?\])?`, and `\w` excludes `$`:

| input | `format` emits | `parse` on that output |
|---|---|---|
| `format("Order/Count", [0,1])` | `Order[0]/Count` | rejects — `[0]` legal only on the last part |
| `format("Order/Count", [-7,1])` | `Order[-7]/Count` | rejects — only `-5` is legal |
| `format("Order[2]/Count", [3,1])` | `Order[2][3]/Count` | rejects — not a valid path part |
| `format("Order$/Count", [1,1])` | `Order$/Count` | rejects — `$` is outside the name grammar |

**`format("", [])` is NOT among them.** Revision 2 listed it as a fifth defect. It is A12's **root pointer**: `DocumentPointerImpl.parseFullName("")` returns `Collections.emptyList()`, and the class gives the value a display name — `"(empty DocumentPointer)"` — which no illegal value gets. Rejecting zero arity would invent a shape stricter than A12's, breaking the project's first interop rule inside a document about preventing defects. Verified against kernel source at `kernel-md/kernel-md-document-v2/…/DocumentPointerImpl.java:95` and `:154`.

**The TS façade validates inbound numbers at three of four call sites** — `model.ts:161` (`Number.isSafeInteger || < 1` → typed `coordinate-not-positive`), `model.ts:169` (range), `limits.ts:35` (the factored one), `documents.ts:97` (`Number.isFinite`) — and `pointers.ts:49` passes `[...repetitions]` straight through. Nothing fails the build when a new call site forgets.

**Two independent scanners live in one guard class.** `GapLedgerTemplateGuardTest` has `audit` and `auditIds`; `InterpreterAbiGoldenDepthTest` parses two golden forms and checks package leakage; `DemoOutputIsRealTest` checks tool failures and intentional nulls. Any control granularity coarser than the scanner leaves siblings vacuous.

**`docGuardTest` runs 11 classes** (`awk '/tasks.register\(.docGuardTest./,/^}/' cli/build.gradle | grep -c includeTestsMatching`) and contains **two** of the six scanners that motivated revision 1's denominator — `DemoOutputIsRealTest` and `CommandReferencesResolveTest`. `InterpreterAbiGoldenDepthTest` is in a separate task, so the broader "all tasks with declared inputs" reading contains three. Revision 2 stated 3 for `docGuardTest` itself, conflating the two figures.

**`*GuardTest` matches 18 classes** — `find . \( -name '*GuardTest.java' -o -name '*GuardTest.kt' \) | grep -v /build/ | grep -v /worktrees/`. The worktree exclusion is load-bearing: without it, 36.

**Nested ABI types: JVM 55, KLIB 72, reconciling 51 = 51** after excluding four JVM-only `$DefaultImpls` and 21 KLIB enum entries. **ABI diff: KLIB +970/−55, JVM +268/−0** (`git diff --numstat fd3e3f1c^ fd3e3f1c -- interpreter/api/`), so "strictly additive, 0 removed" held only for the JVM dump.

**`A12Pointer` does not snapshot its lists**, and the project's existing snapshot idiom does not transfer: `IncludeAssembly` shadows non-`val` constructor params with snapshotting body properties, but it is a plain `class`. `A12Pointer` is a published `data class`, so that shape would drop `copy()`/`componentN()`, and a private primary constructor still leaves the generated `copy()` bypassing validation. Still open — the domain fix did not need to touch the constructor (§6).

**The four asymmetries are now fixed and locked**, so the table above is the *pre-fix* artifact this measure went red against, not current behaviour. Measured scale on that artifact: **4,321** emitted strings `parse` refuses. Current behaviour, and what the fix cost, is §6.

## 4. The open questions — to be answered in code, not here

Each of these was **asserted** in revision 1 or 2 and each assertion was wrong or unverifiable. They are questions now.

1. ~~**Where can the pointer domain rule live, given a published `data class`?**~~ **ANSWERED by building it, and the answer was to sidestep the question.** The rule did not need to go into `A12Pointer` at all: a private `requireLegalDomain(names, repetitions)` that *both* `parse` and `format` call gives one enforcement site with **no** ABI impact, so the `data class`/`copy()` problem never arises. Revision 2 asserted the constructor as the fix and review correctly showed the sketch was impossible in Kotlin; the buildable answer was a third option neither of us named. **The snapshot half remains open** — `A12Pointer` still stores caller-owned lists, and fixing that *does* face the ABI question, so it is a smaller separate decision rather than part of this one.
2. **What is the oracle for the NAME grammar?** Partly answered, and the residue is sharper than before. The built test carries a name corpus as an **independently authored axis** — the vector sweep cannot generate an illegal name — and it caught `Order[2]` and `Order$`. But that corpus is *authored*, so it has the denominator weakness of every authored list: it covers the ways I thought of. The grammar is now single-sourced in one regex constant that both entry points share, which bounds the divergence risk; it does not establish that the grammar itself matches A12's.
3. **What is the right granularity for a scanner's positive control** — method, property, or provider case — given that one class holds several scanners?
4. **How can a meta-guard reject a historical scanner that lacks a control,** when a historical class also lacks the marker that would make it visible? Revision 2's acceptance criterion was circular.
5. **What is the semantic denominator for the JS numeric boundary?** Counting `number`-bearing lines in `typescript.api` reconciles lines to lines, which proves nothing: some aliases are bidirectional, one line carries three parameters, and a new method reusing an existing alias adds no line while adding an obligation. Callable signatures plus transitive parameter types, classified per use site, is the shape — the derivation is not designed.
6. **What is the hostile set per domain?** A single universal set is wrong: `1.5` is a legitimate `A12DocumentField` (`documents.ts` deliberately accepts every finite number), `"ALL"` is a legitimate `RelevantCoordinate`, and "wrong arity" is meaningless for a scalar limit. Uniform obligation or domain-specific partition — and if the latter, what stops the exemptions from swallowing the rule?
7. **What is M3's acceptance evidence?** The reflection check must go red against the real shallow pre-fix goldens; that run has not happened.

## 5. How the answers should arrive

One measure at a time, each as a commit that contains its own red-run evidence, reviewed as code. Code cannot carry a false claim about itself: a guard either fails against the real pre-fix artifact or it does not, and there is no room in it for a plausible sentence.

**Nothing else in §4 should be built until its question is answered**, because every previous attempt to answer them in prose has been wrong.

## 6. What building the first measure actually taught (`a27453c8`)

The M1 lock is built. Reporting it here because three of the four results were unavailable from any amount of prose, which is the argument this document is making.

**The defect was 1,000× larger than my hand-written list.** I had enumerated four asymmetry *classes* by reading. Executed over the full Cartesian — 5,219 vectors across arities 1–3 and repetitions `-8..8`, plus the name corpus — `format` emitted **4,321** strings its own parser refuses. The classes were right; the scale was invisible to reading, and the number came from the test's own failure message rather than from me.

**One of my two laws was unsound, and only running it showed that.** The second law originally quantified over `(names, repetitions)` pairs and rebuilt the canonical string from them to ask whether `parse` accepted it. For an **ill-formed** pair the rebuild names a *different* pointer: `(["Order[2]"], [1])` rebuilt to `Order[2]`, which `parse` rightly reads as `Order` repetition 2, so the law reported a *correct* refusal as over-strictness. The reconstruction step was the bug. Restating it over **strings** — everything `parse` reads, `format` must express — removed the intermediate step and the ambiguity with it. A measure can carry the same defect class as the code it measures, and prose review had not caught it in two rounds.

**The second law earned its keep immediately, twice.** It exists to catch `format` becoming *stricter* than `parse` — the shape that would have made A12's root pointer unwritable. Both times it fired, it fired on my own test's premise rather than on the production fix, which is the outcome a positive-control law is for.

**The `data class` question dissolved rather than resolving** — see §4 question 1. Both revision 2 and its review argued about where a constructor could validate; the buildable answer needed no constructor change.

Evidence, so this section is auditable: `A12PointerSymmetryTest` 3 tests / 0 skipped / 0 failures fresh; `:interpreter:check` green (2m30s, covering the Kotlin/JS unicode-mode regex construction and the purity lint); **`:adapter:test` green in 7m11s run alone**, which is the corpus replay and kernel differential accepting the stricter `format` on the document-read path — the one regression risk I could not settle by reading, since `A12DocumentJson` calls `format` nine times per document walk.
