package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.CaseJson
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The conformance corpus **schema & id guard** (CONFORMANCE-CORPUS-SPEC §13): every committed case parses
 * through the reference (de)serializer ([io.github.mbackschat.a12.dm.interpreter.conformance.CaseJson], the
 * executable validation a runner uses), and the structural invariants hold — `meta.id` equals the file stem,
 * ids are unique corpus-wide, the `partial` tag ⇔ a `validatePart` op, the pinned kernel version, and exactly the
 * op's expectation key is present. The standalone-model invariants also hold: every `modelRef` resolves against
 * the corpus root, every model file under `models/` loads via the kernel-free [ModelLoader], and no model file is
 * orphaned (a model lands with its first referencing case). Also checks `corpus/case.schema.json` exists and stays
 * consistent with the shape the code enforces (a no-rot cross-check without a JSON-schema runtime).
 */
class CorpusSchemaGuardTest {

    @Test
    fun schemaFileExistsAndDescribesTheEnforcedShape() {
        val schema = CorpusFiles.corpusRoot().resolve("case.schema.json")
        assertTrue(schema.exists()) { "missing $schema" }
        val text = schema.readText()
        // Cross-check: the published schema mentions the keys/enums the code (CaseJson) requires, so the two
        // cannot silently drift (a deleted key or renamed enum token trips here).
        listOf("\"meta\"", "\"modelRef\"", "models/", "\"placements\"", "\"op\"", "fuzz-seed", "curated",
            "validateFull", "validatePart", "compute", "GROUP", "FIELD", "EMPTY").forEach {
            assertTrue(it in text) { "corpus/case.schema.json is missing expected token $it" }
        }
    }

    @Test
    fun everyModelRefResolvesToTheCanonicalForm() {
        // CorpusFiles.all() resolves each case's modelRef against the corpus root — an unresolvable ref throws here.
        val refs = CorpusFiles.all().map { it.case.modelRef }
        val bad = refs.filterNot { Regex("^models/[a-z0-9-]+\\.json$").matches(it) }
        assertTrue(bad.isEmpty()) { "modelRef(s) not of the corpus-root-relative form models/<name>.json: $bad" }
    }

    @TestFactory
    fun everyModelFileLoadsAndIsReferenced(): List<DynamicTest> {
        val referenced = CorpusFiles.all().map { it.case.modelRef }.toSet()
        val modelsDir = CorpusFiles.modelsDir()
        val files = if (Files.exists(modelsDir)) {
            Files.list(modelsDir).use { s -> s.filter { it.name.endsWith(".json") }.sorted().toList() }
        } else emptyList()
        return files.map { file ->
            DynamicTest.dynamicTest("models/${file.name}") {
                assertTrue("models/${file.name}" in referenced) {
                    "orphaned model file (no referencing case): models/${file.name}"
                }
                val text = file.readText()
                ModelLoader.load(text)   // loads as a model through the kernel-free loader, or throws
                // The model's SME workspace-item name (header.id) MUST equal the file stem: file stems are unique
                // corpus-wide, so id==stem makes every model a uniquely-named workspace item by construction — two
                // models sharing a base id (e.g. all Order models) can no longer collide in a workspace/SME.
                assertEquals(file.name.removeSuffix(".json"), CaseJson.modelIdOf(text),
                    "model header.id must equal the file stem (unique workspace-item name)")
            }
        }
    }

    @Test
    fun caseIdsAreUniqueCorpusWide() {
        val ids = CorpusFiles.all().map { it.case.meta.id }
        val dupes = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(dupes.isEmpty()) { "duplicate case ids: $dupes" }
    }

    @TestFactory
    fun everyCaseSatisfiesTheInvariants(): List<DynamicTest> = CorpusFiles.all().map { cf ->
        DynamicTest.dynamicTest("${cf.path.parent.fileName}/${cf.case.meta.id}") {
            val case = cf.case
            assertEquals(cf.path.name.removeSuffix(".json"), case.meta.id, "meta.id must equal the file stem")
            assertEquals(CorpusEngines.KERNEL_VERSION, case.meta.kernelVersion, "meta.kernelVersion must be the pin")
            assertTrue(case.meta.sections.isNotEmpty(), "meta.sections must be non-empty")

            val isPartial = case.op is Op.ValidatePart
            assertEquals(isPartial, "partial" in case.meta.tags, "the 'partial' tag ⇔ a validatePart op")

            if (case.op is Op.Compute) {
                assertTrue(case.computeExpected != null && case.expected == null,
                    "a compute op carries computeExpected (not expected)")
            } else {
                assertTrue(case.expected != null && case.computeExpected == null,
                    "a validate op carries expected (not computeExpected)")
            }
            case.clock?.let {
                assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$""").matches(it)) { "malformed clock: $it" }
            }
        }
    }
}
