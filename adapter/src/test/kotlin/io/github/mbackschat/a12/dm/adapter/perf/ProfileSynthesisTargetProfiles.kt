package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.profile.ModelProfiles
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.util.function.Consumer

/** Builds the synthesis benchmark's target profiles through the same typed APIs used by consumers. */
internal object ProfileSynthesisTargetProfiles {

    fun extract(programsPerKind: Int): String {
        require(programsPerKind > 0) { "programsPerKind must be positive" }
        val structure = DocumentModelDraft.named("SynthesisGrowth$programsPerKind")
            .locale("en_US")
            .rootGroup(ROOT_NAME, Consumer { root ->
                repeat(programsPerKind) { index ->
                    root.field(inputName(index), FieldKind.NUMBER)
                    root.field(outputName(index), FieldKind.NUMBER)
                }
            })
            .build()
            .toJson()
        val model = BoundModel.ofJson(structure)
        val root = model.groupAt(ROOT_PATH).orElseThrow()
        val rules = (0 until programsPerKind).map { index ->
            val input = root.num(inputName(index))
            model.rule(root.ref())
                .name(ruleName(index))
                .errorField(input)
                .`when`(input.notFilled())
                .code("INPUT_${suffix(index)}_REQUIRED")
                .severity(Severity.ERROR)
                .message(Dsl.en("Calibration input is required."))
                .build()
        }
        val computations = (0 until programsPerKind).map { index ->
            model.computation(root.num(outputName(index)))
                .name(computationName(index))
                .calc(root.num(inputName(index)).value())
                .message(Dsl.en("Copy the calibration input."))
                .build()
        }
        return ModelProfiles.extract(model.addAll(rules, computations).toJson())
    }

    private fun inputName(index: Int): String = "Input${suffix(index)}"

    private fun outputName(index: Int): String = "Output${suffix(index)}"

    private fun ruleName(index: Int): String = "RequireInput${suffix(index)}"

    private fun computationName(index: Int): String = "CopyInput${suffix(index)}"

    private fun suffix(index: Int): String = index.toString().padStart(5, '0')

    private const val ROOT_NAME = "Calibration"
    private const val ROOT_PATH = "/$ROOT_NAME"
}
