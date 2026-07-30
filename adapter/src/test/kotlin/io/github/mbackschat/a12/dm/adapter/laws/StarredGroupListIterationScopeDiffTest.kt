package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * §9 / SPEC-2026-07-26-01 runtime complement to the admission matrix: the first-star topology retains only
 * the captured outer prefix. `AtLeastOneGroupFilled(Checks*)` therefore iterates Regions and scans every
 * Checks row within the current Region; it must not mix rows across captured prefixes.
 */
class StarredGroupListIterationScopeDiffTest {

    private val baseJson = DocumentModelDraft.named("regional-survey-runtime").locale("en_US")
        .rootGroup("Survey") { survey ->
            survey.group("Regions") { regions ->
                regions.repeatable(5)
                regions.group("Checks") { checks ->
                    checks.repeatable(5)
                    checks.stringField("Result") { }
                }
            }
        }
        .build().toJson()
    private val bound = BoundModel.ofJson(baseJson)
    private val rule = bound.rule(bound.group(REGIONS))
        .name("FlagPopulatedRegion")
        .errorField(bound.str(RESULT))
        .`when`(Dsl.atLeastOneGroupFilled(bound.flattenGroup("$CHECKS*")))
        .code(CODE)
        .severity(Severity.ERROR)
        .message(Dsl.en("This region has populated checks."))
        .build()
    private val modelJson = TypedRules.inject(baseJson, rule)

    @Test
    fun starredGroupListBindsTheExactCapturedOuterRow() {
        val dynamicDocument = kernelDocument()
        val dynamic = signatures(RuntimeLaws.messages(modelJson, dynamicDocument))
        val java = RuntimeLaws.kernelJavaEngine(modelJson)
        val generated = signatures(java.messagesModern(kernelDocument(), null, Locale.US))
        val definition = ModelLoader.load(modelJson)
        val interpretedDocument = definition.newDocument().group(ROOT, 1)
            .group(REGIONS, 1, 1)
            .group(REGIONS, 1, 2)
            .group(CHECKS, 1, 2, 1)
            .field(RESULT, "reviewed", 1, 2, 1, 1)
        val interpreted = DmInterpreter(definition).validateFull(interpretedDocument)
            .filter { it.code == CODE }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }

        val expected = listOf("/Survey[1]/Regions[2]/Checks[1]/Result|VALUE_ERROR")
        assertEquals(expected, dynamic, "dynamic-Groovy kernel")
        assertEquals(expected, generated, "generated-Java kernel")
        assertEquals(expected, interpreted, "kernel-free interpreter")
    }

    private fun kernelDocument(): RuntimeLaws.Doc = RuntimeLaws.doc().group(ROOT, 1)
        .group(REGIONS, 1, 1)
        .group(REGIONS, 1, 2)
        .group(CHECKS, 1, 2, 1)
        .field(RESULT, "reviewed", 1, 2, 1, 1)

    private fun signatures(messages: List<RuntimeLaws.Msg>): List<String> = messages
        .filter { it.code() == CODE }
        .map { "${it.field()}|${it.type().name}" }

    private companion object {
        const val CODE = "REGION_CHECKS"
        const val ROOT = "/Survey"
        const val REGIONS = "$ROOT/Regions"
        const val CHECKS = "$REGIONS/Checks"
        const val RESULT = "$CHECKS/Result"
    }
}
