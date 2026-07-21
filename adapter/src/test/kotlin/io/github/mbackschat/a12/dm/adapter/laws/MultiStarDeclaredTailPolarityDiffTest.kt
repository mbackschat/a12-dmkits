package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for aggregate polarity across every declared repetition level below a multi-star binding.
 * Missing capacity at an outer, middle, or leaf starred group keeps a fired Sum comparison omission-typed; only
 * the complete declared Cartesian domain is fixed/value-typed. The implementation must detect the tail without
 * enumerating that Cartesian domain.
 */
class MultiStarDeclaredTailPolarityDiffTest {

    private val base = DocumentModelDraft.named("portfolio").locale("en_US")
        .rootGroup("Portfolio") { portfolio ->
            portfolio.group("Regions") { regions ->
                regions.repeatable(2)
                regions.group("Accounts") { accounts ->
                    accounts.repeatable(2)
                    accounts.group("Lots") { lots ->
                        lots.repeatable(2).numberField("Amount") { it.fractionDigits(0, 0) }
                    }
                }
            }
        }
        .build()
        .toJson()
    private val model = BoundModel.ofJson(base)
    private val amount = model.num(AMOUNT)
    private val allLevelsRule = rule(
        model.group(ROOT),
        Dsl.sum(model.flatten("$REGIONS*/Accounts*/Lots*/Amount")).gt(-1),
    )
    private val boundRegionRule = rule(
        model.group(REGIONS),
        Dsl.sum(model.flatten("$REGIONS/Accounts*/Lots*/Amount")).gt(-1),
    )

    @Test fun outerDeclaredTailKeepsOmissionPolarity() = agree(allLevelsRule, rowsForRegions(1), "OMISSION_ERROR", "outer tail")

    @Test fun middleDeclaredTailKeepsOmissionPolarity() = agree(
        allLevelsRule,
        buildList { for (region in 1..2) for (lot in 1..2) add(Row(region, 1, lot)) },
        "OMISSION_ERROR",
        "middle tail",
    )

    @Test fun leafDeclaredTailKeepsOmissionPolarity() = agree(
        allLevelsRule,
        buildList { for (region in 1..2) for (account in 1..2) add(Row(region, account, 1)) },
        "OMISSION_ERROR",
        "leaf tail",
    )

    @Test fun exhaustingEveryStarredLevelMakesTheResultFixed() =
        agree(allLevelsRule, rowsForRegions(1, 2), "VALUE_ERROR", "full 2×2×2 domain")

    @Test fun aBoundOuterLevelIsNotPartOfTheStarredDeclaredTail() =
        agree(
            boundRegionRule,
            rowsForRegions(1),
            "VALUE_ERROR",
            "Region[1] bound; Accounts/Lots exhausted",
            // Isolate the instantiated row: the kernel also emits the rule for its absent declared Region[2],
            // a separate rule-row-domain contract unrelated to this aggregate's first-star boundary.
            focusErrorPath = "/Portfolio[1]/Regions[1]/",
        )

    private fun rowsForRegions(vararg regions: Int): List<Row> = buildList {
        for (region in regions) for (account in 1..2) for (lot in 1..2) add(Row(region, account, lot))
    }

    private fun rule(group: io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef, condition: io.github.mbackschat.a12.dm.rulekit.dsl.Condition): Rule =
        model.rule(group).name(CODE).code(CODE).errorField(amount).`when`(condition)
            .severity(Severity.ERROR).message(Dsl.en("The aggregate remains positive.")).build()

    private fun agree(
        rule: Rule,
        rows: List<Row>,
        expectedType: String,
        scenario: String,
        expectedCount: Int = 1,
        expectedTypes: Set<String> = setOf(expectedType),
        focusErrorPath: String? = null,
    ) {
        val modelJson = TypedRules.inject(base, rule)
        val definition = ModelLoader.load(modelJson)
        val dynamicDocument = RuntimeLaws.doc()
        populate(rows, dynamicDocument, definition.newDocument())
        val dynamic = RuntimeLaws.messages(modelJson, dynamicDocument)
            .filter { it.code() == CODE && (focusErrorPath == null || focusErrorPath in it.field()) }
            .map { "${it.code()}|${it.field()}|${it.type().name}" }.sorted()

        val staticEngine = RuntimeLaws.kernelJavaEngine(modelJson)
        val staticDocument = RuntimeLaws.doc()
        populate(rows, staticDocument, definition.newDocument())
        val generated = staticEngine.messages(staticEngine.instance(staticDocument))
            .filter { it.code() == CODE && (focusErrorPath == null || focusErrorPath in it.field()) }
            .map { "${it.code()}|${it.field()}|${it.type().name}" }.sorted()

        val interpreterDocument = definition.newDocument()
        populate(rows, RuntimeLaws.doc(), interpreterDocument)
        val interpreted = DmInterpreter(definition).validateFull(interpreterDocument)
            .filter { it.code == CODE && (focusErrorPath == null || focusErrorPath in it.errorPath) }
            .map {
                val type = if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"
                "${it.code}|${it.errorPath}|$type"
            }
            .sorted()

        assertEquals(expectedCount, dynamic.size, "$scenario — normative dynamic-Groovy firing count")
        assertEquals(expectedTypes, dynamic.map { it.substringAfterLast('|') }.toSet(), "$scenario — normative polarity")
        assertEquals(dynamic, generated, "$scenario — generated Java")
        assertEquals(dynamic, interpreted, "$scenario — interpreter")
    }

    private fun populate(rows: List<Row>, kernel: RuntimeLaws.Doc, interpreter: Document) {
        kernel.group(ROOT, 1)
        interpreter.group(ROOT, 1)
        for ((region, account, lot) in rows) {
            kernel.group(REGIONS, 1, region)
                .group(ACCOUNTS, 1, region, account)
                .group(LOTS, 1, region, account, lot)
                .field(AMOUNT, "1", 1, region, account, lot, 1)
            interpreter.group(REGIONS, 1, region)
                .group(ACCOUNTS, 1, region, account)
                .group(LOTS, 1, region, account, lot)
                .field(AMOUNT, "1", 1, region, account, lot, 1)
        }
    }

    private data class Row(val region: Int, val account: Int, val lot: Int)

    private companion object {
        const val CODE = "PORTFOLIO_SUM"
        const val ROOT = "/Portfolio"
        const val REGIONS = "$ROOT/Regions"
        const val ACCOUNTS = "$REGIONS/Accounts"
        const val LOTS = "$ACCOUNTS/Lots"
        const val AMOUNT = "$LOTS/Amount"
    }
}
