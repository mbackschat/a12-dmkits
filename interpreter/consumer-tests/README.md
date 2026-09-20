# Consumer tests — proving the interpreter is *resolvable*, not only correct

Everything under [`../src`](../src) tests the interpreter from inside Kotlin, where the code under test is on the compile classpath by construction. These four projects do the thing a consumer actually does: **resolve the published artifact by coordinate or package name from a fresh, standalone build, then run it.**

That distinction has caught real defects. A module can compile green while emitting nothing importable; a published POM can name a coordinate nothing publishes; a bundler can choke on an artifact the JVM loads happily. None of those is visible to a test that imports a relative path into `build/`.

| project | how it resolves the artifact | driven by |
|---|---|---|
| [`kotlin-jvm/`](kotlin-jvm) | `dm-interpreter` from a staged Maven repository, in its **own** Gradle build | `./build.sh :interpreter:verifyExternalInterpreterMavenConsumer` |
| [`kotlin-js/`](kotlin-js) | the same coordinate, resolved for the **Kotlin/JS** target, in its own Gradle build | `./build.sh :interpreter:verifyExternalInterpreterMavenJsConsumer` |
| [`typescript/`](typescript) | the packed npm tarball, installed and consumed as TypeScript | `./build.sh :interpreter:verifyExternalInterpreterTypeScriptConsumer` |
| [`browser/`](browser) | the same tarball, Vite-bundled and run in a real browser — and this project **is** the published showcase | `./build.sh :interpreter:verifyExternalInterpreterBrowserConsumer` |

**Each is a standalone build, not a Gradle subproject, and that is the point.** `kotlin-jvm/` and `kotlin-js/` carry their own `settings.gradle.kts`, so they cannot accidentally see this repository's source, its version catalog or its configuration — the only way in is the published coordinate. A composite build would quietly substitute the project for the artifact and prove nothing.

**Some of these files are also documentation under test.** `kotlin-jvm/`'s `GuideExample.kt`, `JavaGuideExample.java` and `ServiceCacheExample.kt`, and `typescript/`'s `guide-finite.ts`, `guide-provider.ts` and `guide-customization.ts`, are the *compiled* sources behind the published API guide's snippets — so a guide example that stops compiling against the real artifact fails the build instead of misleading a reader.

Sibling: [`../../consistency/consumer-tests`](../../consistency/consumer-tests) does the same job for the portable checker. Module instructions: [`../CLAUDE.md`](../CLAUDE.md) · testing map: [`docs/TESTING-SPEC.md`](../../docs/TESTING-SPEC.md).
