package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter

/**
 * The typed premium carrier behind the 2026-10-02 static operand grids (SPEC-2026-10-01-05, -07, SPEC-2026-10-02-03).
 *
 * One field of each kind sits at the root so a comparison or an extrema leader can be moved across kinds on a
 * single model. Two Number families are kept apart on purpose: `Total`, `Base`, `Fee` and `Levy` are scale 0 with
 * `Ratio` the scale-2 odd one out, for the target-read order; `Amount`, `Rate` and `Result` are scale 2 with
 * `Units` the scale-0 odd one out, for the division walk. Operations and conditions stay raw because the refused
 * shapes, which the typed builder will not spell, are the measured input.
 */
internal object PremiumProbes {
    fun premium(baseYear: Int? = null): String {
        return DocumentModelDraft.named("premium-operand-probes").locale("en_US")
            .let { if (baseYear == null) it else it.baseYear(baseYear) }
            .rootGroup("Premium") { p -> p
                .stringField("Note") { }.stringField("Note2") { }.stringField("Agent") { }.stringField("Broker") { }
                .numberField("Total") { it.fractionDigits(0) }.numberField("Base") { it.fractionDigits(0) }
                .numberField("Fee") { it.fractionDigits(0) }.numberField("Levy") { it.fractionDigits(0) }
                .numberField("Ratio") { it.fractionDigits(2) }
                .numberField("Amount") { it.fractionDigits(2) }.numberField("Rate") { it.fractionDigits(2) }
                .numberField("Result") { it.fractionDigits(2) }.numberField("Units") { it.fractionDigits(0) }
                .booleanField("Active") { }.confirmField("Agreed") { }
                .enumField("Tier") { it.value("1").value("2") }
                .enumField("Band") { e -> e.value("1") { it.label("en_US", "Low") }.value("2") { it.label("en_US", "High") } }
                .dateField("Start") { it.format("dd.MM.yyyy") }.timeField("Clock") { it.format("HH:mm:ss") }
                .dateTimeField("Stamp") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                .dateRangeField("Term") { it.format("dd.MM.yyyy").separator("-") }
                .group("Riders") { it.numberField("Count") { f -> f.fractionDigits(0) }.stringField("Rider") { } }
                .group("Extras") { it.stringField("Label") { } }
                .group("Lines") { lines -> lines.repeatable(3)
                    .numberField("LineAmount") { it.fractionDigits(2) }.numberField("LineRate") { it.fractionDigits(2) }
                    .numberField("LineResult") { it.fractionDigits(2) } }
            }.build().toJson()
    }

    /** One guarded or unconditional alternative; a null [precondition] is unconditional. */
    data class Alt(val precondition: String?, val operation: String)

    fun computation(base: String, group: String, target: String, alternatives: List<Alt>,
        suppress: List<String> = emptyList()): String = ModelWriter.addComputation(base, DraftComputation.builder()
            .groupPath(group).name("ProbeComp").computedFieldRelPath("../$target")
            .alternatives(alternatives.map { DraftComputation.Alternative(it.precondition, it.operation) })
            .errorCodesToSuppress(suppress).build(), listOf(Dsl.en("Premium recomputed.")))

    fun rule(base: String, errorField: String, condition: String): String =
        RuntimeLaws.withRule(base, ROOT, "ProbeRule", "../$errorField", "PROBE_RULE", condition)

    const val ROOT = "/Premium"
}
