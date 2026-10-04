package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Which operands a message names as REFERENCED and which as fill-to-fix (`refOmissionErrorResponsible`). Both lists are
 * fixed per rule when the model is generated, not judged against the Document: an omission names every operand that
 * could cause an omission, filled or not. An operand read only by a presence construct (`FieldFilled`,
 * `AtLeastOneFieldFilled`, `GroupFilled`, `AtLeastOneGroupFilled` …) or by a filled-count compared upward against a
 * constant cannot, so it is referenced but never fill-to-fix; a filter's own operands are fill-to-fix even under such a
 * construct. A root group is never expanded into its fields, so a root-group operand contributes to neither list.
 *
 * Every rule is `X And FieldNotFilled(Contact)` over an empty `Contact`, so each fires as an omission and its
 * fill-to-fix list is observable. Own domain (e-commerce order).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageReferenceScopeDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("order-message-references").locale("en_US")
            .rootGroup("Order") { o ->
                o.stringField("Contact") { }
                for (name in listOf("Express", "Insured", "Weight", "MinItems")) o.numberField(name) { it.fractionDigits(0) }
                o.group("Lines") { l ->
                    l.repeatable(3)
                    l.numberField("Qty") { it.fractionDigits(0) }
                    l.numberField("Picked") { it.fractionDigits(0) }
                }
                o.group("Address") { a ->
                    a.stringField("Street") { }
                    a.stringField("City") { }
                }
                o.group("Billing") { b -> b.stringField("Iban") { } }
            }.build().toJson()
        val session = EditSession.read(draft)
        val o = session.group("/Order")
        val contact = o.str("Contact")
        val express = o.num("Express")
        val insured = o.num("Insured").field()
        val weight = o.num("Weight").field()
        val address = session.group("/Order/Address")
        val billing = session.group("/Order/Billing")
        val count = Dsl.numberOfFilledFields(express.field(), weight)
        val none = Dsl.numberOfFilledFields(insured, weight)
        val rules: List<Pair<String, Condition>> = listOf(
            "PresenceOnly" to express.filled(),
            "AnyPresence" to Dsl.atLeastOneFieldFilled(express.field(), weight),
            "NoneFilled" to Dsl.noFieldFilled(insured, weight),
            "CountAbove" to count.gt(0),
            "CountAboveMirrored" to Dsl.number(0).lt(count),
            "CountAboveAField" to count.gt(o.num("MinItems")),
            "DistinctAbove" to Dsl.numberOfDifferentValues(express.field(), weight).gt(0),
            "CountBelow" to none.lt(1),
            "CountBelowMirrored" to Dsl.number(1).gt(none),
            "CountEquals" to none.eq(0),
            "MoreThanOne" to Dsl.moreThanOneFieldFilled(express.field(), o.num("MinItems").field()),
            "ValueRead" to express.asNumber().gt(0),
            "FilteredPresence" to Dsl.allFieldsFilled(
                o.flatten("Lines*/Qty").having(session.group("/Order/Lines").num("Picked").gt(0))),
            "RootPresence" to o.ref().filled(),
            "RootAnyGroup" to Dsl.atLeastOneGroupFilled(o.ref()),
            "GroupPresence" to address.ref().filled(),
            "GroupAbsence" to address.ref().notFilled(),
            "AnyOfGroups" to Dsl.atLeastOneGroupFilled(address.ref(), billing.ref()),
            "AllOfGroups" to Dsl.allGroupsFilled(address.ref(), billing.ref()),
        )
        var json = session.toJson()
        for ((code, condition) in rules) {
            json = TypedRules.inject(json, o.rule {
                it.name(code).code(code).errorField(contact.field()).`when`(condition.and(contact.notFilled()))
                    .message(Dsl.en("Check the order.")).build()
            })
        }
        json
    }

    private fun document(address: String) =
        """{"Order":{"Express":2,"MinItems":0,"Lines":[{"Qty":1,"Picked":1}],"Billing":{"Iban":"DE00"}$address}}"""

    private val documents = linkedMapOf(
        "address given" to document(""","Address":{"Street":"Main"}"""),
        "address missing" to document(""),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * The separating rows, pinned against dynamic Groovy so the agreement above cannot pass vacuously: each code maps to
     * `type|referenced|fill-to-fix`, pointers shortened below the root.
     */
    @Test
    fun theSeparatingRowsAreTheKernelsOwn() {
        fun short(pointers: List<String>) = pointers.map { it.removePrefix("/Order[1]/") }
        val actual = documents.mapValues { (_, json) ->
            DocumentDifferential.kernel(model, json, dynamic = true).messages
                .map { "${it.code}|${it.type.substringBefore('_')}|${short(it.referenced)}|${short(it.fillToFix)}" }
        }
        val given = listOf(
            "AllOfGroups|OMISSION|[Address[1]/City, Address[1]/Street, Billing[1]/Iban, Contact]|[Contact]",
            "AnyOfGroups|OMISSION|[Address[1]/City, Address[1]/Street, Billing[1]/Iban, Contact]|[Contact]",
            "AnyPresence|OMISSION|[Contact, Express, Weight]|[Contact]",
            "CountAboveAField|OMISSION|[Contact, Express, MinItems, Weight]|[Contact]",
            "CountAboveMirrored|OMISSION|[Contact, Express, Weight]|[Contact]",
            "CountAbove|OMISSION|[Contact, Express, Weight]|[Contact]",
            "CountBelowMirrored|OMISSION|[Contact, Insured, Weight]|[Contact, Insured, Weight]",
            "CountBelow|OMISSION|[Contact, Insured, Weight]|[Contact, Insured, Weight]",
            "CountEquals|OMISSION|[Contact, Insured, Weight]|[Contact, Insured, Weight]",
            "DistinctAbove|OMISSION|[Contact, Express, Weight]|[Contact]",
            "FilteredPresence|OMISSION|[Contact, Lines[0]/Picked, Lines[0]/Qty]|[Contact, Lines[0]/Picked]",
            "GroupPresence|OMISSION|[Address[1]/City, Address[1]/Street, Contact]|[Contact]",
            "MoreThanOne|OMISSION|[Contact, Express, MinItems]|[Contact]",
            "NoneFilled|OMISSION|[Contact, Insured, Weight]|[Contact, Insured, Weight]",
            "PresenceOnly|OMISSION|[Contact, Express]|[Contact]",
            "RootAnyGroup|OMISSION|[Contact]|[Contact]",
            "RootPresence|OMISSION|[Contact]|[Contact]",
            "ValueRead|OMISSION|[Contact, Express]|[Contact, Express]",
        )
        // Without the address the two rules that need it stay silent and its absence fires instead.
        val missing = (given.filterNot { it.startsWith("GroupPresence|") || it.startsWith("AllOfGroups|") } +
            "GroupAbsence|OMISSION|[Address[1]/City, Address[1]/Street, Contact]|[Address[1]/City, Address[1]/Street, Contact]").sorted()
        assertEquals(linkedMapOf("address given" to given, "address missing" to missing), actual)
    }
}
