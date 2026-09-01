package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — the **VALIDATION POLARITY** of a fired comparison against a filled-entity COUNT
 * over an unstarred, non-repeatable (fixed) GROUP operand. The value-route sibling is
 * `FixedGroupValueInFieldsComputeDiffTest`.
 *
 * **Why this file exists beside that one.** a12-kernel-lean's SPEC-2026-08-24-05 measured the COMPUTATION route
 * and explicitly left validation unmeasured, so fixing the value walk answered nothing about the fill walk. The
 * fill walk is a separate mechanism, and its group arm is the documented shape of [IF287]: an unstarred group
 * operand is not an `Expr.Star`, so `countFill` finds no filtered operand and falls back to `leafOmission`,
 * whose `!filled(<group path>)` is TRUE for **every** group — a group holds no value of its own. That predicted
 * a permanently growable count regardless of the group's contents, and the FULL-group row below confirmed it:
 * both kernel strategies answer VALUE, the interpreter answered OMISSION.
 *
 * **The discriminator is a FULL group; the partially-filled row is the control.** With both declarations filled
 * nothing remains to fill, so an expansion-aware fill answers not-growable (VALUE) while the group-path read
 * answers growable (OMISSION). With one cell open both accounts agree on growable, which is what proves the
 * fixture fires at all and that OMISSION is reachable — without it a uniform VALUE result would be
 * indistinguishable from a rule that never fired.
 *
 * **All three counts are measured in the same sweep, because they share `countFill` and take the same group
 * operand.** `NumberOfFilledFields` counts CELLS like the value count and takes the same expansion fix.
 * `NumberOfFilledGroups` counts GROUPS: its group operand is the counted ENTITY rather than a cell container, so
 * it gets its own entity tally, and the half-full row below is the discriminator that separates the two accounts.
 */
class FixedGroupValueInFieldsPolarityDiffTest {

    private val model = DocumentModelDraft.named("order-fixed-flag-polarity").locale("en_US")
        .rootGroup("Order") { order ->
            order.numberField("Threshold") { it.fractionDigits(0, 0) }
            order.group("Approvals") { g ->
                g.field("Expedited", FieldKind.BOOLEAN)
                g.field("TermsAccepted", FieldKind.CONFIRM)
            }
            order.group("Escalations") { g ->
                g.field("Reviewed", FieldKind.BOOLEAN)
                g.field("Escalated", FieldKind.BOOLEAN)
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(model)
    private fun approvals() = bound.flattenGroup(APPROVALS)

    /** `[Threshold] != <count>` — authored typed off the bound model, rendered group-relative by `TypedRules`. */
    private fun modelWith(code: String, count: NumberExpr): String =
        TypedRules.inject(
            model,
            bound.rule(bound.group(ROOT)).name(code).code(code).errorField(bound.num(THRESHOLD))
                .`when`(bound.num(THRESHOLD).asNumber().ne(count))
                .severity(Severity.ERROR)
                .message(Dsl.en("The recorded threshold does not match what the approvals group holds.")).build(),
        )

    /** Tri-check `code|type` over one (threshold, Expedited, TermsAccepted, Reviewed) tuple. */
    private fun sigs(
        modelJson: String, threshold: String, expedited: String?, terms: String?, reviewed: String? = null,
    ): List<String> {
        val definition = ModelLoader.load(modelJson)
        fun kernelDoc(): RuntimeLaws.Doc {
            val d = RuntimeLaws.doc().group(ROOT, 1).field(THRESHOLD, threshold, 1, 1)
                .group(APPROVALS, 1, 1).group(ESCALATIONS, 1, 1)
            expedited?.let { d.field(EXPEDITED, it, 1, 1, 1) }
            terms?.let { d.field(TERMS, it, 1, 1, 1) }
            reviewed?.let { d.field(REVIEWED, it, 1, 1, 1) }
            return d
        }
        val interpreterDoc = definition.newDocument().group(ROOT, 1)
            .field(THRESHOLD, threshold, 1, 1).group(APPROVALS, 1, 1).group(ESCALATIONS, 1, 1)
        expedited?.let { interpreterDoc.field(EXPEDITED, it, 1, 1, 1) }
        terms?.let { interpreterDoc.field(TERMS, it, 1, 1, 1) }
        reviewed?.let { interpreterDoc.field(REVIEWED, it, 1, 1, 1) }

        val groovy = RuntimeLaws.messages(modelJson, kernelDoc()).map { "${it.code()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(modelJson)
        val javaStatic = engine.validationSignaturesSorted(engine.instance(kernelDoc()))
            .map { it.split("|").take(2).joinToString("|") }.sorted()
        val interpreter = DmInterpreter(definition).validateFull(interpreterDoc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()

        assertEquals(groovy, javaStatic, "threshold=$threshold flags=($expedited,$terms) — groovy vs java-static")
        assertEquals(groovy, interpreter, "threshold=$threshold flags=($expedited,$terms) — kernel vs interpreter")
        return groovy
    }

    @Test fun theValueCountsFiredPolarityFollowsTheExpansionsFillability() {
        val json = modelWith(VALUE_COUNT, Dsl.numberOfValueInFields(true, approvals()))
        assertEquals(
            listOf(
                "full group    => [$VALUE_COUNT|VALUE_ERROR]",
                "one cell open => [$VALUE_COUNT|OMISSION_ERROR]",
            ),
            listOf(
                "full group    => ${sigs(json, "5", "true", "true")}",
                "one cell open => ${sigs(json, "5", "true", null)}",
            ),
            "the fired message type as BOTH kernel strategies report it",
        )
    }

    @Test fun theFilledFieldCountsFiredPolarityFollowsTheSameExpansion() {
        val json = modelWith(FILLED_COUNT, Dsl.numberOfFilledFields(approvals()))
        assertEquals(
            listOf(
                "full group    => [$FILLED_COUNT|VALUE_ERROR]",
                "one cell open => [$FILLED_COUNT|OMISSION_ERROR]",
            ),
            listOf(
                "full group    => ${sigs(json, "5", "true", "true")}",
                "one cell open => ${sigs(json, "5", "true", null)}",
            ),
            "the fired message type as BOTH kernel strategies report it",
        )
    }

    /**
     * THE THIRD COUNT, and it is the reason the two fixes above are per-operator rather than one shared change.
     * `NumberOfFilledGroups` shares `countFill`'s old fallback and takes the same fixed-group operands, but it
     * counts GROUPS: the cell expansion that fixes the two counts above would report a group already counted as
     * still growable. Its entity tally is measured here on the same discriminator plus a third row that no
     * cell-based account can pass — a group with ONE of its two cells filled is already counted, so filling the
     * other cannot raise the count, while every remaining empty CELL says "growable".
     */
    @Test fun theFilledGroupCountsFiredPolarityCountsGroupsNotCells() {
        val json = modelWith(
            GROUP_COUNT, Dsl.numberOfFilledGroups(bound.group(APPROVALS), bound.group(ESCALATIONS)),
        )
        assertEquals(
            listOf(
                "both groups filled  => [$GROUP_COUNT|VALUE_ERROR]",
                "one group half-full => [$GROUP_COUNT|VALUE_ERROR]",
                "one group empty     => [$GROUP_COUNT|OMISSION_ERROR]",
            ),
            listOf(
                "both groups filled  => ${sigs(json, "5", "true", "true", "true")}",
                "one group half-full => ${sigs(json, "5", "true", null, "true")}",
                "one group empty     => ${sigs(json, "5", "true", "true", null)}",
            ),
            "the fired message type as BOTH kernel strategies report it",
        )
    }

    private companion object {
        const val ROOT = "/Order"
        const val THRESHOLD = "/Order/Threshold"
        const val APPROVALS = "/Order/Approvals"
        const val EXPEDITED = "/Order/Approvals/Expedited"
        const val TERMS = "/Order/Approvals/TermsAccepted"
        const val ESCALATIONS = "/Order/Escalations"
        const val REVIEWED = "/Order/Escalations/Reviewed"
        const val VALUE_COUNT = "APPROVAL_VALUE_TALLY"
        const val FILLED_COUNT = "APPROVAL_FILLED_TALLY"
        const val GROUP_COUNT = "APPROVAL_GROUP_TALLY"
    }
}
