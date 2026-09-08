# Guard task inputs — keeping the declaration and the read set the same thing

> **What this is.** A PROPOSAL. A guard that reads a repo file nothing declares leaves its lane `UP-TO-DATE` on an edit to that file, so the guard stays green while checking nothing. One such gap shipped and was measured; a gate now refuses new ones. This asks whether the gate is the right end state, or a detector for a shape that should not exist.
>
> **Status.** The gate is landed. Everything below the first section is proposed, not built — except that `guardInputs` itself now lives in the `build-logic` included build as `a12Guards.guardInputs`, with its coverage and empty-tree rules unit-tested and both refusals proved to fire ([BUILD-LOGIC-PROPOSAL](BUILD-LOGIC-PROPOSAL.md) W1–W5). That does not answer this doc's question: the two derivations are still two, and making them one object is W6a, which is blocked.

## What is already true

`guardInputs` declares a guard's runtime-read files as task inputs and fails a task whose declared tree resolves empty. Two additions landed on 2026-09-02:

- `Checkout` records every path it hands out, and each guard task's own `doLast` compares that against its declared inputs, **failing** the build on a read nothing covers.
- **Known limitation, chosen deliberately:** a `doLast` is skipped when its task fails, so the check does not run on a red guard. A shared finalizer was tried instead and reverted — it runs even when its guard is SKIPPED, and then judges a task that never executed against an observation directory left by an earlier build. Measured: under `-Pfast`, `docGuardTest` does not run while the finalizer still reports on it, and on an `UP-TO-DATE` run the directory is neither cleared nor rewritten, so an absent directory reads as "no reads" and passes vacuously. A false refusal is what gets a guard switched off rather than repaired; not checking a red run is the smaller cost. A correct finalizer needs a per-invocation discriminator telling it which observations belong to THIS build.
- Two real gaps it found: `LockCitationResolvesTest` read seven docs while its task declared one, and `jsonConstructionGuardTest` scanned `interpreter-bridge/src/main`, which arrived with that module while the declaration list stayed as it was.

**Reach is narrow, and the number matters.** `Checkout` exists only in the root project's test sources, so of the 26 `guardInputs` sites the recorder can observe the 8 in the root build and none of the 18 in `:cli` (7, which use a second copy of the idea in `RepoWalk`), `:adapter` (8) or `:interpreter` (3).

## The question

The gate **detects** drift. It does not **prevent** it. A guard can still read a file nobody declared; the build only refuses afterwards. The alternative is to make the two the same object: Gradle resolves the files and passes them to the test, so the test cannot read anything undeclared because it never learns of anything else.

## What the denominator is, per guard

Three categories, and the right mechanism differs:

| the guard's denominator | example | mechanism |
|---|---|---|
| a fixed list of files | `LockCitationResolvesTest` (seven docs), attribution surfaces, version mentions | the guard names them; the declaration must match, which the gate enforces |
| a directory tree | `DocsIndexGuardTest` over `docs/**/*.md` | same |
| **the git-tracked set** | `PrivateMarkerLeakGuardTest`, `SurvivingDataNotInBuildGuardTest`, `RawClockCensusTest` | see below |

The third row is why `Checkout` exists, and it is load-bearing: the tracked set is exact and closed, while a working-directory walk takes every tool's output as its input. Three hand-rolled spellings of "the test sources" once scanned 1571, 1295 and 1170 files, and the narrowest matched none of the interpreter's 273 Kotlin-Multiplatform test sources.

## What a measurement changed about the third row

`build.gradle` asserted that the git-tracked set "is not something a Gradle input can express". **That is false**, and the correction matters because it reopens the third row. Measured on Gradle 9.0.0 with `providers.of(<a ValueSource running git ls-files>)` mapped to a `FileCollection`:

| case | result |
|---|---|
| no change | `UP-TO-DATE` |
| tracked file's content changes | re-runs |
| untracked file created | `UP-TO-DATE` — correctly ignored |
| a file is `git add`ed | re-runs, with the new file in the set |
| all four under `--configuration-cache` | entry stored, then **reused** while the task still re-ran on the `git add` |

`git ls-files` costs **7 ms** on this repository. The claim was true of the approach that had been tried — a `fileTree` with excludes — and false of the mechanism Gradle provides for external state.

## Tried and backed out: passing the files in

The obvious improvement — Gradle resolves the files and hands them to the test, so the read set and the declaration are the same object — was built as a pilot on `LockCitationResolvesTest` and **reverted**. Three things it needed, in order, each a sign it was fighting the grain:

1. **A wire protocol.** `guardInputs` takes a human-readable named map, so the sets had to be keyed by a slugified name to cross into the test JVM. Stringly-typed, invented here, checked by nothing.
2. **A listing file per set.** Passing the paths as a `-D` argument overflowed the worker JVM's command line — a declared tree can hold thousands of files — so each set had to be written to `<temporaryDir>/declared-sets/<slug>.txt` and re-read. `Test.systemProperty` also stores a `Provider` as its `toString` rather than resolving it, which cost a debugging cycle.
3. **A floor constant.** Once the declaration is the guard's SUBJECT, removing an entry from it no longer changes *when* the guard runs — it changes *what the guard checks*, silently and with no failure. A `MIN_CITING_DOCS` floor had to be added to stop that reaching zero unnoticed.

The third is the real objection, and it is a design one rather than a plumbing one. **A declared input answers "when must this task re-run"; a guard's subject answers "what does this check".** They coincide often enough to look like one thing, and the pilot conflated them — which coupled the guard's scope to an edit in `build.gradle` and traded a silent-staleness failure for a silent-narrowing one.

Two further costs: a repo-scanning guard reports against **repo-relative paths** (`docs/KERNEL-FINDINGS.md cites a missing class`), so staging inputs into a build directory would either lose that identity or need a manifest mapping back; and the whole approach reaches only the fixed-list category, which is the one already least likely to drift.

So the read observer stays the mechanism: it keeps the two in sync **without** making either the other.

## The proposal

1. **Do not migrate guards onto their declared inputs** — see the section above; it was tried and reverted.
2. **Leave the git-denominator guards alone.** A `ValueSource` would work, but `upToDateWhen { false }` is a guarantee that cannot go stale, and swapping it for one that depends on a value source being re-evaluated is a deliberate trade, not a cleanup. It needs its own decision.
3. **Keep the gate.** It is the mechanism, not a stopgap: it holds the declaration and the read set in step without making either one the other.

**Non-goals.** Converting all 26 `guardInputs` sites in one change; moving `:cli`'s `RepoWalk` guards; giving the shared helpers a build of their own — that is [BUILD-LOGIC-PROPOSAL](BUILD-LOGIC-PROPOSAL.md)'s question, and a typed `guardInputs` is its W3.

## What would reopen this

- A guard whose subject genuinely IS "whatever Gradle declared" rather than a named part of the repository. None exists today; the three categories above are all repo-defined.
- `guardInputs` growing a typed, per-property form — named `@InputFiles` properties instead of a Groovy map. That is the part of the current shape that reads as un-idiomatic, and it is worth doing on its own merits; it does not by itself change who resolves the files. Carried as W3 of [BUILD-LOGIC-PROPOSAL](BUILD-LOGIC-PROPOSAL.md), which is where the helper would have to live to be unit-testable.
- The `:cli` guards, which use `RepoWalk` and are outside the gate's reach entirely.
