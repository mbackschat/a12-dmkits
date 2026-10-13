package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.en
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.portable
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.rule
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which keyed message parameters a keyed read admits (a12-kernel-lean `SPEC-2026-10-13-12`): a keyed field read
 * and a keyed GROUP read, keys compared by spelling (a Number index included), the unkeyed twin, and the value
 * suffix's star gate beside keyed and starred reads. Each row compares the kernel with the portable checker.
 *
 * Own domain (an insurance policy: claims keyed by reference, tiers keyed by number, an office with desks).
 * Message text stays raw, because its tokens are the measured input.
 */
class KeyedMessageMembershipAgreementTest {

    private val policy: String = DocumentModelDraft.named("policy-keyed-membership").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Holder") { }.stringField("Pick") { }
            .group("Claims") { c -> c.repeatable(5).indexField("Ref")
                .stringField("Ref") { }.stringField("Note") { }.stringField("Gate") { }
                .group("Items") { i -> i.repeatable(3).stringField("Item") { } } }
            .group("Tiers") { t -> t.repeatable(3).indexField("Num")
                .numberField("Num") { it.fractionDigits(0) }.stringField("Label") { } }
            .group("Office") { o -> o.stringField("Staff") { }
                .group("Desks") { d -> d.repeatable(2).stringField("Seats") { } } } }
        .build().toJson()

    private enum class Reads { KEYED_FIELD, KEYED_GROUP, KEYED_NUMBER, STARRED, KEYED_AND_STARRED, OFFICE }

    private fun condition(edit: EditSession, reads: Reads): Condition {
        val root = edit.group("/Policy")
        val holder = root.str("Holder").filled()
        val keyed = root.str("Claims/Note").semanticIndex("c1").filled()
        val starred = Dsl.numberOfFilledFields(root.flatten("Claims*/Note")).gt(0)
        return when (reads) {
            Reads.KEYED_FIELD -> holder.and(keyed)
            Reads.KEYED_GROUP -> holder.and(root.flattenGroup("Claims").forIndex("c1").filled())
            Reads.KEYED_NUMBER -> holder.and(root.str("Tiers/Label").semanticIndex("1").filled())
            Reads.STARRED -> holder.and(starred)
            Reads.KEYED_AND_STARRED -> holder.and(keyed).and(starred)
            Reads.OFFICE -> holder.and(root.group("Office").filled())
        }
    }

    private fun rows(): Map<String, Pair<Reads, String>> = linkedMapOf(
        "keyed field: the field" to (Reads.KEYED_FIELD to "\$/Policy/Claims/Note.value For \"c1\"\$"),
        "keyed field: its name" to (Reads.KEYED_FIELD to "\$/Policy/Claims/Note For \"c1\"\$"),
        "keyed field: its index field" to (Reads.KEYED_FIELD to "\$/Policy/Claims/Ref.value For \"c1\"\$"),
        "keyed field: another field" to (Reads.KEYED_FIELD to "\$/Policy/Claims/Gate.value For \"c1\"\$"),
        "keyed field: another key" to (Reads.KEYED_FIELD to "\$/Policy/Claims/Note.value For \"c2\"\$"),
        "keyed field: a field key" to (Reads.KEYED_FIELD to "\$/Policy/Claims/Note.value For /Policy/Pick\$"),
        "keyed field: unkeyed" to (Reads.KEYED_FIELD to "\$/Policy/Claims/Note.value\$"),
        "keyed field: unkeyed name" to (Reads.KEYED_FIELD to "\$/Policy/Claims/Note\$"),
        "keyed field: another key and a category" to (Reads.KEYED_FIELD to "\$/Policy/Claims/Note->Band For \"c2\"\$"),
        "keyed field: the keyed group named" to (Reads.KEYED_FIELD to "\$/Policy/Claims For \"c1\"\$"),
        "keyed group: a field" to (Reads.KEYED_GROUP to "\$/Policy/Claims/Note.value For \"c1\"\$"),
        "keyed group: another field" to (Reads.KEYED_GROUP to "\$/Policy/Claims/Gate.value For \"c1\"\$"),
        "keyed group: the index field" to (Reads.KEYED_GROUP to "\$/Policy/Claims/Ref.value For \"c1\"\$"),
        "keyed group: another key" to (Reads.KEYED_GROUP to "\$/Policy/Claims/Note.value For \"c2\"\$"),
        "keyed group: unkeyed" to (Reads.KEYED_GROUP to "\$/Policy/Claims/Note.value\$"),
        "keyed group: the group named" to (Reads.KEYED_GROUP to "\$/Policy/Claims For \"c1\"\$"),
        "keyed group: a deeper repeatable field's name" to (Reads.KEYED_GROUP to "\$/Policy/Claims/Items/Item For \"c1\"\$"),
        "keyed group: a deeper repeatable field's value" to (Reads.KEYED_GROUP to "\$/Policy/Claims/Items/Item.value For \"c1\"\$"),
        "number key: as written" to (Reads.KEYED_NUMBER to "\$/Policy/Tiers/Label.value For \"1\"\$"),
        "number key: a leading zero" to (Reads.KEYED_NUMBER to "\$/Policy/Tiers/Label.value For \"01\"\$"),
        "number key: a fraction" to (Reads.KEYED_NUMBER to "\$/Policy/Tiers/Label.value For \"1.0\"\$"),
        "starred: unkeyed value" to (Reads.STARRED to "\$/Policy/Claims/Note.value\$"),
        "starred: keyed value" to (Reads.STARRED to "\$/Policy/Claims/Note.value For \"c1\"\$"),
        "keyed and starred: unkeyed value" to (Reads.KEYED_AND_STARRED to "\$/Policy/Claims/Note.value\$"),
        "group read: its field" to (Reads.OFFICE to "\$/Policy/Office/Staff.value\$"),
        "group read: a repeatable child's field" to (Reads.OFFICE to "\$/Policy/Office/Desks/Seats.value\$"),
        "group read: a keyed group named" to (Reads.OFFICE to "\$/Policy/Claims For \"c1\"\$"),
    )

    @Test
    fun theCheckerAgreesWithA12() {
        val verdicts = rows().mapValues { (_, row) ->
            val model = rule(policy, "/Policy", "Holder", en(row.second)) { edit -> condition(edit, row.first) }
            kernel(model) to portable(model)
        }
        assertEquals(emptyMap<String, Any>(), verdicts.filterValues { it.first != it.second }
            .mapValues { "A12 ${it.value.first}, ours ${it.value.second}" })
        // The grid is not agreement on nothing: every row carries A12's own verdict, refusals and admissions both.
        assertEquals(mapOf(
            "keyed field: the field" to emptyList(), "keyed field: its name" to emptyList(),
            "keyed field: its index field" to emptyList(), "keyed field: another field" to listOf(INDEX),
            "keyed field: another key" to listOf(INDEX), "keyed field: a field key" to listOf(INDEX),
            "keyed field: unkeyed" to listOf(FIELD), "keyed field: unkeyed name" to listOf(FIELD),
            // A String field, so the key gate answers before a category gate could.
            "keyed field: another key and a category" to listOf(INDEX),
            "keyed field: the keyed group named" to listOf(INDEX),
            "keyed group: a field" to emptyList(), "keyed group: another field" to emptyList(),
            "keyed group: the index field" to emptyList(), "keyed group: another key" to listOf(INDEX),
            "keyed group: unkeyed" to listOf(FIELD), "keyed group: the group named" to listOf(INDEX),
            "keyed group: a deeper repeatable field's name" to emptyList(),
            "keyed group: a deeper repeatable field's value" to listOf(STAR),
            "number key: as written" to emptyList(), "number key: a leading zero" to listOf(INDEX),
            "number key: a fraction" to listOf(INDEX),
            "starred: unkeyed value" to listOf(STAR), "starred: keyed value" to listOf(INDEX),
            "keyed and starred: unkeyed value" to emptyList(),
            "group read: its field" to emptyList(), "group read: a repeatable child's field" to listOf(STAR),
            "group read: a keyed group named" to listOf(INDEX),
        ), verdicts.mapValues { it.value.first })
    }

    /**
     * The `index(…)` terminal on two Enumeration-indexed groups read in parallel, their labels differing: A12 refuses
     * the value suffix (`validate.laws.ParallelEnumIndexDisplayLawsTest`), and the checker, which declines an index
     * name two groups declare, stays silent rather than naming another class.
     */
    @Test
    fun theCheckerLeavesTheDisplayLabelClassToA12() {
        val grades = DocumentModelDraft.named("policy-parallel-grades").locale("en_US")
            .rootGroup("Policy") { p -> p
                .group("Covers") { c -> c.repeatable(5).indexField("Grade")
                    .enumField("Grade") { e -> e.value("A") { it.label("en_US", "Alpha") }.value("B") { it.label("en_US", "Beta") } }
                    .stringField("Remark") { } }
                .group("Limits") { c -> c.repeatable(5).indexField("Grade")
                    .enumField("Grade") { e -> e.value("A") { it.label("en_US", "Ace") }.value("B") { it.label("en_US", "Bee") } }
                    .numberField("Cap") { it.fractionDigits(0) } } }
            .build().toJson()
        val model = rule(grades, "/Policy/Covers", "Remark", Dsl.en("grade \$index(Grade).value\$")) { edit ->
            edit.group("/Policy/Covers").str("Remark").filled().and(edit.group("/Policy").num("Limits/Cap").filled())
        }
        assertEquals(listOf("INVALID_INDEX_FIELD_ENUM_XML_TO_DISPLAY") to emptyList<String>(), kernel(model) to portable(model))
    }

    private companion object {
        const val FIELD = "INVALID_FIELD"
        const val INDEX = "MVK_INDEX_FOR_ERROR_TEXT_INVALID"
        const val STAR = "PARAM_VALUE_WITH_STARREFERENCE"
    }
}
