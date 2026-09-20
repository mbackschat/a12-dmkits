# `:adapter` — the kernel backend, and the only module that touches it

**Not published, deliberately.** Package `io.github.mbackschat.a12.dm.adapter`.

The kits are kernel-free; this module is where the real A12 Kernel lives. It is the *one* place in the repository that reads the kernel's `a12internal` tier, and keeping it unpublished is what makes that containment a fact rather than a policy: the internal tier can never reach a consumer's classpath, because no consumer can resolve this artifact at all.

**It supplies the SPI providers that turn a kernel-free kit into a kernel-confirmed one.** Registered through `META-INF/services`: `KernelConsistencyOracle` (so `BoundModel.ofJson` and `RuleValidator.check` answer `KERNEL_CONFIRMED`), `KernelBackendInfo`, and `KernelConditionReadBack` — the authoritative, *total* read-back implementation, where [`:interpreter-bridge`](../interpreter-bridge)'s covers a subset. Put this module on a runtime classpath and the kits gain the kernel oracle; leave it off and they still load, build and render, reporting `STRUCTURAL_ONLY` instead of claiming a verdict nobody gave.

Beyond the ports it carries [`KernelAdapter`](src/main/java/io/github/mbackschat/a12/dm/adapter/KernelAdapter.java) — an opt-in read / convert / rewrite surface over the internal AST — the semantic-diff projectors behind `dmtool model review`, and the string-only `ModelProfiles.extract`/`compare` performance-calibration boundary.

**It is also where the interpreter is held honest.** The kernel cannot be the oracle inside [`:interpreter`](../interpreter) or [`:consistency`](../consistency), whose whole claim is that no kernel is present — so their **differential** suites live here, beside the kernel they compare against: rule-for-rule differentials, a model-driven fuzzer, the conformance-corpus replay, and `ConditionReadBackParityTest`.

**`:adapter:test` is its own Gradle invocation and gets the machine to itself.** It carries timing assertions, so running it beside another build, a demo, a native build or an eval contends for user-mode cycles that no clock filters out. The pinned JDK is enforced at the seam every timed observation passes through — `JdkIdentity.requireSupported()` refuses rather than recording a figure it cannot compare.

```sh
./build.sh :adapter:test        # alone — nothing else running
```

Requires the kernel in `mavenLocal`, published by the sibling `a12-kernel-bootstrap` kit. Design: [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) "Kernel coupling — three tiers" · notes: [`docs/KERNEL-INTEGRATION-NOTES.md`](../docs/KERNEL-INTEGRATION-NOTES.md) · licensing: [`docs/LICENSING.md`](../docs/LICENSING.md).
