# Standalone Kotlin/JVM consumer

Its **own** Gradle build — note the [`settings.gradle.kts`](settings.gradle.kts) — which resolves `io.github.mbackschat.a12.dm:dm-interpreter` from a staged Maven repository and runs it. Because this build cannot see the repository that produced the artifact, the only way in is the published coordinate: a broken POM, a missing variant or a wrong dependency scope fails here rather than at a consumer's site.

```sh
./build.sh :interpreter:verifyExternalInterpreterMavenConsumer
```

[`Consumer.kt`](src/main/kotlin/Consumer.kt) is the resolution proof: prepare a workspace from a model source, validate a document, read the result. The rest is **documentation under test** — [`GuideExample.kt`](src/main/kotlin/GuideExample.kt), [`JavaGuideExample.java`](src/main/java/JavaGuideExample.java) and [`ServiceCacheExample.kt`](src/main/kotlin/ServiceCacheExample.kt) are the compiled sources behind the published API guide's Kotlin and Java snippets, so a guide example that stops compiling against the real artifact fails the build instead of misleading a reader.

The Java file is not redundant with the Kotlin one: a Java consumer meets a different surface — no default arguments, no named parameters, nullability via annotations — and only Java source proves that surface is usable.
