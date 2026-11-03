package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.Admission
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.admit
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.core.ModelInputException
import io.github.mbackschat.a12.dm.core.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12 on what kernel 31.1.1's reader binds (KERNEL-FINDINGS KF654): a member it does not know, a
 * field type written without its body, a type it no longer has, and the members whose absence leaves no model. The
 * value entries without a `value` are KF655's: such an entry is a value with no token, which keeps its place.
 *
 * A12's column is asserted, not copied: every row reads the Kernel gate, with a model it cannot read or build spelled
 * as the checker's `CK_MODEL_NOT_BUILDABLE` and a codeless sentence named through [legibleNotificationClass].
 *
 * Own domain (a claim with a reference, a note, an amount and a filing date, and one rule).
 */
class CheckerReaderBindingAgreementTest {

    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("claim-reader-binding").locale("en_US")
        .rootGroup("Claim") { c ->
            c.stringField("Ref") { }.stringField("Note") { }.numberField("Amount") { it.fractionDigits(2) }
                .dateField("FiledOn") { }
        }
        .build().toJson().let { RuntimeLaws.withRule(it, "/Claim", "RefCheck", "../Ref", "REF", "FieldFilled(Ref)") }

    @Test
    fun theCheckerReadsWhatA12Reads() {
        val actual = ROWS.mapValues { (_, edit) ->
            val json = Edit(base).also(edit).json()
            a12Classes(json) to ours(json)
        }
        val expected = A12.mapValues { (row, classes) ->
            classes.toSortedSet().let { it to (DECLINED[row]?.let(::sortedSetOf) ?: it) }
        }
        assertEquals(expected, actual, "as (A12's classes, the checker's)")
    }

    /** The checker's rejecting codes, or the code of the admission it declined, which checks nothing. */
    private fun ours(json: String): Set<String> = when (val admission = admit(ModelSource(json))) {
        is Admission.Rejected -> sortedSetOf("declined:${admission.diagnostic.code}")
        is Admission.Admitted -> check(ModelSource(json)).findings.filter { it.justifiesRejection }
            .mapTo(sortedSetOf()) { it.code }
    }

    private fun a12Classes(json: String): Set<String> = try {
        ModelLoader.loadSelfContained(json)
        sortedSetOf()
    } catch (unreadable: ModelInputException) {
        sortedSetOf(UNBUILT)
    } catch (rejected: ModelConsistencyException) {
        rejected.notifications().mapTo(sortedSetOf()) { notification ->
            val message = notification.message().trim()
            if (message.startsWith(NOT_BUILT)) UNBUILT else legibleNotificationClass(message)
        }
    }

    private class Edit(json: String) {
        private val root = ObjectMapper().readTree(json) as ObjectNode
        private val elements = root.at("/content/modelRoot/rootGroups/0/Group/elements") as ArrayNode

        fun element(name: String): ObjectNode = elements.single { it.path("name").asText() == name } as ObjectNode
        fun header(): ObjectNode = root.path("header") as ObjectNode
        fun rule(): ObjectNode = element("RefCheck").path("Rule") as ObjectNode
        fun fieldType(name: String): ObjectNode = element(name).path("Field").path("fieldType") as ObjectNode

        /** [name]'s field type replaced by [type], with [body] as its body, or none when [body] is null. */
        fun retype(name: String, type: String, body: String?) {
            val declared = (element(name).path("Field") as ObjectNode).putObject("fieldType").put("type", type)
            body?.let { declared.set<ObjectNode>(type, ObjectMapper().readTree(it)) }
        }

        /** [name] as an enumeration of `OPEN` and `CLOSED`, with [extra] members beside its values. */
        fun states(name: String, extra: String = "") =
            retype(name, "EnumerationType", """{"values":[{"value":"OPEN"},{"value":"CLOSED"}]$extra}""")

        /** A second rule listing, beside a declared token, one [name]'s enumeration does not declare. */
        fun undeclaredToken(name: String) {
            val rule = elements.addObject().put("type", "Rule").put("id", "state-check").put("name", "StateCheck")
                .putObject("Rule").put("errorEntityRelPath", "../$name").put("errorCode", "STATE")
                .put("errorCondition", "FieldValueIncludedInValueList($name, \"OPEN\", \"PENDING\")").put("severity", "ERROR")
            rule.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Check the state.")
        }

        /**
         * A computation writing `Amount`, or naming no target at all when [target] is null; [explicitNull] writes the
         * member as JSON `null` instead of leaving it out.
         */
        fun computation(target: String?, explicitNull: Boolean = false) {
            val body = ObjectMapper().createObjectNode()
            target?.let { body.put("computedFieldRelPath", it) }
            if (explicitNull) body.putNull("computedFieldRelPath")
            body.putArray("computationAlternatives").addObject().put("operation", "1")
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Amount is one.")
            elements.addObject().put("type", "Computation").put("id", "/Claim/AmountComp").put("name", "AmountComp")
                .set<ObjectNode>("Computation", body)
        }

        fun json(): String = root.toString()
    }

    private companion object {
        const val UNBUILT = "CK_MODEL_NOT_BUILDABLE"
        const val NOT_BUILT = "the kernel could not build this model"
        const val ENUM_TOKEN = "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION"
        const val VALUES_MISSING = "MVK_INTERNAL_VALUES_MISSING"
        const val DISPLAY = "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES"
        const val OPEN_LABEL = "\"label\":[{\"locale\":\"en_US\",\"text\":\"Open\"}]"
        const val CLOSED_LABEL = "\"label\":[{\"locale\":\"en_US\",\"text\":\"Closed\"}]"

        val ROWS: Map<String, (Edit) -> Unit> = linkedMapOf(
            "the base" to { },
            "a member no rule has" to { e -> e.rule().put("colourScheme", "red") },
            "a member no field has" to { e -> (e.element("Note").path("Field") as ObjectNode).put("colourScheme", "red") },
            "a fieldType on a rule" to { e -> e.rule().putObject("fieldType").put("type", "StringType") },
            "a member no date type has" to { e -> (e.fieldType("FiledOn").path("DateType") as ObjectNode).put("formatt", "yyyy") },
            "a date type with no body" to { e -> e.retype("Note", "DateType", null) },
            "a time type with no body" to { e -> e.retype("Note", "TimeType", null) },
            "a date-time type with no body" to { e -> e.retype("Note", "DateTimeType", null) },
            "a date fragment with no body" to { e -> e.retype("Note", "DateFragmentType", null) },
            "a date range with no body" to { e -> e.retype("Note", "DateRangeType", null) },
            "an enumeration with no body" to { e -> e.retype("Note", "EnumerationType", null) },
            "a custom type with no body" to { e -> e.retype("Note", "CustomFieldType", null) },
            "a string type with no body" to { e -> e.retype("Note", "StringType", null) },
            "the unspecified type" to { e -> e.retype("Note", "UnspecifiedType", "{}") },
            "an enumeration's values misspelled" to { e ->
                e.retype("Note", "EnumerationType", """{"valuez":[{"value":"OPEN"}]}""")
            },
            // A value entry whose `value` is misspelled is read as a value with no token (KERNEL-FINDINGS KF655).
            "no value entry with a value" to { e ->
                e.retype("Note", "EnumerationType", """{"values":[{"valu":"OPEN"},{"valu":"CLOSED"}]}""")
            },
            "one of two value entries without a value" to { e ->
                e.retype("Note", "EnumerationType", """{"values":[{"value":"OPEN"},{"valu":"CLOSED"}]}""")
            },
            "no value entry with a value, both labelled" to { e ->
                e.retype("Note", "EnumerationType", """{"values":[{"valu":"OPEN",$OPEN_LABEL},{"valu":"CLOSED",$CLOSED_LABEL}]}""")
            },
            "no value entry with a value, beside a category" to { e ->
                e.retype("Note", "EnumerationType", """{"values":[{"valu":"OPEN"}],"categories":[{"name":"State","values":["OPEN"]}]}""")
            },
            "two of three value entries without a value" to { e ->
                e.retype("Note", "EnumerationType", """{"values":[{"value":"OPEN"},{"valu":"A"},{"valu":"B"}]}""")
            },
            "one of two value entries without a value, both labelled" to { e ->
                e.retype("Note", "EnumerationType", """{"values":[{"value":"OPEN",$OPEN_LABEL},{"valu":"CLOSED",$CLOSED_LABEL}]}""")
            },
            // A token-less entry keeps its position in the category pairing, which these two rows separate from a
            // pairing over the entries that carry a token: that one would admit both.
            "a token-less entry between two, beside a two-token category" to { e ->
                e.retype("Note", "EnumerationType", """{"values":[{"value":"OPEN"},{"valu":"X"},{"value":"CLOSED"}],""" +
                    """"categories":[{"name":"State","values":["A","B"]}]}""")
            },
            "a token-less entry first, beside a one-token category" to { e ->
                e.retype("Note", "EnumerationType", """{"values":[{"valu":"X"},{"value":"OPEN"}],""" +
                    """"categories":[{"name":"State","values":["A"]}]}""")
            },
            "an enumeration's categories misspelled" to { e ->
                e.retype("Note", "EnumerationType", """{"values":[{"value":"OPEN"}],"categoriez":[{"name":"State","values":["OPEN"]}]}""")
            },
            "a table name beside the values" to { e ->
                e.retype("Note", "EnumerationType", """{"values":[{"value":"OPEN"}],"tableName":"States"}""")
            },
            // The twins behind dropping the checker's table exemptions: the reader passes over `tableName`, so the
            // enumeration is judged like the one without it.
            "an undeclared token against an enumeration" to { e -> e.states("Note"); e.undeclaredToken("Note") },
            "the same beside a table name" to { e ->
                e.states("Note", ",\"tableName\":\"States\""); e.undeclaredToken("Note")
            },
            "a rule with no errorCode" to { e -> e.rule().remove("errorCode") },
            "a rule with no errorEntityRelPath" to { e -> e.rule().remove("errorEntityRelPath") },
            // The twins: one computation, differing only by whether it names the field it writes.
            "a computation naming its computed field" to { e -> e.computation("../Amount") },
            "a computation with no computedFieldRelPath" to { e -> e.computation(null) },
            "a computation whose computedFieldRelPath is null" to { e -> e.computation(null, explicitNull = true) },
            "a header with no id" to { e -> e.header().remove("id") },
        )

        /** A12's classes per row, measured on kernel 31.1.1 (2026-11-02; the computation twins 2026-11-03). */
        val A12: Map<String, List<String>> = linkedMapOf(
            "the base" to listOf(),
            "a member no rule has" to listOf(),
            "a member no field has" to listOf(),
            "a fieldType on a rule" to listOf(),
            "a member no date type has" to listOf(),
            "a date type with no body" to listOf(),
            "a time type with no body" to listOf(),
            "a date-time type with no body" to listOf(),
            "a date fragment with no body" to listOf(UNBUILT),
            "a date range with no body" to listOf("RK_DATE_RANGE_FORMAT_INVALID"),
            "an enumeration with no body" to listOf(UNBUILT),
            "a custom type with no body" to listOf("CUSTOM_FIELD_TYPE_CODENAME_MISSING"),
            "a string type with no body" to listOf(),
            "the unspecified type" to listOf(UNBUILT),
            "an enumeration's values misspelled" to listOf(UNBUILT),
            "no value entry with a value" to listOf(VALUES_MISSING, "RK_ENUM_VALUE_DUPLICATE"),
            "one of two value entries without a value" to listOf(),
            "no value entry with a value, both labelled" to
                listOf(VALUES_MISSING, DISPLAY, "MVK_INTERNAL_VALUE_MISSING", "RK_ENUM_VALUE_DUPLICATE"),
            "no value entry with a value, beside a category" to listOf(VALUES_MISSING, "MVK_MISSING_INTERNAL_VALUE"),
            "two of three value entries without a value" to listOf("RK_ENUM_VALUE_DUPLICATE"),
            "one of two value entries without a value, both labelled" to listOf("MVK_INTERNAL_VALUE_MISSING", DISPLAY),
            "a token-less entry between two, beside a two-token category" to
                listOf("MVK_MISSING_CATEGORY_VALUE", "MVK_MISSING_INTERNAL_VALUE"),
            "a token-less entry first, beside a one-token category" to
                listOf("MVK_MISSING_CATEGORY_VALUE", "MVK_MISSING_INTERNAL_VALUE"),
            "an enumeration's categories misspelled" to listOf(),
            "a table name beside the values" to listOf(),
            "an undeclared token against an enumeration" to listOf(ENUM_TOKEN),
            "the same beside a table name" to listOf(ENUM_TOKEN),
            "a rule with no errorCode" to listOf(UNBUILT),
            "a rule with no errorEntityRelPath" to listOf(UNBUILT),
            "a computation naming its computed field" to listOf(),
            "a computation with no computedFieldRelPath" to listOf(UNBUILT),
            "a computation whose computedFieldRelPath is null" to listOf(UNBUILT),
            "a header with no id" to listOf(UNBUILT),
        )

        /**
         * The rows where the checker declines the source rather than judging it, which reports less than A12 and
         * never more: a source with no `header.id` has no identity to key a report by.
         */
        val DECLINED: Map<String, String> = mapOf("a header with no id" to "declined:SOURCE_HAS_NO_IDENTITY")
    }
}
