package io.github.mbackschat.a12.dm.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory
import com.mgmtp.a12.kernel.md.model.api.IField
import com.mgmtp.a12.kernel.md.model.api.IGroup
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IEnumerationType
import com.mgmtp.a12.model.notification.Severity
import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Why an EMPTY declaration member reads as an absent one on kernel 31.1.1: the expand step, not the check
 * (KERNEL-FINDINGS KF658).
 *
 * Kernel 31.1.1's only expand path, `CombinationModelService.expand`, starts by copying the model through A12's own
 * serializer, which writes with `NON_EMPTY` value inclusion; the copy omits an empty string and reads the member's
 * builder default back (source-read at `eec9134`). The rival account is a change in the check itself, carried by the
 * bumped external metamodel validator. This is the discriminator between the two: the same bytes checked RAW, with
 * no expand, and through the adapter's expand. Raw still answers as 30.8.1 did, so the check did not change; the
 * expanded model answers the 31.1.1 way, so the copy is what changed. A blank member is not empty, and its row
 * agrees on both arms — the control that the copy keys on emptiness rather than on blankness.
 *
 * Own domain (an insurance policy's start date).
 */
class ExpandCopyEmptyMemberLawsTest {

    @Test
    fun theExpandCopyReadsAnEmptyMemberAsItsDefault() {
        val rows = linkedMapOf(
            "date format absent" to withStart("""{"type":"DateType","DateType":{}}"""),
            "date format empty" to withStart("""{"type":"DateType","DateType":{"format":""}}"""),
            "date format a space" to withStart("""{"type":"DateType","DateType":{"format":" "}}"""),
            "range ISO, separator `/`" to range("yyyy-MM-dd", "/"),
            "range ISO, separator empty" to range("yyyy-MM-dd", ""),
            "range German, separator empty" to range("dd.MM.yyyy", ""),
        )
        val observed = rows.mapValues { (_, json) -> raw(json) to expanded(json) }

        val rangeRefused = listOf("Field with path '/Policy/Start': The date range format is invalid.")
        val expected = linkedMapOf(
            "date format absent" to (ADMITTED to ADMITTED),
            "date format empty" to (UNBUILT to ADMITTED),
            "date format a space" to (UNBUILT to UNBUILT),
            "range ISO, separator `/`" to (ADMITTED to ADMITTED),
            "range ISO, separator empty" to (rangeRefused to ADMITTED),
            // `/` does not take the German format, so the copy's default is refused too: the discriminator for
            // "empty reads as `/`" against "an empty separator became admitted".
            "range German, separator empty" to (rangeRefused to rangeRefused),
        )
        assertEquals(expected, observed, "(raw check, check after A12's expand)")
    }

    /**
     * The copy reaches inside an enumeration's value entries too: a value written `""` is the empty token on the raw
     * model and no token at all after the expand, which is why kernel 31.1.1 refuses `""` as a condition literal even
     * where it is declared (`adapter.laws.EmptyEnumerationTokenDiffTest`). The non-empty neighbour is the control.
     */
    @Test
    fun theExpandCopyReadsAnEmptyEnumerationTokenAsNoToken() {
        val json = DocumentModelDraft.named("gift-order").locale("en_US")
            .rootGroup("Gift") { g -> g.enumField("Wrapping") { e -> e.value("").value("Gold") } }
            .build().toJson()
        val raw = KernelModelInput.deserialize(FACTORY.createDocumentModelSerializer(), json)
        val rawField = (raw.content.documentModelRoot.elements.single() as IGroup).elements.single() as IField
        val expanded = KernelModelOracle.ofSelfContained(json).fieldType("/Gift/Wrapping")
        assertEquals(
            listOf("", "Gold") to listOf(null, "Gold"),
            (rawField.fieldType as IEnumerationType).values.map { it.value } to
                (expanded as IEnumerationType).values.map { it.value },
            "(raw tokens, tokens after A12's expand)",
        )
    }

    /** `checkConsistency` on the deserialized model, never expanded. */
    private fun raw(json: String): List<String> = try {
        val model = KernelModelInput.deserialize(FACTORY.createDocumentModelSerializer(), json)
        buildList {
            FACTORY.createDocumentModelService().checkConsistency(model) {
                if (it.severity.isAtLeastAsSevereAs(Severity.ERROR)) add(it.message.trim())
            }
        }
    } catch (couldNotBuild: IllegalArgumentException) {
        UNBUILT
    }

    /** The adapter's path: A12's expand, then the same check. */
    private fun expanded(json: String): List<String> = try {
        KernelModelPreparation.expand(json, ReferenceResolvers.selfContained()).notifications()
            .filter { it.severity() == io.github.mbackschat.a12.dm.core.Severity.ERROR }.map { it.message().trim() }
    } catch (couldNotBuild: ConsistencyCheckFailedException) {
        UNBUILT
    }

    private fun range(format: String, separator: String) = withStart(
        """{"type":"DateRangeType","DateRangeType":{"format":"$format","rangeSeparator":"$separator"}}""",
    )

    /** The policy model with its `Start` field's type replaced by [fieldType]. */
    private fun withStart(fieldType: String): String {
        val root = MAPPER.readTree(BASE) as ObjectNode
        val start = root.at("/content/modelRoot/rootGroups/0/Group/elements").single { it["name"].asText() == "Start" }
        (start["Field"] as ObjectNode).set<ObjectNode>("fieldType", MAPPER.readTree(fieldType))
        return MAPPER.writeValueAsString(root)
    }

    private companion object {
        val FACTORY = DocumentModelServiceFactory()
        val MAPPER = ObjectMapper()
        val ADMITTED = emptyList<String>()
        val UNBUILT = listOf("NOT BUILDABLE")
        val BASE: String = DocumentModelDraft.named("policy-dates").locale("en_US")
            .rootGroup("Policy") { p -> p.stringField("Number") { }.dateField("Start") { d -> d.format("yyyy-MM-dd") } }
            .build().toJson()
    }
}
