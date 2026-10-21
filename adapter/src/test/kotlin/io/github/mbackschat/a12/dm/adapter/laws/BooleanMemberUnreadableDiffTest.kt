package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * A model `boolean` member A12 cannot bind refuses the model on both sides (KF577).
 *
 * KF569 measured which spellings A12's POJO binding takes on `fieldRefByShortNameAllowed`; this grid asks the same of
 * `Field.global` and of every field-type `boolean` the interpreter reads, each in its own model, with `true` as the
 * control that the carrier loads at all. A12's verdict is its own serializer reading the model or not.
 */
class BooleanMemberUnreadableDiffTest {

    private data class Member(val type: String?, val name: String, val draft: (DocumentModelDraft.GroupBuilder) -> Unit)

    private val members = listOf(
        Member(null, "global") { g -> g.numberField("Probe") {} },
        Member("StringType", "lineBreaksPermitted") { g -> g.stringField("Probe") {} },
        Member("StringType", "noValueValidation") { g -> g.stringField("Probe") {} },
        Member("NumberType", "zeroNotAllowed") { g -> g.numberField("Probe") {} },
        Member("NumberType", "positivesOnly") { g -> g.numberField("Probe") {} },
        Member("NumberType", "leadingZerosAllowed") { g -> g.numberField("Probe") {} },
        Member("DateType", "youngerThan1900Check") { g -> g.dateField("Probe") {} },
        Member("DateFragmentType", "youngerThan1900Check") { g -> g.dateFragmentField("Probe") { f -> f.fragmentFormat("yyyy-MM") } },
        Member("DateRangeType", "youngerThan1900Check") { g -> g.dateRangeField("Probe") { r -> r.format("yyyy-MM-dd").separator("/") } },
    )

    private val values = linkedMapOf<String, Any>("true — the control" to true, "\"yes\"" to "yes", "1.5" to 1.5, "{}" to emptyMap<String, Any>())

    @Test
    fun aMemberA12CannotBindRefusesTheModelOnBothSides() {
        val kernel = linkedMapOf<String, String>()
        val interpreter = linkedMapOf<String, String>()
        for (member in members) for ((label, value) in values) {
            val row = "${member.type ?: "Field"}.${member.name} = $label"
            val json = model(member, value)
            kernel[row] = if (RuntimeLaws.deserializes(json)) LOADS else REFUSED
            interpreter[row] = if (runCatching { ModelLoader.load(json) }.isSuccess) LOADS else REFUSED
        }
        assertEquals(kernel.mapValues { (row, _) -> if (row.endsWith("the control")) LOADS else REFUSED }, kernel, "A12")
        assertEquals(kernel, interpreter, "the interpreter")
    }

    /**
     * A type object the field's `type` does not name is not bound at all: A12 loads and admits the model whatever that
     * object's booleans hold, so a stray `NumberType.positivesOnly` does not refuse a String field (KF577). The
     * interpreter reads a type member only under the matching `type`, and the portable checker admits the same models.
     */
    @Test
    fun aTypeObjectTheFieldDoesNotDeclareIsNeverBound() {
        val stray = listOf(
            Member("NumberType", "positivesOnly") { g -> g.stringField("Probe") {} },
            Member("NumberType", "zeroNotAllowed") { g -> g.stringField("Probe") {} },
            Member("StringType", "noValueValidation") { g -> g.numberField("Probe") {} },
        )
        val observed = linkedMapOf<String, String>()
        for (member in stray) for ((label, value) in values) {
            val json = model(member, value)
            val kernel = if (RuntimeLaws.deserializes(json) && RuntimeLaws.consistencyMessages(json).isEmpty()) LOADS else REFUSED
            val interpreter = if (runCatching { ModelLoader.load(json) }.isSuccess) LOADS else REFUSED
            val checker = if (check(ModelSource(json)).findings.none { it.justifiesRejection }) LOADS else REFUSED
            observed["${member.type}.${member.name} = $label"] = "A12 $kernel, interpreter $interpreter, checker $checker"
        }
        assertEquals(observed.mapValues { "A12 $LOADS, interpreter $LOADS, checker $LOADS" }, observed)
    }

    private fun model(member: Member, value: Any): String {
        val root = MAPPER.readTree(DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy", Consumer { g -> member.draft(g) }).build().toJson()) as ObjectNode
        val field = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements")
            .first { it.path("name").asText() == "Probe" }.path("Field") as ObjectNode
        val owner = member.type?.let { type ->
            val fieldType = field.path("fieldType") as ObjectNode
            (fieldType.get(type) as? ObjectNode) ?: fieldType.putObject(type)
        } ?: field
        owner.set<ObjectNode>(member.name, MAPPER.valueToTree(value))
        return root.toString()
    }

    private companion object {
        val MAPPER = ObjectMapper()
        const val LOADS = "loads"
        const val REFUSED = "refused"
    }
}
