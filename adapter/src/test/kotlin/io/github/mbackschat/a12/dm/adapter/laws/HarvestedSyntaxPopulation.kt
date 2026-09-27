package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator

/** P4 preserves helper inputs; host completion is separate from the original minimal model bytes. */
internal object HarvestedSyntaxPopulation {
    private val mapper = ObjectMapper()
    private val validator = RuleValidator()
    private val catalog = OperatorCatalog.load().entries()

    data class Row(val id: String, val text: String, val language: ConditionLanguage, val entry: SyntaxEntry,
            val host: Host, val originalModel: String? = null) {
        fun model(): String = if (entry == SyntaxEntry.OPERATION) {
            validator.inject(host.json, DraftComputation.builder().groupPath(host.group).name("HarvestProbe")
                .computedFieldRelPath(host.target)
                .alternatives(listOf(DraftComputation.Alternative(null, text))).build())
        } else {
            validator.inject(host.json, DraftRule.builder().groupPath(host.group).name("HarvestProbe")
                .errorEntityRelPath(host.target).errorCondition(text).errorCode("HARVEST_PROBE")
                .severity("ERROR").build())
        }
    }

    data class Host(val json: String, val group: String, val target: String)

    private val shapeModel = DocumentModelDraft.named("order-syntax-harvest").locale("en_US")
        .rootGroup("Order") { o -> o
            .enumField("Tier") { e -> e.value("gold").value("silver") }
            .enumField("Premium") { e -> e.value("gold").value("silver") }
            .booleanField("AutoRenew") { }
            .numberField("BaseFee") { n -> n.fractionDigits(2) }
            .numberField("EffectiveFee") { n -> n.fractionDigits(2) }
            .numberField("HarvestTotal") { n -> n.fractionDigits(2) }
            .group("Addons") { a -> a.repeatable(5).stringField("Name") { } } }
        .build().toJson()

    private fun languageModel(json: String, language: ConditionLanguage): String =
        ModelDocument.read(json).setConditionLanguage(if (language == ConditionLanguage.EN) "en_US" else "de_DE")
            .toJson()

    private fun completenessHost(language: ConditionLanguage): Host {
        val german = language == ConditionLanguage.DE
        val group = if (german) "Katalog" else "Catalog"
        val field = if (german) "Preis" else "Price"
        val model = DocumentModelDraft.named("catalog-syntax-harvest").locale(if (german) "de_DE" else "en_US")
            .rootGroup(group) { g -> g.stringField(field) { } }.build().toJson()
        return Host(languageModel(model, language), "/$group", "../$field")
    }

    fun fixedRows(): List<Row> {
        val resource = checkNotNull(javaClass.getResource("/checker-near-miss/harvested-syntax.json"))
        return mapper.readTree(resource.readText()).get("rows").map { value ->
            val source = value.get("source").asText()
            val language = ConditionLanguage.valueOf(value.get("language").asText())
            val host = if (source.startsWith("ConditionCompletenessTest.")) completenessHost(language)
                else Host(languageModel(shapeModel, language), "/Order",
                    if (value.get("entry").asText() == "OPERATION") "../HarvestTotal" else "../Tier")
            Row("$source#${value.get("row").asInt()}", value.get("text").asText(), language,
                SyntaxEntry.valueOf(value.get("entry").asText()), host, value.get("originalModel")?.asText())
        }
    }

    /** The seven scanner hosts named in R3a's contract, kept separate from T1's frozen denominator. */
    fun scannerRows(): List<Row> {
        val model = DocumentModelDraft.named("policy-scanner-hosts").locale("en_US")
            .rootGroup("Policy") { p -> p.stringField("Tier") { }.stringField("Plate") { }
                .group("Garage") { g -> g.stringField("Name") { } }
                .group("Coverage") { g -> g.stringField("Code") { } }
                .group("Claims") { g -> g.repeatable(9).numberField("Amount") { n -> n.fractionDigits(2) }
                    .stringField("Paid") { } } }.build().toJson()
        val texts = listOf(
            "Sum(Claims*/Amount Having [Claims/Paid] == \"Y\") > 0",
            "AllGroupsFilled(Garage @From 2, Coverage)",
            "AllGroupsFilled(Garage For 2, Coverage)",
            "AllGroupsFilled(Garage -> Band, Coverage)",
            "AllFieldsFilled([Tier], Plate)",
            "AllGroupsFilled(\"Garage\", Coverage)",
            "AllGroupsFilled(\$Claims, Coverage)",
        )
        return texts.mapIndexed { index, text -> Row("scanner#${index + 1}", text,
            ConditionLanguage.EN, SyntaxEntry.CONDITION, Host(model, "/Policy", "../Tier")) }
    }

    /** The same live source as ConditionCorpus; harvested German texts verify this projection in T1. */
    fun catalogRows(): List<Row> = catalog.flatMap { operator ->
        val fixture = CatalogHosting.fixtureOf(operator)
        val model = fixture.json
        val fields = ModelIndex.ofSelfContained(model).fieldPaths()
            .filter { it.startsWith("${fixture.ruleGroup}/") }.toList()
        listOfNotNull(
            operator.validExample()?.dsl()?.let { Triple("validExample", it, SyntaxEntry.CONDITION) },
            operator.invalidExample()?.dsl()?.let { Triple("invalidExample", it, SyntaxEntry.CONDITION) },
            operator.computationExample()?.operation()?.let { Triple("computationExample", it, SyntaxEntry.OPERATION) },
        ).flatMap { (member, english, entry) ->
            val target = if (entry == SyntaxEntry.OPERATION) operator.computationExample().computedField()
                else fields.firstOrNull { path ->
                val relative = path.removePrefix("${fixture.ruleGroup}/")
                Regex("(?<![A-Za-z0-9_])${Regex.escape(relative)}(?![A-Za-z0-9_])").containsMatchIn(english)
            } ?: fields.first()
            ConditionLanguage.entries.map { language ->
                Row("catalog#${operator.id()}:$member:$language", if (language == ConditionLanguage.EN) english
                    else german(english), language, entry,
                    Host(languageModel(model, language), fixture.ruleGroup,
                        "../${target.removePrefix("${fixture.ruleGroup}/")}"))
            }
        }
    }

    private fun german(text: String): String {
        val spellings = catalog.mapNotNull { operator -> operator.keywords()?.let { it.en() to it.de() } }
            .filter { (english, german) -> english != null && german != null }
            .toMap() + mapOf("RuleGroup" to "RegelKontext", "@From" to "@Von")
        val out = StringBuilder(text)
        tokenize(text).asReversed().filter { it.kind == TokenKind.NAME }.forEach { token ->
            val marked = token.start > 0 && text[token.start - 1] == '@'
            val start = if (marked) token.start - 1 else token.start
            spellings[if (marked) "@${token.text}" else token.text]?.let { out.replace(start, token.end, it) }
        }
        return out.toString()
    }
}
