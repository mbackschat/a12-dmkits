package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A field copied into a field of any other kind, on the complete cross of eleven declarations over nine kinds,
 * with A12's class predicted from the comparison's arm order and the portable checker required to name it too.
 *
 * A computation is judged as the comparison `[<target>] != <operation>` it is lowered to
 * ([KF483](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf483)), and that comparison asks its arms
 * in a fixed order ([KF469](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf469)). So the prediction
 * is one function of the two declarations, written before the run: a Confirm on either side, then a Boolean,
 * then a temporal against a non-temporal kind or two temporal kinds of different formats, then a DateRange,
 * then a Number against a text, then the scale. A String or an Enumeration against an Enumeration meets the
 * display arm (a labelled side against an unlabelled one, or two conflicting labellings) ahead of the
 * calculation's own domain class at an Enumeration target.
 *
 * The cross is what discriminates. "The target's kind names the class" and "the source's kind names it" each
 * predict one direction of every Confirm and Boolean row wrongly, and "the calculation answers first at an
 * Enumeration target" predicts `MVK_ERROR_INVALID_ENUM_CALCULATION` for the wider unlabelled domain copied into
 * the labelled target, where the display class stands alone. The unlabelled Enumeration copied into a String is
 * the admitted row that refutes "any Enumeration into a String is refused".
 *
 * The cross follows a12-kernel-lean's `SPEC-2026-09-26-04`, whose grid over the same shape they report on their
 * own instrument; the rows here are this repository's own measurement on its own fixture.
 *
 * Own domain: an insurance policy holding one field of each kind and a twin of each as the target.
 */
class CheckerFieldCopyCrossAgreementTest {

    @Test
    fun everyCrossKindCopyDrawsTheFirstArmThatRefusesIt() {
        val rows = linkedMapOf<String, Pair<Copy, Set<String>>>()
        for (source in BASE) {
            for (target in BASE) {
                rows["${source.name} into ${target.name}Copy"] =
                    Copy("../${target.name}Copy", "[${source.name}]") to predict(source, target)
            }
        }
        rows["PlanSwapped into PlanCopy"] = Copy("../PlanCopy", "[PlanSwapped]") to predict(PLAN_SWAPPED, PLAN)
        rows["TierWide into TierCopy"] = Copy("../TierCopy", "[TierWide]") to predict(TIER_WIDE, TIER)
        rows["TierWide into PlanCopy"] = Copy("../PlanCopy", "[TierWide]") to predict(TIER_WIDE, PLAN)
        rows["Tier into TierWide"] = Copy("../TierWide", "[Tier]") to predict(TIER, TIER_WIDE)
        rows["PlanSwapped into HolderCopy"] = Copy("../HolderCopy", "[PlanSwapped]") to predict(PLAN_SWAPPED, HOLDER)
        rows["Premium + 1 into HolderCopy"] = Copy("../HolderCopy", "[Premium] + 1") to TO_ENUM_OR_STRING

        val wrong = rows.mapNotNull { (row, probe) ->
            val (copy, predicted) = probe
            val observed = compare(copy)
            val expected = predicted to (OURS[row] ?: predicted)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "${wrong.size} of ${rows.size} rows disagree:\n"))
    }

    /** The arm order, as one function of the two declarations. */
    private fun predict(source: Decl, target: Decl): Set<String> {
        val kinds = setOf(source.kind, target.kind)
        val differ = source.kind != target.kind
        return when {
            Kind.CONFIRM in kinds && differ -> setOf("MVK_INVALID_COMPARE_TO_YES")
            Kind.BOOLEAN in kinds && differ -> setOf("MVK_INVALID_COMPARE_TO_YESNO")
            source.kind.temporal != target.kind.temporal -> TO_DATE
            source.kind.temporal && differ -> TO_DATE
            Kind.DATE_RANGE in kinds && differ -> setOf("MVK_INVALID_COMPARE_TO_DATE_RANGE")
            Kind.NUMBER in kinds && differ -> TO_ENUM_OR_STRING
            source.kind == Kind.NUMBER && source.scale != target.scale -> setOf("MVK_INVALID_COMPARE_DEC_PLACES")
            source.kind == Kind.ENUMERATION || target.kind == Kind.ENUMERATION -> display(source, target)
            else -> ADMITTED
        }
    }

    /** A String or an Enumeration against an Enumeration: the display arm, then the calculation's domain. */
    private fun display(source: Decl, target: Decl): Set<String> = when {
        source.labelled != target.labelled -> setOf("MVK_INVALID_COMPARE_ENUM_TO_STRING")
        source.labelled && source.labels.any { (value, label) -> target.labels[value]?.let { it != label } == true } ->
            setOf("MVK_INVALID_COMPARE_ENUM_TO_ENUM")
        target.kind == Kind.ENUMERATION && (source.kind == Kind.STRING || !target.values.containsAll(source.values)) ->
            setOf("MVK_ERROR_INVALID_ENUM_CALCULATION")
        else -> ADMITTED
    }

    private data class Copy(val target: String, val operation: String)

    private fun compare(copy: Copy): Pair<Set<String>, Set<String>> {
        val draft = DraftComputation("/Policy", "Derived", copy.target, null,
            listOf(DraftComputation.Alternative.builder(copy.operation).build()))
        val a12 = validator.check(policy, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        }
        val ours = check(ModelSource(validator.inject(policy, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private enum class Kind(val temporal: Boolean) {
        STRING(false), NUMBER(false), DATE(true), TIME(true), DATE_TIME(true), DATE_RANGE(false),
        BOOLEAN(false), CONFIRM(false), ENUMERATION(false),
    }

    /** One declaration: its kind, and what the arms read of it (a scale, an Enumeration's values and labels). */
    private data class Decl(
        val name: String,
        val kind: Kind,
        val scale: Int = 0,
        val labels: Map<String, String?> = emptyMap(),
    ) {
        val values: Set<String> get() = labels.keys
        val labelled: Boolean get() = labels.values.any { it != null }
    }

    private val validator = RuleValidator()

    private val policy = DocumentModelDraft.named("policy-copies").locale("en_US")
        .rootGroup("Policy") { p ->
            for (suffix in listOf("", "Copy")) {
                p.stringField("Holder$suffix") { }
                    .numberField("Premium$suffix") { n -> n.fractionDigits(0) }
                    .numberField("Deductible$suffix") { n -> n.fractionDigits(2) }
                    .dateField("Inception$suffix") { d -> d.format("dd.MM.yyyy") }
                    .timeField("Cutoff$suffix") { t -> t.format("HH:mm:ss") }
                    .dateTimeField("IssuedAt$suffix") { t -> t.format("yyyy-MM-dd'T'HH:mm:ss") }
                    .dateRangeField("Cover$suffix") { r -> r.format("dd.MM.yyyy").separator("-") }
                    .booleanField("Renewable$suffix") { }
                    .confirmField("Consented$suffix") { }
                    .enumField("Tier$suffix") { e -> e.value("GOLD").value("SILVER") }
                    .enumField("Plan$suffix") { e -> e
                        .value("GOLD") { l -> l.label("en_US", "Gold") }
                        .value("SILVER") { l -> l.label("en_US", "Silver") } }
            }
            p.enumField("PlanSwapped") { e -> e
                .value("GOLD") { l -> l.label("en_US", "Silver") }
                .value("SILVER") { l -> l.label("en_US", "Gold") } }
                .enumField("TierWide") { e -> e.value("GOLD").value("SILVER").value("BRONZE") }
        }
        .build().toJson()

    private companion object {
        val ADMITTED = emptySet<String>()
        val TO_DATE = setOf("MVK_INVALID_COMPARE_TO_DATE")
        val TO_ENUM_OR_STRING = setOf("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING")

        val HOLDER = Decl("Holder", Kind.STRING)
        val TIER = Decl("Tier", Kind.ENUMERATION, labels = mapOf("GOLD" to null, "SILVER" to null))
        val PLAN = Decl("Plan", Kind.ENUMERATION, labels = mapOf("GOLD" to "Gold", "SILVER" to "Silver"))
        val PLAN_SWAPPED = Decl("PlanSwapped", Kind.ENUMERATION, labels = mapOf("GOLD" to "Silver", "SILVER" to "Gold"))
        val TIER_WIDE = Decl("TierWide", Kind.ENUMERATION,
            labels = mapOf("GOLD" to null, "SILVER" to null, "BRONZE" to null))

        /** The eleven declarations every row copies between, each into the other's twin. */
        val BASE = listOf(
            HOLDER,
            Decl("Premium", Kind.NUMBER, scale = 0),
            Decl("Deductible", Kind.NUMBER, scale = 2),
            Decl("Inception", Kind.DATE),
            Decl("Cutoff", Kind.TIME),
            Decl("IssuedAt", Kind.DATE_TIME),
            Decl("Cover", Kind.DATE_RANGE),
            Decl("Renewable", Kind.BOOLEAN),
            Decl("Consented", Kind.CONFIRM),
            TIER,
            PLAN,
        )

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf(
            // The comparison families type a side only where it is one entity spec or a written-out constant
            // ([CONSISTENCY-GAPS DG117](../../../../../../../../../../../docs/CONSISTENCY-GAPS.md)).
            "Premium + 1 into HolderCopy" to ADMITTED,
        )
    }
}
