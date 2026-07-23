package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for the FIXED group-list quantifiers over the common field-or-group entity list
 * (SPEC-2026-07-23-09). Despite their names, all five admit FIELD operands: a checked field
 * contributes filled / empty / unavailable (formally invalid or not relevant) directly — the kernel's
 * three-way `isWertAngegeben` state — while a group operand contributes the IF193 product state. The
 * five consume one partial tally (pₜ definitely filled, pₑ definitely empty, pᵤ neither) without
 * treating unavailable as empty; a decisive witness fires beside an unavailable sibling; and a fixed
 * singleton has exactly the scalar field/group presence semantics.
 */
class GroupListEntityOperandDiffTest {

    private val base = DocumentModelDraft.named("claimintake").locale("en_US")
        .rootGroup("Claim") { claim ->
            claim.stringField("Witness") { }
            claim.stringField("PolicyNumber") { }
            claim.stringField("IncidentNote") { }
            claim.numberField("DeductibleRate") { it.fractionDigits(1, 1) }
            claim.group("Vehicle") { vehicle ->
                vehicle.stringField("Vin") { }
                vehicle.numberField("AxleCount") { it.fractionDigits(1, 1) }
            }
        }
        .build()
        .toJson()

    // Conditions are authored with the rulekit typed DSL: a checked field enters the entity list as a
    // FieldRef (`m.field`), a group scope as a GroupListRef (`m.flattenGroup` — bare path, no star).
    private val m = BoundModel.ofJson(base)
    private val witness = m.str(WITNESS)
    private val rules = mapOf(
        ALL_MIXED to Dsl.allGroupsFilled(m.field(POLICY_NUMBER), m.flattenGroup(VEHICLE)),
        NO_MIXED to Dsl.noGroupFilled(m.field(POLICY_NUMBER), m.flattenGroup(VEHICLE)),
        ONE_MIXED to Dsl.atLeastOneGroupFilled(m.field(POLICY_NUMBER), m.flattenGroup(VEHICLE)),
        NOT_ALL_MIXED to Dsl.notAllGroupsFilled(m.field(POLICY_NUMBER), m.flattenGroup(VEHICLE)),
        SPLIT_MIXED to Dsl.groupsNotCollectivelyFilled(m.field(POLICY_NUMBER), m.flattenGroup(VEHICLE)),
        ONE_RATE to Dsl.atLeastOneGroupFilled(m.field(DEDUCTIBLE_RATE), m.flattenGroup(VEHICLE)),
        NOT_ALL_RATE to Dsl.notAllGroupsFilled(m.field(DEDUCTIBLE_RATE), m.flattenGroup(VEHICLE)),
        NO_RATE to Dsl.noGroupFilled(m.field(DEDUCTIBLE_RATE), m.flattenGroup(VEHICLE)),
        ALL_FIELDS to Dsl.allGroupsFilled(m.field(POLICY_NUMBER), m.field(INCIDENT_NOTE)),
        SPLIT_FIELDS to Dsl.groupsNotCollectivelyFilled(m.field(POLICY_NUMBER), m.field(INCIDENT_NOTE)),
        SINGLE_NO_FIELD to Dsl.noGroupFilled(m.field(DEDUCTIBLE_RATE)),
        SCALAR_NOT_FILLED to m.field(DEDUCTIBLE_RATE).notFilled(),
        SINGLE_ONE_FIELD to Dsl.atLeastOneGroupFilled(m.field(DEDUCTIBLE_RATE)),
        SCALAR_FILLED to m.field(DEDUCTIBLE_RATE).filled(),
        SINGLE_NO_GROUP to Dsl.noGroupFilled(m.group(VEHICLE)),
        SCALAR_GROUP_NOT_FILLED to m.group(VEHICLE).notFilled(),
    )
    private val modelJson = rules.entries.fold(base) { json, (code, condition) ->
        TypedRules.inject(json, rule(code, condition))
    }

    private fun rule(code: String, condition: Condition) = m.rule(m.group(ROOT)).name(code).code(code)
        .errorField(witness).`when`(witness.filled().and(condition)).severity(Severity.ERROR)
        .message(Dsl.en("Entity-list tally outcome.")).build()
    private val definition = ModelLoader.load(modelJson)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(modelJson)

    // --- mixed field/group lists across the plain fill states -----------------------------------

    @Test fun mixedListBothPresent() = agree(
        mapOf(POLICY_NUMBER to "P-100", VIN to "WAU12345"), MIXED_CODES,
        setOf("$ALL_MIXED|VALUE_ERROR", "$ONE_MIXED|VALUE_ERROR"),
        "field filled + vehicle filled",
    )

    @Test fun mixedListBothEmpty() = agree(
        emptyMap(), MIXED_CODES,
        setOf("$NO_MIXED|OMISSION_ERROR", "$NOT_ALL_MIXED|OMISSION_ERROR"),
        "field empty + vehicle empty",
    )

    @Test fun mixedListFieldOnlyFilled() = agree(
        mapOf(POLICY_NUMBER to "P-100"), MIXED_CODES,
        setOf("$ONE_MIXED|VALUE_ERROR", "$NOT_ALL_MIXED|OMISSION_ERROR", "$SPLIT_MIXED|OMISSION_ERROR"),
        "field filled + vehicle empty",
    )

    @Test fun mixedListGroupOnlyFilled() = agree(
        mapOf(VIN to "WAU12345"), MIXED_CODES,
        setOf("$ONE_MIXED|VALUE_ERROR", "$NOT_ALL_MIXED|OMISSION_ERROR", "$SPLIT_MIXED|OMISSION_ERROR"),
        "field empty + vehicle filled",
    )

    // --- an unavailable operand joins neither bucket; decisive witnesses still fire -------------

    /** Malformed field beside a filled group: the positive witness fires the ≥1 form; the invalid
     *  operand supplies no empty witness to `NotAll`. */
    @Test fun invalidFieldOperandBesideFilledGroup() = agree(
        mapOf(DEDUCTIBLE_RATE to "7", VIN to "WAU12345"), RATE_CODES,
        setOf("$ONE_RATE|VALUE_ERROR"),
        "invalid rate + vehicle filled",
    )

    /** Malformed field beside an empty group: the decisive empty witness fires `NotAll`, while the
     *  unavailable operand blocks `NoGroupFilled` — unavailable is NOT empty. */
    @Test fun invalidFieldOperandBesideEmptyGroup() = agree(
        mapOf(DEDUCTIBLE_RATE to "7"), RATE_CODES,
        setOf("$NOT_ALL_RATE|OMISSION_ERROR"),
        "invalid rate + vehicle empty",
    )

    @Test fun validFieldOperandBesideEmptyGroup() = agree(
        mapOf(DEDUCTIBLE_RATE to "1.5"), RATE_CODES,
        setOf("$ONE_RATE|VALUE_ERROR", "$NOT_ALL_RATE|OMISSION_ERROR"),
        "valid rate + vehicle empty",
    )

    /** Malformed-only GROUP content beside a decisively filled field: the field fires the ≥1 form,
     *  while the erroneous group joins neither bucket of the mixed pair tallies. */
    @Test fun invalidOnlyGroupBesideFilledField() = agree(
        mapOf(POLICY_NUMBER to "P-100", AXLE_COUNT to "3"), MIXED_CODES,
        setOf("$ONE_MIXED|VALUE_ERROR"),
        "field filled + vehicle invalid-only",
    )

    // --- field-only lists ------------------------------------------------------------------------

    @Test fun fieldOnlyListBothFilled() = agree(
        mapOf(POLICY_NUMBER to "P-100", INCIDENT_NOTE to "rear-ended at crossing"), FIELD_ONLY_CODES,
        setOf("$ALL_FIELDS|VALUE_ERROR"),
        "both field operands filled",
    )

    @Test fun fieldOnlyListSplit() = agree(
        mapOf(INCIDENT_NOTE to "rear-ended at crossing"), FIELD_ONLY_CODES,
        setOf("$SPLIT_FIELDS|OMISSION_ERROR"),
        "note filled + policy number empty",
    )

    // --- fixed singletons have exactly the scalar presence semantics ----------------------------

    @Test fun singletonFieldEmptyMatchesScalarPresence() = agree(
        emptyMap(), SINGLE_FIELD_CODES,
        setOf("$SINGLE_NO_FIELD|OMISSION_ERROR", "$SCALAR_NOT_FILLED|OMISSION_ERROR"),
        "singleton field empty",
    )

    @Test fun singletonFieldFilledMatchesScalarPresence() = agree(
        mapOf(DEDUCTIBLE_RATE to "1.5"), SINGLE_FIELD_CODES,
        setOf("$SINGLE_ONE_FIELD|VALUE_ERROR", "$SCALAR_FILLED|VALUE_ERROR"),
        "singleton field filled",
    )

    @Test fun singletonFieldInvalidMatchesScalarPresence() = agree(
        mapOf(DEDUCTIBLE_RATE to "7"), SINGLE_FIELD_CODES,
        emptySet(),
        "singleton field malformed — all four silent",
    )

    @Test fun singletonGroupEmptyMatchesScalarPresence() = agree(
        emptyMap(), SINGLE_GROUP_CODES,
        setOf("$SINGLE_NO_GROUP|OMISSION_ERROR", "$SCALAR_GROUP_NOT_FILLED|OMISSION_ERROR"),
        "singleton group empty",
    )

    @Test fun singletonGroupFilledMatchesScalarPresence() = agree(
        mapOf(VIN to "WAU12345"), SINGLE_GROUP_CODES,
        emptySet(),
        "singleton group filled — both negative forms silent",
    )

    @Test fun singletonGroupInvalidOnlyMatchesScalarPresence() = agree(
        mapOf(AXLE_COUNT to "3"), SINGLE_GROUP_CODES,
        emptySet(),
        "singleton group malformed-only — neither definitely empty",
    )

    // --- partial validation: a non-relevant FIELD operand is unavailable ------------------------

    @Test fun aNonRelevantFieldOperandCannotProveAllFilled() = agreePartial(
        values = mapOf(POLICY_NUMBER to "P-100"),
        kernelRelevant = setOf(Relevant.of(WITNESS, 0, 0), Relevant.of(POLICY_NUMBER, 0, 0)),
        interpreterRelevant = setOf(RelevantEntity.anyRep(WITNESS), RelevantEntity.anyRep(POLICY_NUMBER)),
        expected = emptySet(),
        scenario = "note not relevant — neither a filled nor an empty witness",
    )

    @Test fun aRelevantEmptyFieldOperandStillWitnessesTheSplit() = agreePartial(
        values = mapOf(POLICY_NUMBER to "P-100"),
        kernelRelevant = setOf(
            Relevant.of(WITNESS, 0, 0), Relevant.of(POLICY_NUMBER, 0, 0), Relevant.of(INCIDENT_NOTE, 0, 0),
        ),
        interpreterRelevant = setOf(
            RelevantEntity.anyRep(WITNESS), RelevantEntity.anyRep(POLICY_NUMBER), RelevantEntity.anyRep(INCIDENT_NOTE),
        ),
        expected = setOf("$SPLIT_FIELDS|OMISSION_ERROR"),
        scenario = "both operands relevant, one empty",
    )

    private fun agree(values: Map<String, String>, codes: Set<String>, expected: Set<String>, scenario: String) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).group(VEHICLE, 1, 1).field(WITNESS, "yes", 1, 1)
        values.forEach { (path, value) -> kernelDocument.field(path, value, *repsOf(path)) }
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).signatures(codes)
        val generated = javaEngine.messages(javaEngine.instance(kernelDocument)).signatures(codes)

        val interpreterDocument = definition.newDocument().group(ROOT, 1).group(VEHICLE, 1, 1)
            .field(WITNESS, "yes", 1, 1)
        values.forEach { (path, value) -> interpreterDocument.field(path, value, *repsOf(path)) }
        val interpreted = DmInterpreter(definition).validateFull(interpreterDocument).interpreterSignatures(codes)

        assertEquals(expected, dynamic, "$scenario — normative dynamic-Groovy")
        assertEquals(dynamic, generated, "$scenario — generated Java")
        assertEquals(dynamic, interpreted, "$scenario — interpreter")
    }

    private fun agreePartial(
        values: Map<String, String>,
        kernelRelevant: Set<Relevant>,
        interpreterRelevant: Set<RelevantEntity>,
        expected: Set<String>,
        scenario: String,
    ) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(WITNESS, "yes", 1, 1)
        values.forEach { (path, value) -> kernelDocument.field(path, value, *repsOf(path)) }
        val interpreterDocument = definition.newDocument().group(ROOT, 1).field(WITNESS, "yes", 1, 1)
        values.forEach { (path, value) -> interpreterDocument.field(path, value, *repsOf(path)) }

        val dynamic = RuntimeLaws.messagesPart(modelJson, kernelDocument, kernelRelevant).signatures(FIELD_ONLY_CODES)
        val generated = javaEngine.messagesPart(kernelDocument, kernelRelevant).signatures(FIELD_ONLY_CODES)
        val interpreted = DmInterpreter(definition).validatePart(interpreterDocument, interpreterRelevant)
            .messages.interpreterSignatures(FIELD_ONLY_CODES)

        assertEquals(expected, dynamic, "$scenario — normative dynamic-Groovy")
        assertEquals(dynamic, generated, "$scenario — generated Java")
        assertEquals(dynamic, interpreted, "$scenario — interpreter")
    }

    private fun repsOf(path: String): IntArray =
        if (path.startsWith("$VEHICLE/")) intArrayOf(1, 1, 1) else intArrayOf(1, 1)

    private fun List<RuntimeLaws.Msg>.signatures(codes: Set<String>): Set<String> =
        filter { it.code() in codes }.mapTo(sortedSetOf()) { "${it.code()}|${it.type().name}" }

    private fun List<io.github.mbackschat.a12.dm.interpreter.model.Message>.interpreterSignatures(
        codes: Set<String>,
    ): Set<String> = filter { it.code in codes }.mapTo(sortedSetOf()) {
        "${it.code}|${if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"}"
    }

    private companion object {
        const val ROOT = "/Claim"
        const val WITNESS = "$ROOT/Witness"
        const val POLICY_NUMBER = "$ROOT/PolicyNumber"
        const val INCIDENT_NOTE = "$ROOT/IncidentNote"
        const val DEDUCTIBLE_RATE = "$ROOT/DeductibleRate"
        const val VEHICLE = "$ROOT/Vehicle"
        const val VIN = "$VEHICLE/Vin"
        const val AXLE_COUNT = "$VEHICLE/AxleCount"
        const val ALL_MIXED = "ALL_MIXED"
        const val NO_MIXED = "NO_MIXED"
        const val ONE_MIXED = "ONE_MIXED"
        const val NOT_ALL_MIXED = "NOT_ALL_MIXED"
        const val SPLIT_MIXED = "SPLIT_MIXED"
        const val ONE_RATE = "ONE_RATE"
        const val NOT_ALL_RATE = "NOT_ALL_RATE"
        const val NO_RATE = "NO_RATE"
        const val ALL_FIELDS = "ALL_FIELDS"
        const val SPLIT_FIELDS = "SPLIT_FIELDS"
        const val SINGLE_NO_FIELD = "SINGLE_NO_FIELD"
        const val SCALAR_NOT_FILLED = "SCALAR_NOT_FILLED"
        const val SINGLE_ONE_FIELD = "SINGLE_ONE_FIELD"
        const val SCALAR_FILLED = "SCALAR_FILLED"
        const val SINGLE_NO_GROUP = "SINGLE_NO_GROUP"
        const val SCALAR_GROUP_NOT_FILLED = "SCALAR_GROUP_NOT_FILLED"
        val MIXED_CODES = setOf(ALL_MIXED, NO_MIXED, ONE_MIXED, NOT_ALL_MIXED, SPLIT_MIXED)
        val RATE_CODES = setOf(ONE_RATE, NOT_ALL_RATE, NO_RATE)
        val FIELD_ONLY_CODES = setOf(ALL_FIELDS, SPLIT_FIELDS)
        val SINGLE_FIELD_CODES = setOf(SINGLE_NO_FIELD, SCALAR_NOT_FILLED, SINGLE_ONE_FIELD, SCALAR_FILLED)
        val SINGLE_GROUP_CODES = setOf(SINGLE_NO_GROUP, SCALAR_GROUP_NOT_FILLED)
    }
}
