package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.function.Consumer

/**
 * `FieldValuesNotUnique(f1, f2, …)` against the kernel — fires iff two of the listed fields hold the **same
 * value** (their compared values are not all distinct). Probed semantics (KERNEL-FINDINGS): a VALUE error;
 * **empties are skipped** (two empties are NOT a duplicate); equality is **numeric** (`5` == `5.00` across
 * different-scale fields) for numbers and string equality for strings; a filled-but-**unconvertible** operand is
 * likewise **skipped** rather than making the rule non-evaluable (two equal unconvertible values do not duplicate,
 * and a duplicate elsewhere still fires — see [theOperandScanIsOrderedAndReturnsAtTheDuplicate], which measured
 * that and corrected the earlier "suppressed" reading). On the non-repeatable `/Order`
 * (`Quantity`/`BackorderQuantity`/`StockOnHand` scale 0, `UnitPrice` scale 2, `CustomerName`/`ProductCode`
 * strings).
 */
class FieldValuesNotUniqueDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(BACK, Kind.NUMBER, scale = 0),
        FieldDef(STOCK, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2),
        FieldDef(NAME, Kind.STRING), FieldDef(CODE, Kind.STRING),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. ABSOLUTE paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Inject [cond] at /Order (error field [errorRel] — must be referenced in the condition), set each
     *  (path → value) cell (null = leave empty), assert firing agrees with the kernel. */
    private fun agree(cond: String, vararg cells: Pair<String, String?>, errorRel: String = "../Quantity", msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "FU", errorRel, "FU", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/FU", "/Order", cond, "FU", errorRel)))
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument()
            .group("/Order", 1)
        for ((path, value) in cells) if (value != null) {
            kDoc.field(path, value, 1, 1); iDoc.field(path, value, 1, 1)
        }
        val kernel = RuntimeLaws.fires(kModel, "FU", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "FU" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val two = render(Dsl.fieldValuesNotUnique(m.field(QTY), m.field(BACK)))
    private val three = render(Dsl.fieldValuesNotUnique(m.field(QTY), m.field(BACK), m.field(STOCK)))

    @Test fun twoEqualFires() = agree(two, QTY to "5", BACK to "5", msg = "5 == 5 → not all distinct → fires")
    @Test fun twoDistinctDoesNotFire() = agree(two, QTY to "5", BACK to "9", msg = "5 != 9 → distinct → not fire")
    @Test fun oneEmptyDoesNotFire() = agree(two, QTY to "5", BACK to null, msg = "only one value → no duplicate")
    @Test fun bothEmptyDoesNotFire() = agree(two, QTY to null, BACK to null, msg = "two empties are NOT a duplicate")
    // Named for what it can actually establish. It was `malformedOperandSuppresses`, but this document has no
    // duplicate to begin with once "abc" is out of the comparison, so "does not fire" is the same observation under
    // both the suppression and the skip reading — and [theOperandScanIsOrderedAndReturnsAtTheDuplicate] measured the
    // skip. Kept as the boolean control; cases 8 and 11 there are what discriminate.
    @Test fun oneUnconvertibleAndOneDistinctValueDoNotFire() =
        agree(two, QTY to "abc", BACK to "5", msg = "\"abc\" is skipped, leaving one value → no duplicate")

    @Test fun threeWithTwoEqualFires() = agree(three, QTY to "5", BACK to "9", STOCK to "5", msg = "two of three equal → fires")
    @Test fun threeAllDistinctDoesNotFire() = agree(three, QTY to "5", BACK to "9", STOCK to "3", msg = "all distinct → not fire")
    @Test fun threeTwoEqualPlusEmptyFires() = agree(three, QTY to "5", BACK to "5", STOCK to null, msg = "the two 5s duplicate; empty skipped")
    @Test fun threeOneFilledTwoEmptyDoesNotFire() = agree(three, QTY to "5", BACK to null, STOCK to null, msg = "one value, empties skipped → no duplicate")

    @Test fun crossScaleNumericEqualFires() =
        agree(render(Dsl.fieldValuesNotUnique(m.field(QTY), m.field(PRICE))), QTY to "5", PRICE to "5.00", msg = "5 == 5.00 numerically across scales → fires")

    @Test fun equalStringsFires() =
        agree(render(Dsl.fieldValuesNotUnique(m.field(NAME), m.field(CODE))), NAME to "AB", CODE to "AB",
            errorRel = "../CustomerName", msg = "\"AB\" == \"AB\" → fires")

    @Test fun distinctStringsDoesNotFire() =
        agree(render(Dsl.fieldValuesNotUnique(m.field(NAME), m.field(CODE))), NAME to "AB", CODE to "CD",
            errorRel = "../CustomerName", msg = "\"AB\" != \"CD\" → not fire")

    // The STARRED form `FieldValuesNotUnique(Items* / Count)` — cross-ROW distinctness of one repeatable field
    // (the no-`--group` alternative to RepetitionNotUnique). Count is non-index (Sku is the index field, kept
    // distinct per row so its auto uniqueIndex/mandatory checks don't pollute the probe).
    private val itemFields = listOf(FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER, scale = 0))

    private fun starAgree(cond: String, counts: List<String?>, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "FU", "../Items/Count", "FU", cond)
        val def = ModelDef(
            itemFields, listOf(RuleDef("/Order/FU", "/Order", cond, "FU", "../Items/Count")),
            repeatableGroups = setOf(ITEMS), declaredReps = mapOf(ITEMS to 100), indexFields = setOf(SKU),
        )
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument()
            .group("/Order", 1)
        counts.forEachIndexed { i, c ->
            val r = i + 1
            kDoc.group(ITEMS, 1, r).field(SKU, "K$r", 1, r, 1)
            iDoc.group(ITEMS, 1, r).field(SKU, "K$r", 1, r, 1)
            if (c != null) { kDoc.field(COUNT, c, 1, r, 1); iDoc.field(COUNT, c, 1, r, 1) }
        }
        val kernel = RuntimeLaws.fires(kModel, "FU", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "FU" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val star = render(Dsl.fieldValuesNotUnique(m.flatten("/Order/Items*/Count")))

    @Test fun starredCrossRowDupFires() = starAgree(star, listOf("5", "9", "5"), msg = "two rows Count=5 → fires")
    @Test fun starredCrossRowDistinctDoesNotFire() = starAgree(star, listOf("5", "9", "3"), msg = "all distinct rows → not fire")
    @Test fun starredEmptyRowSkippedStillDup() = starAgree(star, listOf("5", null, "5"), msg = "empty row skipped; the two 5s duplicate → fires")
    @Test fun starredDistinctWithEmptyDoesNotFire() = starAgree(star, listOf("5", null, "9"), msg = "distinct rows + empty → not fire")
    @Test fun starredSingleRowDoesNotFire() = starAgree(star, listOf("5"), msg = "one value → no duplicate")

    @Test fun starredDuplicateEmitsOneFirstRowMessageWithItsWildcardReferenceInBothPlacements() {
        val having = render(Dsl.fieldValuesNotUnique(
            m.flatten("/Order/Items*/Count").having(m.str(SKU).ne("K0")),
        ))
        for ((group, errorRel) in listOf(
            ITEMS to "../Count",
            "/Order" to "../Items/Count",
        )) {
            for ((condition, expected) in listOf(
                havingGeq2 to "OMISSION_ERROR|[/Order[1]/Items[0]/Count]",
                having to "OMISSION_ERROR|[/Order[1]/Items[0]/Count, /Order[1]/Items[0]/Sku]",
                star to "VALUE_ERROR|[/Order[1]/Items[0]/Count]",
            )) {
                val kModel = RuntimeLaws.withRule(order, group, "FU", errorRel, "FU", condition)
                val def = ModelLoader.load(kModel)

                fun documents(): Pair<RuntimeLaws.Doc, io.github.mbackschat.a12.dm.interpreter.eval.Document> {
                    val kDoc = RuntimeLaws.doc().group("/Order", 1)
                    val iDoc = def.newDocument().group("/Order", 1)
                    listOf("7", "5", "5").forEachIndexed { index, count ->
                        val row = index + 1
                        kDoc.group(ITEMS, 1, row).field(SKU, "K$row", 1, row, 1)
                        kDoc.field(COUNT, count, 1, row, 1)
                        iDoc.group(ITEMS, 1, row).field(SKU, "K$row", 1, row, 1)
                        iDoc.field(COUNT, count, 1, row, 1)
                    }
                    return kDoc to iDoc
                }

                val (dynamicDoc, interpreterDoc) = documents()
                val dynamic = RuntimeLaws.messages(kModel, dynamicDoc).filter { it.code() == "FU" }
                    .map { "${it.field()}|${it.type().name}|${it.referenced().sorted()}" }
                val (staticDoc, _) = documents()
                val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
                    .messagesModern(staticDoc, null, java.util.Locale.US).filter { it.code() == "FU" }
                    .map { "${it.field()}|${it.type().name}|${it.referenced().sorted()}" }
                val interpreter = DmInterpreter(def).validateFull(interpreterDoc).filter { it.code == "FU" }
                    .map { "${it.errorPath}|${if (it.type.name == "OMISSION") "OMISSION_ERROR" else "VALUE_ERROR"}|${it.referenced.sorted()}" }
                val expectedMessages = listOf("/Order[1]/Items[1]/Count|$expected")

                assertEquals(expectedMessages, dynamic, "$group — dynamic kernel exact message")
                assertEquals(dynamic, javaStatic, "$group — dynamic/static kernel exact message")
                assertEquals(dynamic, interpreter, "$group — kernel/interpreter exact message")
            }
        }
    }

    // A `Having` on the starred operand — kernel-probed to be ACCEPTED + EVALUATED (unlike SumOfProducts, which
    // the kernel authoring-rejects). The keep-law is the overlap-predicate one (keep iff the filter is TRUE);
    // filtering the rows CHANGES which values are compared for distinctness (IF72).
    private val items = m.group("/Order/Items")
    private val havingLeq2 = render(Dsl.fieldValuesNotUnique(m.flatten("/Order/Items*/Count").having(Dsl.currentRepetition(items).le(2))))
    private val havingGeq2 = render(Dsl.fieldValuesNotUnique(m.flatten("/Order/Items*/Count").having(Dsl.currentRepetition(items).ge(2))))

    @Test fun starHavingKeepsDistinctDoesNotFire() =
        starAgree(havingLeq2, listOf("5", "9", "9"), msg = "Having <=2 keeps rows 1,2 = [5,9] distinct → not fire (filter evaluated)")
    @Test fun starHavingKeepsDuplicateFires() =
        starAgree(havingGeq2, listOf("5", "9", "9"), msg = "Having >=2 keeps rows 2,3 = [9,9] duplicate → fires (filter evaluated)")

    /**
     * SPEC-2026-08-05-01 — the operand list is consumed as an **ORDERED scan that returns at the first duplicate**, so
     * a later operand's contribution is unreachable once the duplicate is found. The rival account (the operand list
     * is inspected as a whole, and a filter anywhere escalates) agrees on every previously measured shape, because
     * those all had one starred operand and nothing after it.
     *
     * <p>All thirteen rows are measured, and two of the three engine readings each was asserted against are the real
     * kernel: each case pins the fired message TYPE against dynamic-Groovy and requires the full
     * `field|type|referenced` triple to agree across dynamic kernel, static-Java kernel, and interpreter.
     *
     * <p><b>The scan order (1–3, 13).</b> A filter escalates the firing to OMISSION only through a cell that actually
     * yielded a compared value, and only if that happened before the duplicate:
     *
     * | # | list order | the filtered operand contributes | measured | whole-list account |
     * |---|---|---|---|---|
     * | 1 | duplicate first, filter after | (never reached) | VALUE | OMISSION |
     * | 2 | filter first, duplicate after | present values | OMISSION | OMISSION |
     * | 3 | filter first, duplicate after | only empty cells | VALUE | OMISSION |
     * | 13 | filter first, duplicate after | only unconvertible cells | VALUE | OMISSION |
     *
     * 1, 3 and 13 are the discriminators; 2 is the positive control that OMISSION is reachable at all on this shape,
     * without which "always VALUE" would satisfy the other three. Case 13 is also what places the escalation relative
     * to the skip: a present-but-unconvertible cell does <em>not</em> escalate.
     *
     * <p><b>An unconvertible cell is SKIPPED, not a suppressor (4–8, 11–12)</b> — the neighbouring fact measuring the
     * scan order turned up, and the reason the boolean `malformedOperandSuppresses` case above had to be renamed. A stored value that
     * cannot be converted to the declared type contributes nothing, exactly like an empty cell, in a starred operand
     * (4, 5) and in a direct one (6) alike; a duplicate elsewhere in the list still fires. What makes this a skip
     * rather than a fire-anyway is cases 8 and 11: two <em>equal</em> unconvertible values do not duplicate, because
     * neither ever enters the comparison. Measured on Number (8) and on Date (11–12), so the interpreter's
     * kind-agnostic implementation is not resting on a Number-only basis. Case 7 is the positive control that the
     * starred operand is genuinely read, by making a starred row the duplicate's partner.
     *
     * <p><b>Cross-scale equality is order-independent (9–10).</b> The controls that refute the rival explanation of
     * case 5 — that the comparison mode is selected by whether the first operand is a direct Number field, which
     * would compare stored text when a starred operand leads. It does not: `5` duplicates `5.00` either way round.
     */
    @Test fun theOperandScanIsOrderedAndReturnsAtTheDuplicate() {
        val keptFromRowTwo = Dsl.currentRepetition(items).ge(2)
        val counts = m.flatten("/Order/Items*/Count")
        val dupFirst = render(Dsl.fieldValuesNotUnique(m.field(QTY), m.field(BACK), counts.having(keptFromRowTwo)))
        val filterFirst = render(Dsl.fieldValuesNotUnique(counts.having(keptFromRowTwo), m.field(QTY), m.field(BACK)))
        val dupBeforeStar = render(Dsl.fieldValuesNotUnique(m.field(QTY), m.field(BACK), counts))
        val starBeforeDup = render(Dsl.fieldValuesNotUnique(counts, m.field(QTY), m.field(BACK)))
        val duplicatePair = listOf(QTY to "5", BACK to "5")

        val cases = listOf(
            OrderedScanCase("1 duplicate before the filter", dupFirst, duplicatePair, listOf("7", "8", "9"), "VALUE_ERROR"),
            OrderedScanCase("2 filter before the duplicate, kept rows filled", filterFirst, duplicatePair, listOf("7", "8", "9"), "OMISSION_ERROR"),
            OrderedScanCase("3 filter before the duplicate, kept rows all empty", filterFirst, duplicatePair, listOf("7", null, null), "VALUE_ERROR"),
            OrderedScanCase("4 unconvertible starred row after the duplicate", dupBeforeStar, duplicatePair, listOf("abc"), "VALUE_ERROR"),
            OrderedScanCase("5 unconvertible starred row before the duplicate", starBeforeDup, duplicatePair, listOf("abc"), "VALUE_ERROR"),
            OrderedScanCase("6 unconvertible direct operand before the duplicate", dupBeforeStar,
                listOf(QTY to "abc", BACK to "5"), listOf("5"), "VALUE_ERROR"),
            OrderedScanCase("7 a starred row is the duplicate's partner", dupBeforeStar,
                listOf(QTY to "5", BACK to "9"), listOf("5"), "VALUE_ERROR"),
            OrderedScanCase("8 two EQUAL unconvertible Numbers do not duplicate", render(Dsl.fieldValuesNotUnique(m.field(QTY), m.field(BACK))),
                listOf(QTY to "abc", BACK to "abc"), emptyList(), null),
            OrderedScanCase("9 cross-scale equal, starred operand first",
                render(Dsl.fieldValuesNotUnique(counts, m.field(PRICE))),
                listOf(PRICE to "5.00"), listOf("5"), "VALUE_ERROR", errorRel = "../UnitPrice"),
            OrderedScanCase("10 cross-scale equal, direct Number operand first",
                render(Dsl.fieldValuesNotUnique(m.field(PRICE), counts)),
                listOf(PRICE to "5.00"), listOf("5"), "VALUE_ERROR", errorRel = "../UnitPrice"),
            // The same skip, on a second declared kind — so the interpreter's kind-agnostic fix is not implemented on
            // a Number-only basis. OrderDate/DeliveryDate/Items*/PromiseDate all declare `yyyy-MM-dd`, the one shared
            // format the operator's admission gate requires of a temporal list.
            OrderedScanCase("11 two EQUAL unconvertible Dates do not duplicate",
                render(Dsl.fieldValuesNotUnique(m.field(ORDER_DATE), m.field(DELIVERY_DATE))),
                listOf(ORDER_DATE to "xx", DELIVERY_DATE to "xx"), emptyList(), null,
                errorRel = "../OrderDate"),
            OrderedScanCase("12 unconvertible Date before a Date duplicate",
                render(Dsl.fieldValuesNotUnique(m.field(ORDER_DATE), m.field(DELIVERY_DATE), m.flatten("/Order/Items*/PromiseDate"))),
                listOf(ORDER_DATE to "xx", DELIVERY_DATE to "2026-01-01"), listOf("2026-01-01"), "VALUE_ERROR",
                errorRel = "../OrderDate", starField = PROMISE),
            // Does a PRESENT-but-unconvertible cell in a filtered operand set the escalation flag? The two accounts
            // ("any specified cell" vs "any cell that yields a comparable value") differ only here, and the answer
            // decides where the flag assignment sits relative to the skip.
            OrderedScanCase("13 filtered operand contributes only unconvertible cells", filterFirst, duplicatePair,
                listOf("7", "abc", "abc"), "VALUE_ERROR"),
            // Kinds the admission gate demonstrably allows (SPEC-2026-08-05-02) whose EVALUATION had no differential:
            // the interpreter keys a compared value by Number/String/Boolean, so a kind arriving as some other
            // resolved value would silently never duplicate. Dates are covered by 11–12; these close DateTime.
            OrderedScanCase("14 two equal DateTimes duplicate",
                render(Dsl.fieldValuesNotUnique(m.field(SCHEDULED_AT), m.field(COMPLETED_AT))),
                listOf(SCHEDULED_AT to "2026-03-01T08:30:00", COMPLETED_AT to "2026-03-01T08:30:00"), emptyList(),
                "VALUE_ERROR", errorRel = "../ScheduledAt"),
            OrderedScanCase("15 two distinct DateTimes do not duplicate",
                render(Dsl.fieldValuesNotUnique(m.field(SCHEDULED_AT), m.field(COMPLETED_AT))),
                listOf(SCHEDULED_AT to "2026-03-01T08:30:00", COMPLETED_AT to "2026-03-01T09:00:00"), emptyList(),
                null, errorRel = "../ScheduledAt"),
        )
        // Every case's three engine readings are collected first and asserted AFTER the loop, so one run reports the
        // whole matrix instead of aborting at the first divergent row.
        val readings = cases.map { case ->
            val kModel = RuntimeLaws.withRule(order, "/Order", "FU", case.errorRel, "FU", case.condition)
            val def = ModelLoader.load(kModel)

            fun documents(): Pair<RuntimeLaws.Doc, io.github.mbackschat.a12.dm.interpreter.eval.Document> {
                val kDoc = RuntimeLaws.doc().group("/Order", 1)
                val iDoc = def.newDocument().group("/Order", 1)
                for ((path, value) in case.direct) if (value != null) {
                    kDoc.field(path, value, 1, 1); iDoc.field(path, value, 1, 1)
                }
                case.counts.forEachIndexed { index, count ->
                    val row = index + 1
                    kDoc.group(ITEMS, 1, row).field(SKU, "K$row", 1, row, 1)
                    iDoc.group(ITEMS, 1, row).field(SKU, "K$row", 1, row, 1)
                    if (count != null) {
                        kDoc.field(case.starField, count, 1, row, 1); iDoc.field(case.starField, count, 1, row, 1)
                    }
                }
                return kDoc to iDoc
            }

            val (dynamicDoc, interpreterDoc) = documents()
            val dynamic = RuntimeLaws.messages(kModel, dynamicDoc).filter { it.code() == "FU" }
                .map { "${it.field()}|${it.type().name}|${it.referenced().sorted()}" }
            val (staticDoc, _) = documents()
            val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
                .messagesModern(staticDoc, null, java.util.Locale.US).filter { it.code() == "FU" }
                .map { "${it.field()}|${it.type().name}|${it.referenced().sorted()}" }
            val interpreter = DmInterpreter(def).validateFull(interpreterDoc).filter { it.code == "FU" }
                .map { "${it.errorPath}|${if (it.type.name == "OMISSION") "OMISSION_ERROR" else "VALUE_ERROR"}|${it.referenced.sorted()}" }

            OrderedScanReading(case, dynamic, javaStatic, interpreter)
        }

        // Agreement first, prediction last: a wrong prediction of mine must not mask an engine divergence.
        assertEquals(
            readings.map { "${it.case.label}: ${it.dynamic}" },
            readings.map { "${it.case.label}: ${it.interpreter}" },
            "kernel/interpreter exact messages",
        )
        assertEquals(
            readings.map { "${it.case.label}: ${it.dynamic}" },
            readings.map { "${it.case.label}: ${it.javaStatic}" },
            "dynamic/static kernel exact messages",
        )
        assertEquals(
            cases.map { "${it.label} => ${it.expectedType ?: "(no message)"}" },
            readings.map { "${it.case.label} => ${it.dynamicType}" },
            "the ordered-scan matrix as the dynamic kernel reports it",
        )
    }

    /**
     * Every operand kind the admission gate demonstrably allows must also **compare** correctly. SPEC-2026-08-05-02
     * measured that the temporal family is admitted (not Date alone, as the entry read it), which makes Time and
     * DateFragment operand lists authorable — and the interpreter keys a compared value by Number/String/Boolean, so
     * a kind arriving as some other resolved value would silently never duplicate and the rule would never fire.
     * Date and DateTime are covered by the ordered-scan matrix; these are the two remaining authorable kinds, each on
     * the existing fixture that already declares a same-format pair (the admission gate requires one shared format).
     *
     * <p>Custom is covered separately by [customTypedOperandsCompareOnTheirAcceptedValue], which needs a registered
     * validator on both sides and so cannot ride this fixture loop.
     */
    @Test fun everyAuthorableTemporalKindComparesOnItsValue() {
        for (case in listOf(
            TemporalKindCase("Time", "models/schedule.dm.json", "/Schedule",
                "/Schedule/WindowStart", "/Schedule/WindowEnd", "08:30:00", "09:15:00"),
            TemporalKindCase("DateFragment yyyy-MM", "models/membership.dm.json", "/Card",
                "/Card/CardExpiry", "/Card/RenewExpiry", "2026-03", "2026-04"),
        )) {
            val model = RuntimeLaws.model(case.modelPath)
            val bound = BoundModel.ofJson(model)
            val condition = render(Dsl.fieldValuesNotUnique(bound.field(case.first), bound.field(case.second)))
            val kModel = RuntimeLaws.withRule(model, case.group, "FU",
                "../" + case.first.substringAfterLast('/'), "FU", condition)
            val def = ModelLoader.load(kModel)
            for ((label, secondValue) in listOf("equal" to case.equal, "distinct" to case.distinct)) {
                val kDoc = RuntimeLaws.doc().group(case.group, 1)
                val iDoc = def.newDocument().group(case.group, 1)
                for (setter in listOf<(String, String) -> Unit>(
                    { p, v -> kDoc.field(p, v, 1, 1) }, { p, v -> iDoc.field(p, v, 1, 1) },
                )) { setter(case.first, case.equal); setter(case.second, secondValue) }
                val kernel = RuntimeLaws.messages(kModel, kDoc).filter { it.code() == "FU" }
                    .map { "${it.field()}|${it.type().name}" }
                val interpreter = DmInterpreter(def).validateFull(iDoc).filter { it.code == "FU" }
                    .map { "${it.errorPath}|${if (it.type.name == "OMISSION") "OMISSION_ERROR" else "VALUE_ERROR"}" }
                assertEquals(kernel, interpreter, "${case.kind} $label — kernel/interpreter message")
                // The positive control: an equal pair MUST fire, or "kernel == interpreter" would be satisfied by two
                // engines that both silently never compare this kind at all.
                assertEquals(if (label == "equal") 1 else 0, kernel.size,
                    "${case.kind} $label — the kernel itself must $label-fire: $kernel")
            }
        }
    }

    private data class TemporalKindCase(
        val kind: String, val modelPath: String, val group: String,
        val first: String, val second: String, val equal: String, val distinct: String,
    )

    /**
     * The last operand kind KERNEL-FINDINGS KF173 proved admissible: **Custom**, which
     * needs a registered validator on both sides and therefore its own fixture. Two custom-typed fields of the same
     * registered type compare on their **accepted** value; a pair the validator REJECTS is skipped like any other
     * unconvertible cell, so two equal rejected values do not duplicate — the Custom analogue of the ordered-scan
     * matrix's rows 8 and 11, and the row that keeps this from being satisfied by an engine that simply never
     * compares Custom at all.
     */
    @Test fun customTypedOperandsCompareOnTheirAcceptedValue() {
        val accepts: (String) -> Boolean = { it.length in 3..5 }
        val model = DocumentModelDraft.named("stock-refs").locale("en_US")
            .rootGroup("Refs", Consumer { g ->
                g.customField("SupplierRef", Consumer<CustomFieldBuilder> { it.type("Code").minLength(3).maxLength(5) })
                g.customField("CarrierRef", Consumer<CustomFieldBuilder> { it.type("Code").minLength(3).maxLength(5) })
            })
            .build().toJson()
        val bound = BoundModel.ofJson(model)
        val condition = render(Dsl.fieldValuesNotUnique(bound.field(SUPPLIER_REF), bound.field(CARRIER_REF)))
        val kModel = RuntimeLaws.withRule(model, "/Refs", "FU", "../SupplierRef", "FU", condition)
        val def = ModelLoader.load(kModel)
        val interpreterTypes = mapOf("Code" to CustomFieldType { value, _ ->
            if (accepts(value)) null else CustomTypeRejection("CODE_INVALID")
        })
        val kernelFactory = object : ICustomFieldTypeFactory {
            override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
                if (name != "Code") Optional.empty() else Optional.of(object : ICustomFieldValidator {
                    override fun validate(value: String, p: ICustomFieldTypeValidationParam, isDisplay: Boolean):
                            Optional<ICustomFieldTypeCheckError> =
                        if (accepts(value)) Optional.empty() else Optional.of(object : ICustomFieldTypeCheckError {
                            override fun getErrorMessage() = "bad"
                            override fun getErrorKey() = "CODE_INVALID"
                        })
                })
        }
        for ((label, values, mustFire) in listOf(
            Triple("two equal accepted values", "abcd" to "abcd", true),
            Triple("two distinct accepted values", "abcd" to "efgh", false),
            Triple("two equal REJECTED values", "ab" to "ab", false),
        )) {
            val kDoc = RuntimeLaws.doc().group("/Refs", 1)
            val iDoc = def.newDocument().group("/Refs", 1)
            for (setter in listOf<(String, String) -> Unit>(
                { p, v -> kDoc.field(p, v, 1, 1) }, { p, v -> iDoc.field(p, v, 1, 1) },
            )) { setter(SUPPLIER_REF, values.first); setter(CARRIER_REF, values.second) }
            val kernel = RuntimeLaws.messages(kModel, kDoc, kernelFactory).filter { it.code() == "FU" }
                .map { "${it.field()}|${it.type().name}" }
            val interpreter = DmInterpreter(def, customFieldTypes = interpreterTypes).validateFull(iDoc)
                .filter { it.code == "FU" }
                .map { "${it.errorPath}|${if (it.type.name == "OMISSION") "OMISSION_ERROR" else "VALUE_ERROR"}" }
            assertEquals(kernel, interpreter, "Custom $label — kernel/interpreter message")
            assertEquals(if (mustFire) 1 else 0, kernel.size,
                "Custom $label — the kernel itself must ${if (mustFire) "" else "not "}fire: $kernel")
        }
    }

    private data class OrderedScanCase(
        val label: String, val condition: String,
        /** The direct `/Order` cells to set (null value = leave empty). */
        val direct: List<Pair<String, String?>>,
        /** One value per `Items` row, in row order (null = leave that row's [starField] empty). */
        val counts: List<String?>,
        /** The message type the dynamic kernel must report, or null for "must not fire". */
        val expectedType: String?,
        /** The rule's error field, which its condition must reference. */
        val errorRel: String = "../Quantity",
        /** The repeating field the starred operand reads. */
        val starField: String = COUNT,
    )

    /** One case's `field|type|referenced` message list as each of the three engines reports it. */
    private data class OrderedScanReading(
        val case: OrderedScanCase,
        val dynamic: List<String>, val javaStatic: List<String>, val interpreter: List<String>,
    ) {
        val dynamicType: String get() = dynamic.singleOrNull()?.split("|")?.get(1) ?: "(no message)"
    }

    private companion object {
        const val QTY = "/Order/Quantity"
        const val BACK = "/Order/BackorderQuantity"
        const val STOCK = "/Order/StockOnHand"
        const val PRICE = "/Order/UnitPrice"
        const val NAME = "/Order/CustomerName"
        const val CODE = "/Order/ProductCode"
        const val ITEMS = "/Order/Items"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
        const val ORDER_DATE = "/Order/OrderDate"
        const val DELIVERY_DATE = "/Order/DeliveryDate"
        const val PROMISE = "/Order/Items/PromiseDate"
        const val SCHEDULED_AT = "/Order/ScheduledAt"
        const val COMPLETED_AT = "/Order/CompletedAt"
        const val SUPPLIER_REF = "/Refs/SupplierRef"
        const val CARRIER_REF = "/Refs/CarrierRef"
    }
}
