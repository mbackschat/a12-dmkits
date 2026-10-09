package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Locale

/**
 * SPEC-2026-10-04-03: a full-validation rule does not iterate the document's physical rows. When the product of
 * the declared capacities along its iteration chain, the root's included, is at most 5, it visits every declared
 * slot, stored or not; above that it visits each level's first coordinate and every later one whose prefix an
 * instantiated row of ANY group sets at that depth, so a sibling root's rows open coordinates here. Neither mode
 * visits a row past its declared capacity.
 *
 * Four layouts of one order model: `Lines` at capacity 2 (dense), at capacity 6 with a sibling `Archive` root
 * whose `Lines` share the depth (occupied prefixes), and `Lines` × `Parts` at 2 × 2 (dense nested) and at 3 × 3
 * (occupied prefixes, nested). The rule fires
 * on an empty note (`Note + "x" == "x"`), so every visited slot that holds no note is observable.
 *
 * Own domain (e-commerce order lines).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidationIterationDomainDiffTest {

    private fun model(name: String, lineCapacity: Int, nestedParts: Boolean, partCapacity: Int = 2): String {
        val draft = DocumentModelDraft.named(name).locale("en_US")
            .rootGroup("Order") { o ->
                o.stringField("Ref") { }
                o.group("Lines") { l ->
                    l.repeatable(lineCapacity)
                    if (nestedParts) l.group("Parts") { p -> p.repeatable(partCapacity); p.stringField("Note") { } }
                    else l.stringField("Note") { }
                }
            }
            .rootGroup("Archive") { a ->
                a.stringField("Ref") { }
                a.group("Lines") { l -> l.repeatable(lineCapacity); l.stringField("Kept") { } }
            }.build().toJson()
        val session = EditSession.read(draft)
        val scope = session.group(if (nestedParts) "/Order/Lines/Parts" else "/Order/Lines")
        val note = scope.str("Note")
        return TypedRules.inject(session.toJson(), scope.rule {
            it.name("NoteMissing").code("NoteMissing").errorField(note.field())
                .`when`(note.asString().plus(Dsl.text("x")).eq(Dsl.text("x"))).message(Dsl.en("Add a note.")).build()
        })
    }

    private val dense = model("order-lines-dense", 2, nestedParts = false)
    private val sparse = model("order-lines-sparse", 6, nestedParts = false)
    private val nested = model("order-lines-nested", 2, nestedParts = true)
    private val nestedSparse = model("order-lines-nested-sparse", 3, nestedParts = true, partCapacity = 3)

    private val documents = linkedMapOf(
        "dense root-only" to (dense to """{"Order":{"Ref":"A1"}}"""),
        "dense over-capacity" to (dense to """{"Order":{"Ref":"A1","Lines":[{"Note":"n"},{"Note":"n"},{}]}}"""),
        "sparse root-only" to (sparse to """{"Order":{"Ref":"A1"}}"""),
        "sparse two blank rows" to (sparse to """{"Order":{"Ref":"A1","Lines":[{},{}]}}"""),
        "sparse over-capacity" to
            (sparse to """{"Order":{"Ref":"A1","Lines":[{"Note":"n"},{"Note":"n"},{"Note":"n"},{"Note":"n"},{"Note":"n"},{"Note":"n"},{}]}}"""),
        "sparse sibling rows" to (sparse to """{"Order":{"Ref":"A1"},"Archive":{"Ref":"B1","Lines":[{},{},{"Kept":"k"}]}}"""),
        "nested root-only" to (nested to """{"Order":{"Ref":"A1"}}"""),
        "nested one part" to (nested to """{"Order":{"Ref":"A1","Lines":[{"Parts":[{"Note":"n"}]}]}}"""),
        "nested sparse, one part then three" to (nestedSparse to
            """{"Order":{"Ref":"A1","Lines":[{"Parts":[{"Note":"n"}]},{"Parts":[{"Note":"n"},{"Note":"n"},{"Note":"n"}]}]}}"""),
    )

    private fun kernelSignature(m: IMessage): String =
        "${m.errorCode}|${m.messageType.name}|" +
            formatPointer(m.errorFieldPointer.fullName(), m.errorFieldPointer.repetitionIndexes())

    private fun kernel(dynamic: Boolean, model: String, json: String): List<String> {
        val messages = if (dynamic) RuntimeLaws.kernelEngine(model).let { it.validationMessages(it.instance(json), null, Locale.US) }
            else RuntimeLaws.kernelJavaEngine(model).let { it.validationMessages(it.instance(json), null, Locale.US) }
        return messages.filter { it.errorCode == "NoteMissing" }.map(::kernelSignature).sorted()
    }

    private fun interpreter(model: String, json: String): List<String> {
        val def = ModelLoader.load(model)
        return DmInterpreter(def).validate(def.readDocumentJson(json)).messages.filter { it.code == "NoteMissing" }.map {
            val type = if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"
            "${it.code}|$type|" + formatPointer(it.errorPointer.fullName, it.errorPointer.repetitionIndexes)
        }.sorted()
    }

    @Test
    fun theModelsAreKernelConsistent() {
        for (model in listOf(dense, sparse, nested, nestedSparse)) assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        for ((name, pair) in documents) {
            val (model, json) = pair
            val dynamic = kernel(dynamic = true, model, json)
            assertEquals(dynamic, kernel(dynamic = false, model, json), "$name: static Java vs dynamic Groovy")
            assertEquals(dynamic, interpreter(model, json), "$name: interpreter vs dynamic Groovy")
        }
    }

    /**
     * The visited coordinates, pinned against the normative kernel so the agreement above cannot pass vacuously.
     * Each row rules out a rival domain: physical rows only (root-only and sibling documents fire nowhere), dense
     * above the threshold (the sparse root-only document would fire six times), a per-group occupancy (sibling rows
     * would open nothing), a domain past the declared capacity (the over-capacity row would fire), and a level-wide
     * bound, the one a `Having` filter's walk takes (the line holding one part would fire for parts 2 and 3).
     */
    @Test
    fun theVisitedCoordinatesAreTheKernelsOwn() {
        fun rows(doc: String) = documents.getValue(doc).let { (model, json) -> kernel(dynamic = true, model, json) }
            .map { it.substringAfterLast('|').removePrefix("/Order[1]/") }
        assertEquals(linkedMapOf(
            "dense root-only" to listOf("Lines[1]/Note", "Lines[2]/Note"),
            "dense over-capacity" to emptyList(),
            "sparse root-only" to listOf("Lines[1]/Note"),
            "sparse two blank rows" to listOf("Lines[1]/Note", "Lines[2]/Note"),
            "sparse over-capacity" to emptyList(),
            "sparse sibling rows" to listOf("Lines[1]/Note", "Lines[2]/Note", "Lines[3]/Note"),
            "nested root-only" to
                listOf("Lines[1]/Parts[1]/Note", "Lines[1]/Parts[2]/Note", "Lines[2]/Parts[1]/Note", "Lines[2]/Parts[2]/Note"),
            "nested one part" to listOf("Lines[1]/Parts[2]/Note", "Lines[2]/Parts[1]/Note", "Lines[2]/Parts[2]/Note"),
            "nested sparse, one part then three" to emptyList(),
        ), documents.keys.associateWith(::rows))
    }
}
