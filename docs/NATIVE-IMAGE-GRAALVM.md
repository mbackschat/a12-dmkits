# GraalVM native image — recipe, findings & status

The single durable home for building the `dmtool` CLI as a **GraalVM native image**. Other docs point here and don't duplicate it:

- [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §3a — the release artifact. · [`CLI-SPEC.md`](CLI-SPEC.md) §11 — packaging *design/decision*. · `CLAUDE.md` "Build" — routes here.

> **Why its own doc:** native-image for a kernel-embedding CLI has non-obvious, interacting constraints (closed-world reflection, Groovy's `invokedynamic`, resource bundling, GraalVM-version quirks). It is **release-only** — never in the dev inner loop (that stays JVM/`installDist`), never in CI.

## 1. Status (2026-06-24, GraalVM **25.0.3**)

| Aspect | State |
|---|---|
| Toolchain | **GraalVM for JDK 25** (`graalvm-25.jdk`), plugin `org.graalvm.buildtools.native:1.1.1`, Gradle 9 |
| Build | ✅ ~45–60 s |
| Size | ✅ **19 MB** bare, **~33 MB** with reflection metadata — both via `-Os` (GraalVM 25 only) |
| Groovy | ✅ compiles on 25 (no analysis failure), but **excluded from the native classpath** (startup-init issue, §3.1) → JVM keeps it |
| **Showboat gate** | **6 / 6 demos PASS** — incl. `cli-runtime.md` (the runtime verbs default to the native-safe interpreter, §4) + the no-crash battery (172 checks, 0 signatures) |
| Reflection coverage | ✅ **rule-based over the two bounded reflective-surface packages** (§3.4) — Jackson POJOs (`md.model.a12internal.*`) + StringTemplate codegen (`core.codegen.internal.generator.*`), registered wholesale so coverage is **shape-independent**, not capture-bound. Supersedes the demo/fixture capture that shipped the v0.1.0/v0.1.1 patterned-field + parser-error bugs (§3.5); the residual out-of-package surface is now captured **test-suite-driven** (`:cli:test` under the agent, §3.3/§5) |
| Runtime verbs (`model eval`/`rule eval`/`model compute`) | ✅ **run natively** — on the native-safe `dm.interpreter` (kernel-free), the sole runtime eval engine, so they stay in the native command tree and on every derived surface (§4). The former JVM-only `--kernel` eval option was **retired** (Set V) — no runtime verb offers it on any target. Guarded by `NativeImageRuntimeVerbsTest`. (`model seed` is interpreter-backed too.) |
| **Shippable?** | **Yes — the whole verb surface, incl. the interpreter-backed runtime verbs** — behind the §3.4 rule-based registration **and** the §3.6 no-crash battery (`native-battery.sh`: 172 checks, 0 signatures — incl. the current-date operators that crashed in v0.1.0). The v0.1.0/v0.1.1 binaries shipped native-only defects (§3.5) a demo-only gate missed; that gap is now closed. B1 is measured (§10) — **native-only for v1** re-affirmed on data. Residual: §3.6. |

**GraalVM 25 vs 21 — 25 is decisively better and is the toolchain going forward:**

| | GraalVM 21.0.11 | GraalVM **25.0.3** |
|---|---|---|
| Groovy `invokedynamic` | ❌ analysis fails (`MutableCallSite`) | ✅ compiles |
| `-Os` size optimization | ❌ "Invalid value 's'" | ✅ → 19 MB |
| Reflection metadata format | `reflect-config.json` (multi-file) | unified `reachability-metadata.json` |
| Demo gate | 0/6 | **6/6** |
| Build time / size | ~2 min / 52–61 MB | **~45–60 s / 19–31 MB** |
| `typeReached` warning | yes | cleared |

## 2. Build (committed, opt-in)

```sh
export GRADLE_USER_HOME="$(cd .. && pwd)/.gradle-isolated"
./gradlew :cli:nativeCompile        # → cli/build/native/nativeCompile/dmtool (~33 MB)
```

**GraalVM 25 is auto-discovered — no `GRAALVM_HOME` needed locally** (since 2026-06-17). The native binary declares an explicit toolchain launcher: when `GRAALVM_HOME` is unset, `graalvmNative.binaries.main` resolves `javaToolchains.launcherFor { languageVersion = 25; vendor = JvmVendorSpec.ORACLE }`, which Gradle's installation detection maps uniquely to the installed **Oracle GraalVM 25** (it reports as "Oracle JDK 25"). The JDK-21 *compile* toolchain (wrong version) and any **Homebrew OpenJDK 25** (vendor "Homebrew", and it has no `native-image`) are both excluded. So a plain `:cli:nativeCompile` builds with GraalVM 25 even when the daemon runs on the JDK-21 `JAVA_HOME`.

The `:cli` build file carries: the plugin, `graalvmNative { toolchainDetection = false; binaries.main { javaLauncher = …GraalVM 25…; imageName='dmtool'; buildArgs += [--no-fallback, -Os, IncludeResources…] } }`, and a **native-only** Groovy exclusion (`configurations.named('nativeImageClasspath') { exclude group: 'org.codehaus.groovy' }`). The reflection metadata is bundled at `cli/src/main/resources/META-INF/native-image/rulekit-agent/reachability-metadata.json` (auto-detected).

> **Override / CI.** Setting **`GRAALVM_HOME`** still works and takes precedence (the auto-launcher only applies when it's unset) — CI keeps its explicit `GRAALVM_HOME` flow (RELEASE-SPEC). The historical gotcha was that `GRAALVM_HOME` wins over `JAVA_HOME`: if it points at a *stale* GraalVM (e.g. `…/graalvm-jdk-21…`) the build uses that regardless of `JAVA_HOME` and fails with *"native-image wasn't found / not a GraalVM distribution."* Fix: unset it (let auto-discovery pick GraalVM 25) or point it at the GraalVM 25 home, then `./gradlew --stop` to drop a stale daemon.

## 3. The three requirements beyond the defaults

### 3.1 Exclude Groovy — native-classpath-only
Groovy (`org.codehaus.groovy:groovy:3.0.25`, a kernel transitive) compiles on GraalVM 25, but **picocli's `DefaultFactory.loadClosureClass` initializes `groovy.lang.Closure` at build time** → `GroovySystem.<clinit>` reads Groovy's DGM metadata stream, which is **closed at runtime** (`java.io.IOException: Stream closed`). Excluding Groovy makes picocli skip Closure loading and startup works. **The exclusion MUST be native-only** (`nativeImageClasspath`, not `configurations.all`) — a global exclude breaks the JVM runtime-eval verbs, the eval, and the CI showboat gate. Verified: `:cli:test` stays green with the native-only form. (On GraalVM 21 the failure was different — `invokedynamic`/`MutableCallSite` broke *analysis* — but the conclusion is the same: exclude Groovy from native.)

### 3.2 Bundle resources
Closed-world drops anything read via `getResourceAsStream`, so `-H:IncludeResources=` for `catalog/.*\.json` (operator catalog), `internal/templates/.*` (StringTemplate, used by `checkConsistency`), `schema/.*\.json`, `verb-examples\.json`, `.*version\.properties`, and **`.*custom-js-worker\.mjs`** (the G31 Node-worker script — `CustomJsWorker` extracts it to a temp file to invoke `node`; a missing include fails its first `--custom-conditions-js`/`--custom-field-types-js` use as "bundled worker script not found"). Verified for the existing set: the binary reads `catalog/operators.json` once these are set (a missing one fails as `IllegalStateException: … resource not found`).

> **G31 native notes (re-verify when the JS escape is first built native).** (1) The worker `.mjs` resource above must be in the include glob. (2) `--custom-conditions-js`/`--custom-field-types-js` spawn the system **`node`** via `ProcessBuilder` — native-image-clean (a subprocess, no embedded JS VM, so the kernel-free/native thesis holds), but it is a **runtime dependency**: `node` absent → the construct degrades to `unsupported` (never a crash), exactly as on the JVM. No reflection metadata is involved (the bridge uses Jackson + process I/O, already covered).

> **`-H:IncludeLocales=en,de` is REQUIRED — and is a build-host-INDEPENDENCE fix, not size tuning (added v0.8.0).** GraalVM bundles only the **build host's default locale** (+ `en`) into the image's resolvable locales unless told otherwise. The kernel's condition formatter is **German-canonical** — to render a rule in *any* language (even English) it reads the **German** terminal map (`AbstractMultiLanguageHandler.getTerminalNameByGermanTerminalName`; [KERNEL-FINDINGS](KERNEL-FINDINGS.md)). So a binary built on a **`de_DE`** host (the maintainer's Mac) silently carries the German data and `rule format` works, while one built on an **`en`/`C`** host (the Linux CI runner) **drops German** → `rule format`'s English render fails *only on that binary* with *"The german token name 'Und' is unknown"* / `ExceptionInInitializerError`. This shipped as the v0.8.0 Linux-only defect (caught by the Linux native gate over `examples/cli-jsonschema.md`). Pinning `en,de` makes every build identical regardless of host. **Reproduce a host-stripped build locally** with a forced builder locale: add `-J-Duser.language=en -J-Duser.country=US` to `buildArgs` (this mimics the Linux host on a German Mac — `LANG`/`JAVA_TOOL_OPTIONS` do **not** work, macOS reads the JVM default locale from system prefs). Negligible size cost (~−2 MB observed, since it also bounds an otherwise host-dependent set).

### 3.3 Register reflection — the tracing-agent capture, test-suite driven (partial; see §3.4)
Neither picocli-codegen nor GraalVM 25's auto-handling covers the Jackson-deserialized POJOs (`OperatorEntry` &c.) or the kernel's reflective hotspots — without config the binary throws `Cannot construct instance of OperatorEntry … configure reflection`. A **tracing-agent** capture (§5) produces `reachability-metadata.json` (~520 KB) that registers what a traced run reaches. The capture **drives the JUnit `:cli:test` suite** under the agent ([`../scripts/capture-native-config.sh`](../scripts/capture-native-config.sh)), not a hand-curated list of verb invocations — so coverage **tracks the test suite and grows with TDD**: every test that exercises a new shape (a field kind, a constraint, an operator, an apply/refactor op, an error/diagnostic path) extends the metadata automatically. This **replaces** the earlier curated capture (all fixtures × verbs + every operator + the demos), whose coverage was bounded by the invocations it remembered to list — the root cause of the v0.1.0/v0.1.1 bugs (§3.5). The suite already exercises both shapes those bugs needed: patterned fields (`FieldAddSpecTest`/`FieldModifyCommandTest`) and parser-error formatting (`DiagnosticConformanceFuzzTest`/`CheckVerbTest`).

> **⚠ A capture only ever covers the shapes it runs — so we bind it to the test suite, not a curated list.** This section once claimed a curated capture "closes the gap (0 errors)." It did **not**: the curated run had **no patterned field and no parser-error path**, so v0.1.0/v0.1.1 shipped two native-only defects (§3.5). Two changes close that for good: **(1)** the durable fix for the two bounded reflective surfaces is **rule-based, not capture-based** (§3.4 — shape-independent); **(2)** the residual out-of-package capture now **drives the JUnit suite** (§5), so it cannot silently omit a shape that has a test — and the bug-shapes now do. The agent capture is retained for the surface *outside* the §3.4 packages (operator entries, parser internals); the two bounded packages stay registered wholesale. (Measured: the test-suite capture is a **superset** of the old curated one for the native surface — its only omissions are `kernel.core.rt.*` runtime-eval classes, which native excludes anyway.)

### 3.4 The durable fix — rule-based registration of the two bounded reflective surfaces
The kernel reaches two surfaces **by name at runtime**, which static analysis cannot see and the tracing agent records only *per shape exercised* (capture-bound). Both live in **bounded, enumerable packages**, so we register every class in them **by rule** — coverage becomes **shape-independent**:

| Surface | How it's reached | Package (slash form) | #classes | Mechanism |
|---|---|---|---|---|
| Jackson model/field-type POJOs | `FieldtypeDeserializer` reflects **setters** during deserialize | `md/model/a12internal/` | 102 | wholesale `allDeclared{Methods,Constructors,Fields}` |
| StringTemplate codegen | `ObjectModelAdaptor` reflects **getters by name** during `checkConsistency` | `core/codegen/internal/generator/` | 297 | same |

Generated by **[`../scripts/gen-native-reflect-metadata.sh`](../scripts/gen-native-reflect-metadata.sh)** → committed at `cli/src/main/resources/META-INF/native-image/rulekit-manual/reachability-metadata.json` (399 entries; **separate** from the captured `rulekit-agent/` file so a re-capture never clobbers it; GraalVM merges both). **Cost: +0.8 MB** (32.7 → 33.5 MB) — negligible, since the classes are already in the image; only metadata is added. The **resource** sibling (i18n bundles) is fixed the same rule-based way: a glob `-H:IncludeResources=.*error_messages.*\.properties` in `cli/build.gradle` includes **all 12 bundles × all locales**, not the capture's en/en_US subset.

Why this beats broadening the capture: a capture covers "the shapes the run touched"; the package rule covers "**every** class in the surface, for every shape" — so a *new* field constraint, enum, date-range, computation, … cannot reintroduce the gap. The residual (a reflective class *outside* these two packages) is what the §3.6 gate must catch.

### 3.5 The bugs this closes (v0.1.0 / v0.1.1 / v0.6.0 native binaries)
Reported against v0.1.0; reproduced on v0.1.1 (same metadata). All are **native-only** — the JVM build is clean — and are instances of the same root cause (by-name reflection / lazy resource load the capture never exercised):

1. **Patterned STRING field unconstructable (reflection).** `field add` with a `string.pattern`+`patternMessage` threw `MissingReflectionRegistrationError` on `StringType.setErrorMessage(ILocalizedTextMap)` (carried by `StringType` + `EnumerationType`; the deserializer reflects setters on all 14 field types). → §3.4 Jackson-package rule.
2. **Patterned field unvalidatable (StringTemplate, *un-masked by fixing #1*).** With #1 fixed, `model check` of a patterned field threw `STNoSuchPropertyException: CodeGenMetaFields.metaFelderCode` from `ObjectModelAdaptor` during codegen → reported "not kernel-valid" though the JVM accepts it. The classic peel-one-find-the-next. → §3.4 codegen-package rule.
3. **Parser-error formatting broken in *every* locale (resource bundle).** Any invalid condition (`rule check`/`model check`) failed with `Can't find bundle … internal/vk_parser_common_error_messages/error_messages, locale <X>` — reproduced in **en_US** too, not just de_DE. The bundle loads only when *formatting a parse error*; the capture ran only valid conditions, so the whole bundle (every locale) was absent. The report's de_DE framing was a symptom: an English keyword (`FieldNotFilled`) is invalid in a `de_DE` condition language (the keyword is `FeldNichtAngegeben`), which triggered error-formatting; *valid* German conditions work on native. → resource glob.
4. **Header annotation crashes the SERIALIZE path (reflection — v0.6.0).** A model carrying a `roles` **header** annotation — the standard A12 access-control gate, which `workspace roles` actively recommends adding to every model — threw `MissingReflectionRegistrationError` on `AnnotationImpl.getName()` whenever the model was **serialized during kernel expansion**: `group extract` on any annotated model (trigger A), and `model check`/`describe`/`export`/`rule read` over a parent that **includes** an annotated sub-model (trigger B). The metadata had the annotation **setters** (deserialize) but not the **getters** (serialize) — the getters fire only during expand/copy, and no test ever *rendered* an annotated model, so the test-suite capture never recorded them. The symmetry is the tell: every type with registered setters needs its getters too. → closed at the root by a test-suite-driven re-capture (`AnnotationRenderingTest` loads/describes/exports an annotated include, so the agent records `getName`/`getValue` + any sibling header getters), with `native-battery.sh`'s annotated-include block as the runtime trip-wire. **Independently, this had escaped as a raw stack trace** (the error is an `Error`, which slipped the Exception-only `Cli` seam); the boundary now renders any such residual as a structured `RK_INTERNAL_ERROR` (CLI-SPEC §3b) rather than a bare trace.
5. **Message interpolation token crashes the validate path (resource bundle — probe 11, 2026-06-22).** A rule whose error **message** carries an interpolation token (`$Field.value$` / `$Field$` / `$#Group$` — the kernel's documented way to embed a runtime value / label / row index, advertised in `schema rule add`) threw `MissingResourceException: Can't find bundle … errortext.grammar.languages.LexerTerminals` → `ExceptionInInitializerError` → exit 70, on `rule add`/`rule modify` and even `--dry-run`. The token invokes the kernel's error-text **parameter** parser (`RuleErrortextChecker` → `ErrortextParameterMultiLanguageHandler.<clinit>`), which lazily loads that `LexerTerminals` bundle — a *different* bundle from the condition parser-error one the battery already covered, and a path **no `:cli` test exercised** (the `:`/`:adapter` `MessageInterpolationTest`s exist, but the capture runs only the `:cli` suite), so the test-suite capture never recorded it. Same root as #1/#4: an un-exercised shape → a capture gap. → closed at the root by a test-suite-driven re-capture (`RuleVerbTest.addPersistsARuleWhoseMessageInterpolatesValueAndRowIndexTokens` validates a token-bearing message through `rule add`, so the agent records the bundle), with `native-battery.sh`'s `error-text PARAMETER tokens` block as the runtime trip-wire. The lesson is the recurring one: **any kernel surface the CLI advertises but no `:cli` test drives is a latent native gap** — the fix is always "add the missing shape to the suite that drives the capture," never a hand-registered one-off.

### 3.6 The whack-a-mole question — failure taxonomy, our measured exposure & the strategy
The bugs raise a fair concern: *is fixing them just whack-a-mole?* Native-image's failure modes are a **known, finite** set (anything resolved by name/dynamically at runtime that static analysis can't see). The honest defense is to enumerate that set, measure our exposure against the **real kernel** (2340 classes, probed by constant-pool reference), and pick a mechanism per class that is **rule-based** (covers all shapes) rather than **capture-based** (covers run shapes). Exposure measured 2026-06-15 on kernel 30.8.1:

| Failure class (GraalVM) | What trips it | Our exposure (measured) | Mechanism / status |
|---|---|---|---|
| **Reflection — Jackson POJOs** | deserialize reflects ctors/setters | `md.model.a12internal.*` = **102** | §3.4 **rule** (whole package) ✅ |
| **Reflection — StringTemplate codegen** | `ObjectModelAdaptor` getters **by name** | `core.codegen.internal.generator.*` = **297** (262 backing-beans), `ObjectModelAdaptor` present | §3.4 **rule** (whole package) ✅ |
| **Resources** (`getResourceAsStream`) | closed-world drops them | catalog/schema/templates/version | `-H:IncludeResources` globs ✅ |
| **ResourceBundle (i18n, per-locale)** | locale-parameterized lazy load | 13 loader classes, **12 bundles × {de,en,fr,nl}** | glob over all bundles/locales ✅ (was the de_DE/en_US bug) |
| **Runtime codegen (Groovy)** | `GroovyClassLoader.parseClass`, invokedynamic | `groovy/lang`=4, `GroovyClassLoader`=1 — the retired `--kernel` eval path only | **excluded; the verbs run on the interpreter (native), and the `--kernel` eval path was retired** (§4) ✅ |
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
| Capture the agent over the **JUnit test suite** (not the curated script) | ✅-ish — coverage tracks tests, grows with TDD | **adopted — this IS the capture** (`capture-native-config.sh` now drives `:cli:test`, §5). The suite already exercises patterned fields + parser errors, and records the out-of-package surfaces the §3.4 packages miss. Measured a **superset** of the old curated capture for the native surface (its only omissions are excluded Groovy runtime-eval classes) |
| Broad **no-crash battery** ([`../scripts/native-battery.sh`](../scripts/native-battery.sh)): drive a derived matrix — every fixture × read/validate verbs + the FieldKind × constraint matrix + invalid conditions per locale + the **current-date operators** (`Today`/`AddYears`/`YearFromDate`/`DifferenceInYears` — the literal v0.1.0 crash) + the **error-text message tokens** (`$Field.value$`/`$#Group$` — the probe-11 `LexerTerminals` crash) + the self-describing surface (manifest/operators) + a real rule read-back — through the native binary; assert no `MissingReflection`/`MissingResource`/`STNoSuchProperty`/"Can't find bundle" signature | ✅ catches *out-of-package* residuals at build time | **DONE** — **172 checks, 0 signatures** on the current binary (the broad sweep finding zero gaps is the evidence the §3.4 fix isn't whack-a-mole). Byte-identity vs JVM is **not** needed (failures are loud → no normalization layer). Wired into `native-gate.sh`. The date-operator row was added 2026-06-19 after probe 01b confirmed the path works on native — the deterministic guard that the v0.1.0 crash can't recur |
| `-H:+ThrowMissingRegistrationErrors` / strict mode | turns silent gaps loud | **already the GraalVM 25 default — nothing to wire.** `-R:MissingRegistrationReportingMode=Throw` is the default, and the v0.1.0/.1 bugs threw `MissingRegistrationError` from a binary built *without* the flag → the battery already runs against a throwing binary. The `-H:ThrowMissingRegistrationErrors=<paths>` form only *scopes* it; it is not the on/off switch |
| Exclude `validate`/`check` from native too | ✅ removes the class from native | **rejected** — guts the core value (kernel-confirmed authoring *is* the binary's point) |
| **Ship JVM (jpackage/jlink B1) instead of native** | ✅✅ removes the **entire** native-image failure surface | **the escape hatch — now measured** (§10): B1 builds + passes **6/6** demos, but **119 MB trimmed / 147 MB** (~4× native's 33 MB) and **~600 ms** start (~7× native's ~90 ms). Native now also passes 6/6 (the runtime verbs run on the interpreter); with `--kernel` eval retired (Set V) B1 no longer has an eval-engine edge at all. Native stays the shipped default; B1 is the proven fallback for the day a kernel engine must ship for another reason or the residual proves unmanageable |

**The irreducible residual (stated honestly):** rule-based registration + the battery shrink the risk to "a reflective path in a class *outside* the two packages that *no battery input* exercises." That cannot be driven to zero while AOT-compiling a reflection-heavy embedded kernel — the only zero is B1 (a real JVM). The chosen posture (all three now in place): **rule-based coverage of the known surfaces (§3.4) makes the common shapes safe; the no-crash battery (`native-battery.sh`, 172 checks) catches out-of-package gaps at build time; B1 (§10, measured) is the escape hatch if the battery ever shows native can't be made robust per shape.** Failures are at least **loud** (thrown/coded), never silently-wrong.

## 4. The Groovy ceiling — lifted for the verbs; the `--kernel` eval path that hit it is retired

**History (the ceiling that was):** `model eval` / `rule eval` / `model compute` used to execute the **kernel's** runtime validation against a document instance, which **generates and loads Groovy classes at runtime** (`GroovyClassLoader.parseClass`; the agent captured `Com_mgmtp_a12_kernel_generated_*` classes) — [`CLI-SPEC.md`](CLI-SPEC.md) §11's hard ceiling. Groovy is genuinely required by that *kernel* path (JVM and native), and the §3.1 exclusion keeps it off the native classpath, so the **kernel** engine can't run native.

**What lifted it:** those verbs now run on the **kernel-free `dm.interpreter`** — a from-scratch evaluator that reproduces the kernel's runtime semantics with no Groovy codegen (kernel-differential-verified, INTERPRETER-ARCHITECTURE/FINDINGS). So the verbs **run on the native binary** and stay on every self-describing surface. The one path that hit the Groovy ceiling — the opt-in `--kernel` eval engine — has since been **retired entirely** (Set V, INTERPRETER-DROPIN-CERTIFICATE): the interpreter is the sole runtime eval engine on every target, so no eval path invokes Groovy and no self-describing surface offers a JVM-only eval flag. `rule check`/`model check` are *structural* — they drive `checkConsistency`, which renders **StringTemplate** codegen (no Groovy compile/execute), so they too stay native (§3.4).

> **⚠ "StringTemplate, native-OK" is true only with §3.4's registration.** This line once read "native-OK — is unaffected," implying StringTemplate validate is free. It is not: StringTemplate's `ObjectModelAdaptor` resolves `<bean.prop>` by **reflective getter lookup at render time**, invisible to static analysis. Validating a *patterned* field renders a codegen path that reads `CodeGenMetaFields.getMetaFelderCode()` — unregistered → `STNoSuchPropertyException` → the model is reported "not kernel-valid" on native though the JVM accepts it (§3.5, bug 1b). Native validate is sound **only because §3.4 registers the whole codegen package**.

**Implemented — the native tree (`Cli.run(…, nativeProfile=true)`).** `Cli.JVM_ONLY_VERBS` is **empty** — no *verb* is hidden; the runtime verbs stay in the native tree (interpreter-backed) and a cold native agent discovers them normally, on the one live command tree (so `manifest`/`schema`/`--help` follow automatically). The former `Cli.NATIVE_REMOVED_OPTIONS` machinery — which stripped the JVM-only `--kernel` option in native — was **deleted** when `--kernel` eval was retired (Set V): the option no longer exists on any target, so there is nothing to strip. `Cli` detects native via `org.graalvm.nativeimage.imagecode`. **Guarded by `NativeImageRuntimeVerbsTest`** (CI, JUnit, forced-native profile on the JVM): it asserts the verbs are *present* on manifest/`--help`/`schema`, the interpreter path runs, and the retired `--kernel` flag is rejected as an unknown option on every profile — so native isn't built in CI but the contract can't regress. *(This supersedes the old hiding gate `NativeImageVerbHidingTest`, from when these verbs ran the kernel.)*

## 5. Generating the reflection metadata (tracing agent)

One committed script does it: **[`../scripts/capture-native-config.sh`](../scripts/capture-native-config.sh)** (`GRAALVM_HOME=…/graalvm-25 ./scripts/capture-native-config.sh`). It runs the **JUnit `:cli:test` suite** under the agent — `build.gradle`'s `-PrkNativeAgent` mode points the test task's `executable` at GraalVM (the agent ships there, not in the JDK-21 toolchain), attaches `-agentlib:native-image-agent=config-merge-dir`, and serializes forks (merge-dir is not fork-safe) — merging into one `reachability-metadata.json` and copying it into place. Then `./gradlew :cli:nativeCompile` bundles it. **Why the test suite, not a curated verb list:** the agent only registers what it reaches, so a hand-driven list silently omits any shape it forgets (the v0.1.0/v0.1.1 bugs, §3.5). Binding the capture to `:cli:test` makes coverage track the suite and grow with every TDD test — a new shape with a test is covered automatically, no one having to reason about it.

### 5.1 Where it's stored & how often to regenerate — it is NOT a per-build cost

There are now **two committed metadata sources** (GraalVM merges both), plus a build-arg glob — and a kernel bump must refresh **all three**:

```
cli/src/main/resources/META-INF/native-image/rulekit-agent/reachability-metadata.json    (~520 KB, TEST-SUITE agent capture — §3.3/§5)
cli/src/main/resources/META-INF/native-image/rulekit-manual/reachability-metadata.json   (399 entries, RULE-BASED — §3.4)
cli/build.gradle  →  -H:IncludeResources=.*error_messages.*\.properties                  (i18n bundle glob — §3.4)
```

`:cli:nativeCompile` **reads** these; it does not regenerate them. Both metadata files derive from the **kernel's own classes**, so a kernel bump can change the class set in the registered packages, the reflective hotspots, *and* the i18n bundle base names — **all three drift on a bump and must be redone in lockstep.** Triggers and the exact redo step:

| Trigger | Redo |
|---|---|
| **Kernel bump** (`a12-kernel`) | **(1)** `./scripts/gen-native-reflect-metadata.sh` (re-derives the 399 rule-based entries from the new jars); **(2)** `GRAALVM_HOME=…/graalvm-25 ./scripts/capture-native-config.sh` (re-runs the test-suite agent capture); **(3)** confirm the i18n glob still matches the bundle layout (`unzip -l` the kernel jars for `error_messages*.properties` — §3.4); **(4)** `:cli:nativeCompile` + `./scripts/native-gate.sh`. See [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md). |
| **CLI / catalog surface change** | a new verb, a new Jackson POJO, new operators in `operators.json` → ensure a `:cli:test` exercises it (usually already true under TDD), then re-run the capture (2). Coverage tracks the suite, so the capture follows the tests automatically |
| **CLI command-class *package move/rename*** | the agent capture records command classes by **fully-qualified name**, so moving them between packages (e.g. the 2026-06-17 family-sub-package split) makes the captured FQ names stale → re-run the capture (2). A mechanical FQ-rename of `rulekit-agent/reachability-metadata.json` is a valid stopgap when GraalVM-25 isn't on hand (it was done for the split), but the next real capture supersedes it |
| **Jackson / reflection-using dependency bump** | databind internals change → re-run the agent capture (2). *Note (2026-06-18): R3/R4 added `jackson-dataformat-yaml` (SnakeYAML, reflection-using) for `workspace roles`' `roles.yaml` + `users.yaml` reads — a new reflective surface the next capture must cover; `cli.workspace.WorkspaceRolesCommandTest` exercises it, so the suite-tracking capture picks it up automatically.* |
| **New kernel reflective surface** (the gate finds a `MissingReflection`/`STNoSuchProperty` outside the two §3.4 packages) | add the offending package to `PACKAGES` in `gen-native-reflect-metadata.sh` and re-run (1) — extend the *rule*, don't hand-add one class |

The **verification gate is the trip-wire**: if any source lags, a fresh reflective path throws `MissingReflectionRegistrationError`/`MissingResourceException`/`STNoSuchPropertyException`, and the native gate fails. **But note (§3.5): the gate's inputs decide what it catches** — the demo-only gate that shipped passed while patterned-field/parser-error paths were broken. `native-gate.sh` now runs the **no-crash battery** ([`../scripts/native-battery.sh`](../scripts/native-battery.sh), §3.6 — 172 checks across every fixture × read/validate verbs + the FieldKind × constraint matrix + invalid conditions per locale + the self-describing surface (manifest/operators) + a real rule read-back), so the broad reflective/resource surface is checked, not just the happy-path demos. Add a shape to the battery whenever a new native-only gap is found.

## 6. Sizing & shrink levers

`-Os` (GraalVM 25) is the big lever: **19 MB** bare, **~33 MB** with the reflection metadata (the rule-based §3.4 registration added only ~0.8 MB over the prior ~31 MB — the classes are already in the image, only metadata is added). Far below the GraalVM-21 numbers (52–61 MB) and **far** below the **measured** jpackage app-image (§10: **119 MB trimmed / 147 MB**, ~4×). Further levers if needed: `--strip-debug`, drop more unused kernel jars, tighter (hand-authored vs full-agent) metadata. `upx` (~50%) is possible but **breaks macOS notarization + slows startup** — not for distribution.

## 7. Decision — native-only for v1 (B2)

**Decided: v1 ships native-only.** The shipped CLI is the GraalVM 25 native binary — the right fit for the agent-first, latency-sensitive use case (~37 ms start, ~31 MB, downloaded on demand by the plugin's skill-run installer). GraalVM 25 closed both earlier blockers:

1. ~~Reflection coverage~~ — **done** (§3.3, the comprehensive capture).
2. ~~The 3 runtime-eval verbs~~ — **done** (§4, the registration gate hides them from every surface; guarded by `NativeImageVerbHidingTest`).

**Scope consequence of native-only:** v1's shipped CLI **does** offer runtime evaluation (`model eval`/`rule eval`/`model compute` — on the kernel-free interpreter) and `model seed`. With `--kernel` eval retired (Set V), there is no JVM-only eval engine left — evaluation is interpreter-only on every target, so native has no eval-capability gap. No jpackage/B1 full-verb distributable in v1. (B1 — a trimmed JVM + launcher, no reflection grind, **measured at 119–147 MB/OS and ~600 ms start** below — stays the documented fallback if a real consumer ever needs a bundled kernel engine outside the dev loop.) What's left for the release is **release-engineering only** — signing/distribution (RELEASE-SPEC).

**jpackage (B1) — measured 2026-06-15** (not estimated). Built from `installDist` via `jpackage --type app-image` (`jdeps`-derived module set `java.{base,compiler,desktop,management,prefs,scripting,sql}`):

| | Native (current, hardened) | B1 (JVM app-image) |
|---|---|---|
| Size | **33 MB** single binary | **119 MB** trimmed (`--strip-debug --compress=zip-9 --no-man/-header`) / 147 MB untrimmed — runtime 99 MB + libs 20 MB |
| Cold start | **~90 ms** | ~600 ms warm, ~1.5 s first-call |
| Demos | **6/6** (incl. cli-runtime — interpreter-backed) | **6/6** |
| Native-image failure classes | the §3.6 taxonomy — *managed* (rule-based + battery), residual remains | **zero** (it's a JVM) |

**Decision (re-affirmed on measured data): native-only for v1, B1 = proven escape hatch.** Native's 4× smaller single-file artifact and ~7× faster start are exactly aligned to the agent-plugin delivery (download one 33 MB binary), and the whack-a-mole is now *bounded* (§3.4) + *gated* (§3.6 battery). With `--kernel` eval retired (Set V), B1 has no unique eval win left — the runtime verbs run native on the interpreter — so B1's only remaining offer is zero native-image risk at a 4×-size / 7×-startup cost. Flip to B1 only if shipping a bundled kernel engine becomes a real need, or the battery shows native can't be kept robust per shape. The B1 build is reproducible from the recipe above (no committed artifact — it's the fallback, not the ship).

## 8. Verification gate — two gates, two targets

The demos are authored for the **full JVM CLI**, so against the native *subset* some are legitimately not-applicable. Rather than eyeball a known red, the gate is split by target:

- **JVM gate** — CI, the `installDist` launcher, **all 7 demos green** (`.github/workflows/ci.yml`; `node` is set up there for the `cli-custom-types` JS-escape demo). Unchanged.
- **Native gate** — **[`../scripts/native-gate.sh`](../scripts/native-gate.sh)** — verifies the native binary byte-for-byte across the **native-applicable** demos, and **skips the non-applicable ones with their reason** (never a spurious FAIL). Exits non-zero only on a real regression in a native-applicable demo.

```
$ ./scripts/native-gate.sh
PASS  cli-apply.md
PASS  cli-edit-loop.md
PASS  cli-runtime.md
PASS  cli-structure-edit.md
PASS  cli-tour.md
PASS  cli-version.md
native gate: GREEN
```

**`NATIVE_SKIP` is now EMPTY — all 6 demos are native-applicable (2026-06-24).** The two former skips are obsolete:

- `cli-runtime.md` — its runtime verbs (`model eval`/`rule eval`/`model compute`) run on the kernel-free interpreter, so they run on the native binary (verified above). The former JVM-only `--kernel` eval option was **retired** (Set V) — no target offers it; guarded by `NativeImageRuntimeVerbsTest`. **Standing convention if a verb ever becomes genuinely native-incompatible:** isolate it in its own demo file (showboat verify is per-file, so one unverifiable block would forfeit native coverage of the rest) — but no verb is in that state today.
- `cli-tour.md` — its `manifest` block is no longer a native subset: `Cli.JVM_ONLY_VERBS` is empty (no verb hidden), so the native manifest equals the JVM one and the demo verifies byte-for-byte on native too. (`NativeImageRuntimeVerbsTest` superseded the old hiding test.)

> **Pending the next native build: the 7th demo, `cli-custom-types.md` (added 2026-06-27).** It exercises the G31 JS escape (`--custom-conditions-js` / `--custom-field-types-js`), whose blocks spawn the system **`node`**. On native it is applicable **only if `node` is on the host PATH** (else those blocks degrade to `unsupported` → different output → the per-file verify fails). When native is next built: ensure `node` on the native test host (as CI does) and re-measure the demo gate to **7/7**, or — per the standing convention above — isolate the node-dependent blocks. The §1 "6/6" status is the 2026-06-24 measurement, before this demo existed.

> **Closed (v0.2.0) — `manifest` + a real `rule read` read-back are now in the battery.** `manifest` lives only in `cli-tour.md` (which was skipped on native back then), and the fixture sweep's `rule read` runs **without** a `<rulePathInModel>` (picocli rejects it before the read tier) — so for a while the only thing running `manifest` on a native binary was the **Windows CI smoke** (RELEASE-SPEC §4), and the rule read-back was untested locally too. A native-only `manifest` defect (it builds the full picocli tree + operator catalog → a reflection/resource path the read/validate verbs don't hit) would have slipped past the local gate; it surfaced indirectly when the Windows smoke's *own* bugs were fixed and it finally ran `manifest`. **`native-battery.sh` now drives `manifest`, `operators`, and `rule read`/`rule explain /Order/DeliveryNotBeforeOrder`** (catalog + adapter-read-back reflection), so the local gate covers them — not only the Windows-only smoke.

This split is what keeps "native ≡ JVM" honest *per verb* (identical behavior for every verb native exposes) while letting native legitimately expose fewer.
