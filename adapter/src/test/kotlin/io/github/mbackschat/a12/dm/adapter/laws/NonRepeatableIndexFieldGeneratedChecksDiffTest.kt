package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.signatures
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What an index field nominated in a NON-repeatable group arms, the acceptance of a12-kernel-lean
 * SPEC-2026-10-26-24 ([KERNEL-FINDINGS KF638]). The mandatory arm fires on an empty key in the holder and in a
 * cover's detail alike, while the same keys left unnominated draw nothing; the unique arm has no sibling to
 * compare, so two covers whose details share a key draw nothing. Every row is three-engine agreed.
 *
 * Own domain (an insurance claim's covers).
 */
class NonRepeatableIndexFieldGeneratedChecksDiffTest {

    private val model = admitted(DocumentModelDraft.named("claim-cover-details").locale("en_US")
        .rootGroup("Claim") { c -> c.stringField("Ref") { }
            .group("Holder") { h -> h.stringField("HolderKey") { }.stringField("Name") { }.indexField("HolderKey") }
            .group("Covers") { v -> v.repeatable(3).numberField("Amount") { it.fractionDigits(0) }
                .group("Detail") { d -> d.stringField("DetailKey") { }.stringField("Note") { }.indexField("DetailKey") } } }
        .build().toJson())

    @Test
    fun theMandatoryArmChecksANonRepeatableIndexField() {
        val observed = linkedMapOf(
            "holder without key" to """{"Claim":{"Ref":"R","Holder":{"Name":"N"}}}""",
            "detail without key" to """{"Claim":{"Ref":"R","Covers":[{"Amount":1,"Detail":{"Note":"x"}}]}}""",
            "two details sharing a key" to """{"Claim":{"Ref":"R","Covers":[{"Amount":1,"Detail":{"DetailKey":"a"}},""" +
                """{"Amount":2,"Detail":{"DetailKey":"a"}}]}}""",
            "a cover without a detail" to """{"Claim":{"Ref":"R","Covers":[{"Amount":1}]}}""",
        ).mapValues { (_, document) -> signatures(model, document) }
        assertEquals(linkedMapOf(
            "holder without key" to listOf("mandatoryField|OMISSION_ERROR|/Claim[1]/Holder[1]/HolderKey"),
            "detail without key" to listOf("mandatoryField|OMISSION_ERROR|/Claim[1]/Covers[1]/Detail[1]/DetailKey"),
            "two details sharing a key" to emptyList(),
            "a cover without a detail" to emptyList(),
        ), observed)
    }

    @Test
    fun theSameKeysUnnominatedDrawNothing() {
        val plain = admitted(DocumentModelDraft.named("claim-cover-details").locale("en_US")
            .rootGroup("Claim") { c -> c.stringField("Ref") { }
                .group("Holder") { h -> h.stringField("HolderKey") { }.stringField("Name") { } }
                .group("Covers") { v -> v.repeatable(3).numberField("Amount") { it.fractionDigits(0) }
                    .group("Detail") { d -> d.stringField("DetailKey") { }.stringField("Note") { } } } }
            .build().toJson())
        val observed = linkedMapOf(
            "holder without key" to """{"Claim":{"Ref":"R","Holder":{"Name":"N"}}}""",
            "detail without key" to """{"Claim":{"Ref":"R","Covers":[{"Amount":1,"Detail":{"Note":"x"}}]}}""",
        ).mapValues { (_, document) -> signatures(plain, document) }
        assertEquals(mapOf("holder without key" to emptyList<String>(), "detail without key" to emptyList()), observed)
    }
}
