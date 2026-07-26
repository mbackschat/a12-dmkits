plugins {
    kotlin("multiplatform") version "2.2.0"
}

kotlin {
    js {
        nodejs()
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(
                "io.github.mbackschat.a12.dm:dm-interpreter:" +
                    providers.environmentVariable("INTERPRETER_VERSION").get(),
            )
        }
    }
}
