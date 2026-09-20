# Standalone Kotlin/JS consumer

The [`kotlin-jvm`](../kotlin-jvm) sibling's question asked of the **other** target: can a Kotlin/JS build resolve `dm-interpreter` from the staged Maven repository and run it on Node? Its own [`settings.gradle.kts`](settings.gradle.kts) keeps this repository's sources out of reach, so only the published Gradle-module metadata and the `js` variant can satisfy it.

```sh
./build.sh :interpreter:verifyExternalInterpreterMavenJsConsumer
```

[`Consumer.kt`](src/jsMain/kotlin/Consumer.kt) reads a model with Node's own `fs` through an `@JsModule` external declaration, prepares a workspace and validates a document — deliberately using the platform's API rather than a Kotlin abstraction, because a consumer on this target will.

**This is the Maven/Kotlin path, not the npm path.** A TypeScript or JavaScript consumer installs the packed tarball instead; that arm is [`../typescript`](../typescript), and the browser arm is [`../browser`](../browser). Both resolution routes are published, so both are proved.
