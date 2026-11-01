plugins {
    kotlin("multiplatform") version "2.4.0"
}
kotlin {
    jvmToolchain(21)
    jvm()
    js {
        nodejs()
        binaries.executable()
    }
    sourceSets.commonMain.dependencies {
        implementation("io.github.mbackschat.a12.dm:dm-consistency:" + providers.environmentVariable("CHECKER_VERSION").get())
    }
}
tasks.register<JavaExec>("runJvm") {
    dependsOn("jvmJar")
    classpath(files(tasks.named("jvmJar")), configurations["jvmRuntimeClasspath"])
    mainClass.set("JvmConsumerKt")
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download = false
}
