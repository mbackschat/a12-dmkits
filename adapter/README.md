# `:adapter` — where the A12 Kernel actually lives

## The problem

Every other module here is deliberately kernel-free, because the kernel is EUPL-licensed, is not on Maven Central, and cannot go into a native image. But the kernel is also the **only** authority on whether a model is valid, the only canonical formatter and safe-rewriter, and the only thing against which a clean-room reimplementation can be proved equivalent.

So the kernel has to be reachable, and it has to be reachable from exactly **one** place — because a kernel dependency that spreads is a kernel dependency that reaches a consumer, and then an MIT artifact has pulled copyleft across a boundary nobody inspected.

## What this module does about it

It is that one place, and it is **not published** — which is what turns containment from a policy into a fact. The kernel's internal `a12internal` read tier can never reach a consumer's classpath, because no consumer can resolve this artifact at all.

**It supplies the providers that turn a kernel-free kit into a kernel-confirmed one.** Registered through `META-INF/services` against [`:core`](../core)'s ports:

| provider | what it makes true |
|---|---|
| `KernelConsistencyOracle` | `BoundModel.ofJson` and `RuleValidator.check` are arbitrated by the real kernel, and `Backends.verification()` answers `KERNEL_CONFIRMED` |
| `KernelBackendInfo` | a caller can ask *which* kernel answered, and its version |
| `KernelConditionReadBack` | the **total** condition read-back, where [`:interpreter-bridge`](../interpreter-bridge)'s covers a subset |

Beyond the ports it carries [`KernelAdapter`](src/main/java/io/github/mbackschat/a12/dm/adapter/KernelAdapter.java) — an opt-in read / convert / rewrite surface over the internal AST, which is what `dmtool`'s richer read verbs use — the semantic-diff projectors behind `model review`, and the string-only `ModelProfiles.extract`/`compare` performance-calibration boundary.

## It is also where the clean-room engines are held honest

[`:interpreter`](../interpreter) and [`:consistency`](../consistency) both claim to reproduce kernel behaviour with no kernel present. Neither can test that claim inside itself — the oracle it needs is the very thing its purity lint forbids. So their **differential** suites live here, beside the kernel they are compared against: rule-for-rule differentials, a model-driven fuzzer, the conformance-corpus replay, and `ConditionReadBackParityTest`.

## Running it: alone, on the pinned JDK

`:adapter:test` carries timing assertions, so it is **its own Gradle invocation** — never beside another build, a demo script, a native build or an eval, and never fanned across concurrent CI dispatches. Those contend for user-mode cycles that no clock filters out.

```sh
./build.sh :adapter:test        # nothing else running
```

You do not have to remember the JDK pin: `JdkIdentity.requireSupported()` sits on the seam every timed observation passes through and **refuses, recording nothing**, rather than storing a figure that cannot be compared. If you see that refusal, fix the toolchain — never the anchors.

## Reference

Not published (owner ruling). Package `io.github.mbackschat.a12.dm.adapter`. Requires the kernel in `mavenLocal`, published by the sibling `a12-kernel-bootstrap` kit.

Kernel coupling and its three tiers: [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) · integration facts: [`docs/KERNEL-INTEGRATION-NOTES.md`](../docs/KERNEL-INTEGRATION-NOTES.md) · what the licence boundary means for which artifact: [`docs/LICENSING.md`](../docs/LICENSING.md) · measured kernel behaviour: [`docs/KERNEL-FINDINGS.md`](../docs/KERNEL-FINDINGS.md).
