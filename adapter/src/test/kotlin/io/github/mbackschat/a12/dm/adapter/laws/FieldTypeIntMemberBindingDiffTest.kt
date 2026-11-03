package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.CK_MODEL_NOT_BUILDABLE
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationResult
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How A12's reader binds a field type's integer members, and that the checker and the interpreter bind them alike
 * (KERNEL-FINDINGS KF593, corrected 2026-11-02). Kernel 31.1.1 reads through Jackson 3, which refuses a null for a
 * primitive, so a value that binds no value as an `Integer` leaves no model when the member is a primitive `int`, and
 * is plain absence when it is boxed.
 *
 * Each row varies one member of one model and nothing else. `NumberType.minFractionalDigits` and `maxFractionalDigits`
 * are the primitive members; `NumberType.maxIntegerDigits`, `minLength` and `maxLength` and the `minLength` and
 * `maxLength` of `StringType` and `CustomFieldType` are boxed, which with `Group.repeatability` and
 * `modelInfo.baseYear` is every integer member of the kernel's model classes. The boxed rows are the discriminator against the rival reading, "kernel 31 refuses null for every integer
 * member", which would refuse them too. Every member also carries a value no integer reading binds, the basis for a
 * refusal of it, and a fractional number, which truncates and binds.
 *
 * Own domain (an insurance policy's premium and reference).
 */
class FieldTypeIntMemberBindingDiffTest {

    @Test
    fun aMemberWithNoValueLeavesNoModelOnlyWhereTheMemberIsPrimitive() =
        assertAgreement(listOf(Carrier("field", ::onTheField)), MEMBERS)

    /**
     * The number members in a `content.typeDefinitions` entry, which A12's reader reads whether or not a field names
     * it, referenced or not.
     */
    @Test
    fun aTypeDefinitionsMemberBindsAlike() = assertAgreement(listOf(
        Carrier("unreferenced definition") { member, value -> inATypeDefinition(member, value, referenced = false) },
        Carrier("referenced definition") { member, value -> inATypeDefinition(member, value, referenced = true) },
    ), MEMBERS.filter { it.type == "NumberType" })

    /**
     * An unreferenced definition's BOOLEAN member is read by the same reader (KERNEL-FINDINGS KF569), so the
     * interpreter's admission of a definition no field names judges it too; one spelling binds and one does not.
     */
    @Test
    fun anUnreferencedDefinitionsBooleanMemberBindsAlike() {
        val positivesOnly = Member("Premium", "NumberType", "positivesOnly", primitive = false)
        val observed = listOf("true", "\"yes\"").associateWith { value ->
            val json = inATypeDefinition(positivesOnly, value, referenced = false)
            val ours = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            val source = WorkspaceModelSource.builder().json(json).build()
            val interpreted = ModelWorkspace.fromSources(listOf(source)).tryPrepare(MODEL_ID) is ModelPreparationResult.Success
            "A12 ${verdict(kernelModelClasses(json))}, checker ${verdict(ours)}, interpreter ${if (interpreted) "prepares" else "refuses"}"
        }
        assertEquals(mapOf(
            "true" to "A12 built, checker built, interpreter prepares",
            "\"yes\"" to "A12 not built, checker not built, interpreter refuses",
        ), observed)
    }

    /**
     * Every built model holds the bound A12 binds, so the interpreter accepts and refuses the same values. A value the
     * reader takes only as A12 binds it — the text `"2"` or the number `2.7` — is what tells a member read through the
     * binding from one read as a plain integer literal, which held both as absent.
     */
    @Test
    fun everyBuiltModelHoldsTheBoundA12Binds() {
        for (member in MEMBERS) for ((value, kind) in VALUES) {
            if (!built(member, kind)) continue
            DocumentDifferential.assertAgree(onTheField(member, value), member.documents.mapKeys { (label, _) -> "$member=$value, $label" })
        }
    }

    private fun assertAgreement(carriers: List<Carrier>, members: List<Member>) {
        val expected = linkedMapOf<String, String>()
        val observed = linkedMapOf<String, String>()
        for (carrier in carriers) for (member in members) for ((value, kind) in VALUES) {
            val row = "${carrier.name} $member=$value"
            val built = built(member, kind)
            expected[row] = "A12 ${verdict(built)}, checker ${verdict(built)}, interpreter ${if (built) "prepares" else "refuses"}"
            val json = carrier.model(member, value)
            val ours = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            val source = WorkspaceModelSource.builder().json(json).build()
            val interpreted = ModelWorkspace.fromSources(listOf(source)).tryPrepare(MODEL_ID) is ModelPreparationResult.Success
            observed[row] = "A12 ${verdict(kernelModelClasses(json))}, checker ${verdict(ours)}, " +
                "interpreter ${if (interpreted) "prepares" else "refuses"}"
        }
        assertEquals(expected, observed)
    }

    private fun built(member: Member, kind: Kind) = when (kind) {
        Kind.BOUND -> true
        Kind.NO_VALUE -> !member.primitive
        Kind.UNBINDABLE -> false
    }

    private fun verdict(built: Boolean) = if (built) "built" else "not built"

    private fun verdict(classes: Set<String>) = when (classes) {
        emptySet<String>() -> "built"
        setOf(CK_MODEL_NOT_BUILDABLE) -> "not built"
        else -> classes.toString()
    }

    private fun onTheField(member: Member, value: String): String {
        val root = MAPPER.readTree(BASE) as ObjectNode
        val fieldType = element(root, member.owner).path("Field").path("fieldType") as ObjectNode
        val body = fieldType.get(member.type) as? ObjectNode ?: fieldType.putObject(member.type)
        body.set<ObjectNode>(member.name, MAPPER.readTree(value))
        return root.toString()
    }

    private fun inATypeDefinition(member: Member, value: String, referenced: Boolean): String {
        val root = MAPPER.readTree(BASE) as ObjectNode
        val body = MAPPER.createObjectNode().put("maxFractionalDigits", 2).set<ObjectNode>(member.name, MAPPER.readTree(value))
        (root.path("content") as ObjectNode).putArray("typeDefinitions").addObject()
            .put("id", "PremiumAmount").put("name", "PremiumAmount").put("revision", "1")
            .putObject("fieldType").put("type", member.type).set<ObjectNode>(member.type, body)
        if (referenced) {
            (element(root, member.owner).path("Field") as ObjectNode).putObject("fieldType").put("type", "TypeDefType")
                .putObject("TypeDefType").put("typeDefinitionId", "PremiumAmount")
        }
        return root.toString()
    }

    private fun element(root: ObjectNode, name: String) =
        root.at("/content/modelRoot/rootGroups/0/Group/elements").first { it.path("name").asText() == name }

    private enum class Kind { NO_VALUE, UNBINDABLE, BOUND }

    /** Where the member is written. */
    private class Carrier(val name: String, val model: (Member, String) -> String)

    /** One integer member of one field's type body; [documents] each fall on one side of the bound `2`. */
    private data class Member(val owner: String, val type: String, val name: String, val primitive: Boolean) {
        val documents: Map<String, String>
            get() = when (owner) {
                "Premium" -> mapOf(
                    "two decimals" to """{"Policy":{"Premium":12.34}}""", "three decimals" to """{"Policy":{"Premium":12.345}}""")
                "Ref" -> mapOf("one letter" to """{"Policy":{"Ref":"A"}}""", "three letters" to """{"Policy":{"Ref":"ABC"}}""")
                // A custom type's lengths are its validator's inputs, and A12 runs no validator without one registered.
                else -> emptyMap()
            }

        override fun toString() = "$type.$name"
    }

    private companion object {
        val MAPPER = ObjectMapper()
        const val MODEL_ID = "policy-premium"
        /** A premium, a reference, and a policy code of a custom type, which the typed builder writes as a STRING. */
        val BASE: String = (MAPPER.readTree(DocumentModelDraft.named(MODEL_ID).locale("en_US")
            .rootGroup("Policy") { it.numberField("Premium") { n -> n.fractionDigits(2) }.stringField("Ref") { }.stringField("Code") { } }
            .build().toJson()) as ObjectNode).also { root ->
                val code = root.at("/content/modelRoot/rootGroups/0/Group/elements").first { it.path("name").asText() == "Code" }
                (code.path("Field") as ObjectNode).putObject("fieldType").put("type", "CustomFieldType")
                    .putObject("CustomFieldType").put("name", "PolicyCode")
            }.toString()
        val MEMBERS = listOf(
            Member("Premium", "NumberType", "minFractionalDigits", primitive = true),
            Member("Premium", "NumberType", "maxFractionalDigits", primitive = true),
            Member("Premium", "NumberType", "maxIntegerDigits", primitive = false),
            Member("Premium", "NumberType", "minLength", primitive = false),
            Member("Premium", "NumberType", "maxLength", primitive = false),
            Member("Ref", "StringType", "minLength", primitive = false),
            Member("Ref", "StringType", "maxLength", primitive = false),
            Member("Code", "CustomFieldType", "minLength", primitive = false),
            Member("Code", "CustomFieldType", "maxLength", primitive = false),
        )
        /** Each value as JSON text, by how an `Integer` reading binds it. */
        val VALUES = linkedMapOf(
            "null" to Kind.NO_VALUE, "\"\"" to Kind.NO_VALUE, "\" \"" to Kind.NO_VALUE, "\"null\"" to Kind.NO_VALUE,
            "\" null \"" to Kind.NO_VALUE, "\"abc\"" to Kind.UNBINDABLE, "true" to Kind.UNBINDABLE,
            "2.7" to Kind.BOUND, "\"2\"" to Kind.BOUND,
        )
    }
}
