package io.github.mbackschat.a12.dm.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gives a project the shared build guards as a typed extension — `a12Guards` — instead of a set of untyped
 * closures on the ROOT project's `ext` reached as `rootProject.<helper>(...)`.
 *
 * That reach was load-bearing on root configuring first, is cross-project state access (which project
 * isolation forbids), and put the code where nothing could unit-test it. Applying a plugin has none of those
 * properties, and the decision logic behind each helper now lives in plain Kotlin objects beside their tests.
 */
class BuildGuardsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("a12Guards", BuildGuardsExtension::class.java, project)
    }
}
