package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The conformance corpus **schema & id guard** (CONFORMANCE-CORPUS-SPEC §13): every committed case parses
 * through the reference (de)serializer ([io.github.mbackschat.a12.dm.interpreter.conformance.CaseJson], the
 * executable validation a runner uses), and the structural invariants hold — `meta.id` equals the file stem,
 * ids are unique corpus-wide, the `partial` tag ⇔ a `validatePart` op, the pinned kernel version, and exactly the
 * op's expectation key is present. Also checks `corpus/case.schema.json` exists and stays consistent with the
 * shape the code enforces (a no-rot cross-check without a JSON-schema runtime).
 */
class CorpusSchemaGuardTest {

    @Test
    fun schemaFileExistsAndDescribesTheEnforcedShape() {
        val schema = CorpusFiles.repoRoot().resolve("corpus").resolve("case.schema.json")
        assertTrue(schema.exists()) { "missing $schema" }
        val text = schema.readText()
        // Cross-check: the published schema mentions the keys/enums the code (CaseJson) requires, so the two
        // cannot silently drift (a deleted key or renamed enum token trips here).
        listOf("\"meta\"", "\"model\"", "\"placements\"", "\"op\"", "fuzz-seed", "curated",
            "validateFull", "validatePart", "compute", "GROUP", "FIELD", "EMPTY").forEach {
            assertTrue(it in text) { "corpus/case.schema.json is missing expected token $it" }
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
