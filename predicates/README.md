# `:predicates` — one A12 law, one live statement

## The problem

A12 refuses some things outright. It refuses an element whose name sits in a reserved namespace. It refuses certain regular expressions in a string pattern. Both refusals have to be applied in **two** completely separate implementations here:

- [`:rulekit`](../rulekit), which is Java, refuses them as you build a `Rule` or a `Computation`
- [`:consistency`](../consistency), the portable checker, which is Kotlin Multiplatform, reports them when it reads a stored model

Before this module existed, those two could share a law **only by each restating it** — and restated laws drift. They did:

| law | what had happened |
|---|---|
| the reserved-namespace refusal | **three** copies existed, and they had already drifted apart |
| A12's pattern-admission blacklist | **two** copies, disagreeing in *both* directions — `:core`'s `KernelPatternPolicy` against the checker's pattern family |

Drift here is the expensive kind: each copy is individually plausible, every test passes, and the two products quietly disagree about what A12 accepts.

## What this module does

It is the one place either side can reach. A law lives here as a plain function over a stored declaration, and both callers call it — so there is exactly one live statement of each:

| function | called from Java | called from Kotlin |
|---|---|---|
| `isReservedElementName` | [`Rule`](../rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/Rule.java), [`Computation`](../rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/Computation.java) | the checker's declaration-slot family |
| `kernelPatternBlacklist` | [`KernelPatternPolicy`](../core/src/main/java/io/github/mbackschat/a12/dm/core/KernelPatternPolicy.java) | the checker's pattern-admission and comparison families |

Being reachable from both is the *whole* requirement, and it is what forces the module's shape: Kotlin Multiplatform, so the JVM kits and the JS checker both resolve it, and every function pure and side-effect-free, so neither caller inherits a dependency or a lifecycle.

## Narrowness is the contract, not an accident

`verifyPredicatesPurity` fails on **any** resolved dependency beyond the Kotlin standard library — not even `kotlinx` — and, since 2026-09-17, on any intra-repo project edge too. (That arm was silently dropping project edges, because a project component carries this repository's own group and the module filter excluded it.)

The reason is that a leaf which grows a dependency stops being a leaf and starts being **a second checker**: once it can read a model, it accretes logic that then has to be kept in agreement with the checker, which is the problem this module was built to remove.

**A predicate earns a place here by having two consumers that would otherwise state the same law twice.** That is the admission test. A law with one consumer belongs in that consumer; `:rulekit` holds plenty of refusals that no other implementation applies, and they stay there.

## Reference

Its publication coordinate is `io.github.mbackschat.a12.dm:dm-predicates` (MIT). Kotlin Multiplatform (JVM + JS), package `io.github.mbackschat.a12.dm.predicates`. It carries a coordinate of its own because `:rulekit`'s POM depends on it, and a shipped POM naming a coordinate nothing publishes is a consumer who cannot resolve the kit. Being multiplatform, that root coordinate is a Gradle-module marker; `dm-predicates-jvm` carries the JVM classes, so a mirror must ship both or resolution succeeds and links nothing usable. The publish wiring is landed and the public deploy is **deferred** ([`../docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md) §11), so today you get it by `./build.sh publishToMavenLocal` and `mavenLocal()`, not from a registry.

```sh
./build.sh :predicates:check   # check, not test
```

`check` rather than `test`, for two reasons: a Kotlin Multiplatform module registers no plain `test` task at all, and the purity lint hangs off `check`.

Whether a Java builder and the portable checker actually agree — the thing sharing a law is meant to achieve — is held separately by `BuilderCheckerDivergenceGuardTest` at the [root project](../src).
