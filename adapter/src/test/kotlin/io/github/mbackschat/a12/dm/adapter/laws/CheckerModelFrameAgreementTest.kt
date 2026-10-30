package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.Admission
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.admit
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader as InterpreterModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12 on a model's FRAME, the `header` and `content` objects and the members under them
 * (KERNEL-FINDINGS KF650): each member absent and as `null`, an object, an array, a text, a number and a boolean,
 * every row through the Kernel gate, A12's classes the expected column.
 *
 * A member A12's reader cannot bind, and one its consistency check needs, both leave no verdict, which the checker
 * reports as `CK_MODEL_NOT_BUILDABLE`. A source with no text `header.id` or no `content` object the checker does not
 * admit at all: it reports no verdict and names why ([UNADMITTED]). The values A12 judges by their text are
 * `CheckerFrameValueAgreementTest`'s rows. Own domain (an insurance policy with a reference).
 */
class CheckerModelFrameAgreementTest {

    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("policy-frame").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { } }
        .build().toJson()

    @Test
    fun everyFrameMemberIsReadAsA12ReadsIt() {
        val rows = linkedMapOf<String, String>()
        for (member in MEMBERS) for ((shape, value) in SHAPES) rows["$member $shape"] = edited(member, value?.let(mapper::readTree))
        for (code in CONDITION_LANGUAGES) rows["conditionLanguage $code"] = edited("/content/modelConfig/conditionLanguage", mapper.readTree(code))
        for (entries in CHARACTER_ENTRIES) rows["supportedCharacters $entries"] = edited("/content/modelConfig/supportedCharacters", mapper.readTree(entries))
        for (root in ROOTS) rows["root $root"] = root
        val observed = rows.mapValues { (_, json) -> a12(json) to ours(json) }
        assertEquals(observed.mapValues { (row, v) ->
            v.first to when (row) {
                in UNADMITTED, in UNIDENTIFIED -> setOf(NOT_ADMITTED)
                else -> v.first
            }
        }, observed, "as (A12, ours)")
        assertEquals(emptyList<String>(), UNADMITTED.filter { observed[it]?.first.isNullOrEmpty() }, "every row set aside is one A12 refuses")
    }

    @Test
    fun aBareConditionLanguageDeclaresItAsItsCodeDoes() {
        val german = withRule(languageDeclared("\"de_DE\""), "FeldAngegeben(Ref)")
        val english = withRule(languageDeclared("\"de_DE\""), "FieldFilled(Ref)")
        assertEquals(
            listOf(true to true, false to false),
            listOf(german, english).map { a12(it).isEmpty() to rejectingCodes(it).isEmpty() },
            "a German condition builds under a bare de_DE and an English one does not, as (A12, ours)",
        )
        // The interpreter parses the German condition, which it reads as English when it misses the bare form.
        InterpreterModelLoader.load(german)
    }

    /**
     * The version and the model info are each needed for the check, and their absence withholds every class, not
     * just the clean verdict: over the sixteen presence combinations of four header and content members, a sound rule
     * and one naming a field the model lacks are judged only when both are present. The two members never asked for
     * are the control that the rows are not merely counting removals.
     */
    @Test
    fun aMissingVersionOrModelInfoWithholdsEveryClass() {
        val members = listOf("/header/modelVersion", "/content/modelInfo", "/content/modelConfig", "/header/modelType")
        val observed = linkedMapOf<String, Pair<Set<String>, Set<String>>>()
        val predicted = linkedMapOf<String, Pair<Set<String>, Set<String>>>()
        for ((rule, judged) in listOf("FieldFilled(Ref)" to emptySet(), "FieldFilled(Nope)" to setOf("MVK_INVALID_ENTITY"))) {
            for (kept in 0 until (1 shl members.size)) {
                val absent = members.filterIndexed { i, _ -> kept and (1 shl i) == 0 }
                val json = absent.fold(withRule(base, rule)) { model, pointer -> edited(model, pointer, null) }
                val a12 = if (absent.none { it in members.take(2) }) judged else setOf("CK_MODEL_NOT_BUILDABLE")
                observed["$rule without $absent"] = a12(json) to ours(json)
                predicted["$rule without $absent"] = a12 to a12
            }
        }
        assertEquals(predicted, observed, "as (A12, ours)")
    }

    private fun edited(pointer: String, value: JsonNode?): String = edited(base, pointer, value)

    private fun edited(model: String, pointer: String, value: JsonNode?): String {
        val root = mapper.readTree(model) as ObjectNode
        val parent = root.at(pointer.substringBeforeLast('/')) as ObjectNode
        val name = pointer.substringAfterLast('/')
        if (value == null) parent.remove(name) else parent.set<JsonNode>(name, value)
        return root.toString()
    }

    private fun languageDeclared(language: String) = edited("/content/modelConfig/conditionLanguage", mapper.readTree(language))

    private fun withRule(model: String, condition: String) = RuntimeLaws.withRule(model, "/Policy", "RRef", "../Ref", "REF", condition)

    /** The checker's rejecting codes, or [NOT_ADMITTED] when it reads no DocumentModel in the source at all. */
    private fun ours(json: String): Set<String> =
        if (admit(ModelSource(json)) is Admission.Rejected) setOf(NOT_ADMITTED) else rejectingCodes(json).toSet()

    /** A12's classes, a Kernel exception escaping its typed channels read as the model it never built. */
    private fun a12(json: String): Set<String> = try {
        kernelModelClasses(json)
    } catch (escaped: RuntimeException) {
        setOf("CK_MODEL_NOT_BUILDABLE")
    }

    private companion object {
        val MEMBERS = listOf(
            "/header", "/header/id", "/header/modelType", "/header/modelVersion", "/header/description", "/header/locales",
            "/header/labels", "/header/annotations", "/header/modelReferences",
            "/content", "/content/modelInfo", "/content/modelInfo/name", "/content/modelInfo/variant", "/content/modelInfo/branch",
            "/content/modelInfo/revision", "/content/modelInfo/comment", "/content/modelInfo/joinedModelsInfo",
            "/content/modelConfig", "/content/modelConfig/supportedCharacters", "/content/modelConfig/decimalSeparator",
            "/content/modelConfig/timeZone", "/content/modelConfig/conditionLanguage", "/content/typeDefinitions",
            "/content/modelRoot", "/content/modelRoot/rootGroups",
        )

        val SHAPES = linkedMapOf("absent" to null, "null" to "null", "{}" to "{}", "[]" to "[]", "\"x\"" to "\"x\"", "5" to "5", "true" to "true")

        val CONDITION_LANGUAGES = listOf(
            "\"en_US\"", "\"de_DE\"", "{\"code\":\"de_DE\"}", "{\"code\":\"de-DE\"}", "{\"code\":\"en-US\"}", "{\"code\":\"zz\"}", "{\"code\":\"en_us\"}", "{\"code\":5}",
            "{\"code\":\"\"}", "{\"code\":null}", "{\"code\":{}}", "{\"x\":\"en_US\"}",
        )

        val CHARACTER_ENTRIES = listOf("[\"a\"]", "[\"a-z\"]", "[5]", "[{}]", "[[]]", "[null]", "[\"ab\"]", "[\"\"]", "[true]")

        val ROOTS = listOf("[]", "5", "\"x\"", "null", "{}")

        const val NOT_ADMITTED = "not admitted"

        /**
         * A model with no `header.id` text A12 builds, and the checker cannot identify, so it reports no verdict and
         * says why (`DiagnosticCode.SOURCE_HAS_NO_IDENTITY`): a workspace names each model by that id.
         */
        val UNIDENTIFIED = setOf("/header/id absent", "/header/id null")

        /** No text `header.id` or no `content` object: A12 builds none of these, and the checker admits none. */
        val UNADMITTED = SHAPES.keys.filter { it != "{}" }.flatMap { listOf("/header $it", "/content $it") }.toSet() +
            setOf("/header {}", "/header/id {}", "/header/id []") + ROOTS.map { "root $it" }
    }
}
