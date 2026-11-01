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
                maven { url = uri(providers.environmentVariable("CHECKER_MAVEN_REPOSITORY").get()) }
            }
            filter { includeGroup("io.github.mbackschat.a12.dm") }
        }
        mavenCentral()
    }
}
rootProject.name = "checker-maven-gradle-consumer"
