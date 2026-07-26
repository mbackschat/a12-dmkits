package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.util.Optional
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for over-repetition as the **address-formal gate that runs BEFORE scalar conversion**
 * (SPEC-2026-07-23-13). The kernel's `CheckCommand.formalePruefung` calls `FormalChecker.checkIndices`
 * before value conversion; `checkIndicesLessOrEqualThanMax` walks the COMPLETE repeatable ancestry and
 * reports the FIRST excess coordinate, so an over-limit instance never reaches scalar/custom checking and
 * every descendant read of that instance is unavailable — even an absent one (it must not read as clean
 * empty). The immutable physical row stays instantiated, so it still supplies structural group content.
 *
 * Complements [OverRepetitionDiffTest] (structural emission + rule/mandatory/unique suppression) with the
 * PHASE separators the entry names: over-limit descendants that are valid / malformed / absent / custom each
 * expose over-repetition rather than a scalar/custom result; the in-cap sibling stays ordinary; an in-cap
 * inner coordinate beneath an over-limit OUTER ancestor is still over-repetition; and the over-limit row is
 * still counted as structural group content. Every case compares dynamic-Groovy (normative), static-Java,
 * and the interpreter over the full (code @ field-pointer) message set.
 *
 * Rows are instantiated CONTIGUOUSLY (the kernel materializes a repeatable list densely from its highest
 * index; a sparse builder would compare structurally-different documents). Insurance domain (own fixtures).
 */
class OverRepetitionFormalGateDiffTest {

    // /Policy { Insured; Coverages[2] { Premium(2,2 decimals); Riders[2] { Fee } } } — cap 2, so index 3 is
    // over-limit at either axis. Premium requires exactly two decimals, so "10.00" is valid and "10" malformed.
    private val base = DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy", Consumer { p ->
            p.stringField("Insured", Consumer { })
                .group("Coverages", Consumer<GroupBuilder> { c ->
                    c.repeatable(2).numberField("Premium", Consumer { it.fractionDigits(2, 2) })
                        .group("Riders", Consumer<GroupBuilder> { r -> r.repeatable(2).numberField("Fee", Consumer { }) })
                })
        })
        .build().toJson()

    /** Mirror one document build across the kernel [RuntimeLaws.Doc] and the interpreter document. */
    private inner class Build(model: String) {
        val kDoc = RuntimeLaws.doc().group(POLICY, 1)
        val iDoc = ModelLoader.load(model).newDocument().group(POLICY, 1)
        fun insured(v: String) { kDoc.field(INSURED, v, 1, 1); iDoc.field(INSURED, v, 1, 1) }
        fun coverage(i: Int) { kDoc.group(COVERAGES, 1, i); iDoc.group(COVERAGES, 1, i) }
        fun premium(i: Int, v: String) { coverage(i); kDoc.field(PREMIUM, v, 1, i, 1); iDoc.field(PREMIUM, v, 1, i, 1) }
        fun rider(ci: Int, ri: Int, fee: String) {
            coverage(ci); kDoc.group(RIDERS, 1, ci, ri)
            iDoc.group(RIDERS, 1, ci, ri); kDoc.field(FEE, fee, 1, ci, ri, 1); iDoc.field(FEE, fee, 1, ci, ri, 1)
        }
    }

    private fun sigs(cs: List<Pair<String, String>>) = cs.map { "${it.first}@${it.second}" }.sorted()

    /** The full (code @ pointer) message set across all three engines must agree; dynamic-Groovy is normative. */
    private fun agree(label: String, model: String, build: Build.() -> Unit) {
        val b = Build(model).apply(build)
        val dynamic = sigs(RuntimeLaws.messages(model, b.kDoc).map { it.code() to it.field() })
        val java = RuntimeLaws.kernelJavaEngine(model).let { e -> sigs(e.messages(e.instance(b.kDoc)).map { it.code() to it.field() }) }
        val interp = sigs(DmInterpreter(ModelLoader.load(model)).validate(b.iDoc).messages.map { it.code to it.errorPath })
        assertEquals(dynamic, java, "$label — static-Java vs dynamic-Groovy")
        assertEquals(dynamic, interp, "$label — interpreter vs dynamic-Groovy  kernel=$dynamic interp=$interp")
    }

    private fun model() = BoundModel.ofJson(base)

    private val fieldCoordinateBase = DocumentModelDraft.named("application").locale("en_US")
        .rootGroup("Application", Consumer { a ->
            a.stringField("Reference", Consumer { })
                .group("Lines", Consumer<GroupBuilder> { l ->
                    l.repeatable(2).numberField("Amount", Consumer { })
                })
        })
        .build().toJson()

    /**
     * A field's terminal coordinate is not a repetition axis: the Kernel's V1-to-V2 document conversion refuses
     * any value other than 1 before either evaluation strategy can run. The two cases lock the class rather than
     * one path: a root field at terminal 2 and a nested field at terminal 3.
     */
    @Test
    fun fieldTerminalCoordinateOtherThanOneIsRejectedBeforeEitherKernelStrategy() {
        val documents = listOf(
            RuntimeLaws.doc()
                .group(APPLICATION, 1)
                .field(REFERENCE, "APP-1", 1, 2),
            RuntimeLaws.doc()
                .group(APPLICATION, 1)
                .group(APPLICATION_LINES, 1, 1)
                .field(AMOUNT, "10", 1, 1, 3),
        )
        val expectedCoordinates = listOf(2, 3)
        val staticEngine = RuntimeLaws.kernelJavaEngine(fieldCoordinateBase)

        documents.forEachIndexed { index, document ->
            val coordinate = expectedCoordinates[index]
            val dynamic = assertThrows(AssertionError::class.java) {
                RuntimeLaws.messages(fieldCoordinateBase, document)
            }
            val java = assertThrows(AssertionError::class.java) {
                staticEngine.instance(document)
            }

            val expected = "expected repetition 1 for field, but got $coordinate"
            assertTrue(dynamic.message.orEmpty().contains(expected), "dynamic-Groovy document conversion: ${dynamic.message}")
            assertTrue(java.message.orEmpty().contains(expected), "static-Java document conversion: ${java.message}")
        }
    }

    /** A VALID over-limit descendant exposes over-repetition, and the in-cap sibling stays fully ordinary (clean). */
    @Test fun validOverLimitDescendantExposesOverRepetitionAndInCapSiblingStaysOrdinary() =
        agree("valid over-limit Premium + clean in-cap sibling", base) {
            premium(1, "10.00")   // in cap → clean sibling
            coverage(2)           // in cap, bare empty → clean
            premium(3, "10.00")   // over cap, valid value → over-repetition only, NOT a clean/scalar result
        }

    /** A MALFORMED over-limit descendant exposes over-repetition, NOT the format error — the gate precedes scalar
     *  conversion. The identical malformed value on the in-cap row DOES draw the format error (the control). */
    @Test fun malformedOverLimitDescendantExposesOverRepetitionNotFormat() =
        agree("malformed in-cap draws format; malformed over-limit draws only over-repetition", base) {
            premium(1, "10")      // in cap, too few decimals → format error
            coverage(2)           // in cap, bare empty → clean
            premium(3, "10")      // over cap, same malformed value → over-repetition only (scalar suppressed)
        }

    /** An ABSENT over-limit descendant reads UNAVAILABLE, not clean empty: a per-row `[Premium] < 5` fires on the
     *  in-cap empty rows (empty→0<5) but is suppressed on the over-limit row (the read is unavailable). */
    @Test fun absentOverLimitDescendantReadsUnavailableNotCleanEmpty() {
        val m = model()
        val premium = m.num(PREMIUM)
        val rule: Rule = m.rule(m.group(COVERAGES)).name("PremLow").code("PREM_LOW").errorField(premium)
            .`when`(premium.lt(5)).severity(Severity.ERROR).message(Dsl.en("Premium below floor")).build()
        val ruleModel = TypedRules.inject(base, rule)
        agree("absent Premium: in-cap fires (empty→0<5), over-limit suppressed (unavailable)", ruleModel) {
            coverage(1)   // in cap, Premium absent → PREM_LOW fires (empty reads 0)
            coverage(2)   // in cap, Premium absent → PREM_LOW fires
            coverage(3)   // over cap, Premium absent → PREM_LOW suppressed; only over-repetition
        }
    }

    /** An in-cap INNER coordinate beneath an over-limit OUTER ancestor is still over-repetition: terminal-axis-only
     *  checking is wrong. Coverage 3 (over-limit outer) with Rider 1 (in-cap inner) — the whole subtree is flagged. */
    @Test fun inCapInnerBeneathOverLimitOuterIsOverRepetition() =
        agree("over-limit outer Coverage[3] with in-cap inner Rider[1]", base) {
            premium(1, "10.00")            // in-cap outer, clean sibling
            coverage(2)                    // in-cap outer, bare empty → clean
            rider(3, 1, "5")               // over-limit OUTER (Coverage 3), in-cap INNER (Rider 1)
        }

    // ---- the over-limit row is OUTSIDE every star aggregate / quantifier / count domain (the discovered
    //      divergence IG-fixed by this entry: the kernel does not insert an over-limit cell/row into its
    //      evaluation cache, so a Sum/NumberOfFilledFields/NumberOfFilledGroups(G*) ranges over the in-cap rows
    //      only — clean and definite, never poisoned; the interpreter used to leak the over-limit row in) ----

    /** `Sum` over `Coverages* / Premium` excludes the over-limit row: rows 1,2 = 10.00 + 20.00 → 30.00 (NOT
     *  129.00), so `== 30.00` fires. The over-limit row's Premium (99.00) is not in the star domain. */
    @Test fun sumOverRepeatableGroupExcludesOverLimitRow() {
        val m = model()
        val insured = m.str(INSURED)
        val rule: Rule = m.rule(m.group(POLICY)).name("SumEq").code("SUM_EQ").errorField(insured)
            .`when`(insured.filled().and(Dsl.sum(m.flatten("/Policy/Coverages*/Premium")).eq("30.00")))
            .severity(Severity.ERROR).message(Dsl.en("premium sum is 30")).build()
        agree("Sum over Coverages* is 30.00 (over-limit row excluded, not poisoned)", TypedRules.inject(base, rule)) {
            insured("yes"); premium(1, "10.00"); premium(2, "20.00"); premium(3, "99.00")
        }
    }

    /** `NumberOfFilledFields` over `Coverages* / Premium` counts 2 (rows 1,2), NOT 3 — the over-limit row's filled
     *  Premium is not in the star domain. */
    @Test fun numberOfFilledFieldsExcludesOverLimitRow() {
        val m = model()
        val insured = m.str(INSURED)
        val rule: Rule = m.rule(m.group(POLICY)).name("NffEq").code("NFF_EQ").errorField(insured)
            .`when`(insured.filled().and(Dsl.numberOfFilledFields(m.flatten("/Policy/Coverages*/Premium")).eq(2)))
            .severity(Severity.ERROR).message(Dsl.en("two filled premiums")).build()
        agree("NumberOfFilledFields over Coverages* is 2 (over-limit row excluded)", TypedRules.inject(base, rule)) {
            insured("yes"); premium(1, "10.00"); premium(2, "20.00"); premium(3, "99.00")
        }
    }

    /** `NumberOfFilledGroups(Coverages*)` counts 2 instantiated rows (1,2), NOT 3 — the over-limit row is outside
     *  the declared-range star domain (though it stays instantiated and `GroupFilled`-visible, above). */
    @Test fun numberOfFilledGroupsExcludesOverLimitRow() {
        val m = model()
        val insured = m.str(INSURED)
        val rule: Rule = m.rule(m.group(POLICY)).name("NfgEq").code("NFG_EQ").errorField(insured)
            .`when`(insured.filled().and(Dsl.numberOfFilledGroups(m.group(COVERAGES)).eq(2)))
            .severity(Severity.ERROR).message(Dsl.en("two coverage rows")).build()
        agree("NumberOfFilledGroups(Coverages*) is 2 (over-limit row excluded from the count)", TypedRules.inject(base, rule)) {
            insured("yes"); coverage(1); coverage(2); coverage(3)   // row 3 over-limit → not counted
        }
    }

    // ---- structural group content: the over-limit row stays instantiated and GroupFilled-visible ----

    private val contentBase = DocumentModelDraft.named("bundle").locale("en_US")
        .rootGroup("Bundle", Consumer { b ->
            b.stringField("Tag", Consumer { })
                .group("Section", Consumer<GroupBuilder> { s ->
                    s.group("Lines", Consumer<GroupBuilder> { l -> l.repeatable(2).numberField("Amount", Consumer { }) })
                })
        })
        .build().toJson()

    /** The immutable over-limit row is RETAINED as structural content: a `/Bundle`-scope
     *  `Tag filled AND GroupFilled(Section)` fires (the non-repeatable `Section` is content-bearing from its
     *  instantiated `Lines` rows, the over-limit one included) while the over-limit row still emits `zuGrosseZeile`.
     *  Dropping the physical over-limit row would lose group content; this guards that the aggregate-domain
     *  exclusion above does NOT drop the row from `GroupFilled`-style presence. */
    @Test fun overLimitRowRetainedAsGroupContent() {
        val cm = BoundModel.ofJson(contentBase)
        val tag = cm.str(TAG)
        val rule: Rule = cm.rule(cm.group(BUNDLE)).name("HasSection").code("HAS_SECTION").errorField(tag)
            .`when`(tag.filled().and(cm.group(SECTION).filled())).severity(Severity.ERROR)
            .message(Dsl.en("Section has content")).build()
        val ruleModel = TypedRules.inject(contentBase, rule)
        val kDoc = RuntimeLaws.doc().group(BUNDLE, 1).field(TAG, "x", 1, 1).group(SECTION, 1, 1)
        val iDoc = ModelLoader.load(ruleModel).newDocument().group(BUNDLE, 1).field(TAG, "x", 1, 1).group(SECTION, 1, 1)
        for (i in 1..3) { kDoc.group(LINES, 1, 1, i); iDoc.group(LINES, 1, 1, i) }   // Lines[3] over cap 2
        val dynamic = sigs(RuntimeLaws.messages(ruleModel, kDoc).map { it.code() to it.field() })
        val java = RuntimeLaws.kernelJavaEngine(ruleModel).let { e -> sigs(e.messages(e.instance(kDoc)).map { it.code() to it.field() }) }
        val interp = sigs(DmInterpreter(ModelLoader.load(ruleModel)).validate(iDoc).messages.map { it.code to it.errorPath })
        assertEquals(dynamic, java, "structural content — static-Java vs dynamic-Groovy")
        assertEquals(dynamic, interp, "structural content — interpreter vs dynamic-Groovy  kernel=$dynamic interp=$interp")
    }

    // ---- the registered-custom arm: over-repetition exposed AND the validator callback provably not invoked ----

    private val customBase = DocumentModelDraft.named("registry").locale("en_US")
        .rootGroup("Registry", Consumer { g ->
            g.group("Plans", Consumer<GroupBuilder> { pl ->
                pl.repeatable(2).customField("Code", Consumer<CustomFieldBuilder> { it.type("PlanCode") })
            })
        })
        .build().toJson()

    /**
     * A REGISTERED CustomFieldType on an over-limit cell exposes over-repetition, and its validator callback is
     * provably NOT invoked (the gate precedes custom checking). Proven with instrumented validators on ALL three
     * engines: each records the values it is asked to check — the in-cap cell's value appears, the over-limit
     * cell's value never does. Distinct values (`AAA` in-cap, `BBB` over-limit) make the absence unambiguous.
     */
    @Test fun registeredCustomOverLimitDescendantExposesOverRepetitionAndSkipsCallback() {
        // Contiguous Plans list [1,2,3]; row 2 bare empty (empty custom cell → never validated on either engine).
        val kDoc = RuntimeLaws.doc().group(REGISTRY, 1)
            .group(PLANS, 1, 1).field(CODE, "AAA", 1, 1, 1)   // in cap → validator invoked, rejected
            .group(PLANS, 1, 2)                               // in cap, empty → validator not invoked
            .group(PLANS, 1, 3).field(CODE, "BBB", 1, 3, 1)   // over cap → over-repetition, validator NOT invoked

        val dynamicFactory = CapturingFactory()
        val dynamic = RuntimeLaws.messages(customBase, kDoc, dynamicFactory).map { it.code() to it.field() }
        val staticFactory = CapturingFactory()
        val staticEngine = RuntimeLaws.kernelJavaEngine(customBase)
        val java = staticEngine.messages(staticEngine.instance(kDoc), staticFactory).map { it.code() to it.field() }

        val model = ModelLoader.load(customBase)
        val interpSeen = mutableListOf<String>()
        val validator = CustomFieldType { value, _ ->
            interpSeen += value
            if (value == "AAA") CustomTypeRejection(REJECTION) else null
        }
        val iDoc = model.newDocument().group(REGISTRY, 1)
            .group(PLANS, 1, 1).field(CODE, "AAA", 1, 1, 1)
            .group(PLANS, 1, 2)
            .group(PLANS, 1, 3).field(CODE, "BBB", 1, 3, 1)
        val interp = DmInterpreter(model, customFieldTypes = mapOf("PlanCode" to validator))
            .validate(iDoc).messages.map { it.code to it.errorPath }

        // The message set: custom rejection on the in-cap cell, over-repetition (never the rejection) on the over-limit cell.
        assertEquals(sigs(dynamic), sigs(java), "custom message set — static-Java vs dynamic-Groovy")
        assertEquals(sigs(dynamic), sigs(interp), "custom message set — interpreter vs dynamic-Groovy  kernel=${sigs(dynamic)} interp=${sigs(interp)}")

        // The callback is provably not invoked on the over-limit cell: "BBB" never reaches any validator.
        assertEquals(listOf("AAA"), dynamicFactory.seen, "dynamic-Groovy validator invoked only in-cap")
        assertEquals(listOf("AAA"), staticFactory.seen, "static-Java validator invoked only in-cap")
        assertEquals(listOf("AAA"), interpSeen, "interpreter validator invoked only in-cap")
    }

    private class CapturingFactory : ICustomFieldTypeFactory {
        val seen = mutableListOf<String>()
        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != "PlanCode") Optional.empty()
            else Optional.of(object : ICustomFieldValidator {
                override fun validate(
                    value: String,
                    param: ICustomFieldTypeValidationParam,
                    isDisplay: Boolean,
                ): Optional<ICustomFieldTypeCheckError> {
                    seen += value
                    return if (value == "AAA") Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorMessage() = "rejected"
                        override fun getErrorKey() = REJECTION
                    }) else Optional.empty()
                }
            })
    }

    private companion object {
        const val POLICY = "/Policy"
        const val INSURED = "/Policy/Insured"
        const val COVERAGES = "/Policy/Coverages"
        const val PREMIUM = "/Policy/Coverages/Premium"
        const val RIDERS = "/Policy/Coverages/Riders"
        const val FEE = "/Policy/Coverages/Riders/Fee"

        const val REGISTRY = "/Registry"
        const val PLANS = "/Registry/Plans"
        const val CODE = "/Registry/Plans/Code"
        const val REJECTION = "PLAN_CODE_INVALID"

        const val BUNDLE = "/Bundle"
        const val TAG = "/Bundle/Tag"
        const val SECTION = "/Bundle/Section"
        const val LINES = "/Bundle/Section/Lines"

        const val APPLICATION = "/Application"
        const val REFERENCE = "/Application/Reference"
        const val APPLICATION_LINES = "/Application/Lines"
        const val AMOUNT = "/Application/Lines/Amount"
    }
}
