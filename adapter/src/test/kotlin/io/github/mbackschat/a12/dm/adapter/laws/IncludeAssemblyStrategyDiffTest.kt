package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.ModelReader
import io.github.mbackschat.a12.dm.core.ModelResolver
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.modelkit.MountOptions
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * IF146 assembly lock: production include preparation is compared with kernel expansion, then the prepared
 * models execute through Groovy-dynamic, generated Java, and the interpreter. The fixture combines mount-depth
 * 0/1/n rules, a nested computation whose common/alternative/operation fragments use a pre-mount short name,
 * a bare root-group reference, and a legal absolute `$` correlation inside `Having`.
 */
class IncludeAssemblyStrategyDiffTest {

    @Test
    fun singleRootMountAgreesAcrossBothKernelStrategies() = agree(modelRoot = false)

    @Test
    fun modelRootMountAgreesAcrossBothKernelStrategies() = agree(modelRoot = true)

    private fun agree(modelRoot: Boolean) {
        val included = includedModel()
        val raw = policy(modelRoot)
        val resolver = ModelResolver { ref -> if (ref == "benefits") included else null }
        val kernel = CorpusModelPreparer.prepareKernelJson(raw, resolver)
        val interpreter = ModelReader.expand(raw, resolver).toJson()
        val placements = placements(modelRoot)

        val dynamicValidation = CorpusEngines.groovyDynamic(kernel, placements, Op.ValidateFull)
        val staticValidation = CorpusEngines.javaStatic(kernel, placements, Op.ValidateFull)
        val interpretedValidation = CorpusEngines.interpret(interpreter, placements, Op.ValidateFull)
        assertTrue(dynamicValidation.any { it.startsWith("OUTER|") }, "the mounted Having/$ rule must fire")
        assertTrue(dynamicValidation.any { it.startsWith("ROOT|") }, "the mounted bare-root rule must fire")
        assertTrue(dynamicValidation.any { it.startsWith("LEVEL|") }, "the depth-1 rule must fire")
        assertTrue(dynamicValidation.any { it.startsWith("DEEP|") }, "the depth-n rule must fire")
        assertEquals(dynamicValidation, staticValidation)
        assertEquals(dynamicValidation, interpretedValidation)

        val dynamicCompute = CorpusEngines.groovyDynamic(kernel, placements, Op.Compute)
        val staticCompute = CorpusEngines.javaStatic(kernel, placements, Op.Compute)
        val interpretedCompute = CorpusEngines.interpret(interpreter, placements, Op.Compute)
        assertTrue(dynamicCompute.any { it.endsWith("/Total|VALUE|5") },
            "the short-name-bound nested computation must produce a value: $dynamicCompute")
        assertEquals(dynamicCompute, staticCompute)
        assertEquals(dynamicCompute, interpretedCompute)
    }

    private fun includedModel(): String {
        val structure = DocumentModelDraft.named("benefits").locale("en_US")
            .rootGroup("Benefits") { root ->
                root.numberField("Threshold") { it.fractionDigits(0) }
                    .stringField("Note") { }
                    .group("Lines") { lines ->
                        lines.repeatable(5)
                            .stringField("Key") { }
                            .numberField("Amount") { it.fractionDigits(0) }
                            .indexField("Key")
                    }
                    .group("Level") { level ->
                        level.numberField("Value") { it.fractionDigits(0) }
                            .group("Deep") { deep ->
                                deep.numberField("Marker") { it.fractionDigits(0) }
                                    .numberField("Total") { it.fractionDigits(0) }
                            }
                    }
            }.build().toJson()

        var model = BoundModel.ofJson(structure)
        var json = model.add(model.rule(model.group("/Benefits")).name("OuterMatch")
            .errorField(model.str("/Benefits/Note").field())
            .`when`(model.str("/Benefits/Note").filled().and(
                Dsl.sum(model.flatten("/Benefits/Lines*/Amount").having(
                    model.num("/Benefits/Lines/Amount").eq(model.num("/Benefits/Threshold").outer()),
                )).gt(0),
            ))
            .code("OUTER").severity(Severity.ERROR).message(en("Outer match.")).build()).toJson()

        model = BoundModel.ofJson(json)
        json = model.add(model.rule(model.group("/Benefits")).name("RootPresent")
            .errorField(model.str("/Benefits/Note").field())
            .`when`(model.str("/Benefits/Note").filled().and(model.group("/Benefits").filled()))
            .code("ROOT").severity(Severity.ERROR).message(en("Root present.")).build()).toJson()
            .replace("GroupFilled(RuleGroup)", "GroupFilled(/Benefits)")

        model = BoundModel.ofJson(json)
        json = model.add(model.rule(model.group("/Benefits/Level")).name("LevelNegative")
            .errorField(model.num("/Benefits/Level/Value").field())
            .`when`(model.num("/Benefits/Level/Value").filled()
                .and(model.num("/Benefits/Level/Value").lt(0)))
            .code("LEVEL").severity(Severity.ERROR).message(en("Level is negative.")).build()).toJson()
            .replace("FieldFilled(Value)", "FieldFilled(/Benefits/Level/Value)")
            .replace("[Value] < 0", "[/Benefits/Level/Value] < 0")

        model = BoundModel.ofJson(json)
        json = model.add(model.rule(model.group("/Benefits/Level/Deep")).name("DeepNegative")
            .errorField(model.num("/Benefits/Level/Deep/Marker").field())
            .`when`(model.num("/Benefits/Level/Deep/Marker").filled()
                .and(model.num("/Benefits/Level/Deep/Marker").lt(0)))
            .code("DEEP").severity(Severity.ERROR).message(en("Deep value is negative.")).build()).toJson()
            .replace("FieldFilled(Marker)", "FieldFilled(/Benefits/Level/Deep/Marker)")
            .replace("[Marker] < 0", "[/Benefits/Level/Deep/Marker] < 0")

        model = BoundModel.ofJson(json)
        return model.add(model.computation(model.num("/Benefits/Level/Deep/Total")).name("CopyThreshold")
            .commonPrecondition(model.num("/Benefits/Level/Deep/Marker").filled()
                .and(model.num("/Benefits/Threshold").gt(0)))
            .`when`(model.num("/Benefits/Level/Deep/Marker").lt(0)
                .and(model.num("/Benefits/Threshold").gt(0)))
            .calc(model.num("/Benefits/Threshold").value())
            .message(en("Total follows threshold.")).build()).toJson()
            .replace("[../../Threshold]", "[Threshold]")
    }

    private fun policy(modelRoot: Boolean): String {
        val options = if (modelRoot) MountOptions.single().asModelRoot() else MountOptions.single()
        return DocumentModelDraft.named("policy").locale("en_US")
            .include("benefits", "benefits")
            .rootGroup("Policy") { policy ->
                policy.field("PolicyNo", FieldKind.STRING)
                    .numberField("Threshold") { it.fractionDigits(0) }
                    .mount("Cover", "benefits", options)
            }.build().toJson()
    }

    private fun placements(modelRoot: Boolean): List<Placement> {
        val root = if (modelRoot) "/Policy/Cover/Benefits" else "/Policy/Cover"
        val rootReps = if (modelRoot) listOf(1, 1, 1) else listOf(1, 1)
        fun group(path: String, reps: List<Int>) = Placement(PlacementKind.GROUP, path, reps)
        fun field(path: String, value: String, reps: List<Int>) =
            Placement(PlacementKind.FIELD, path, reps + 1, value)
        return listOf(
            group("/Policy", listOf(1)),
            field("/Policy/Threshold", "99", listOf(1)),
            group("/Policy/Cover", listOf(1, 1)),
        ) + (if (modelRoot) listOf(group(root, rootReps)) else emptyList()) + listOf(
            field("$root/Threshold", "5", rootReps),
            field("$root/Note", "present", rootReps),
            group("$root/Lines", rootReps + 1),
            field("$root/Lines/Key", "A", rootReps + 1),
            field("$root/Lines/Amount", "5", rootReps + 1),
            group("$root/Level", rootReps + 1),
            field("$root/Level/Value", "-1", rootReps + 1),
            group("$root/Level/Deep", rootReps + listOf(1, 1)),
            field("$root/Level/Deep/Marker", "-1", rootReps + listOf(1, 1)),
        )
    }
}
