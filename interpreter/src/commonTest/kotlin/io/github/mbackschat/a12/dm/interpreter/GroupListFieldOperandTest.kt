package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the FIXED group-list quantifiers over the common field-or-group
 * entity list (SPEC-2026-07-23-09): a checked FIELD operand contributes filled / empty / unavailable
 * directly (the shared `presenceTri` — a malformed value joins NEITHER bucket), a GROUP operand the
 * IF193 product state, and a decisive witness fires beside an unavailable sibling. A fixed singleton
 * has the scalar field-presence semantics. The kernel differential (both strategies) is
 * `adapter.laws.GroupListEntityOperandDiffTest`.
 */
class GroupListFieldOperandTest {

    // /Claim: Witness (row gate), Rate (NUMBER, exactly one fraction digit — "7" is malformed),
    // a non-repeatable Vehicle group with a Vin member.
    private fun model(cond: String) = ModelDef(
        fields = listOf(
            FieldDef("/Claim/Witness", Kind.STRING),
            FieldDef("/Claim/Rate", Kind.NUMBER, scale = 1, minFractionalDigits = 1),
            FieldDef("/Claim/Vehicle/Vin", Kind.STRING),
        ),
        rules = listOf(RuleDef("/Claim/R", "/Claim", "FieldFilled(Witness) And $cond", "GL", "../Witness")),
    )

    private fun doc(m: ModelDef, rate: String?, vin: String?): Document =
        m.newDocument().group("/Claim", 1).group("/Claim/Vehicle", 1, 1)
            .field("/Claim/Witness", "yes", 1, 1).also { d ->
                if (rate != null) d.field("/Claim/Rate", rate, 1, 1)
                if (vin != null) d.field("/Claim/Vehicle/Vin", vin, 1, 1, 1)
            }

    private fun sig(cond: String, rate: String? = null, vin: String? = null): String? {
        val m = model(cond)
        return DmInterpreter(m).validateFull(doc(m, rate, vin)).firstOrNull { it.code == "GL" }
            ?.let { if (it.type == MsgType.OMISSION) "OMISSION" else "VALUE" }
    }

    // --- mixed field/group tallies ---------------------------------------------------------------

    @Test fun allFiresWhenFieldAndGroupAreFilled() =
        assertEquals("VALUE", sig("AllGroupsFilled(Rate, Vehicle)", rate = "1.5", vin = "V1"))

    @Test fun allDoesNotFireOnAnEmptyField() =
        assertEquals(null, sig("AllGroupsFilled(Rate, Vehicle)", vin = "V1"))

    @Test fun noneFiresWhenBothAreEmpty() =
        assertEquals("OMISSION", sig("NoGroupFilled(Rate, Vehicle)"))

    @Test fun collectivelyFiresOnAFieldGroupSplit() =
        assertEquals("OMISSION", sig("GroupsNotCollectivelyFilled(Rate, Vehicle)", rate = "1.5"))

    // --- a malformed FIELD operand is unavailable: neither bucket, decisive witnesses still fire --

    @Test fun aMalformedFieldBlocksNone() =
        assertEquals(null, sig("NoGroupFilled(Rate, Vehicle)", rate = "7"))

    @Test fun aMalformedFieldSuppliesNoEmptyWitnessToNotAll() =
        assertEquals(null, sig("NotAllGroupsFilled(Rate, Vehicle)", rate = "7", vin = "V1"))

    @Test fun aFilledGroupIsDecisiveBesideAMalformedField() =
        assertEquals("VALUE", sig("AtLeastOneGroupFilled(Rate, Vehicle)", rate = "7", vin = "V1"))

    @Test fun anEmptyGroupIsDecisiveBesideAMalformedField() =
        assertEquals("OMISSION", sig("NotAllGroupsFilled(Rate, Vehicle)", rate = "7"))

    // --- field-only lists ------------------------------------------------------------------------

    @Test fun fieldOnlyAllFires() =
        assertEquals("VALUE", sig("AllGroupsFilled(Witness, Rate)", rate = "1.5"))

    @Test fun fieldOnlySplitFires() =
        assertEquals("OMISSION", sig("GroupsNotCollectivelyFilled(Witness, Rate)"))

    // --- fixed singletons have the scalar field-presence semantics ------------------------------

    @Test fun singletonNoGroupFilledMatchesFieldNotFilled() {
        for (rate in listOf(null, "1.5", "7")) {
            assertEquals(
                sig("FieldNotFilled(Rate)", rate = rate),
                sig("NoGroupFilled(Rate)", rate = rate),
                "NoGroupFilled(F) ≡ FieldNotFilled(F) at rate=$rate",
            )
        }
    }

    @Test fun singletonAtLeastOneGroupFilledMatchesFieldFilled() {
        for (rate in listOf(null, "1.5", "7")) {
            assertEquals(
                sig("FieldFilled(Rate)", rate = rate),
                sig("AtLeastOneGroupFilled(Rate)", rate = rate),
                "AtLeastOneGroupFilled(F) ≡ FieldFilled(F) at rate=$rate",
            )
        }
    }
}
