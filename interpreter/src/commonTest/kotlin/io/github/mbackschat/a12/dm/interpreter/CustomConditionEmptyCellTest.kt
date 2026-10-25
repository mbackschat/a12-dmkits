package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF440 and IF441: a custom condition's data view lists every field and group
 * instance the Document holds, empty ones included, its stored value reads a String stored as `""` as `""` while
 * JSON `null` and a typed `""` hold no value, and a present repeatable group instance reads A12's placeholder. The Kernel answers are `adapter.laws.CustomConditionEmptyCellDiffTest`'s.
 */
class CustomConditionEmptyCellTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef("/Order/Ref", Kind.STRING),
            FieldDef("/Order/Note", Kind.STRING),
            FieldDef("/Order/Qty", Kind.NUMBER, scale = 0),
            FieldDef("/Order/Lines/Sku", Kind.STRING),
        ),
        rules = listOf(RuleDef("/Order/ObserveRule", "/Order", "FieldFilled(Ref) And CustomCondition Observe", "OBSERVE", "../Ref")),
        groupPaths = setOf("/Order", "/Order/Lines"),
        declaredReps = mapOf("/Order/Lines" to 3),
        repeatableGroups = setOf("/Order/Lines"),
    )

    /** Each field instance the condition sees, with its stored value. */
    private fun seen(json: String): Map<String, String?> {
        var fields: Map<String, String?>? = null
        val observe = CustomCondition { data, _, _, _ ->
            fields = data.fields().associate { it.path to data.storedValue(it) }
            false
        }
        val report = DmInterpreter(model, customConditions = mapOf("Observe" to observe)).validate(model.readDocumentJson(json))
        assertEquals(emptyList(), report.unsupported)
        return checkNotNull(fields)
    }

    @Test fun aStringStoredAsEmptyIsAFieldHoldingTheEmptyString() =
        assertEquals(mapOf("/Order/Ref" to "r", "/Order/Note" to ""), seen("""{"Order":{"Ref":"r","Note":""}}"""))

    @Test fun jsonNullAndATypedEmptyAreFieldsHoldingNoValue() =
        assertEquals(mapOf("/Order/Ref" to "r", "/Order/Note" to null, "/Order/Qty" to null),
            seen("""{"Order":{"Ref":"r","Note":null,"Qty":""}}"""))

    /** A bare `{}` row is an instance too, and only a group declared with more than one repetition reads the
     *  placeholder; the root group and an absent row read null, A12's `""`. */
    @Test fun groupInstancesAreListedAndARepeatableOneReadsThePlaceholder() {
        var groups: List<String>? = null
        var reads: List<String?>? = null
        val observe = CustomCondition { data, _, _, _ ->
            groups = data.groups().map { it.path + it.indices }.sorted()
            reads = listOf(listOf(1), listOf(1, 1), listOf(1, 2), listOf(1, 3)).map { reps ->
                data.getValue(CustomConditionFieldPointer(if (reps.size == 1) "/Order" else "/Order/Lines", reps))
            }
            false
        }
        DmInterpreter(model, customConditions = mapOf("Observe" to observe))
            .validate(model.readDocumentJson("""{"Order":{"Ref":"r","Lines":[{},{"Sku":"s"}]}}"""))
        assertEquals(listOf("/Order/Lines[1, 1]", "/Order/Lines[1, 2]", "/Order[1]"), groups)
        assertEquals(listOf(null, "NonEmptyString", "NonEmptyString", null), reads)
    }

    @Test fun theIDataReadStillAnswersAnEmptyStringWithNull() {
        var read: String? = "unread"
        val observe = CustomCondition { data, _, _, _ ->
            read = data.getValue(CustomConditionFieldPointer("/Order/Note", listOf(1, 1)))
            false
        }
        DmInterpreter(model, customConditions = mapOf("Observe" to observe)).validate(model.readDocumentJson("""{"Order":{"Ref":"r","Note":""}}"""))
        assertEquals(null, read)
    }
}
