# GraalVM native image — recipe, findings & status

The single durable home for building the `dmtool` CLI as a **GraalVM native image**. Other docs point here and don't duplicate it:

- [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §3a — the release artifact. · [`CLI-SPEC.md`](CLI-SPEC.md) §11 — packaging *design/decision*. · `CLAUDE.md` "Build" — routes here.

> **Why its own doc:** native-image for a kernel-embedding CLI has non-obvious, interacting constraints (closed-world reflection, Groovy's `invokedynamic`, resource bundling, GraalVM-version quirks). It is **release-only** — never in the dev inner loop (that stays JVM/`installDist`), never in CI.

## 1. Status (2026-06-14, GraalVM **25.0.3**)

| Aspect | State |
|---|---|
| Toolchain | **GraalVM for JDK 25** (`graalvm-25.jdk`), plugin `org.graalvm.buildtools.native:1.1.1`, Gradle 9 |
| Build | ✅ ~45–60 s |
| Size | ✅ **19 MB** bare, **~33 MB** with reflection metadata — both via `-Os` (GraalVM 25 only) |
| Groovy | ✅ compiles on 25 (no analysis failure), but **excluded from the native classpath** (startup-init issue, §3.1) → JVM keeps it |
| **Showboat gate** | **5 / 6 demos PASS** — only `cli-runtime.md` fails (the runtime-eval verbs, §4) |
| Reflection coverage | ✅ **rule-based over the two bounded reflective-surface packages** (§3.4) — Jackson POJOs (`md.model.a12internal.*`) + StringTemplate codegen (`core.codegen.internal.generator.*`), registered wholesale so coverage is **shape-independent**, not capture-bound. Supersedes the demo/fixture capture that shipped the v0.1.0/v0.1.1 patterned-field + parser-error bugs (§3.5) |
| Runtime-eval verbs (`model eval`/`rule test`/`model compute`) | ✅ **hidden** from the native command tree and every derived surface (§4) — can't go native (Groovy); JVM keeps them. Guarded by `NativeImageVerbHidingTest` |
| **Shippable?** | **Authoring/check/structure/read surface works**, native gate GREEN. **But the v0.1.0/v0.1.1 binaries shipped real native-only defects** (§3.5) the curated capture missed — the honest status is "shippable *behind the rule-based registration of §3.4 + a broad no-crash gate*"; a happy-path demo gate is **not** sufficient evidence (§3.6). The B2-vs-B1 call: native-only for v1 (§7), revisit per §3.6. |

**GraalVM 25 vs 21 — 25 is decisively better and is the toolchain going forward:**

| | GraalVM 21.0.11 | GraalVM **25.0.3** |
|---|---|---|
| Groovy `invokedynamic` | ❌ analysis fails (`MutableCallSite`) | ✅ compiles |
| `-Os` size optimization | ❌ "Invalid value 's'" | ✅ → 19 MB |
| Reflection metadata format | `reflect-config.json` (multi-file) | unified `reachability-metadata.json` |
| Demo gate | 0/6 | **5/6** |
| Build time / size | ~2 min / 52–61 MB | **~45–60 s / 19–31 MB** |
| `typeReached` warning | yes | cleared |

## 2. Build (committed, opt-in)

```sh
export GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home   # see the GOTCHA below
export JAVA_HOME="$GRAALVM_HOME"
export GRADLE_USER_HOME="$(cd .. && pwd)/.gradle-isolated"
./gradlew :cli:nativeCompile        # → cli/build/native/nativeCompile/dmtool (~31 MB)
```

The `:cli` build file carries: the plugin, `graalvmNative { toolchainDetection = false; binaries.main { imageName='dmtool'; buildArgs += [--no-fallback, -Os, IncludeResources…] } }`, and a **native-only** Groovy exclusion (`configurations.named('nativeImageClasspath') { exclude group: 'org.codehaus.groovy' }`). The reflection metadata is bundled at `cli/src/main/resources/META-INF/native-image/rulekit-agent/reachability-metadata.json` (auto-detected).

> **GOTCHA — `GRAALVM_HOME` wins over `JAVA_HOME`.** The plugin (with `toolchainDetection=false`) resolves the GraalVM via **`GRAALVM_HOME`** first. If it points at an old GraalVM (e.g. a stale `…/graalvm-jdk-21…`), the build uses *that* regardless of `JAVA_HOME` — failing with *"native-image wasn't found / JDK isn't a GraalVM distribution."* Export `GRAALVM_HOME` to the GraalVM 25 home (and `./gradlew --stop` to drop a stale daemon).

## 3. The three requirements beyond the defaults

### 3.1 Exclude Groovy — native-classpath-only
Groovy (`org.codehaus.groovy:groovy:3.0.25`, a kernel transitive) compiles on GraalVM 25, but **picocli's `DefaultFactory.loadClosureClass` initializes `groovy.lang.Closure` at build time** → `GroovySystem.<clinit>` reads Groovy's DGM metadata stream, which is **closed at runtime** (`java.io.IOException: Stream closed`). Excluding Groovy makes picocli skip Closure loading and startup works. **The exclusion MUST be native-only** (`nativeImageClasspath`, not `configurations.all`) — a global exclude breaks the JVM runtime-eval verbs, the eval, and the CI showboat gate. Verified: `:cli:test` stays green with the native-only form. (On GraalVM 21 the failure was different — `invokedynamic`/`MutableCallSite` broke *analysis* — but the conclusion is the same: exclude Groovy from native.)

### 3.2 Bundle resources
Closed-world drops anything read via `getResourceAsStream`, so `-H:IncludeResources=` for `catalog/.*\.json` (operator catalog), `internal/templates/.*` (StringTemplate, used by `checkConsistency`), `schema/.*\.json`, `verb-examples\.json`, `.*version\.properties`. Verified: the binary reads `catalog/operators.json` once these are set (a missing one fails as `IllegalStateException: … resource not found`).

### 3.3 Register reflection — the tracing-agent capture (partial; see §3.4)
Neither picocli-codegen nor GraalVM 25's auto-handling covers the Jackson-deserialized POJOs (`OperatorEntry` &c.) or the kernel's reflective hotspots — without config the binary throws `Cannot construct instance of OperatorEntry … configure reflection`. A **tracing-agent** capture (§5) produces `reachability-metadata.json` (~377 KB) that registers what a traced run reaches. A *demo-only* capture is **not enough** (coverage is capture-bound — a `model validate` on a model whose operators the demos didn't exercise threw `MissingReflectionRegistrationError`). The capture ([`../scripts/capture-native-config.sh`](../scripts/capture-native-config.sh)) runs all fixtures × read/validate verbs + every operator + the demos, with two lessons baked in: validate *valid* models for reflection, **and** run the demos for the **error paths**.

> **⚠ The capture is capture-bound — and that shipped real bugs.** This section once claimed the capture "closes the gap (0 errors)." It does **not**: it only covers the *shapes the capture run exercised*. The capture had **no patterned field and no parser-error path**, so v0.1.0/v0.1.1 shipped two native-only defects (§3.5). The durable fix for the reflective surfaces is **rule-based, not capture-based** — §3.4. The agent capture is retained for the surface *outside* those packages (operator entries, parser internals); the two bounded packages are now registered wholesale.

### 3.4 The durable fix — rule-based registration of the two bounded reflective surfaces
The kernel reaches two surfaces **by name at runtime**, which static analysis cannot see and the tracing agent records only *per shape exercised* (capture-bound). Both live in **bounded, enumerable packages**, so we register every class in them **by rule** — coverage becomes **shape-independent**:

| Surface | How it's reached | Package (slash form) | #classes | Mechanism |
|---|---|---|---|---|
| Jackson model/field-type POJOs | `FieldtypeDeserializer` reflects **setters** during deserialize | `md/model/a12internal/` | 102 | wholesale `allDeclared{Methods,Constructors,Fields}` |
| StringTemplate codegen | `ObjectModelAdaptor` reflects **getters by name** during `checkConsistency` | `core/codegen/internal/generator/` | 297 | same |

Generated by **[`../scripts/gen-native-reflect-metadata.sh`](../scripts/gen-native-reflect-metadata.sh)** → committed at `cli/src/main/resources/META-INF/native-image/rulekit-manual/reachability-metadata.json` (399 entries; **separate** from the captured `rulekit-agent/` file so a re-capture never clobbers it; GraalVM merges both). **Cost: +0.8 MB** (32.7 → 33.5 MB) — negligible, since the classes are already in the image; only metadata is added. The **resource** sibling (i18n bundles) is fixed the same rule-based way: a glob `-H:IncludeResources=.*error_messages.*\.properties` in `cli/build.gradle` includes **all 12 bundles × all locales**, not the capture's en/en_US subset.

Why this beats broadening the capture: a capture covers "the shapes the run touched"; the package rule covers "**every** class in the surface, for every shape" — so a *new* field constraint, enum, date-range, computation, … cannot reintroduce the gap. The residual (a reflective class *outside* these two packages) is what the §3.6 gate must catch.

### 3.5 The bugs this closes (v0.1.0 / v0.1.1 native binaries)
Reported against v0.1.0; reproduced on v0.1.1 (same metadata). All three are **native-only** — the JVM build is clean — and are instances of the same root cause (by-name reflection / lazy resource load the capture never exercised):

1. **Patterned STRING field unconstructable (reflection).** `field add` with a `string.pattern`+`patternMessage` threw `MissingReflectionRegistrationError` on `StringType.setErrorMessage(ILocalizedTextMap)` (carried by `StringType` + `EnumerationType`; the deserializer reflects setters on all 14 field types). → §3.4 Jackson-package rule.
2. **Patterned field unvalidatable (StringTemplate, *un-masked by fixing #1*).** With #1 fixed, `model validate` of a patterned field threw `STNoSuchPropertyException: CodeGenMetaFields.metaFelderCode` from `ObjectModelAdaptor` during codegen → reported "not kernel-valid" though the JVM accepts it. The classic peel-one-find-the-next. → §3.4 codegen-package rule.
3. **Parser-error formatting broken in *every* locale (resource bundle).** Any invalid condition (`rule check`/`model validate`) failed with `Can't find bundle … internal/vk_parser_common_error_messages/error_messages, locale <X>` — reproduced in **en_US** too, not just de_DE. The bundle loads only when *formatting a parse error*; the capture ran only valid conditions, so the whole bundle (every locale) was absent. The report's de_DE framing was a symptom: an English keyword (`FieldNotFilled`) is invalid in a `de_DE` condition language (the keyword is `FeldNichtAngegeben`), which triggered error-formatting; *valid* German conditions work on native. → resource glob.

### 3.6 The whack-a-mole question — failure taxonomy, our measured exposure & the strategy
The bugs raise a fair concern: *is fixing them just whack-a-mole?* Native-image's failure modes are a **known, finite** set (anything resolved by name/dynamically at runtime that static analysis can't see). The honest defense is to enumerate that set, measure our exposure against the **real kernel** (2340 classes, probed by constant-pool reference), and pick a mechanism per class that is **rule-based** (covers all shapes) rather than **capture-based** (covers run shapes). Exposure measured 2026-06-15 on kernel 30.8.1:

| Failure class (GraalVM) | What trips it | Our exposure (measured) | Mechanism / status |
|---|---|---|---|
| **Reflection — Jackson POJOs** | deserialize reflects ctors/setters | `md.model.a12internal.*` = **102** | §3.4 **rule** (whole package) ✅ |
| **Reflection — StringTemplate codegen** | `ObjectModelAdaptor` getters **by name** | `core.codegen.internal.generator.*` = **297** (262 backing-beans), `ObjectModelAdaptor` present | §3.4 **rule** (whole package) ✅ |
| **Resources** (`getResourceAsStream`) | closed-world drops them | catalog/schema/templates/version | `-H:IncludeResources` globs ✅ |
| **ResourceBundle (i18n, per-locale)** | locale-parameterized lazy load | 13 loader classes, **12 bundles × {de,en,fr,nl}** | glob over all bundles/locales ✅ (was the de_DE/en_US bug) |
| **Runtime codegen (Groovy)** | `GroovyClassLoader.parseClass`, invokedynamic | `groovy/lang`=4, `GroovyClassLoader`=1 — the eval verbs only | **excluded + verbs hidden** (§4) ✅ hard ceiling |
| **`Class.forName` / `loadClass`** | dynamic class load | 6 / 1 classfiles — all in the Groovy codegen path | covered by the eval exclusion; none on the native surface ✅ |
| **Serialization** | `readObject`/`writeObject` | `Serializable`=65 but `readObject`=1, `writeObject`=0 | not on our deserialize/validate path — **no serialization config needed** (re-check on bump) |
| **Dynamic proxies** | `Proxy.newProxyInstance` | **0** | none ✅ |
| **JNI / `Unsafe`** | native calls / unsafe | `Unsafe`=0; no JNI | none ✅ |
| **`ServiceLoader`** | `META-INF/services` | **1** provider (`ModelConsistencyRulesProvider`) | GraalVM auto-includes `META-INF/services`; reached via consistency checks (works in demos) — watch on bump |
| **MethodHandles / lambdas** | `invokedynamic` bootstrap | 786 classfiles | GraalVM handles standard lambda/method-ref indy; the *problematic* indy is Groovy's, already excluded ✅ |
| **Default locale / timezone / charset** | build-time default ≠ runtime | `Locale`=295, `TimeZone/ZoneId`=48 | a real native-image footgun; not yet a observed bug, but **a gate candidate** (validate a model with a non-UTC `timeZone` + non-en locale) |

**Strategy = rule-based coverage + a broad no-crash gate + an escape hatch.** Mapping each candidate strategy to whether it actually kills the class (not the instance):

| Option | Kills the class? | Verdict |
|---|---|---|
| Broaden the capture script case-by-case | ❌ instance-only | whack-a-mole — rejected as the primary fix |
| **Rule-based whole-package registration (§3.4)** | ✅ for the two reflection surfaces (all shapes) | **adopted** — the structural fix |
| **Resource glob over all bundles (§3.4)** | ✅ for the i18n class (all bundles/locales) | **adopted** |
| Capture the agent over the **JUnit test suite** (not the curated script) | ✅-ish — coverage tracks tests, grows with TDD | **adopted as the gate's input** (the suite already validates patterned fields, `FieldConfigTest`); records out-of-package surfaces the packages miss |
| Broad **no-crash parity gate** (run a derived battery — catalog ops, fixtures, field-type×constraint, valid+invalid, each locale — through the native binary; assert no `MissingReflection`/`MissingResource`/`STNoSuchProperty`, well-formed, expected exit) | ✅ catches *out-of-package* residuals at build time | **TODO** — the missing piece; byte-identity vs JVM is **not** needed (the failures are loud), so no normalization layer |
| `-H:+ThrowMissingRegistrationErrors` / strict mode | turns silent gaps loud | gate amplifier (pair with the battery) |
| Exclude `validate`/`check` from native too | ✅ removes the class from native | **rejected** — guts the core value (kernel-confirmed authoring *is* the binary's point) |
| **Ship JVM (jpackage/jlink B1) instead of native** | ✅✅ removes the **entire** native-image failure surface | **the escape hatch** (§7/§10): if the gate ever shows native can't be made robust per shape, B1 (all verbs, no reflection grind; ~65–90 MB, ~600 ms start) is the honest ship |

**The irreducible residual (stated honestly):** rule-based registration + the gate shrink the risk to "a reflective path in a class *outside* the two packages that *no test/battery input* exercises." That cannot be driven to zero while AOT-compiling a reflection-heavy embedded kernel — the only zero is B1 (a real JVM). The chosen posture: **rule-based coverage of the known surfaces (done) makes the common shapes safe; the no-crash gate (TODO) catches the rest at build time; B1 is the escape hatch if the gate proves native unwinnable.** Failures are at least **loud** (thrown/coded), never silently-wrong.

## 4. The hard ceiling — runtime-eval verbs can't go native

`model eval` / `rule test` / `model compute` execute the kernel's **runtime validation** against a document instance, which **generates and loads Groovy classes at runtime** (`GroovyClassLoader.parseClass`; the agent captured `Com_mgmtp_a12_kernel_generated_*` classes). This is [`CLI-SPEC.md`](CLI-SPEC.md) §11's hard ceiling, breached by verbs that grew past it. Groovy is genuinely required by these 3 verbs (JVM and native), so: the §3.1 exclusion stays native-only (JVM keeps them), and **the native binary cannot run them** (no Groovy → no runtime codegen). `rule check`/`model validate` are *structural* — they drive `checkConsistency`, which renders **StringTemplate** codegen (no Groovy compile/execute), so they **are** native-capable and stay in the native tree.

> **⚠ "StringTemplate, native-OK" is true only with §3.4's registration.** This line once read "native-OK — is unaffected," implying StringTemplate validate is free. It is not: StringTemplate's `ObjectModelAdaptor` resolves `<bean.prop>` by **reflective getter lookup at render time**, invisible to static analysis. Validating a *patterned* field renders a codegen path that reads `CodeGenMetaFields.getMetaFelderCode()` — unregistered → `STNoSuchPropertyException` → the model is reported "not kernel-valid" on native though the JVM accepts it (§3.5, bug 1b). Native validate is sound **only because §3.4 registers the whole codegen package**.

**Implemented — the registration gate (`Cli.JVM_ONLY_VERBS` + `hideJvmOnlyVerbs`).** In the native build these 3 verbs are **removed from the picocli command tree** (`Cli` detects native via `org.graalvm.nativeimage.imagecode`). Because `manifest`/`schema`/`--help` all **derive from the live tree**, that single removal makes them **vanish from every self-describing surface** — a cold native agent can't even *discover* them (no help entry, not in `manifest`, `schema model eval` → unknown, uninvocable). The JVM build keeps them. **Guarded by `NativeImageVerbHidingTest`** (CI, JUnit): it runs the tree in forced-native profile and asserts the 3 are absent from manifest/`--help`/`schema` and uninvocable, while present in the JVM profile — so native isn't built in CI but the contract still can't regress.

## 5. Generating the reflection metadata (tracing agent)

One committed script does it: **[`../scripts/capture-native-config.sh`](../scripts/capture-native-config.sh)** (`GRAALVM_HOME=…/graalvm-25 ./scripts/capture-native-config.sh`). It drives the agent on a Groovy-present `installDist` JVM across all fixtures × read/validate verbs + every operator + the demos, merging into one `reachability-metadata.json`, and copies it into place. Then `./gradlew :cli:nativeCompile` bundles it. (The agent only registers what it reaches — hence the script's breadth; the two non-obvious coverage lessons are in §3.3.)

### 5.1 Where it's stored & how often to regenerate — it is NOT a per-build cost

There are now **two committed metadata sources** (GraalVM merges both), plus a build-arg glob — and a kernel bump must refresh **all three**:

```
cli/src/main/resources/META-INF/native-image/rulekit-agent/reachability-metadata.json    (~377 KB, AGENT capture — §3.3/§5)
cli/src/main/resources/META-INF/native-image/rulekit-manual/reachability-metadata.json   (399 entries, RULE-BASED — §3.4)
cli/build.gradle  →  -H:IncludeResources=.*error_messages.*\.properties                  (i18n bundle glob — §3.4)
```

`:cli:nativeCompile` **reads** these; it does not regenerate them. Both metadata files derive from the **kernel's own classes**, so a kernel bump can change the class set in the registered packages, the reflective hotspots, *and* the i18n bundle base names — **all three drift on a bump and must be redone in lockstep.** Triggers and the exact redo step:

| Trigger | Redo |
|---|---|
| **Kernel bump** (`a12-kernel`) | **(1)** `./scripts/gen-native-reflect-metadata.sh` (re-derives the 399 rule-based entries from the new jars); **(2)** `GRAALVM_HOME=…/graalvm-25 ./scripts/capture-native-config.sh` (re-runs the agent capture); **(3)** confirm the i18n glob still matches the bundle layout (`unzip -l` the kernel jars for `error_messages*.properties` — §3.4); **(4)** `:cli:nativeCompile` + `./scripts/native-gate.sh`. See [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md). |
| **CLI / catalog surface change** | a new verb, a new Jackson POJO, new operators in `operators.json` → re-run the agent capture (2) |
| **Jackson / reflection-using dependency bump** | databind internals change → re-run the agent capture (2) |
| **New kernel reflective surface** (the gate finds a `MissingReflection`/`STNoSuchProperty` outside the two §3.4 packages) | add the offending package to `PACKAGES` in `gen-native-reflect-metadata.sh` and re-run (1) — extend the *rule*, don't hand-add one class |

The **verification gate is the trip-wire**: if any source lags, a fresh reflective path throws `MissingReflectionRegistrationError`/`MissingResourceException`/`STNoSuchPropertyException`, and the native gate fails. **But note (§3.5): the gate's inputs decide what it catches** — the demo-only gate that shipped passed while patterned-field/parser-error paths were broken. `native-gate.sh` now runs a **reflection/resource smoke** (patterned-field add+validate, invalid-condition per locale) that locks exactly those paths — the **seed** of the broader §3.6 no-crash battery (still TODO). Add a shape to the smoke whenever a new native-only gap is found.

## 6. Sizing & shrink levers

`-Os` (GraalVM 25) is the big lever: **19 MB** bare, **31 MB** with the reflection metadata (the metadata costs ~12 MB). Far below the GraalVM-21 numbers (52–61 MB) and well below the jpackage app-image (§10: ~65–90 MB/OS). Further levers if needed: `--strip-debug`, drop more unused kernel jars, tighter (hand-authored vs full-agent) metadata. `upx` (~50%) is possible but **breaks macOS notarization + slows startup** — not for distribution.

## 7. Decision — native-only for v1 (B2)

**Decided: v1 ships native-only.** The shipped CLI is the GraalVM 25 native binary — the right fit for the agent-first, latency-sensitive use case (~37 ms start, ~31 MB, downloaded by the plugin's `SessionStart` hook). GraalVM 25 closed both earlier blockers:

1. ~~Reflection coverage~~ — **done** (§3.3, the comprehensive capture).
2. ~~The 3 runtime-eval verbs~~ — **done** (§4, the registration gate hides them from every surface; guarded by `NativeImageVerbHidingTest`).

**Scope consequence of native-only:** v1's shipped CLI does **not** offer runtime evaluation (`model eval`/`rule test`/`model compute`) — those need Groovy and remain the **JVM dev/eval vehicle only** (`installDist`), not a shipped artifact. No jpackage/B1 full-verb distributable in v1. (B1 — a trimmed JVM + launcher, all verbs, no reflection grind, but ~65–90 MB/OS and ~600 ms start — stays the documented fallback if a real consumer ever needs the runtime-eval verbs outside the dev loop.) What's left for the release is **release-engineering only** — signing/distribution (RELEASE-SPEC).

**jpackage (B1)** still "just works" for *all* verbs with no reflection grind, at the cost of size (~65–90 MB/OS) and startup (~600 ms). That decision is **resolved — native-only for v1** (§7): GraalVM 25's results made native the stronger candidate for the primary use case, and it shipped. B1 remains the fallback for full-verb coverage.

## 8. Verification gate — two gates, two targets

The demos are authored for the **full JVM CLI**, so against the native *subset* some are legitimately not-applicable. Rather than eyeball a known red, the gate is split by target:

- **JVM gate** — CI, the `installDist` launcher, **all 6 demos green** (`.github/workflows/ci.yml`). Unchanged.
- **Native gate** — **[`../scripts/native-gate.sh`](../scripts/native-gate.sh)** — verifies the native binary byte-for-byte across the **native-applicable** demos, and **skips the non-applicable ones with their reason** (never a spurious FAIL). Exits non-zero only on a real regression in a native-applicable demo.

```
$ ./scripts/native-gate.sh
PASS  cli-apply.md
PASS  cli-edit-loop.md
SKIP  cli-runtime.md   — JVM-only verbs — model eval/rule test/model compute need Groovy (§4)
PASS  cli-structure-edit.md
SKIP  cli-tour.md      — manifest block lists the full verb list — a subset on native; asserted by NativeImageVerbHidingTest
PASS  cli-version.md
native gate: GREEN
```

The skip set (`NATIVE_SKIP`) lives in the script with the *why* next to each entry. Two distinct causes:

- **JVM-only verbs** — `cli-runtime.md` is *entirely* `model eval`/`rule test`/`model compute` (all 9 invocations), so nothing to salvage. **Convention — isolate JVM-only verbs in their own demo file:** showboat verify is per-file, so a *single* runtime-eval block would turn its whole demo unverifiable on native and you'd lose native coverage of its other blocks. Never sprinkle one into a native-applicable demo.
- **Subset divergence** — `cli-tour.md`'s `manifest` block enumerates the full verb list, which is legitimately shorter on native (the 3 hidden verbs). A per-block "native-aware capture" isn't possible (showboat verifies whole files), so the demo is skipped on native and **the native-aware manifest assertion lives in `NativeImageVerbHidingTest`** (it checks native's manifest omits exactly those 3 and keeps the rest). cli-tour's other (read-verb) blocks aren't lost coverage: native JSON-output fidelity is already proven byte-for-byte by the 4 passing demos.

This split is what keeps "native ≡ JVM" honest *per verb* (identical behavior for every verb native exposes) while letting native legitimately expose fewer.
