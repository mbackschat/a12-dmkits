# Interpreter release — gaps

> **What this is.** The `-GAPS` tracker for the interpreter publication boundary. Implemented release behavior belongs in [INTERPRETER-RELEASE-SPEC](INTERPRETER-RELEASE-SPEC.md). `IRG<n>` ids are stable, so a code comment or spec ref resolves.
>
> **Lifecycle.** A gap lives here only while open: §1 open, §2 deferred (trigger-gated), §3 out of scope. When a gap ships its row is **deleted** rather than retained as status history — git is the *what/when* and the graduated spec section is the *why*.
>
> **Current state.** §1 is drained. The only remaining release work is the trigger-gated IRG1 drop-in artifact in §2.

## 1. Open

### IRG2 — the showcase never demonstrates a repeated computed field <a id="irg2"></a>

Both built-in showcase models compute only into repeatability-1 targets (`permit-basic` → `/Permit/ReviewedArea`, `/Permit/EstimatedFee`; `inspection-checks` → `CopyScore`), so the page never shows the case where per-row identity matters. A user who uploads a model with a computation inside a repeating group sees several results whose `pointer.path` is the *same* string, distinguished only by a separate `coordinates` array that the showcase renders as raw JSON and never joins into a pointer.

Two things to fix together, since either alone still misleads: give a built-in scenario a repeated computed field, and render a formatted per-row pointer beside the raw `pointer` object. The formatter already exists — `consumer-tests/browser/src/qualification.ts` hand-rolls one — but it should come from the published utility instead, which is [IAG9](INTERPRETER-API-GAPS.md#iag9).

**Why it is worth doing rather than noting:** the qualification test *does* exercise per-row identity and passes (`corpus/cases/compute/order-stock-per-row.json`), so the capability is real and only the demonstration is missing — exactly the shape of defect dmtool's own `model compute` demo turned out to have on 2026-08-01, where a worked example had been CI-green for months while showing an empty result. A demonstration that runs is not a demonstration that shows the thing. Found 2026-08-01.

### IRG3 — the KLIB golden cannot catch a member-level break <a id="irg3"></a>

`interpreter/api/interpreter.klib.api` is a **type-name inventory**: every one of its non-`enum entry` lines is a declaration header, and `build.gradle` consumes it through a regex over `^(?:final |abstract |sealed )*(?:enum class|class|fun interface|interface|object)` to generate the llms.txt type list. It therefore locks *which types exist* on the Kotlin/JS surface and nothing about their members — a removed function, a changed signature, or a dropped property on an existing type passes it silently.

That is not drift; it is what the file is for. But it means the JS/KLIB surface has strictly weaker protection than the JVM one (`interpreter.api`, member-level) and the TypeScript one (`typescript.api`, member-level), and the gap is invisible because all three are called "the goldens". Either add a member-level KLIB dump beside the inventory, or state in `INTERPRETER-RELEASE-SPEC` that the TS golden is the JS surface's member-level lock and the KLIB file is deliberately coarser. Found 2026-08-01 while auditing the three goldens against a public-API addition; the addition itself was caught, by the JVM and TS goldens.


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
