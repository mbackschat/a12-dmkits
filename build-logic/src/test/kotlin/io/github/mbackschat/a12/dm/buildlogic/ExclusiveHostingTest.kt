package io.github.mbackschat.a12.dm.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExclusiveHostingTest {

    private val owners = linkedMapOf(
        "docGuardTest" to setOf("a.DocsIndexGuardTest", "a.DocAnchorResolveGuardTest"),
        "perfTest" to setOf("a.PerfGateTest"),
    )

    @Test
    fun `an owner whose filter includes nothing is refused by name`() {
        // The mutant this exists for: gut the derivation and it yields nothing, so the broad lane excludes
        // nothing while every text-matching guard still passes.
        val refusal = ExclusiveHosting.emptyOwnerRefusal(":test", owners + ("emptyLane" to emptySet()))
        requireNotNull(refusal)
        assertTrue(refusal.contains("'emptyLane'"), refusal)
        assertNull(ExclusiveHosting.emptyOwnerRefusal(":test", owners))
    }

    @Test
    fun `two lanes claiming one class is refused rather than resolved last-wins`() {
        val clashing = owners + ("regenerator" to setOf("a.DocsIndexGuardTest"))
        val refusal = ExclusiveHosting.ambiguousOwnershipRefusal(clashing)
        requireNotNull(refusal)
        assertTrue(refusal.contains("a.DocsIndexGuardTest"), refusal)
        assertTrue(refusal.contains("docGuardTest"), refusal)
        assertTrue(refusal.contains("regenerator"), refusal)
    }

    @Test
    fun `one lane naming a class twice is not ambiguous`() {
        assertNull(ExclusiveHosting.ambiguousOwnershipRefusal(owners))
    }

    @Test
    fun `every owned class maps to its lane`() {
        assertEquals(
            mapOf(
                "a.DocsIndexGuardTest" to "docGuardTest",
                "a.DocAnchorResolveGuardTest" to "docGuardTest",
                "a.PerfGateTest" to "perfTest",
            ),
            ExclusiveHosting.hostByClass(owners),
        )
    }

    @Test
    fun `only classes owned by a build-tooling lane stay out of a capture`() {
        assertEquals(
            setOf("a.PerfGateTest"),
            ExclusiveHosting.buildToolingClasses(ExclusiveHosting.hostByClass(owners), setOf("perfTest")),
        )
        assertEquals(
            emptySet<String>(),
            ExclusiveHosting.buildToolingClasses(ExclusiveHosting.hostByClass(owners), emptySet()),
        )
    }

    @Test
    fun `a build-tooling classification naming a non-owner is refused`() {
        val refusal = ExclusiveHosting.untracedNonOwnerRefusal(":test", setOf("ghostLane"), owners.keys)
        requireNotNull(refusal)
        assertTrue(refusal.contains("'ghostLane'"), refusal)
        assertNull(ExclusiveHosting.untracedNonOwnerRefusal(":test", setOf("perfTest"), owners.keys))
    }

    @Test
    fun `only an unclassified filtered lane sharing the compiled output must be classified`() {
        assertTrue(requires())
        assertFalse(requires(name = "test"), "the broad lane is not its own stray")
        assertFalse(requires(name = "docGuardTest"), "a declared owner is classified")
        assertFalse(requires(name = "regenerator"), "a reasoned non-owner is classified")
        assertFalse(requires(filtered = false), "an unfiltered lane owns no individual class")
        assertFalse(requires(shared = false), "a separate source set cannot run in the broad lane")
    }

    /**
     * A lane exempted by NAME must not pay to answer the two expensive questions. Reading a task's filter and
     * resolving its `testClassesDirs` is configuration-time work over every sibling `Test` task, and the
     * broad lane and every declared owner are exempt before either is needed.
     */
    @Test
    fun `a lane exempted by name asks neither expensive question`() {
        val asked = mutableListOf<String>()
        fun ask(name: String) = ExclusiveHosting.requiresClassification(
            taskName = name,
            broadTaskName = "test",
            owningTaskNames = owners.keys,
            classifiedNonOwners = setOf("regenerator"),
            hasIncludePatterns = { asked += "$name:filter"; true },
            sharesCompiledOutput = { asked += "$name:classes"; true },
        )
        listOf("test", "docGuardTest", "perfTest", "regenerator").forEach { ask(it) }
        assertEquals(emptyList<String>(), asked, "an exemption by name must short-circuit before both")

        ask("strayLane")
        assertEquals(listOf("strayLane:filter", "strayLane:classes"), asked)

        asked.clear()
        ExclusiveHosting.requiresClassification(
            "unfilteredLane", "test", owners.keys, setOf("regenerator"),
            hasIncludePatterns = { asked += "filter"; false },
            sharesCompiledOutput = { asked += "classes"; true },
        )
        assertEquals(listOf("filter"), asked, "an unfiltered lane must not resolve its class dirs")
    }

    private fun requires(
        name: String = "strayLane",
        filtered: Boolean = true,
        shared: Boolean = true,
    ) = ExclusiveHosting.requiresClassification(
        taskName = name,
        broadTaskName = "test",
        owningTaskNames = owners.keys,
        classifiedNonOwners = setOf("regenerator"),
        hasIncludePatterns = { filtered },
        sharesCompiledOutput = { shared },
    )

    @Test
    fun `a classification row must name a declared property and give a reason`() {
        val declared = setOf("junit.parallel", "a12.exe")
        assertNull(
            ExclusiveHosting.invalidClassificationRefusal(
                "publicContractTest", declared, mapOf("junit.parallel" to "scheduler only"),
            ),
        )
        requireNotNull(
            ExclusiveHosting.invalidClassificationRefusal(
                "publicContractTest", declared, mapOf("absent.key" to "scheduler only"),
            ),
        )
        requireNotNull(
            ExclusiveHosting.invalidClassificationRefusal(
                "publicContractTest", declared, mapOf("junit.parallel" to "  "),
            ),
        )
    }

    @Test
    fun `an absent classification is empty and a non-map one is a mistake`() {
        assertEquals(emptyMap<String, String>(), ExclusiveHosting.classificationRows(null))
        assertEquals(
            mapOf("junit.parallel" to "scheduler only"),
            ExclusiveHosting.classificationRows(mapOf("junit.parallel" to "scheduler only")),
        )
        // A non-string reason must not read as a valid row; it becomes blank, which the refusal above catches.
        assertEquals(mapOf("junit.parallel" to ""), ExclusiveHosting.classificationRows(mapOf("junit.parallel" to 7)))
        // Anything that is not a map at all is a mistake, and null is how this says so — reading it as an
        // empty classification would silently exempt nothing while looking like it exempted everything.
        assertNull(ExclusiveHosting.classificationRows("junit.parallel"))
        assertNull(ExclusiveHosting.classificationRows(listOf("junit.parallel")))
    }

    @Test
    fun `a capture re-host must supply every unclassified property the owner declares`() {
        val refusal = ExclusiveHosting.unsuppliedPropertyRefusal(
            broadPath = ":cli:test",
            ownerName = "publicContractTest",
            ownerProperties = setOf("a12.exe", "a12.version", "junit.parallel"),
            broadProperties = setOf("a12.exe"),
            classified = setOf("junit.parallel"),
        )
        requireNotNull(refusal)
        assertTrue(refusal.contains("a12.version"), refusal)
        assertFalse(refusal.contains("junit.parallel"), "a classified key is not demanded: $refusal")
        assertNull(
            ExclusiveHosting.unsuppliedPropertyRefusal(
                ":cli:test", "publicContractTest",
                setOf("a12.exe"), setOf("a12.exe"), emptySet(),
            ),
        )
    }
}
