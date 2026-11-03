package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The checker against A12 on two imported libraries that define one id (KERNEL-FINDINGS KF647). Kernel 31.1.1's
 * expansion admits the second definition only when it equals the first, member by member, and otherwise builds no
 * model. Each row edits one member of the second library's definition.
 *
 * The checker refuses where both definitions write a member differently, and leaves a member only one side writes to
 * A12: an explicit default and an absent member bind alike, so judging that row would need every type's defaults.
 * The last row is that under-report, pinned so its direction stays visible.
 *
 * Own domain (a permit and its code).
 */
class CheckerImportedTypeDefinitionAgreementTest {

    private val mapper = ObjectMapper()

    @Test
    fun aSecondDefinitionOfOneIdMustEqualTheFirst(@TempDir dir: Path) {
        val rows = linkedMapOf<String, (ObjectNode) -> Unit>(
            "equal — the control" to { },
            "a different maximum length" to { it.with("fieldType").with("StringType").put("maxLength", 16) },
            "a different name" to { it.put("name", "OtherCode") },
            "a different field type" to { it.replace("fieldType", mapper.readTree("""{"type":"NumberType","NumberType":{}}""")) },
            "an unknown member" to { it.put("note", "ignored by the reader") },
            "a revision only the second writes" to { it.put("revision", "2") },
        )
        val observed = rows.mapValues { (_, edit) ->
            val second = provider("long-types").also { edit(definition(it)) }.toString()
            Files.writeString(dir.resolve("short-types.dm.json"), provider("short-types").toString())
            Files.writeString(dir.resolve("long-types.dm.json"), second)
            a12(dir) to ours(provider("short-types").toString(), second)
        }
        val expected = linkedMapOf(
            "equal — the control" to (ADMITTED to ADMITTED),
            "a different maximum length" to (UNBUILT to UNBUILT),
            "a different name" to (UNBUILT to UNBUILT),
            "a different field type" to (UNBUILT to UNBUILT),
            "an unknown member" to (ADMITTED to ADMITTED),
            "a revision only the second writes" to (UNBUILT to ADMITTED),
        )
        assertEquals(expected, observed, "(row → A12, the checker)")
    }

    private fun a12(dir: Path): String = try {
        val classes = KernelConsistencyOracle().checkConsistency(CONSUMER, ReferenceResolvers.directory(dir))
        if (classes.isEmpty()) ADMITTED else classes.joinToString { it.message().trim() }
    } catch (expansion: Exception) {
        UNBUILT
    }

    private fun ours(first: String, second: String): String {
        val findings = ConsistencyWorkspace.fromSources(listOf(ModelSource(CONSUMER), ModelSource(first), ModelSource(second)))
            .check("permit").findings.filter { it.justifiesRejection }.map { it.code }
        return if (findings.isEmpty()) ADMITTED else if (UNBUILT in findings) UNBUILT else findings.joinToString()
    }

    private fun provider(id: String): ObjectNode = mapper.readTree(DocumentModelDraft.typeDefModel(id).locale("en_US")
        .typeDef("SharedCode") { t -> t.string { s -> s.maxLength(8) } }.build().toJson()) as ObjectNode

    private fun definition(model: ObjectNode): ObjectNode = model.at("/content/typeDefinitions/0") as ObjectNode

    private companion object {
        const val ADMITTED = "admitted"
        const val UNBUILT = "CK_MODEL_NOT_BUILDABLE"
        val CONSUMER: String = DocumentModelDraft.named("permit").locale("en_US")
            .importTypeDefs("short-types").importTypeDefs("long-types")
            .rootGroup("Permit") { p -> p.typedefField("Code", "SharedCode") }
            .build().toJson()
    }
}
