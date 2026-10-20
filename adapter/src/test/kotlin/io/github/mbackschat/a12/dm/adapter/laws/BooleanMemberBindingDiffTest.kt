package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential (groovy-dynamic == java-static == interpreter) for the field types' primitive `boolean` members
 * written in the spellings A12's reader binds but a JSON-boolean reader does not: the integer `1` and the
 * padded `" TRUE"` set a member, `0` and the text `"null"` clear it ([KERNEL-FINDINGS KF569]; the binding is
 * `:predicates`' `a12BooleanOfString` / `a12BooleanOfNumber`). Each member sits alone on a claim model with a
 * document value it decides — a line break, a zero, a negative, leading zeros, a pre-1900 date, and the
 * eliminated `Length` rule a `noValueValidation` string carries — so a reader that bound the spelling as the
 * member's default would report a different message set.
 *
 * `Field.global` is the same primitive binding one level up, and only partial validation shows it (IG11,
 * [PartialValidationGlobalFieldDiffTest]): a global error field is relevant although the caller named another.
 *
 * The positive control is per member: the kernel's answer for the set spellings must differ from its answer
 * for the cleared ones, or the row would hold whatever the interpreter read.
 */
class BooleanMemberBindingDiffTest {

    private companion object {
        val SET = listOf<Any>(1, " TRUE")
        val CLEARED = listOf<Any>(0, "null")
        val MAPPER = ObjectMapper()
    }

    /** One member of one field type, the value that member decides, and a rule when only a rule shows it. */
    private data class Member(
        val name: String,
        val type: String,
        val field: String,
        val value: String,
        val draft: (DocumentModelDraft.GroupBuilder) -> Unit,
        val rule: String? = null,
    )

    private val members = listOf(
        Member("lineBreaksPermitted", "StringType", "Note", "first\nsecond", { g -> g.stringField("Note") {} }),
        Member("zeroNotAllowed", "NumberType", "Instalments", "0", { g -> g.numberField("Instalments") {} }),
        Member("positivesOnly", "NumberType", "Adjustment", "-5", { g -> g.numberField("Adjustment") {} }),
        Member("leadingZerosAllowed", "NumberType", "Branch", "007", { g -> g.numberField("Branch") {} }),
        Member("youngerThan1900Check", "DateType", "Inception", "1850-01-01", { g -> g.dateField("Inception") {} }),
        Member(
            "noValueValidation", "StringType", "Scan", "abcdef",
            { g -> g.stringField("Scan") { s -> s.lineBreaks() } },
            rule = "Length(Scan) > 3",
        ),
    )

    private fun model(member: Member, spelling: Any): String {
        val base = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", Consumer { g -> member.draft(g) }).build().toJson()
        val ruled = member.rule?.let { RuntimeLaws.withRule(base, "/Claim", "R_LEN", "../${member.field}", "LEN", it) }
            ?: base
        val root = MAPPER.readTree(ruled) as ObjectNode
        val element = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group").path("elements")
            .first { it.path("name").asText() == member.field }
        val fieldType = element.path("Field").path("fieldType") as ObjectNode
        val body = (fieldType.get(member.type) as? ObjectNode) ?: fieldType.putObject(member.type)
        body.set<ObjectNode>(member.name, MAPPER.valueToTree(spelling))
        return root.toString()
    }

    /** The message codes per engine — (groovy, java-static, interpreter) — for [member] written as [spelling]. */
    private fun codes(member: Member, spelling: Any): Triple<List<String>, List<String>, List<String>> {
        val modelJson = model(member, spelling)
        val path = "/Claim/${member.field}"
        val kDoc = RuntimeLaws.doc().group("/Claim", 1).field(path, member.value, 1, 1)
        val groovy = RuntimeLaws.messages(modelJson, kDoc).map { it.code() }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(modelJson)
        val java = engine.messages(engine.instance(kDoc)).map { it.code() }.sorted()
        val def = ModelLoader.load(modelJson)
        val iDoc = def.newDocument().group("/Claim", 1).field(path, member.value, 1, 1)
        val interpreter = DmInterpreter(def).validateFull(iDoc).map { it.code }.sorted()
        return Triple(groovy, java, interpreter)
    }

    @Test
    fun everyBooleanMemberBindsItsSpellingAsTheKernelDoes() {
        val disagreements = mutableListOf<String>()
        for (member in members) {
            val set = SET.map { spelling -> spelling to codes(member, spelling) }
            val cleared = CLEARED.map { spelling -> spelling to codes(member, spelling) }
            // The control: the member must decide this document, in the kernel, or the row proves nothing.
            assertEquals(1, set.map { it.second.first }.toSet().size, "${member.name}: the set spellings agree")
            assertEquals(1, cleared.map { it.second.first }.toSet().size, "${member.name}: the cleared spellings agree")
            check(set[0].second.first != cleared[0].second.first) {
                "${member.name} does not decide its document in the kernel: ${set[0].second.first}"
            }
            for ((spelling, observed) in set + cleared) {
                val (groovy, java, interpreter) = observed
                if (java != groovy || interpreter != groovy) {
                    disagreements += "${member.name}=${MAPPER.writeValueAsString(spelling)}: " +
                        "groovy=$groovy java=$java interpreter=$interpreter"
                }
            }
        }
        assertEquals(emptyList<String>(), disagreements)
    }

    @Test
    fun theGlobalFlagBindsItsSpellingAsTheKernelDoes() {
        val premium = "/Policy/Premium"
        val risk = "/Policy/RiskScore"
        val observed = (SET + CLEARED).map { spelling ->
            val base = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", Consumer { g -> g.numberField("Premium") {}.numberField("RiskScore") {} })
                .build().toJson()
            val m = BoundModel.ofJson(base)
            val cond = RuntimeLaws.render(Dsl.fieldFilled(m.field(risk)).and(m.num(risk).lt(0)))
            val root = MAPPER.readTree(RuntimeLaws.withRule(base, "/Policy", "RISK", "../RiskScore", "RISK", cond))
                as ObjectNode
            val element = root.path("content").path("modelRoot").path("rootGroups")[0].path("Group")
                .path("elements").first { it.path("name").asText() == "RiskScore" }
            (element.path("Field") as ObjectNode).set<ObjectNode>("global", MAPPER.valueToTree(spelling))
            val modelJson = root.toString()
            val kDoc = RuntimeLaws.doc().group("/Policy", 1).field(premium, "100", 1, 1).field(risk, "-5", 1, 1)
            val kernel = RuntimeLaws.firedCodesPart(modelJson, kDoc, setOf(Relevant.of(premium, 0, 0)))
                .contains("RISK")
            val def = ModelLoader.load(modelJson)
            val iDoc = def.newDocument().group("/Policy", 1).field(premium, "100", 1, 1).field(risk, "-5", 1, 1)
            val interpreter = DmInterpreter(def).validatePart(iDoc, setOf(RelevantEntity.anyRep(premium)))
                .messages.any { it.code == "RISK" }
            MAPPER.writeValueAsString(spelling) to (kernel to interpreter)
        }
        // The control: a set spelling makes the error field relevant in the kernel, a cleared one does not.
        assertEquals(listOf(true, true, false, false), observed.map { it.second.first }, "kernel: $observed")
        assertEquals(observed.map { it.first to it.second.first }, observed.map { it.first to it.second.second })
    }
}
