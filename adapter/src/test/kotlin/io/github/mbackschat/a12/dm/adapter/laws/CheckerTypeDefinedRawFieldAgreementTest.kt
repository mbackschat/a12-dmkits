package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.SortOrder
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A field that waives value validation through a TYPE DEFINITION, against one that waives it in its own
 * declaration, on every operand gate that refuses such a field (KERNEL-FINDINGS KF602).
 *
 * Each row is written twice on one model, the two differing only in which of the two fields it reads, so the
 * pair is a within-model control: A12 answers both alike, and the checker must too.
 *
 * Own domain: an insurance claim's free-text notes and the attachments filed with it.
 */
class CheckerTypeDefinedRawFieldAgreementTest {

    private val claim = DocumentModelDraft.named("claim-typed-raw").locale("en_US")
        .typeDef("FreeText") { it.string { s -> s.noValueValidation().lineBreaks() } }
        .rootGroup("Claim") { c -> c
            .stringField("Note") { s -> s.noValueValidation().lineBreaks() }
            .typedefField("NoteDef", "FreeText")
            .stringField("Other") { }
            .group("Attachments") { a -> a.repeatable(5)
                .stringField("Blob") { s -> s.noValueValidation().lineBreaks() }
                .typedefField("BlobDef", "FreeText")
                .stringField("Name") { } } }
        .build().toJson()

    private val validator = RuleValidator()

    private fun judge(errorField: String, condition: String, message: String): Pair<List<String>, List<String>> {
        val draft = DraftRule.builder().groupPath("/Claim").name("RawProbe").errorEntityRelPath("../$errorField")
            .errorCondition(condition).errorCode("PROBE").severity("ERROR").messages(listOf(Dsl.en(message))).build()
        val result = validator.check(claim, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = check(ModelSource(validator.inject(claim, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    /** Each probe over the [note] and [blob] fields, the declaration-raw pair or its type-defined twins. */
    private fun verdicts(note: String, blob: String) = PROBES.mapValues { (_, probe) ->
        val condition = probe.condition.replace("NOTE", note).replace("BLOB", blob)
        // The error field is read too, so no row is answered by the reference gate instead of the one it probes.
        judge(probe.errorField.replace("BLOB", blob),
            if (probe.errorField == "Other") "FieldFilled(Other) And $condition" else condition,
            probe.message.replace("NOTE", note))
    }

    @Test
    fun a12AnswersBothFieldsAlikeAsPinned() {
        val direct = verdicts("Note", "Blob").mapValues { it.value.first }
        assertEquals(PROBES.mapValues { it.value.a12 }, direct)
        assertEquals(direct, verdicts("NoteDef", "BlobDef").mapValues { it.value.first }, "the type-defined twin")
    }

    @Test
    fun theCheckerNamesA12sClassOnTheDeclarationRawField() {
        val verdicts = verdicts("Note", "Blob")
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    @Test
    fun theCheckerNamesA12sClassOnTheTypeDefinedField() {
        val verdicts = verdicts("NoteDef", "BlobDef")
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    /**
     * A group's ordering key, its index field or a sort field, naming the declaration-raw field or its twin: the two
     * models differ only in that one name, and A12 refuses both alike.
     */
    @Test
    fun anOrderingKeyTakesTheWaiverFromItsTypeDefinition() {
        val verdicts = listOf("Blob", "BlobDef").flatMap { field ->
            listOf("index" to { a: GroupBuilder -> a.indexField(field) },
                "sort" to { a: GroupBuilder -> a.sortField(field, SortOrder.ASC) }).map { (member, key) ->
                "$member $field" to ordered(key).let { model -> kernel(model) to ours(model) }
            }
        }.toMap()
        val a12 = verdicts.mapValues { it.value.first }
        assertEquals(mapOf("index Blob" to listOf("MVK_INDEX_FIELD_NO_VALUE_VALIDATION"),
            "sort Blob" to listOf("MVK_SORT_FIELD_NO_VALUE_VALIDATION"),
            "index BlobDef" to listOf("MVK_INDEX_FIELD_NO_VALUE_VALIDATION"),
            "sort BlobDef" to listOf("MVK_SORT_FIELD_NO_VALUE_VALIDATION")), a12)
        assertEquals(a12, verdicts.mapValues { it.value.second })
    }

    private fun ordered(key: (GroupBuilder) -> GroupBuilder) = DocumentModelDraft.named("claim-typed-raw-key")
        .locale("en_US")
        .typeDef("FreeText") { it.string { s -> s.noValueValidation().lineBreaks() } }
        .rootGroup("Claim") { c -> c
            .stringField("Other") { }
            .group("Attachments") { a -> key(a.repeatable(5)
                .stringField("Blob") { s -> s.noValueValidation().lineBreaks() }
                .typedefField("BlobDef", "FreeText")
                .stringField("Name") { }) } }
        .build().toJson()

    private fun kernel(json: String) =
        KernelConsistencyOracle().checkConsistency(json, ReferenceResolvers.selfContained())
            .map { it.message().trim().let { m -> Regex("\\[(\\w+)]$").find(m)?.groupValues?.get(1) ?: "codeless: $m" } }
            .sorted()

    private fun ours(json: String) = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.sorted()

    private class Probe(val condition: String, val a12: List<String>, val message: String = "Check the claim.",
        val errorField: String = "Other")

    private companion object {
        const val RAW = "MVK_INVALID_RAW_TYPE"

        val PROBES: Map<String, Probe> = linkedMapOf(
            "comparison" to Probe("[NOTE] == \"x\"", listOf(RAW)),
            "length beside another condition" to
                Probe("Length(NOTE) > 5", listOf("MVK_INVALID_LENGTH_OF_RAW_TYPE")),
            "uniqueness" to Probe("FieldValuesNotUnique(Attachments*/BLOB)", listOf(RAW)),
            "value-in-fields text constant" to Probe("NumberOfValueInFields(\"x\" In NOTE, Other) > 0", listOf(RAW)),
            "distinct values" to Probe("NumberOfDifferentValues(NOTE, Other) > 0", listOf(RAW)),
            "predefined type" to Probe("Valid(NOTE, \"IBAN\")", listOf(RAW)),
            "value-list field" to Probe("AtLeastOneFieldValueIncludedInValueList(NOTE In \"a\")", listOf(RAW)),
            "value-list value" to Probe("AtLeastOneFieldValueIncludedInValueList(Other In NOTE)", listOf(RAW)),
            "repetition key" to Probe("RepetitionNotUnique(Attachments/BLOB)", listOf(RAW), errorField = "Attachments/BLOB"),
            "range operand" to Probe("RangeAsString(NOTE, 1, 2) == \"a\"", listOf(RAW)),
            "scalar value list" to Probe("FieldValueIncludedInValueList(NOTE, \"a\")", listOf(RAW)),
            "message value" to Probe("FieldFilled(NOTE)", listOf("INVALID_NO_VALUE_VALIDATION"), "Check \$NOTE.value\$."),
            "presence, the control" to Probe("FieldFilled(NOTE)", listOf()),
        )
    }
}
