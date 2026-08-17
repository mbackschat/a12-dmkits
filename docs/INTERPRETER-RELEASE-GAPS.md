# Interpreter release — gaps

> **What this is.** The `-GAPS` tracker for the interpreter publication boundary. Implemented release behavior belongs in [INTERPRETER-RELEASE-SPEC](INTERPRETER-RELEASE-SPEC.md). `IRG<n>` ids are stable, so a code comment or spec ref resolves.
>
> **Lifecycle.** A gap lives here only while open: §1 open, §2 deferred (trigger-gated), §3 out of scope. When a gap ships its row is **deleted** rather than retained as status history — git is the *what/when* and the graduated spec section is the *why*.
>
> **Current state.** §1 is drained. The only remaining release work is the trigger-gated IRG1 drop-in artifact in §2.

## 1. Open

_None open._

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

### IRG5 — a scripted documentation-only mirror refresh <a id="irg5"></a>

The publisher exposes `--qualify` and `--go-live` only, and `interpreter_release_stage` refuses a published tag, so correcting the mirror's landing `README.md` after publication has no scripted path. It was done by hand on 2026-08-17 for v0.13.0 under the boundary exception, following the sequence and the inventory rule in [INTERPRETER-RELEASE-SPEC §10.1](INTERPRETER-RELEASE-SPEC.md).

A `--refresh-docs` mode would re-assemble, update only mutable presentation files, recompute `PUBLIC-FILES.sha256` **over the existing mirror tree** rather than the local assembly, and refuse if any versioned API directory, Maven coordinate tree, release asset or release tag would change. The inventory source is the load-bearing detail: a locally generated inventory describes local artifacts, and javadoc JARs are not byte-reproducible, so the mismatch surfaces at the next release rather than at the refresh.

**Trigger:** a second hand-run refresh, or any refresh that needs to touch more than the landing document.

**Deferred because:** one occurrence does not yet justify a new release-machinery surface, and the hand sequence is now written down with the trap that makes it dangerous.

## 3. Out of scope

- Publishing first-party packages to npmjs.org, Maven Central, or GitHub Packages.
- Mirroring third-party dependency registries.
- Publishing the broad internal `:adapter` module.
- Moving interpreter development into the public release mirror.
