// Kotlin, deliberately: the Gradle API this code consumes is typed, nullability-annotated and lambda-heavy,
// and a plain Kotlin class is the part a unit test can reach. The 7 000-odd lines of consuming Groovy build
// script are untouched — a precompiled Kotlin script plugin applies from a Groovy `build.gradle` unchanged.
plugins {
    `kotlin-dsl`
}

// A separate source set, so the TestKit lane — which spawns real Gradle builds and costs seconds per case —
// stays out of the fast unit lane. `testSourceSets` is what puts the plugin under test on its classpath.
val functionalTest: SourceSet by sourceSets.creating

gradlePlugin {
    testSourceSets(functionalTest)
    plugins {
        create("buildGuards") {
            id = "a12.build-guards"
            implementationClass = "io.github.mbackschat.a12.dm.buildlogic.BuildGuardsPlugin"
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "functionalTestImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "functionalTestImplementation"("org.junit.jupiter:junit-jupiter")
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Proves each guard refusal FIRES against a real Gradle build."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
tasks.named("check") { dependsOn(functionalTestTask) }
