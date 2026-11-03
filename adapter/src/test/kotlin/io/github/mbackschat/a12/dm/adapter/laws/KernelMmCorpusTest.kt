package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.core.ModelFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Locks that the migrated metamodel corpus loses no file and lands at the version the kernel builds.
 *
 * A file the migrator drops shrinks every kernel-mm sweep without failing any of them, because each sweep only
 * asserts that SOME model got through; this is the one place the denominator is compared with its source.
 */
class KernelMmCorpusTest {

    @Test
    fun everyMetamodelIsMigratedToTheKernelsModelVersion() {
        val raw = KernelMmCorpus.raw()
        assumeTrue(raw != null, "a12-kernel-mm not present — skipping the migrated-corpus lock")
        val migrated = KernelMmCorpus.migrated()!!
        assertEquals(relativeJson(raw!!), relativeJson(migrated))
        val versions = relativeJson(migrated).associateWith {
            MAPPER.readTree(migrated.resolve(it).readText()).path("header").path("modelVersion").asText()
        }
        assertEquals(versions.keys.associateWith { ModelFormat.VERSION }, versions)
    }

    private fun relativeJson(root: Path): List<String> = Files.walk(root).use { paths ->
        paths.filter { it.extension == "json" }.map { root.relativize(it).toString() }.sorted().toList()
    }

    private companion object {
        val MAPPER = ObjectMapper()
    }
}
