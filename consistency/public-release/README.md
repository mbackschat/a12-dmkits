# A12 Consistency Checker

Portable model consistency checks for Kotlin, Java and JavaScript. Check a DocumentModel, compare a candidate edit, inspect unfinished rule text, or trace the declarations affected by a change — without the A12 Kernel or Interpreter.

> Independent community project by Martin Backschat. This is not an official mgm A12 product and is not affiliated with or endorsed by mgm technology partners.

**[Try the browser showcase](https://mbackschat.github.io/a12-consistency-releases/showcase/)** · [API guide](https://mbackschat.github.io/a12-consistency-releases/guide.html) · [Release site](https://mbackschat.github.io/a12-consistency-releases/) · [Latest release](https://github.com/mbackschat/a12-consistency-releases/releases/latest)

## Try it in your browser

The [focused showcase](https://mbackschat.github.io/a12-consistency-releases/showcase/) includes a passing Storefront sample and a broken reference. Paste or open a local DocumentModel, check it, then try draft rule text. Findings, source diagnostics, introduced/resolved counts and coverage remain separate. Local model contents are processed in the browser; they are not uploaded.

## Read the verdict

- `INVALID`: a rejection was established. Inspect the findings and their `justifiesRejection` values.
- `ALL_CHECKS_PASSED` with `INCONCLUSIVE`: the implemented checks passed. This does **not** establish full A12 acceptance.
- `complete: false`: coverage is partial. Read `checksCompleted`, `gaps` and source diagnostics; a completed family does not mean every declaration was covered.

The checker examines **model declarations**. The [Interpreter](https://mbackschat.github.io/a12-interpreter-releases/) evaluates **Documents**. Full A12 acceptance belongs to the Kernel.

![How the checker fits into your application](https://mbackschat.github.io/a12-consistency-releases/assets/a12-consistency-checker.avif)

## Install

Current release: **@VERSION@**. JVM consumers require Java 21 or newer.

### Gradle — Kotlin Multiplatform or JVM

```kotlin
repositories {
    maven("https://mbackschat.github.io/a12-consistency-releases/maven/")
    mavenCentral()
}
dependencies {
    implementation("io.github.mbackschat.a12.dm:dm-consistency:@VERSION@")
}
```

Gradle selects the appropriate JVM or JS variant. `dm-consistency` is the multiplatform entry coordinate; `dm-consistency-jvm` is its JVM artifact, not a separate checker implementation. The matching `dm-predicates` artifacts are transitive dependencies.

### Maven — Java / JVM

```xml
<repositories>
  <repository>
    <id>a12-consistency</id>
    <url>https://mbackschat.github.io/a12-consistency-releases/maven/</url>
  </repository>
</repositories>
<dependencies>
  <dependency>
    <groupId>io.github.mbackschat.a12.dm</groupId>
    <artifactId>dm-consistency-jvm</artifactId>
    <version>@VERSION@</version>
  </dependency>
</dependencies>
```

### Kotlin

```kotlin
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.consistency.toJson

val report = check(ModelSource(modelJson))
println(report.toJson())
```

See the [API guide](https://mbackschat.github.io/a12-consistency-releases/guide.html) for reports, draft checks and integration choices. Java can consume the JVM artifact; the included sources document the typed Kotlin/JVM entry points. JavaScript powers the showcase, but **npm registry publication is not part of this release**.

## What ships

| Coordinate | Purpose |
|---|---|
| `dm-consistency` | Kotlin Multiplatform metadata and variant selection |
| `dm-consistency-jvm` | JVM checker |
| `dm-consistency-js` | Kotlin/JS checker |
| `dm-predicates`, `dm-predicates-jvm`, `dm-predicates-js` | Matching predicate dependency variants |

All coordinates use group `io.github.mbackschat.a12.dm`. This repository hosts the Maven repository, source artifacts, a repository ZIP, release manifest and checksums. It does not publish to Maven Central. Existing versioned Maven artifacts and release assets are immutable; the website and showcase can receive presentation updates separately.

## Architecture

The checker and Interpreter share portable predicate support. GraalVM provides native delivery for the separate dmtool CLI; the checker itself is a library targeting JVM and JavaScript.

![Kotlin, GraalVM and Kernel roles](https://mbackschat.github.io/a12-consistency-releases/assets/kotlin-graalvm-kernel-roles.avif)

<!-- source-statistics:checker-release:start -->
## Source statistics

Measured with [locmetry](https://github.com/mbackschat/locmetry) from the portable checker, predicate dependency, shared regex sources, and browser showcase, using [`.locmetryrc`](https://github.com/mbackschat/a12-consistency-releases/blob/main/.locmetryrc). Only tracked source files are counted; generated code, data fixtures, documentation, third-party wrappers, and build output are excluded. Code excludes comments and blank lines.

### Production

Product implementation and examples.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| CSS | 1 | 1 | 1 | 0 | 0 |
| HTML | 1 | 14 | 14 | 0 | 0 |
| JavaScript | 1 | 176 | 72 | 97 | 7 |
| Kotlin | 188 | 32011 | 18097 | 11646 | 2268 |
| TypeScript | 1 | 73 | 72 | 0 | 1 |
| **Total** | **192** | **32275** | **18256** | **11743** | **2276** |

### Verification

Automated tests, laws, differentials, consumer checks, and their support code.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| HTML | 4 | 101 | 56 | 45 | 0 |
| Java | 1 | 25 | 24 | 0 | 1 |
| JavaScript | 10 | 878 | 529 | 262 | 87 |
| Kotlin | 245 | 35211 | 26027 | 6366 | 2818 |
| TypeScript | 14 | 1861 | 1246 | 468 | 147 |
| **Total** | **274** | **38076** | **27882** | **7141** | **3053** |

Maintainers regenerate these tables with the latest locmetry; release publishers compare them with fresh counts before any public mutation.
<!-- source-statistics:checker-release:end -->

## License and privacy

MIT. See [LICENSE](https://github.com/mbackschat/a12-consistency-releases/blob/main/LICENSE) and the [privacy notice](https://mbackschat.github.io/a12-consistency-releases/privacy.html). Release sources are included in the Maven publications. The source repository is private; this mirror is the public distribution and documentation surface.
