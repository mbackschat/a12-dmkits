# Build inputs

Not a project — the shared inputs the Gradle build reads, kept here so the repository root stays the source modules plus a small, predictable set of conventional directories.

| entry | what it is |
|---|---|
| [`wrapper/`](wrapper) | the Gradle wrapper distribution pin. Prefer [`../build.sh`](../build.sh) over `./gradlew` directly — it pins an isolated `GRADLE_USER_HOME` and JDK 21 so the machine's global Gradle config cannot contaminate the build |
| [`focused-test-selection.gradle`](focused-test-selection.gradle) | the selection guard: it refuses a filtered broad `test` task without `-Pfast` *before* prerequisites execute, so an inner-loop run cannot silently skip the guards that a checkpoint needs |
| [`node-tools.gradle`](node-tools.gradle) | resolves the Node and pnpm tooling the JS lanes and consumer tests need, so a lane names a tool rather than assuming a machine has it |
| [`graalvm-native.properties`](graalvm-native.properties) | the native-image build's pinned configuration — see [`docs/NATIVE-IMAGE-GRAALVM.md`](../docs/NATIVE-IMAGE-GRAALVM.md) |
| [`kotlin-js-store/yarn.lock`](kotlin-js-store/yarn.lock) | the committed Kotlin/JS lockfile. The Kotlin Multiplatform plugin defaults it to a top-level `kotlin-js-store/`; the root build redirects it here through `lockFileDirectory`, because a toolchain artifact should not sit beside the source modules |

Build environment, the isolated Gradle home, the JDK pin and the offline limits: [`docs/BUILD-ENVIRONMENT.md`](../docs/BUILD-ENVIRONMENT.md). Which lane owns what: [`docs/AGENT-RUN-DISCIPLINE.md`](../docs/AGENT-RUN-DISCIPLINE.md).
