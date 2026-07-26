pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "interpreterStaging"
                    url = uri(providers.environmentVariable("INTERPRETER_MAVEN_REPOSITORY").get())
                }
            }
            filter {
                includeGroup("io.github.mbackschat.a12.dm")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "interpreter-maven-js-consumer"
