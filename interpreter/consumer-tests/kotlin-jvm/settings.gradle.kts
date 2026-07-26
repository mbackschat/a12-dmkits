import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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

rootProject.name = "interpreter-maven-consumer"
