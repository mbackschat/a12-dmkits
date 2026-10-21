package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM + Node peers of three kernel-oracled readings of check relevance, each a row its `:adapter` differential pins
 * against both A12 strategies: a required-empty cell is unavailable to an authored read (IF394, KERNEL-FINDINGS
 * KF552, `RequiredEmptyReadDiffTest`), a partial validation runs a rule on every declared slot its selection covers
 * (IF395, KF553, `PartialDeclaredRowDomainDiffTest`), and a field-valued key that fails its own declaration names
 * no row (KF549, `SelectedErrorReanchorEligibilityDiffTest`). Each has its control on the other side of the gate.
 */
class CheckRelevanceReadMultiplatformTest {

    /** The required street is unavailable, so neither read of it fires; the optional lane fires both. */
    @Test
    fun aRequiredEmptyCellIsUnavailableToAuthoredReads() {
        val model = ModelDef(
            fields = listOf(FieldDef(CITY, Kind.STRING), FieldDef(STREET, Kind.STRING, required = Requiredness.RELATIVE_TO_PARENT),
                FieldDef(LANE, Kind.STRING)),
            rules = listOf("Street", "Lane").flatMap { name -> listOf(
                RuleDef("$HOLDER/${name}NotFilled", HOLDER, "FieldNotFilled($name)", "${name}NotFilled", "../$name"),
                RuleDef("$HOLDER/${name}Count", HOLDER, "NumberOfFilledFields($name, City) < 2", "${name}Count", "../$name"),
            ) },
        )
        val document = model.newDocument().group(CLAIM, 1).group(HOLDER, 1, 1).field(CITY, "Linz", 1, 1, 1)
        assertEquals(listOf("LaneCount", "LaneNotFilled", "mandatoryField"),
            DmInterpreter(model).validateFull(document).map { it.code }.sorted())
    }

    /** No unit is held, yet every selected slot runs the rule; a selection covering no unit runs none. */
    @Test
    fun aPartialValidationRunsEveryDeclaredSlotItsSelectionCovers() {
        val model = ModelDef(
            fields = listOf(FieldDef(TENANT, Kind.STRING), FieldDef(RENT, Kind.NUMBER, scale = 0)),
            rules = listOf(RuleDef("$UNITS/RentFloor", UNITS, "Rent < 100", "RENT_FLOOR", "../Rent")),
            declaredReps = mapOf(UNITS to 3),
        )
        val document = model.newDocument().group(LEASE, 1).field(TENANT, "T", 1, 1)
        fun part(selected: RelevantEntity) =
            DmInterpreter(model).validatePart(document, setOf(selected)).messages.map { it.errorPath }.sorted()
        assertEquals((1..3).map { "/Lease[1]/Units[$it]/Rent" }, part(RelevantEntity.anyRep(RENT)))
        assertEquals(listOf("/Lease[1]/Units[2]/Rent"), part(RelevantEntity(RENT, listOf(0, 2, 0))))
        assertEquals(emptyList(), part(RelevantEntity(TENANT, listOf(0, 0))))
    }

    /**
     * A key field holding `bb` names the bay keyed `bb` while it is valid; declared one character long, the same text
     * is formally invalid, so the key reads empty and the address names A12's unknown row.
     */
    @Test
    fun aFormallyInvalidFieldKeyNamesNoRow() {
        fun model(keyLength: Int?) = ModelDef(
            fields = listOf(FieldDef(KEY, Kind.STRING, maxLength = keyLength), FieldDef(NOTE, Kind.STRING),
                FieldDef(BAY_CODE, Kind.STRING), FieldDef(LOAD, Kind.NUMBER, scale = 0)),
            rules = listOf(RuleDef("$DEPOT/OneKey", DEPOT, "FieldFilled(Note) Or [Bays/Load For Short] > 0", "OneKey", "../Bays/Load")),
            declaredReps = mapOf(BAYS to 4),
            indexFields = setOf(BAY_CODE),
        )
        fun address(keyLength: Int?): List<String> {
            val def = model(keyLength)
            val document = def.newDocument().group(DEPOT, 1).field(KEY, "bb", 1, 1).field(NOTE, "n", 1, 1)
                .group(BAYS, 1, 1).field(BAY_CODE, "a", 1, 1, 1).field(LOAD, "1", 1, 1, 1)
                .group(BAYS, 1, 2).field(BAY_CODE, "bb", 1, 2, 1).field(LOAD, "2", 1, 2, 1)
            return DmInterpreter(def).validateFull(document).filter { it.code == "OneKey" }.map { it.errorPath }
        }
        assertEquals(listOf("/Depot[1]/Bays[2]/Load"), address(keyLength = null), "a valid key")
        assertEquals(listOf("/Depot[1]/Bays[-5]/Load"), address(keyLength = 1), "a formally invalid key")
    }

    private companion object {
        const val CLAIM = "/Claim"
        const val HOLDER = "/Claim/Holder"
        const val CITY = "/Claim/Holder/City"
        const val STREET = "/Claim/Holder/Street"
        const val LANE = "/Claim/Holder/Lane"
        const val LEASE = "/Lease"
        const val TENANT = "/Lease/Tenant"
        const val UNITS = "/Lease/Units"
        const val RENT = "/Lease/Units/Rent"
        const val DEPOT = "/Depot"
        const val KEY = "/Depot/Short"
        const val NOTE = "/Depot/Note"
        const val BAYS = "/Depot/Bays"
        const val BAY_CODE = "/Depot/Bays/BayCode"
        const val LOAD = "/Depot/Bays/Load"
    }
}
