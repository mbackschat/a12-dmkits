package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ModelLoader
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader as InterpreterModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where an EMPTY declaration member answers as an absent one, on kernel 31.1.1 and on the portable checker
 * (KERNEL-FINDINGS KF658).
 *
 * Each row sets one member to its empty value on one model and pairs it with the same model without the member.
 * A12's two columns are the measurement, and the checker must answer both as A12 does. Most members answer alike;
 * [DIFFERING] pins the ones that do not, because the expand copy that makes an empty member absent does not reach
 * every member: `BasicConsistencyCheck` judges the model as authored, the header is not part of the copy, an object
 * written `{}` is not empty to the serializer, and a computation's precondition and a hint entry's members keep
 * their own verdicts. Which serializer setting decides each of those was not read.
 *
 * Own domain (an insurance policy with its claims).
 */
class CheckerEmptyMemberAgreementTest {

    private data class Row(val label: String, val at: String, val member: String, val empty: JsonNode)

    @Test
    fun theCheckerAnswersAnEmptyOrAbsentMemberAsA12Does() {
        assertEquals(setOf<String>() to listOf<String>(), kernelModelClasses(BASE) to rejectingCodes(BASE),
            "the carrier, untouched, is admitted on both sides")
        val models = ROWS.associate { row ->
            row.label to (edit(row) { it.set<JsonNode>(row.member, row.empty) } to edit(row) { it.remove(row.member) })
        }
        val a12 = models.mapValues { (_, pair) -> a12(pair.first) to a12(pair.second) }
        val ours = models.mapValues { (_, pair) -> classes(pair.first) to classes(pair.second) }

        assertEquals(DIFFERING, a12.filterValues { (empty, absent) -> empty != absent }.keys,
            "the members whose empty and absent forms A12 answers differently")
        assertEquals(a12.filterKeys { it !in UNDER_REPORTED }, ours.filterKeys { it !in UNDER_REPORTED },
            "(row → empty, absent): A12 against the checker")
    }

    /**
     * The two rows the checker answers less than A12, each by design. A mount with an empty `includeConfig` fails
     * A12's expansion ("The corresponding model reference of the including group … is not specified."), and the
     * checker records the unexpanded mount as a coverage gap, because its mount path carries no findings. A model
     * whose header has no `id` is declined as inconclusive, because the report is keyed by that id.
     */
    @Test
    fun anUnexpandedMountAndAnUnkeyedHeaderAreLeftToA12() {
        val mount = ROWS.single { it.label == INCLUDE_CONFIG }
        val empty = edit(mount) { it.set<JsonNode>(mount.member, mount.empty) }
        assertTrue(a12(empty).single().contains("The corresponding model reference of the including group '/Policy/Claims'"),
            "A12 refuses the mount: ${a12(empty)}")
        val report = check(ModelSource(empty))
        assertEquals(listOf<String>() to true, report.findings.filter { it.justifiesRejection }.map { it.code } to
            report.gaps.any { it.at == "/Policy/Claims" }, "the checker rejects nothing and records the mount")

        val header = ROWS.single { it.label == HEADER_ID }
        assertEquals(setOf("CK_MODEL_NOT_BUILDABLE") to setOf<String>(),
            kernelModelClasses(edit(header) { it.remove(header.member) }) to classes(edit(header) { it.remove(header.member) }))
    }

    /**
     * A category's `name`, on kernel 31.1.1: absent, empty and blank (Java's `isBlank`, the em space included) draw
     * one `MVK_NAME_MISSING` per field and leave that category unpaired, while the field's named categories are
     * still judged. Two categories without a name collide as one `null` key and the model is not built; so does a
     * blank name written twice, and an explicit `null` name is refused by the reader.
     */
    @Test
    fun aCategoryWithoutANameIsAsA12ReadsIt() {
        val categories = linkedMapOf(
            "empty" to """[{"name":"","values":["HIGH","LOW"]}]""",
            "absent" to """[{"values":["HIGH","LOW"]}]""",
            "blank" to """[{"name":" ","values":["HIGH","LOW"]}]""",
            "em space" to """[{"name":"\u2003","values":["HIGH","LOW"]}]""",
            "two distinct blanks" to """[{"name":" ","values":["HIGH","LOW"]},{"name":"\u2003","values":["A","B"]}]""",
            "blank, one token short" to """[{"name":" ","values":["HIGH"]}]""",
            "named, one token short — the control" to """[{"name":"Band","values":["HIGH"]}]""",
            "nameless beside a named one a token short" to """[{"values":["HIGH","LOW"]},{"name":"Band","values":["HIGH"]}]""",
            "absent and empty" to """[{"values":["HIGH","LOW"]},{"name":"","values":["A","B"]}]""",
            "one blank twice" to """[{"name":" ","values":["HIGH","LOW"]},{"name":" ","values":["A","B"]}]""",
            "null" to """[{"name":null,"values":["HIGH","LOW"]}]""",
            "no token list" to """[{"name":"Band"}]""",
        )
        val models = categories.mapValues { (_, declared) ->
            val tree = MAPPER.readTree(BASE)
            (tree.at("$E/4/Field/fieldType/EnumerationType") as ObjectNode).set<JsonNode>("categories", MAPPER.readTree(declared))
            MAPPER.writeValueAsString(tree)
        }
        val missing = setOf("MVK_NAME_MISSING")
        val value = setOf("MVK_MISSING_CATEGORY_VALUE")
        val unbuilt = setOf(UNBUILT)
        val expected = linkedMapOf(
            "empty" to missing, "absent" to missing, "blank" to missing, "em space" to missing,
            "two distinct blanks" to missing, "blank, one token short" to missing,
            "named, one token short — the control" to value, "nameless beside a named one a token short" to missing + value,
            "absent and empty" to unbuilt, "one blank twice" to unbuilt, "null" to unbuilt, "no token list" to value,
        )
        assertEquals(expected, models.mapValues { (_, json) -> kernelModelClasses(json) }, "A12's classes")
        // Where A12 builds no model it reports nothing else, so the checker's other classes there are not compared
        // (the convention of `CheckerBasicSentenceAgreementTest`).
        assertEquals(expected, models.mapValues { (_, json) -> classes(json).let { if (UNBUILT in it) setOf(UNBUILT) else it } },
            "the checker's classes")
        assertEquals(listOf("MVK_NAME_MISSING@/Policy/Tier"),
            check(ModelSource(models.getValue("two distinct blanks"))).findings.filter { it.code == "MVK_NAME_MISSING" }
                .map { "${it.code}@${it.at}" }, "one finding, at the field, as A12 states it")
    }

    /**
     * The kits' reader, `ModelIndex`, reads each empty member as it reads the absent one wherever A12 answers the two
     * alike and builds the model, so a consumer of the index sees the model A12 checked.
     */
    @Test
    fun theModelIndexReadsAnEmptyMemberAsTheAbsentOne() {
        val alike = ROWS.filter { it.label !in DIFFERING }.associate { row ->
            val empty = edit(row) { it.set<JsonNode>(row.member, row.empty) }
            val absent = edit(row) { it.remove(row.member) }
            row.label to (empty to absent)
        }.filterValues { (empty, _) -> UNBUILT !in a12(empty) }
        assertTrue(alike.size >= 20, "the rows A12 builds both ways: ${alike.keys}")
        val loaded = alike.mapValues { (_, pair) -> loads(pair.first) to loads(pair.second) }
        assertEquals(loaded.mapValues { (_, it) -> it.second }, loaded.mapValues { (_, it) -> it.first },
            "(row → whether EditSession and the interpreter load it): empty against absent")
        val read = alike.mapValues { (_, pair) -> indexed(pair.first) to indexed(pair.second) }
        val differing = read.mapValues { (_, it) ->
            val (empty, absent) = it.first.lines() to it.second.lines()
            (empty - absent.toSet()).map { "empty: $it" } + (absent - empty.toSet()).map { "absent: $it" }
        }.filterValues { it.isNotEmpty() }
        assertEquals(emptyMap<String, List<String>>(), differing, "(row → the index's readings that differ)")
    }

    /** Whether the authoring kits and the interpreter each load [json], or the exception each refuses it with. */
    private fun loads(json: String): String {
        fun outcome(load: () -> Any?) = runCatching(load).fold({ "loads" }, { it.javaClass.simpleName })
        return "edit ${outcome { EditSession.read(json) }}, interpreter ${outcome { InterpreterModelLoader.load(json) }}"
    }

    /** What the index answers about every declaration of the carrier, each accessor's failure kept as its reading. */
    private fun indexed(json: String): String {
        val index = runCatching { ModelIndex.ofSelfContained(json) }.getOrElse { return "unreadable: ${it.javaClass.simpleName}" }
        fun ask(question: () -> Any?) = runCatching(question).getOrElse { "threw ${it.javaClass.simpleName}" }
        return buildString {
            index.fieldPaths().sorted().forEach { f ->
                append("$f kind=${ask { index.kindOf(f) }} min=${ask { index.numberMin(f) }} max=${ask { index.numberMax(f) }} ")
                append("digits=${ask { index.numberMaxLength(f) }} len=${ask { index.stringMinLength(f) }}/${ask { index.stringMaxLength(f) }} ")
                append("pattern=${ask { index.stringPattern(f) }} format=${ask { index.temporalFormat(f) }} ")
                append("nvv=${ask { index.stringNoValueValidation(f) }} values=${ask { index.enumValues(f) }} ")
                appendLine("categories=${ask { index.enumCategories(f) }}")
            }
            index.groupPaths().sorted().forEach { g ->
                appendLine("$g repeating=${ask { index.isRepeating(g) }} index=${ask { index.declaresIndexField(g) }}")
            }
            appendLine("locales=${ask { index.locales() }} rules=${ask { index.rules().size }} computations=${ask { index.computations().size }}")
        }
    }

    /** A12's classes, or the expansion's own message where it refuses the model with no notification. */
    private fun a12(json: String): Set<String> = try {
        kernelModelClasses(json)
    } catch (noNotification: IllegalStateException) {
        setOf("refused: " + runCatching { ModelLoader.loadSelfContained(json) }.exceptionOrNull()?.message)
    }

    private fun classes(json: String): Set<String> = rejectingCodes(json).toSet()

    private fun edit(row: Row, change: (ObjectNode) -> Unit): String {
        val tree = MAPPER.readTree(BASE)
        change(tree.at(row.at) as ObjectNode)
        return MAPPER.writeValueAsString(tree)
    }

    private companion object {
        val MAPPER = ObjectMapper()
        val STR: JsonNode = MAPPER.readTree("\"\"")
        val LIST: JsonNode = MAPPER.readTree("[]")
        val OBJ: JsonNode = MAPPER.readTree("{}")

        const val ROOT = "/content/modelRoot/rootGroups/0/Group"
        const val E = "$ROOT/elements"
        const val CODE = "$E/1/Field/fieldType/StringType"
        const val TIER = "$E/4/Field/fieldType/EnumerationType"
        const val CLAIMS = "$E/6/Group"
        const val INCLUDE_CONFIG = "a mount's includeConfig"
        const val HEADER_ID = "the header id"
        const val UNBUILT = "CK_MODEL_NOT_BUILDABLE"

        val ROWS = listOf(
            Row("the time zone", "/content/modelConfig", "timeZone", STR),
            Row("the decimal separator", "/content/modelConfig", "decimalSeparator", STR),
            Row("the condition language code", "/content/modelConfig/conditionLanguage", "code", STR),
            Row("the model name", "/content/modelInfo", "name", STR),
            Row("the variant", "/content/modelInfo", "variant", STR),
            Row("the revision", "/content/modelInfo", "revision", STR),
            Row(HEADER_ID, "/header", "id", STR),
            Row("the model version", "/header", "modelVersion", STR),
            Row("the locales", "/header", "locales", LIST),
            Row("the header labels", "/header", "labels", LIST),
            Row("the annotations", "/header", "annotations", LIST),
            Row("the model references", "/header", "modelReferences", LIST),
            Row("a field's labels", "$E/0/Field", "labels", LIST),
            Row("a String body", "$E/0/Field/fieldType", "StringType", OBJ),
            Row("a pattern", CODE, "pattern", STR),
            Row("a pattern message", CODE, "errorMessage", LIST),
            Row("a minimum length", CODE, "minLength", STR),
            Row("the hint list", CODE, "hintList", LIST),
            Row("a hint entry's locale", "$CODE/hintList/0", "locale", STR),
            Row("a hint entry's values", "$CODE/hintList/0", "values", LIST),
            Row("a pattern beside noValueValidation", "$E/9/Field/fieldType/StringType", "pattern", STR),
            Row("a Number minimum", "$E/2/Field/fieldType/NumberType", "minValue", STR),
            Row("a Number maximum", "$E/2/Field/fieldType/NumberType", "maxValue", STR),
            Row("a Number's integer digits", "$E/2/Field/fieldType/NumberType", "maxIntegerDigits", STR),
            Row("a date format", "$E/3/Field/fieldType/DateType", "format", STR),
            Row("a date's interpretation of the year", "$E/3/Field/fieldType/DateType", "interpretationOfYear", STR),
            Row("a time format", "$E/5/Field/fieldType/TimeType", "format", STR),
            Row("an enumeration's values", TIER, "values", LIST),
            Row("an enumeration's categories", TIER, "categories", LIST),
            Row("a category's name", "$TIER/categories/0", "name", STR),
            Row("a category's tokens", "$TIER/categories/0", "values", LIST),
            Row("an index field", CLAIMS, "indexFieldName", STR),
            Row("the sort info", CLAIMS, "sortInfo", OBJ),
            Row("the sort fields", "$CLAIMS/sortInfo", "sortFields", LIST),
            Row(INCLUDE_CONFIG, CLAIMS, "includeConfig", OBJ),
            Row("a rule's condition", "$E/7/Rule", "errorCondition", STR),
            Row("a rule's severity", "$E/7/Rule", "severity", STR),
            Row("a rule's message", "$E/7/Rule", "errorMessage", LIST),
            Row("a computation's precondition", "$E/8/Computation/computationAlternatives/0", "precondition", STR),
            Row("a computation's message", "$E/8/Computation", "errorMessage", LIST),
        )

        /** Measured on kernel 31.1.1; every other row answers its empty and its absent form alike. */
        val DIFFERING = setOf(
            HEADER_ID, "the model version", "the locales", "a hint entry's locale", "a hint entry's values",
            "an index field", INCLUDE_CONFIG, "a rule's condition", "a rule's severity", "a computation's precondition",
        )

        val UNDER_REPORTED = setOf(INCLUDE_CONFIG, HEADER_ID)

        val BASE = """
            {"header":{"id":"policy-claims","modelType":"document","modelVersion":"29.4.0","locales":[{"code":"en_US"}],
              "labels":[{"locale":"en_US","text":"Policy"}],"annotations":[],"modelReferences":[]},
             "content":{"modelInfo":{"name":"policy-claims","variant":"base","revision":"draft","immutable":false},
              "modelConfig":{"decimalSeparator":".","timeZone":"Europe/Berlin","conditionLanguage":{"code":"en_US"}},
              "modelRoot":{"rootGroups":[{"type":"Group","id":"/Policy","name":"Policy","Group":{"elements":[
               {"type":"Field","id":"/Policy/Reference","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
               {"type":"Field","id":"/Policy/Code","name":"Code","Field":{"fieldType":{"type":"StringType","StringType":
                 {"pattern":"[A-Z]+","errorMessage":[{"locale":"en_US","text":"Capitals only."}],
                  "hintList":[{"locale":"en_US","values":["AB","CD"]}]}}}},
               {"type":"Field","id":"/Policy/Premium","name":"Premium","Field":{"fieldType":{"type":"NumberType","NumberType":
                 {"maxFractionalDigits":2,"minValue":"0","maxValue":"9999"}}}},
               {"type":"Field","id":"/Policy/Start","name":"Start","Field":{"fieldType":{"type":"DateType","DateType":
                 {"format":"yyyy-MM-dd","interpretationOfYear":"PAST"}}}},
               {"type":"Field","id":"/Policy/Tier","name":"Tier","Field":{"fieldType":{"type":"EnumerationType","EnumerationType":
                 {"values":[{"value":"GOLD"},{"value":"SILVER"}],"categories":[{"name":"Band","values":["HIGH","LOW"]}]}}}},
               {"type":"Field","id":"/Policy/Cutoff","name":"Cutoff","Field":{"fieldType":{"type":"TimeType","TimeType":{"format":"HH:mm:ss"}}}},
               {"type":"Group","id":"/Policy/Claims","name":"Claims","Group":{"repeatability":5,"indexFieldName":"ClaimNo",
                 "sortInfo":{"sortFields":[{"name":"ClaimNo"}]},"elements":[
                 {"type":"Field","id":"/Policy/Claims/ClaimNo","name":"ClaimNo","Field":{"fieldType":{"type":"StringType"}}},
                 {"type":"Field","id":"/Policy/Claims/Payout","name":"Payout","Field":{"fieldType":{"type":"NumberType","NumberType":{"maxFractionalDigits":2}}}}]}},
               {"type":"Rule","id":"/Policy/ReferenceGiven","name":"ReferenceGiven","Rule":{"errorEntityRelPath":"../Reference",
                 "errorCode":"REFERENCE_GIVEN","errorCondition":"FieldNotFilled(Reference)","severity":"ERROR",
                 "errorMessage":[{"locale":"en_US","text":"The reference is required."}]}},
               {"type":"Computation","id":"/Policy/PremiumDefault","name":"PremiumDefault","Computation":{"computedFieldRelPath":"../Premium",
                 "computationAlternatives":[{"precondition":"FieldFilled(Reference)","operation":"100"}],
                 "errorMessage":[{"locale":"en_US","text":"The premium could not be set."}]}},
               {"type":"Field","id":"/Policy/Memo","name":"Memo","Field":{"fieldType":{"type":"StringType","StringType":{"lineBreaksPermitted":true,"noValueValidation":true}}}}
              ]}}]}}}
        """.trimIndent()
    }
}
