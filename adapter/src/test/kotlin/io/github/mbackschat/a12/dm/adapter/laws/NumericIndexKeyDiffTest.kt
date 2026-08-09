package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * An admitted index-field cell is identified by its **canonical A12 value, never its stored text** ([IF272]).
 * On a **Number** index field the legal spellings `5` and `5.00` are one kernel key
 * (`IndexFieldCache.normalizeValue`, SPEC-2026-07-23-08), so they collide within a group and match across joined
 * groups. Formal admission comes first: a malformed stored token contributes no identity even when it parses to
 * the same decimal as a valid token.
 *
 * The finite matrix observes seven consumers of the index-identity lifecycle. The raw-text relations share
 * `indexKeyOf`; the starred column keys the typed [io.github.mbackschat.a12.dm.interpreter.ast.Val], whose decimal
 * equality is value-based since IF41:
 *
 *  - scalar semantic reads and semantic presence;
 *  - the semantic-index contained-field scan;
 *  - the **parallel join**'s value→row map (`Document.indexMap`), for collision and cross-group matching;
 *  - the auto **`uniqueIndex`** check (`AutoChecks.duplicateIndexCells`);
 *  - the **computation** engine's invalid-index detection (`ComputationEngine.invalidIndexParentCoords`);
 *  - the semantic-index **column** behind starred aggregates and value-list quantifiers (`IndexColumn`).
 *
 * `5`/`5` beside every `5`/`5.00` is the textual control: it was already exact, so a case that only passes because
 * both spellings are ignored cannot hide here. `5`/`6` is the clean control that still fires. Invalid-before-valid,
 * valid-before-invalid, invalid+two-admitted, over-limit, empty field-valued key, full/partial, and selected-pointer
 * rows separate admission, collision, row selection, 3VL, and pointer stages. Both kernel strategies agree.
 */
class NumericIndexKeyDiffTest {

    private fun modelWithMaximum(maximum: Int) = DocumentModelDraft.named("tariff$maximum").locale("en_US")
        .rootGroup("Tariff", Consumer { g: GroupBuilder ->
            g.stringField("ProductCode", Consumer { })
            g.numberField("LookupKey", Consumer { it.fractionDigits(2) })
            g.numberField("Threshold", Consumer { it.fractionDigits(2) })
            for (name in listOf("Offered", "Quoted")) {
                g.group(name, Consumer { s ->
                    s.repeatable(maximum).indexField("Deductible")
                        .numberField("Deductible", Consumer { it.fractionDigits(2) })
                        .numberField("Premium", Consumer { it.fractionDigits(2) })
                        .numberField("Limit", Consumer { it.fractionDigits(2) })
                })
            }
        }).build().toJson()

    private val base = modelWithMaximum(20)
    private val limitedBase = modelWithMaximum(2)

    /** The parallel join over the two sibling groups, reported by a NEGATIVE operator so an unmatched-into-an
     *  invalid group (3VL UNKNOWN) is distinguishable from a definite empty. */
    private val joinModel by lazy {
        RuntimeLaws.withRule(
            base, "/Tariff", "PJ", "../Offered/Premium", "PJ",
            "FieldsNotCollectivelyFilled(/Tariff/Offered/Premium, /Tariff/Quoted/Premium)",
        )
    }

    /** A starred aggregate over the index field itself — the [IndexColumn] site. Its cells are poisoned by the
     *  `uniqueIndex` error, so a duplicated column reads UNKNOWN and `AtLeastOne` skips it. */
    private val columnModel by lazy {
        RuntimeLaws.withRule(
            base, "/Tariff", "VL", "../Offered/Deductible", "VL",
            "AtLeastOneFieldValueIncludedInValueList(/Tariff/Offered*/Deductible In /Tariff/Threshold)",
        )
    }

    private val computeModel by lazy {
        RuntimeLaws.withComputation(base, "/Tariff/Quoted", "Copy", "../Premium", "[../Offered/Premium]")
    }

    private val bound = BoundModel.ofJson(base)

    private fun withTypedRule(modelJson: String, code: String, errorField: FieldRef, condition: Condition): String {
        val scope = EditSession.read(modelJson).group("/Tariff")
        val rule = scope.rule { builder ->
            builder.name(code).code(code).errorField(errorField).`when`(condition)
                .severity(Severity.ERROR).message(Dsl.en(code)).build()
        }
        return TypedRules.inject(modelJson, rule)
    }

    private val semanticIndexModel by lazy {
        withTypedRule(
            base, "SI", bound.num("/Tariff/Offered/Premium").field(),
            bound.num("/Tariff/Offered/Premium").forIndex("5").gt(0),
        )
    }

    private val semanticIndexPresenceModel by lazy {
        withTypedRule(
            base, "SIP", bound.num("/Tariff/Offered/Premium").field(),
            bound.num("/Tariff/Offered/Premium").semanticIndex("5").filled(),
        )
    }

    private val semanticIndexAbsenceModel by lazy {
        withTypedRule(
            base, "SIN", bound.num("/Tariff/Offered/Premium").field(),
            bound.num("/Tariff/Offered/Premium").semanticIndex("5").notFilled(),
        )
    }

    private val twoSemanticKeysModel by lazy {
        val premium = bound.num("/Tariff/Offered/Premium")
        withTypedRule(
            base, "SIM", premium.field(),
            premium.semanticIndex("5").filled().or(premium.semanticIndex("6").filled()),
        )
    }

    private val twoSemanticFieldKeysModel by lazy {
        val premium = bound.num("/Tariff/Offered/Premium")
        withTypedRule(
            base, "SIFK", premium.field(),
            premium.semanticIndex(bound.field("/Tariff/LookupKey")).filled().or(
                premium.semanticIndex(bound.field("/Tariff/Threshold")).filled(),
            ),
        )
    }

    private val twoSemanticFieldKeysReversedModel by lazy {
        val premium = bound.num("/Tariff/Offered/Premium")
        withTypedRule(
            base, "SIFR", premium.field(),
            premium.semanticIndex(bound.field("/Tariff/Threshold")).filled().or(
                premium.semanticIndex(bound.field("/Tariff/LookupKey")).filled(),
            ),
        )
    }

    private val sameSemanticKeyPayloadsModel by lazy {
        val premium = bound.num("/Tariff/Offered/Premium").forIndex("6")
        val limit = bound.num("/Tariff/Offered/Limit").forIndex("6")
        withTypedRule(
            base, "SIK", bound.num("/Tariff/Offered/Premium").field(),
            premium.gt(0).and(limit.gt(0)),
        )
    }

    private val canonicalSameSemanticKeyPayloadsModel by lazy {
        val premium = bound.num("/Tariff/Offered/Premium").forIndex("5")
        val limit = bound.num("/Tariff/Offered/Limit").forIndex("5.00")
        withTypedRule(
            base, "SIC", bound.num("/Tariff/Offered/Premium").field(),
            premium.gt(0).and(limit.gt(0)),
        )
    }

    private val twoLiteralSpellingsSamePayloadModel by lazy {
        val premium = bound.num("/Tariff/Offered/Premium")
        withTypedRule(
            base, "SILS", premium.field(),
            premium.forIndex("5").gt(0).and(premium.forIndex("5.00").gt(0)),
        )
    }

    private val semanticFieldKeyModel by lazy {
        val indexed = bound.num("/Tariff/Offered/Premium")
            .semanticIndex(bound.field("/Tariff/LookupKey"))
        withTypedRule(
            withTypedRule(base, "SIF", bound.num("/Tariff/Offered/Premium").field(), indexed.filled()),
            "SIN", bound.num("/Tariff/Offered/Premium").field(), indexed.notFilled(),
        )
    }

    private val overLimitSemanticModel by lazy {
        val limited = BoundModel.ofJson(limitedBase)
        val indexed = limited.num("/Tariff/Offered/Premium").semanticIndex("5")
        val errorField = limited.num("/Tariff/Offered/Premium").field()
        val withValue = withTypedRule(
            limitedBase, "SIO", errorField,
            limited.num("/Tariff/Offered/Premium").forIndex("5").gt(0),
        )
        val withPresence = withTypedRule(
            withValue, "SOP", errorField, indexed.filled(),
        )
        withTypedRule(
            withPresence, "SON", errorField, indexed.notFilled(),
        )
    }

    private val semanticGroupModel by lazy {
        withTypedRule(
            base, "SIG", bound.num("/Tariff/Offered/Premium").field(),
            Dsl.firstFilledNumber(bound.firstFilledGroupFor("/Tariff/Offered", "5")).gt(0),
        )
    }

    private val semanticFieldKeyGroupModel by lazy {
        withTypedRule(
            base, "SIGF", bound.str("/Tariff/ProductCode").field(),
            bound.str("/Tariff/ProductCode").filled().and(
                Dsl.firstFilledNumber(
                    bound.firstFilledGroupFor("/Tariff/Offered", bound.field("/Tariff/LookupKey")),
                ).eq(0),
            ),
        )
    }

    private val nestedSemanticErrorModel by lazy {
        val structure = DocumentModelDraft.named("nested-index-pointer").locale("en_US")
            .rootGroup("Tariff") { tariff ->
                tariff.group("Offered") { offered ->
                    offered.repeatable(20)
                    offered.group("Details") { details ->
                        details.repeatable(3)
                        details.numberField("Premium") { it.fractionDigits(2) }
                    }
                    offered.numberField("ItemNo") { it.fractionDigits(2) }.indexField("ItemNo")
                }
            }
            .build().toJson()
        val nested = BoundModel.ofJson(structure)
        val groupSelector = withTypedRule(
            structure,
            "SIG_NESTED",
            nested.num("/Tariff/Offered/Details/Premium").field(),
            Dsl.firstFilledNumber(nested.firstFilledGroupFor("/Tariff/Offered", "5")).gt(0),
        )
        val firstNestedRow = withTypedRule(
            groupSelector,
            "SIS_NESTED_20",
            nested.num("/Tariff/Offered/Details/Premium").field(),
            nested.num("/Tariff/Offered/Details/Premium").forIndex("5").eq(20),
        )
        withTypedRule(
            firstNestedRow,
            "SIS_NESTED_30",
            nested.num("/Tariff/Offered/Details/Premium").field(),
            nested.num("/Tariff/Offered/Details/Premium").forIndex("5").eq(30),
        )
    }

    @Test
    fun aNumericallyEqualPairIsOneKeyInTheJoinAndInTheUniquenessCheck() {
        val readings = listOf(
            "distinct keys (control)" to Doc(listOf("5" to "10", "6" to "20"), listOf("5" to "30", "7" to "40")),
            "5 and 5.00 in the same group" to Doc(listOf("5" to "10", "5.00" to "20"), listOf("6" to "40")),
            "5 and 5.00 alongside a clean 6" to
                Doc(listOf("5" to "10", "5.00" to "20", "6" to "30"), listOf("7" to "40")),
            "5 and 5 in the same group (textual control)" to
                Doc(listOf("5" to "10", "5" to "20"), listOf("6" to "40")),
            "5 here, 5.00 THERE — one joined row" to Doc(listOf("5" to "10"), listOf("5.00" to "40")),
        ).map { (label, doc) -> "$label | ${validate(joinModel, doc)}" }

        assertEquals(
            listOf(
                "distinct keys (control) | groovy=$UNMATCHED_PAIR java=$UNMATCHED_PAIR interp=$UNMATCHED_PAIR",
                "5 and 5.00 in the same group | groovy=$COLLIDED java=$COLLIDED interp=$COLLIDED",
                "5 and 5.00 alongside a clean 6 | groovy=$COLLIDED_PLUS_ROW3 java=$COLLIDED_PLUS_ROW3 " +
                    "interp=$COLLIDED_PLUS_ROW3",
                "5 and 5 in the same group (textual control) | groovy=$COLLIDED java=$COLLIDED interp=$COLLIDED",
                // The two spellings are ONE join key, so both Premiums land in a single joined row and the
                // collective-fill operator is satisfied. Keying by text yields two unmatched rows and two errors.
                "5 here, 5.00 THERE — one joined row | groovy=- java=- interp=-",
            ),
            readings,
            "`5` and `5.00` are one index key: they collide (keying nothing, and flagged uniqueIndex on BOTH " +
                "rows) within a group, and match each other across the joined groups",
        )
    }

    @Test
    fun aNumericallyEqualPairPoisonsAStarredAggregateOverTheIndexField() {
        val readings = listOf(
            "distinct keys (control)" to Doc(listOf("5" to "10", "6" to "20"), listOf()),
            "5 and 5 (textual control)" to Doc(listOf("5" to "10", "5" to "20"), listOf()),
            "5 and 5.00" to Doc(listOf("5" to "10", "5.00" to "20"), listOf()),
        ).map { (label, doc) -> "$label | ${validate(columnModel, doc, only = "VL")}" }

        assertEquals(
            listOf(
                "distinct keys (control) | groovy=$VL_FIRES java=$VL_FIRES interp=$VL_FIRES",
                "5 and 5 (textual control) | groovy=- java=- interp=-",
                "5 and 5.00 | groovy=- java=- interp=-",
            ),
            readings,
            "every cell of a duplicated index value carries the uniqueIndex error and reads UNKNOWN, so the " +
                "starred aggregate skips it — a numerically equal pair exactly as a textually equal one",
        )
    }

    @Test
    fun aNumericallyEqualTargetKeyCompromisesTheComputationColumn() {
        val readings = listOf(
            "target keys 5 and 6 (control)" to Doc(listOf("5" to "30"), listOf("5" to "0", "6" to "0")),
            "target keys 5 and 5.00" to Doc(listOf("5" to "30"), listOf("5" to "0", "5.00" to "0")),
            "target keys 5 and 5 (textual control)" to Doc(listOf("5" to "30"), listOf("5" to "0", "5" to "0")),
        ).map { (label, doc) -> "$label | ${compute(doc)}" }

        assertEquals(
            listOf(
                "target keys 5 and 6 (control) | groovy=$COMPUTED java=$COMPUTED interp=$COMPUTED",
                "target keys 5 and 5.00 | groovy=$BOTH_CLEARED java=$BOTH_CLEARED interp=$BOTH_CLEARED",
                "target keys 5 and 5 (textual control) | groovy=$BOTH_CLEARED java=$BOTH_CLEARED " +
                    "interp=$BOTH_CLEARED",
            ),
            readings,
            "a numerically duplicated TARGET index compromises the computation's whole column, clearing every " +
                "instance — the kernel's own over-marking guard (IF96/IF222), reached by the canonical key",
        )
    }

    @Test
    fun aFormallyInvalidStoredNumberIsNeverAnIndexKey() {
        val readings = listOf(
            "valid semantic-index control" to validate(
                semanticIndexModel,
                Doc(listOf("5" to "10"), emptyList()),
                only = "SI",
            ),
            "invalid semantic-index row" to validate(
                semanticIndexModel,
                Doc(listOf("05" to "10"), emptyList()),
                only = "SI",
            ),
            "invalid semantic-index key cannot satisfy presence" to validate(
                semanticIndexPresenceModel,
                Doc(listOf("05" to "10"), emptyList()),
                only = "SIP",
            ),
            "invalid semantic-index key is unknown, not absent" to validate(
                semanticIndexAbsenceModel,
                Doc(listOf("05" to "10"), emptyList()),
                only = "SIN",
            ),
            "a later admitted equal key remains selectable" to validate(
                semanticIndexPresenceModel,
                Doc(listOf("05" to "10", "5" to "20"), emptyList()),
                only = "SIP",
            ),
            "a later admitted equal key supplies the value" to validate(
                semanticIndexModel,
                Doc(listOf("05" to "10", "5" to "20"), emptyList()),
                only = "SI",
            ),
            "an earlier admitted equal key remains selectable" to validate(
                semanticIndexPresenceModel,
                Doc(listOf("5" to "20", "05" to "10"), emptyList()),
                only = "SIP",
            ),
            "two admitted peers still collide beside an invalid row" to validate(
                semanticIndexPresenceModel,
                Doc(listOf("05" to "10", "5" to "20", "5.00" to "30"), emptyList()),
                only = "SIP",
            ),
            "two admitted equal keys are unavailable to presence" to validate(
                semanticIndexPresenceModel,
                Doc(listOf("5" to "20", "5.00" to "30"), emptyList()),
                only = "SIP",
            ),
            "two malformed values are not duplicates" to validate(
                joinModel,
                Doc(listOf("x" to "10", "x" to "20"), listOf("6" to "40")),
            ),
            "an invalid far-side key invalidates its join column" to validate(
                joinModel,
                Doc(listOf("5" to "10"), listOf("x" to "40")),
            ),
            "a leading-zero value does not collide with a valid key" to validate(
                joinModel,
                Doc(listOf("5" to "10", "05" to "20"), listOf("6" to "40")),
            ),
            "an over-scale value does not collide with a valid key" to validate(
                joinModel,
                Doc(listOf("5" to "10", "5.000" to "20"), listOf("6" to "40")),
            ),
        )

        assertEquals(
            listOf(
                "valid semantic-index control | groovy=SI@/Tariff[1]/Offered[1]/Premium " +
                    "java=SI@/Tariff[1]/Offered[1]/Premium interp=SI@/Tariff[1]/Offered[1]/Premium",
                "invalid semantic-index row | groovy=- java=- interp=-",
                "invalid semantic-index key cannot satisfy presence | groovy=- java=- interp=-",
                "invalid semantic-index key is unknown, not absent | groovy=- java=- interp=-",
                "a later admitted equal key remains selectable | " +
                    "groovy=SIP@/Tariff[1]/Offered[2]/Premium " +
                    "java=SIP@/Tariff[1]/Offered[2]/Premium " +
                    "interp=SIP@/Tariff[1]/Offered[2]/Premium",
                "a later admitted equal key supplies the value | " +
                    "groovy=SI@/Tariff[1]/Offered[2]/Premium " +
                    "java=SI@/Tariff[1]/Offered[2]/Premium " +
                    "interp=SI@/Tariff[1]/Offered[2]/Premium",
                "an earlier admitted equal key remains selectable | " +
                    "groovy=SIP@/Tariff[1]/Offered[1]/Premium " +
                    "java=SIP@/Tariff[1]/Offered[1]/Premium " +
                    "interp=SIP@/Tariff[1]/Offered[1]/Premium",
                "two admitted peers still collide beside an invalid row | groovy=- java=- interp=-",
                "two admitted equal keys are unavailable to presence | groovy=- java=- interp=-",
                "two malformed values are not duplicates | " +
                    "groovy=zahlHatUngueltigeZeichen@/Tariff[1]/Offered[1]/Deductible;" +
                    "zahlHatUngueltigeZeichen@/Tariff[1]/Offered[2]/Deductible " +
                    "java=zahlHatUngueltigeZeichen@/Tariff[1]/Offered[1]/Deductible;" +
                    "zahlHatUngueltigeZeichen@/Tariff[1]/Offered[2]/Deductible " +
                    "interp=zahlHatUngueltigeZeichen@/Tariff[1]/Offered[1]/Deductible;" +
                    "zahlHatUngueltigeZeichen@/Tariff[1]/Offered[2]/Deductible",
                "an invalid far-side key invalidates its join column | " +
                    "groovy=zahlHatUngueltigeZeichen@/Tariff[1]/Quoted[1]/Deductible " +
                    "java=zahlHatUngueltigeZeichen@/Tariff[1]/Quoted[1]/Deductible " +
                    "interp=zahlHatUngueltigeZeichen@/Tariff[1]/Quoted[1]/Deductible",
                "a leading-zero value does not collide with a valid key | " +
                    "groovy=PJ@/Tariff[1]/Offered[1]/Premium;" +
                    "zahlHatFuehrendeNullen@/Tariff[1]/Offered[2]/Deductible " +
                    "java=PJ@/Tariff[1]/Offered[1]/Premium;" +
                    "zahlHatFuehrendeNullen@/Tariff[1]/Offered[2]/Deductible " +
                    "interp=PJ@/Tariff[1]/Offered[1]/Premium;" +
                    "zahlHatFuehrendeNullen@/Tariff[1]/Offered[2]/Deductible",
                "an over-scale value does not collide with a valid key | " +
                    "groovy=PJ@/Tariff[1]/Offered[1]/Premium;" +
                    "zahlFalscheAnzahlNachkommastellen@/Tariff[1]/Offered[2]/Deductible " +
                    "java=PJ@/Tariff[1]/Offered[1]/Premium;" +
                    "zahlFalscheAnzahlNachkommastellen@/Tariff[1]/Offered[2]/Deductible " +
                    "interp=PJ@/Tariff[1]/Offered[1]/Premium;" +
                    "zahlFalscheAnzahlNachkommastellen@/Tariff[1]/Offered[2]/Deductible",
            ),
            readings.map { (label, result) -> "$label | $result" },
            "stored index identity begins only after formal admission; malformed-but-parseable text cannot " +
                "collide, match, or satisfy a semantic-index lookup",
        )
    }

    @Test
    fun aFormallyInvalidIndexCellCompromisesAComputationJoin() {
        val readings = listOf(
            "valid source control" to Doc(listOf("5" to "30"), listOf("5" to "0", "6" to "0")),
            "invalid source key" to Doc(listOf("05" to "30"), listOf("5" to "0", "6" to "0")),
            "invalid target key" to Doc(listOf("5" to "30"), listOf("05" to "0")),
        ).map { (label, doc) -> "$label | ${compute(doc)}" }

        assertEquals(
            listOf(
                "valid source control | groovy=$COMPUTED java=$COMPUTED interp=$COMPUTED",
                "invalid source key | groovy=$BOTH_CLEARED java=$BOTH_CLEARED interp=$BOTH_CLEARED",
                "invalid target key | groovy=/Tariff[1]/Quoted[1]/Premium=CLEARED " +
                    "java=/Tariff[1]/Quoted[1]/Premium=CLEARED " +
                    "interp=/Tariff[1]/Quoted[1]/Premium=CLEARED",
            ),
            readings,
            "one formally invalid index cell compromises its parent-scoped computation join even without a " +
                "duplicate partner",
        )
    }

    @Test
    fun semanticIndexConsumersSelectOnlyAnAdmittedUniqueRow() {
        val readings = listOf(
            "valid control" to Doc(listOf("5" to "20"), emptyList()),
            "invalid then valid" to Doc(listOf("05" to "10", "5" to "20"), emptyList()),
            "invalid plus two admitted duplicates" to
                Doc(listOf("05" to "10", "5" to "20", "5.00" to "30"), emptyList()),
        ).map { (label, doc) -> "$label | ${validate(semanticGroupModel, doc, only = "SIG")}" }
        val twoKeys = validate(
            twoSemanticKeysModel,
            Doc(listOf("5" to "10", "6" to "20"), emptyList()),
            only = "SIM",
        )
        val twoKeysReversed = validate(
            twoSemanticKeysModel,
            Doc(listOf("6" to "20", "5" to "10"), emptyList()),
            only = "SIM",
        )
        val sameKeyDifferentPayloads = validate(
            sameSemanticKeyPayloadsModel,
            Doc(listOf("5" to "10", "6" to "20"), emptyList()),
            only = "SIK",
        )
        val sameCanonicalKeyDifferentSpellings = validate(
            canonicalSameSemanticKeyPayloadsModel,
            Doc(listOf("6" to "10", "5.00" to "20"), emptyList()),
            only = "SIC",
        )
        val distinctFieldExpressionPointers = listOf(
            "same selected row" to Triple(twoSemanticFieldKeysModel, "SIFK", "5.00"),
            "different selected rows" to Triple(twoSemanticFieldKeysModel, "SIFK", "6"),
            "reversed operands, different selected rows" to
                Triple(twoSemanticFieldKeysReversedModel, "SIFR", "6"),
            "one match and one clean no-match" to Triple(twoSemanticFieldKeysModel, "SIFK", "7"),
        ).map { (label, modelAndThreshold) ->
            val (model, code, threshold) = modelAndThreshold
            val output = validate(
                model,
                Doc(
                    listOf("6" to "10", "5.00" to "20"),
                    emptyList(),
                    lookupKey = "5",
                    threshold = threshold,
                ),
                only = code,
            )
            "$label | $output"
        }
        val distinctLiteralSpellingsSelectingTheSameRow = validate(
            twoLiteralSpellingsSamePayloadModel,
            Doc(listOf("6" to "10", "5.00" to "20"), emptyList()),
            only = "SILS",
        )
        val nestedErrorPointer = validateDocuments(
            nestedSemanticErrorModel,
            setOf("SIG_NESTED", "SIS_NESTED_20", "SIS_NESTED_30"),
            kernelDocument = {
                RuntimeLaws.doc().group("/Tariff", 1)
                    .group("/Tariff/Offered", 1, 1)
                    .field("/Tariff/Offered/ItemNo", "6", 1, 1, 1)
                    .group("/Tariff/Offered/Details", 1, 1, 1)
                    .field("/Tariff/Offered/Details/Premium", "1", 1, 1, 1, 1)
                    .group("/Tariff/Offered", 1, 2)
                    .field("/Tariff/Offered/ItemNo", "5", 1, 2, 1)
                    .group("/Tariff/Offered/Details", 1, 2, 1)
                    .field("/Tariff/Offered/Details/Premium", "20", 1, 2, 1, 1)
                    .group("/Tariff/Offered/Details", 1, 2, 2)
                    .field("/Tariff/Offered/Details/Premium", "30", 1, 2, 2, 1)
            },
            interpreterDocument = { def ->
                def.newDocument().group("/Tariff", 1)
                    .group("/Tariff/Offered", 1, 1)
                    .field("/Tariff/Offered/ItemNo", "6", 1, 1, 1)
                    .group("/Tariff/Offered/Details", 1, 1, 1)
                    .field("/Tariff/Offered/Details/Premium", "1", 1, 1, 1, 1)
                    .group("/Tariff/Offered", 1, 2)
                    .field("/Tariff/Offered/ItemNo", "5", 1, 2, 1)
                    .group("/Tariff/Offered/Details", 1, 2, 1)
                    .field("/Tariff/Offered/Details/Premium", "20", 1, 2, 1, 1)
                    .group("/Tariff/Offered/Details", 1, 2, 2)
                    .field("/Tariff/Offered/Details/Premium", "30", 1, 2, 2, 1)
            },
        )

        assertEquals(
            listOf(
                "valid control | groovy=SIG@/Tariff[1]/Offered[1]/Premium " +
                    "java=SIG@/Tariff[1]/Offered[1]/Premium interp=SIG@/Tariff[1]/Offered[1]/Premium",
                "invalid then valid | groovy=SIG@/Tariff[1]/Offered[2]/Premium " +
                    "java=SIG@/Tariff[1]/Offered[2]/Premium interp=SIG@/Tariff[1]/Offered[2]/Premium",
                "invalid plus two admitted duplicates | groovy=- java=- interp=-",
            ),
            readings,
            "the contained-field scan and the scalar semantic index share admission, collision, row selection, " +
                "and the selected message-pointer context",
        )
        assertEquals(
            "groovy=SIM@/Tariff[1]/Offered[1]/Premium " +
                "java=SIM@/Tariff[1]/Offered[1]/Premium " +
                "interp=SIM@/Tariff[1]/Offered[1]/Premium",
            twoKeys,
            "two distinct semantic keys addressing the repeated error field",
        )
        assertEquals(
            "groovy=SIM@/Tariff[1]/Offered[1]/Premium " +
                "java=SIM@/Tariff[1]/Offered[1]/Premium " +
                "interp=SIM@/Tariff[1]/Offered[1]/Premium",
            twoKeysReversed,
            "multiple distinct semantic targets fall back to ordinary error-field iteration; reversing which key " +
                "occupies row 1 discriminates that from reanchoring to the first authored key",
        )
        assertEquals(
            "groovy=SIK@/Tariff[1]/Offered[2]/Premium " +
                "java=SIK@/Tariff[1]/Offered[2]/Premium " +
                "interp=SIK@/Tariff[1]/Offered[2]/Premium",
            sameKeyDifferentPayloads,
            "two payload fields selected through the same semantic group and key are one row target; the " +
                "payload path must not force ordinary row-1 pointer fallback",
        )
        assertEquals(
            "groovy=SIC@/Tariff[1]/Offered[2]/Premium " +
                "java=SIC@/Tariff[1]/Offered[2]/Premium " +
                "interp=SIC@/Tariff[1]/Offered[2]/Premium",
            sameCanonicalKeyDifferentSpellings,
            "a sibling payload reference does not add an error-field target; the Premium reference alone " +
                "reanchors to its canonically selected Number row",
        )
        assertEquals(
            listOf(
                "same selected row | groovy=SIFK@/Tariff[1]/Offered[1]/Premium " +
                    "java=SIFK@/Tariff[1]/Offered[1]/Premium " +
                    "interp=SIFK@/Tariff[1]/Offered[1]/Premium",
                "different selected rows | groovy=SIFK@/Tariff[1]/Offered[1]/Premium " +
                    "java=SIFK@/Tariff[1]/Offered[1]/Premium " +
                    "interp=SIFK@/Tariff[1]/Offered[1]/Premium",
                "reversed operands, different selected rows | " +
                    "groovy=SIFR@/Tariff[1]/Offered[1]/Premium " +
                    "java=SIFR@/Tariff[1]/Offered[1]/Premium " +
                    "interp=SIFR@/Tariff[1]/Offered[1]/Premium",
                "one match and one clean no-match | groovy=SIFK@/Tariff[1]/Offered[1]/Premium " +
                    "java=SIFK@/Tariff[1]/Offered[1]/Premium " +
                    "interp=SIFK@/Tariff[1]/Offered[1]/Premium",
            ),
            distinctFieldExpressionPointers,
            "two distinct authored field-key expressions remain two static semantic targets regardless of " +
                "their current selected rows, operand order, or a clean no-match",
        )
        assertEquals(
            "groovy=SILS@/Tariff[1]/Offered[1]/Premium " +
                "java=SILS@/Tariff[1]/Offered[1]/Premium " +
                "interp=SILS@/Tariff[1]/Offered[1]/Premium",
            distinctLiteralSpellingsSelectingTheSameRow,
            "pointer-target identity preserves the authored literal expression: canonical runtime-key equality " +
                "does not merge two error-field targets",
        )
        assertEquals(
            "groovy=SIG_NESTED@/Tariff[1]/Offered[2]/Details[1]/Premium;" +
                "SIS_NESTED_20@/Tariff[1]/Offered[2]/Details[1]/Premium;" +
                "SIS_NESTED_30@/Tariff[1]/Offered[2]/Details[2]/Premium " +
                "java=SIG_NESTED@/Tariff[1]/Offered[2]/Details[1]/Premium;" +
                "SIS_NESTED_20@/Tariff[1]/Offered[2]/Details[1]/Premium;" +
                "SIS_NESTED_30@/Tariff[1]/Offered[2]/Details[2]/Premium " +
                "interp=SIG_NESTED@/Tariff[1]/Offered[2]/Details[1]/Premium;" +
                "SIS_NESTED_20@/Tariff[1]/Offered[2]/Details[1]/Premium;" +
                "SIS_NESTED_30@/Tariff[1]/Offered[2]/Details[2]/Premium",
            nestedErrorPointer,
            "group-valued and scalar selectors both inherit the nearest ancestor index; the selected outer row " +
                "supplies its coordinate while descendant repeatable rows remain independently iterable",
        )
    }

    @Test
    fun anOverLimitRowNeverEntersTheSemanticIndex() {
        val documents = listOf(
            "the selected row is inside the declared range (control)" to
                Doc(listOf("6" to "0", "5" to "20"), emptyList()),
            "the selected row is past the declared range" to
                Doc(listOf("6" to "0", "7" to "0", "5" to "20"), emptyList()),
        )
        val readings = documents.map { (label, doc) ->
            "$label | ${validate(overLimitSemanticModel, doc, only = "SIO")}"
        }

        assertEquals(
            listOf(
                "the selected row is inside the declared range (control) | " +
                    "groovy=SIO@/Tariff[1]/Offered[2]/Premium " +
                    "java=SIO@/Tariff[1]/Offered[2]/Premium " +
                    "interp=SIO@/Tariff[1]/Offered[2]/Premium",
                "the selected row is past the declared range | groovy=- java=- interp=-",
            ),
            readings,
            "an instantiated over-limit row remains reportable as malformed structure but is outside every " +
            "index identity cache",
        )
        val presenceReadings = documents.map { (label, doc) ->
            "$label | ${validate(overLimitSemanticModel, doc, onlyCodes = setOf("SOP", "SON"))}"
        }
        assertEquals(
            listOf(
                "the selected row is inside the declared range (control) | " +
                    "groovy=SOP@/Tariff[1]/Offered[2]/Premium " +
                    "java=SOP@/Tariff[1]/Offered[2]/Premium " +
                    "interp=SOP@/Tariff[1]/Offered[2]/Premium",
                "the selected row is past the declared range | " +
                    "groovy=SON@/Tariff[1]/Offered[-5]/Premium " +
                    "java=SON@/Tariff[1]/Offered[-5]/Premium " +
                    "interp=SON@/Tariff[1]/Offered[-5]/Premium",
            ),
            presenceReadings,
            "an over-limit row is outside the cache rather than an unavailable in-cache cell: it neither " +
                "selects nor compromises the clean no-match result",
        )
    }

    @Test
    fun anEmptyFieldValuedKeyCannotSelectAnEmptyIndexCell() {
        val readings = listOf(
            "filled field-valued key (control)" to
                Doc(listOf("5" to "20"), emptyList(), lookupKey = "5"),
            "empty query and empty stored index cell" to
                Doc(listOf("" to "20"), emptyList(), lookupKey = ""),
            "empty query beside a filled stored key" to
                Doc(listOf("5" to "20"), emptyList(), lookupKey = ""),
        ).map { (label, doc) ->
            "$label | ${validate(semanticFieldKeyModel, doc, onlyCodes = setOf("SIF", "SIN"))}"
        }

        assertEquals(
            listOf(
                "filled field-valued key (control) | groovy=SIF@/Tariff[1]/Offered[1]/Premium " +
                    "java=SIF@/Tariff[1]/Offered[1]/Premium " +
                    "interp=SIF@/Tariff[1]/Offered[1]/Premium",
                "empty query and empty stored index cell | groovy=- java=- interp=-",
                "empty query beside a filled stored key | " +
                    "groovy=SIN@/Tariff[1]/Offered[-5]/Premium " +
                    "java=SIN@/Tariff[1]/Offered[-5]/Premium " +
                    "interp=SIN@/Tariff[1]/Offered[-5]/Premium",
            ),
            readings,
            "an empty field-valued query is a definite absence only for a clean column; an empty stored index " +
                "cell compromises the lookup to UNKNOWN, so neither presence predicate fires",
        )

        val groupReadings = listOf(
            "clean column (control)" to Doc(listOf("5" to "20"), emptyList(), lookupKey = ""),
            "empty stored index compromises the group selector" to
                Doc(listOf("" to "20"), emptyList(), lookupKey = ""),
        ).map { (label, doc) -> "$label | ${validate(semanticFieldKeyGroupModel, doc, only = "SIGF")}" }
        assertEquals(
            listOf(
                "clean column (control) | groovy=SIGF@/Tariff[1]/ProductCode " +
                    "java=SIGF@/Tariff[1]/ProductCode interp=SIGF@/Tariff[1]/ProductCode",
                "empty stored index compromises the group selector | groovy=- java=- interp=-",
            ),
            groupReadings,
            "the contained-field semantic scan shares the scalar lookup's empty-query 3VL classification",
        )
    }

    @Test
    fun partialRelevanceAndFormalAdmissionAreIndependentPreconditionsOfIdentity() {
        val allPayloads = listOf(
            Sel("/Tariff/Offered/Premium", ALL),
            Sel("/Tariff/Quoted/Premium", ALL),
            Sel("/Tariff/Quoted/Deductible", ALL),
        )
        val readings = listOf(
            "admitted and relevant control" to (
                Doc(listOf("5" to "10"), listOf("6" to "40")) to
                    (allPayloads + Sel("/Tariff/Offered/Deductible", ALL))
                ),
            "formally invalid and relevant" to (
                Doc(listOf("05" to "10"), listOf("6" to "40")) to
                    (allPayloads + Sel("/Tariff/Offered/Deductible", ALL))
                ),
            "formally invalid and non-relevant" to (
                Doc(listOf("05" to "10"), listOf("6" to "40")) to allPayloads
                ),
        ).map { (label, pair) ->
            val (doc, relevant) = pair
            "$label | ${validatePart(joinModel, doc, relevant, only = "PJ")}"
        }

        assertEquals(
            listOf(
                "admitted and relevant control | " +
                    "groovy=$PJ_NONE;$PJ_ROW1 java=$PJ_NONE;$PJ_ROW1 interp=$PJ_NONE;$PJ_ROW1",
                "formally invalid and relevant | groovy=- java=- interp=-",
                "formally invalid and non-relevant | groovy=- java=- interp=-",
            ),
            readings,
            "relevance and formal admission are separate gates, but failing either contributes no key and " +
                "compromises the unmatched-column result",
        )
    }

    @Test
    fun aNonRelevantSemanticIndexCellIsUnknownRatherThanSelectedOrAbsent() {
        val payload = listOf(Sel("/Tariff/Offered/Premium", ALL))
        val complete = payload + Sel("/Tariff/Offered/Deductible", ALL)
        val readings = listOf(
            "relevant matching key makes presence true (control)" to validatePart(
                semanticIndexPresenceModel,
                Doc(listOf("5" to "10"), emptyList()),
                complete,
                only = "SIP",
            ),
            "relevant clean no-match makes absence true (control)" to validatePart(
                semanticIndexAbsenceModel,
                Doc(listOf("6" to "10"), emptyList()),
                complete,
                only = "SIN",
            ),
            "non-relevant matching key cannot satisfy presence" to validatePart(
                semanticIndexPresenceModel,
                Doc(listOf("5" to "10"), emptyList()),
                payload,
                only = "SIP",
            ),
            "non-relevant matching key is not a definite absence" to validatePart(
                semanticIndexAbsenceModel,
                Doc(listOf("5" to "10"), emptyList()),
                payload,
                only = "SIN",
            ),
        ).map { (label, result) -> "$label | $result" }

        assertEquals(
            listOf(
                "relevant matching key makes presence true (control) | " +
                    "groovy=SIP@/Tariff[1]/Offered[1]/Premium " +
                    "java=SIP@/Tariff[1]/Offered[1]/Premium " +
                    "interp=SIP@/Tariff[1]/Offered[1]/Premium",
                "relevant clean no-match makes absence true (control) | " +
                    "groovy=SIN@/Tariff[1]/Offered[-5]/Premium " +
                    "java=SIN@/Tariff[1]/Offered[-5]/Premium " +
                    "interp=SIN@/Tariff[1]/Offered[-5]/Premium",
                "non-relevant matching key cannot satisfy presence | groovy=- java=- interp=-",
                "non-relevant matching key is not a definite absence | groovy=- java=- interp=-",
            ),
            readings,
            "partial semantic lookup short-circuits relevance before admission; an excluded key leaves the " +
                "column unknown, so neither positive nor negative presence fires",
        )
    }

    private fun validate(
        model: String,
        doc: Doc,
        only: String? = null,
        onlyCodes: Set<String>? = only?.let(::setOf),
    ): String {
        return validateDocuments(
            model,
            onlyCodes,
            kernelDocument = { kernelDoc(doc) },
            interpreterDocument = { def -> interpreterDoc(def, doc) },
        )
    }

    private fun validateDocuments(
        model: String,
        onlyCodes: Set<String>?,
        kernelDocument: () -> RuntimeLaws.Doc,
        interpreterDocument: (ModelDef) -> Document,
    ): String {
        val def = ModelLoader.load(model)
        fun sig(v: List<String>) = v.sorted().joinToString(";").ifEmpty { "-" }
        val dynamic = sig(RuntimeLaws.messages(model, kernelDocument())
            .filter { onlyCodes == null || it.code() in onlyCodes }.map { "${it.code()}@${it.field()}" })
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val static = sig(engine.messages(engine.instance(kernelDocument()))
            .filter { onlyCodes == null || it.code() in onlyCodes }.map { "${it.code()}@${it.field()}" })
        val interp = sig(DmInterpreter(def).validateFull(interpreterDocument(def))
            .filter { onlyCodes == null || it.code in onlyCodes }.map { "${it.code}@${it.errorPath}" })
        return "groovy=$dynamic java=$static interp=$interp"
    }

    private fun validatePart(model: String, doc: Doc, relevant: List<Sel>, only: String): String {
        val def = ModelLoader.load(model)
        val kernelRelevant = relevant.mapTo(HashSet()) { Relevant.of(it.path, *it.reps.toIntArray()) }
        val interpreterRelevant = relevant.mapTo(HashSet()) { RelevantEntity(it.path, it.reps) }
        val kernelDoc = kernelDoc(doc)
        fun sig(values: List<String>) = values.sorted().joinToString(";").ifEmpty { "-" }
        val dynamic = sig(RuntimeLaws.messagesPart(model, kernelDoc, kernelRelevant)
            .filter { it.code() == only }.map { "${it.code()}@${it.field()}" })
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = sig(engine.messagesPart(kernelDoc, kernelRelevant)
            .filter { it.code() == only }.map { "${it.code()}@${it.field()}" })
        val interpreter = sig(DmInterpreter(def).validatePart(interpreterDoc(def, doc), interpreterRelevant).messages
            .filter { it.code == only }.map { "${it.code}@${it.errorPath}" })
        return "groovy=$dynamic java=$javaStatic interp=$interpreter"
    }

    private fun compute(doc: Doc): String {
        val def = ModelLoader.load(computeModel)
        fun kernel(c: RuntimeLaws.Computed) =
            if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"
        val dynamic = RuntimeLaws.compute(computeModel, kernelDoc(doc))
            .map { "${it.pointer()}=${kernel(it)}" }.sorted().joinToString(";")
        val engine = RuntimeLaws.kernelJavaEngine(computeModel)
        val static = engine.compute(engine.instance(kernelDoc(doc)))
            .map { "${it.pointer()}=${kernel(it)}" }.sorted().joinToString(";")
        val interp = DmInterpreter(def).compute(interpreterDoc(def, doc)).map {
            val outcome = when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
            }
            "${formatPointer(it.path, it.indices)}=$outcome"
        }.sorted().joinToString(";")
        return "groovy=$dynamic java=$static interp=$interp"
    }

    private fun kernelDoc(doc: Doc) = RuntimeLaws.doc().also { k ->
        doc.place({ p, r -> k.group(p, *r) }, { p, v, r -> k.field(p, v, *r) })
    }

    private fun interpreterDoc(def: ModelDef, doc: Doc): Document =
        def.newDocument().also { i -> doc.place({ p, r -> i.group(p, *r) }, { p, v, r -> i.field(p, v, *r) }) }

    /** One document shape: the two sibling groups' `(Deductible, Premium)` rows. */
    private data class Doc(
        val offered: List<Pair<String, String>>,
        val quoted: List<Pair<String, String>>,
        val lookupKey: String = "5",
        val threshold: String = "5",
    ) {
        fun place(group: (String, IntArray) -> Unit, field: (String, String, IntArray) -> Unit) {
            group("/Tariff", intArrayOf(1))
            field("/Tariff/ProductCode", "COMP-1", intArrayOf(1, 1))
            field("/Tariff/LookupKey", lookupKey, intArrayOf(1, 1))
            field("/Tariff/Threshold", threshold, intArrayOf(1, 1))
            rows("/Tariff/Offered", offered, group, field)
            rows("/Tariff/Quoted", quoted, group, field)
        }

        private fun rows(g: String, values: List<Pair<String, String>>, group: (String, IntArray) -> Unit,
                field: (String, String, IntArray) -> Unit) =
            values.forEachIndexed { i, (deductible, premium) ->
                val r = i + 1
                group(g, intArrayOf(1, r))
                field("$g/Deductible", deductible, intArrayOf(1, r, 1))
                field("$g/Premium", premium, intArrayOf(1, r, 1))
                field("$g/Limit", premium, intArrayOf(1, r, 1))
            }
    }

    private data class Sel(val path: String, val reps: List<Int>)

    private companion object {
        private const val DUP1 = "uniqueIndex@/Tariff[1]/Offered[1]/Deductible"
        private const val DUP2 = "uniqueIndex@/Tariff[1]/Offered[2]/Deductible"
        private const val PJ_ROW2 = "PJ@/Tariff[1]/Offered[2]/Premium"
        private const val PJ_ROW3 = "PJ@/Tariff[1]/Offered[3]/Premium"
        private const val PJ_ROW1 = "PJ@/Tariff[1]/Offered[1]/Premium"

        /** The kernel's `IIdentifier.UNKNOWN` repetition — Offered has no row for the joined value. */
        private const val PJ_NONE = "PJ@/Tariff[1]/Offered[-5]/Premium"

        /** A valid index on both sides: this side's unmatched key fires at row 2, the far side's at `[-5]`. */
        const val UNMATCHED_PAIR = "$PJ_NONE;$PJ_ROW2"

        /** The colliding value keys nothing on either side, so no join row survives to report. */
        const val COLLIDED = "$DUP1;$DUP2"
        const val COLLIDED_PLUS_ROW3 = "$PJ_ROW3;$DUP1;$DUP2"

        const val VL_FIRES = "VL@/Tariff[1]/Offered[1]/Deductible"
        const val COMPUTED = "/Tariff[1]/Quoted[1]/Premium=VALUE|30;/Tariff[1]/Quoted[2]/Premium=VALUE|0"
        const val BOTH_CLEARED = "/Tariff[1]/Quoted[1]/Premium=CLEARED;/Tariff[1]/Quoted[2]/Premium=CLEARED"
        val ALL = listOf(0, 0, 0)
    }
}
