# Interpreter release — gaps

> **What this is.** The `-GAPS` tracker for the interpreter publication boundary. Implemented release behavior belongs in [INTERPRETER-RELEASE-SPEC](INTERPRETER-RELEASE-SPEC.md). `IRG<n>` ids are stable, so a code comment or spec ref resolves.
>
> **Lifecycle.** A gap lives here only while open: §1 open, §2 deferred (trigger-gated), §3 out of scope. When a gap ships its row is **deleted** rather than retained as status history — git is the *what/when* and the graduated spec section is the *why*.
>
> **Current state.** §1 is drained. The only remaining release work is the trigger-gated IRG1 drop-in artifact in §2.

## 1. Open

_None open._

## Retired ids

Every `IRG<n>` ever issued that has no live row, so a citation of one resolves here and **no number is ever reused**. **Not yet enforced**, and this section belongs on its own registry surface rather than inside a live ledger — see the note under [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md) "Retired ids" for both corrections. `IRG2` is why the section exists — it named two unrelated gaps at different times, and a citation in an archived plan could not be told apart from a citation in this file.

| id | what it meant | disposition |
|---|---|---|
| IRG2 | the showcase semantics badge was built and gated but deliberately not deployed under v0.9.1 | shipped with release 0.9.2; cited from [`archived/A12-SHAPE-CONSOLIDATION.md`](archived/A12-SHAPE-CONSOLIDATION.md) step 6, which is where that plan recorded the closure |
| IRG2 *(reused — the defect this section exists to prevent)* | both built-in showcase models computed only into repeatability-1 targets, so the page never demonstrated per-row computed identity | shipped 2026-08-01; the permit model now charges each site-section row its own survey fee and the page renders the joined canonical pointer beside the raw `pointer`. Contract in [INTERPRETER-RELEASE-SPEC](INTERPRETER-RELEASE-SPEC.md) |
| IRG3 | `interpreter.klib.api` was a type-name inventory, so a member-level break on the Kotlin/JS surface passed `checkLegacyAbi` silently while the JVM and TypeScript goldens caught it | shipped 2026-08-01; both Kotlin goldens are member-level and `InterpreterAbiGoldenDepthTest` fails the build if either goes shallow or the ABI filter is widened into an engine package to fake depth |

## 2. Deferred — with re-open triggers

### IRG1 — publish the separate Kernel drop-in artifact <a id="irg1"></a>

The portable MIT interpreter is published independently. A narrow JVM artifact may later implement the pinned Kernel `IDocumentRtService` contract so an existing Kernel-consuming application can replace its implementation while keeping the interface, customization registration, and result projections.

The API and runtime compatibility work is owned by [IAG2 in INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md#iag2). This release gap owns only the additional artifact boundary:

- artifact identity `dm-interpreter-kernel-dropin`;
- EUPL-1.2 source, binary, POM, documentation, and notice metadata;
- Kernel interfaces as compile-only or host-provided dependencies;
- no embedded Kernel bytecode or transitive Kernel runtime;
- corresponding source availability;
- a bytecode/dependency scan plus a pinned external consumer that substitutes the implementation without changing application code;
- release notes naming the exact supported Kernel interface/version range.

**Trigger:** the owner explicitly authorizes the drop-in program and a pinned Kernel interface baseline is available for compilation and differential verification.

**Completion:** the real host consumer, source/license checks, dependency and bytecode scans, complete interface projection, customization lifecycle, validation/computation differentials, and publication smoke all pass. Closing this gap also closes or narrows IAG2 in the same commit.

## 3. Out of scope

- Publishing first-party packages to npmjs.org, Maven Central, or GitHub Packages.
- Mirroring third-party dependency registries.
- Publishing the broad internal `:adapter` module.
- Moving interpreter development into the public release mirror.
