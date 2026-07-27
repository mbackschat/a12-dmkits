# Interpreter release gaps

This document tracks only open or trigger-gated work in the interpreter publication boundary. Implemented release behavior belongs in [INTERPRETER-RELEASE-SPEC](INTERPRETER-RELEASE-SPEC.md); a closed gap is deleted rather than retained as status history.

## Open

### IRG2 — publish the showcase semantics badge

The browser showcase names the A12 Kernel semantics target beside the package version, single-sourced from the root `kernelVersion`/`a12Distribution` pins and asserted by the Chrome smoke gate. It is implemented but not on the public site: rendering it needs a rebuilt browser bundle, and refreshing that bundle under an already-published tag would leave the live showcase differing from the immutable `dm-interpreter-<version>-showcase.zip` asset of that release. The public README states the same target and does ship, because it has no immutable asset counterpart.

**Trigger:** the next interpreter release, which regenerates the whole mirror from source.

**Completion:** the deployed showcase renders the semantics target and the Pages showcase tree matches that release's showcase asset.

## Trigger-gated

### IRG1 — publish the separate Kernel drop-in artifact

The portable MIT interpreter is published independently. A narrow JVM artifact may later implement the pinned Kernel `IDocumentRtService` contract so an existing Kernel-consuming application can replace its implementation while keeping the interface, customization registration, and result projections.

The API and runtime compatibility work is owned by [IAG2 in INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md#iag2--jvm-idocumentrtservice-drop-in). This release gap owns only the additional artifact boundary:

- artifact identity `dm-interpreter-kernel-dropin`;
- EUPL-1.2 source, binary, POM, documentation, and notice metadata;
- Kernel interfaces as compile-only or host-provided dependencies;
- no embedded Kernel bytecode or transitive Kernel runtime;
- corresponding source availability;
- a bytecode/dependency scan plus a pinned external consumer that substitutes the implementation without changing application code;
- release notes naming the exact supported Kernel interface/version range.

**Trigger:** the owner explicitly authorizes the drop-in program and a pinned Kernel interface baseline is available for compilation and differential verification.

**Completion:** the real host consumer, source/license checks, dependency and bytecode scans, complete interface projection, customization lifecycle, validation/computation differentials, and publication smoke all pass. Closing this gap also closes or narrows IAG2 in the same commit.

## Out of scope

- Publishing first-party packages to npmjs.org, Maven Central, or GitHub Packages.
- Mirroring third-party dependency registries.
- Publishing the broad internal `:adapter` module.
- Moving interpreter development into the public release mirror.
